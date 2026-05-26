package com.musica.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import android.view.View;
import android.widget.ImageButton;

import androidx.media3.common.Player;

import com.musica.app.databinding.ActivityMainBinding;
import com.musica.app.fragments.AjustesFragment;
import com.musica.app.fragments.AnadirFragment;
import com.musica.app.fragments.BuscarFragment;
import com.musica.app.fragments.PlaylistsFragment;
import com.musica.app.fragments.ReproductorFragment;
import com.musica.app.playback.PlaybackController;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private PlaybackController pc;
    private int currentTab = R.id.nav_buscar;
    private final PlaybackController.Listener miniListener = this::updateMini;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        if (savedInstanceState == null) {
            switchTo(new BuscarFragment());
            binding.bottomNav.setSelectedItemId(R.id.nav_buscar);
        }

        // Android 13+ needs this permission for the media notification to show.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        binding.bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            Fragment f;
            if      (id == R.id.nav_buscar)       f = new BuscarFragment();
            else if (id == R.id.nav_playlists)    f = new PlaylistsFragment();
            else if (id == R.id.nav_reproductor)  f = new ReproductorFragment();
            else if (id == R.id.nav_anadir)       f = new AnadirFragment();
            else if (id == R.id.nav_ajustes)      f = new AjustesFragment();
            else return false;
            currentTab = id;
            switchTo(f);
            updateMini();
            return true;
        });

        pc = PlaybackController.get(this);
        binding.miniPlayer.setOnClickListener(v -> openReproductor());
        binding.miniPlay.setOnClickListener(v -> { pc.toggle(); updateMini(); });
        binding.miniPrev.setOnClickListener(v -> pc.prev());
        binding.miniNext.setOnClickListener(v -> pc.next());
        binding.miniShuffle.setOnClickListener(v -> { pc.toggleShuffle(); updateMini(); });
        binding.miniRepeat.setOnClickListener(v -> { pc.cycleRepeat(); updateMini(); });
    }

    @Override
    protected void onStart() {
        super.onStart();
        pc.addListener(miniListener);
        updateMini();
    }

    @Override
    protected void onStop() {
        super.onStop();
        pc.removeListener(miniListener);
    }

    /** Persistent bar with the current track; hidden when idle or on the player tab. */
    private void updateMini() {
        boolean show = pc.hasQueue() && currentTab != R.id.nav_reproductor;
        binding.miniPlayer.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        binding.miniTitle.setText(pc.currentTitle());
        binding.miniArtist.setText(pc.currentArtists());
        binding.miniPlay.setImageResource(
                pc.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);

        tintMini(binding.miniShuffle,
                pc.shuffle() ? R.color.echoes_primary : R.color.echoes_on_surface_variant);
        int repeat = pc.repeatMode();
        binding.miniRepeat.setImageResource(repeat == Player.REPEAT_MODE_ONE
                ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        tintMini(binding.miniRepeat, repeat == Player.REPEAT_MODE_OFF
                ? R.color.echoes_on_surface_variant : R.color.echoes_primary);
    }

    private void tintMini(ImageButton btn, int colorRes) {
        btn.setColorFilter(ContextCompat.getColor(this, colorRes));
    }

    /** Navigates to the Now Playing tab (used after starting playback). */
    public void openReproductor() {
        binding.bottomNav.setSelectedItemId(R.id.nav_reproductor);
    }

    /** Pushes a detail screen over the current tab; back returns to it. */
    public void openDetail(Fragment f) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.contenedor_fragments, f)
                .addToBackStack(null)
                .commit();
    }

    private void switchTo(Fragment f) {
        // Drop any pushed detail screen so tab switches always reset to the tab root.
        getSupportFragmentManager().popBackStack(null,
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.contenedor_fragments, f)
                .commit();
    }
}
