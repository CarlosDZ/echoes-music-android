package com.musica.app.fragments;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.musica.app.R;
import com.musica.app.data.LocalRepository;
import com.musica.app.data.RemoteRepository;
import com.musica.app.data.Tags;
import com.musica.app.databinding.FragmentAnadirBinding;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Añadir música": pick one MP3, edit its title/artists (pre-filled from tags),
 * then choose where it goes — local, server, or both. The destination prompt is
 * skipped when no server is configured. Both backends converge on single-ingest.
 */
public class AnadirFragment extends Fragment {

    private enum Dest { LOCAL, SERVER, BOTH }

    private FragmentAnadirBinding b;
    private LocalRepository local;
    private RemoteRepository remote;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private ActivityResultLauncher<String[]> picker;
    private Uri pickedUri;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        picker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> { if (uri != null) onPicked(uri); });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentAnadirBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        local = new LocalRepository(requireContext());
        remote = new RemoteRepository(requireContext());

        b.btnPick.setOnClickListener(v -> picker.launch(new String[]{"audio/mpeg"}));
        b.btnAddArtist.setOnClickListener(v -> addTypedArtist());
        b.etArtist.setOnItemClickListener((p, v, pos, id) -> addTypedArtist());
        b.etArtist.setOnEditorActionListener((v, actionId, e) -> {
            addTypedArtist();
            return true;
        });
        b.btnSave.setOnClickListener(v -> onSave());
    }

    private void onPicked(Uri uri) {
        pickedUri = uri;
        b.tvFile.setText(displayName(uri));
        b.tvStatus.setText("");
        b.etTitle.setText("");
        b.chipArtists.removeAllViews();
        b.form.setVisibility(View.VISIBLE);

        // Pre-fill title/artists from the file's tags, and load existing artist
        // names for the autocomplete — both off the main thread.
        final Context app = requireContext().getApplicationContext();
        io.execute(() -> {
            Tags.Read tags = null;
            try {
                tags = Tags.read(app, uri);
            } catch (RuntimeException ignored) {
                // unreadable tags → leave the form blank for the user to fill
            }
            List<String> suggestions = mergedArtists();
            final Tags.Read t = tags;
            View root = getView();
            if (root != null) root.post(() -> prefill(t, suggestions));
        });
    }

    private void prefill(Tags.Read tags, List<String> suggestions) {
        if (b == null) return;
        b.etArtist.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_1, suggestions));
        if (tags != null) {
            if (tags.title() != null) b.etTitle.setText(tags.title());
            for (String a : tags.artists()) addChip(a);
        }
    }

    /** Existing artist names from local + server (silent degrade), deduped. */
    private List<String> mergedArtists() {
        TreeSet<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        set.addAll(local.artists());
        if (remote.isConfigured()) set.addAll(remote.artists());
        return new ArrayList<>(set);
    }

    private void addTypedArtist() {
        String name = b.etArtist.getText() == null ? "" : b.etArtist.getText().toString().trim();
        if (!name.isBlank()) addChip(name);
        b.etArtist.setText("");
    }

    private void addChip(String name) {
        String trimmed = name.trim();
        for (int i = 0; i < b.chipArtists.getChildCount(); i++) {
            Chip existing = (Chip) b.chipArtists.getChildAt(i);
            if (existing.getText().toString().equalsIgnoreCase(trimmed)) return;  // no dups
        }
        Chip chip = new Chip(requireContext());
        chip.setText(trimmed);
        chip.setCloseIconVisible(true);
        chip.setOnCloseIconClickListener(v -> b.chipArtists.removeView(chip));
        b.chipArtists.addView(chip);
    }

    private List<String> collectArtists() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < b.chipArtists.getChildCount(); i++) {
            out.add(((Chip) b.chipArtists.getChildAt(i)).getText().toString());
        }
        return out;
    }

    private void onSave() {
        if (pickedUri == null) return;
        addTypedArtist();   // fold any text left in the box into a chip

        String title = b.etTitle.getText() == null ? "" : b.etTitle.getText().toString().trim();
        List<String> artists = collectArtists();

        if (remote.isConfigured()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.add_dest_title)
                    .setItems(new CharSequence[]{
                            getString(R.string.add_dest_local),
                            getString(R.string.add_dest_server),
                            getString(R.string.add_dest_both)
                    }, (d, which) -> {
                        Dest dest = switch (which) {
                            case 1 -> Dest.SERVER;
                            case 2 -> Dest.BOTH;
                            default -> Dest.LOCAL;
                        };
                        process(pickedUri, dest, title, artists);
                    })
                    .show();
        } else {
            process(pickedUri, Dest.LOCAL, title, artists);
        }
    }

    private void process(Uri uri, Dest dest, String title, List<String> artists) {
        final Context app = requireContext().getApplicationContext();
        b.btnSave.setEnabled(false);
        b.btnPick.setEnabled(false);
        setStatus(getString(R.string.add_processing), R.color.echoes_on_surface_variant);

        io.execute(() -> {
            StringBuilder msg = new StringBuilder();
            boolean ok = true;

            if (dest == Dest.LOCAL || dest == Dest.BOTH) {
                try {
                    LocalRepository.IngestResult r = local.ingest(app, uri, title, artists);
                    msg.append(r.created()
                            ? "Local: añadida"
                            : "Local: ya estaba (no duplicada)");
                } catch (IOException | RuntimeException e) {
                    ok = false;
                    msg.append("Local: error — ").append(e.getMessage());
                }
            }

            if (dest == Dest.SERVER || dest == Dest.BOTH) {
                if (msg.length() > 0) msg.append("\n");
                RemoteRepository.UploadResult r = remote.upload(app, uri, title, artists);
                switch (r.status()) {
                    case CREATED -> msg.append("Servidor: subida correctamente");
                    case DUPLICATE -> msg.append("Servidor: ya existía (metadatos actualizados)");
                    case UNAUTHORIZED -> { ok = false; msg.append("Servidor: clave incorrecta"); }
                    case NOT_CONFIGURED -> { ok = false; msg.append("Servidor: no configurado"); }
                    case ERROR -> { ok = false; msg.append("Servidor: error — ").append(r.detail()); }
                }
            }

            final boolean success = ok;
            final String text = msg.toString();
            View root = getView();
            if (root != null) root.post(() -> showResult(text, success));
        });
    }

    private void showResult(String text, boolean ok) {
        if (b == null) return;
        b.btnSave.setEnabled(true);
        b.btnPick.setEnabled(true);
        setStatus(text, ok ? R.color.echoes_success : R.color.echoes_error);
    }

    private void setStatus(String msg, int colorRes) {
        b.tvStatus.setText(msg);
        b.tvStatus.setTextColor(ContextCompat.getColor(requireContext(), colorRes));
    }

    /** Best-effort human-readable file name from a content Uri. */
    private String displayName(Uri uri) {
        try (Cursor c = requireContext().getContentResolver()
                .query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (RuntimeException ignored) {
            // fall through to the raw uri
        }
        return uri.getLastPathSegment();
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
