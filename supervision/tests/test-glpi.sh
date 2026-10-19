#!/bin/bash
#
# Teste la chaîne d'INVENTAIRE avec de vrais composants : le VRAI agent GLPI 1.21 (extrait sans root) -> le VRAI Caddy (site glpi livré,
# identifiant par enseigne) -> un FAUX serveur GLPI. Vérifie aussi les scripts storepos-kiosk-glpi-enroll / -check.
# Prérequis : tests/fetch-tools.sh, tests/fetch-glpi-agent.sh, tests/fetch-glpi-perl.sh, tests/completer-perl-glpi.sh
# Limite assumée : GLPI lui-même n'est PAS lancé (PHP, MariaDB et Docker indisponibles ici) ; sa réponse est simulée.
set -u
SUP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="${SUPERVISION_TOOLS:-$HOME/supervision-test/bin}"
H="$HOME/supervision-test/glpi-agent"; P5="$HOME/supervision-test/perl5"
T=$(mktemp -d); PIDS=(); FAIL=0
cleanup() { for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done; sleep 1; pkill -9 -f "$T" 2>/dev/null; rm -rf "$T"; }
trap cleanup EXIT
ok() { echo "  OK    $*"; }; ko() { echo "  ECHEC $*"; FAIL=1; }; etape() { printf '\n== %s ==\n' "$*"; }
[ -x "$H/usr/bin/glpi-agent" ] || { echo "Agent GLPI absent : bash tests/fetch-glpi-agent.sh puis completer-perl-glpi.sh" >&2; exit 2; }
for port in 18080 18081 18082 18090 2019; do
    if (exec 3<>/dev/tcp/127.0.0.1/$port) 2>/dev/null; then echo "Port $port occupé" >&2; exit 2; fi
done
code() { curl -s -o /dev/null -m 15 -w '%{http_code}' "$@"; }

etape "1. Serveur : site Caddy GLPI livré + identifiant d'enseigne créé par le script livré"
S="$T/serveur"; mkdir -p "$S"; cp -r "$SUP/serveur/caddy" "$SUP/serveur/scripts" "$S/"
cp "$S/caddy/sites/glpi.caddy.exemple" "$S/caddy/sites/glpi.caddy"
sed -i "s#/etc/caddy/#$S/caddy/#g" "$S/caddy/Caddyfile"
export ENSEIGNES_FILE="$S/caddy/enseignes.caddy" CADDY_HASH_CMD="$BIN/caddy hash-password --plaintext" CADDY_RELOAD_CMD=":"
PW=$(bash "$S/scripts/enseigne-ajouter.sh" alpha | sed -n 's/^  Mot de passe : \([A-Za-z0-9]*\) .*/\1/p')
[ "${#PW}" = 40 ] && ok "enseigne « alpha » créée" || ko "création de l'enseigne"
export ACME_EMAIL=t@example.com METRICS_DOMAIN="http://localhost:18080" LOGS_DOMAIN="http://localhost:18081" GLPI_DOMAIN="http://localhost:18082" \
       PROMETHEUS_UPSTREAM=127.0.0.1:1 LOKI_UPSTREAM=127.0.0.1:1 GLPI_UPSTREAM=127.0.0.1:18090 GRAFANA_DOMAIN=grafana.invalide
"$BIN/caddy" validate --config "$S/caddy/Caddyfile" --adapter caddyfile >"$T/val.log" 2>&1 && ok "Caddy accepte le Caddyfile avec le site GLPI" || { ko "Caddyfile invalide"; cat "$T/val.log"; }
python3 -I "$SUP/tests/fake_glpi_server.py" 18090 "$T/requetes.jsonl" & PIDS+=($!)
"$BIN/caddy" run --config "$S/caddy/Caddyfile" --adapter caddyfile >"$T/caddy.log" 2>&1 & PIDS+=($!)
for i in $(seq 1 30); do [ "$(code http://localhost:18082/healthz)" = 200 ] && break; sleep 1; done
[ "$(code http://localhost:18082/healthz)" = 200 ] && ok "le site GLPI de Caddy répond (/healthz)" || ko "Caddy ne répond pas"

etape "2. Filtrage : seul l'envoi d'inventaire passe, avec l'identifiant de l'enseigne"
G="http://localhost:18082"; : > "$T/requetes.jsonl"
c=$(code -X POST "$G/front/inventory.php" -d '{}'); [ "$c" = 401 ] && ok "envoi sans identifiant → 401" || ko "sans identifiant : $c"
c=$(code -u alpha:mauvais -X POST "$G/front/inventory.php" -d '{}'); [ "$c" = 401 ] && ok "mauvais mot de passe → 401" || ko "mauvais mot de passe : $c"
[ ! -s "$T/requetes.jsonl" ] && ok "rien n'a atteint GLPI sans identifiant valide" || ko "une requête non authentifiée a atteint GLPI"
c=$(code -u alpha:$PW -X POST "$G/front/inventory.php" -d '{}'); [ "$c" = 200 ] && ok "POST /front/inventory.php avec identifiant → 200" || ko "inventory.php : $c"
c=$(code -u alpha:$PW -X POST "$G/" -d '{}'); [ "$c" = 200 ] && ok "POST / avec identifiant → 200 (protocole natif de l'agent)" || ko "POST / : $c"
for chemin in /login.php /front/central.php /front/computer.php /apirest.php/initSession /install/install.php /index.php /plugins/ /marketplace/; do
    c=$(code -u alpha:$PW "$G$chemin"); [ "$c" = 404 ] || ko "interface de GLPI exposée : GET $chemin → $c"
    c=$(code -u alpha:$PW -X POST "$G$chemin" -d x); [ "$c" = 404 ] || ko "interface de GLPI exposée : POST $chemin → $c"
done; ok "interface web, API, installation et plugins de GLPI : 404 même avec un identifiant valide"
c=$(code -u alpha:$PW "$G/"); [ "$c" = 404 ] && ok "GET / → 404 (seul POST passe)" || ko "GET / : $c"
auth=$(python3 -c "import json;[print(json.loads(l)['authorization'] or 'AUCUN') for l in open('$T/requetes.jsonl')]" | sort -u | tr '\n' ' ')
[ "$auth" = "AUCUN " ] && ok "l'en-tête d'authentification n'est jamais transmis à GLPI" || ko "Authorization transmis à GLPI : $auth"
head -c 40000000 /dev/zero > "$T/gros.bin"; c=$(code -u alpha:$PW -X POST "$G/front/inventory.php" --data-binary @"$T/gros.bin"); [ "$c" = 413 ] && ok "envoi de 40 Mo refusé (limite 32 Mo) → 413" || ko "limite de taille : $c"

etape "3. Scripts de la caisse : storepos-kiosk-glpi-enroll"
E="$SUP/glpi-agent/pkg/usr/sbin/storepos-kiosk-glpi-enroll"; K="$SUP/glpi-agent/pkg/usr/sbin/storepos-kiosk-glpi-check"
mkdir -p "$T/conf.d" "$T/shim"
printf '#!/bin/sh\necho "GLPI Agent (1.21-1)"\n' > "$T/shim/glpi-agent"; chmod +x "$T/shim/glpi-agent"
cat > "$T/supervision.conf" <<EOF
METRICS_URL=https://metrics.exemple.fr/api/v1/write
METRICS_USER=alpha
METRICS_PASSWORD=$PW
ENSEIGNE=alpha
MAGASIN=LYON-01
POSTE=2
EOF
export STOREPOS_TEST=1 GLPI_CONF_DIR="$T/conf.d" STOREPOS_MONITORING_CONF="$T/supervision.conf"
bash "$E" --server-url "$G/front/inventory.php" >"$T/enroll.log" 2>&1 && ok "inscription réussie (identifiants repris de la supervision)" || { ko "inscription"; cat "$T/enroll.log"; }
CFG="$T/conf.d/50-storepos-kiosk-serveur.cfg"
grep -q "^server = $G/front/inventory.php" "$CFG" && grep -q '^user = alpha' "$CFG" && grep -q "^password = $PW" "$CFG" && grep -q '^tag = alpha/LYON-01/2' "$CFG" && ok "fichier de configuration : serveur, utilisateur, mot de passe et étiquette enseigne/magasin/poste" || { ko "contenu du fichier"; cat "$CFG"; }
[ "$(stat -c %a "$CFG")" = 600 ] && ok "fichier en droits 0600 (il contient un mot de passe)" || ko "droits : $(stat -c %a "$CFG")"
grep -q '^no-httpd = 1' "$SUP/glpi-agent/pkg/etc/glpi-agent/conf.d/40-storepos-kiosk.cfg" && ok "paquet : interface web de l'agent désactivée (aucun port ouvert)" || ko "no-httpd absent"
refus() { out=$(bash "$E" "$@" 2>&1) && ko "accepté à tort : $*" || echo "$out" | grep -q "$REFUS" && true || ko "message inattendu pour $* : $out"; }
REFUS="https" refus --server-url http://glpi.exemple.fr/front/inventory.php
REFUS="obligatoire" refus
REFUS="quote" refus --server-url https://g.exemple.fr/x --password "a'b"
REFUS="quote" refus --server-url https://g.exemple.fr/x --password "a b"
REFUS="invalide" refus --server-url https://g.exemple.fr/x --magasin 'LYON;rm'
ok "adresse http, mots de passe dangereux et étiquettes invalides refusés"
STOREPOS_MONITORING_CONF=/nonexistent bash "$E" --server-url https://g.exemple.fr/x >/dev/null 2>&1 && ko "inscription sans identifiants acceptée" || ok "sans identifiants ni supervision : refusé"

etape "4. Scripts de la caisse : storepos-kiosk-glpi-check"
out=$(PATH="$T/shim:$PATH" bash "$K" 2>&1); echo "$out" | grep -q 'serveur joint, identifiants acceptés' && ok "check : serveur joint, identifiants acceptés" || { ko "check OK attendu"; echo "$out"; }
echo "$out" | grep -q 'caisse inscrite' && echo "$out" | grep -q 'fichier protégé' && ok "check : réglages et droits contrôlés" || ko "check réglages"
cp "$CFG" "$T/cfg.sauve"; sed -i 's/^password = .*/password = faux/' "$CFG"
out=$(PATH="$T/shim:$PATH" bash "$K" 2>&1); echo "$out" | grep -q 'accès refusé (code 401)' && ok "check : mauvais mot de passe détecté (401)" || { ko "check 401 attendu"; echo "$out"; }
cp "$T/cfg.sauve" "$CFG"
sed -i "s#^server = .*#server = http://localhost:18082/front/autre.php#" "$CFG"
out=$(PATH="$T/shim:$PATH" bash "$K" 2>&1); echo "$out" | grep -q 'code 404' && ok "check : mauvaise adresse détectée (404)" || ko "check 404 attendu"
cp "$T/cfg.sauve" "$CFG"

etape "5. Le VRAI agent GLPI 1.21 envoie son inventaire à travers Caddy"
export LD_LIBRARY_PATH="$P5/usr/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}"
export PERL5LIB="$H/usr/share/glpi-agent/lib:$P5/usr/share/perl5:$P5/usr/lib/x86_64-linux-gnu/perl5/5.40:$P5/usr/lib/x86_64-linux-gnu/perl/5.40"
cat "$SUP/glpi-agent/pkg/etc/glpi-agent/conf.d/40-storepos-kiosk.cfg" "$CFG" > "$T/agent.cfg"
printf 'logger = stderr\ndelaytime = 1\ndebug = 1\n' >> "$T/agent.cfg"
mkdir -p "$T/var"; : > "$T/requetes.jsonl"
( cd "$H" && timeout 150 perl "$H/usr/bin/glpi-agent" --conf-file="$T/agent.cfg" --force --no-category=software --vardir="$T/var" >"$T/agent.log" 2>&1 )
n=$(wc -l < "$T/requetes.jsonl")
[ "$n" -ge 1 ] && ok "GLPI (simulé) a reçu $n requête(s) de l'agent à travers Caddy" || { ko "aucune requête reçue"; tail -8 "$T/agent.log"; }
python3 - "$T/requetes.jsonl" <<'EOF' && ok "requêtes : chemin /front/inventory.php, sans en-tête d'authentification, corps compressé" || ko "forme des requêtes"
import json, sys
r = [json.loads(l) for l in open(sys.argv[1])]
assert r, "vide"
assert all(x['chemin'] == '/front/inventory.php' and x['methode'] == 'POST' for x in r), [x['chemin'] for x in r]
assert all(x['authorization'] == '' for x in r)
assert any(x['corps_taille'] > 100 for x in r)
EOF
grep -q '"user_id":"alpha"' "$T/caddy.log" && ok "Caddy a authentifié l'agent comme l'enseigne « alpha »" || ko "enseigne absente du journal de Caddy"
grep -q '"status":401' "$T/caddy.log" && grep -q '"status":200' "$T/caddy.log" && ok "dialogue d'authentification observé : 401 puis 200 (l'agent représente ses identifiants)" || ko "pas de dialogue 401 puis 200"
grep -qi 'error' "$T/agent.log" | head -1; grep -E '^\[error\]' "$T/agent.log" | head -3
grep -q "$PW" "$T/caddy.log" && ko "mot de passe dans le journal de Caddy !" || ok "mot de passe absent du journal de Caddy"

etape "6. Agent avec un mauvais mot de passe : rien n'arrive à GLPI"
sed -i 's/^password = .*/password = mauvais/' "$T/agent.cfg"; : > "$T/requetes.jsonl"; rm -rf "$T/var"; mkdir -p "$T/var"
( cd "$H" && timeout 150 perl "$H/usr/bin/glpi-agent" --conf-file="$T/agent.cfg" --force --no-category=software --vardir="$T/var" >"$T/agent2.log" 2>&1 )
[ ! -s "$T/requetes.jsonl" ] && ok "avec un mauvais mot de passe, aucune requête n'atteint GLPI" || ko "des requêtes ont atteint GLPI malgré le mauvais mot de passe"

echo; [ "$FAIL" = 0 ] && echo "INVENTAIRE GLPI : TOUT EST OK" || { echo "INVENTAIRE GLPI : DES ECHECS"; exit 1; }
