package com.musica.app.data;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Thin connectivity probe against the music server. This is NOT the future
 * {@code RemoteRepository} — it only validates the URL + key configured in
 * settings, with no DB, no caching and no model parsing.
 *
 * <p>Uses {@link HttpURLConnection} from the JDK on purpose: the project keeps
 * dependencies minimal, so no OkHttp/Retrofit.
 */
public final class ServerProbe {

    public enum Status { OK, BAD_KEY, UNREACHABLE }

    /** Outcome of a probe; {@code detail} carries extra context for failures. */
    public record Result(Status status, String detail) {}

    private static final int TIMEOUT_MS = 5000;

    private ServerProbe() {}

    /**
     * Runs blocking network I/O — must be called off the main thread.
     *
     * <p>Strategy: {@code /health} needs no auth, so it answers "is the server
     * reachable?". Then an authenticated endpoint ({@code /songs}) distinguishes
     * a valid key (200) from a wrong one (401).
     */
    public static Result test(String rawUrl, String apiKey) {
        String base = normalize(rawUrl);
        if (base.isEmpty()) {
            return new Result(Status.UNREACHABLE, "URL vacía");
        }

        try {
            int health = get(base + "/health", null);
            if (health != 200) {
                return new Result(Status.UNREACHABLE,
                        "El servidor respondió " + health + " en /health");
            }
        } catch (IOException e) {
            return new Result(Status.UNREACHABLE, e.getMessage());
        }

        try {
            int songs = get(base + "/songs", apiKey);
            if (songs == 200) return new Result(Status.OK, null);
            if (songs == 401) return new Result(Status.BAD_KEY, null);
            return new Result(Status.UNREACHABLE,
                    "Respuesta inesperada (" + songs + ") en /songs");
        } catch (IOException e) {
            return new Result(Status.UNREACHABLE, e.getMessage());
        }
    }

    /** Issues a GET and returns the HTTP status code. */
    private static int get(String url, String apiKey) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("GET");
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            // Server expects the literal key in Authorization (no "Bearer ").
            if (apiKey != null && !apiKey.isEmpty()) {
                c.setRequestProperty("Authorization", apiKey);
            }
            return c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    /** Trims whitespace and strips trailing slashes so paths concat cleanly. */
    private static String normalize(String raw) {
        String s = raw == null ? "" : raw.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
