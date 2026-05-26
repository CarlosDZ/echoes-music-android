package com.musica.app.ui;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.musica.app.data.MergedLibrary;
import com.musica.app.databinding.ItemPickBinding;
import com.musica.app.model.Song;

import java.util.ArrayList;
import java.util.List;

/** Compact tap-to-add list used by the "add songs to playlist" picker. */
public class PickSongAdapter extends RecyclerView.Adapter<PickSongAdapter.VH> {

    public interface Listener {
        void onPick(MergedLibrary.Item item);
    }

    private final List<MergedLibrary.Item> items = new ArrayList<>();
    private final Listener listener;

    public PickSongAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<MergedLibrary.Item> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemPickBinding b = ItemPickBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        MergedLibrary.Item item = items.get(position);
        Song s = item.song();
        h.b.tvTitle.setText((s.title() == null || s.title().isBlank()) ? "Sin título" : s.title());
        h.b.tvArtists.setText((s.artists() == null || s.artists().isEmpty())
                ? "—" : String.join(", ", s.artists()));
        h.b.getRoot().setOnClickListener(v -> listener.onPick(item));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final ItemPickBinding b;
        VH(ItemPickBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
