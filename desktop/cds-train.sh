#!/usr/bin/env bash
# Añade a KevMusic Desktop la caché AOT de Java 25 (JEP 483/514/515: clases ya cargadas y enlazadas y perfiles de
# métodos), que abre la ventana en ~1,4 s frente a ~3,3 s con Java 21 + AppCDS (medido en el portátil).
# La JVM la genera abriendo la app unos segundos (KEVMUSIC_CDS_TRAIN=1) con -XX:AOTCacheOutput.
#   ./cds-train.sh build/compose/binaries/main/app/kevmusic-desktop   (carpeta de createDistributable)
#   ./cds-train.sh paquete.deb                                         (reempaqueta el .deb)
# La caché valida tamaño y fecha de cada .jar: se genera sobre los archivos definitivos y no deben cambiar
# después (dpkg y tar conservan las fechas). Si no coincidiera, la JVM la ignora y arranca como antes.
# Para armarla, Java lanza el `bin/java` del runtime, que Compose quita al recortarlo: se copia un momento el del
# mismo JDK (RUNTIME_JDK, JAVA_HOME_25_X64 o ~/.jdks/jdk-25*) y se borra al terminar.
# En el .deb también corrige el lanzador: nombre "KevMusic Desktop" y StartupWMClass para fijarlo al panel.
set -euo pipefail
TARGET="$(readlink -f "$1")"
JDK="${RUNTIME_JDK:-${JAVA_HOME_25_X64:-$(ls -d "$HOME"/.jdks/jdk-25* 2>/dev/null | sort | tail -n 1)}}"

train() {
    local app="$1"
    local cfg
    cfg=$(ls "$app"/lib/app/*.cfg)
    local runtime_java="$app/lib/runtime/bin/java"
    if [ -z "$JDK" ] || [ ! -x "$JDK/bin/java" ]; then
        echo "AVISO: sin JDK 25 (RUNTIME_JDK); la app arrancará sin caché AOT" >&2
        return
    fi
    local backup
    backup=$(mktemp)
    cp "$cfg" "$backup"
    rm -f "$app/lib/app/kevmusic.aot" "$app/lib/app/kevmusic.jsa"
    sed -i '/SharedArchiveFile\|AOTCache/d' "$cfg"
    echo 'java-options=-XX:AOTCacheOutput=$APPDIR/kevmusic.aot' >> "$cfg"
    mkdir -p "$(dirname "$runtime_java")"
    cp "$JDK/bin/java" "$runtime_java"
    local run=("$app/bin/kevmusic-desktop")
    if [ -z "${DISPLAY:-}" ]; then run=(xvfb-run -a "${run[@]}"); fi
    KEVMUSIC_CDS_TRAIN=1 timeout 180 "${run[@]}" >/dev/null 2>&1 || true
    rm -f "$runtime_java"
    rmdir "$(dirname "$runtime_java")" 2>/dev/null || true
    cp "$backup" "$cfg"
    rm -f "$backup"
    sed -i '/SharedArchiveFile\|AOTCache/d' "$cfg"
    if [ -s "$app/lib/app/kevmusic.aot" ]; then
        echo 'java-options=-XX:AOTCache=$APPDIR/kevmusic.aot' >> "$cfg"
        echo "Caché AOT: $(du -h "$app/lib/app/kevmusic.aot" | cut -f1) en $app"
    else
        echo "AVISO: no se generó la caché AOT; la app arrancará sin ella" >&2
    fi
}

# Lanzador rápido: si la app ya está abierta (o en la bandeja) le pide mostrarse por su socket en ~30 ms con
# Python, sin arrancar otra JVM (~0,6 s); si no, abre la app normal. Ver Instance.kt.
add_opener() {
    local app="$1"
    cat > "$app/bin/kevmusic-open" <<'SH'
#!/bin/sh
DIR="$(dirname "$(readlink -f "$0")")"
SOCK="${XDG_CONFIG_HOME:-$HOME/.config}/kevmusic-desktop/instance.sock"
if [ -S "$SOCK" ] && command -v python3 >/dev/null 2>&1 && python3 -c 'import socket,sys
s=socket.socket(socket.AF_UNIX); s.settimeout(1); s.connect(sys.argv[1]); s.sendall(b"show\n")' "$SOCK" 2>/dev/null; then
    exit 0
fi
exec "$DIR/kevmusic-desktop" "$@"
SH
    chmod 755 "$app/bin/kevmusic-open"
}

if [ -d "$TARGET" ]; then
    train "$TARGET"
    add_opener "$TARGET"
    exit 0
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
dpkg-deb -R "$TARGET" "$WORK/pkg"
APP="$WORK/pkg/opt/kevmusic-desktop"
train "$APP"
add_opener "$APP"
for f in "$APP"/lib/*.desktop; do
    sed -i 's#^Exec=.*#Exec=/opt/kevmusic-desktop/bin/kevmusic-open#' "$f"
    sed -i 's/^Name=.*/Name=KevMusic Desktop/' "$f"
    grep -q '^StartupWMClass=' "$f" || echo 'StartupWMClass=kevmusic-desktop-kevmusic-desktop' >> "$f"
done
dpkg-deb -b --root-owner-group "$WORK/pkg" "$TARGET" >/dev/null
echo "Reempaquetado $TARGET"
