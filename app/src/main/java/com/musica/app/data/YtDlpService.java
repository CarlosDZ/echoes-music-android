package com.musica.app.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kotlin.Unit;

/**
 * Thin async wrapper around youtubedl-android. Every call runs the yt-dlp
 * subprocess off the main thread and delivers its result back on it. It knows
 * nothing about the music library: callers decide where a downloaded file lands.
 *
 * Assumes YoutubeDL.init()/FFmpeg.init() already ran (see {@link com.musica.app.MusicApp}).
 */
public final class YtDlpService {

    private static final String TAG = "YtDlpService";

    private static YtDlpService instance;

    public static synchronized YtDlpService get() {
        if (instance == null) instance = new YtDlpService();
        return instance;
    }

    // Cached pool so a search isn't blocked behind an in-flight download.
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());

    private YtDlpService() {}

    // ---- Search -----------------------------------------------------------

    /** One lightweight entry in a search result list. */
    public static final class SearchResult {
        public final String id;
        public final String title;
        public final String uploader;
        public final int durationSeconds;

        SearchResult(String id, String title, String uploader, int durationSeconds) {
            this.id = id;
            this.title = title;
            this.uploader = uploader;
            this.durationSeconds = durationSeconds;
        }

        /** YouTube watch URL for this result. */
        public String url() {
            return "https://www.youtube.com/watch?v=" + id;
        }
    }

    public interface SearchCallback {
        void onResults(List<SearchResult> results);
        void onError(Exception e);
    }

    /**
     * Runs a YouTube search and returns up to {@code max} results. Uses
     * --flat-playlist so it just lists titles without extracting each video's
     * streams (fast, no per-video round trip). Callback fires on the main thread.
     */
    public void search(String query, int max, SearchCallback cb) {
        io.execute(() -> {
            try {
                YoutubeDLRequest req = new YoutubeDLRequest("ytsearch" + max + ":" + query);
                req.addOption("--flat-playlist");
                req.addOption("--dump-json");
                YoutubeDLResponse resp = YoutubeDL.getInstance().execute(req);
                List<SearchResult> results = parseSearch(resp.getOut());
                main.post(() -> cb.onResults(results));
            } catch (Exception e) {
                Log.e(TAG, "search failed", e);
                main.post(() -> cb.onError(e));
            }
        });
    }

    /** --dump-json prints one JSON object per line; parse each into a result. */
    private List<SearchResult> parseSearch(String out) {
        List<SearchResult> results = new ArrayList<>();
        for (String raw : out.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            try {
                JSONObject o = new JSONObject(line);
                String id = o.optString("id", "");
                if (id.isEmpty()) continue;
                String title = o.optString("title", "(untitled)");
                String uploader = o.optString("uploader", o.optString("channel", ""));
                int duration = (int) o.optDouble("duration", 0);
                results.add(new SearchResult(id, title, uploader, duration));
            } catch (JSONException e) {
                // A stray non-JSON line (e.g. a warning on stdout) must not sink
                // the whole search; just skip it.
                Log.w(TAG, "skipping unparseable search line", e);
            }
        }
        return results;
    }

    // ---- Download ---------------------------------------------------------

    public interface DownloadCallback {
        /** @param percent 0..100, {@code etaSeconds} -1 when unknown. */
        void onProgress(float percent, long etaSeconds);
        void onComplete(File file);
        void onError(Exception e);
    }

    /**
     * Downloads a video's audio as MP3 into {@code targetDir}, named "&lt;id&gt;.mp3".
     * Progress/completion fire on the main thread. Pass the same {@code videoId}
     * to {@link #cancel} to abort. Requires the bundled ffmpeg artifact.
     */
    public void download(String videoId, File targetDir, DownloadCallback cb) {
        io.execute(() -> {
            try {
                if (!targetDir.exists() && !targetDir.mkdirs()) {
                    throw new IllegalStateException("could not create " + targetDir);
                }
                File output = new File(targetDir, videoId + ".mp3");

                YoutubeDLRequest req = new YoutubeDLRequest(
                        "https://www.youtube.com/watch?v=" + videoId);
                req.addOption("-f", "bestaudio/best");   // audio-only stream, fall back to best
                req.addOption("-x");                      // extract the audio track...
                req.addOption("--audio-format", "mp3");   // ...as mp3 (needs ffmpeg)
                req.addOption("--audio-quality", "0");    // best VBR quality
                req.addOption("--no-playlist");           // a watch URL, never its playlist
                // %(ext)s resolves to mp3 after extraction, so the final file is <id>.mp3.
                req.addOption("-o", new File(targetDir, videoId + ".%(ext)s").getAbsolutePath());

                // The progress lambda is a Kotlin (Float,Long,String)->Unit; from
                // Java it's a SAM, hence the Unit.INSTANCE return.
                YoutubeDL.getInstance().execute(req, videoId, (progress, eta, line) -> {
                    main.post(() -> cb.onProgress(progress, eta));
                    return Unit.INSTANCE;
                });

                if (!output.exists()) {
                    throw new IllegalStateException("download produced no file: " + output.getName());
                }
                main.post(() -> cb.onComplete(output));
            } catch (Exception e) {
                Log.e(TAG, "download failed for " + videoId, e);
                main.post(() -> cb.onError(e));
            }
        });
    }

    /** Aborts an in-flight download started with the given {@code videoId}. */
    public boolean cancel(String videoId) {
        return YoutubeDL.getInstance().destroyProcessById(videoId);
    }

    // ---- Engine update ----------------------------------------------------

    public interface UpdateCallback {
        /** @param detail the new yt-dlp version on success, or the error message. */
        void onDone(boolean ok, String detail);
    }

    /**
     * Downloads the latest yt-dlp (nightly channel) from GitHub, replacing the
     * bundled one at runtime. This is how YouTube breakages get fixed without a
     * new app release: when the player changes and downloads start failing, a
     * fresh yt-dlp usually restores them. Callback fires on the main thread.
     *
     * @param appContext application context (safe to retain).
     */
    public void updateEngine(Context appContext, UpdateCallback cb) {
        io.execute(() -> {
            try {
                YoutubeDL.getInstance()
                        .updateYoutubeDL(appContext, YoutubeDL.UpdateChannel._NIGHTLY);
                String version = YoutubeDL.getInstance().version(appContext);
                main.post(() -> cb.onDone(true, version));
            } catch (Exception e) {
                Log.e(TAG, "yt-dlp update failed", e);
                main.post(() -> cb.onDone(false, e.getMessage()));
            }
        });
    }
}
