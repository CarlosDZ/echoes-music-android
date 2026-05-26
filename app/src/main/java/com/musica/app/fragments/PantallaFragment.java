package com.musica.app.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.musica.app.databinding.FragmentPantallaBinding;

public abstract class PantallaFragment extends Fragment {

    protected abstract String etiqueta();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        FragmentPantallaBinding b = FragmentPantallaBinding.inflate(inflater, container, false);
        b.titulo.setText(etiqueta());
        return b.getRoot();
    }
}
