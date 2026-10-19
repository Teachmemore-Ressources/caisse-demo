#!/bin/bash
# Construit le projet dans WSL (système de fichiers Linux = bien plus rapide que /mnt/c).
# Usage : bash tools/wsl-build.sh [tâche gradle...]     (défaut : test)
set -euo pipefail
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$HOME/caisse-demo-build"
GRADLE_VERSION="9.8.1"
GRADLE_HOME_DIR="$HOME/.local/gradle-$GRADLE_VERSION"

mkdir -p "$WORK"
rsync -a --delete --exclude build --exclude .gradle --exclude '*.deb' --exclude out "$SRC/" "$WORK/"
cd "$WORK"

if [ ! -x "$GRADLE_HOME_DIR/bin/gradle" ]; then
    echo "==> Téléchargement de Gradle $GRADLE_VERSION"
    mkdir -p "$HOME/.local"
    curl -fsSL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o /tmp/gradle.zip
    unzip -q -o /tmp/gradle.zip -d "$HOME/.local"
fi

TASKS=("$@")
[ ${#TASKS[@]} -eq 0 ] && TASKS=(test)
"$GRADLE_HOME_DIR/bin/gradle" --console=plain --no-daemon "${TASKS[@]}"

# Si la tâche demandée est createDistributable, assemble aussi le .deb dans le dossier du projet
if [[ " ${TASKS[*]} " == *" createDistributable "* ]]; then
    VERSION=$(grep -oP 'packageVersion = "\K[^"]+' build.gradle.kts)
    python3 "$WORK/tools/make_deb.py" "$WORK/build/compose/binaries/main/app/caisse-demo" "$VERSION" "$SRC/caisse-demo_${VERSION}_amd64.deb"
fi
