package com.musica.app.data;

import com.musica.app.model.Song;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Soft, advisory near-duplicate detection used when adding/uploading a song.
 * A candidate matches when it shares at least one author (normalized) AND has a
 * similar title. Title similarity is symmetric (normalize both, then equality or
 * an edit-distance ratio) — deliberately NOT the type-ahead {@link Fuzzy}.
 *
 * <p>This complements the byte hash, which only catches exact-file duplicates;
 * this catches "same song from another source" (re-encodes, slightly different
 * length, etc.). It never blocks — the caller just warns the user.
 */
public final class DuplicateCheck {

    /** Strings at or above this Levenshtein ratio (0..1) count as "the same". */
    public static final double SIMILAR_THRESHOLD = 0.85;

    private DuplicateCheck() { }

    /** Candidates that share ≥1 author with the input and have a similar title. */
    public static List<Song> findSimilar(String title, List<String> artists, List<Song> candidates) {
        List<Song> out = new ArrayList<>();
        if (title == null || title.isBlank()) return out;
        Set<String> wanted = normalizedSet(artists);
        if (wanted.isEmpty()) return out;   // no author → can't satisfy the rule

        for (Song s : candidates) {
            if (sharesAuthor(wanted, s.artists()) && titlesSimilar(title, s.title())) {
                out.add(s);
            }
        }
        return out;
    }

    private static boolean sharesAuthor(Set<String> wanted, List<String> candidateArtists) {
        if (candidateArtists == null) return false;
        for (String a : candidateArtists) {
            if (a != null && wanted.contains(norm(a))) return true;
        }
        return false;
    }

    public static boolean titlesSimilar(String a, String b) {
        return similarity(a, b) >= SIMILAR_THRESHOLD;
    }

    /**
     * Symmetric similarity in [0,1] from the Levenshtein edit distance over
     * normalized strings. Reused for both song titles and artist names.
     */
    public static double similarity(String a, String b) {
        if (a == null || b == null) return 0;
        String na = norm(a);
        String nb = norm(b);
        if (na.isEmpty() || nb.isEmpty()) return 0;
        if (na.equals(nb)) return 1;
        int max = Math.max(na.length(), nb.length());
        return (max - levenshtein(na, nb)) / (double) max;
    }

    private static Set<String> normalizedSet(List<String> xs) {
        Set<String> set = new HashSet<>();
        if (xs != null) {
            for (String x : xs) if (x != null && !x.isBlank()) set.add(norm(x));
        }
        return set;
    }

    /** UPPERCASE + trim + collapse inner whitespace. */
    private static String norm(String s) {
        return s.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }
}
