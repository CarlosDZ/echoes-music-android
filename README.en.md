🌐 **English** · [Español](README.md)

# Echoes — Android App

Personal music player for Android (native Java). A library **stored locally on the phone** that **merges** with the **remote** library of an [Echoes server](https://github.com/CarlosDZ/echoes-music-server) when online. It plays local files and remote streaming interchangeably, and can clone songs/playlists for offline use.

This is the client app; the server is a separate project.

## Features (v1.1)

- **Merged library** local + server, deduplicated by hash. Each song shows its availability: `LOCAL`, `REMOTA` (remote) or `CLONADA` (cloned).
- **Search** with real-time fuzzy matching by title and artist.
- **Add** an MP3 from the phone, with editable title/artists, to local, the server, or both.
- **Search and download from YouTube** — *fully on-device, no API key, no server*: search by title, pick a result and it downloads as MP3. The metadata form is **pre-filled** (title/artist, with noise-suffix cleanup and *fuzzy-matching* against your existing artists) and reuses the same save flow (local / server / both, with a duplicate warning).
- **Edit / delete** songs (on the relevant side; split delete for cloned ones).
- **Clone** (remote → local, offline) and **upload** (local → server) individual songs.
- **Merged playlists** (local + server, paired by name): create, add/remove songs (with search), **clone** one from server to local, and **share** a local one to the server.
- **Player** (ExoPlayer / media3): shuffle, repeat list/song, progress bar with times, previous/next, animated equalizer.
- **Media notification** and background playback (with shuffle and repeat), lock-screen and headset controls.
- Persistent **mini-player** with all controls.
- Dark theme with a purple accent.

## Requirements

- **JDK 17** to build. ⚠️ If your `JAVA_HOME`/Gradle points to another version (e.g. JDK 21+), AGP's `JdkImageTransform` fails. Build with an explicit JDK 17 or pin it in Android Studio (*Settings → Build Tools → Gradle → Gradle JDK → 17*).
- **Android SDK** with `compileSdk 34`. The SDK path goes in `local.properties` (not versioned):
  ```properties
  sdk.dir=/path/to/your/Android/Sdk
  ```
- **minSdk 24** (Android 7.0) — **targetSdk 34**.

## Build and install

```bash
# Debug APK
./gradlew assembleDebug -Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk

# Install on a connected device (adb)
adb install -r "app/build/outputs/apk/debug/Echoes Music-1.1_2-debug.apk"
```

> On MIUI/Xiaomi phones, `./gradlew installDebug` may fail with
> `INSTALL_FAILED_USER_RESTRICTED`. Use `adb install -r ...`, or enable
> "Install via USB" in Developer options.

## Configuration (server connection)

The app works **without a server** (local only). To use the remote side, in the
**Ajustes** (Settings) tab:

- **Server URL**: e.g. `https://musica.midominio.com` (no trailing slash).
- **Access key**: the server's `API_KEY` (sent in the `Authorization` header).
- **Save and test connection** validates URL + key (`/health` and `/songs`).

The configuration is stored in `SharedPreferences`. No credentials in the code.

## Architecture

```
app/src/main/java/com/musica/app/
├── MainActivity            # 5 tabs + mini-player
├── MusicApp                # Application: initializes yt-dlp/ffmpeg in the background at startup
├── model/                  # Song, Artist, Playlist (records, shared with the server),
│                           #   Availability, PlaylistRef
├── data/
│   ├── MusicDb             # local SQLite (same schema as the server), no ORM
│   ├── LocalRepository     # single-ingest (hash + dedup), local songs and playlists
│   ├── RemoteRepository    # HTTP client to the server (HttpURLConnection, org.json), offline-guarded
│   ├── MergedLibrary       # merges local + remote by file_hash
│   ├── Tags                # metadata reading via MediaMetadataRetriever
│   ├── Fuzzy               # fuzzy matcher for search
│   ├── YtDlpService        # on-device YouTube search + download (yt-dlp), off the main thread
│   ├── YtMetadata          # cleans YouTube title/artist and canonicalizes against existing ones (fuzzy)
│   └── Prefs               # server URL + key
├── playback/
│   ├── PlaybackService     # MediaSessionService: ExoPlayer + MediaSession + notification
│   └── PlaybackController  # facade (MediaController) used by the UI
├── ui/                     # RecyclerView adapters, EqualizerView
└── fragments/              # Buscar, Playlists (+ detail), Reproductor, Añadir, Ajustes
```

Principles (inherited from the project's design): no ORM (hand-written SQL over
native SQLite), minimal dependencies, model shared with the server, **single-ingest**
(map/upload/clone all converge on `LocalRepository.ingest`), **one-way, manual**
cloning (no bidirectional sync), and silent degradation when the server doesn't
respond.

### Dependencies

`appcompat`, `fragment`, `material`, and `media3-exoplayer` + `media3-session` for
playback and the notification. JSON via `org.json` (bundled with Android); no
third-party HTTP clients.

For YouTube downloading, `io.github.junkfood02.youtubedl-android` (`library` +
`ffmpeg` artifacts), which bundles **native yt-dlp + Python + ffmpeg** and works
**without an API key** (it talks to YouTube directly from the device's IP). The
yt-dlp engine can self-update at runtime. This grows the APK to **~100 MB** (native
libs for `arm64-v8a`/`armeabi-v7a`; add `x86_64` to `abiFilters` for an emulator).
The first launch after install unpacks Python once (a few seconds).

## License

Released under the [PolyForm Noncommercial License 1.0.0](LICENSE). Free for
personal, study and non-commercial use; commercial use is not permitted.
