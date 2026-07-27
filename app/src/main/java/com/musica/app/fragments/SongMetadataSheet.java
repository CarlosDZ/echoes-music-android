package com.musica.app.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.chip.Chip;
import com.musica.app.databinding.BottomSheetSongMetadataBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * Modal (bottom sheet) that collects a song's title + artists, pre-filled by the
 * caller. On save it publishes the values via the Fragment Result API and
 * dismisses; the host ({@link AnadirFragment}) runs the actual ingest pipeline.
 * This keeps the sheet ignorant of where the song ends up.
 */
public class SongMetadataSheet extends BottomSheetDialogFragment {

    public static final String RESULT_KEY = "song_metadata_result";
    public static final String ARG_TITLE = "title";
    public static final String ARG_ARTISTS = "artists";
    private static final String ARG_SUGGESTIONS = "suggestions";

    public static SongMetadataSheet newInstance(String title, ArrayList<String> artists,
                                                ArrayList<String> suggestions) {
        SongMetadataSheet s = new SongMetadataSheet();
        Bundle args = new Bundle();
        args.putString(ARG_TITLE, title);
        args.putStringArrayList(ARG_ARTISTS, artists);
        args.putStringArrayList(ARG_SUGGESTIONS, suggestions);
        s.setArguments(args);
        return s;
    }

    private BottomSheetSongMetadataBinding b;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = BottomSheetSongMetadataBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Bundle args = requireArguments();
        b.etSheetTitle.setText(args.getString(ARG_TITLE, ""));

        List<String> suggestions = args.getStringArrayList(ARG_SUGGESTIONS);
        if (suggestions == null) suggestions = new ArrayList<>();
        b.etSheetArtist.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_1, suggestions));

        List<String> artists = args.getStringArrayList(ARG_ARTISTS);
        if (artists != null) for (String a : artists) addChip(a);

        b.btnSheetAddArtist.setOnClickListener(v -> addTypedArtist());
        b.etSheetArtist.setOnItemClickListener((p, v, pos, id) -> addTypedArtist());
        b.etSheetArtist.setOnEditorActionListener((v, actionId, e) -> {
            addTypedArtist();
            return true;
        });
        b.btnSheetSave.setOnClickListener(v -> save());
    }

    private void save() {
        addTypedArtist();   // fold any text left in the box into a chip
        String title = b.etSheetTitle.getText() == null
                ? "" : b.etSheetTitle.getText().toString().trim();
        Bundle result = new Bundle();
        result.putString(ARG_TITLE, title);
        result.putStringArrayList(ARG_ARTISTS, collectArtists());
        getParentFragmentManager().setFragmentResult(RESULT_KEY, result);
        dismiss();
    }

    private void addTypedArtist() {
        String name = b.etSheetArtist.getText() == null
                ? "" : b.etSheetArtist.getText().toString().trim();
        if (!name.isBlank()) addChip(name);
        b.etSheetArtist.setText("");
    }

    private void addChip(String name) {
        String trimmed = name.trim();
        for (int i = 0; i < b.chipSheetArtists.getChildCount(); i++) {
            Chip existing = (Chip) b.chipSheetArtists.getChildAt(i);
            if (existing.getText().toString().equalsIgnoreCase(trimmed)) return;  // no dups
        }
        Chip chip = new Chip(requireContext());
        chip.setText(trimmed);
        chip.setCloseIconVisible(true);
        chip.setOnCloseIconClickListener(v -> b.chipSheetArtists.removeView(chip));
        b.chipSheetArtists.addView(chip);
    }

    private ArrayList<String> collectArtists() {
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; i < b.chipSheetArtists.getChildCount(); i++) {
            out.add(((Chip) b.chipSheetArtists.getChildAt(i)).getText().toString());
        }
        return out;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}
