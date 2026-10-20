#!/bin/bash
# Teste la décision « l'application dessine-t-elle son propre bouton i ? » du bouton Tk (sans écran ni Tk : seule la fonction est chargée).
set -u
ICI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin"
FAIL=0
essai() {  # essai <version installée ou vide> <mode> <attendu>
    if [ -n "$1" ]; then printf '#!/bin/sh\nprintf "%%s" "%s"\n' "$1" > "$T/bin/dpkg-query"; else printf '#!/bin/sh\nexit 1\n' > "$T/bin/dpkg-query"; fi
    chmod +x "$T/bin/dpkg-query"
    r=$(PATH="$T/bin:$PATH" python3 - "$ICI/pkg/usr/bin/storepos-kiosk-infobutton" "$2" <<'PY'
import sys, types, importlib.machinery, importlib.util
sys.modules['tkinter'] = types.ModuleType('tkinter')      # Tk n'est pas nécessaire pour tester la décision
loader = importlib.machinery.SourceFileLoader('infobtn', sys.argv[1])
spec = importlib.util.spec_from_loader('infobtn', loader); m = importlib.util.module_from_spec(spec); loader.exec_module(m)
print(m.app_draws_own_button({"INFO_BUTTON_MODE": sys.argv[2]} if sys.argv[2] else {}))
PY
)
    if [ "$r" = "$3" ]; then echo "  OK    caisse-demo='$1' mode='$2' -> $r"; else echo "  ECHEC caisse-demo='$1' mode='$2' -> $r (attendu $3)"; FAIL=1; fi
}
essai "1.3.2" "" False
essai "1.3.3" "" True
essai "1.4.0" "auto" True
essai "1.10.0" "" True
essai "" "" False
essai "1.3.3" "tk" False
[ "$FAIL" = 0 ] && echo "BOUTON I : OK" || { echo "BOUTON I : ECHEC"; exit 1; }
