<h1 align="center">🎵 KevMusicPlayer</h1>

<p align="center">
  <strong>Tu música, en tu teléfono. Sin streaming, sin anuncios y sin rastreo.</strong><br>
  Reproductor local para Android 13+, con app de escritorio para Linux y Windows.
</p>

<p align="center">
  <a href="https://github.com/KevshuppD/KevMusicPlayer/releases/latest"><img src="https://img.shields.io/github/v/release/KevshuppD/KevMusicPlayer?style=flat-square&label=versi%C3%B3n&color=ff4081" alt="Última versión" /></a>
  <img src="https://img.shields.io/badge/Android-13%2B-3ddc84?style=flat-square&logo=android&logoColor=white" alt="Android 13 o superior" />
  <img src="https://img.shields.io/badge/Kotlin-Jetpack_Compose-7f52ff?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin y Jetpack Compose" />
  <a href="LICENSE"><img src="https://img.shields.io/badge/licencia-MIT-blue?style=flat-square" alt="Licencia MIT" /></a>
</p>

<p align="center">
  <a href="https://github.com/KevshuppD/KevMusicPlayer/releases/latest"><img src="https://img.shields.io/badge/Descargar-APK-success?style=for-the-badge&logo=android&logoColor=white" alt="Descargar APK" /></a>
  &nbsp;
  <a href="obtainium://add?url=https://github.com/KevshuppD/KevMusicPlayer"><img src="https://img.shields.io/badge/Añadir_a-Obtainium-purple?style=for-the-badge&logo=android&logoColor=white" alt="Añadir a Obtainium" /></a>
  &nbsp;
  <a href="https://github.com/KevshuppD/KevMusicPlayer/releases?q=desktop&expanded=true"><img src="https://img.shields.io/badge/Descargar-Escritorio-blue?style=for-the-badge&logo=linux&logoColor=white" alt="Descargar KevMusic Desktop" /></a>
</p>

---

## ✨ Lo más destacado

| | |
|---|---|
| 🎧 **Local primero** | Tu biblioteca vive en tu dispositivo. La nube es opcional y solo sincroniza listas, letras, reproducciones y ajustes. |
| 🎤 **Letras sincronizadas** | Desde etiquetas, archivos `.lrc` o [LRCLIB](https://lrclib.net), con edición y traducción línea a línea. |
| 📺 **Chromecast** | Envía tu cola a la TV y contrólala desde el reproductor. |
| 🏷️ **Etiquetas en orden** | Normaliza tu biblioteca con Deezer y MusicBrainz, revisando cada cambio antes de aplicarlo. |
| 📊 **KevMusic Wrapped** | Tus minutos, canciones, artistas y géneros en historias, con un póster para compartir. |
| 🎬 **Clips de video** | Historias MP4 con letra animada, vinilo y escenas en pixel art. |
| 🖥️ **KevMusic Desktop** | Controla el teléfono desde el PC, con letras, cola y biblioteca completas. |

## 📱 Funciones

### Biblioteca
- **Arranque rápido:** reescaneo incremental, solo de lo que cambió.
- **Varias carpetas de música**, álbumes, carpetas y artistas separados por colaboraciones.
- **Listas inteligentes**, Favoritos y mezclas diarias en **Hecho para ti**.
- **Tus datos siguen a cada canción:** estadísticas, letras y listas se conservan aunque Android renumere una tarjeta SD.

### Reproducción
- Segundo plano con **Media3 / ExoPlayer**, pantalla de bloqueo, notificación, crossfade y saltos instantáneos.
- **Ecualizador de 5 bandas**, refuerzo de graves, sonido envolvente y ReplayGain.
- **Chromecast** con carátula y control de volumen. *Al transmitir no se aplican los efectos ni el crossfade.*

### Herramientas
- **Carátulas en HD** desde Deezer, con caché de varios niveles.
- **Editor de etiquetas** con escritura segura y proveedor a elegir: Deezer, MusicBrainz o LRCLIB.
- **Normalizar biblioteca:** propone los datos oficiales, marca los cambios dudosos y te deja ignorar canciones.
- **Mantenimiento:** duplicados, archivos cortados o dañados, audios cortos, y organizar o migrar carpetas.

### Descargas *(opcionales)*
- **Bot de Telegram:** búsqueda en Deezer y SoundCloud, modo bajo demanda y etiquetas oficiales al guardar. Necesita tus propias credenciales de la API de Telegram.
- **Soulseek:** módulo apagado por defecto, también disponible en el PC.

### Personalización
- Temas Cyberpunk, Azul Petróleo, Turquesa, Obsidiana AMOLED, Monocromo y paletas **Pixel Retro** (PICO-8, Game Boy, NES, C64…).
- Efecto cristal, iconos y carátulas en pixel art, icono a juego con el tema y animaciones a 120 Hz.
- **Widgets** de reproducción y de tus videos.

## 🖥️ KevMusic Desktop

La música sigue sonando en el teléfono; el PC muestra la letra, la cola y la biblioteca, y lo controla todo. Lo que descargues ahí llega solo al teléfono.

- **Descargas desde el PC:** Telegram, Soulseek y YouTube Music.
- **Discos:** arma álbumes que no están en Deezer con datos de MusicBrainz y audio de SoundCloud, revisando cada pista.

| | Linux | Windows |
|---|---|---|
| **Paquete** | `.deb` o `.tar.gz` portable | `.msi` o `.zip` portable |
| **Teclas multimedia** | ✅ MPRIS | — |
| **Bandeja del sistema** | ✅ | ✅ |
| **Actualización automática** | ✅ | ✅ |

**Para empezar:**
1. Descarga la [última versión de escritorio](https://github.com/KevshuppD/KevMusicPlayer/releases?q=desktop&expanded=true).
2. En el teléfono, activa **Ajustes › Sistema › Control desde el PC**.
3. Abre la app de escritorio, elige tu teléfono y escribe el código de 6 cifras.

> [!NOTE]
> El instalador de Windows aún no está firmado. Si SmartScreen avisa, pulsa **Más información → Ejecutar de todas formas**.

Más detalles en [`desktop/README.md`](desktop/README.md).

## 🔒 Privacidad

- Sin analíticas ni venta de datos.
- La sincronización es opcional y guarda tus datos en tu ruta privada de Firestore (`users/<uid>/…`).
- La conexión con el escritorio y Chromecast funciona en tu red local y **no va cifrada**: úsala solo en redes de confianza.

## 🛠️ Tecnologías

| Android | Escritorio |
|---|---|
| Kotlin, Coroutines y Flow | Kotlin + Compose Multiplatform |
| Jetpack Compose y Material 3 | OkHttp (WebSocket) y JmDNS |
| Media3 (ExoPlayer, MediaSession) y Google Cast | dbus-java (MPRIS, bandeja) |
| Room y Firebase (Auth, Firestore) | Cliente propio de Soulseek |
| Coil, jaudiotagger, mp3agic, TDLib, NanoHTTPD | yt-dlp, ffmpeg y jpackage |

## 📦 Compilar

<details>
<summary><strong>App de Android</strong></summary>

1. Clona el repositorio y ábrelo en Android Studio.
2. Añade tu `app/google-services.json` para la sincronización en la nube.
3. Opcional: define `TELEGRAM_API_ID` y `TELEGRAM_API_HASH` en `local.properties`.
4. Ejecuta:

```bash
./gradlew assembleDebug
```
</details>

<details>
<summary><strong>App de escritorio (JDK 21)</strong></summary>

```bash
cd desktop
./gradlew run            # ejecutar desde el código
./gradlew packageDeb     # .deb, en Linux
./gradlew packageMsi     # .msi, en Windows
```
</details>

GitHub Actions publica las versiones: los tags `v*` generan el APK, y los tags `desktop-v*`, los paquetes de escritorio.

## ⚖️ Aviso legal

KevMusicPlayer es un reproductor y una herramienta de gestión de música. No aloja, distribuye ni vende contenido, y no está afiliado a Deezer, Telegram, Soulseek, YouTube, SoundCloud, MusicBrainz, LRCLIB ni Google.

Las funciones de descarga son opcionales y se conectan a servicios y redes de terceros, que son quienes ponen el contenido a disposición. Cada usuario es el único responsable de lo que descarga, comparte y reproduce, y de cumplir los derechos de autor, las leyes de su país y las condiciones de esos servicios. El autor no se hace responsable del uso que se le dé a la aplicación. Apoya a los artistas siempre que puedas.

## 🤝 Contribuir

¡Las contribuciones son bienvenidas! Abre un issue o envía un Pull Request.

## 📄 Licencia

Distribuido bajo la [licencia MIT](LICENSE).

---

<p align="center">Hecho por <strong>KevshuppD</strong></p>
