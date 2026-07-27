package com.musica.app.ui;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.musica.app.R;
import com.musica.app.data.YtDlpService;
import com.musica.app.databinding.ItemYtResultBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Tap-to-download list of YouTube search results shown in the Añadir screen. */
public class YtResultAdapter extends RecyclerView.Adapter<YtResultAdapter.VH> {

    public interface Listener {
        void onPick(YtDlpService.SearchResult result);
    }

    private final List<YtDlpService.SearchResult> items = new ArrayList<>();
    private final Listener listener;

    public YtResultAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<YtDlpService.SearchResult> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemYtResultBinding b = ItemYtResultBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        YtDlpService.SearchResult r = items.get(position);
        h.b.tvTitle.setText(r.title);
        h.b.tvMeta.setText(metaOf(h, r));
        h.b.getRoot().setOnClickListener(v -> listener.onPick(r));
    }

    /** Subtitle: "m:ss · canal", degrading gracefully when a field is missing. */
    private static String metaOf(VH h, YtDlpService.SearchResult r) {
        String duration = r.durationSeconds > 0 ? formatDuration(r.durationSeconds) : null;
        boolean hasUploader = r.uploader != null && !r.uploader.isBlank();
        if (duration != null && hasUploader) {
            return h.b.getRoot().getContext()
                    .getString(R.string.yt_result_meta, duration, r.uploader);
        }
        if (hasUploader) return r.uploader;
        if (duration != null) return duration;
        return "—";
    }

    private static String formatDuration(int seconds) {
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final ItemYtResultBinding b;
        VH(ItemYtResultBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
