🌐 [English](README.en.md) · **Español**

# Echoes — App Android

Reproductor de música personal para Android (Java nativo). Biblioteca **local en el teléfono** que se **fusiona** con la biblioteca **remota** de un [servidor Echoes](https://github.com/CarlosDZ/echoes-music-server) cuando hay conexión. Reproduce indistintamente archivos locales y streaming remoto, y permite clonar canciones/playlists para tenerlas offline.

Es la app cliente; el servidor es un proyecto aparte.

## Características (v1.1)

- **Biblioteca fusionada** local + servidor, deduplicada por hash. Cada canción muestra su disponibilidad: `LOCAL`, `REMOTA` o `CLONADA`.
- **Buscador** con coincidencia difusa (fuzzy) por título y artista, en tiempo real.
- **Añadir** MP3 desde el teléfono, con título/artistas editables, a local, al servidor o a ambos.
- **Buscar y descargar de YouTube** — *todo en el dispositivo, sin API key ni servidor*: busca por título, elige un resultado y se descarga como MP3. El formulario de metadatos se **prerrellena** (título/artista, con limpieza de sufijos y *fuzzy-match* contra tus artistas existentes) y reutiliza el mismo flujo de guardado (local / servidor / ambos, con aviso de duplicados).
- **Editar / borrar** canciones (en el lado que corresponda; borrado partido para las clonadas).
- **Clonar** (remoto → local, offline) y **subir** (local → servidor) canciones individuales.
- **Playlists fusionadas** (local + servidor, emparejadas por nombre): crear, añadir/quitar canciones (con buscador), **clonar** una de servidor a local y **compartir** una local al servidor.
- **Reproductor** (ExoPlayer / media3): aleatorio, repetir lista/canción, barra de progreso con tiempos, anterior/siguiente, ecualizador animado.
- **Notificación de medios** y reproducción en segundo plano (con aleatorio y repetir), controles en pantalla de bloqueo y auriculares.
- **Mini-reproductor** persistente con todos los controles.
- Tema oscuro con acento morado.

## Requisitos

- **JDK 17** para compilar. ⚠️ Si tu `JAVA_HOME`/Gradle apunta a otra versión (p. ej. JDK 21+), el `JdkImageTransform` de AGP falla. Compila pasando el JDK 17 explícito o fíjalo en Android Studio (*Settings → Build Tools → Gradle → Gradle JDK → 17*).
- **Android SDK** con `compileSdk 34`. La ruta del SDK va en `local.properties` (no se versiona):
  ```properties
  sdk.dir=/ruta/a/tu/Android/Sdk
  ```
- **minSdk 24** (Android 7.0) — **targetSdk 34**.

## Compilar e instalar

```bash
# APK de depuración
./gradlew assembleDebug -Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk

# Instalar en un dispositivo conectado (adb)
adb install -r "app/build/outputs/apk/debug/Echoes Music-1.1_2-debug.apk"
```

> En móviles MIUI/Xiaomi, `./gradlew installDebug` puede fallar con
> `INSTALL_FAILED_USER_RESTRICTED`. Usa `adb install -r ...`, o activa
> "Instalar vía USB" en Opciones de desarrollador.

## Configuración (conexión al servidor)

La app funciona **sin servidor** (solo local). Para usar lo remoto, en la pestaña
**Ajustes**:

- **URL del servidor**: p. ej. `https://musica.midominio.com` (sin barra final).
- **Clave de acceso**: la `API_KEY` del servidor (se envía en la cabecera `Authorization`).
- **Guardar y probar conexión** valida URL + clave (`/health` y `/songs`).

La configuración se guarda en `SharedPreferences`. No hay credenciales en el código.

## Arquitectura

```
app/src/main/java/com/musica/app/
├── MainActivity            # 5 pestañas + mini-reproductor
├── MusicApp                # Application: inicializa yt-dlp/ffmpeg en 2º plano al arrancar
├── model/                  # Song, Artist, Playlist (records, compartidos con el server),
│                           #   Availability, PlaylistRef
├── data/
│   ├── MusicDb             # SQLite local (mismo esquema que el server), sin ORM
│   ├── LocalRepository     # ingesta única (hash + dedup), canciones y playlists locales
│   ├── RemoteRepository    # cliente HTTP al server (HttpURLConnection, org.json), con guarda offline
│   ├── MergedLibrary       # fusiona local + remoto por file_hash
│   ├── Tags                # lectura de metadatos con MediaMetadataRetriever
│   ├── Fuzzy               # matcher difuso del buscador
│   ├── YtDlpService        # búsqueda + descarga de YouTube on-device (yt-dlp), fuera del hilo principal
│   ├── YtMetadata          # limpia título/artista de YouTube y canonicaliza contra los existentes (fuzzy)
│   └── Prefs               # URL + clave del servidor
├── playback/
│   ├── PlaybackService     # MediaSessionService: ExoPlayer + MediaSession + notificación
│   └── PlaybackController  # fachada (MediaController) que usa la UI
├── ui/                     # adapters de RecyclerView, EqualizerView
└── fragments/              # Buscar, Playlists (+ detalle), Reproductor, Añadir, Ajustes
```

Principios (heredados del diseño del proyecto): sin ORM (SQL a mano sobre SQLite
nativo), dependencias mínimas, modelo compartido con el servidor, **ingesta única**
(mapear/subir/clonar convergen en `LocalRepository.ingest`), clonado **unidireccional
y manual** (sin sincronización bidireccional), y degradación silenciosa cuando el
servidor no responde.

### Dependencias

`appcompat`, `fragment`, `material`, y `media3-exoplayer` + `media3-session` para
reproducción y la notificación. JSON con `org.json` (incluido en Android); sin
clientes HTTP de terceros.

Para la descarga de YouTube, `io.github.junkfood02.youtubedl-android` (artefactos
`library` + `ffmpeg`), que empaqueta **yt-dlp + Python + ffmpeg nativos** y funciona
**sin API key** (habla directamente con YouTube desde la IP del dispositivo). El motor
yt-dlp puede autoactualizarse en caliente. Esto sube el APK a **~100 MB** (libs nativas
para `arm64-v8a`/`armeabi-v7a`; añade `x86_64` en `abiFilters` para emulador). El primer
arranque tras instalar desempaqueta el Python una vez (unos segundos).

## Licencia

Publicada bajo la [PolyForm Noncommercial License 1.0.0](LICENSE). Uso libre para
fines personales, de estudio y no comerciales; el uso comercial no está permitido.
