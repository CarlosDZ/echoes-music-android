package com.musica.app.model;

/**
 * A playlist as shown in the merged UI. Playlists are matched across local and
 * server by name (mirroring how songs match by hash):
 * <ul>
 *   <li>{@code LOCAL}  — only on the phone.</li>
 *   <li>{@code REMOTE} — only on the server (shared, streamed).</li>
 *   <li>{@code CLONED} — on both: a local offline copy of a server playlist.</li>
 * </ul>
 * {@code localId}/{@code remoteId} are the row ids on each side (-1 if absent).
 */
public record PlaylistRef(String name, Origin origin, long localId, long remoteId) {

    public enum Origin { LOCAL, REMOTE, CLONED }

    /** Backed by a local copy (LOCAL or CLONED) → openable/playable offline. */
    public boolean isLocalBacked() { return origin == Origin.LOCAL || origin == Origin.CLONED; }

    public boolean isRemoteOnly()  { return origin == Origin.REMOTE; }
    public boolean isCloned()      { return origin == Origin.CLONED; }
}
