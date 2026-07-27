🌐 **English** · [Español](CHANGELOG.md)

# Changelog

All notable changes to the Echoes (Android) app are documented here.

The format follows the idea of [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versioning uses the project's own `MAJOR.MINOR_PATCH` scheme (e.g. `1.1_0`); Android's
`versionCode` increases by +1 on each release.

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
- The `yt-dlp` engine is bundled (`youtubedl-android`) and can self-update at runtime,
  so YouTube breakages are fixed without shipping a new app version.
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
