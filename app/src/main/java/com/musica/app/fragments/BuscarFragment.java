package com.musica.app.fragments;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.musica.app.MainActivity;
import com.musica.app.R;
import com.musica.app.playback.PlaybackController;
import com.musica.app.data.DuplicateCheck;
import com.musica.app.data.Fuzzy;
import com.musica.app.data.LocalRepository;
import com.musica.app.data.MergedLibrary;
import com.musica.app.data.RemoteRepository;
import com.musica.app.databinding.DialogEditSongBinding;
import com.musica.app.databinding.FragmentBuscarBinding;
import com.musica.app.model.Availability;
import com.musica.app.model.Playlist;
import com.musica.app.model.PlaylistRef;
import com.musica.app.model.Song;
import com.musica.app.ui.SongAdapter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Search over the merged (local + remote) library. The whole library is loaded
 * once, then filtered client-side with fuzzy matching as the user types. The
 * detail button opens an edit/delete dialog that targets whichever side(s) the
 * song lives on.
 */
public class BuscarFragment extends Fragment {

    private FragmentBuscarBinding b;
    private MergedLibrary library;
    private LocalRepository local;
    private RemoteRepository remote;
    private SongAdapter adapter;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    /** Full unfiltered library, kept in memory for instant filtering. */
    private final List<MergedLibrary.Item> all = new ArrayList<>();
    /** Currently visible (filtered) list — also the playback queue when you hit play. */
    private final List<MergedLibrary.Item> visible = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentBuscarBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        library = new MergedLibrary(requireContext());
        local = new LocalRepository(requireContext());
        remote = new RemoteRepository(requireContext());

        adapter = new SongAdapter(new SongAdapter.Listener() {
            @Override public void onPlay(MergedLibrary.Item item) { play(item); }
            @Override public void onDetail(MergedLibrary.Item item) { showEditDialog(item); }
        });
        b.list.setLayoutManager(new LinearLayoutManager(requireContext()));
        b.list.setAdapter(adapter);

        b.etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { applyFilter(); }
            @Override public void afterTextChanged(Editable s) { }
        });

        loadLibrary();
    }

    private void loadLibrary() {
        b.tvEmpty.setText(R.string.search_loading);
        b.tvEmpty.setVisibility(View.VISIBLE);
        final Context app = requireContext().getApplicationContext();
        io.execute(() -> {
            List<MergedLibrary.Item> loaded = library.load(app);
            View root = getView();
            if (root != null) root.post(() -> {
                if (b == null) return;
                all.clear();
                all.addAll(loaded);
                applyFilter();
            });
        });
    }

    /** Recomputes the visible list from the query, fuzzy-ranked. */
    private void applyFilter() {
        if (b == null) return;
        String query = b.etSearch.getText() == null ? "" : b.etSearch.getText().toString();

        List<Scored> scored = new ArrayList<>();
        for (MergedLibrary.Item item : all) {
            int s = scoreItem(query, item.song());
            if (s != Fuzzy.NO_MATCH) scored.add(new Scored(item, s));
        }
        scored.sort(Comparator
                .comparingInt((Scored x) -> x.score).reversed()
                .thenComparing(x -> title(x.item.song()).toLowerCase(Locale.ROOT)));

        visible.clear();
        for (Scored x : scored) visible.add(x.item);
        adapter.submit(visible);

        if (visible.isEmpty()) {
            b.tvEmpty.setText(all.isEmpty()
                    ? R.string.search_empty_library
                    : R.string.search_empty);
            b.tvEmpty.setVisibility(View.VISIBLE);
        } else {
            b.tvEmpty.setVisibility(View.GONE);
        }
    }

    private static int scoreItem(String query, Song s) {
        int best = Fuzzy.score(query, s.title());
        if (s.artists() != null) {
            for (String a : s.artists()) best = Math.max(best, Fuzzy.score(query, a));
        }
        return best;
    }

    /** Starts the current results as a queue at the tapped song, then opens Now Playing. */
    private void play(MergedLibrary.Item item) {
        int index = visible.indexOf(item);
        if (index < 0) return;
        PlaybackController.get(requireContext())
                .playQueue(visible, index, getString(R.string.player_from_search));
        if (requireActivity() instanceof MainActivity main) {
            main.openReproductor();
        }
    }

    // ---------------- edit / delete ----------------

    private void showEditDialog(MergedLibrary.Item item) {
        DialogEditSongBinding d = DialogEditSongBinding.inflate(getLayoutInflater());
        Song s = item.song();
        if (s.title() != null) d.etTitle.setText(s.title());
        if (s.artists() != null) for (String a : s.artists()) addChip(d.chipArtists, a);
        d.etArtist.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_1, knownArtists()));

        Runnable addTyped = () -> {
            String n = d.etArtist.getText() == null ? "" : d.etArtist.getText().toString().trim();
            if (!n.isBlank()) addChip(d.chipArtists, n);
            d.etArtist.setText("");
        };
        d.btnAddArtist.setOnClickListener(v -> addTyped.run());
        d.etArtist.setOnItemClickListener((p, v, pos, id) -> addTyped.run());
        d.etArtist.setOnEditorActionListener((v, a, e) -> { addTyped.run(); return true; });

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.edit_title)
                .setView(d.getRoot())
                .create();

        // Buttons live in the layout (Cancelar + Guardar on one row, Más acciones
        // below) instead of the dialog button bar, so they don't stack.
        d.btnCancel.setOnClickListener(v -> dialog.dismiss());
        d.btnSave.setOnClickListener(v -> {
            addTyped.run();
            String title = d.etTitle.getText() == null ? "" : d.etTitle.getText().toString().trim();
            List<String> artists = collectArtists(d.chipArtists);
            dialog.dismiss();
            saveEdit(item, title, artists);
        });
        d.btnMore.setOnClickListener(v -> {
            addTyped.run();
            String title = d.etTitle.getText() == null ? "" : d.etTitle.getText().toString().trim();
            List<String> artists = collectArtists(d.chipArtists);
            dialog.dismiss();
            showActionsMenu(item, title, artists);
        });
        dialog.show();
    }

    private void saveEdit(MergedLibrary.Item item, String title, List<String> artists) {
        io.execute(() -> {
            boolean ok = true;
            if (item.hasLocal()) local.updateSong(item.localId(), title, artists);
            if (item.hasRemote()) ok = remote.patchSong(item.remoteId(), title, artists);
            finishOp(ok, R.string.edit_done, R.string.edit_error);
        });
    }

    /** Context actions for a song, depending on where it lives. */
    private void showActionsMenu(MergedLibrary.Item item, String title, List<String> artists) {
        List<CharSequence> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        labels.add(getString(R.string.action_add_to_playlist));
        actions.add(() -> addToPlaylistFlow(item));

        if (item.availability() == Availability.REMOTE) {
            labels.add(getString(R.string.action_clone_local));
            actions.add(() -> cloneToLocal(item, title, artists));
        }
        if (item.availability() == Availability.LOCAL && remote.isConfigured()) {
            labels.add(getString(R.string.action_upload_server));
            actions.add(() -> uploadToServer(item, title, artists));
        }
        // Delete: scoped per side, so a cloned song splits into two options.
        if (item.hasLocal()) {
            labels.add(getString(R.string.action_delete_local));
            actions.add(() -> confirmDelete(item, true, false));
        }
        if (item.hasRemote()) {
            labels.add(getString(R.string.action_delete_server));
            actions.add(() -> confirmDelete(item, false, true));
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title(item.song()))
                .setItems(labels.toArray(new CharSequence[0]),
                        (dl, which) -> actions.get(which).run())
                .show();
    }

    private void cloneToLocal(MergedLibrary.Item item, String title, List<String> artists) {
        final java.io.File cacheDir = requireContext().getCacheDir();
        io.execute(() -> {
            boolean ok = false;
            java.io.File tmp = null;
            try {
                tmp = java.io.File.createTempFile("clone", ".mp3", cacheDir);
                if (remote.downloadFile(item.remoteId(), tmp)) {
                    local.ingest(tmp, title, artists);
                    ok = true;
                }
            } catch (java.io.IOException | RuntimeException e) {
                ok = false;
            } finally {
                if (tmp != null) //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
            }
            finishOp(ok, R.string.clone_done, R.string.clone_error);
        });
    }

    private void uploadToServer(MergedLibrary.Item item, String title, List<String> artists) {
        // Warn about a near-duplicate already on the server before uploading.
        io.execute(() -> {
            List<Song> matches = DuplicateCheck.findSimilar(title, artists, remote.allSongs());
            View root = getView();
            if (root == null) return;
            root.post(() -> {
                if (b == null) return;
                if (matches.isEmpty()) doUploadToServer(item, title, artists);
                else new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.add_dupe_title)
                        .setMessage(dupeMessage(matches, R.string.dupe_scope_server))
                        .setNegativeButton(R.string.action_cancel, null)
                        .setPositiveButton(R.string.add_dupe_add_anyway,
                                (d, w) -> doUploadToServer(item, title, artists))
                        .show();
            });
        });
    }

    private void doUploadToServer(MergedLibrary.Item item, String title, List<String> artists) {
        io.execute(() -> {
            RemoteRepository.UploadResult r =
                    remote.upload(local.fileOf(item.song().path()), title, artists);
            boolean ok = r.status() == RemoteRepository.Status.CREATED
                    || r.status() == RemoteRepository.Status.DUPLICATE;
            finishOp(ok, R.string.upload_done, R.string.upload_error);
        });
    }

    private String dupeMessage(List<Song> matches, int scopeRes) {
        StringBuilder sb = new StringBuilder(getString(R.string.add_dupe_intro));
        for (Song s : matches) {
            String t = (s.title() == null || s.title().isBlank()) ? getString(R.string.unknown_title) : s.title();
            sb.append("\n• «").append(t).append("» (").append(getString(scopeRes)).append(")");
        }
        return sb.toString();
    }

    // ---------------- add to playlist ----------------

    private void addToPlaylistFlow(MergedLibrary.Item item) {
        io.execute(() -> {
            // Same name-matched merge as the Playlists tab (LOCAL / REMOTE / CLONED).
            Map<String, Playlist> localByName = new LinkedHashMap<>();
            for (Playlist p : local.playlists()) localByName.put(p.name(), p);
            Map<String, Playlist> remoteByName = new LinkedHashMap<>();
            if (remote.isConfigured()) {
                for (Playlist p : remote.playlists()) remoteByName.put(p.name(), p);
            }
            List<PlaylistRef> refs = new ArrayList<>();
            for (Map.Entry<String, Playlist> e : localByName.entrySet()) {
                Playlist lp = e.getValue();
                Playlist rp = remoteByName.get(e.getKey());
                refs.add(rp != null
                        ? new PlaylistRef(lp.name(), PlaylistRef.Origin.CLONED, lp.id(), rp.id())
                        : new PlaylistRef(lp.name(), PlaylistRef.Origin.LOCAL, lp.id(), -1));
            }
            for (Map.Entry<String, Playlist> e : remoteByName.entrySet()) {
                if (!localByName.containsKey(e.getKey())) {
                    refs.add(new PlaylistRef(e.getValue().name(),
                            PlaylistRef.Origin.REMOTE, -1, e.getValue().id()));
                }
            }
            View root = getView();
            if (root != null) root.post(() -> showPlaylistPicker(item, refs));
        });
    }

    private void showPlaylistPicker(MergedLibrary.Item item, List<PlaylistRef> refs) {
        if (b == null) return;
        if (refs.isEmpty()) {
            Toast.makeText(requireContext(), R.string.playlist_none, Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] names = new CharSequence[refs.size()];
        for (int i = 0; i < refs.size(); i++) {
            PlaylistRef r = refs.get(i);
            int badge = switch (r.origin()) {
                case CLONED -> R.string.playlist_badge_cloned;
                case REMOTE -> R.string.playlist_badge_server;
                default -> R.string.playlist_badge_local;
            };
            names[i] = r.name() + " · " + getString(badge);
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.playlist_add_title)
                .setItems(names, (d, w) -> addToPlaylist(item, refs.get(w)))
                .show();
    }

    private void addToPlaylist(MergedLibrary.Item item, PlaylistRef ref) {
        io.execute(() -> {
            boolean ok;
            if (ref.isLocalBacked()) {
                // Local/cloned playlist: store by hash. A remote song goes in
                // without downloading — it stays streamed.
                String hash = item.song().fileHash();
                ok = hash != null;
                if (ok) local.addToPlaylist(ref.localId(), hash);
            } else {
                // Server playlist needs a server song id; upload the local one first.
                long songId = ensureOnServer(item);
                ok = songId >= 0 && remote.addSongToPlaylist(ref.remoteId(), songId);
            }
            final boolean done = ok;
            View root = getView();
            if (root != null) root.post(() -> Toast.makeText(requireContext(),
                    done ? R.string.playlist_added : R.string.playlist_add_error,
                    Toast.LENGTH_SHORT).show());
        });
    }

    /**
     * Returns the song's server id, uploading the local file first if it isn't on
     * the server yet (so it becomes Cloned). Returns -1 if it can't be uploaded.
     */
    private long ensureOnServer(MergedLibrary.Item item) {
        if (item.hasRemote()) return item.remoteId();
        if (!item.hasLocal()) return -1;
        RemoteRepository.UploadResult up = remote.upload(
                local.fileOf(item.song().path()), item.song().title(), item.song().artists());
        return (up.status() == RemoteRepository.Status.CREATED
                || up.status() == RemoteRepository.Status.DUPLICATE) ? up.songId() : -1;
    }

    private void confirmDelete(MergedLibrary.Item item, boolean deleteLocal, boolean deleteRemote) {
        String scope = deleteLocal ? getString(R.string.scope_local)
                : getString(R.string.scope_server);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete_confirm_title)
                .setMessage(getString(R.string.delete_confirm_msg, scope))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete,
                        (dl, w) -> doDelete(item, deleteLocal, deleteRemote))
                .show();
    }

    private void doDelete(MergedLibrary.Item item, boolean deleteLocal, boolean deleteRemote) {
        io.execute(() -> {
            boolean ok = true;
            if (deleteRemote && item.hasRemote()) ok = remote.deleteSong(item.remoteId());
            if (deleteLocal && item.hasLocal()) local.delete(item.localId());   // local always works
            int okMsg = deleteLocal ? R.string.delete_local_done : R.string.delete_server_done;
            finishOp(ok, okMsg, R.string.delete_error);
        });
    }

    /** Posts a toast and reloads the library on success. */
    private void finishOp(boolean ok, int okMsg, int errMsg) {
        View root = getView();
        if (root == null) return;
        root.post(() -> {
            if (b == null) return;
            Toast.makeText(requireContext(), ok ? okMsg : errMsg, Toast.LENGTH_SHORT).show();
            if (ok) loadLibrary();
        });
    }

    /** Distinct artist names already in the loaded library, for autocomplete. */
    private List<String> knownArtists() {
        TreeSet<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (MergedLibrary.Item it : all) {
            if (it.song().artists() != null) set.addAll(it.song().artists());
        }
        return new ArrayList<>(set);
    }

    private void addChip(ChipGroup group, String name) {
        String trimmed = name.trim();
        for (int i = 0; i < group.getChildCount(); i++) {
            Chip existing = (Chip) group.getChildAt(i);
            if (existing.getText().toString().equalsIgnoreCase(trimmed)) return;
        }
        Chip chip = new Chip(requireContext());
        chip.setText(trimmed);
        chip.setCloseIconVisible(true);
        chip.setOnCloseIconClickListener(v -> group.removeView(chip));
        group.addView(chip);
    }

    private List<String> collectArtists(ChipGroup group) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < group.getChildCount(); i++) {
            out.add(((Chip) group.getChildAt(i)).getText().toString());
        }
        return out;
    }

    private String title(Song s) {
        String t = s.title();
        return (t == null || t.isBlank()) ? getString(R.string.unknown_title) : t;
    }

    private record Scored(MergedLibrary.Item item, int score) { }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}
