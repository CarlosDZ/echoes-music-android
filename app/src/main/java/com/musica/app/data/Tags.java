package com.musica.app.data;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads ID3 tags with Android's native {@link MediaMetadataRetriever}. The
 * server reads the same fields with mp3agic; the DB core stays agnostic of how
 * tags were obtained (project design rule §5), so this returns the same shape:
 * title, ordered artists, duration in seconds.
 */
public final class Tags {

    private Tags() { }

    public record Read(String title, List<String> artists, int duration) { }

    /** Reads from a stored file (used during local ingest, mainly for duration). */
    public static Read read(File mp3) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        mmr.setDataSource(mp3.getAbsolutePath());
        return extract(mmr);
    }

    /** Reads from a content {@link Uri} (used to pre-fill the add form). */
    public static Read read(Context context, Uri uri) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        mmr.setDataSource(context, uri);
        return extract(mmr);
    }

    private static Read extract(MediaMetadataRetriever mmr) {
        try {
            String title = nullIfBlank(
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            String artistStr = nullIfBlank(
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            int duration = parseSeconds(
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            return new Read(title, splitArtists(artistStr), duration);
        } finally {
            try {
                mmr.release();
            } catch (java.io.IOException ignored) {
                // release() declares IOException since API 29; nothing to do.
            }
        }
    }

    private static int parseSeconds(String millisStr) {
        if (millisStr == null) return 0;
        try {
            return (int) (Long.parseLong(millisStr.trim()) / 1000L);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * Splits a multi-artist string. ID3v2.4 uses NUL; many tags use ';' or '/'.
     * Note: the server also splits on spaces, which mangles names like
     * "Daft Punk" — we deliberately do NOT split on spaces here. Artists don't
     * affect the file hash, so local/remote dedup is unaffected by the
     * difference; worth aligning the server later.
     */
    private static List<String> splitArtists(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String part : s.split("[;/\\x00]")) {
            String t = part.trim();
            if (!t.isBlank()) out.add(t);
        }
        return out;
    }
}
