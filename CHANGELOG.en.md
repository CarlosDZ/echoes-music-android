🌐 **English** · [Español](CHANGELOG.md)

# Changelog

All notable changes to the Echoes (Android) app are documented here.

The format follows the idea of [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versioning uses the project's own `MAJOR.MINOR_PATCH` scheme (e.g. `1.1_0`); Android's
`versionCode` increases by +1 on each release.

## [1.1_3] — 2026-07-28

### Fixed
- **Streaming playback of remote songs** no longer fails (401) when the server is
  configured or changed *after* the app has started: the player now reads the access
  key **fresh for every stream** instead of fixing it once when its playback service
  is created. (Local playback and cloning were unaffected.)

## [1.1_2] — 2026-07-27

### Fixed
- The download progress bar no longer shows Material's stop-indicator dot at the end
  of the track.
- The player no longer flickers (the UI flashed for an instant) when opened with
  nothing playing.

## [1.1_1] — 2026-07-27

### Changed
- The add-song title/artists form is now a **modal (bottom sheet)**, shared by the
  YouTube download and "Elegir MP3" flows; the save result is shown as a *Snackbar*.
- The YouTube results list closes when a download starts (prevents tapping another
  result by mistake).
- The download progress bar uses a grey track with purple progress.

## [1.1_0] — 2026-07-27

### Added
- **Search and download music from YouTube** from the *Añadir* (Add) section, fully
  on-device (no API key, no server): search by title, pick a result and it downloads
  as MP3.
- **Metadata pre-fill** of the form from the video: strips noise suffixes
  (`(Official Video)`, `[Audio]`…), extracts `feat.` performers as artists, and
  canonicalizes each name against your existing artists via *fuzzy match*
  (e.g. `BadBunnyVEVO` → `Bad Bunny`).
- The download reuses the existing save flow: local, server or both destinations,
  with a possible-duplicate warning; the temporary file is deleted on save.

### Changed
- The `yt-dlp` engine is bundled (`youtubedl-android`) and, when a download fails, it
  **self-updates (nightly) and retries automatically** — so YouTube breakages are fixed
  without shipping a new app version.
- The APK grows to ~100 MB due to the native libraries (Python + ffmpeg) for
  `arm64-v8a` / `armeabi-v7a`.

## [1.0_1] — 2026-05-26

### Added
- First version: **merged local + server library** deduplicated by hash, with
  `LOCAL` / `REMOTA` / `CLONADA` availability.
- Search with fuzzy matching by title and artist.
- Add a phone MP3 (editable title/artists) to local, server, or both.
- Edit / delete songs, clone (remote → local) and upload (local → server).
- Merged playlists (create, add/remove, clone and share).
- Player (media3/ExoPlayer) with shuffle, repeat, media notification, background
  playback and a persistent mini-player.
