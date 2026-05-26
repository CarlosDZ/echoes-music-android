package com.musica.app.data;

import java.util.Locale;

/**
 * Tiny fuzzy matcher for client-side search over a small personal library.
 * Returns a score (higher = better) or {@code NO_MATCH}. Substring hits rank
 * above scattered subsequence hits; earlier and prefix matches rank higher.
 */
public final class Fuzzy {

    public static final int NO_MATCH = -1;

    private Fuzzy() { }

    /** Score of {@code query} against {@code text}; empty query matches all. */
    public static int score(String query, String text) {
        if (query == null || query.isBlank()) return 0;
        if (text == null) return NO_MATCH;

        String q = query.trim().toLowerCase(Locale.ROOT);
        String t = text.toLowerCase(Locale.ROOT);

        int idx = t.indexOf(q);
        if (idx == 0) return 1000;            // prefix: best
        if (idx > 0) return 800 - idx;        // substring: earlier is better

        // Subsequence: every query char appears in order, possibly scattered.
        int ti = 0;
        for (int i = 0; i < q.length(); i++) {
            ti = t.indexOf(q.charAt(i), ti);
            if (ti < 0) return NO_MATCH;
            ti++;
        }
        return 100;
    }
}
