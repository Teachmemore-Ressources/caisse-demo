#!/bin/bash
# Teste postinst/postrm dans un bac à sable (aucun fichier système touché) :
# les chemins système sont redirigés vers un dossier temporaire et la liste des utilisateurs est simulée.
set -euo pipefail
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
T=$(mktemp -d)
mkdir -p "$T/home/alice" "$T/home/bob" "$T/home/caissepos" "$T/skel" "$T/share"

# raccourci tel que livré par le paquet
python3 - "$SRC" "$T/share/caisse-demo.desktop" <<'EOF'
import re, sys
src = open(sys.argv[1] + "/tools/make_deb.py", encoding="utf-8").read()
m = re.search(r'DESKTOP = """(.*?)"""', src, re.S)
open(sys.argv[2], "w", encoding="utf-8").write(m.group(1))
EOF

adapter() {  # redirige les chemins et simule getent / runuser
    sed -e "s#/etc/skel#$T/skel#g" \
        -e "s#/usr/share/applications/caisse-demo.desktop#$T/share/caisse-demo.desktop#g" \
        -e "s#/usr/share/icons/hicolor#$T/share#g" \
        -e "s#/usr/share/applications#$T/share#g" \
        -e "s#getent passwd#printf 'alice:x:1000:1000::$T/home/alice:/bin/bash\\\\nbob:x:1001:1001::$T/home/bob:/bin/bash\\\\ncaissepos:x:1002:1002::$T/home/caissepos:/bin/bash\\\\nsvc:x:1003:1003::$T/home/svc:/usr/sbin/nologin\\\\n'#g" \
        -e 's#runuser -u "\$user" --#env#g' \
        -e 's#chown "\$user" #true #g' "$1"
}
adapter "$SRC/tools/deb/postinst" > "$T/postinst.sh"
adapter "$SRC/tools/deb/postrm" > "$T/postrm.sh"

echo "== postinst configure"
sh "$T/postinst.sh" configure
ok() { [ -e "$1" ] && echo "  OK   $2" || { echo "  ECHEC $2"; FAIL=1; }; }
non() { [ ! -e "$1" ] && echo "  OK   $2" || { echo "  ECHEC $2"; FAIL=1; }; }
FAIL=0
ok  "$T/skel/Desktop/caisse-demo.desktop"      "raccourci pour les futurs utilisateurs (/etc/skel)"
ok  "$T/home/alice/Desktop/caisse-demo.desktop" "raccourci sur le bureau d'alice"
ok  "$T/home/bob/Desktop/caisse-demo.desktop"   "raccourci sur le bureau de bob"
non "$T/home/caissepos/Desktop/caisse-demo.desktop" "pas de raccourci pour le compte kiosque"
non "$T/home/svc/Desktop/caisse-demo.desktop"   "pas de raccourci pour un compte sans shell"
[ -x "$T/home/alice/Desktop/caisse-demo.desktop" ] && echo "  OK   le raccourci est exécutable" || { echo "  ECHEC raccourci non exécutable"; FAIL=1; }

echo "== postinst relancé (mise à jour) : idempotent"
sh "$T/postinst.sh" configure 1.1.0 && echo "  OK"

echo "== postrm upgrade : les raccourcis restent"
sh "$T/postrm.sh" upgrade
ok "$T/home/alice/Desktop/caisse-demo.desktop" "raccourci conservé pendant une mise à jour"

echo "== postrm remove : les raccourcis disparaissent"
sh "$T/postrm.sh" remove
non "$T/home/alice/Desktop/caisse-demo.desktop" "raccourci d'alice retiré"
non "$T/home/bob/Desktop/caisse-demo.desktop"   "raccourci de bob retiré"
non "$T/skel/Desktop/caisse-demo.desktop"       "modèle /etc/skel retiré"

rm -rf "$T"
[ "$FAIL" -eq 0 ] && echo "TOUT EST OK" || { echo "DES ECHECS"; exit 1; }
