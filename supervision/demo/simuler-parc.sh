#!/bin/bash
#
# SIMULATEUR DE PARC POUR LA DEMO : fait apparaître plusieurs magasins fictifs dans Grafana à partir d'UNE machine.
# Chaque caisse simulée est un vrai agent Grafana Alloy (la configuration du paquet de supervision) qui envoie, à travers la
# vraie passerelle, des mesures de caisse FICTIVES. Les magasins sont préfixés « SIM- » : ne jamais les présenter comme réels.
#
#   sudo bash simuler-parc.sh demarrer [N]     lance N caisses simulées (défaut 6, maximum 12)
#   sudo bash simuler-parc.sh etat             liste les caisses simulées
#   sudo bash simuler-parc.sh incident N TYPE  TYPE = attente (tickets bloqués) | horsligne (caisse hors ligne) | normal
#   sudo bash simuler-parc.sh silence N        coupe l'agent de la caisse N (elle devient « muette »)
#   sudo bash simuler-parc.sh reveil N         relance l'agent de la caisse N
#   sudo bash simuler-parc.sh arreter          arrête tout et efface les fichiers de simulation
#
# Prérequis : l'agent de supervision est installé ET inscrit sur cette machine (storepos-kiosk-monitoring-enroll) ;
# les identifiants d'envoi sont lus dans /etc/default/storepos-kiosk-monitoring.
# Limites : les mesures du SYSTÈME (processeur, mémoire, disque) des caisses simulées sont celles de cette machine, car chaque
# agent mesure son hôte. Pour montrer le détail d'une caisse, choisir la vraie. Les journaux ne sont pas simulés.
set -u
RACINE="${SIM_DIR:-/var/lib/storepos-kiosk/simulation}"
CONF="${STOREPOS_MONITORING_CONF:-/etc/default/storepos-kiosk-monitoring}"
ALLOY="${ALLOY_BIN:-/usr/bin/alloy}"
CONF_ALLOY="${SIM_ALLOY_CONF:-/etc/storepos-kiosk/alloy/10-metriques.alloy}"
MAGASINS=(PARIS-01 PARIS-02 NANTES-01 LILLE-01 BORDEAUX-01 TOULOUSE-01 MARSEILLE-01 STRASBOURG-01 RENNES-01 NICE-01 LYON-02 DIJON-01)
PORT_BASE=12400

[ "$(id -u)" = 0 ] || [ -n "${SIM_DIR:-}" ] || { echo "Exécuter avec sudo." >&2; exit 1; }
cmd="${1:-}"

nom_pid() { echo "$RACINE/sim-$1/alloy.pid"; }
vivant() { [ -f "$1" ] && kill -0 "$(cat "$1")" 2>/dev/null; }

ecrire_mesures() {  # ecrire_mesures <n> : un fichier caisse.prom plausible, qui évolue avec l'heure
    local n="$1" d="$RACINE/sim-$1" etat maintenant debut tickets_jour ventes attente sync derniere
    etat=$(cat "$d/incident" 2>/dev/null || echo normal)
    maintenant=$(date +%s); debut=$(cat "$d/debut")
    tickets_jour=$(( (maintenant - debut) / 40 + n * 7 ))                 # ~1 ticket toutes les 40 s
    ventes=$(( tickets_jour * (1800 + (n * 313) % 1500) ))                # panier moyen 18 à 33 € (en centimes)
    attente=0; sync=1; derniere=$maintenant
    case "$etat" in
        attente)   attente=$(( 25 + (maintenant - $(cat "$d/incident_depuis")) / 20 )); sync=0; derniere=$(cat "$d/incident_depuis") ;;
        horsligne) sync=0; derniere=$(cat "$d/incident_depuis"); attente=$(( (maintenant - derniere) / 60 )) ;;
    esac
    local f="$d/metrics/caisse.prom"
    {
        echo "# TYPE caisse_app_info gauge"
        echo "caisse_app_info{version=\"1.3.0\",device_id=\"sim-$n\",saas_magasin=\"SIM-${MAGASINS[$((n-1))]}\",saas_tpv=\"1\"} 1"
        echo "caisse_app_last_write_timestamp_seconds $maintenant"
        echo "caisse_app_start_time_seconds $debut"
        echo "caisse_tickets_pending $attente"
        echo "caisse_tickets_today $tickets_jour"
        echo "caisse_sales_today_cents $ventes"
        echo "caisse_catalog_products 1240"
        echo "caisse_catalog_customers $((300 + n * 41))"
        echo "caisse_sync_state $sync"
        echo "caisse_last_sync_timestamp_seconds $derniere"
        echo "caisse_db_size_bytes $((4000000 + tickets_jour * 900))"
        echo "caisse_cashier_session 1"
    } > "$f.tmp" && mv -f "$f.tmp" "$f"
}

lancer_alloy() {
    local n="$1" d="$RACINE/sim-$1"
    vivant "$(nom_pid "$n")" && return 0
    # shellcheck disable=SC1090
    ( . "$CONF"
      export METRICS_URL METRICS_USER METRICS_PASSWORD CF_ACCESS_ID CF_ACCESS_SECRET
      ENSEIGNE="${ENSEIGNE:-demo}" MAGASIN="SIM-${MAGASINS[$((n-1))]}" POSTE=1 MACHINE_ID="sim-$(printf '%02d' "$n")" \
      LOGS_URL="${LOGS_URL:-}" LOGS_USER="${LOGS_USER:-}" LOGS_PASSWORD="${LOGS_PASSWORD:-}"
      export ENSEIGNE MAGASIN POSTE MACHINE_ID LOGS_URL LOGS_USER LOGS_PASSWORD
      nohup "$ALLOY" run --disable-reporting --server.http.listen-addr="127.0.0.1:$((PORT_BASE + n))" \
          --storage.path="$d/data" "$d/alloy" >"$d/alloy.log" 2>&1 &
      echo $! > "$(nom_pid "$n")" )
}

case "$cmd" in
demarrer)
    N="${2:-6}"; [[ "$N" =~ ^[0-9]+$ ]] && [ "$N" -ge 1 ] && [ "$N" -le 12 ] || { echo "N doit être entre 1 et 12." >&2; exit 1; }
    [ -f "$CONF" ] || { echo "Agent non inscrit : $CONF absent. Lancer d'abord storepos-kiosk-monitoring-enroll." >&2; exit 1; }
    [ -x "$ALLOY" ] || { echo "Alloy introuvable : $ALLOY" >&2; exit 1; }
    # shellcheck disable=SC1090
    ( . "$CONF"; [ -n "${METRICS_USER:-}" ] && [ -n "${METRICS_PASSWORD:-}" ] ) || { echo "Identifiants absents de $CONF : inscrire d'abord la caisse." >&2; exit 1; }
    mkdir -p "$RACINE"
    for n in $(seq 1 "$N"); do
        d="$RACINE/sim-$n"; mkdir -p "$d/metrics" "$d/alloy" "$d/data"
        [ -f "$d/debut" ] || date +%s > "$d/debut"
        # même configuration que le paquet, avec un dossier de mesures par caisse simulée
        sed "s#/var/lib/storepos-kiosk/metrics#$d/metrics#" "$CONF_ALLOY" > "$d/alloy/10-metriques.alloy"
        ecrire_mesures "$n"
        lancer_alloy "$n"
    done
    # générateur : réécrit les mesures toutes les 30 s (sinon l'alerte « application figée » se déclencherait)
    if ! vivant "$RACINE/generateur.pid"; then
        ( while :; do for d in "$RACINE"/sim-*; do [ -d "$d" ] && "$0" _ecrire "${d##*sim-}"; done; sleep 30; done ) >/dev/null 2>&1 &
        echo $! > "$RACINE/generateur.pid"
    fi
    echo "$N caisses simulées démarrées (magasins SIM-…). Elles apparaissent dans Grafana d'ici 1 à 2 minutes."
    ;;
_ecrire) ecrire_mesures "$2" ;;
etat)
    [ -d "$RACINE" ] || { echo "Aucune simulation."; exit 0; }
    for d in "$RACINE"/sim-*; do
        [ -d "$d" ] || continue; n="${d##*sim-}"
        printf 'caisse %-2s SIM-%-14s agent:%-8s incident:%s\n' "$n" "${MAGASINS[$((n-1))]}" "$(vivant "$(nom_pid "$n")" && echo actif || echo arrêté)" "$(cat "$d/incident" 2>/dev/null || echo normal)"
    done ;;
incident)
    n="${2:-}"; type="${3:-}"; d="$RACINE/sim-$n"
    [ -d "$d" ] || { echo "Caisse simulée $n inconnue." >&2; exit 1; }
    case "$type" in attente|horsligne|normal) ;; *) echo "TYPE : attente, horsligne ou normal." >&2; exit 1 ;; esac
    echo "$type" > "$d/incident"; date +%s > "$d/incident_depuis"; ecrire_mesures "$n"
    echo "Caisse $n (SIM-${MAGASINS[$((n-1))]}) : incident « $type »." ;;
silence)
    n="${2:-}"; vivant "$(nom_pid "$n")" && kill "$(cat "$(nom_pid "$n")")" && echo "Agent de la caisse $n arrêté : elle devient muette (alerte après 20 min en heures d'ouverture)." || echo "Agent déjà arrêté ou inconnu." ;;
reveil) lancer_alloy "${2:-}"; echo "Agent de la caisse ${2:-} relancé." ;;
arreter)
    for p in "$RACINE"/sim-*/alloy.pid "$RACINE/generateur.pid"; do [ -f "$p" ] && kill "$(cat "$p")" 2>/dev/null; done
    sleep 1; rm -rf "$RACINE"; echo "Simulation arrêtée et effacée. Les séries restent visibles dans Grafana jusqu'à ce qu'elles sortent de la plage de temps." ;;
*) sed -n '2,17p' "$0"; exit 1 ;;
esac
