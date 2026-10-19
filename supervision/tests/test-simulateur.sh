#!/bin/bash
# Teste demo/simuler-parc.sh avec le VRAI Alloy et un VRAI Prometheus (réception directe, sans Caddy) :
# les caisses simulées arrivent avec leurs étiquettes, les incidents changent les valeurs, le silence rend une caisse muette.
set -u
SUP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="${SUPERVISION_TOOLS:-$HOME/supervision-test/bin}"
T=$(mktemp -d); PIDS=()
cleanup() { SIM_DIR="$T/sim" bash "$SUP/demo/simuler-parc.sh" arreter >/dev/null 2>&1; for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done; sleep 1; pkill -9 -f "$T" 2>/dev/null; rm -rf "$T"; }
trap cleanup EXIT
FAIL=0; ok() { echo "  OK    $*"; }; ko() { echo "  ECHEC $*"; FAIL=1; }
P=19190
if (exec 3<>/dev/tcp/127.0.0.1/$P) 2>/dev/null; then echo "port $P occupé" >&2; exit 2; fi
"$BIN/prometheus" --config.file=/dev/null --web.listen-address=127.0.0.1:$P --web.enable-remote-write-receiver --storage.tsdb.path="$T/prom" >"$T/prom.log" 2>&1 &
PIDS+=($!)
for i in $(seq 1 30); do curl -fsS "http://127.0.0.1:$P/-/ready" >/dev/null 2>&1 && break; sleep 1; done
cat > "$T/agent.conf" <<EOF
METRICS_URL=http://127.0.0.1:$P/api/v1/write
METRICS_USER=demo
METRICS_PASSWORD=x
LOGS_URL=
LOGS_USER=
LOGS_PASSWORD=
CF_ACCESS_ID=
CF_ACCESS_SECRET=
ENSEIGNE=demo
MAGASIN=LYON
POSTE=1
MACHINE_ID=reelle
EOF
export SIM_DIR="$T/sim" STOREPOS_MONITORING_CONF="$T/agent.conf" ALLOY_BIN="$BIN/alloy" SIM_ALLOY_CONF="$SUP/agent/pkg/etc/storepos-kiosk/alloy/10-metriques.alloy"
S() { bash "$SUP/demo/simuler-parc.sh" "$@"; }
q() { curl -fsS --get "http://127.0.0.1:$P/api/v1/query" --data-urlencode "query=$1" 2>/dev/null | jq -r '.data.result[0].value[1] // "0"'; }

S demarrer 3 | tail -1
S etat | grep -c 'agent:actif' | grep -q '^3$' && ok "3 agents Alloy actifs" || ko "agents non actifs : $(S etat)"
echo "  attente des mesures (jusqu'à 150 s)…"
for i in $(seq 1 75); do [ "$(q 'count(caisse_tickets_today{enseigne="demo"})')" = "3" ] && break; sleep 2; done
[ "$(q 'count(caisse_tickets_today{enseigne="demo"})')" = "3" ] && ok "3 caisses simulées visibles dans Prometheus" || ko "caisses absentes ($(q 'count(caisse_tickets_today)'))"
[ "$(q 'count(caisse_tickets_today{magasin=~"SIM-.*"})')" = "3" ] && ok "toutes préfixées SIM-" || ko "préfixe SIM- absent"
[ "$(q 'count(count by (machine) (up{job="caisse"}))')" = "3" ] && ok "3 machines distinctes (up{job=\"caisse\"}, base du tableau de bord)" || ko "machines : $(q 'count(count by (machine) (up{job="caisse"}))')"
[ "$(q 'count(caisse_app_info{version="1.3.0"})')" = "3" ] && ok "version de l'application renseignée" || ko "caisse_app_info"
[ "$(q 'sum(caisse_tickets_pending)')" = "0" ] && ok "état normal : aucun ticket en attente" || ko "tickets en attente au départ"

attendre() {  # attendre <requete> <test shell sur $v> : jusqu'a 150 s (l'agent envoie toutes les 60 s)
    for i in $(seq 1 75); do v=$(q "$1"); eval "$2" && return 0; sleep 2; done; return 1
}
S incident 2 attente >/dev/null
attendre 'caisse_tickets_pending{magasin="SIM-PARIS-02"}' '[ "${v%.*}" -ge 25 ]' && ok "incident « attente » : $v tickets en attente sur SIM-PARIS-02" || ko "incident attente : '$v'"
S incident 3 horsligne >/dev/null
attendre 'caisse_sync_state{magasin="SIM-NANTES-01"}' '[ "$v" = "0" ]' && ok "incident « horsligne » : synchronisation à 0 sur SIM-NANTES-01" || ko "incident horsligne : '$v'"
S incident 2 normal >/dev/null; S incident 3 normal >/dev/null
attendre 'caisse_tickets_pending{magasin="SIM-PARIS-02"}' '[ "$v" = "0" ]' && ok "retour à la normale" || ko "retour à la normale : '$v'"

S silence 1 >/dev/null; S etat | grep -q 'caisse 1  .*arrêté' && ok "silence : l'agent de la caisse 1 est arrêté" || ko "silence"
S reveil 1 >/dev/null; sleep 1; S etat | grep -q 'caisse 1  .*actif' && ok "réveil : agent relancé" || ko "réveil"
S arreter >/dev/null; [ ! -d "$T/sim" ] && ok "arrêt : processus stoppés et fichiers effacés" || ko "dossier de simulation encore là"
pgrep -f "$T/sim" >/dev/null && ko "processus Alloy encore actifs" || ok "plus aucun processus de simulation"
S demarrer 99 >/dev/null 2>&1 && ko "N=99 accepté" || ok "N hors limites refusé"
echo; [ "$FAIL" = 0 ] && echo "SIMULATEUR : TOUT EST OK" || { echo "SIMULATEUR : DES ECHECS"; exit 1; }
