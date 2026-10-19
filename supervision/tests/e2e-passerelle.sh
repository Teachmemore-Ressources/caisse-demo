#!/bin/bash
#
# Test de la PASSERELLE avec les vrais composants : Caddy (fichiers livrés) devant Prometheus et Loki, avec de vrais agents Alloy.
#   - un identifiant par enseigne (créé par les scripts livrés), ajout à chaud, révocation
#   - seuls deux chemins d'envoi traversent ; tout le reste (lecture, interface) répond 404
#   - refus sans identifiant / avec un mauvais ; limite de taille ; sonde de disponibilité ; identifiant dans les journaux d'accès
# Prérequis : bash supervision/tests/fetch-tools.sh      Usage : bash supervision/tests/e2e-passerelle.sh
set -u
SUP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="${SUPERVISION_TOOLS:-$HOME/supervision-test/bin}"
T=$(mktemp -d)
PROM=19090; LOKI=13100; M_PORT=18080; L_PORT=18081
FAIL=0; PIDS=()
cleanup() { for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done; sleep 1; pkill -9 -f "$T" 2>/dev/null; rm -rf "$T"; }
trap cleanup EXIT
ok()   { echo "  OK    $*"; }
ko()   { echo "  ECHEC $*"; FAIL=1; }
etape() { printf '\n== %s ==\n' "$*"; }
for port in $PROM $LOKI $M_PORT $L_PORT 2019 12391 12392 12393 12394; do
    if (exec 3<>/dev/tcp/127.0.0.1/$port) 2>/dev/null; then echo "Le port $port est déjà utilisé (reste d'un ancien test : pkill -f supervision-test)." >&2; exit 2; fi
done

code() { curl -s -o /dev/null -m 15 -w '%{http_code}' "$@"; }        # code HTTP seul
attendre_code() {  # attendre_code <attendu> <description> <args curl...> : réessaie quelques secondes
    local attendu="$1" desc="$2"; shift 2; local c=""
    for i in $(seq 1 15); do c=$(code "$@"); [ "$c" = "$attendu" ] && break; sleep 1; done
    [ "$c" = "$attendu" ] && ok "$desc → $c" || ko "$desc : attendu $attendu, obtenu $c"
}

# ---------------------------------------------------------------------------------------------------------
etape "1. Fichiers livrés copiés dans un bac à sable ; identifiants créés avec les scripts livrés"
S="$T/serveur"; mkdir -p "$S"; cp -r "$SUP/serveur/caddy" "$SUP/serveur/scripts" "$S/"
sed -i "s#/etc/caddy/#$S/caddy/#g" "$S/caddy/Caddyfile"
export ENSEIGNES_FILE="$S/caddy/enseignes.caddy" CADDY_HASH_CMD="$BIN/caddy hash-password --plaintext" CADDY_RELOAD_CMD=":"
creer() { bash "$S/scripts/enseigne-ajouter.sh" "$1" | sed -n 's/^  Mot de passe : \([A-Za-z0-9]*\) .*/\1/p'; }
printf 'METRICS_DOMAIN=metrics.test.fr            # commentaire\nLOGS_DOMAIN=logs.test.fr   # autre\n' > "$S/.env"
SORTIE=$(bash "$S/scripts/enseigne-ajouter.sh" alpha); PW_ALPHA=$(echo "$SORTIE" | sed -n 's/^  Mot de passe : \([A-Za-z0-9]*\) .*/\1/p')
echo "$SORTIE" | grep -q -- "--metrics-url https://metrics.test.fr/api/v1/write --logs-url https://logs.test.fr/loki/api/v1/push" \
    && ok "la commande d'enrôlement affichée reprend les noms de domaine du .env (sans commentaires)" || ko "commande d'enrôlement incorrecte"
PW_BRAVO=$(creer bravo)
[ "${#PW_ALPHA}" = "40" ] && [ "${#PW_BRAVO}" = "40" ] && ok "2 enseignes créées (mots de passe de 40 caractères)" || ko "création des enseignes (${#PW_ALPHA}/${#PW_BRAVO})"
[ "$PW_ALPHA" != "$PW_BRAVO" ] && ok "mots de passe différents" || ko "mots de passe identiques"
grep -q "$PW_ALPHA" "$S/caddy/enseignes.caddy" && ko "le mot de passe est écrit EN CLAIR dans le fichier" || ok "le mot de passe n'est conservé qu'en empreinte bcrypt"
bash "$S/scripts/enseigne-ajouter.sh" alpha >/dev/null 2>&1 && ko "doublon accepté" || ok "doublon refusé"
for mauvais in 'Alpha' 'a' 'a b' '-x' 'x;rm'; do bash "$S/scripts/enseigne-ajouter.sh" "$mauvais" >/dev/null 2>&1 && ko "nom invalide accepté : '$mauvais'" || true; done; ok "noms invalides refusés"
liste=$(bash "$S/scripts/enseigne-lister.sh" 2>/dev/null | tr '\n' ' '); [ "$liste" = "alpha bravo " ] && ok "liste : $liste(le modèle n'apparaît pas)" || ko "liste : '$liste'"
export ACME_EMAIL=test@example.com METRICS_DOMAIN="http://localhost:$M_PORT" LOGS_DOMAIN="http://localhost:$L_PORT" PROMETHEUS_UPSTREAM="127.0.0.1:$PROM" LOKI_UPSTREAM="127.0.0.1:$LOKI" GRAFANA_DOMAIN=grafana.invalide
"$BIN/caddy" validate --config "$S/caddy/Caddyfile" --adapter caddyfile >"$T/validate.log" 2>&1 \
    && ok "Caddy accepte le Caddyfile livré" || { ko "Caddyfile invalide"; cat "$T/validate.log"; }
cp "$S/caddy/sites/grafana-public.caddy.exemple" "$S/caddy/sites/grafana.caddy"
GRAFANA_DOMAIN=grafana.exemple.fr "$BIN/caddy" validate --config "$S/caddy/Caddyfile" --adapter caddyfile >"$T/validate2.log" 2>&1 \
    && ok "Caddy accepte aussi le site Grafana public (sites/grafana-public.caddy.exemple)" || { ko "site Grafana public invalide"; cat "$T/validate2.log"; }
rm -f "$S/caddy/sites/grafana.caddy"

# ---------------------------------------------------------------------------------------------------------
etape "2. Démarrage de Prometheus, Loki et Caddy"
mkdir -p "$T/prom-data" "$T/rules" "$T/loki"
cp "$SUP/serveur/prometheus/rules/"*.yml "$T/rules/"
sed -e "s#/etc/prometheus/rules#$T/rules#" -e 's#alertmanager:9093#127.0.0.1:19093#' "$SUP/serveur/prometheus/prometheus.yml" > "$T/prometheus.yml"
"$BIN/prometheus" --config.file="$T/prometheus.yml" --web.listen-address=127.0.0.1:$PROM --web.enable-remote-write-receiver --web.enable-admin-api --storage.tsdb.path="$T/prom-data" >"$T/prom.log" 2>&1 &
PIDS+=($!)
sed -e "s#/loki#$T/loki#g" -e "s#http_listen_port: 3100#http_listen_port: $LOKI\n  grpc_listen_port: 19095#" "$SUP/serveur/loki/loki.yml" > "$T/loki.yml"
"$BIN/loki" -config.file="$T/loki.yml" >"$T/loki.log" 2>&1 &
PIDS+=($!)
"$BIN/caddy" run --config "$S/caddy/Caddyfile" --adapter caddyfile >"$T/caddy.log" 2>&1 &
PIDS+=($!)
for i in $(seq 1 40); do curl -fsS "http://127.0.0.1:$PROM/-/ready" >/dev/null 2>&1 && break; sleep 1; done
for i in $(seq 1 60); do curl -fsS "http://127.0.0.1:$LOKI/ready" 2>/dev/null | grep -q ready && break; sleep 1; done
curl -fsS "http://127.0.0.1:$PROM/-/ready" >/dev/null 2>&1 && ok "Prometheus prêt" || { ko "Prometheus"; tail -5 "$T/prom.log"; }
curl -fsS "http://127.0.0.1:$LOKI/ready" 2>/dev/null | grep -q ready && ok "Loki prêt" || { ko "Loki"; tail -5 "$T/loki.log"; }
attendre_code 200 "Caddy répond (sonde /healthz, mesures)" "http://localhost:$M_PORT/healthz"
attendre_code 200 "Caddy répond (sonde /healthz, journaux)" "http://localhost:$L_PORT/healthz"

# ---------------------------------------------------------------------------------------------------------
etape "3. Filtrage : seuls les deux chemins d'envoi traversent, avec identifiant"
M="http://localhost:$M_PORT"; L="http://localhost:$L_PORT"
CA="alpha:$PW_ALPHA"; CB="bravo:$PW_BRAVO"
c=$(code -X POST "$M/api/v1/write" --data-binary ''); [ "$c" = "401" ] && ok "envoi sans identifiant → 401" || ko "sans identifiant : $c"
c=$(code -u "alpha:mauvais" -X POST "$M/api/v1/write" --data-binary ''); [ "$c" = "401" ] && ok "envoi avec un mauvais mot de passe → 401" || ko "mauvais mot de passe : $c"
c=$(code -u "inconnu:$PW_ALPHA" -X POST "$M/api/v1/write" --data-binary ''); [ "$c" = "401" ] && ok "envoi avec un identifiant inconnu → 401" || ko "identifiant inconnu : $c"
c=$(code -u "_modele_inutilisable:password" -X POST "$M/api/v1/write" --data-binary ''); [ "$c" = "401" ] && ok "le compte modèle est inutilisable → 401" || ko "compte modèle : $c"
c=$(code -u "$CA" -X POST "$M/api/v1/write" -H 'Content-Type: application/x-protobuf' --data-binary ''); case "$c" in 200|204|400|415) ok "envoi avec le bon identifiant → atteint Prometheus ($c)";; *) ko "bon identifiant : $c";; esac
for chemin in "/api/v1/query?query=up" "/api/v1/query_range?query=up&start=0&end=1&step=1" "/api/v1/series" "/api/v1/label/__name__/values" "/api/v1/admin/tsdb/snapshot" "/graph" "/metrics" "/config" "/-/reload" "/"; do
    c=$(code -u "$CA" "$M$chemin"); [ "$c" = "404" ] || ko "lecture exposée : $chemin → $c"
done; ok "lecture, administration et interface de Prometheus : tout répond 404, même avec un bon identifiant"
c=$(code -u "$CA" -X POST "$M/api/v1/admin/tsdb/snapshot"); [ "$c" = "404" ] && ok "l'API d'administration (sauvegardes) n'est pas joignable de l'extérieur → 404" || ko "API d'administration : $c"
c=$(code -u "$CA" -X GET "$M/api/v1/write"); [ "$c" = "404" ] && ok "GET sur le chemin d'envoi → 404 (seul POST passe)" || ko "GET write : $c"
c=$(code -u "$CA" -X POST "$M/loki/api/v1/push" --data-binary ''); [ "$c" = "404" ] && ok "chemin des journaux sur le nom des mesures → 404" || ko "mélange des chemins : $c"
head -c 9000000 /dev/zero > "$T/gros.bin"
c=$(code -u "$CA" -X POST "$M/api/v1/write" -H 'Content-Type: application/x-protobuf' --data-binary @"$T/gros.bin"); [ "$c" = "413" ] && ok "requête de 9 Mo refusée (limite 8 Mo) → 413" || ko "limite de taille : $c"

NS=$(date +%s)000000000
CORPS='{"streams":[{"stream":{"job":"test-passerelle","enseigne":"alpha"},"values":[["'$NS'","ligne de test envoyée par une caisse"]]}]}'
c=$(code -X POST "$L/loki/api/v1/push" -H 'Content-Type: application/json' -d "$CORPS"); [ "$c" = "401" ] && ok "journaux sans identifiant → 401" || ko "journaux sans identifiant : $c"
c=$(code -u "$CA" -X POST "$L/loki/api/v1/push" -H 'Content-Type: application/json' -d "$CORPS"); [ "$c" = "204" ] && ok "journaux avec le bon identifiant → 204" || ko "journaux : $c"
c=$(code -u "$CA" "$L/loki/api/v1/query?query=%7Bjob%3D%22test-passerelle%22%7D"); [ "$c" = "404" ] && ok "lecture des journaux depuis l'extérieur → 404" || ko "lecture Loki exposée : $c"
for i in $(seq 1 15); do
    n=$(curl -fsS --get "http://127.0.0.1:$LOKI/loki/api/v1/query_range" --data-urlencode 'query={job="test-passerelle"}' --data-urlencode "start=$(( $(date +%s) - 300 ))000000000" 2>/dev/null | jq -r '[.data.result[].values[]] | length' 2>/dev/null)
    [ "${n:-0}" -ge 1 ] 2>/dev/null && break; sleep 1
done
[ "${n:-0}" -ge 1 ] 2>/dev/null && ok "la ligne envoyée est bien arrivée dans Loki" || ko "la ligne n'est pas arrivée dans Loki"

# ---------------------------------------------------------------------------------------------------------
etape "4. De vrais agents Alloy envoient à travers Caddy, chacun avec l'identifiant de son enseigne"
mkdir -p "$T/metrics" "$T/alloy-conf"
cat > "$T/metrics/caisse.prom" <<'EOF'
# HELP caisse_tickets_pending Tickets en attente.
# TYPE caisse_tickets_pending gauge
caisse_tickets_pending 3
EOF
sed "s#/var/lib/storepos-kiosk/metrics#$T/metrics#" "$SUP/agent/pkg/etc/storepos-kiosk/alloy/10-metriques.alloy" > "$T/alloy-conf/10-metriques.alloy"
agent() {  # agent <enseigne> <utilisateur> <mot de passe> <port>
    mkdir -p "$T/alloy-$1-data"
    METRICS_URL="$M/api/v1/write" METRICS_USER="$2" METRICS_PASSWORD="$3" ENSEIGNE="$1" MAGASIN="m-$1" POSTE=1 MACHINE_ID="id-$1" \
        "$BIN/alloy" run --disable-reporting --server.http.listen-addr=127.0.0.1:$4 --storage.path="$T/alloy-$1-data" "$T/alloy-conf" >"$T/alloy-$1.log" 2>&1 &
    PIDS+=($!)
}
agent alpha alpha "$PW_ALPHA" 12391
agent bravo bravo "$PW_BRAVO" 12392
agent intrus alpha "mauvais-mot-de-passe" 12393
echo "  attente des mesures (jusqu'à 120 s)…"
q() { curl -fsS --get "http://127.0.0.1:$PROM/api/v1/query" --data-urlencode "query=$1" 2>/dev/null | jq -r '.data.result[0].value[1] // "0"'; }
for i in $(seq 1 60); do
    [ "$(q 'count(caisse_tickets_pending{enseigne="alpha"})')" != "0" ] && [ "$(q 'count(caisse_tickets_pending{enseigne="bravo"})')" != "0" ] && break; sleep 2
done
[ "$(q 'count(caisse_tickets_pending{enseigne="alpha"})')" = "1" ] && ok "les mesures de l'enseigne alpha arrivent dans Prometheus" || ko "alpha absent"
[ "$(q 'count(caisse_tickets_pending{enseigne="bravo"})')" = "1" ] && ok "les mesures de l'enseigne bravo arrivent dans Prometheus" || ko "bravo absent"
[ "$(q 'count(caisse_tickets_pending{enseigne="intrus"})')" = "0" ] && ok "l'agent au mauvais mot de passe n'a RIEN fait passer" || ko "l'intrus a envoyé des mesures"
grep -qi "401\|unauthorized" "$T/alloy-intrus.log" && ok "l'agent refusé le constate dans ses journaux (401)" || echo "  info  (pas encore de 401 dans le journal de l'agent refusé)"

# ---------------------------------------------------------------------------------------------------------
etape "5. Journal d'accès : l'enseigne est identifiée, le mot de passe n'apparaît jamais"
grep -q '"user_id":"alpha"' "$T/caddy.log" && ok "le journal d'accès de Caddy indique l'enseigne (user_id=alpha)" || ko "enseigne absente du journal d'accès"
grep -q "$PW_ALPHA" "$T/caddy.log" && ko "le MOT DE PASSE apparaît dans le journal !" || ok "le mot de passe n'apparaît pas dans le journal"
grep -qi '"Authorization"' "$T/caddy.log" && grep -q 'REDACTED' "$T/caddy.log" && ok "l'en-tête Authorization est masqué dans le journal" || echo "  info  (l'en-tête Authorization n'est pas journalisé)"

# ---------------------------------------------------------------------------------------------------------
etape "6. Ajout à chaud, puis révocation, sans toucher aux autres enseignes"
export CADDY_RELOAD_CMD="$BIN/caddy reload --config $S/caddy/Caddyfile --adapter caddyfile --address localhost:2019"
PW_CHARLIE=$(creer charlie)
[ "${#PW_CHARLIE}" = "40" ] && ok "enseigne charlie ajoutée pendant que Caddy tourne (rechargement sans coupure)" || ko "ajout à chaud"
c=$(code -u "charlie:$PW_CHARLIE" -X POST "$M/api/v1/write" -H 'Content-Type: application/x-protobuf' --data-binary ''); case "$c" in 200|204|400|415) ok "charlie est immédiatement acceptée ($c)";; *) ko "charlie : $c";; esac
bash "$S/scripts/enseigne-revoquer.sh" alpha >/dev/null 2>&1 && ok "alpha révoquée" || ko "révocation"
c=$(code -u "$CA" -X POST "$M/api/v1/write" -H 'Content-Type: application/x-protobuf' --data-binary ''); [ "$c" = "401" ] && ok "alpha est désormais refusée → 401" || ko "alpha encore acceptée : $c"
c=$(code -u "$CB" -X POST "$M/api/v1/write" -H 'Content-Type: application/x-protobuf' --data-binary ''); case "$c" in 200|204|400|415) ok "bravo n'est pas affectée ($c)";; *) ko "bravo touchée : $c";; esac
c=$(code -u "charlie:$PW_CHARLIE" -X POST "$M/api/v1/write" -H 'Content-Type: application/x-protobuf' --data-binary ''); case "$c" in 200|204|400|415) ok "charlie n'est pas affectée ($c)";; *) ko "charlie touchée : $c";; esac
bash "$S/scripts/enseigne-revoquer.sh" _modele_inutilisable >/dev/null 2>&1 && ko "le compte modèle a pu être supprimé" || ok "le compte modèle ne peut pas être supprimé (Caddy ne démarrerait plus)"
# un rechargement qui échoue doit annuler l'opération et restaurer le fichier
cp "$S/caddy/enseignes.caddy" "$T/avant"
CADDY_RELOAD_CMD="false" bash "$S/scripts/enseigne-ajouter.sh" delta >/dev/null 2>&1 && ko "ajout accepté malgré l'échec du rechargement" || ok "rechargement en échec : l'ajout est annulé"
cmp -s "$S/caddy/enseignes.caddy" "$T/avant" && ok "le fichier des identifiants est restauré à l'identique" || ko "fichier altéré après échec"

echo; [ "$FAIL" -eq 0 ] && echo "PASSERELLE : TOUT EST OK" || { echo "PASSERELLE : DES ECHECS"; echo "--- caddy.log (fin) ---"; tail -5 "$T/caddy.log"; exit 1; }
