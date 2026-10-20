#!/bin/bash
# Télécharge les VRAIS binaires du serveur RustDesk (hbbs, hbbr) pour les tests, sans root, dans ~/supervision-test/rustdesk.
set -eu
V="${RUSTDESK_SERVER_VERSION:-1.1.16}"
D="$HOME/supervision-test/rustdesk"; mkdir -p "$D"
Z="$D/serveur-$V.zip"
if [ ! -s "$Z" ]; then
    for nom in rustdesk-server-linux-amd64.zip; do
        curl -fsSL -o "$Z" "https://github.com/rustdesk/rustdesk-server/releases/download/$V/$nom" && break
    done
fi
sha256sum "$Z"
unzip -qo "$Z" -d "$D/serveur"
find "$D/serveur" -type f | head
H=$(find "$D/serveur" -name hbbs | head -1)
chmod +x "$(dirname "$H")"/* 2>/dev/null || true
"$H" --help 2>&1 | head -30
