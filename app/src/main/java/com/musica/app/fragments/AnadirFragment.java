package com.musica.app.fragments;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.musica.app.R;
import com.musica.app.MusicApp;
import com.musica.app.data.DuplicateCheck;
import com.musica.app.data.LocalRepository;
import com.musica.app.data.RemoteRepository;
import com.musica.app.data.Tags;
import com.musica.app.data.YtDlpService;
import com.musica.app.data.YtMetadata;
import com.musica.app.model.Song;
import com.musica.app.ui.YtResultAdapter;
import com.musica.app.databinding.FragmentAnadirBinding;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Añadir música": get a song either by searching/downloading it from YouTube
 * (on-device) or by picking an MP3 from the phone. Either source opens a modal
 * {@link SongMetadataSheet} pre-filled with title/artists; on confirm the song
 * is ingested to local, the server, or both. The destination prompt is skipped
 * when no server is configured. Both backends converge on single-ingest.
 */
public class AnadirFragment extends Fragment {

    private enum Dest { LOCAL, SERVER, BOTH }

    private FragmentAnadirBinding b;
    private LocalRepository local;
    private RemoteRepository remote;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Snackbar working;   // indefinite "processing…" snackbar, replaced by the result

    private ActivityResultLauncher<String[]> picker;
    private Uri pickedUri;

    private static final int YT_MAX_RESULTS = 8;
    private YtDlpService yt;
    private YtResultAdapter ytResults;
    private boolean ytBusy;
    private boolean ytEngineUpdated;   // yt-dlp refreshed this session (auto-recovery)
    private File ytTempFile;   // last YT download awaiting save; deleted on success/leave

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        picker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> { if (uri != null) onPicked(uri); });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentAnadirBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        local = new LocalRepository(requireContext());
        remote = new RemoteRepository(requireContext());

        b.btnPick.setOnClickListener(v -> picker.launch(new String[]{"audio/mpeg"}));

        yt = YtDlpService.get();
        ytResults = new YtResultAdapter(this::onYtResultPicked);
        b.rvYtResults.setLayoutManager(new LinearLayoutManager(requireContext()));
        b.rvYtResults.setAdapter(ytResults);
        b.btnYtSearch.setOnClickListener(v -> onYtSearch());
        b.etYtQuery.setOnEditorActionListener((v, actionId, e) -> {
            onYtSearch();
            return true;
        });

        // The metadata sheet returns the confirmed title/artists here.
        getChildFragmentManager().setFragmentResultListener(
                SongMetadataSheet.RESULT_KEY, this, (key, bundle) -> {
                    String title = bundle.getString(SongMetadataSheet.ARG_TITLE, "");
                    ArrayList<String> artists = bundle.getStringArrayList(SongMetadataSheet.ARG_ARTISTS);
                    onMetadataConfirmed(title, artists == null ? new ArrayList<>() : artists);
                });
    }

    // ------------------------- Source: pick an MP3 -------------------------

    private void onPicked(Uri uri) {
        deleteYtTemp();   // switching to a file pick abandons any pending YT download
        pickedUri = uri;
        final Context app = requireContext().getApplicationContext();
        io.execute(() -> {
            Tags.Read tags = null;
            try {
                tags = Tags.read(app, uri);
            } catch (RuntimeException ignored) {
                // unreadable tags → open the sheet blank for the user to fill
            }
            List<String> suggestions = mergedArtists();
            String title = tags != null && tags.title() != null ? tags.title() : "";
            ArrayList<String> artists = new ArrayList<>();
            if (tags != null) artists.addAll(tags.artists());
            postOpenSheet(title, artists, suggestions);
        });
    }

    // ------------------------- Source: YouTube -------------------------

    private void onYtSearch() {
        String query = b.etYtQuery.getText() == null ? "" : b.etYtQuery.getText().toString().trim();
        if (query.isBlank() || ytBusy) return;

        // Gate on the one-time yt-dlp unpack (see MusicApp): run now if ready,
        // wait behind a "preparing" message if still initializing, or bail if it
        // failed this session.
        MusicApp app = (MusicApp) requireContext().getApplicationContext();
        switch (app.ytState()) {
            case READY -> runYtSearch(query);
            case FAILED -> setYtStatus(getString(R.string.yt_unavailable), R.color.echoes_error);
            case INITIALIZING -> {
                setYtSearchSpinner(true);
                setYtStatus(getString(R.string.yt_preparing), R.color.echoes_on_surface_variant);
                app.whenYtReady(ok -> {
                    if (b == null) return;
                    if (ok) {
                        runYtSearch(query);
                    } else {
                        setYtSearchSpinner(false);
                        setYtStatus(getString(R.string.yt_unavailable), R.color.echoes_error);
                    }
                });
            }
        }
    }

    private void runYtSearch(String query) {
        setYtSearchSpinner(true);
        setYtStatus("", R.color.echoes_on_surface_variant);
        b.rvYtResults.setVisibility(View.GONE);
        yt.search(query, YT_MAX_RESULTS, new YtDlpService.SearchCallback() {
            @Override
            public void onResults(List<YtDlpService.SearchResult> results) {
                if (b == null) return;
                setYtSearchSpinner(false);
                if (results.isEmpty()) {
                    setYtStatus(getString(R.string.yt_no_results),
                            R.color.echoes_on_surface_variant);
                    return;
                }
                ytResults.submit(results);
                b.rvYtResults.setVisibility(View.VISIBLE);
            }

            @Override
            public void onError(Exception e) {
                if (b == null) return;
                setYtSearchSpinner(false);
                setYtStatus(getString(R.string.yt_search_error), R.color.echoes_error);
            }
        });
    }

    private void onYtResultPicked(YtDlpService.SearchResult r) {
        if (ytBusy) return;
        ytBusy = true;
        b.btnYtSearch.setEnabled(false);
        b.rvYtResults.setVisibility(View.GONE);   // lock the list while downloading
        deleteYtTemp();   // drop any previous unsaved download
        startYtDownload(r, false);
    }

    private void startYtDownload(YtDlpService.SearchResult r, boolean isRetry) {
        setYtSearchSpinner(false);
        b.pbYtDownload.setVisibility(View.VISIBLE);
        b.pbYtDownload.setProgress(0);
        setYtStatus(getString(R.string.yt_download_wait), R.color.echoes_on_surface_variant);

        File tempDir = new File(requireContext().getCacheDir(), "yt");
        yt.download(r.id, tempDir, new YtDlpService.DownloadCallback() {
            @Override
            public void onProgress(float percent, long etaSeconds) {
                if (b == null) return;
                // percent is -1 during the connecting phase; only move the bar
                // once real progress arrives (the indicator stays determinate).
                if (percent >= 0) {
                    b.pbYtDownload.setProgress((int) percent);
                    setYtStatus(getString(R.string.yt_downloading, (int) percent),
                            R.color.echoes_on_surface_variant);
                }
            }

            @Override
            public void onComplete(File file) {
                if (b == null) return;
                ytBusy = false;
                b.btnYtSearch.setEnabled(true);
                b.pbYtDownload.setVisibility(View.GONE);
                setYtStatus("", R.color.echoes_on_surface_variant);
                // Treat the download like a picked file, then open the sheet.
                ytTempFile = file;
                pickedUri = Uri.fromFile(file);
                prefillFromYoutube(r);
            }

            @Override
            public void onError(Exception e) {
                if (b == null) return;
                // A download failure is usually the bundled yt-dlp being stale
                // vs YouTube's current player. Refresh the engine once per session
                // and retry; if it still fails, surface the error.
                if (!ytEngineUpdated && !isRetry) {
                    updateEngineThenRetry(r);
                } else {
                    ytBusy = false;
                    b.btnYtSearch.setEnabled(true);
                    b.pbYtDownload.setVisibility(View.GONE);
                    b.rvYtResults.setVisibility(View.VISIBLE);   // let the user pick another
                    setYtStatus(getString(R.string.yt_download_error), R.color.echoes_error);
                }
            }
        });
    }

    /** Updates yt-dlp (nightly) once, then retries the download a single time. */
    private void updateEngineThenRetry(YtDlpService.SearchResult r) {
        b.pbYtDownload.setVisibility(View.GONE);
        setYtSearchSpinner(true);
        setYtStatus(getString(R.string.yt_updating_engine), R.color.echoes_on_surface_variant);
        yt.updateEngine(requireContext().getApplicationContext(), (ok, detail) -> {
            if (b == null) return;
            setYtSearchSpinner(false);
            if (ok) {
                ytEngineUpdated = true;
                setYtStatus(getString(R.string.yt_engine_updated),
                        R.color.echoes_on_surface_variant);
                startYtDownload(r, true);
            } else {
                ytBusy = false;
                b.btnYtSearch.setEnabled(true);
                b.rvYtResults.setVisibility(View.VISIBLE);
                setYtStatus(getString(R.string.yt_download_error), R.color.echoes_error);
            }
        });
    }

    /** Cleans the YouTube title/artist, canonicalizes, then opens the sheet. */
    private void prefillFromYoutube(YtDlpService.SearchResult r) {
        io.execute(() -> {
            List<String> suggestions = mergedArtists();
            YtMetadata.Guess guess = YtMetadata.parse(r.title, r.uploader);
            List<String> artists = YtMetadata.canonicalize(guess.artists(), suggestions);
            postOpenSheet(guess.title(), new ArrayList<>(artists), suggestions);
        });
    }

    // ------------------------- Shared metadata sheet -------------------------

    /** Posts sheet opening back to the main thread (callers run off-thread). */
    private void postOpenSheet(String title, ArrayList<String> artists, List<String> suggestions) {
        ArrayList<String> sug = new ArrayList<>(suggestions);
        View root = getView();
        if (root != null) root.post(() -> {
            if (b == null || !isAdded()) return;
            SongMetadataSheet.newInstance(title, artists, sug)
                    .show(getChildFragmentManager(), "song_metadata");
        });
    }

    /** Existing artist names from local + server (silent degrade), deduped. */
    private List<String> mergedArtists() {
        TreeSet<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        set.addAll(local.artists());
        if (remote.isConfigured()) set.addAll(remote.artists());
        return new ArrayList<>(set);
    }

    private void onMetadataConfirmed(String title, List<String> artists) {
        if (pickedUri == null) return;
        if (remote.isConfigured()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.add_dest_title)
                    .setItems(new CharSequence[]{
                            getString(R.string.add_dest_local),
                            getString(R.string.add_dest_server),
                            getString(R.string.add_dest_both)
                    }, (d, which) -> {
                        Dest dest = switch (which) {
                            case 1 -> Dest.SERVER;
                            case 2 -> Dest.BOTH;
                            default -> Dest.LOCAL;
                        };
                        checkThenProcess(dest, title, artists);
                    })
                    .show();
        } else {
            checkThenProcess(Dest.LOCAL, title, artists);
        }
    }

    // ------------------------- Ingest pipeline -------------------------

    /**
     * Before ingesting, look for a near-duplicate on each side this song would
     * land on (local and/or server) and, if found, warn — the byte hash only
     * catches exact files, not the same song from another source.
     */
    private void checkThenProcess(Dest dest, String title, List<String> artists) {
        showWorking(R.string.add_checking_dupes);
        io.execute(() -> {
            List<Song> localMatches = (dest == Dest.LOCAL || dest == Dest.BOTH)
                    ? DuplicateCheck.findSimilar(title, artists, local.allSongs())
                    : List.of();
            List<Song> remoteMatches = (dest == Dest.SERVER || dest == Dest.BOTH)
                    ? DuplicateCheck.findSimilar(title, artists, remote.allSongs())
                    : List.of();
            View root = getView();
            if (root == null) return;
            root.post(() -> {
                if (b == null) return;
                if (localMatches.isEmpty() && remoteMatches.isEmpty()) {
                    process(pickedUri, dest, title, artists);
                } else {
                    dismissWorking();
                    showDuplicateWarning(dest, title, artists, localMatches, remoteMatches);
                }
            });
        });
    }

    private void showDuplicateWarning(Dest dest, String title, List<String> artists,
                                      List<Song> localMatches, List<Song> remoteMatches) {
        StringBuilder msg = new StringBuilder(getString(R.string.add_dupe_intro));
        for (Song s : localMatches) {
            msg.append("\n• «").append(songTitle(s)).append("» (")
                    .append(getString(R.string.dupe_scope_local)).append(")");
        }
        for (Song s : remoteMatches) {
            msg.append("\n• «").append(songTitle(s)).append("» (")
                    .append(getString(R.string.dupe_scope_server)).append(")");
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.add_dupe_title)
                .setMessage(msg.toString())
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.add_dupe_add_anyway,
                        (d, w) -> process(pickedUri, dest, title, artists))
                .show();
    }

    private static String songTitle(Song s) {
        String t = s.title();
        return (t == null || t.isBlank()) ? "sin título" : t;
    }

    private void process(Uri uri, Dest dest, String title, List<String> artists) {
        final Context app = requireContext().getApplicationContext();
        showWorking(R.string.add_processing);

        io.execute(() -> {
            StringBuilder msg = new StringBuilder();
            boolean ok = true;

            if (dest == Dest.LOCAL || dest == Dest.BOTH) {
                try {
                    LocalRepository.IngestResult r = local.ingest(app, uri, title, artists);
                    msg.append(r.created()
                            ? "Local: añadida"
                            : "Local: ya estaba (no duplicada)");
                } catch (IOException | RuntimeException e) {
                    ok = false;
                    msg.append("Local: error — ").append(e.getMessage());
                }
            }

            if (dest == Dest.SERVER || dest == Dest.BOTH) {
                if (msg.length() > 0) msg.append("\n");
                RemoteRepository.UploadResult r = remote.upload(app, uri, title, artists);
                switch (r.status()) {
                    case CREATED -> msg.append("Servidor: subida correctamente");
                    case DUPLICATE -> msg.append("Servidor: ya existía (metadatos actualizados)");
                    case UNAUTHORIZED -> { ok = false; msg.append("Servidor: clave incorrecta"); }
                    case NOT_CONFIGURED -> { ok = false; msg.append("Servidor: no configurado"); }
                    case ERROR -> { ok = false; msg.append("Servidor: error — ").append(r.detail()); }
                }
            }

            final boolean success = ok;
            final String text = msg.toString();
            View root = getView();
            if (root != null) root.post(() -> {
                if (b == null) return;
                result(text);
                // On success the bytes are now in the library (and/or on the
                // server), so the downloaded temp file is no longer needed.
                if (success) deleteYtTemp();
            });
        });
    }

    // ------------------------- Feedback helpers -------------------------

    private void showWorking(int msgRes) {
        dismissWorking();
        if (b == null) return;
        working = Snackbar.make(b.getRoot(), getString(msgRes), Snackbar.LENGTH_INDEFINITE);
        working.show();
    }

    private void dismissWorking() {
        if (working != null) {
            working.dismiss();
            working = null;
        }
    }

    private void result(String text) {
        dismissWorking();
        if (b == null) return;
        Snackbar.make(b.getRoot(), text, Snackbar.LENGTH_LONG).show();
    }

    private void setYtSearchSpinner(boolean show) {
        b.pbYtSearch.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void setYtStatus(String msg, int colorRes) {
        b.tvYtStatus.setText(msg);
        b.tvYtStatus.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), colorRes));
        b.tvYtStatus.setVisibility(msg == null || msg.isBlank() ? View.GONE : View.VISIBLE);
    }

    private void deleteYtTemp() {
        if (ytTempFile != null) {
            //noinspection ResultOfMethodCallIgnored
            ytTempFile.delete();
            ytTempFile = null;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        dismissWorking();
        b = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        deleteYtTemp();
        io.shutdownNow();
    }
}
