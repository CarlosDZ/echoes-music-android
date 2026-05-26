package com.musica.app.playback;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.CommandButton;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionResult;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.musica.app.R;
import com.musica.app.data.Prefs;

import java.util.Collections;
import java.util.List;

/**
 * Hosts the {@link ExoPlayer} and a {@link MediaSession}. media3 turns the
 * session into the standard media notification and keeps playback alive in the
 * background. Beyond the default transport controls it adds custom shuffle and
 * repeat buttons to the notification via the session's custom layout.
 */
@OptIn(markerClass = UnstableApi.class)
public final class PlaybackService extends MediaSessionService {

    private static final String ACTION_SHUFFLE = "com.musica.app.SHUFFLE";
    private static final String ACTION_REPEAT = "com.musica.app.REPEAT";
    private static final SessionCommand CMD_SHUFFLE = new SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY);
    private static final SessionCommand CMD_REPEAT = new SessionCommand(ACTION_REPEAT, Bundle.EMPTY);

    private MediaSession session;
    private ExoPlayer player;

    @Override
    public void onCreate() {
        super.onCreate();

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true);
        String key = new Prefs(this).apiKey();
        if (key != null && !key.isEmpty()) {
            http.setDefaultRequestProperties(Collections.singletonMap("Authorization", key));
        }
        DefaultDataSource.Factory dataSource = new DefaultDataSource.Factory(this, http);

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSource))
                .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
                .setHandleAudioBecomingNoisy(true)
                .build();

        // Tapping the notification opens the app (Now Playing).
        Intent open = new Intent(this, com.musica.app.MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent sessionActivity = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        session = new MediaSession.Builder(this, player)
                .setSessionActivity(sessionActivity)
                .setCallback(new Callback())
                .setCustomLayout(customLayout())
                .build();

        // Keep the notification's shuffle/repeat icons in sync with player state.
        player.addListener(new Player.Listener() {
            @Override public void onEvents(Player p, Player.Events events) {
                if (events.contains(Player.EVENT_REPEAT_MODE_CHANGED)
                        || events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) {
                    session.setCustomLayout(customLayout());
                }
            }
        });
    }

    /** The two custom buttons; the repeat icon reflects the current mode. */
    private List<CommandButton> customLayout() {
        // A dot under the icon marks the "on" state (notification icons are
        // monochrome, so colour can't signal it).
        int shuffleIcon = player.getShuffleModeEnabled()
                ? R.drawable.ic_shuffle_on : R.drawable.ic_shuffle;
        CommandButton shuffle = new CommandButton.Builder()
                .setSessionCommand(CMD_SHUFFLE)
                .setIconResId(shuffleIcon)
                .setDisplayName("Aleatorio")
                .build();
        int repeatIcon = switch (player.getRepeatMode()) {
            case Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one;
            case Player.REPEAT_MODE_ALL -> R.drawable.ic_repeat_on;
            default -> R.drawable.ic_repeat;
        };
        CommandButton repeat = new CommandButton.Builder()
                .setSessionCommand(CMD_REPEAT)
                .setIconResId(repeatIcon)
                .setDisplayName("Repetir")
                .build();
        return ImmutableList.of(shuffle, repeat);
    }

    private final class Callback implements MediaSession.Callback {
        @Override
        public MediaSession.ConnectionResult onConnect(
                MediaSession session, MediaSession.ControllerInfo controller) {
            SessionCommands commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                    .buildUpon()
                    .add(CMD_SHUFFLE)
                    .add(CMD_REPEAT)
                    .build();
            return new MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(commands)
                    .build();
        }

        @Override
        public ListenableFuture<SessionResult> onCustomCommand(
                MediaSession session, MediaSession.ControllerInfo controller,
                SessionCommand customCommand, Bundle args) {
            switch (customCommand.customAction) {
                case ACTION_SHUFFLE -> player.setShuffleModeEnabled(!player.getShuffleModeEnabled());
                case ACTION_REPEAT -> player.setRepeatMode(switch (player.getRepeatMode()) {
                    case Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL;
                    case Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE;
                    default -> Player.REPEAT_MODE_OFF;
                });
                default -> { return Futures.immediateFuture(
                        new SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)); }
            }
            return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
        }
    }

    @Nullable
    @Override
    public MediaSession onGetSession(@Nullable MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        // Swiping the app away should stop playback and clear the notification,
        // not keep playing in the background.
        player.stop();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (session != null) {
            session.getPlayer().release();
            session.release();
            session = null;
        }
        super.onDestroy();
    }
}
