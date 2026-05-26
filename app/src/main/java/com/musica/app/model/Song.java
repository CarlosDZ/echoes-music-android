package com.musica.app.model;

import java.util.List;

/**
 * Shared model, copied verbatim from the server (package aside). Pure JVM record
 * with no SQLite/Android coupling — see the project design rules.
 */
public record Song(
        long id,
        String path,
        String fileHash,
        String title,
        List<String> artists,
        int duration,
        long addedAt
) { }
