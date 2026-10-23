#!/usr/bin/env bash
# Publica una versión de KevMusic Desktop: ./publicar.sh 1.0.1
# Sube la versión en build.gradle.kts, hace commit con changelog/desktop-v<versión>.md y empuja el tag
# desktop-v<versión>; GitHub Actions (desktop-release.yml) compila el .deb y crea la release.
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"
VERSION="${1:-}"
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Uso: $0 X.Y.Z   (versión actual: $(grep -m1 -oP '^version = "\K[^"]+' build.gradle.kts))"
    exit 1
fi
TAG="desktop-v$VERSION"
NOTES="../changelog/$TAG.md"
if [ ! -f "$NOTES" ]; then
    echo "Falta $NOTES con las notas de la versión (viñetas '- ', **negrita**)."
    exit 1
fi
if git rev-parse "$TAG" >/dev/null 2>&1; then
    echo "El tag $TAG ya existe."
    exit 1
fi
sed -i "0,/^version = \".*\"/s//version = \"$VERSION\"/" build.gradle.kts
git add build.gradle.kts "$NOTES"
git commit -m "release: KevMusic Desktop $VERSION"
git tag "$TAG"
git push origin HEAD
git push origin "$TAG"
echo "Publicado $TAG. Sigue la compilación en https://github.com/KevshuppD/KevMusicPlayer/actions"
