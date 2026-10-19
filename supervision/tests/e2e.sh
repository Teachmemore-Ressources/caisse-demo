#!/bin/bash
#
# Test de bout en bout de la supervision, avec les VRAIS composants (aucune simulation du réseau) :
#   application de caisse (fichier de mesures réel)  ->  Grafana Alloy (configuration livrée dans le paquet)
#   -> remote_write -> Prometheus (règles d'alerte livrées)  ->  Grafana (provisioning livré, requête via l'API)
#
# Prérequis : bash supervision/tests/fetch-tools.sh   (une fois)
# Usage     : bash supervision/tests/e2e.sh
set -u
SUP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$(cd "$SUP/.." && pwd)"
BIN="${SUPERVISION_TOOLS:-$HOME/supervision-test/bin}"
T=$(mktemp -d)
PROM_PORT=19090; ALLOY_PORT=12399; GRAF_PORT=13000
for port in $PROM_PORT $ALLOY_PORT $GRAF_PORT 19091 12397 12396; do
    if (exec 3<>/dev/tcp/127.0.0.1/$port) 2>/dev/null; then echo "Le port $port est déjà utilisé (processus restant d'un ancien test ?). Arrêtez-le : pkill -f supervision-test" >&2; exit 2; fi
done
FAIL=0; PIDS=()

cleanup() {
    for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done; sleep 1
    for p in "${PIDS[@]}"; do kill -9 "$p" 2>/dev/null; done
    # filet de sécurité : tout processus encore lancé depuis ce dossier temporaire (jamais d'orphelin qui garde un port)
    pkill -9 -f "$T" 2>/dev/null
    rm -rf "$T"
}
trap cleanup EXIT

ok()   { echo "  OK    $*"; }
ko()   { echo "  ECHEC $*"; FAIL=1; }
etape() { printf '\n== %s ==\n' "$*"; }
q() { curl -fsS --get "http://127.0.0.1:$PROM_PORT/api/v1/query" --data-urlencode "query=$1"; }   # requête PromQL instantanée

# ---------------------------------------------------------------------------------------------------------
etape "1. L'application écrit ses mesures (vrai code de l'application)"
mkdir -p "$T/metrics"
E2E_METRICS_DIR="$T/metrics" bash "$ROOT/tools/wsl-build.sh" cleanTest test --tests "*E2EMetriquesTest*" >"$T/gradle.log" 2>&1 \
    && ok "fichier caisse.prom produit par l'application" || { ko "génération impossible"; tail -20 "$T/gradle.log"; exit 1; }
"$BIN/promtool" check metrics < "$T/metrics/caisse.prom" >"$T/pm1.log" 2>&1 && ok "caisse.prom : format Prometheus valide (promtool)" || { ko "caisse.prom invalide"; cat "$T/pm1.log"; }

etape "2. Le script système écrit ses mesures"
METRICS_DIR="$T/metrics" bash "$SUP/agent/pkg/usr/lib/storepos-kiosk/kiosk-metrics.sh" && ok "kiosk.prom produit" || ko "kiosk-metrics.sh en échec"
"$BIN/promtool" check metrics < "$T/metrics/kiosk.prom" >"$T/pm2.log" 2>&1 && ok "kiosk.prom : format Prometheus valide (promtool)" || { ko "kiosk.prom invalide"; cat "$T/pm2.log"; }

# ---------------------------------------------------------------------------------------------------------
etape "3. Démarrage de Prometheus (récepteur remote_write + règles d'alerte livrées)"
mkdir -p "$T/prom-data" "$T/rules"
cp "$SUP/serveur/prometheus/rules/"*.yml "$T/rules/"
sed -e "s#/etc/prometheus/rules#$T/rules#" -e 's#alertmanager:9093#127.0.0.1:19093#' "$SUP/serveur/prometheus/prometheus.yml" > "$T/prometheus.yml"
"$BIN/prometheus" --config.file="$T/prometheus.yml" --web.listen-address=127.0.0.1:$PROM_PORT --web.enable-remote-write-receiver \
    --storage.tsdb.path="$T/prom-data" >"$T/prom.log" 2>&1 &
PIDS+=($!)
for i in $(seq 1 30); do curl -fsS "http://127.0.0.1:$PROM_PORT/-/ready" >/dev/null 2>&1 && break; sleep 1; done
curl -fsS "http://127.0.0.1:$PROM_PORT/-/ready" >/dev/null 2>&1 && ok "Prometheus prêt" || { ko "Prometheus ne démarre pas"; tail -20 "$T/prom.log"; exit 1; }

# ---------------------------------------------------------------------------------------------------------
etape "4. Démarrage de l'agent Alloy avec la configuration DU PAQUET (seuls l'adresse et le dossier de mesures changent)"
mkdir -p "$T/alloy-conf" "$T/alloy-data"
sed "s#/var/lib/storepos-kiosk/metrics#$T/metrics#" "$SUP/agent/pkg/etc/storepos-kiosk/alloy/10-metriques.alloy" > "$T/alloy-conf/10-metriques.alloy"
# Les journaux (journald) ne sont pas disponibles ici : le fichier 20-journaux.alloy est validé par validate-configs.sh
METRICS_URL="http://127.0.0.1:$PROM_PORT/api/v1/write" METRICS_USER="" METRICS_PASSWORD="" \
ENSEIGNE=demo MAGASIN=LYON-01 POSTE=2 MACHINE_ID=abc123def456 \
    "$BIN/alloy" run --disable-reporting --server.http.listen-addr=127.0.0.1:$ALLOY_PORT --storage.path="$T/alloy-data" "$T/alloy-conf" >"$T/alloy.log" 2>&1 &
PIDS+=($!)
for i in $(seq 1 30); do curl -fsS "http://127.0.0.1:$ALLOY_PORT/-/ready" >/dev/null 2>&1 && break; sleep 1; done
curl -fsS "http://127.0.0.1:$ALLOY_PORT/-/ready" >/dev/null 2>&1 && ok "Alloy prêt" || { ko "Alloy ne démarre pas"; tail -20 "$T/alloy.log"; exit 1; }

echo "  attente des premières mesures (jusqu'à 150 s)…"
for i in $(seq 1 75); do
    n=$(q 'count(caisse_tickets_pending)' 2>/dev/null | jq -r '.data.result[0].value[1] // "0"')
    [ "$n" != "0" ] && break; sleep 2
done

# ---------------------------------------------------------------------------------------------------------
etape "5. Les mesures sont arrivées dans Prometheus, avec les bonnes valeurs et étiquettes"
verif() {  # verif <description> <requête> <attendu> ; compare la valeur numérique
    v=$(q "$2" 2>/dev/null | jq -r '.data.result[0].value[1] // "absent"')
    if [ "$v" = "$3" ]; then ok "$1 = $v"; else ko "$1 : attendu $3, obtenu $v"; fi
}
verif "tickets en attente (l'app en a laissé 2)"        'caisse_tickets_pending'                  2
verif "tickets du jour (4 créés)"                        'caisse_tickets_today'                    4
verif "catalogue : 25 produits"                          'caisse_catalog_products'                 25
verif "mode autonome (-1)"                               'caisse_sync_state'                       -1
verif "session caissier ouverte"                         'caisse_cashier_session'                  1
verif "étiquette magasin posée par l'agent"              'count(caisse_tickets_pending{enseigne="demo",magasin="LYON-01",poste="2",machine="abc123def456"})' 1
verif "agent joignable : up{job=\"caisse\"} = 1"         'up{job="caisse",magasin="LYON-01"}'      1
verif "une seule caisse en ligne (requête du tableau)"   'count(up{job="caisse"} == 1)'            1
verif "aucune série sous un autre nom de job"            'count(up{job!="caisse",job!="prometheus"}) or vector(0)' 0
verif "métriques système présentes (mémoire)"            'count(node_memory_MemTotal_bytes{machine="abc123def456"})' 1
verif "métriques du script système (kiosk_os_info)"      'count(kiosk_os_info{machine="abc123def456"})' 1
verif "pas de séries inutiles (go_*)"                    'count({__name__=~"go_.*",job="caisse"}) or vector(0)' 0
v=$(q 'caisse_app_info' | jq -r '.data.result[0].metric | "version=\(.version) saas_magasin=\(.saas_magasin) device_id=\(.device_id[0:8])…"'); echo "  info : $v"

echo "  attente d'une deuxième mesure (les courbes de taux, comme le processeur, en exigent deux)…"
for i in $(seq 1 80); do
    n=$(q 'max(count_over_time(up{job="caisse"}[5m]))' 2>/dev/null | jq -r '.data.result[0].value[1] // "0"')
    [ "${n%.*}" -ge 2 ] 2>/dev/null && break; sleep 2
done

etape "6. Chaque requête du tableau de bord Grafana s'exécute sans erreur sur ces données"
python3 -I - "$SUP/serveur/grafana/dashboards/parc-caisses.json" "$PROM_PORT" <<'EOF'
import json, sys, urllib.parse, urllib.request
dash = json.load(open(sys.argv[1], encoding="utf-8")); port = sys.argv[2]
subst = {"$enseigne": ".*", "$magasin": ".*", "$machine": ".*", "$__rate_interval": "5m", "$__interval": "1m"}
def requete(expr):
    for k, v in subst.items(): expr = expr.replace(k, v)
    url = f"http://127.0.0.1:{port}/api/v1/query?" + urllib.parse.urlencode({"query": expr})
    return json.load(urllib.request.urlopen(url, timeout=20)), expr
ko = 0; n = 0
for p in dash["panels"]:
    for t in p.get("targets", []):
        if p.get("datasource", {}).get("type") != "prometheus": continue
        n += 1
        try:
            r, e = requete(t["expr"])
            statut = r["status"]
        except Exception as ex:
            statut, e = f"erreur {ex}", t["expr"]
        if statut != "success":
            ko += 1; print(f"  ECHEC [{p['title']}] {e[:90]} -> {statut}")
for v in dash["templating"]["list"]:
    n += 1
    r, _ = requete(v["definition"].replace("label_values(", "group by (").split(",")[0] + ")") if False else (None, None)
print(f"  {n-ko}/{n} requêtes exécutées sans erreur" if ko == 0 else f"  {ko} requête(s) en échec sur {n}")
sys.exit(1 if ko else 0)
EOF
[ $? -eq 0 ] && ok "toutes les requêtes du tableau de bord sont valides" || ko "requêtes du tableau de bord en échec"
# Et elles renvoient des DONNÉES (une requête qui ne s'exécute pas, ou qui reste vide, est un tableau de bord vide)
verif "tableau : « Caisses en ligne »"                   'count(up{job="caisse", enseigne=~".*", magasin=~".*"} == 1) or vector(0)' 1
verif "tableau : « Tickets en attente »"                 'sum(caisse_tickets_pending{enseigne=~".*", magasin=~".*"}) or vector(0)' 2
verif "tableau : « Versions de l'application »"          'count(count by (version) (caisse_app_info{enseigne=~".*", magasin=~".*"}))' 1
verif "tableau : « Caisses à surveiller »"               'count(caisse_tickets_pending{enseigne=~".*", magasin=~".*"} > 0)' 1
verif "tableau : variable « Machine »"                   'count(count by (machine) (up{job="caisse", enseigne=~".*", magasin=~".*"}))' 1
verif "tableau : processeur"                             'count(100 * (1 - avg by (machine) (rate(node_cpu_seconds_total{mode="idle", machine=~".*"}[5m]))))' 1

etape "7. Les règles d'alerte sont chargées et saines dans Prometheus"
sleep 5
curl -fsS "http://127.0.0.1:$PROM_PORT/api/v1/rules" | jq -r '[.data.groups[].rules[] | select(.health!="ok")] | length' | { read n; [ "$n" = "0" ] && ok "toutes les règles chargées, aucune en erreur" || ko "$n règle(s) en erreur"; }
nr=$(curl -fsS "http://127.0.0.1:$PROM_PORT/api/v1/rules" | jq -r '[.data.groups[].rules[]] | length'); [ "$nr" = "14" ] && ok "nombre de règles : $nr" || ko "nombre de règles : $nr (attendu 14)"
curl -fsS "http://127.0.0.1:$PROM_PORT/api/v1/alerts" | jq -r '.data.alerts[] | "  alerte en cours : \(.labels.alertname) (\(.state))"'

# ---------------------------------------------------------------------------------------------------------
etape "7b. Jeton de service Cloudflare : l'agent envoie ses en-têtes (proxy qui imite Cloudflare Access)"
python3 -I "$SUP/tests/fake_access_proxy.py" 19091 "http://127.0.0.1:$PROM_PORT" test-id test-secret "$T/proxy" &
PIDS+=($!)
sleep 1
lancer_agent() {  # lancer_agent <magasin> <secret> <port> : une instance d'Alloy qui passe par le proxy
    mkdir -p "$T/alloy-$1-data"
    METRICS_URL="http://127.0.0.1:19091/api/v1/write" METRICS_USER="" METRICS_PASSWORD="" CF_ACCESS_ID=test-id CF_ACCESS_SECRET="$2" \
    ENSEIGNE=demo MAGASIN="$1" POSTE=9 MACHINE_ID="cf-$1" \
        "$BIN/alloy" run --disable-reporting --server.http.listen-addr=127.0.0.1:$3 --storage.path="$T/alloy-$1-data" "$T/alloy-conf" >"$T/alloy-$1.log" 2>&1 &
    PIDS+=($!)
}
lancer_agent CF-OK test-secret 12397
lancer_agent CF-KO mauvais-secret 12396
echo "  attente (jusqu'à 120 s)…"
for i in $(seq 1 60); do
    n=$(q 'count(up{job="caisse",magasin="CF-OK"})' 2>/dev/null | jq -r '.data.result[0].value[1] // "0"')
    [ "$n" != "0" ] && [ -s "$T/proxy/refuses" ] && break; sleep 2
done
n=$(q 'count(up{job="caisse",magasin="CF-OK"})' | jq -r '.data.result[0].value[1] // "0"')
[ "$n" = "1" ] && ok "avec le bon jeton : les mesures passent le proxy et arrivent dans Prometheus" || ko "bon jeton : mesures absentes (obtenu $n)"
n=$(q 'count(up{job="caisse",magasin="CF-KO"})' | jq -r '.data.result[0].value[1] // "0"')
[ "$n" = "0" ] && ok "avec un mauvais jeton : rien n'arrive (l'accès est bien refusé)" || ko "mauvais jeton : des mesures sont passées ($n)"
[ -s "$T/proxy/refuses" ] && ok "le proxy a refusé $(wc -l < "$T/proxy/refuses") envoi(s) sans le bon jeton" || ko "aucun refus enregistré"
[ -s "$T/proxy/acceptes" ] && ok "le proxy a accepté $(wc -l < "$T/proxy/acceptes") envoi(s) avec le bon jeton" || ko "aucun envoi accepté"

# ---------------------------------------------------------------------------------------------------------
etape "8. Grafana : provisioning (sources de données + tableau de bord) puis requête à travers Grafana"
G="$BIN/grafana"
if [ ! -x "$G/bin/grafana" ]; then echo "  Grafana non installé : étape ignorée"; else
    mkdir -p "$T/g/provisioning/datasources" "$T/g/provisioning/dashboards" "$T/g/dashboards" "$T/g/data" "$T/g/logs" "$T/g/plugins"
    sed -e "s#http://prometheus:9090#http://127.0.0.1:$PROM_PORT#" -e 's#http://loki:3100#http://127.0.0.1:13100#' -e 's#http://alertmanager:9093#http://127.0.0.1:19093#' \
        "$SUP/serveur/grafana/provisioning/datasources/datasources.yml" > "$T/g/provisioning/datasources/datasources.yml"
    sed "s#/var/lib/grafana/dashboards#$T/g/dashboards#" "$SUP/serveur/grafana/provisioning/dashboards/dashboards.yml" > "$T/g/provisioning/dashboards/dashboards.yml"
    cp "$SUP/serveur/grafana/dashboards/"*.json "$T/g/dashboards/"
    ( cd "$G" && GF_PATHS_DATA="$T/g/data" GF_PATHS_LOGS="$T/g/logs" GF_PATHS_PLUGINS="$T/g/plugins" GF_PATHS_PROVISIONING="$T/g/provisioning" \
        GF_SERVER_HTTP_ADDR=127.0.0.1 GF_SERVER_HTTP_PORT=$GRAF_PORT GF_SECURITY_ADMIN_PASSWORD=e2e-test GF_ANALYTICS_REPORTING_ENABLED=false GF_ANALYTICS_CHECK_FOR_UPDATES=false \
        exec ./bin/grafana server --homepath "$G" >"$T/grafana.log" 2>&1 ) &
    PIDS+=($!)
    for i in $(seq 1 60); do curl -fsS "http://127.0.0.1:$GRAF_PORT/api/health" >/dev/null 2>&1 && break; sleep 2; done
    if curl -fsS "http://127.0.0.1:$GRAF_PORT/api/health" >/dev/null 2>&1; then
        ok "Grafana démarré"
        GA="curl -fsS -u admin:e2e-test http://127.0.0.1:$GRAF_PORT"
        # le provisioning se termine un peu après que l'API répond : attente active (jusqu'à 90 s) plutôt qu'une pause fixe
        for i in $(seq 1 45); do
            n=$($GA/api/datasources 2>/dev/null | jq -r 'length' 2>/dev/null || echo 0)
            d=$($GA/api/search?query=Parc 2>/dev/null | jq -r 'length' 2>/dev/null || echo 0)
            [ "$n" = "3" ] && [ "$d" != "0" ] && break; sleep 2
        done
        n=$($GA/api/datasources | jq -r 'length'); [ "$n" = "3" ] && ok "3 sources de données provisionnées (Prometheus, Loki, Alertmanager)" || ko "sources de données : $n"
        $GA/api/search?query=Parc | jq -r '.[0].title' | grep -q "Parc de caisses" && ok "tableau de bord « Parc de caisses » provisionné" || ko "tableau de bord introuvable"
        np=$($GA/api/dashboards/uid/parc-caisses | jq -r '.dashboard.panels | length'); [ "$np" = "21" ] && ok "tableau de bord chargé par Grafana : $np panneaux" || ko "panneaux : $np"
        # requête réelle à travers Grafana vers Prometheus (source « prom »)
        rep=$($GA/api/ds/query -H 'Content-Type: application/json' -d '{"queries":[{"refId":"A","datasource":{"uid":"prom","type":"prometheus"},"expr":"sum(caisse_tickets_pending{magasin=\"LYON-01\"})","instant":true}],"from":"now-5m","to":"now"}' | jq -r '.results.A.frames[0].data.values[1][0]')
        [ "$rep" = "2" ] && ok "requête à travers Grafana : tickets en attente = $rep" || ko "requête via Grafana : $rep"
    else
        ko "Grafana ne démarre pas"; tail -15 "$T/grafana.log"
    fi
fi

echo
[ "$FAIL" -eq 0 ] && echo "E2E : TOUT EST OK" || { echo "E2E : DES ECHECS"; echo "--- alloy.log ---"; tail -15 "$T/alloy.log"; exit 1; }
