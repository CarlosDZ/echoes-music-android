package com.musica.app.data;

import android.content.Context;

import com.musica.app.model.Availability;
import com.musica.app.model.Song;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Combines the local and remote libraries into one list for the UI, deduplicated
 * by {@code file_hash} (plan §4.3). Local always wins a tie (plays offline); a
 * song present in both is marked {@link Availability#CLONED}.
 *
 * <p>This is the read side of what will become the full merged repository. The
 * remote call degrades silently: if the server is down, you still get local.
 */
public final class MergedLibrary {

    /**
     * A song plus where it lives. {@code song} is the local copy when present.
     * {@code localId}/{@code remoteId} are the row ids on each side (-1 if absent)
     * so edits/deletes can target the right backend(s).
     */
    public record Item(Song song, Availability availability, long localId, long remoteId) {
        public boolean hasLocal()  { return localId  >= 0; }
        public boolean hasRemote() { return remoteId >= 0; }
    }

    private final LocalRepository local;
    private final RemoteRepository remote;

    public MergedLibrary(Context context) {
        this.local = new LocalRepository(context);
        this.remote = new RemoteRepository(context);
    }

    /** Index a loaded library by file hash, for resolving playlist membership. */
    public static Map<String, Item> indexByHash(List<Item> items) {
        Map<String, Item> map = new LinkedHashMap<>();
        for (Item it : items) {
            if (it.song().fileHash() != null) map.put(it.song().fileHash(), it);
        }
        return map;
    }

    /** Blocking (queries DB + network) — call off the main thread. */
    public List<Item> load(Context context) {
        // Local first so its entries take precedence on a hash collision.
        Map<String, Item> byHash = new LinkedHashMap<>();
        List<Item> noHash = new ArrayList<>();   // rows without a hash never merge

        for (Song s : local.allSongs()) {
            Item item = new Item(s, Availability.LOCAL, s.id(), -1);
            if (s.fileHash() == null) noHash.add(item);
            else byHash.put(s.fileHash(), item);
        }

        if (remote.isConfigured()) {
            for (Song s : remote.allSongs()) {
                if (s.fileHash() == null) {
                    noHash.add(new Item(s, Availability.REMOTE, -1, s.id()));
                    continue;
                }
                Item localHit = byHash.get(s.fileHash());
                if (localHit != null) {
                    // Same file on both sides → cloned; keep the local song but
                    // remember the remote id too.
                    byHash.put(s.fileHash(), new Item(localHit.song(),
                            Availability.CLONED, localHit.localId(), s.id()));
                } else {
                    byHash.put(s.fileHash(), new Item(s, Availability.REMOTE, -1, s.id()));
                }
            }
        }

        List<Item> out = new ArrayList<>(byHash.size() + noHash.size());
        out.addAll(byHash.values());
        out.addAll(noHash);
        return out;
    }
}
