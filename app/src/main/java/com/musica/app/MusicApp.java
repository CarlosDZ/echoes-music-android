package com.musica.app;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;

import java.util.ArrayList;
import java.util.List;

/**
 * Application entry point. Its one job today is to warm up youtubedl-android:
 * the first init() after install unpacks a bundled Python (a few seconds), so
 * we run it eagerly on a background thread at startup and expose the readiness
 * state. The YouTube search screen reads that state to gate downloads and, if
 * init is still running, show a "preparing" overlay.
 */
public class MusicApp extends Application {

    private static final String TAG = "MusicApp";

    /** One-time unpack lifecycle of the bundled yt-dlp + ffmpeg. */
    public enum YtState { INITIALIZING, READY, FAILED }

    /** Fired once, on the main thread, when init finishes. */
    public interface YtReadyListener {
        void onYtReady(boolean ok);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<YtReadyListener> waiting = new ArrayList<>();
    private volatile YtState ytState = YtState.INITIALIZING;

    @Override
    public void onCreate() {
        super.onCreate();
        // The unpack blocks for a few seconds only on the very first launch after
        // install (or a library upgrade); afterwards init() is near-instant. Keep
        // it off the main thread either way so startup never janks.
        new Thread(this::initYtdlp, "ytdlp-init").start();
    }

    private void initYtdlp() {
        boolean ok = true;
        try {
            YoutubeDL.getInstance().init(this);
            FFmpeg.getInstance().init(this);
        } catch (Exception e) {
            // Catch broadly: a failed init must never take the whole app down,
            // it just leaves the YouTube feature unavailable for this session.
            Log.e(TAG, "youtubedl-android init failed", e);
            ok = false;
        }
        final boolean ready = ok;
        main.post(() -> finish(ready));
    }

    /** Runs on the main thread: flips the state and drains queued listeners. */
    private void finish(boolean ok) {
        ytState = ok ? YtState.READY : YtState.FAILED;
        for (YtReadyListener l : waiting) l.onYtReady(ok);
        waiting.clear();
    }

    public YtState ytState() {
        return ytState;
    }

    /**
     * Registers a one-shot readiness callback. Call from the main thread. If init
     * already finished it fires immediately; otherwise it fires (once) when init
     * completes. The search screen uses this to know when it can start work.
     */
    public void whenYtReady(YtReadyListener listener) {
        if (ytState != YtState.INITIALIZING) {
            listener.onYtReady(ytState == YtState.READY);
        } else {
            waiting.add(listener);
        }
    }
}
