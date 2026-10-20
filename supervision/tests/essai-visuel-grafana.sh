#!/bin/bash
#
# Monte un Grafana + Prometheus LOCAUX avec des caisses simulées de plusieurs enseignes, pour VOIR le tableau de bord « Santé du parc ».
#   bash supervision/tests/essai-visuel-grafana.sh        (reste actif ; Ctrl-C pour arrêter)  puis ouvrir http://localhost:13300
# Grafana est lancé SANS connexion (accès anonyme administrateur) : uniquement pour cet essai local.
set -u
SUP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="${SUPERVISION_TOOLS:-$HOME/supervision-test/bin}"; G="$BIN/grafana"
T=$(mktemp -d); PIDS=()
cleanup() { SIM_DIR="$T/simA" bash "$SUP/demo/simuler-parc.sh" arreter >/dev/null 2>&1; SIM_DIR="$T/simB" bash "$SUP/demo/simuler-parc.sh" arreter >/dev/null 2>&1; for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done; sleep 1; pkill -9 -f "$T" 2>/dev/null; rm -rf "$T"; }
trap cleanup EXIT
P=19390; GP=13300
mkdir -p "$T/rules" "$T/prom"; cp "$SUP/serveur/prometheus/rules/"*.yml "$T/rules/"
sed -e "s#/etc/prometheus/rules#$T/rules#" -e 's#alertmanager:9093#127.0.0.1:19393#' "$SUP/serveur/prometheus/prometheus.yml" > "$T/prometheus.yml"
"$BIN/prometheus" --config.file="$T/prometheus.yml" --web.listen-address=127.0.0.1:$P --web.enable-remote-write-receiver --storage.tsdb.path="$T/prom" >"$T/prom.log" 2>&1 & PIDS+=($!)
for i in $(seq 1 30); do curl -fsS "http://127.0.0.1:$P/-/ready" >/dev/null 2>&1 && break; sleep 1; done

# deux enseignes simulées (identifiants d'envoi factices : Prometheus local sans authentification)
for e in alpha:A:12400:5 bravo:B:12450:4; do IFS=: read ens id base n <<<"$e"
    cat > "$T/agent-$ens.conf" <<EOF
METRICS_URL=http://127.0.0.1:$P/api/v1/write
METRICS_USER=u
METRICS_PASSWORD=p
LOGS_URL=
LOGS_USER=
LOGS_PASSWORD=
CF_ACCESS_ID=
CF_ACCESS_SECRET=
ENSEIGNE=$ens
EOF
    SIM_DIR="$T/sim$id" SIM_PORT_BASE=$base STOREPOS_MONITORING_CONF="$T/agent-$ens.conf" ALLOY_BIN="$BIN/alloy" SIM_ALLOY_CONF="$SUP/agent/pkg/etc/storepos-kiosk/alloy/10-metriques.alloy" \
        bash "$SUP/demo/simuler-parc.sh" demarrer "$n" | tail -1
done
# un incident visible dans les tableaux
SIM_DIR="$T/simA" bash "$SUP/demo/simuler-parc.sh" incident 2 attente >/dev/null
SIM_DIR="$T/simB" bash "$SUP/demo/simuler-parc.sh" incident 1 horsligne >/dev/null

# Grafana provisionné avec les fichiers livrés
mkdir -p "$T/g/prov/datasources" "$T/g/prov/dashboards" "$T/g/dash" "$T/g/data" "$T/g/logs" "$T/g/plugins"
sed -e "s#http://prometheus:9090#http://127.0.0.1:$P#" -e 's#http://loki:3100#http://127.0.0.1:13199#' -e 's#http://alertmanager:9093#http://127.0.0.1:19393#' "$SUP/serveur/grafana/provisioning/datasources/datasources.yml" > "$T/g/prov/datasources/datasources.yml"
sed "s#/var/lib/grafana/dashboards#$T/g/dash#" "$SUP/serveur/grafana/provisioning/dashboards/dashboards.yml" > "$T/g/prov/dashboards/dashboards.yml"
cp "$SUP/serveur/grafana/dashboards/"*.json "$T/g/dash/"
( cd "$G" && GF_PATHS_DATA="$T/g/data" GF_PATHS_LOGS="$T/g/logs" GF_PATHS_PLUGINS="$T/g/plugins" GF_PATHS_PROVISIONING="$T/g/prov" \
    GF_SERVER_HTTP_ADDR=0.0.0.0 GF_SERVER_HTTP_PORT=$GP GF_AUTH_ANONYMOUS_ENABLED=true GF_AUTH_ANONYMOUS_ORG_ROLE=Admin GF_AUTH_DISABLE_LOGIN_FORM=true \
    GF_ANALYTICS_REPORTING_ENABLED=false GF_ANALYTICS_CHECK_FOR_UPDATES=false exec ./bin/grafana server --homepath "$G" >"$T/grafana.log" 2>&1 ) & PIDS+=($!)
for i in $(seq 1 60); do curl -fsS "http://127.0.0.1:$GP/api/health" >/dev/null 2>&1 && break; sleep 2; done
echo "PRET : http://localhost:$GP/d/parc-caisses (les données arrivent en 2 à 4 minutes)"
sleep "${DUREE:-1500}"
