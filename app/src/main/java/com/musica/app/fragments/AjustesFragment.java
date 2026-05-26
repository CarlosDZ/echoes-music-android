package com.musica.app.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.musica.app.R;
import com.musica.app.data.Prefs;
import com.musica.app.data.ServerProbe;
import com.musica.app.databinding.FragmentAjustesBinding;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings screen. For now it only configures the remote-server connection
 * (URL + pre-shared key) and lets the user test it. Storage management and the
 * local music folder come later, once LocalRepository exists.
 */
public class AjustesFragment extends Fragment {

    private FragmentAjustesBinding b;
    private Prefs prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentAjustesBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        prefs = new Prefs(requireContext());
        b.etUrl.setText(prefs.serverUrl());
        b.etKey.setText(prefs.apiKey());
        b.btnTest.setOnClickListener(v -> saveAndTest());
    }

    private void saveAndTest() {
        String url = text(b.etUrl);
        String key = text(b.etKey);
        prefs.save(url, key);

        b.btnTest.setEnabled(false);
        setStatus(getString(R.string.settings_testing), R.color.echoes_on_surface_variant);

        io.execute(() -> {
            ServerProbe.Result r = ServerProbe.test(url, key);
            View root = getView();
            if (root != null) {
                root.post(() -> showResult(r));
            }
        });
    }

    private void showResult(ServerProbe.Result r) {
        if (b == null) return;   // view destroyed while the probe was running
        b.btnTest.setEnabled(true);
        switch (r.status()) {
            case OK -> setStatus(getString(R.string.settings_status_ok),
                    R.color.echoes_success);
            case BAD_KEY -> setStatus(getString(R.string.settings_status_bad_key),
                    R.color.echoes_error);
            case UNREACHABLE -> {
                String msg = getString(R.string.settings_status_unreachable);
                if (r.detail() != null && !r.detail().isEmpty()) {
                    msg += "\n" + r.detail();
                }
                setStatus(msg, R.color.echoes_error);
            }
        }
    }

    private void setStatus(String msg, int colorRes) {
        b.tvStatus.setText(msg);
        b.tvStatus.setTextColor(ContextCompat.getColor(requireContext(), colorRes));
    }

    private static String text(com.google.android.material.textfield.TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
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
