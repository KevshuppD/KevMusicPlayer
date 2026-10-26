# Kev Music Player

**Kev Music Player** es un reproductor de música rápido y local para Android 13+, hecho con **Kotlin** y **Jetpack
Compose**, junto con **KevMusic Desktop**, una app para Linux y Windows que controla el teléfono desde la red de casa.

Sin servicios de streaming, sin anuncios y sin rastreo: tu música vive en tu dispositivo. La nube (inicio de sesión
con Google, opcional) solo mantiene sincronizadas entre teléfonos tus listas, letras, reproducciones y ajustes.

<p align="center">
  <a href="obtainium://add?url=https://github.com/KevshuppD/KevMusicPlayer">
    <img src="https://img.shields.io/badge/Añadir_a-Obtainium-purple?style=for-the-badge&logo=android" alt="Añadir a Obtainium" />
  </a>
  &nbsp;
  <a href="https://github.com/KevshuppD/KevMusicPlayer/releases/latest">
    <img src="https://img.shields.io/badge/Descargar-APK-success?style=for-the-badge&logo=android" alt="Descargar APK" />
  </a>
  &nbsp;
  <a href="https://github.com/KevshuppD/KevMusicPlayer/releases?q=desktop&expanded=true">
    <img src="https://img.shields.io/badge/Descargar-Escritorio-blue?style=for-the-badge&logo=linux&logoColor=white" alt="Descargar KevMusic Desktop" />
  </a>
</p>

## 🚀 Funciones

### Biblioteca
- **Arranque instantáneo:** la biblioteca se guarda en Room y se reescanea de forma incremental (solo lo que cambió en MediaStore).
- **Varias carpetas de música**, artistas separados por colaboraciones (`feat.`, `&`, `x`…), álbumes, carpetas y listas.
- **Listas inteligentes** (más escuchadas, historial, agregadas recientemente, reglas) y **Favoritos** siempre a mano.
- **Las canciones conservan su identidad:** cuando Android renumera una tarjeta SD, las estadísticas, letras y listas siguen a cada canción.

### Reproducción
- Servicio en segundo plano con **Media3 / ExoPlayer**, controles en la pantalla de bloqueo y la notificación,
  crossfade y saltos instantáneos.
- **Efectos de sonido:** ecualizador de 5 bandas, refuerzo de graves, sonido envolvente y normalización de volumen (ReplayGain).
- **Letras sincronizadas** (.lrc) desde etiquetas, archivos o [LRCLIB](https://lrclib.net), con edición, salto al
  tocar una línea y traducción por idioma (las canciones que mezclan coreano e inglés se traducen línea a línea).

### Herramientas
- **Carátulas en HD** desde Deezer (1000×1000) y una caché de varios niveles que nunca bloquea el desplazamiento.
- **Editor de etiquetas** con escritura segura y atómica (el archivo original nunca se trunca).
- **Normalizar biblioteca:** propone títulos, artistas, álbumes, años y números de pista oficiales de Deezer; no se
  escribe nada sin revisarlo.
- **Mantenimiento:** buscador de duplicados (conserva la copia de mejor calidad), verificador de archivos cortados o
  dañados, filtro de audios cortos, organizar y migrar carpetas.
- **Descargas por un bot de Telegram y modo bajo demanda** (opcional, necesita tus propias credenciales de la API de Telegram).

### Extras
- **KevMusic Wrapped:** historias al estilo Spotify con tus minutos, canciones y artista favoritos, géneros y un póster para compartir.
- **Clips de video:** historias MP4 codificadas por hardware (9:16, 4:5, 1:1, 16:9) con letra sincronizada animada,
  vinilo, ondas y escenas en pixel art, listas para WhatsApp, Instagram o TikTok.
- **Temas:** Cyberpunk Rosa/Púrpura, Azul Petróleo, Turquesa, Obsidiana AMOLED, Monocromo y las paletas Pixel Retro
  (PICO-8, Game Boy, NES, C64…), efecto cristal, icono a juego con el tema y animaciones a 120 Hz.
- **Sincronización en la nube** (Firebase): listas, reproducciones y letras en tiempo real entre dispositivos, y copias comprimidas.
- **Widgets** de reproducción y de tus videos.

## 🖥️ KevMusic Desktop

La música sigue sonando en el teléfono; el PC muestra lo que suena con la letra sincronizada, la cola y toda la
biblioteca, y lo maneja todo. También descarga desde Soulseek y envía las canciones directamente al teléfono.

| | Linux | Windows |
|---|---|---|
| Paquete | `.deb` (Debian, Ubuntu, Mint) o `.tar.gz` portable | Instalador `.msi` o `.zip` portable |
| Teclas multimedia | ✅ MPRIS (applet de sonido, pantalla de bloqueo) | — |
| Bandeja del sistema | ✅ StatusNotifierItem | ✅ |
| Actualización automática | ✅ | ✅ |

Descárgala desde las [releases del escritorio](https://github.com/KevshuppD/KevMusicPlayer/releases?q=desktop&expanded=true)
(tags `desktop-v*`). En el teléfono activa **Ajustes › Sistema › Control desde el PC**, abre la app de escritorio,
elige el teléfono y escribe el código de 6 cifras. Más detalles en [`desktop/README.md`](desktop/README.md).

> El instalador de Windows aún no está firmado: SmartScreen mostrará un aviso. Pulsa **Más información → Ejecutar de todas formas**.

## 🛠 Tecnologías

| Android | Escritorio |
|---|---|
| Kotlin, Coroutines y Flow | Kotlin 2.2 + Compose Multiplatform |
| Jetpack Compose, Material 3 | WebSocket con OkHttp, JmDNS (búsqueda por mDNS) |
| AndroidX Media3 (ExoPlayer, MediaSession) | dbus-java (MPRIS, bandeja) |
| Room, SharedPreferences | Cliente de Soulseek propio (protocolo, compartidos, UPnP) |
| Firebase Auth + Firestore | jpackage (`.deb`, `.msi`) con AppCDS |
| Coil, jaudiotagger, mp3agic, TDLib, NanoHTTPD | |

## 📦 Compilar

**App de Android**
1. Clona el repositorio y ábrelo en Android Studio.
2. Añade tu propio `app/google-services.json` (Firebase) para la sincronización en la nube.
3. Opcional: `TELEGRAM_API_ID` / `TELEGRAM_API_HASH` en `local.properties` para las descargas de Telegram.
4. Compila y ejecuta el módulo `app` (`./gradlew assembleDebug`).

**App de escritorio** (JDK 21)
```bash
cd desktop
./gradlew run            # ejecutar desde el código
./gradlew packageDeb     # .deb de Linux   (en Linux)
./gradlew packageMsi     # .msi de Windows (en Windows)
```

Las versiones las compila GitHub Actions: los tags `v*` publican el APK y los tags `desktop-v*` los paquetes de Linux y Windows.

## 🔒 Privacidad

Local primero: sin analíticas y sin vender datos a nadie. La sincronización en la nube, opcional, guarda tus datos
en tu propia ruta privada de Firestore (`users/<uid>/…`). La conexión con el escritorio solo funciona en tu red local
y no va cifrada.

## 🤝 Contribuir

¡Las contribuciones son bienvenidas! Abre un issue o envía un Pull Request.

## 📄 Licencia

MIT.

---
Desarrollado por **KevshuppD** (kcastrod@uft.edu)
