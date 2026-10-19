
# ---------------------------------------------------------------------------------------------------------------------
# Première mise en route du serveur (exécutée une seule fois par cloud-init, en root). Journal : /var/log/passerelle-bootstrap.log
# Reprise possible à la main : sudo bash /var/lib/cloud/instance/scripts/part-001  (idempotent)
# ---------------------------------------------------------------------------------------------------------------------
set -euo pipefail
exec > >(tee -a /var/log/passerelle-bootstrap.log) 2>&1
echo "=== bootstrap passerelle : $(date -u +%FT%TZ) ==="
# shellcheck disable=SC1091
. /etc/passerelle.env
export DEBIAN_FRONTEND=noninteractive

echo "== 1. Disque de données =="
dev="/dev/disk/by-id/nvme-Amazon_Elastic_Block_Store_${DATA_VOLUME_ID//-/}"
for _ in $(seq 1 150); do [ -e "$dev" ] && break; sleep 2; done
[ -e "$dev" ] || { echo "Disque de données introuvable : $dev" >&2; exit 1; }
blkid "$dev" >/dev/null 2>&1 || mkfs.ext4 -q -L passerelle-data "$dev"   # ne formate JAMAIS un disque déjà formaté
mkdir -p /srv/data
grep -q 'LABEL=passerelle-data' /etc/fstab || echo 'LABEL=passerelle-data /srv/data ext4 defaults,nofail 0 2' >> /etc/fstab
mountpoint -q /srv/data || mount /srv/data
mkdir -p /srv/data/docker /srv/data/sauvegardes
chmod 700 /srv/data/sauvegardes

echo "== 2. Paquets =="
apt-get update -y
apt-get install -y ca-certificates curl jq cron chrony unattended-upgrades snapd
systemctl enable --now chrony cron
printf 'APT::Periodic::Update-Package-Lists "1";\nAPT::Periodic::Unattended-Upgrade "1";\nAPT::Periodic::AutocleanInterval "7";\n' > /etc/apt/apt.conf.d/20auto-upgrades

echo "== 3. Docker (dépôt officiel), données sur le disque dédié =="
mkdir -p /etc/docker
printf '{\n  "data-root": "/srv/data/docker",\n  "log-driver": "local",\n  "log-opts": {"max-size": "20m", "max-file": "5"}\n}\n' > /etc/docker/daemon.json
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" > /etc/apt/sources.list.d/docker.list
apt-get update -y
apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
systemctl enable --now docker

echo "== 4. AWS CLI =="
snap wait system seed.loaded || true
command -v aws >/dev/null 2>&1 || snap install aws-cli --classic
export PATH="$PATH:/snap/bin"
ln -sf /snap/bin/aws /usr/local/bin/aws

echo "== 5. Outils d'exploitation (depuis S3) =="
for f in passerelle-sync passerelle-sauvegarde; do
    aws s3 cp --region "$REGION" "s3://$CONFIG_BUCKET/outils/$f.sh" "/usr/local/sbin/$f"
    chmod 0750 "/usr/local/sbin/$f"
done
# Sauvegarde quotidienne (03:30 UTC) : archive locale puis copie dans S3. Journal : /var/log/passerelle-sauvegarde.log
printf '30 3 * * * root /usr/local/sbin/passerelle-sauvegarde >> /var/log/passerelle-sauvegarde.log 2>&1\n' > /etc/cron.d/passerelle-sauvegarde
chmod 644 /etc/cron.d/passerelle-sauvegarde

echo "== 6. Configuration + démarrage de la pile =="
/usr/local/sbin/passerelle-sync

echo "=== bootstrap terminé : $(date -u +%FT%TZ) ==="
