package com.musica.app.ui;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.musica.app.R;
import com.musica.app.data.MergedLibrary;
import com.musica.app.databinding.ItemSongBinding;
import com.musica.app.model.Song;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders merged-library items as a list: title, artists, an availability badge,
 * and play / detail buttons. Play is a mock for now (no player yet).
 */
public class SongAdapter extends RecyclerView.Adapter<SongAdapter.VH> {

    public interface Listener {
        void onPlay(MergedLibrary.Item item);
        void onDetail(MergedLibrary.Item item);
    }

    private final List<MergedLibrary.Item> items = new ArrayList<>();
    private final Listener listener;
    /** Icon for the secondary button (0 = keep the layout default, an info icon). */
    private final int secondaryIconRes;

    public SongAdapter(Listener listener) {
        this(listener, 0);
    }

    public SongAdapter(Listener listener, int secondaryIconRes) {
        this.listener = listener;
        this.secondaryIconRes = secondaryIconRes;
    }

    public void submit(List<MergedLibrary.Item> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemSongBinding b = ItemSongBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        MergedLibrary.Item item = items.get(position);
        Song s = item.song();

        h.b.tvTitle.setText(title(s));
        h.b.tvArtists.setText(artists(s));

        h.b.tvBadge.setText(badgeText(item));
        h.b.tvBadge.setTextColor(ContextCompat.getColor(
                h.b.getRoot().getContext(), badgeColor(item)));

        if (secondaryIconRes != 0) h.b.btnDetail.setImageResource(secondaryIconRes);
        h.b.btnPlay.setOnClickListener(v -> listener.onPlay(item));
        h.b.btnDetail.setOnClickListener(v -> listener.onDetail(item));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private static String title(Song s) {
        String t = s.title();
        return (t == null || t.isBlank())
                ? s.path() == null ? "?" : s.path()
                : t;
    }

    private static String artists(Song s) {
        if (s.artists() == null || s.artists().isEmpty()) return "—";
        return String.join(", ", s.artists());
    }

    private static int badgeText(MergedLibrary.Item item) {
        return switch (item.availability()) {
            case LOCAL -> R.string.badge_local;
            case REMOTE -> R.string.badge_remote;
            case CLONED -> R.string.badge_cloned;
        };
    }

    private static int badgeColor(MergedLibrary.Item item) {
        return switch (item.availability()) {
            case LOCAL -> R.color.echoes_on_surface_variant;
            case REMOTE -> R.color.echoes_primary;
            case CLONED -> R.color.echoes_success;
        };
    }

    static final class VH extends RecyclerView.ViewHolder {
        final ItemSongBinding b;
        VH(ItemSongBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
