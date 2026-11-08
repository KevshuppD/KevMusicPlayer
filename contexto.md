# 🎵 KevMusicPlayer — Contexto General del Proyecto

Punto de entrada de la documentación del proyecto. Lo común a todo el repositorio está aquí; el detalle de cada subapp, en su archivo:

- **[`app.md`](app.md):** app Android (`app/`): arquitectura, nube, reglas críticas, Telegram, videos, estilo visual, compilación y optimizaciones.
- **[`desktop.md`](desktop.md):** KevMusic Desktop (`desktop/`): puente con el teléfono y su protocolo, Soulseek, YouTube Music, empaquetado y rendimiento en el PC.

**Antes de tocar una subapp, lee su archivo.** Un cambio en `data/remote/` o en el protocolo afecta a las dos: lee ambos. Cada dato va en un solo archivo.

---

## 1. Propósito y Visión General
**KevMusicPlayer** es un reproductor de audio moderno, ligero y ultrarrápido desarrollado en **Kotlin y Jetpack Compose** para Android 13+ (API 33 a 37+). Su arquitectura está diseñada bajo la filosofía **Local-First**, ofreciendo una experiencia estética, fluida y altamente configurable sin depender de servicios de streaming externos.

### Pilares Funcionales:
1. **Biblioteca Local Inteligente:** Escaneo nativo con `MediaStore` y extracción de metadatos/etiquetas (ID3v1, ID3v2, FLAC, Vorbis, MP4) vía `jaudiotagger` y `mp3agic`.
2. **Sistema de Carga de Carátulas Multi-Nivel:** Arquitectura en 5 niveles de respaldo (RAM LruCache, Disco WebP, `loadThumbnail` acelerado por hardware, `MediaMetadataRetriever` protegido e imágenes de carpeta) con cero bloqueos y protección contra envenenamiento de caché.
3. **Sincronización y Respaldo en la Nube (Google Cloud / Firebase):** Inicio de sesión con Google (`GoogleSignInClient` + `FirebaseAuth`) y almacenamiento comprimido GZIP en Firestore de listas de reproducción, letras de canciones, estadísticas y configuraciones del usuario.
4. **Reproducción y Audio DSP:** Basado en `Jetpack Media3 / ExoPlayer` (`MediaSessionService`) con soporte para reproducción en segundo plano, controles de pantalla de bloqueo, integración de notificación multimedia nativa, ecualizador gráfico de 5 bandas, refuerzo de graves (*Bass Boost*), sonido envolvente virtualizado (*Virtualizer*), normalización de volumen (*ReplayGain*) y salto manual de pista instantáneo.
5. **Letras Sincronizadas y Traducidas:** Visualización y edición en tiempo real de letras sincronizadas (.lrc) o estáticas, con integración de búsqueda automática en línea (LrcLib API) y persistencia local/nube.
6. **Mantenimiento y Diagnóstico:** Herramientas integradas para detectar canciones duplicadas por hash/metadatos, verificar integridad de archivos corruptos, filtrar audios cortos (audios de WhatsApp/tonos) y descargar carátulas faltantes en HD desde Deezer API.
7. **KevMusic Wrapped (Historias Estilo Spotify Wrapped):** Resumen estadístico interactivo a pantalla completa con barras de progreso animadas, aura sonora, hábitos de escucha, arquetipos de personalidad y póster final exportable en PNG.
8. **Personalización Estética Neo-Glow / Glassmorphism:** Temas visuales (*Cyberpunk Rosa, Cyberpunk Púrpura, Azul Petróleo, Turquesa, Obsidiana AMOLED y Monocromo*), soporte de transparencia Glassmorphism en barra de navegación y tarjetas, fondo ambiental dinámico basado en los colores de la carátula, visualizador animado (FFT) y selector de tasa de refresco a **120 Hz**.
9. **Generador y Exportador de Clips de Video (Music Story / Reel):** Renderizado acelerado por hardware (`MediaCodec` H.264 + AAC + `MediaMuxer`) para exportar fragmentos de video (9:16 vertical o 1:1 cuadrado) con música, letras sincronizadas animadas, ondas de audio y carátula con vinilo girando directamente a WhatsApp, Instagram Stories, TikTok o galería.

---

## 2. Estructura del Repositorio

- `app/`: app Android (ver `app.md`).
- `desktop/`: KevMusic Desktop, proyecto Gradle independiente (ver `desktop.md`).
- `soulseek-core/`: fuentes compartidas por ambas (abajo).
- `changelog/`: notas de cada versión. `docs/`: receptor de Chromecast. `tools/`: generador de iconos.

### Código compartido
- **Núcleo compartido (`soulseek-core/`):** el protocolo, `SoulseekClient`, `Shares`, `Upnp`, `AlbumMatcher` (genérico: `AlbumMatcher.Album<T>`, `LocalSong`) y el agrupado y orden de resultados (`SlskResults.kt`: `FolderGroup`, `folderGroups()`, `rankSources()`, `splitPlan()`) están en `soulseek-core/src/main/kotlin/com/kevshupp/soulseek/`, que compilan el PC (`kotlin.srcDir` en `desktop/build.gradle.kts`) y la app (`kotlin.srcDir` en `app/build.gradle.kts`). No es un módulo Gradle: solo una carpeta de fuentes, sin Compose ni Android (JVM pura, `org.json` lo da Android o la dependencia del PC). El cliente recibe un `SlskSettings` (en el PC lo implementa `SoulseekConfig`), la carpeta de la cola y unos `SlskLabels` con los textos que dependen de dónde corre. En el PC queda `desktop/.../soulseek/SoulseekModels.kt` (`SoulseekConfig` y `DeezerAlbums.searchAlbums`).

---

## 3. Privacidad y Licencia
- **Licencia:** Distribuido bajo la Licencia **MIT (Código Abierto)**.
- **Privacidad Local-First:** Cero recopilación o venta de datos de telemetría a terceros. Todo el catálogo de canciones reside 100% en el dispositivo del usuario.

---

## 4. Publicación (reglas comunes)
- **No esperar a que termine la release:** tras empujar el tag, avisar que quedó subido y terminar, sin vigilar la compilación. El dueño la vigila y avisa si pasa algo.
- **Límite de la API de GitHub:** sin sesión, `api.github.com` permite 60 consultas por hora **por IP pública**, y el PC y el teléfono comparten la del router. No vigilar las compilaciones consultando la API en bucle: el 23/10/2026 se agotó y "Buscar actualizaciones" del teléfono (`AppUpdater.checkUpdate()`, que con cualquier respuesta distinta de 200 devuelve `null`) decía que no había versión nueva durante 15 min. Para comprobar una release, una o dos consultas espaciadas, o sin la API: `git ls-remote --tags origin`, la redirección de `github.com/<repo>/releases/latest` o `github.com/<repo>/releases.atom`. **Desde la app 1.18.4 y el PC 1.8.2** los buscadores de actualizaciones (`AppUpdater.latestRelease()`, `Updater.check()`) usan `releases.atom` si la API falla, y el paquete y las notas por sus direcciones fijas; "no se pudo comprobar" ya no se muestra como "al día". El 08/11/2026 se volvió a agotar (las ~15 aperturas del PC en pruebas gastan una consulta cada una).

---

## 5. Pendientes
- **Escuchar juntos: calibrar con el micrófono (app, `data/together/TogetherController.kt`).** En el modo juntos el reloj ya queda a ~5–20 ms; lo que falta es el retraso de salida de audio de cada teléfono (altavoz 20–80 ms, Bluetooth +150–300 ms), que hoy se compensa a mano con "Ajuste fino". Idea: botón "Calibrar" con ambos teléfonos juntos; cada uno emite un pitido corto en un instante acordado por el reloj común, el micrófono de uno graba ambos y la distancia entre pitidos se guarda en `settings_prefs/together_offset_ms`. Meta: ±10–20 ms. Necesita permiso de micrófono (ya lo usa el visualizador).
