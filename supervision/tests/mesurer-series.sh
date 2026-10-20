#!/bin/bash
#
# Mesure COMBIEN DE SÉRIES une caisse envoie réellement (vrai Alloy avec la configuration du paquet -> vrai Prometheus), pour dimensionner
# le serveur à 6000 caisses. Résultat : nombre de séries par caisse, répartition par métrique, extrapolation.
#   bash supervision/tests/mesurer-series.sh [dossier de la configuration Alloy]     (défaut : celle du paquet)
set -u
SUP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="${SUPERVISION_TOOLS:-$HOME/supervision-test/bin}"
CONF="${1:-$SUP/agent/pkg/etc/storepos-kiosk/alloy}"
T=$(mktemp -d); PIDS=()
cleanup() { for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done; sleep 1; pkill -9 -f "$T" 2>/dev/null; rm -rf "$T"; }
trap cleanup EXIT
P=19290
"$BIN/prometheus" --config.file=/dev/null --web.listen-address=127.0.0.1:$P --web.enable-remote-write-receiver --storage.tsdb.path="$T/prom" >"$T/prom.log" 2>&1 & PIDS+=($!)
for i in $(seq 1 30); do curl -fsS "http://127.0.0.1:$P/-/ready" >/dev/null 2>&1 && break; sleep 1; done

# fichiers « textfile » réalistes : ceux de l'application (voir Metriques.kt) et du script système
mkdir -p "$T/metrics" "$T/alloy"
NOW=$(date +%s)
cat > "$T/metrics/caisse.prom" <<EOF
caisse_app_info{version="1.3.3",device_id="d1",saas_magasin="LYON",saas_tpv="1"} 1
caisse_app_last_write_timestamp_seconds $NOW
caisse_app_start_time_seconds $NOW
caisse_tickets_pending 0
caisse_tickets_today 12
caisse_sales_today_cents 24500
caisse_catalog_products 1240
caisse_catalog_customers 340
caisse_sync_state 1
caisse_last_sync_timestamp_seconds $NOW
caisse_db_size_bytes 4000000
caisse_cashier_session 1
EOF
KM="$SUP/agent/pkg/usr/lib/storepos-kiosk/kiosk-metrics.sh"
[ -f "$KM" ] && METRICS_DIR="$T/metrics" bash "$KM" >/dev/null 2>&1
for f in "$CONF"/*.alloy; do case "$f" in *10-metriques.alloy) sed "s#/var/lib/storepos-kiosk/metrics#$T/metrics#" "$f" > "$T/alloy/$(basename "$f")";; esac; done
mkdir -p "$T/alloy-data"
METRICS_URL="http://127.0.0.1:$P/api/v1/write" METRICS_USER=u METRICS_PASSWORD=p ENSEIGNE=mes MAGASIN=M POSTE=1 MACHINE_ID=mesure \
    "$BIN/alloy" run --disable-reporting --server.http.listen-addr=127.0.0.1:12498 --storage.path="$T/alloy-data" "$T/alloy" >"$T/alloy.log" 2>&1 & PIDS+=($!)

q() { curl -fsS --get "http://127.0.0.1:$P/api/v1/query" --data-urlencode "query=$1" 2>/dev/null; }
echo "attente de deux collectes (jusqu'à 150 s)…"
for i in $(seq 1 75); do n=$(q 'count({machine="mesure"})' | jq -r '.data.result[0].value[1] // "0"'); [ "$n" -gt 5 ] 2>/dev/null && [ $i -gt 65 ] && break; sleep 2; done
N=$(q 'count({machine="mesure"})' | jq -r '.data.result[0].value[1] // "0"')
echo; echo "Séries envoyées par UNE caisse : $N"
echo; echo "Répartition (les 25 métriques qui pèsent le plus) :"
q 'topk(25, count by (__name__) ({machine="mesure"}))' | jq -r '.data.result | sort_by(-(.value[1]|tonumber))[] | "  \(.value[1])\t\(.metric.__name__)"'
echo; echo "Cœurs de processeur de cette machine : $(nproc)"
echo; echo "À 6000 caisses (même nombre de cœurs) : $(( N * 6000 )) séries actives"
