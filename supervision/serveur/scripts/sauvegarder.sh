#!/bin/bash
#
# Sauvegarde de la passerelle : configuration + volumes (Grafana, Alertmanager, Loki, certificats Caddy) + instantané Prometheus.
# À lancer depuis le dossier du docker-compose (cron conseillé : chaque nuit).  Usage :
#   bash scripts/sauvegarder.sh [dossier-de-destination]        (défaut : ./sauvegardes)
#   RETENTION=14  nombre de sauvegardes conservées (défaut 14)
#   CHIFFRER_POUR=age1xxxx  si défini et si « age » est installé, l'archive est chiffrée pour cette clé publique
#
# Contenu SENSIBLE (.env, empreintes des enseignes, secrets) : l'archive reste sur le serveur en droits 600 ; la COPIER
# HORS DU SERVEUR (autre machine ou stockage objet), idéalement chiffrée. Une sauvegarde qui reste sur le même disque n'en est pas une.
set -eu
DEST="${1:-./sauvegardes}"; RETENTION="${RETENTION:-14}"
DOCKER="${DOCKER:-docker}"; COMPOSE="${COMPOSE:-$DOCKER compose}"
PROJET="${PROJET:-supervision-caisses}"
HORO=$(date +%Y%m%d-%H%M%S)
mkdir -p "$DEST"; chmod 700 "$DEST"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
echo "== Configuration =="
tar -czf "$W/config.tar.gz" --exclude=./sauvegardes --exclude='./*.tar.gz' .
echo "  config.tar.gz ($(du -h "$W/config.tar.gz" | cut -f1))"

echo "== Instantané Prometheus (cohérent, sans arrêt) =="
snap=$($COMPOSE exec -T prometheus wget -qO- --post-data='' http://localhost:9090/api/v1/admin/tsdb/snapshot 2>/dev/null | sed -n 's/.*"name":"\([^"]*\)".*/\1/p' || true)
if [ -n "$snap" ]; then
    $DOCKER run --rm -v "${PROJET}_prometheus_data:/prometheus:ro" -v "$W:/sortie" alpine:3 tar -czf /sortie/prometheus.tar.gz -C /prometheus/snapshots "$snap"
    $COMPOSE exec -T prometheus rm -rf "/prometheus/snapshots/$snap" || true
    echo "  prometheus.tar.gz ($(du -h "$W/prometheus.tar.gz" | cut -f1))"
else echo "  ATTENTION : instantané Prometheus impossible (service arrêté ?) — mesures non sauvegardées" >&2; ECHEC=1; fi

# GLPI (seulement s'il est installé) : export SQL cohérent de la base + fichiers (la clé de chiffrement glpicrypt.key est INDISPENSABLE)
if $DOCKER volume inspect "${PROJET}_glpi_db_data" >/dev/null 2>&1; then
    echo "== Base GLPI =="
    # export dans un fichier PUIS compression : un échec de mariadb-dump (service arrêté) ne doit pas passer pour un succès
    if $COMPOSE exec -T glpi-db sh -c 'mariadb-dump -uroot -p"$MARIADB_ROOT_PASSWORD" --single-transaction --routines glpi' > "$W/glpi_db.sql" 2>/dev/null && [ -s "$W/glpi_db.sql" ]; then
        gzip "$W/glpi_db.sql"
        echo "  glpi_db.sql.gz ($(du -h "$W/glpi_db.sql.gz" | cut -f1))"
    else
        echo "  ATTENTION : export de la base GLPI impossible (service arrêté ?) — inventaire non sauvegardé" >&2; rm -f "$W/glpi_db.sql"; ECHEC=1
    fi
    GLPI_VOLUMES="glpi_data"
fi

for v in grafana_data alertmanager_data caddy_data loki_data ${GLPI_VOLUMES:-}; do
    echo "== Volume $v =="
    # Grafana (SQLite) et Loki : copie à chaud acceptable pour une sauvegarde de reprise ; Loki est volumineux et reconstituable (journaux de diagnostic).
    $DOCKER run --rm -v "${PROJET}_${v}:/donnees:ro" -v "$W:/sortie" alpine:3 tar -czf "/sortie/${v}.tar.gz" -C /donnees .
    echo "  ${v}.tar.gz ($(du -h "$W/${v}.tar.gz" | cut -f1))"
done

ARCH="$DEST/passerelle-$HORO.tar"
tar -cf "$ARCH" -C "$W" .
if [ -n "${CHIFFRER_POUR:-}" ] && command -v age >/dev/null 2>&1; then age -r "$CHIFFRER_POUR" -o "$ARCH.age" "$ARCH" && rm -f "$ARCH" && ARCH="$ARCH.age"; echo "  chiffrée pour la clé publique fournie"; fi
chmod 600 "$ARCH"
echo "== Sauvegarde : $ARCH ($(du -h "$ARCH" | cut -f1)) =="

# rotation : ne garder que les RETENTION plus récentes
ls -1t "$DEST"/passerelle-*.tar* 2>/dev/null | tail -n +"$((RETENTION+1))" | while read -r vieux; do rm -f "$vieux"; echo "  supprimée (rotation) : $vieux"; done
[ "${ECHEC:-0}" = 1 ] && exit 2
echo "OK — pensez à copier $DEST hors du serveur."
