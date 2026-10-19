#!/bin/bash
#
# kiosk-metrics.sh : écrit les mesures « système » de la caisse au format texte Prometheus.
# Lancé chaque minute par storepos-kiosk-metrics.timer ; lu par l'agent (collecteur textfile).
# L'écriture est atomique (fichier temporaire puis renommage) : l'agent ne lit jamais un fichier à moitié écrit.

set -u
DIR="${METRICS_DIR:-/var/lib/storepos-kiosk/metrics}"
OUT="$DIR/kiosk.prom"
TMP="$OUT.tmp"
mkdir -p "$DIR"

esc() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }

{
    echo "# HELP kiosk_package_info Version des paquets de la caisse (1 si installé)."
    echo "# TYPE kiosk_package_info gauge"
    for p in caisse-demo com.aemsofts.storepos storepos-kiosk storepos-kiosk-monitoring teamviewer-host teamviewer; do
        ligne=$(dpkg-query -W -f='${db:Status-Abbrev} ${Version}' "$p" 2>/dev/null || true)
        case "$ligne" in
            "ii "*) printf 'kiosk_package_info{package="%s",version="%s"} 1\n' "$p" "$(esc "${ligne#ii }")" ;;
        esac
    done

    echo "# HELP kiosk_os_info Système d'exploitation de la caisse."
    echo "# TYPE kiosk_os_info gauge"
    os=$(. /etc/os-release 2>/dev/null && printf '%s' "${PRETTY_NAME:-inconnu}")
    printf 'kiosk_os_info{os="%s"} 1\n' "$(esc "$os")"

    echo "# HELP kiosk_reboot_required 1 si le système demande un redémarrage."
    echo "# TYPE kiosk_reboot_required gauge"
    if [ -e /var/run/reboot-required ]; then echo "kiosk_reboot_required 1"; else echo "kiosk_reboot_required 0"; fi

    echo "# HELP kiosk_service_restarts Redémarrages d'un service depuis son dernier démarrage manuel (boucle de plantage si ça monte)."
    echo "# TYPE kiosk_service_restarts gauge"
    for u in storepos.service lightdm.service; do
        n=$(systemctl show -p NRestarts --value "$u" 2>/dev/null || true)
        case "$n" in ''|*[!0-9]*) ;; *) printf 'kiosk_service_restarts{unit="%s"} %s\n' "$u" "$n" ;; esac
    done

    echo "# HELP kiosk_usb_device_present 1 si un périphérique du constructeur est détecté en USB (scanner, imprimante)."
    echo "# TYPE kiosk_usb_device_present gauge"
    usb=$(lsusb 2>/dev/null || true)
    for entree in "datalogic:05f9" "epson:04b8"; do
        vendor=${entree%%:*}; id=${entree##*:}
        if printf '%s' "$usb" | grep -qi "ID $id:"; then v=1; else v=0; fi
        printf 'kiosk_usb_device_present{vendor="%s"} %s\n' "$vendor" "$v"
    done

    echo "# HELP kiosk_metrics_last_run_timestamp_seconds Heure de la dernière exécution de ce script."
    echo "# TYPE kiosk_metrics_last_run_timestamp_seconds gauge"
    echo "kiosk_metrics_last_run_timestamp_seconds $(date +%s)"
} > "$TMP"

chmod 0644 "$TMP"
mv -f "$TMP" "$OUT"
