package com.musica.app.fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.media3.common.Player;

import com.musica.app.R;
import com.musica.app.databinding.FragmentReproductorBinding;
import com.musica.app.playback.PlaybackController;

import java.util.Locale;

/**
 * Now Playing screen. Reflects the shared {@link PlaybackController} (which keeps
 * playing across tab switches) and drives it: shuffle, repeat (off/all/one),
 * play-pause, prev/next, a seekable progress bar with times, and an animated
 * equalizer in the middle.
 */
public class ReproductorFragment extends Fragment {

    private static final long TICK_MS = 500;

    private FragmentReproductorBinding b;
    private PlaybackController pc;
    private boolean dragging = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, TICK_MS);
        }
    };
    private final PlaybackController.Listener listener = this::render;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentReproductorBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        pc = PlaybackController.get(requireContext());

        b.btnPlay.setOnClickListener(v -> { pc.toggle(); render(); });
        b.btnNext.setOnClickListener(v -> { pc.next(); render(); });
        b.btnPrev.setOnClickListener(v -> { pc.prev(); render(); });
        b.btnShuffle.setOnClickListener(v -> { pc.toggleShuffle(); render(); });
        b.btnRepeat.setOnClickListener(v -> { pc.cycleRepeat(); render(); });

        b.seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) b.tvPos.setText(formatTime(progress));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                pc.seekTo(s.getProgress());
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        pc.addListener(listener);
        handler.post(tick);
    }

    @Override
    public void onPause() {
        super.onPause();
        pc.removeListener(listener);
        handler.removeCallbacks(tick);
    }

    private void render() {
        if (b == null) return;

        if (!pc.hasQueue()) {
            b.content.setVisibility(View.GONE);
            b.tvEmpty.setVisibility(View.VISIBLE);
            b.equalizer.setActive(false);
            return;
        }
        b.content.setVisibility(View.VISIBLE);
        b.tvEmpty.setVisibility(View.GONE);

        b.tvTitle.setText(pc.currentTitle());
        b.tvArtists.setText(pc.currentArtists());
        String label = pc.contextLabel();
        b.tvContext.setText(label);
        b.tvContext.setVisibility(label.isEmpty() ? View.GONE : View.VISIBLE);

        long dur = pc.durationMs();
        long pos = pc.positionMs();
        b.seek.setMax((int) Math.max(0, dur));
        if (!dragging) {
            b.seek.setProgress((int) Math.max(0, pos));
            b.tvPos.setText(formatTime(pos));
        }
        b.tvDur.setText(formatTime(dur));

        boolean playing = pc.isPlaying();
        b.btnPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        b.equalizer.setActive(playing);

        tint(b.btnShuffle, pc.shuffle() ? R.color.echoes_primary : R.color.echoes_on_surface_variant);

        int repeat = pc.repeatMode();
        b.btnRepeat.setImageResource(repeat == Player.REPEAT_MODE_ONE
                ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        tint(b.btnRepeat, repeat == Player.REPEAT_MODE_OFF
                ? R.color.echoes_on_surface_variant : R.color.echoes_primary);
    }

    private void tint(android.widget.ImageButton btn, int colorRes) {
        btn.setColorFilter(ContextCompat.getColor(requireContext(), colorRes));
    }

    private static String formatTime(long ms) {
        long totalSec = ms / 1000L;
        return String.format(Locale.ROOT, "%d:%02d", totalSec / 60, totalSec % 60);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}
