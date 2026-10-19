#!/bin/bash
# Contrôle le .deb : métadonnées, contenu, extraction, démarrage de l'application (WSLg ou X11 disponible).
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEB=$(ls "$SRC"/caisse-demo_*_amd64.deb | tail -n 1)
T=$(mktemp -d)
echo "== $DEB"
dpkg-deb --info "$DEB" | sed -n '1,25p'
echo "== fichiers clés"
dpkg-deb --contents "$DEB" | grep -E 'bin/caisse-demo|\.desktop|caisse-demo\.cfg|caisse-demo\.png|usr/bin' | head
echo "== icônes, raccourci, scripts d'installation"
dpkg-deb --contents "$DEB" | grep -E 'hicolor/.*caisse-demo.png|pixmaps|applications/caisse-demo.desktop' | awk '{print $1, $6}'
dpkg-deb --ctrl-tarfile "$DEB" | tar -t | grep -E 'postinst|postrm' | sed 's/^/script de contrôle : /'
echo "== contenu du raccourci"
dpkg-deb --fsys-tarfile "$DEB" | tar -xO ./usr/share/applications/caisse-demo.desktop
command -v desktop-file-validate >/dev/null && dpkg-deb --fsys-tarfile "$DEB" | tar -xO ./usr/share/applications/caisse-demo.desktop > /tmp/cd.desktop && desktop-file-validate /tmp/cd.desktop && echo "desktop-file-validate : OK"
echo "== extraction + droits"
dpkg-deb -x "$DEB" "$T"
ls -l "$T/opt/caisse-demo/bin/caisse-demo" "$T/usr/bin/caisse-demo"
echo "== dépendances natives manquantes (ldd)"
ldd "$T/opt/caisse-demo/bin/caisse-demo" "$T/opt/caisse-demo/lib/libapplauncher.so" 2>&1 | grep -i "not found" || echo "aucune"
echo "== démarrage (15 s) : DISPLAY=${DISPLAY:-?} WAYLAND_DISPLAY=${WAYLAND_DISPLAY:-?}"
export CAISSE_DATA_DIR="$T/data"
timeout 15 "$T/opt/caisse-demo/bin/caisse-demo" >"$T/out.log" 2>&1 &
sleep 12
if pgrep -f "$T/opt/caisse-demo" >/dev/null; then echo "L'application tourne après 12 s : OK"; else echo "L'application s'est arrêtée"; fi
wait 2>/dev/null
echo "== sortie (20 dernières lignes)"; tail -n 20 "$T/out.log"
echo "== base créée ?"; ls -l "$T/data" 2>&1
rm -rf "$T"
