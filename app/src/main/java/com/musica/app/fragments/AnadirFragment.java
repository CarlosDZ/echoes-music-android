package com.musica.app.fragments;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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
 * "Añadir música": pick one MP3, edit its title/artists (pre-filled from tags),
 * then choose where it goes — local, server, or both. The destination prompt is
 * skipped when no server is configured. Both backends converge on single-ingest.
 */
public class AnadirFragment extends Fragment {

    private enum Dest { LOCAL, SERVER, BOTH }

    private FragmentAnadirBinding b;
    private LocalRepository local;
    private RemoteRepository remote;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private ActivityResultLauncher<String[]> picker;
    private Uri pickedUri;

    private static final int YT_MAX_RESULTS = 8;
    private YtDlpService yt;
    private YtResultAdapter ytResults;
    private boolean ytBusy;
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
        b.btnAddArtist.setOnClickListener(v -> addTypedArtist());
        b.etArtist.setOnItemClickListener((p, v, pos, id) -> addTypedArtist());
        b.etArtist.setOnEditorActionListener((v, actionId, e) -> {
            addTypedArtist();
            return true;
        });
        b.btnSave.setOnClickListener(v -> onSave());

        yt = YtDlpService.get();
        ytResults = new YtResultAdapter(this::onYtResultPicked);
        b.rvYtResults.setLayoutManager(new LinearLayoutManager(requireContext()));
        b.rvYtResults.setAdapter(ytResults);
        b.btnYtSearch.setOnClickListener(v -> onYtSearch());
        b.etYtQuery.setOnEditorActionListener((v, actionId, e) -> {
            onYtSearch();
            return true;
        });
    }

    private void onPicked(Uri uri) {
        deleteYtTemp();   // switching to a file pick abandons any pending YT download
        pickedUri = uri;
        b.tvFile.setText(displayName(uri));
        b.tvStatus.setText("");
        b.etTitle.setText("");
        b.chipArtists.removeAllViews();
        b.form.setVisibility(View.VISIBLE);

        // Pre-fill title/artists from the file's tags, and load existing artist
        // names for the autocomplete — both off the main thread.
        final Context app = requireContext().getApplicationContext();
        io.execute(() -> {
            Tags.Read tags = null;
            try {
                tags = Tags.read(app, uri);
            } catch (RuntimeException ignored) {
                // unreadable tags → leave the form blank for the user to fill
            }
            List<String> suggestions = mergedArtists();
            final Tags.Read t = tags;
            View root = getView();
            if (root != null) root.post(() -> prefill(t, suggestions));
        });
    }

    private void prefill(Tags.Read tags, List<String> suggestions) {
        if (b == null) return;
        b.etArtist.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_1, suggestions));
        if (tags != null) {
            if (tags.title() != null) b.etTitle.setText(tags.title());
            for (String a : tags.artists()) addChip(a);
        }
    }

    /** Existing artist names from local + server (silent degrade), deduped. */
    private List<String> mergedArtists() {
        TreeSet<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        set.addAll(local.artists());
        if (remote.isConfigured()) set.addAll(remote.artists());
        return new ArrayList<>(set);
    }

    // ------------------------- YouTube: search -------------------------

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

    // ------------------------- YouTube: download -------------------------

    private void onYtResultPicked(YtDlpService.SearchResult r) {
        if (ytBusy) return;
        ytBusy = true;
        b.btnYtSearch.setEnabled(false);
        deleteYtTemp();   // drop any previous unsaved download

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
                // Reuse the existing add pipeline: treat the download like a
                // picked file, then let the user confirm the pre-filled metadata.
                ytTempFile = file;
                pickedUri = Uri.fromFile(file);
                prefillFromYoutube(r);
            }

            @Override
            public void onError(Exception e) {
                if (b == null) return;
                ytBusy = false;
                b.btnYtSearch.setEnabled(true);
                b.pbYtDownload.setVisibility(View.GONE);
                setYtStatus(getString(R.string.yt_download_error), R.color.echoes_error);
            }
        });
    }

    /** Fills the shared metadata form from the YouTube result, cleaned + canonicalized. */
    private void prefillFromYoutube(YtDlpService.SearchResult r) {
        b.tvStatus.setText("");
        b.etTitle.setText("");
        b.chipArtists.removeAllViews();
        b.tvFile.setText(r.title);
        b.form.setVisibility(View.VISIBLE);

        io.execute(() -> {
            List<String> suggestions = mergedArtists();
            YtMetadata.Guess guess = YtMetadata.parse(r.title, r.uploader);
            List<String> artists = YtMetadata.canonicalize(guess.artists(), suggestions);
            View root = getView();
            if (root != null) root.post(() -> {
                if (b == null) return;
                b.etArtist.setAdapter(new ArrayAdapter<>(requireContext(),
                        android.R.layout.simple_list_item_1, suggestions));
                b.etTitle.setText(guess.title());
                for (String a : artists) addChip(a);
            });
        });
    }

    private void setYtSearchSpinner(boolean show) {
        b.pbYtSearch.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void setYtStatus(String msg, int colorRes) {
        b.tvYtStatus.setText(msg);
        b.tvYtStatus.setTextColor(ContextCompat.getColor(requireContext(), colorRes));
        b.tvYtStatus.setVisibility(msg == null || msg.isBlank() ? View.GONE : View.VISIBLE);
    }

    private void deleteYtTemp() {
        if (ytTempFile != null) {
            //noinspection ResultOfMethodCallIgnored
            ytTempFile.delete();
            ytTempFile = null;
        }
    }

    private void addTypedArtist() {
        String name = b.etArtist.getText() == null ? "" : b.etArtist.getText().toString().trim();
        if (!name.isBlank()) addChip(name);
        b.etArtist.setText("");
    }

    private void addChip(String name) {
        String trimmed = name.trim();
        for (int i = 0; i < b.chipArtists.getChildCount(); i++) {
            Chip existing = (Chip) b.chipArtists.getChildAt(i);
            if (existing.getText().toString().equalsIgnoreCase(trimmed)) return;  // no dups
        }
        Chip chip = new Chip(requireContext());
        chip.setText(trimmed);
        chip.setCloseIconVisible(true);
        chip.setOnCloseIconClickListener(v -> b.chipArtists.removeView(chip));
        b.chipArtists.addView(chip);
    }

    private List<String> collectArtists() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < b.chipArtists.getChildCount(); i++) {
            out.add(((Chip) b.chipArtists.getChildAt(i)).getText().toString());
        }
        return out;
    }

    private void onSave() {
        if (pickedUri == null) return;
        addTypedArtist();   // fold any text left in the box into a chip

        String title = b.etTitle.getText() == null ? "" : b.etTitle.getText().toString().trim();
        List<String> artists = collectArtists();

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

    /**
     * Before ingesting, look for a near-duplicate on each side this song would
     * land on (local and/or server) and, if found, warn — the byte hash only
     * catches exact files, not the same song from another source.
     */
    private void checkThenProcess(Dest dest, String title, List<String> artists) {
        b.btnSave.setEnabled(false);
        setStatus(getString(R.string.add_checking_dupes), R.color.echoes_on_surface_variant);
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
                .setNegativeButton(R.string.action_cancel, (d, w) -> {
                    b.btnSave.setEnabled(true);
                    setStatus("", R.color.echoes_on_surface_variant);
                })
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
        b.btnSave.setEnabled(false);
        b.btnPick.setEnabled(false);
        setStatus(getString(R.string.add_processing), R.color.echoes_on_surface_variant);

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
            if (root != null) root.post(() -> showResult(text, success));
        });
    }

    private void showResult(String text, boolean ok) {
        if (b == null) return;
        b.btnSave.setEnabled(true);
        b.btnPick.setEnabled(true);
        setStatus(text, ok ? R.color.echoes_success : R.color.echoes_error);
        // On success the bytes are now in the library (and/or on the server), so
        // the downloaded temp file is no longer needed. No-op for file picks.
        if (ok) deleteYtTemp();
    }

    private void setStatus(String msg, int colorRes) {
        b.tvStatus.setText(msg);
        b.tvStatus.setTextColor(ContextCompat.getColor(requireContext(), colorRes));
    }

    /** Best-effort human-readable file name from a content Uri. */
    private String displayName(Uri uri) {
        try (Cursor c = requireContext().getContentResolver()
                .query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (RuntimeException ignored) {
            // fall through to the raw uri
        }
        return uri.getLastPathSegment();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        deleteYtTemp();
        io.shutdownNow();
    }
}
