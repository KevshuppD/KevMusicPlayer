#!/usr/bin/env bash
# Recompila KevMusic Desktop con los últimos cambios y lo abre (sin instalar nada).
cd "$(dirname "$(readlink -f "$0")")" || exit 1
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
# Lanzador del escritorio: si falta el JDK, ofrece instalarlo
DEPS=/home/kevshupp/Escritorio/sh/comprobar_dependencias.sh
if [ -f "$DEPS" ]; then
    source "$DEPS"
    necesitar_jdk || exit 1
fi
if ! ./gradlew -q createDistributable; then
    notify-send "KevMusic Desktop" "Error al compilar: ejecuta probar.sh en una terminal para ver el motivo" 2>/dev/null
    exit 1
fi
exec ./build/compose/binaries/main/app/kevmusic-desktop/bin/kevmusic-desktop "$@"
