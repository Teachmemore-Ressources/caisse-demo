#!/bin/bash
# Sauvegarde quotidienne : archive locale (scripts/sauvegarder.sh) puis copie dans le bucket S3 de sauvegardes.
# Lancée par /etc/cron.d/passerelle-sauvegarde. Le bucket chiffre au repos, versionne et fait expirer les anciennes copies.
set -euo pipefail
export PATH="$PATH:/snap/bin"
# shellcheck disable=SC1091
. /etc/passerelle.env
cd /opt/passerelle
RETENTION=3 bash scripts/sauvegarder.sh /srv/data/sauvegardes          # 3 copies locales ; l'historique est dans S3
aws s3 sync --region "$REGION" --only-show-errors /srv/data/sauvegardes "s3://$BACKUP_BUCKET/passerelle/"
echo "Copie S3 terminée : s3://$BACKUP_BUCKET/passerelle/ ($(date -u +%FT%TZ))"
