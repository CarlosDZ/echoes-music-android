package com.musica.app.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;

/**
 * Local SQLite store. Schema is identical to the server's (see Library.java) so
 * the same model and ingest logic apply on both sides. No ORM: hand-written SQL
 * over the native SQLite API, per the project rules.
 *
 * <p>Like the server, there are no migrations: a schema change means bumping
 * {@link #VERSION} and wiping the DB. {@code onUpgrade} drops and recreates.
 */
public final class MusicDb extends SQLiteOpenHelper {

    private static final String NAME = "echoes.db";
    private static final int VERSION = 2;

    public MusicDb(Context context) {
        super(context.getApplicationContext(), NAME, null, VERSION);
    }

    @Override
    public void onConfigure(@NonNull SQLiteDatabase db) {
        // SQLite ignores ON DELETE CASCADE unless foreign keys are enabled.
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(@NonNull SQLiteDatabase db) {
        db.execSQL("""
                CREATE TABLE songs (
                    id         INTEGER PRIMARY KEY,
                    path       TEXT NOT NULL UNIQUE,
                    file_hash  TEXT UNIQUE,
                    title      TEXT,
                    duration   INTEGER,
                    added_at   INTEGER
                )
                """);
        db.execSQL("""
                CREATE TABLE artists (
                    id    INTEGER PRIMARY KEY,
                    name  TEXT NOT NULL UNIQUE
                )
                """);
        db.execSQL("""
                CREATE TABLE song_artists (
                    song_id    INTEGER NOT NULL REFERENCES songs(id)   ON DELETE CASCADE,
                    artist_id  INTEGER NOT NULL REFERENCES artists(id) ON DELETE CASCADE,
                    position   INTEGER NOT NULL,
                    PRIMARY KEY (song_id, artist_id)
                )
                """);
        db.execSQL("CREATE INDEX idx_song_artists_artist ON song_artists(artist_id)");
        db.execSQL("""
                CREATE TABLE playlists (
                    id    INTEGER PRIMARY KEY,
                    name  TEXT NOT NULL
                )
                """);
        // Membership is keyed by file_hash (not song_id) so a local playlist can
        // also hold songs that only exist remotely. No FK: resolution happens
        // against the merged library at display time.
        db.execSQL("""
                CREATE TABLE playlist_songs (
                    playlist_id  INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
                    file_hash    TEXT NOT NULL,
                    position     INTEGER NOT NULL,
                    PRIMARY KEY (playlist_id, file_hash)
                )
                """);
    }

    @Override
    public void onUpgrade(@NonNull SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS playlist_songs");
        db.execSQL("DROP TABLE IF EXISTS song_artists");
        db.execSQL("DROP TABLE IF EXISTS playlists");
        db.execSQL("DROP TABLE IF EXISTS artists");
        db.execSQL("DROP TABLE IF EXISTS songs");
        onCreate(db);
    }
}
