# KevMusic Desktop

Control remoto de escritorio (Linux/Debian) para KevMusicPlayer. La música sigue sonando en el teléfono;
el PC muestra lo que suena con su letra sincronizada, la cola y la biblioteca, y lo maneja todo.

## Uso
1. En el teléfono: Ajustes › Sistema › **Control desde el PC** (activar). Aparecen la dirección y un código de 6 cifras.
2. En el PC: abre KevMusic Desktop, elige el teléfono de la lista (se encuentra solo por mDNS) o escribe su IP,
   pon el código y pulsa **Emparejar**. Los dos tienen que estar en la misma red Wi-Fi.
3. El emparejamiento se recuerda en `~/.config/kevmusic-desktop/config.json`; las siguientes veces conecta solo
   y se reconecta cada 3 s si se pierde la conexión.

Atajos: `Ctrl+P` reproducir/pausa, `Ctrl+←/→` anterior/siguiente, `Ctrl+↑/↓` volumen, `Ctrl+1…6` secciones.
Clic derecho en una canción, un álbum o un elemento de la cola para más opciones.

## Compilar
Proyecto Gradle independiente (no forma parte de la compilación de Android). Requiere JDK 21.

```bash
cd desktop
./probar.sh            # recompila con los últimos cambios y abre la app (createDistributable)
./gradlew run          # ejecutar desde Gradle
./gradlew packageDeb   # build/compose/binaries/main/deb/kevmusic-desktop_<versión>_amd64.deb
sudo apt install ./build/compose/binaries/main/deb/kevmusic-desktop_*_amd64.deb
```

## Protocolo
El servidor vive en la app (`data/remote/RemoteControlServer.kt`), puerto 47820:
- `ws://<ip>:47820/ws`: primer mensaje `{"type":"pair","code":"123456","name":"pc"}` o `{"type":"hello","token":"…"}`;
  respuesta `auth`. Después llegan `state`, `queue` y `lyrics`, y se envían comandos
  (`toggle`, `next`, `prev`, `seek`, `volume`, `repeat`, `playIds`, `enqueue`, `jump`, `remove`, `move`, `getLyrics`).
- `GET /library?token=…` (canciones y listas) y `GET /art/<id>?token=…&size=small|big` (carátula JPEG).

Sin cifrar: pensado solo para la red local de casa.
