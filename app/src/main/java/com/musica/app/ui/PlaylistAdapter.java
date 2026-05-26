package com.musica.app.ui;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.musica.app.R;
import com.musica.app.databinding.ItemPlaylistBinding;
import com.musica.app.model.PlaylistRef;

import java.util.ArrayList;
import java.util.List;

/** Lists playlists (local + server) with an origin badge. */
public class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.VH> {

    public interface Listener {
        void onOpen(PlaylistRef ref);
    }

    private final List<PlaylistRef> items = new ArrayList<>();
    private final Listener listener;

    public PlaylistAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<PlaylistRef> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemPlaylistBinding b = ItemPlaylistBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        PlaylistRef ref = items.get(position);
        h.b.tvName.setText(ref.name());
        int badge;
        int color;
        switch (ref.origin()) {
            case CLONED -> { badge = R.string.playlist_badge_cloned; color = R.color.echoes_success; }
            case REMOTE -> { badge = R.string.playlist_badge_server; color = R.color.echoes_primary; }
            default     -> { badge = R.string.playlist_badge_local; color = R.color.echoes_on_surface_variant; }
        }
        h.b.tvBadge.setText(badge);
        h.b.tvBadge.setTextColor(ContextCompat.getColor(h.b.getRoot().getContext(), color));
        h.b.getRoot().setOnClickListener(v -> listener.onOpen(ref));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final ItemPlaylistBinding b;
        VH(ItemPlaylistBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
