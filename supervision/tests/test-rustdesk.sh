#!/bin/bash
#
# Teste la prise en main à distance avec le VRAI serveur RustDesk (hbbs/hbbr 1.1.16, lancés en local avec « -k _ »)
# et les scripts de la caisse (inscription, diagnostic) avec un faux client « rustdesk ».
# Limite assumée : le CLIENT RustDesk lui-même (interface graphique, session réelle) n'est pas lancé ici : il demande un écran.
# Prérequis : bash supervision/tests/fetch-rustdesk.sh
set -u
SUP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
B="$HOME/supervision-test/rustdesk/serveur/amd64"
T=$(mktemp -d); PIDS=(); FAIL=0
cleanup() { for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done; sleep 1; pkill -9 -f "$T" 2>/dev/null; rm -rf "$T"; }
trap cleanup EXIT
ok() { echo "  OK    $*"; }; ko() { echo "  ECHEC $*"; FAIL=1; }; etape() { printf '\n== %s ==\n' "$*"; }
[ -x "$B/hbbs" ] || { echo "Serveur RustDesk absent : bash tests/fetch-rustdesk.sh" >&2; exit 2; }
for p in 31115 31116 31117 31118 31119; do
    if (exec 3<>/dev/tcp/127.0.0.1/$p) 2>/dev/null; then echo "Port $p occupé" >&2; exit 2; fi
done
joignable() { timeout 4 bash -c "exec 3<>/dev/tcp/127.0.0.1/$1" 2>/dev/null; }

etape "1. Le VRAI serveur démarre avec « -k _ » : clé créée, ports ouverts"
mkdir -p "$T/data"; cd "$T/data" || exit 1
"$B/hbbs" -p 31116 -r 127.0.0.1:31117 -k _ >"$T/hbbs.log" 2>&1 & PIDS+=($!)
for i in $(seq 1 20); do [ -s "$T/data/id_ed25519.pub" ] && break; sleep 0.5; done
"$B/hbbr" -p 31117 -k _ >"$T/hbbr.log" 2>&1 & PIDS+=($!)
sleep 2
[ -s "$T/data/id_ed25519.pub" ] && [ -s "$T/data/id_ed25519" ] && ok "paire de clés créée (id_ed25519 + id_ed25519.pub)" || ko "pas de clé"
CLE=$(tr -d '[:space:]' < "$T/data/id_ed25519.pub")
printf '%s' "$CLE" | grep -Eq '^[A-Za-z0-9+/]{43}=$' && ok "clé publique au bon format (44 caractères base64)" || ko "format de la clé : $CLE"
grep -q "Key: $CLE" "$T/hbbs.log" && ok "hbbs annonce exiger cette clé (« -k _ »)" || ko "hbbs n'annonce pas la clé"
for p in 31115 31116 31117; do joignable $p && ok "port $p accessible" || ko "port $p fermé"; done
joignable 31118 && ok "port 31118 (client web) ouvert par hbbs : à NE PAS ouvrir sur Internet (le groupe de sécurité ne l'ouvre pas)" || echo "  info  port 31118 fermé"
ls -la "$T/data" | grep -q id_ed25519 && [ "$(stat -c %a "$T/data/id_ed25519")" != "" ] && ok "clé privée dans le dossier de données : à sauvegarder (volume rustdesk_data)" || true

etape "2. Scripts du serveur : clé publique et mots de passe dérivés"
S="$T/serveur"; mkdir -p "$S/scripts"; cp "$SUP/serveur/scripts/rustdesk-cle.sh" "$SUP/serveur/scripts/rustdesk-mdp.sh" "$S/scripts/"
SECRET="s3cr3t-de-test-0123456789-abcdefghij"
printf 'RUSTDESK_DOMAIN=rustdesk.teachmemore.fr   # commentaire\nRUSTDESK_SECRET=%s\n' "$SECRET" > "$S/.env"
sortie=$(RUSTDESK_DATA_DIR="$T/data" bash "$S/scripts/rustdesk-cle.sh" 2>&1)
echo "$sortie" | grep -q "Clé publique     : $CLE" && ok "rustdesk-cle.sh affiche la clé du vrai serveur" || { ko "rustdesk-cle.sh"; echo "$sortie"; }
echo "$sortie" | grep -q -- "--server rustdesk.teachmemore.fr --key '$CLE'" && ok "commande d'inscription prête (domaine du .env, sans commentaire)" || ko "commande d'inscription"
RUSTDESK_DATA_DIR="$T/vide" bash "$S/scripts/rustdesk-cle.sh" >/dev/null 2>&1 && ko "clé absente acceptée" || ok "serveur sans clé : refus explicite"
ref=$(python3 -I - "$SECRET" 123456789 <<'PY'
import sys, hmac, hashlib, base64
secret, ident = sys.argv[1].encode(), sys.argv[2]
print(base64.urlsafe_b64encode(hmac.new(secret, f"rustdesk:{ident}".encode(), hashlib.sha256).digest()).decode().rstrip("=")[:24])
PY
)
mdp=$(bash "$S/scripts/rustdesk-mdp.sh" 123456789)
[ "$mdp" = "$ref" ] && [ "${#mdp}" = 24 ] && ok "rustdesk-mdp.sh = HMAC-SHA256 de référence (24 caractères) : $mdp" || ko "mot de passe dérivé : '$mdp' attendu '$ref'"
[ "$(bash "$S/scripts/rustdesk-mdp.sh" 123456790)" != "$mdp" ] && ok "deux caisses ont deux mots de passe différents" || ko "mots de passe identiques"
bash "$S/scripts/rustdesk-mdp.sh" 12ab >/dev/null 2>&1 && ko "ID invalide accepté" || ok "ID invalide refusé"
RUSTDESK_SECRET=court bash "$S/scripts/rustdesk-mdp.sh" 123456789 >/dev/null 2>&1 && ko "secret trop court accepté" || ok "secret trop court refusé"

etape "3. Inscription d'une caisse (faux client rustdesk)"
mkdir -p "$T/shim" "$T/conf" "$T/etat"
cat > "$T/shim/rustdesk" <<'EOF'
#!/bin/bash
case "$1" in
  --get-id) echo "${FAUX_ID:-123456789}" ;;
  --password) echo "$2" >> "$SHIM_MDP" ;;
esac
EOF
chmod +x "$T/shim/rustdesk"; export SHIM_MDP="$T/mdp.log"; : > "$SHIM_MDP"
export STOREPOS_TEST=1 STOREPOS_TEST_SANS_SERVICE=1 RUSTDESK_BIN="$T/shim/rustdesk" RUSTDESK_CONF_DIR="$T/conf" STOREPOS_STATE_DIR="$T/etat" RUSTDESK_ATTENTE_ID=2
E="$SUP/remote-agent/pkg/usr/sbin/storepos-kiosk-remote-enroll"; K="$SUP/remote-agent/pkg/usr/sbin/storepos-kiosk-remote-check"
printf '%s\n' "$SECRET" > "$T/secret"; chmod 600 "$T/secret"
sortie=$(bash "$E" --server 127.0.0.1 --key "$CLE" --secret-file "$T/secret" 2>&1) && ok "inscription réussie" || { ko "inscription"; echo "$sortie"; }
CFG="$T/conf/RustDesk2.toml"
grep -q "^rendezvous_server = '127.0.0.1:21116'" "$CFG" && grep -q "^custom-rendezvous-server = '127.0.0.1'" "$CFG" && grep -q "^relay-server = '127.0.0.1'" "$CFG" && grep -q "^key = '$CLE'" "$CFG" && ok "RustDesk2.toml : serveur, relais et clé du serveur de l'entreprise" || { ko "contenu de la configuration"; cat "$CFG"; }
[ "$(stat -c %a "$CFG")" = 600 ] && [ "$(stat -c %a "$T/conf")" = 700 ] && ok "configuration en 0600 dans un dossier 0700" || ko "droits de la configuration"
[ "$(cat "$T/etat/remote-id")" = 123456789 ] && ok "ID enregistré pour le bouton « i » (remote-id)" || ko "remote-id"
[ "$(tail -1 "$SHIM_MDP")" = "$ref" ] && ok "le mot de passe posé sur la caisse est EXACTEMENT celui que calcule le serveur (même formule)" || ko "mot de passe de la caisse : '$(tail -1 "$SHIM_MDP")'"
echo "$sortie" | grep -q "$ref" && ko "le mot de passe est affiché à l'écran" || ok "le mot de passe n'est pas affiché"
echo "$sortie" | grep -q "$SECRET" && ko "le secret est affiché" || ok "le secret n'est pas affiché"
refus() { out=$(bash "$E" "$@" 2>&1) && ko "accepté à tort : $*" || { [ -n "$REFUS" ] && echo "$out" | grep -q "$REFUS" || ko "message inattendu pour [$*] : $out"; }; }
REFUS="invalide" refus --server "evil.com:21116" --key "$CLE" --secret-file "$T/secret"
REFUS="invalide" refus --server "http://x.fr" --key "$CLE" --secret-file "$T/secret"
REFUS="invalide" refus --server "x.fr'; rm -rf /; '" --key "$CLE" --secret-file "$T/secret"
REFUS="invalide" refus --server x.fr --key "pas-une-cle" --secret-file "$T/secret"
REFUS="pas les deux" refus --server x.fr --key "$CLE" --secret-file "$T/secret" --password "MotDePasseTresLong1"
REFUS="Il faut" refus --server x.fr --key "$CLE"
REFUS="invalide" refus --server x.fr --key "$CLE" --password "court"
REFUS="invalide" refus --server x.fr --key "$CLE" --password "un mot de passe avec espaces"
printf 'trop-court\n' > "$T/court"; REFUS="trop court" refus --server x.fr --key "$CLE" --secret-file "$T/court"
REFUS="obligatoires" refus --server x.fr
ok "noms de serveur, clés, mots de passe et secrets dangereux ou invalides refusés"
printf '%s\n' "$SECRET" > "$T/secret-ouvert"; chmod 644 "$T/secret-ouvert"
bash "$E" --server 127.0.0.1 --key "$CLE" --secret-file "$T/secret-ouvert" 2>&1 | grep -q "lisible par tous" && ok "secret lisible par tous : avertissement" || ko "pas d'avertissement sur les droits du secret"
bash "$E" --server 127.0.0.1 --key "$CLE" --password "MotDePasseImpose-12345" >/dev/null 2>&1 && [ "$(tail -1 "$SHIM_MDP")" = "MotDePasseImpose-12345" ] && ok "mot de passe imposé (--password) pris en compte" || ko "--password"
FAUX_ID=12 bash "$E" --server 127.0.0.1 --key "$CLE" --password "MotDePasseImpose-12345" >/dev/null 2>&1 && ko "ID invalide accepté" || ok "ID RustDesk introuvable : l'inscription échoue franchement"

etape "4. Diagnostic de la caisse contre le VRAI serveur"
bash "$E" --server 127.0.0.1 --key "$CLE" --secret-file "$T/secret" >/dev/null 2>&1
export RUSTDESK_PORT_ID=31116 RUSTDESK_PORT_RELAIS=31117
out=$(bash "$K" 2>&1); echo "$out" | grep -q 'annuaire joignable' && echo "$out" | grep -q 'relais joignable' && ok "check : annuaire et relais joignables sur le vrai serveur" || { ko "check joignabilité"; echo "$out"; }
echo "$out" | grep -q 'ID de la caisse : 123456789' && echo "$out" | grep -q 'clé du serveur présente' && ok "check : ID, clé et fichier contrôlés" || ko "check ID/clé"
for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done; sleep 2
out=$(bash "$K" 2>&1); echo "$out" | grep -q 'annuaire injoignable' && ok "check : serveur arrêté -> annuaire injoignable détecté" || ko "check n'a pas détecté l'arrêt"
sed -i "s/^key = .*/key = ''/" "$CFG"
bash "$K" 2>&1 | grep -q 'sans elle, ce poste utiliserait les serveurs PUBLICS' && ok "check : clé absente signalée (risque d'utiliser les serveurs publics)" || ko "clé absente non signalée"
rm -f "$CFG"; bash "$K" 2>&1 | grep -q 'non inscrite' && ok "check : caisse non inscrite détectée" || ko "caisse non inscrite"

etape "5. Fichiers livrés : compose, sauvegarde, groupe de sécurité"
python3 -I - "$SUP/serveur" <<'PY' && ok "compose RustDesk : image versionnée, « -k _ » sur hbbs ET hbbr, relais déclaré, clés dans un volume, rien d'autre publié" || ko "compose RustDesk non conforme"
import sys, yaml
d = yaml.safe_load(open(sys.argv[1] + "/docker-compose.rustdesk.yml"))
s = d["services"]
for n in ("hbbs", "hbbr"):
    assert s[n]["image"] == "rustdesk/rustdesk-server:1.1.16", s[n]["image"]
    assert s[n]["command"][-2:] == ["-k", "_"], s[n]["command"]
    assert s[n]["network_mode"] == "host" and s[n]["volumes"] == ["rustdesk_data:/root"]
assert s["hbbs"]["command"][:3] == ["hbbs", "-r", "${RUSTDESK_DOMAIN:?définir RUSTDESK_DOMAIN dans .env}:21117"], s["hbbs"]["command"]
assert "rustdesk_data" in d["volumes"]
assert not any("ports" in v for v in s.values())
PY
grep -q 'RUSTDESK_VOLUMES="rustdesk_data"' "$SUP/serveur/scripts/sauvegarder.sh" && ok "la sauvegarde inclut le volume RustDesk (clé privée du serveur)" || ko "sauvegarde sans RustDesk"
R="$SUP/terraform/network.tf"
grep -Eq '"21115-21115" *= *"tcp"' "$R" && grep -Eq '"21116-21116" *= *"tcp"' "$R" && grep -Eq '"21117-21117" *= *"tcp"' "$R" && grep -Eq '"21116-21116-udp" *= *"udp"' "$R" && ok "groupe de sécurité : 21115, 21116 (tcp+udp) et 21117 seulement" || ko "ports RustDesk du groupe de sécurité"
grep -vE '^[[:space:]]*#' "$R" | grep -qE '2111[89]' && ko "ports du client web (21118/21119) ouverts" || ok "ports du client web 21118/21119 non ouverts"
grep -Eq 'for_each *= *\{ *for .* if var\.activer_rustdesk' "$R" && ok "ports ouverts seulement si activer_rustdesk = true" || ko "ouverture conditionnelle"

echo; [ "$FAIL" = 0 ] && echo "PRISE EN MAIN À DISTANCE : TOUT EST OK" || { echo "PRISE EN MAIN À DISTANCE : DES ECHECS"; exit 1; }
