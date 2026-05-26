package com.musica.app.fragments;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.musica.app.MainActivity;
import com.musica.app.R;
import com.musica.app.data.LocalRepository;
import com.musica.app.data.RemoteRepository;
import com.musica.app.databinding.DialogNewPlaylistBinding;
import com.musica.app.databinding.FragmentPlaylistsBinding;
import com.musica.app.model.Playlist;
import com.musica.app.model.PlaylistRef;
import com.musica.app.ui.PlaylistAdapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lists local + server playlists together and creates new ones. */
public class PlaylistsFragment extends Fragment {

    private FragmentPlaylistsBinding b;
    private LocalRepository local;
    private RemoteRepository remote;
    private PlaylistAdapter adapter;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentPlaylistsBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        local = new LocalRepository(requireContext());
        remote = new RemoteRepository(requireContext());
        adapter = new PlaylistAdapter(this::open);
        b.list.setLayoutManager(new LinearLayoutManager(requireContext()));
        b.list.setAdapter(adapter);
        b.btnNew.setOnClickListener(v -> promptName());
        load();
    }

    private void load() {
        io.execute(() -> {
            // Match local and server playlists by name → LOCAL / REMOTE / CLONED.
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
            if (root != null) root.post(() -> {
                if (b == null) return;
                adapter.submit(refs);
                b.tvEmpty.setText(R.string.playlist_none);
                b.tvEmpty.setVisibility(refs.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void open(PlaylistRef ref) {
        if (requireActivity() instanceof MainActivity main) {
            main.openDetail(PlaylistDetailFragment.newInstance(ref));
        }
    }

    /** Step 1: ask for the name. */
    private void promptName() {
        DialogNewPlaylistBinding d = DialogNewPlaylistBinding.inflate(getLayoutInflater());
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.playlist_create_title)
                .setView(d.getRoot())
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(android.R.string.ok, (dlg, w) -> {
                    String name = d.etName.getText() == null ? "" : d.etName.getText().toString().trim();
                    if (name.isEmpty()) return;
                    if (remote.isConfigured()) chooseWhere(name);
                    else createLocal(name);
                })
                .show();
    }

    /** Step 2 (only if a server exists): local or server? */
    private void chooseWhere(String name) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.playlist_create_title)
                .setItems(new CharSequence[]{
                        getString(R.string.playlist_where_local),
                        getString(R.string.playlist_where_server)
                }, (d, which) -> {
                    if (which == 1) createServer(name);
                    else createLocal(name);
                })
                .show();
    }

    private void createLocal(String name) {
        io.execute(() -> {
            local.createPlaylist(name);
            done(true, R.string.playlist_created);
        });
    }

    private void createServer(String name) {
        io.execute(() -> {
            boolean ok = remote.createPlaylist(name) >= 0;
            done(ok, ok ? R.string.playlist_created : R.string.playlist_create_error);
        });
    }

    private void done(boolean ok, int msg) {
        View root = getView();
        if (root == null) return;
        root.post(() -> {
            if (b == null) return;
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
            if (ok) load();
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
