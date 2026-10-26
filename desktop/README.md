# KevMusic Desktop

Control remoto de escritorio para KevMusicPlayer, para **Linux** y **Windows**. La música sigue sonando en el
teléfono; el PC muestra lo que suena con su letra sincronizada (y traducción), la cola y la biblioteca, y lo maneja todo.

## Qué hace
- **Reproduciendo:** carátula, letra sincronizada (tocar una línea salta ahí), traducción, volumen del teléfono,
  aleatorio y repetición.
- **Biblioteca:** Inicio (recientes, lo más escuchado, tus artistas), canciones con búsqueda y orden, artistas
  (con foto y panel propio), álbumes y listas normales e inteligentes. Crear listas, añadir o quitar canciones y
  borrar canciones del teléfono desde el menú contextual.
- **Cola:** saltar, quitar, poner a continuación, reordenar.
- **Descargar:** por el bot de Telegram del teléfono, o con el cliente de **Soulseek** integrado (álbumes reconocidos
  con Deezer, la mejor fuente, descarga repartida entre usuarios, compartir carpetas); lo descargado se envía solo al teléfono.
- **Integración:** teclas multimedia y applet de sonido (MPRIS, solo Linux), icono en la bandeja, tema sincronizado
  con el del teléfono, actualizaciones automáticas.

## Instalar
Descarga la última versión desde las [releases `desktop-v*`](https://github.com/KevshuppD/KevMusicPlayer/releases?q=desktop&expanded=true).

**Linux (Debian, Ubuntu, Mint)**
```bash
sudo apt install ./kevmusic-desktop_<versión>_amd64.deb
```
O descomprime el `.tar.gz` y ejecuta `kevmusic-desktop/bin/kevmusic-desktop`.

**Windows 10/11**
Ejecuta `kevmusic-desktop-<versión>-windows-x64.msi`, o descomprime el `.zip` y abre `kevmusic-desktop.exe`.
- El instalador aún no está firmado: si SmartScreen avisa, pulsa **Más información → Ejecutar de todas formas**.
- La primera vez Windows pregunta si permite el acceso a la red: acéptalo en **redes privadas** (lo necesitan la
  búsqueda del teléfono y Soulseek).

Las actualizaciones se avisan al abrir la app (Ajustes › Actualizaciones).

## Uso
1. En el teléfono: Ajustes › Sistema › **Control desde el PC** (activar). Aparecen la dirección y un código de 6 cifras.
2. En el PC: abre KevMusic Desktop, elige el teléfono de la lista (se encuentra solo por mDNS) o escribe su IP,
   pon el código y pulsa **Emparejar**. Los dos tienen que estar en la misma red.
3. Se recuerdan todos los teléfonos emparejados (también la app debug, en el puerto 47821): las siguientes veces
   conecta solo y se reconecta si se pierde la conexión.

Atajos: `Ctrl+P` reproducir/pausa, `Ctrl+←/→` anterior/siguiente, `Ctrl+↑/↓` volumen, `Ctrl+1…9` secciones,
`Ctrl+Q` salir. Clic derecho en una canción, un álbum o un elemento de la cola para más opciones.

Al cerrar la ventana (Ajustes › Ventana) puede salir, minimizarse o seguir en la bandeja.

### Dónde guarda sus datos
| | Linux | Windows |
|---|---|---|
| Configuración (teléfonos, ajustes, Soulseek) | `~/.config/kevmusic-desktop/` | `%APPDATA%\kevmusic-desktop\` |
| Caché (carátulas, actualizaciones) | `~/.cache/kevmusic-desktop/` | `%LOCALAPPDATA%\kevmusic-desktop\cache\` |

## Compilar
Proyecto Gradle independiente (no forma parte de la compilación de Android). Requiere JDK 21.

```bash
cd desktop
./probar.sh                 # Linux: recompila (createDistributable) y abre la app
./gradlew run               # ejecutar desde el código
./gradlew packageDeb        # Linux:   build/compose/binaries/main/deb/*.deb
./gradlew packageMsi        # Windows: build/compose/binaries/main/msi/*.msi
```
jpackage solo genera paquetes del sistema en el que corre: el `.msi` se compila en Windows.

## Publicar una versión
1. Escribe las notas en `changelog/desktop-vX.Y.Z.md`.
2. `desktop/publicar.sh X.Y.Z`: sube la versión, hace commit y empuja el tag `desktop-vX.Y.Z`.
3. `.github/workflows/desktop-release.yml` compila el `.deb` y el `.tar.gz` en Ubuntu y el `.msi` y el `.zip` en
   Windows, y los publica juntos (sin marcarla como "latest", que es la de Android). Desde la pestaña Actions se
   puede lanzar a mano para una compilación de prueba sin publicar.

## Protocolo
El servidor vive en la app (`data/remote/RemoteControlServer.kt`), puerto 47820 (47821 en la debug), anunciado por
mDNS como `_kevmusic._tcp`:
- `ws://<ip>:47820/ws`: primer mensaje `{"type":"pair","code":"123456","name":"pc"}` o `{"type":"hello","token":"…"}`;
  respuesta `auth` (con la versión del protocolo). Después llegan `state`, `queue`, `lyrics`, `theme`,
  `libraryChanged`, `smartPlaylists`… y se envían comandos (`toggle`, `next`, `prev`, `seek`, `volume`, `repeat`,
  `shuffle`, `playIds`, `enqueue`, `jump`, `remove`, `move`, `getLyrics`, edición de listas, descargas de Telegram).
- `GET /library?token=…` (canciones y listas), `GET /art/<id>?token=…&size=small|big` (carátula JPEG) y
  `PUT /upload?token=…` (enviar una canción al teléfono).

Sin cifrar: pensado solo para la red local de casa.
