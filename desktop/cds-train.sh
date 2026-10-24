#!/usr/bin/env bash
# Añade a KevMusic Desktop un archivo AppCDS (clases ya cargadas y verificadas) que reduce el arranque de ~3 s a
# ~1,5 s. La JVM lo genera abriendo la app unos segundos (KEVMUSIC_CDS_TRAIN=1) con -XX:ArchiveClassesAtExit.
#   ./cds-train.sh build/compose/binaries/main/app/kevmusic-desktop   (carpeta de createDistributable)
#   ./cds-train.sh paquete.deb                                         (reempaqueta el .deb)
# El archivo valida tamaño y fecha de cada .jar: se genera sobre los archivos definitivos y no deben cambiar
# después (dpkg y tar conservan las fechas). Si no coincidiera, la JVM lo ignora y arranca como antes.
# En el .deb también corrige el lanzador: nombre "KevMusic Desktop" y StartupWMClass para fijarlo al panel.
set -euo pipefail
TARGET="$(readlink -f "$1")"

train() {
    local app="$1"
    local cfg
    cfg=$(ls "$app"/lib/app/*.cfg)
    local backup
    backup=$(mktemp)
    cp "$cfg" "$backup"
    rm -f "$app/lib/app/kevmusic.jsa"
    echo 'java-options=-XX:ArchiveClassesAtExit=$APPDIR/kevmusic.jsa' >> "$cfg"
    local run=("$app/bin/kevmusic-desktop")
    if [ -z "${DISPLAY:-}" ]; then run=(xvfb-run -a "${run[@]}"); fi
    KEVMUSIC_CDS_TRAIN=1 timeout 120 "${run[@]}" >/dev/null 2>&1 || true
    cp "$backup" "$cfg"
    rm -f "$backup"
    if [ -s "$app/lib/app/kevmusic.jsa" ]; then
        echo 'java-options=-XX:SharedArchiveFile=$APPDIR/kevmusic.jsa' >> "$cfg"
        echo "AppCDS: $(du -h "$app/lib/app/kevmusic.jsa" | cut -f1) en $app"
    else
        echo "AVISO: no se generó el archivo AppCDS; la app arrancará sin él" >&2
    fi
}

if [ -d "$TARGET" ]; then
    train "$TARGET"
    exit 0
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
dpkg-deb -R "$TARGET" "$WORK/pkg"
APP="$WORK/pkg/opt/kevmusic-desktop"
train "$APP"
for f in "$APP"/lib/*.desktop; do
    sed -i 's/^Name=.*/Name=KevMusic Desktop/' "$f"
    grep -q '^StartupWMClass=' "$f" || echo 'StartupWMClass=kevmusic-desktop-kevmusic-desktop' >> "$f"
done
dpkg-deb -b --root-owner-group "$WORK/pkg" "$TARGET" >/dev/null
echo "Reempaquetado $TARGET"
