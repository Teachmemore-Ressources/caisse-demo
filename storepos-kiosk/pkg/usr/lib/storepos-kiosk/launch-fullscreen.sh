#!/bin/bash
#
# launch-fullscreen.sh
# Lance l'application de caisse StorePOS et, en complément de la règle Openbox (rc.xml),
# force le plein écran dès que sa fenêtre apparaît. Appelé par storepos.service.
# Réglages : /etc/default/storepos-kiosk

set -u

APP_BIN="${APP_BIN:-}"
if [ -z "$APP_BIN" ]; then
    for c in /opt/com.aemsofts.storepos/bin/com.aemsofts.storepos /opt/caisse-demo/bin/caisse-demo; do
        [ -x "$c" ] && APP_BIN="$c" && break
    done
fi
WINDOW_NAME_PATTERN="${WINDOW_NAME_PATTERN:-storepos|aemsoft|caisse}"
MAX_WAIT_SECONDS="${MAX_WAIT_SECONDS:-30}"
LOG_FILE="${LOG_FILE:-/var/log/storepos-kiosk.log}"

# Journal : stdout (donc journalctl) + fichier
log() {
    local msg
    msg="$(date '+%Y-%m-%d %H:%M:%S') - $*"
    echo "$msg"
    echo "$msg" >> "$LOG_FILE" 2>/dev/null || true
}

# Détection de l'écran X de la session caissepos : lightdm n'utilise pas toujours :0
# (:1, :2... si les numéros précédents sont déjà pris). Seul l'écran dont le cookie
# (~/.Xauthority) est celui de caissepos répond à xset.
detect_display() {
    local sock d
    for sock in /tmp/.X11-unix/X*; do
        [ -S "$sock" ] || continue
        d=":${sock##*/X}"
        if DISPLAY="$d" xset q >/dev/null 2>&1; then
            echo "$d"
            return 0
        fi
    done
    return 1
}

DISPLAY_FOUND=""
for _ in $(seq 1 120); do
    DISPLAY_FOUND=$(detect_display) && break
    sleep 1
done
if [ -z "$DISPLAY_FOUND" ]; then
    log "ERREUR: aucun écran X joignable par $(id -un) après 120 s (sockets: $(ls /tmp/.X11-unix 2>/dev/null | tr '\n' ' '))"
    exit 1
fi
export DISPLAY="$DISPLAY_FOUND"
log "Écran X détecté : $DISPLAY"

if [ ! -x "$APP_BIN" ]; then
    log "ERREUR: binaire introuvable ou non exécutable: $APP_BIN"
    exit 1
fi

log "Lancement de l'application StorePOS..."
# shellcheck disable=SC2086
"$APP_BIN" ${APP_ARGS:-} &
APP_PID=$!
log "Application lancée (PID=$APP_PID)"

# Arrêt propre : systemd envoie SIGTERM à ce script, on le transmet à l'application
trap 'kill -TERM "$APP_PID" 2>/dev/null' TERM INT

# Recherche de la fenêtre : d'abord par PID, sinon par titre
find_window() {
    local id
    id=$(wmctrl -lp 2>/dev/null | awk -v p="$APP_PID" '$3 == p {print $1; exit}')
    if [ -z "$id" ]; then
        id=$(wmctrl -l 2>/dev/null | grep -iE "$WINDOW_NAME_PATTERN" | awk '{print $1; exit}')
    fi
    echo "$id"
}

(
    for _ in $(seq 1 "$MAX_WAIT_SECONDS"); do
        WIN_ID=$(find_window)
        if [ -n "$WIN_ID" ]; then
            wmctrl -i -r "$WIN_ID" -b add,fullscreen
            log "Fenêtre trouvée (ID=$WIN_ID) - plein écran appliqué"
            exit 0
        fi
        sleep 1
    done
    log "ATTENTION: fenêtre non détectée après ${MAX_WAIT_SECONDS}s (vérifier WINDOW_NAME_PATTERN avec 'wmctrl -l')"
) &
WATCHER_PID=$!

EXIT_CODE=0
while kill -0 "$APP_PID" 2>/dev/null; do
    wait "$APP_PID"
    EXIT_CODE=$?
done
kill "$WATCHER_PID" 2>/dev/null || true

log "Application terminée (code de sortie: $EXIT_CODE) - systemd va la relancer"
exit "$EXIT_CODE"
