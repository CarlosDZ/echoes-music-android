package com.musica.app.fragments;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.musica.app.MainActivity;
import com.musica.app.R;
import com.musica.app.data.Fuzzy;
import com.musica.app.data.LocalRepository;
import com.musica.app.data.MergedLibrary;
import com.musica.app.data.RemoteRepository;
import com.musica.app.databinding.DialogAddSongsBinding;
import com.musica.app.databinding.FragmentPlaylistDetailBinding;
import com.musica.app.model.Availability;
import com.musica.app.model.Playlist;
import com.musica.app.model.PlaylistRef;
import com.musica.app.model.Song;
import com.musica.app.playback.PlaybackController;
import com.musica.app.ui.PickSongAdapter;
import com.musica.app.ui.SongAdapter;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One playlist's songs, resolved against the merged library. A CLONED playlist
 * (local copy of a server one) opens its local side so it plays offline; a
 * REMOTE-only playlist can be cloned to local from here.
 */
public class PlaylistDetailFragment extends Fragment {

    public static PlaylistDetailFragment newInstance(PlaylistRef ref) {
        PlaylistDetailFragment f = new PlaylistDetailFragment();
        Bundle a = new Bundle();
        a.putString("name", ref.name());
        a.putInt("origin", ref.origin().ordinal());
        a.putLong("localId", ref.localId());
        a.putLong("remoteId", ref.remoteId());
        f.setArguments(a);
        return f;
    }

    private FragmentPlaylistDetailBinding b;
    private LocalRepository local;
    private RemoteRepository remote;
    private MergedLibrary library;
    private SongAdapter adapter;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<MergedLibrary.Item> items = new ArrayList<>();
    private final List<MergedLibrary.Item> allLibrary = new ArrayList<>();
    /** Hashes already in the playlist — hidden from the add-songs picker. */
    private final Set<String> memberHashes = new HashSet<>();

    private PlaylistRef.Origin origin;
    private long localId;
    private long remoteId;
    private String playlistName;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentPlaylistDetailBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Bundle a = requireArguments();
        playlistName = a.getString("name", "");
        origin = PlaylistRef.Origin.values()[a.getInt("origin")];
        localId = a.getLong("localId");
        remoteId = a.getLong("remoteId");

        local = new LocalRepository(requireContext());
        remote = new RemoteRepository(requireContext());
        library = new MergedLibrary(requireContext());

        b.tvName.setText(playlistName);
        b.btnClone.setVisibility(origin == PlaylistRef.Origin.REMOTE ? View.VISIBLE : View.GONE);
        b.btnShare.setVisibility(
                origin == PlaylistRef.Origin.LOCAL && remote.isConfigured()
                        ? View.VISIBLE : View.GONE);

        adapter = new SongAdapter(new SongAdapter.Listener() {
            @Override public void onPlay(MergedLibrary.Item item) { playFrom(item); }
            @Override public void onDetail(MergedLibrary.Item item) { confirmRemove(item); }
        }, R.drawable.ic_remove);   // secondary button removes from the playlist
        b.list.setLayoutManager(new LinearLayoutManager(requireContext()));
        b.list.setAdapter(adapter);

        b.btnBack.setOnClickListener(v -> getParentFragmentManager().popBackStack());
        b.btnPlayAll.setOnClickListener(v -> { if (!items.isEmpty()) playFrom(items.get(0)); });
        b.btnAddSongs.setOnClickListener(v -> showAddSongs());
        b.btnClone.setOnClickListener(v -> clonePlaylist());
        b.btnShare.setOnClickListener(v -> sharePlaylist());
        b.btnDeletePlaylist.setOnClickListener(v -> confirmDeletePlaylist());

        load();
    }

    private boolean localBacked() {
        return origin == PlaylistRef.Origin.LOCAL || origin == PlaylistRef.Origin.CLONED;
    }

    private void load() {
        b.tvEmpty.setText(R.string.search_loading);
        b.tvEmpty.setVisibility(View.VISIBLE);
        final Context app = requireContext().getApplicationContext();
        io.execute(() -> {
            List<MergedLibrary.Item> all = library.load(app);
            Map<String, MergedLibrary.Item> byHash = MergedLibrary.indexByHash(all);
            List<MergedLibrary.Item> resolved = resolveMembership(byHash);
            View root = getView();
            if (root != null) root.post(() -> {
                if (b == null) return;
                allLibrary.clear();
                allLibrary.addAll(all);
                items.clear();
                items.addAll(resolved);
                adapter.submit(items);
                b.tvEmpty.setText(R.string.playlist_empty);
                b.tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    /** Resolves membership; a CLONED/LOCAL playlist uses its local copy (offline). */
    private List<MergedLibrary.Item> resolveMembership(Map<String, MergedLibrary.Item> byHash) {
        List<MergedLibrary.Item> out = new ArrayList<>();
        if (localBacked()) {
            for (String hash : local.playlistHashes(localId)) {
                MergedLibrary.Item it = byHash.get(hash);
                if (it != null) out.add(it);
            }
        } else {
            for (Song s : remote.playlistSongs(remoteId)) {
                MergedLibrary.Item it = (s.fileHash() != null) ? byHash.get(s.fileHash()) : null;
                if (it == null) it = new MergedLibrary.Item(s, Availability.REMOTE, -1, s.id());
                out.add(it);
            }
        }
        return out;
    }

    private void playFrom(MergedLibrary.Item item) {
        int index = items.indexOf(item);
        if (index < 0) return;
        PlaybackController.get(requireContext()).playQueue(items, index,
                getString(R.string.player_from_playlist, playlistName));
        if (requireActivity() instanceof MainActivity main) main.openReproductor();
    }

    // ---------------- clone whole playlist to local ----------------

    private void clonePlaylist() {
        final File cacheDir = requireContext().getCacheDir();
        b.btnClone.setEnabled(false);
        Toast.makeText(requireContext(), R.string.playlist_cloning, Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            boolean ok = true;
            try {
                List<Song> songs = remote.playlistSongs(remoteId);
                // Download + ingest each song (ingest dedups, so already-local stay).
                for (Song s : songs) {
                    File tmp = File.createTempFile("clone", ".mp3", cacheDir);
                    try {
                        if (remote.downloadFile(s.id(), tmp)) {
                            local.ingest(tmp, s.title(), s.artists());
                        }
                    } finally {
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                    }
                }
                // Reuse an existing local playlist of the same name, or create it.
                long pid = -1;
                for (Playlist p : local.playlists()) {
                    if (p.name().equals(playlistName)) { pid = p.id(); break; }
                }
                if (pid < 0) pid = local.createPlaylist(playlistName).id();
                for (Song s : songs) {
                    if (s.fileHash() != null) local.addToPlaylist(pid, s.fileHash());
                }
            } catch (Exception e) {
                ok = false;
            }
            final boolean done = ok;
            View root = getView();
            if (root != null) root.post(() -> {
                if (b == null) return;
                b.btnClone.setEnabled(true);
                Toast.makeText(requireContext(),
                        done ? R.string.playlist_cloned : R.string.playlist_clone_error,
                        Toast.LENGTH_SHORT).show();
                if (done) getParentFragmentManager().popBackStack();   // list will show CLONADA
            });
        });
    }

    // ---------------- share a local playlist to the server ----------------

    private void sharePlaylist() {
        b.btnShare.setEnabled(false);
        Toast.makeText(requireContext(), R.string.playlist_sharing, Toast.LENGTH_SHORT).show();
        // Snapshot the resolved songs now (on the main thread).
        final List<MergedLibrary.Item> snapshot = new ArrayList<>(items);
        io.execute(() -> {
            boolean ok = true;
            try {
                // Reuse a server playlist of the same name, or create it.
                long pid = -1;
                for (Playlist p : remote.playlists()) {
                    if (p.name().equals(playlistName)) { pid = p.id(); break; }
                }
                if (pid < 0) pid = remote.createPlaylist(playlistName);
                if (pid < 0) {
                    ok = false;
                } else {
                    for (MergedLibrary.Item it : snapshot) {
                        long songId = ensureOnServer(it);   // uploads local-only songs
                        if (songId >= 0) remote.addSongToPlaylist(pid, songId);
                    }
                }
            } catch (Exception e) {
                ok = false;
            }
            final boolean done = ok;
            View root = getView();
            if (root != null) root.post(() -> {
                if (b == null) return;
                b.btnShare.setEnabled(true);
                Toast.makeText(requireContext(),
                        done ? R.string.playlist_shared : R.string.playlist_share_error,
                        Toast.LENGTH_SHORT).show();
                if (done) getParentFragmentManager().popBackStack();   // list will show CLONADA
            });
        });
    }

    // ---------------- add songs (searchable picker) ----------------

    private void showAddSongs() {
        // Songs already in this playlist shouldn't appear in the picker.
        memberHashes.clear();
        for (MergedLibrary.Item it : items) {
            if (it.song().fileHash() != null) memberHashes.add(it.song().fileHash());
        }

        DialogAddSongsBinding d = DialogAddSongsBinding.inflate(getLayoutInflater());
        PickSongAdapter pick = new PickSongAdapter(this::addSong);
        d.list.setLayoutManager(new LinearLayoutManager(requireContext()));
        d.list.setAdapter(pick);
        pick.submit(filter(""));

        d.etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int dd) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int dd) {
                pick.submit(filter(s.toString()));
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.action_add_songs)
                .setView(d.getRoot())
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnDismissListener(dl -> load());
        dialog.show();
    }

    private List<MergedLibrary.Item> filter(String query) {
        List<MergedLibrary.Item> out = new ArrayList<>();
        for (MergedLibrary.Item it : allLibrary) {
            Song s = it.song();
            if (s.fileHash() != null && memberHashes.contains(s.fileHash())) continue;  // already in
            int best = Fuzzy.score(query, s.title());
            if (s.artists() != null) {
                for (String a : s.artists()) best = Math.max(best, Fuzzy.score(query, a));
            }
            if (best != Fuzzy.NO_MATCH) out.add(it);
        }
        return out;
    }

    private void addSong(MergedLibrary.Item item) {
        io.execute(() -> {
            boolean ok;
            if (localBacked()) {
                String hash = item.song().fileHash();
                ok = hash != null;
                if (ok) local.addToPlaylist(localId, hash);
            } else {
                long songId = ensureOnServer(item);
                ok = songId >= 0 && remote.addSongToPlaylist(remoteId, songId);
            }
            toast(ok ? R.string.playlist_added : R.string.playlist_add_error);
        });
    }

    private long ensureOnServer(MergedLibrary.Item item) {
        if (item.hasRemote()) return item.remoteId();
        if (!item.hasLocal()) return -1;
        RemoteRepository.UploadResult up = remote.upload(
                local.fileOf(item.song().path()), item.song().title(), item.song().artists());
        return (up.status() == RemoteRepository.Status.CREATED
                || up.status() == RemoteRepository.Status.DUPLICATE) ? up.songId() : -1;
    }

    // ---------------- remove song / delete playlist ----------------

    private void confirmRemove(MergedLibrary.Item item) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.action_remove_from_playlist)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (d, w) -> remove(item))
                .show();
    }

    private void remove(MergedLibrary.Item item) {
        io.execute(() -> {
            boolean ok = true;
            if (localBacked()) {
                local.removeFromPlaylist(localId, item.song().fileHash());
            } else {
                ok = remote.removeSongFromPlaylist(remoteId, item.remoteId());
            }
            final boolean done = ok;
            View root = getView();
            if (root != null) root.post(() -> {
                if (b == null) return;
                Toast.makeText(requireContext(),
                        done ? R.string.playlist_song_removed : R.string.playlist_add_error,
                        Toast.LENGTH_SHORT).show();
                if (done) load();
            });
        });
    }

    private void confirmDeletePlaylist() {
        if (origin == PlaylistRef.Origin.CLONED) {
            // Cloned → choose which side to delete (split like cloned songs).
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.delete_confirm_title)
                    .setItems(new CharSequence[]{
                            getString(R.string.action_delete_local),
                            getString(R.string.action_delete_server),
                            getString(R.string.action_delete_both)
                    }, (d, w) -> {
                        if (w == 0) deletePlaylist(true, false);
                        else if (w == 1) deletePlaylist(false, true);
                        else deletePlaylist(true, true);
                    })
                    .show();
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete_confirm_title)
                .setMessage(R.string.playlist_delete_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (d, w) ->
                        deletePlaylist(origin == PlaylistRef.Origin.LOCAL, origin == PlaylistRef.Origin.REMOTE))
                .show();
    }

    private void deletePlaylist(boolean deleteLocal, boolean deleteRemote) {
        io.execute(() -> {
            boolean ok = true;
            if (deleteRemote) ok = remote.deletePlaylist(remoteId);
            if (deleteLocal) local.deletePlaylist(localId);
            int okMsg;
            if (origin == PlaylistRef.Origin.CLONED && !(deleteLocal && deleteRemote)) {
                okMsg = deleteLocal ? R.string.playlist_uncloned        // local gone, server stays
                        : R.string.playlist_deleted_server;            // server gone, local stays
            } else {
                okMsg = R.string.playlist_deleted;
            }
            final boolean done = ok;
            final int message = done ? okMsg : R.string.playlist_delete_error;
            View root = getView();
            if (root != null) root.post(() -> {
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
                if (done) getParentFragmentManager().popBackStack();
            });
        });
    }

    private void toast(int msg) {
        View root = getView();
        if (root != null) root.post(() -> {
            if (b != null) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
        });
    }

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
