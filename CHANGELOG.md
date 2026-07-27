# Changelog

Todos los cambios notables de la app Echoes (Android) se documentan aquí.

El formato sigue la idea de [Keep a Changelog](https://keepachangelog.com/es-ES/1.0.0/).
El versionado usa el esquema propio del proyecto `MAJOR.MINOR_PATCH` (p. ej. `1.1_0`);
el `versionCode` de Android sube en +1 en cada release.

## [1.1_0] — 2026-07-27

### Added
- **Buscar y descargar música de YouTube** desde la sección *Añadir*, todo en el
  dispositivo (sin API key ni servidor): se busca por título, se elige un resultado
  y se descarga como MP3.
- **Prerelleno de metadatos** del formulario a partir del vídeo: limpia sufijos de
  ruido (`(Official Video)`, `[Audio]`…), extrae los `feat.` como artistas y
  canonicaliza cada nombre contra los artistas ya existentes por *fuzzy match*
  (p. ej. `BadBunnyVEVO` → `Bad Bunny`).
- La descarga reutiliza el flujo de guardado existente: destino local, servidor o
  ambos, con aviso de posibles duplicados; el archivo temporal se borra al guardar.

### Changed
- El motor `yt-dlp` va empaquetado (`youtubedl-android`) y puede autoactualizarse en
  caliente, así que las roturas de YouTube se arreglan sin publicar una versión nueva.
- El APK crece a ~100 MB por las librerías nativas (Python + ffmpeg) para
  `arm64-v8a` / `armeabi-v7a`.

## [1.0_1] — 2026-05-26

### Added
- Primera versión: biblioteca **local + servidor fusionada** y deduplicada por hash,
  con disponibilidad `LOCAL` / `REMOTA` / `CLONADA`.
- Buscador con coincidencia difusa por título y artista.
- Añadir MP3 del teléfono (título/artistas editables) a local, servidor o ambos.
- Editar / borrar canciones, clonar (remoto → local) y subir (local → servidor).
- Playlists fusionadas (crear, añadir/quitar, clonar y compartir).
- Reproductor (media3/ExoPlayer) con aleatorio, repetir, notificación de medios,
  reproducción en segundo plano y mini-reproductor persistente.
