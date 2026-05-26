package com.musica.app.data;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;

import com.musica.app.model.Playlist;
import com.musica.app.model.Song;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin HTTP client to the music server. For now: upload (ingest) and the artist
 * list (for the add-form autocomplete). Reuses {@link Prefs} for the base URL +
 * key and {@link HttpURLConnection} (no extra deps), like {@link ServerProbe}.
 * JSON via {@code org.json}, which ships with Android.
 */
public final class RemoteRepository {

    public enum Status { CREATED, DUPLICATE, UNAUTHORIZED, NOT_CONFIGURED, ERROR }

    public record UploadResult(Status status, String detail, long songId) {
        /** Convenience for failure cases that have no song id. */
        public UploadResult(Status status, String detail) {
            this(status, detail, -1);
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 60000;   // uploads can be large

    private final Prefs prefs;
    private final Context app;

    public RemoteRepository(Context context) {
        this.app = context.getApplicationContext();
        this.prefs = new Prefs(app);
    }

    public boolean isConfigured() {
        return !prefs.serverUrl().trim().isEmpty();
    }

    /** True if there's no usable network — lets callers skip the connect timeout. */
    private boolean offline() {
        ConnectivityManager cm =
                (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;   // can't tell → let the request try
        Network n = cm.getActiveNetwork();
        if (n == null) return true;
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        return caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /**
     * Streams the MP3 to {@code POST /songs}, then applies the user's title /
     * artists with {@code PATCH /songs/{id}}. Blocking — call off the main
     * thread. The server replies 201 (new) or 200 (duplicate by hash).
     */
    public UploadResult upload(Context context, Uri uri, String title, List<String> artists) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return new UploadResult(Status.ERROR, "no se pudo abrir el archivo");
            return uploadStream(in, title, artists);
        } catch (IOException e) {
            return new UploadResult(Status.ERROR, e.getMessage());
        }
    }

    /** Uploads an already-stored local file (used to push a local song up). */
    public UploadResult upload(File file, String title, List<String> artists) {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            return uploadStream(in, title, artists);
        } catch (IOException e) {
            return new UploadResult(Status.ERROR, e.getMessage());
        }
    }

    private UploadResult uploadStream(InputStream in, String title, List<String> artists) {
        String base = normalize(prefs.serverUrl());
        if (base.isEmpty()) {
            return new UploadResult(Status.NOT_CONFIGURED, null);
        }
        if (offline()) {
            return new UploadResult(Status.ERROR, "sin conexión");
        }

        HttpURLConnection c = null;
        boolean created;
        long songId;
        try {
            c = (HttpURLConnection) new URL(base + "/songs").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", prefs.apiKey());
            c.setRequestProperty("Content-Type", "audio/mpeg");
            c.setChunkedStreamingMode(0);

            try (OutputStream out = c.getOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }

            int code = c.getResponseCode();
            if (code == 401) return new UploadResult(Status.UNAUTHORIZED, null);
            if (code != 200 && code != 201) {
                return new UploadResult(Status.ERROR, "HTTP " + code);
            }
            created = (code == 201);
            songId = new JSONObject(readBody(c)).getLong("id");
        } catch (IOException | JSONException e) {
            return new UploadResult(Status.ERROR, e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }

        // Apply the metadata the user entered (overrides what the server read
        // from tags). Applies to duplicates too: same file, user's chosen tags.
        if (hasMetadata(title, artists)) {
            try {
                JSONObject body = new JSONObject();
                if (title != null && !title.isBlank()) body.put("title", title.trim());
                JSONArray arr = new JSONArray();
                if (artists != null) for (String a : artists) arr.put(a);
                body.put("artists", arr);

                int pc = patch(base + "/songs/" + songId, body.toString());
                if (pc == 401) return new UploadResult(Status.UNAUTHORIZED, null);
                if (pc != 200) return new UploadResult(Status.ERROR, "PATCH HTTP " + pc);
            } catch (IOException | JSONException e) {
                return new UploadResult(Status.ERROR, "metadatos: " + e.getMessage());
            }
        }

        return new UploadResult(created ? Status.CREATED : Status.DUPLICATE, null, songId);
    }

    /** All songs on the server. Empty on any failure (silent degradation). */
    public List<Song> allSongs() {
        List<Song> out = new ArrayList<>();
        String base = normalize(prefs.serverUrl());
        if (base.isEmpty() || offline()) return out;

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + "/songs").openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestProperty("Authorization", prefs.apiKey());
            if (c.getResponseCode() != 200) return out;
            JSONArray arr = new JSONArray(readBody(c));
            for (int i = 0; i < arr.length(); i++) {
                out.add(parseSong(arr.getJSONObject(i)));
            }
        } catch (IOException | JSONException e) {
            return new ArrayList<>();   // partial parse → return nothing, stay silent
        } finally {
            if (c != null) c.disconnect();
        }
        return out;
    }

    private static Song parseSong(JSONObject o) throws JSONException {
        List<String> artists = new ArrayList<>();
        JSONArray ar = o.optJSONArray("artists");
        if (ar != null) {
            for (int i = 0; i < ar.length(); i++) artists.add(ar.getString(i));
        }
        return new Song(
                o.getLong("id"),
                stringOrNull(o, "path"),
                stringOrNull(o, "fileHash"),
                stringOrNull(o, "title"),
                artists,
                o.optInt("duration", 0),
                o.optLong("addedAt", 0L));
    }

    private static String stringOrNull(JSONObject o, String key) {
        return o.isNull(key) ? null : o.optString(key, null);
    }

    /** Artist names known to the server. Empty on any failure (silent). */
    public List<String> artists() {
        List<String> out = new ArrayList<>();
        String base = normalize(prefs.serverUrl());
        if (base.isEmpty() || offline()) return out;

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + "/artists").openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(CONNECT_TIMEOUT_MS);
            c.setRequestProperty("Authorization", prefs.apiKey());
            if (c.getResponseCode() != 200) return out;
            JSONArray arr = new JSONArray(readBody(c));
            for (int i = 0; i < arr.length(); i++) {
                out.add(arr.getJSONObject(i).getString("name"));
            }
        } catch (IOException | JSONException e) {
            return out;   // degrade silently to whatever local provided
        } finally {
            if (c != null) c.disconnect();
        }
        return out;
    }

    /** Updates title + artists on the server. Returns true on success (200). */
    public boolean patchSong(long id, String title, List<String> artists) {
        String base = normalize(prefs.serverUrl());
        if (base.isEmpty() || offline()) return false;
        try {
            JSONObject body = new JSONObject();
            body.put("title", title == null ? "" : title.trim());
            JSONArray arr = new JSONArray();
            if (artists != null) for (String a : artists) arr.put(a);
            body.put("artists", arr);
            return patch(base + "/songs/" + id, body.toString()) == 200;
        } catch (IOException | JSONException e) {
            return false;
        }
    }

    /** Deletes a song on the server. Returns true on success (204). */
    public boolean deleteSong(long id) {
        String base = normalize(prefs.serverUrl());
        if (base.isEmpty() || offline()) return false;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + "/songs/" + id).openConnection();
            c.setRequestMethod("DELETE");
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(CONNECT_TIMEOUT_MS);
            c.setRequestProperty("Authorization", prefs.apiKey());
            int code = c.getResponseCode();
            return code == 204 || code == 200;
        } catch (IOException e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Downloads a song's file into {@code dest}. Returns true on success. */
    public boolean downloadFile(long id, File dest) {
        String base = normalize(prefs.serverUrl());
        if (base.isEmpty() || offline()) return false;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + "/songs/" + id + "/file").openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestProperty("Authorization", prefs.apiKey());
            if (c.getResponseCode() != 200) return false;
            try (InputStream in = c.getInputStream();
                 OutputStream out = Files.newOutputStream(dest.toPath())) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---------------- playlists (server) ----------------

    public List<Playlist> playlists() {
        List<Playlist> out = new ArrayList<>();
        String base = normalize(prefs.serverUrl());
        if (base.isEmpty() || offline()) return out;
        HttpURLConnection c = null;
        try {
            c = get(base + "/playlists");
            if (c.getResponseCode() != 200) return out;
            JSONArray arr = new JSONArray(readBody(c));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Playlist(o.getLong("id"), o.optString("name", "")));
            }
        } catch (IOException | JSONException e) {
            return new ArrayList<>();
        } finally {
            if (c != null) c.disconnect();
        }
        return out;
    }

    /** Songs of a server playlist, in order. */
    public List<Song> playlistSongs(long playlistId) {
        List<Song> out = new ArrayList<>();
        String base = normalize(prefs.serverUrl());
        if (base.isEmpty() || offline()) return out;
        HttpURLConnection c = null;
        try {
            c = get(base + "/playlists/" + playlistId + "/songs");
            if (c.getResponseCode() != 200) return out;
            JSONArray arr = new JSONArray(readBody(c));
            for (int i = 0; i < arr.length(); i++) out.add(parseSong(arr.getJSONObject(i)));
        } catch (IOException | JSONException e) {
            return new ArrayList<>();
        } finally {
            if (c != null) c.disconnect();
        }
        return out;
    }

    /** Creates a server playlist; returns its id or -1 on failure. */
    public long createPlaylist(String name) {
        String base = normalize(prefs.serverUrl());
        if (base.isEmpty() || offline()) return -1;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + "/playlists").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(CONNECT_TIMEOUT_MS);
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", prefs.apiKey());
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(new JSONObject().put("name", name).toString()
                        .getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() != 201) return -1;
            return new JSONObject(readBody(c)).getLong("id");
        } catch (IOException | JSONException e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public boolean deletePlaylist(long playlistId) {
        return sendNoBody("DELETE", "/playlists/" + playlistId);
    }

    public boolean addSongToPlaylist(long playlistId, long songId) {
        String base = normalize(prefs.serverUrl());
        if (base.isEmpty() || offline()) return false;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + "/playlists/" + playlistId + "/songs")
                    .openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(CONNECT_TIMEOUT_MS);
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", prefs.apiKey());
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(new JSONObject().put("songId", songId).toString()
                        .getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            return code == 200 || code == 201 || code == 204;
        } catch (IOException | JSONException e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public boolean removeSongFromPlaylist(long playlistId, long songId) {
        return sendNoBody("DELETE", "/playlists/" + playlistId + "/songs/" + songId);
    }

    private HttpURLConnection get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestProperty("Authorization", prefs.apiKey());
        return c;
    }

    private boolean sendNoBody(String method, String path) {
        String base = normalize(prefs.serverUrl());
        if (base.isEmpty() || offline()) return false;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + path).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(CONNECT_TIMEOUT_MS);
            c.setRequestProperty("Authorization", prefs.apiKey());
            int code = c.getResponseCode();
            return code == 200 || code == 204;
        } catch (IOException e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private int patch(String url, String jsonBody) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("PATCH");   // Android's HttpURLConnection allows PATCH
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(CONNECT_TIMEOUT_MS);
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", prefs.apiKey());
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
            return c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    private static boolean hasMetadata(String title, List<String> artists) {
        return (title != null && !title.isBlank())
                || (artists != null && !artists.isEmpty());
    }

    private static String readBody(HttpURLConnection c) throws IOException {
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        try (InputStream src = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = src.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String normalize(String raw) {
        String s = raw == null ? "" : raw.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
