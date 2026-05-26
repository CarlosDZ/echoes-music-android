package com.musica.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import com.musica.app.model.Playlist;
import com.musica.app.model.Song;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Local-first music store. Owns the SQLite DB and the on-disk MP3 files under
 * the app's private {@code files/music} directory.
 *
 * <p>Mirrors the server's single-ingest design: mapping/uploading/cloning all
 * converge on {@link #ingest}. Files are named by their SHA-256 (flat storage),
 * and content is deduplicated by that hash — the same MP3 never stored twice.
 */
public final class LocalRepository {

    private final MusicDb db;
    private final File musicRoot;

    public LocalRepository(Context context) {
        Context app = context.getApplicationContext();
        this.db = new MusicDb(app);
        this.musicRoot = new File(app.getFilesDir(), "music");
        //noinspection ResultOfMethodCallIgnored
        this.musicRoot.mkdirs();
    }

    public record IngestResult(Song song, boolean created) { }

    /**
     * Ingests an MP3 selected by the user (a content {@link Uri}) with the
     * title/artists the user confirmed in the form. Blocking I/O — call off the
     * main thread.
     *
     * <p>Steps: copy bytes to a temp file → hash → if the hash already exists,
     * drop the temp and return the existing row → otherwise move to
     * {@code <hash>.mp3}, insert with the given metadata (duration still comes
     * from the file's tags).
     */
    public IngestResult ingest(Context context, Uri uri, String title, List<String> artists)
            throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("no se pudo abrir el archivo seleccionado");
            return ingestStream(in, title, artists);
        }
    }

    /** Ingests from an already-downloaded file (used when cloning a remote song). */
    public IngestResult ingest(File source, String title, List<String> artists)
            throws IOException {
        try (InputStream in = Files.newInputStream(source.toPath())) {
            return ingestStream(in, title, artists);
        }
    }

    /** Absolute file for a stored relative path (for uploading a local song). */
    public File fileOf(String relativePath) {
        return new File(musicRoot, relativePath);
    }

    private IngestResult ingestStream(InputStream in, String title, List<String> artists)
            throws IOException {
        File tmp = File.createTempFile("ingest", ".mp3", musicRoot);
        try {
            copy(in, tmp);

            String hash = sha256(tmp);

            Song existing = findByHash(hash);
            if (existing != null) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return new IngestResult(existing, false);
            }

            String relativePath = hash + ".mp3";
            File dest = new File(musicRoot, relativePath);
            if (!tmp.renameTo(dest)) {
                Files.copy(tmp.toPath(), dest.toPath());
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }

            int duration = Tags.read(dest).duration();
            String cleanTitle = (title == null || title.isBlank()) ? null : title.trim();
            Song song = insert(relativePath, hash, cleanTitle, artists, duration);
            return new IngestResult(song, true);
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
    }

    /** Inserts the song row plus its ordered artists in one transaction. */
    private Song insert(String relativePath, String hash, String title,
                        List<String> rawArtists, int duration) {
        List<String> artists = cleanArtists(rawArtists);
        long now = System.currentTimeMillis() / 1000L;

        SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            cv.put("path", relativePath);
            cv.put("file_hash", hash);
            cv.put("title", title);
            cv.put("duration", duration);
            cv.put("added_at", now);
            long songId = w.insertOrThrow("songs", null, cv);

            int pos = 0;
            for (String name : artists) {
                long artistId = upsertArtist(w, name);
                ContentValues link = new ContentValues();
                link.put("song_id", songId);
                link.put("artist_id", artistId);
                link.put("position", pos++);
                w.insertOrThrow("song_artists", null, link);
            }

            w.setTransactionSuccessful();
            return new Song(songId, relativePath, hash, title, artists, duration, now);
        } finally {
            w.endTransaction();
        }
    }

    /** Updates a song's title and replaces its artist list, in one transaction. */
    public void updateSong(long id, String title, List<String> rawArtists) {
        SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            cv.put("title", (title == null || title.isBlank()) ? null : title.trim());
            w.update("songs", cv, "id = ?", new String[]{String.valueOf(id)});

            w.delete("song_artists", "song_id = ?", new String[]{String.valueOf(id)});
            int pos = 0;
            for (String name : cleanArtists(rawArtists)) {
                long artistId = upsertArtist(w, name);
                ContentValues link = new ContentValues();
                link.put("song_id", id);
                link.put("artist_id", artistId);
                link.put("position", pos++);
                w.insertOrThrow("song_artists", null, link);
            }
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
    }

    /** Deletes a song row (cascades to links) and its on-disk file. */
    public void delete(long id) {
        SQLiteDatabase w = db.getWritableDatabase();
        String path = null;
        try (Cursor c = w.rawQuery("SELECT path FROM songs WHERE id = ?",
                new String[]{String.valueOf(id)})) {
            if (c.moveToFirst()) path = c.getString(0);
        }
        if (path == null) return;
        w.delete("songs", "id = ?", new String[]{String.valueOf(id)});
        File f = new File(musicRoot, path);
        //noinspection ResultOfMethodCallIgnored
        if (f.exists()) f.delete();
    }

    private long upsertArtist(SQLiteDatabase w, String name) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        long id = w.insertWithOnConflict("artists", null, cv,
                SQLiteDatabase.CONFLICT_IGNORE);
        if (id != -1) return id;
        // Already present: look up its id.
        try (Cursor c = w.rawQuery("SELECT id FROM artists WHERE name = ?",
                new String[]{name})) {
            c.moveToFirst();
            return c.getLong(0);
        }
    }

    private Song findByHash(String hash) {
        List<Song> list = loadSongs(
                "SELECT id, path, file_hash, title, duration, added_at FROM songs WHERE file_hash = ?",
                new String[]{hash});
        return list.isEmpty() ? null : list.get(0);
    }

    /** All songs, alphabetical. Used by later screens (Buscar/biblioteca). */
    public List<Song> allSongs() {
        return loadSongs(
                "SELECT id, path, file_hash, title, duration, added_at FROM songs ORDER BY title",
                null);
    }

    // ---------------- playlists (local, membership by file_hash) ----------------

    public List<Playlist> playlists() {
        SQLiteDatabase r = db.getReadableDatabase();
        List<Playlist> out = new ArrayList<>();
        try (Cursor c = r.rawQuery("SELECT id, name FROM playlists ORDER BY name", null)) {
            while (c.moveToNext()) out.add(new Playlist(c.getLong(0), c.getString(1)));
        }
        return out;
    }

    public Playlist createPlaylist(String name) {
        SQLiteDatabase w = db.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        long id = w.insertOrThrow("playlists", null, cv);
        return new Playlist(id, name);
    }

    public void renamePlaylist(long id, String name) {
        SQLiteDatabase w = db.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        w.update("playlists", cv, "id = ?", new String[]{String.valueOf(id)});
    }

    public void deletePlaylist(long id) {
        SQLiteDatabase w = db.getWritableDatabase();
        w.delete("playlists", "id = ?", new String[]{String.valueOf(id)});   // cascades to members
    }

    /** Ordered file hashes of a playlist's songs. */
    public List<String> playlistHashes(long playlistId) {
        SQLiteDatabase r = db.getReadableDatabase();
        List<String> out = new ArrayList<>();
        try (Cursor c = r.rawQuery(
                "SELECT file_hash FROM playlist_songs WHERE playlist_id = ? ORDER BY position",
                new String[]{String.valueOf(playlistId)})) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    /** Appends a song (by hash) to a playlist; ignores if already present. */
    public void addToPlaylist(long playlistId, String fileHash) {
        SQLiteDatabase w = db.getWritableDatabase();
        int nextPos;
        try (Cursor c = w.rawQuery(
                "SELECT COALESCE(MAX(position) + 1, 0) FROM playlist_songs WHERE playlist_id = ?",
                new String[]{String.valueOf(playlistId)})) {
            c.moveToFirst();
            nextPos = c.getInt(0);
        }
        ContentValues cv = new ContentValues();
        cv.put("playlist_id", playlistId);
        cv.put("file_hash", fileHash);
        cv.put("position", nextPos);
        w.insertWithOnConflict("playlist_songs", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public void removeFromPlaylist(long playlistId, String fileHash) {
        SQLiteDatabase w = db.getWritableDatabase();
        w.delete("playlist_songs", "playlist_id = ? AND file_hash = ?",
                new String[]{String.valueOf(playlistId), fileHash});
    }

    /** Distinct artist names, alphabetical. Feeds the add-form autocomplete. */
    public List<String> artists() {
        SQLiteDatabase r = db.getReadableDatabase();
        List<String> out = new ArrayList<>();
        try (Cursor c = r.rawQuery("SELECT name FROM artists ORDER BY name", null)) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    public int count() {
        SQLiteDatabase r = db.getReadableDatabase();
        try (Cursor c = r.rawQuery("SELECT COUNT(*) FROM songs", null)) {
            c.moveToFirst();
            return c.getInt(0);
        }
    }

    private List<Song> loadSongs(String sql, String[] args) {
        SQLiteDatabase r = db.getReadableDatabase();
        List<Song> out = new ArrayList<>();
        try (Cursor c = r.rawQuery(sql, args)) {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                out.add(new Song(
                        id,
                        c.getString(1),
                        c.getString(2),
                        c.getString(3),
                        artistsFor(r, id),
                        c.getInt(4),
                        c.getLong(5)));
            }
        }
        return out;
    }

    private List<String> artistsFor(SQLiteDatabase r, long songId) {
        List<String> out = new ArrayList<>();
        try (Cursor c = r.rawQuery("""
                SELECT a.name
                FROM song_artists sa
                JOIN artists a ON a.id = sa.artist_id
                WHERE sa.song_id = ?
                ORDER BY sa.position
                """, new String[]{String.valueOf(songId)})) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    private static List<String> cleanArtists(List<String> artists) {
        List<String> out = new ArrayList<>();
        if (artists == null) return out;
        for (String a : artists) {
            if (a == null) continue;
            String t = a.trim();
            if (!t.isBlank() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    private static void copy(InputStream in, File dest) throws IOException {
        try (OutputStream out = Files.newOutputStream(dest.toPath())) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /** SHA-256 of the file content, lowercase hex (matches the server). */
    static String sha256(File file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file.toPath())) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            return toHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
