package com.musica.app.playback;

import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;

import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;
import com.musica.app.data.LocalRepository;
import com.musica.app.data.MergedLibrary;
import com.musica.app.data.Prefs;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * App-scoped facade over a {@link MediaController} connected to
 * {@link PlaybackService}. The actual player lives in the service (so playback
 * continues in the background with a media notification); this just builds the
 * queue and relays controls/state to the UI. Connection is async — controls
 * issued before it's ready are queued.
 */
public final class PlaybackController {

    public interface Listener {
        void onPlaybackChanged();
    }

    private static PlaybackController instance;

    public static synchronized PlaybackController get(Context ctx) {
        if (instance == null) instance = new PlaybackController(ctx.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final LocalRepository local;
    private final Prefs prefs;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private MediaController controller;
    private String contextLabel = "";
    private Runnable pending;   // a playQueue requested before the controller connected

    private PlaybackController(Context app) {
        this.app = app;
        this.local = new LocalRepository(app);
        this.prefs = new Prefs(app);
        connect();
    }

    private void connect() {
        SessionToken token = new SessionToken(app, new ComponentName(app, PlaybackService.class));
        ListenableFuture<MediaController> future =
                new MediaController.Builder(app, token).buildAsync();
        future.addListener(() -> {
            try {
                controller = future.get();
                controller.addListener(new Player.Listener() {
                    @Override public void onEvents(Player p, Player.Events e) { notifyListeners(); }
                });
                if (pending != null) { pending.run(); pending = null; }
                notifyListeners();
            } catch (Exception ignored) {
                // service failed to bind; UI just shows the empty state
            }
        }, ContextCompat.getMainExecutor(app));
    }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    private void notifyListeners() {
        for (Listener l : listeners) l.onPlaybackChanged();
    }

    /** Replaces the queue and starts at {@code startIndex}. */
    public void playQueue(List<MergedLibrary.Item> items, int startIndex, String label) {
        List<MediaItem> media = new ArrayList<>();
        String base = normalize(prefs.serverUrl());
        for (MergedLibrary.Item it : items) media.add(toMediaItem(it, base));
        contextLabel = label == null ? "" : label;
        if (media.isEmpty()) return;
        int idx = Math.max(0, Math.min(startIndex, media.size() - 1));

        Runnable action = () -> {
            controller.setMediaItems(media, idx, 0L);
            controller.prepare();
            controller.play();
            notifyListeners();
        };
        if (controller != null) action.run();
        else pending = action;
    }

    private MediaItem toMediaItem(MergedLibrary.Item it, String base) {
        Uri uri = it.hasLocal()
                ? Uri.fromFile(local.fileOf(it.song().path()))
                : Uri.parse(base + "/songs/" + it.remoteId() + "/stream");
        MediaMetadata meta = new MediaMetadata.Builder()
                .setTitle(displayTitle(it))
                .setArtist(displayArtists(it))
                .build();
        return new MediaItem.Builder().setUri(uri).setMediaMetadata(meta).build();
    }

    // ---------------- state ----------------

    public boolean hasQueue()   { return controller != null && controller.getMediaItemCount() > 0; }
    public boolean isPlaying()  { return controller != null && controller.isPlaying(); }
    public boolean shuffle()    { return controller != null && controller.getShuffleModeEnabled(); }
    public int repeatMode()     { return controller == null ? Player.REPEAT_MODE_OFF : controller.getRepeatMode(); }
    public String contextLabel(){ return contextLabel; }
    public long positionMs()    { return controller == null ? 0 : Math.max(0, controller.getCurrentPosition()); }

    public long durationMs() {
        if (controller == null) return 0;
        long d = controller.getDuration();
        return d == androidx.media3.common.C.TIME_UNSET ? 0 : d;
    }

    public String currentTitle() {
        MediaItem mi = (controller == null) ? null : controller.getCurrentMediaItem();
        CharSequence t = (mi == null) ? null : mi.mediaMetadata.title;
        return t == null ? "" : t.toString();
    }

    public String currentArtists() {
        MediaItem mi = (controller == null) ? null : controller.getCurrentMediaItem();
        CharSequence a = (mi == null) ? null : mi.mediaMetadata.artist;
        return a == null ? "" : a.toString();
    }

    // ---------------- controls ----------------

    public void toggle() {
        if (controller == null) return;
        if (controller.isPlaying()) controller.pause();
        else controller.play();
    }

    public void next()         { if (controller != null) controller.seekToNextMediaItem(); }
    public void prev()         { if (controller != null) controller.seekToPrevious(); }
    public void seekTo(long ms) { if (controller != null) controller.seekTo(ms); }

    public void toggleShuffle() {
        if (controller != null) controller.setShuffleModeEnabled(!controller.getShuffleModeEnabled());
    }

    public void cycleRepeat() {
        if (controller == null) return;
        int next = switch (controller.getRepeatMode()) {
            case Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL;
            case Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE;
            default -> Player.REPEAT_MODE_OFF;
        };
        controller.setRepeatMode(next);
    }

    private static String displayTitle(MergedLibrary.Item it) {
        String t = it.song().title();
        return (t == null || t.isBlank()) ? "Sin título" : t;
    }

    private static String displayArtists(MergedLibrary.Item it) {
        List<String> a = it.song().artists();
        return (a == null || a.isEmpty()) ? "—" : String.join(", ", a);
    }

    private static String normalize(String raw) {
        String s = raw == null ? "" : raw.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
