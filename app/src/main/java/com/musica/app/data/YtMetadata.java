package com.musica.app.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort cleanup of a YouTube video title + channel into a song
 * title/artist(s) guess, to pre-fill the add form (the user can still edit).
 *
 * <p>Heuristics, in order:
 * <ol>
 *   <li>Strip junk parentheticals/brackets — (Official Video), [Audio],
 *       (Lyrics)… — keeping meaningful ones like (feat. X).</li>
 *   <li>Pull "feat./ft." performers out of the title into the artist list.</li>
 *   <li>If the title reads "Artist - Song", the left side is the lead artist(s);
 *       that dash-form is human-typed and usually the cleanest source.</li>
 *   <li>Otherwise take the lead artist from the channel, minus "VEVO"/"- Topic".</li>
 *   <li>Split every artist string on ",", "&amp;", "x", "feat" into individual
 *       names (the app's model is multi-artist).</li>
 * </ol>
 *
 * <p>{@link #canonicalize} then snaps each guessed name to an existing library
 * artist when they're fuzzy-close, so "BadBunny" (from a VEVO channel) reuses
 * the existing "Bad Bunny" instead of creating a near-duplicate.
 *
 * <p>Pure logic, no Android dependencies.
 */
public final class YtMetadata {

    /** A parsed guess. {@code artists} may be empty when none could be derived. */
    public record Guess(String title, List<String> artists) { }

    // Bracketed groups whose contents match one of these words are noise, not
    // part of the song title. "feat"/"ft" groups don't match, so they survive.
    private static final Pattern JUNK_GROUP = Pattern.compile(
            "[(\\[][^)\\]]*\\b("
                    + "official|lyrics?|audio|video|v[ií]deo|visuali[sz]er|"
                    + "hd|4k|mv|remaster(ed)?|explicit|oficial|full album|music"
                    + ")\\b[^)\\]]*[)\\]]",
            Pattern.CASE_INSENSITIVE);

    // A "feat."/"ft."/"featuring" clause at the end of the title, parenthesized
    // or bare. Group 1 is the performer list.
    private static final Pattern FEAT = Pattern.compile(
            "\\s*[(\\[]?\\s*(?:feat|ft|featuring)\\.?\\s+([^)\\]]+?)\\s*[)\\]]?\\s*$",
            Pattern.CASE_INSENSITIVE);

    // A dash used as an "Artist - Song" separator: surrounded by spaces so we
    // don't split hyphenated words.
    private static final Pattern TITLE_SEPARATOR = Pattern.compile("\\s[-–—]\\s");

    // Separators between collaborating artists. Deliberately conservative: no
    // "/" (breaks AC/DC) and no bare Spanish "y"/"con" (too common as words).
    private static final Pattern ARTIST_SPLIT = Pattern.compile(
            "\\s*[,&×]\\s*|\\s+x\\s+|\\s+(?:feat|ft|featuring)\\.?\\s+",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CHANNEL_TOPIC = Pattern.compile("(?i)\\s*-\\s*topic$");
    private static final Pattern CHANNEL_VEVO = Pattern.compile("(?i)vevo$");

    private YtMetadata() {}

    public static Guess parse(String videoTitle, String channel) {
        String raw = videoTitle == null ? "" : videoTitle;
        String cleaned = collapseSpaces(JUNK_GROUP.matcher(raw).replaceAll("")).trim();
        // If stripping emptied it (e.g. the whole title was "[Official Video]"),
        // keep the original rather than showing nothing.
        if (cleaned.isEmpty()) cleaned = raw.trim();

        // 1. Extract a trailing feat. clause into its own performers, off the title.
        List<String> featArtists = new ArrayList<>();
        Matcher fm = FEAT.matcher(cleaned);
        if (fm.find()) {
            featArtists = splitArtists(fm.group(1));
            cleaned = collapseSpaces(cleaned.substring(0, fm.start())).trim();
        }

        // 2. Lead artist(s) + title: prefer the "Artist - Song" dash form.
        String title;
        List<String> leadArtists;
        String[] parts = TITLE_SEPARATOR.split(cleaned, 2);
        if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
            leadArtists = splitArtists(parts[0].trim());
            title = parts[1].trim();
        } else {
            title = cleaned;
            leadArtists = splitArtists(cleanChannel(channel));
        }

        List<String> artists = new ArrayList<>(leadArtists);
        artists.addAll(featArtists);
        return new Guess(title, dedup(artists));
    }

    /**
     * Snaps each guessed artist to an existing library name when they're close
     * enough (Levenshtein ratio ≥ {@link DuplicateCheck#SIMILAR_THRESHOLD}),
     * picking the best match. Names with no close existing artist are kept as-is.
     * Order is preserved, duplicates dropped.
     */
    public static List<String> canonicalize(List<String> artists, Collection<String> existing) {
        List<String> out = new ArrayList<>();
        for (String a : artists) {
            out.add(bestExisting(a, existing));
        }
        return dedup(out);
    }

    private static String bestExisting(String artist, Collection<String> existing) {
        String best = artist;
        double bestScore = DuplicateCheck.SIMILAR_THRESHOLD;   // must clear the bar
        if (existing != null) {
            for (String e : existing) {
                double s = DuplicateCheck.similarity(artist, e);
                if (s >= bestScore) {
                    bestScore = s;
                    best = e;
                }
            }
        }
        return best;
    }

    private static List<String> splitArtists(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String part : ARTIST_SPLIT.split(s.trim())) {
            String p = part.trim();
            if (!p.isBlank()) out.add(p);
        }
        return out;
    }

    private static String cleanChannel(String channel) {
        if (channel == null) return null;
        String c = CHANNEL_TOPIC.matcher(channel.trim()).replaceAll("");
        c = CHANNEL_VEVO.matcher(c).replaceAll("");
        return c.trim();
    }

    /** Case-insensitive de-dup, preserving first-seen order. */
    private static List<String> dedup(List<String> xs) {
        List<String> out = new ArrayList<>();
        for (String x : xs) {
            boolean seen = false;
            for (String o : out) {
                if (o.equalsIgnoreCase(x)) { seen = true; break; }
            }
            if (!seen) out.add(x);
        }
        return out;
    }

    private static String collapseSpaces(String s) {
        return s.replaceAll("\\s{2,}", " ");
    }
}
