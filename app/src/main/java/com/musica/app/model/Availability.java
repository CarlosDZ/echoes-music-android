package com.musica.app.model;

/**
 * Where a song lives after merging local and remote libraries (plan §4.5).
 * Derived from the merge, not stored.
 */
public enum Availability {
    LOCAL,    // only in the local DB — plays offline
    REMOTE,   // only on the server — needs streaming
    CLONED    // in both (same file hash) — shown once, plays from local
}
