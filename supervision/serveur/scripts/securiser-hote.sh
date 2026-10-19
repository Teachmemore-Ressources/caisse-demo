#!/bin/bash
#
# Durcit un serveur Ubuntu/Debian tout neuf qui va héberger la passerelle de supervision.
#   - pare-feu : seuls 80/443 (public) et 22 (SSH, avec limitation de tentatives) sont ouverts
#   - SSH : connexion par clé uniquement, pas de mot de passe, pas de root
#   - mises à jour de sécurité automatiques, synchronisation de l'heure, fail2ban sur SSH
#
# PAR DÉFAUT : n'écrit RIEN, affiche ce qui serait fait.  Pour appliquer :  sudo bash securiser-hote.sh --appliquer
# Avant d'appliquer : vérifier qu'une clé SSH est bien installée pour votre utilisateur (sinon vous seriez enfermé dehors).
# Ne bannit volontairement PAS les adresses vues par Caddy : les caisses d'un même magasin partagent une adresse (NAT).
set -eu
APPLIQUER=0; [ "${1:-}" = "--appliquer" ] && APPLIQUER=1
RACINE="${HOTE_ROOT:-}"                 # préfixe pour les tests ; vide en production
PORT_SSH="${SSH_PORT:-22}"
sh()  { if [ "$APPLIQUER" = 1 ]; then echo "+ $*"; "$@"; else echo "[à faire] $*"; fi; }
ecrire() {  # ecrire <fichier> (contenu sur stdin)
    local f="$RACINE$1"; local c; c=$(cat)
    if [ "$APPLIQUER" = 1 ]; then mkdir -p "$(dirname "$f")"; printf '%s\n' "$c" > "$f"; echo "+ écrit $f"
    else echo "[à faire] écrire $f :"; printf '%s\n' "$c" | sed 's/^/        /'; fi
}

if [ "$APPLIQUER" = 1 ] && [ "$(id -u)" != 0 ] && [ -z "$RACINE" ]; then echo "Exécuter en root : sudo bash $0 --appliquer" >&2; exit 1; fi

echo "== Garde-fou : une clé SSH est-elle en place ? =="
cles=0
for h in "$RACINE"/root /home/*; do
    f="${h#"$RACINE"}"; [ "$h" = "$RACINE/root" ] && f=/root
    [ -s "$RACINE$f/.ssh/authorized_keys" ] && cles=$((cles+1))
done 2>/dev/null || true
if [ "$cles" -gt 0 ]; then echo "  OK : $cles compte(s) avec une clé SSH autorisée"
elif [ "$APPLIQUER" = 1 ]; then echo "REFUS : aucune clé SSH trouvée (authorized_keys). Ajoutez la vôtre d'abord (ssh-copy-id), sinon la désactivation des mots de passe vous enfermerait dehors." >&2; exit 1
else echo "  ATTENTION : aucune clé SSH trouvée ; --appliquer refusera de continuer tant qu'elle n'est pas installée"; fi

echo; echo "== Paquets =="
sh apt-get update -y
sh apt-get install -y ufw fail2ban unattended-upgrades chrony curl ca-certificates

echo; echo "== SSH : clé uniquement =="
ecrire /etc/ssh/sshd_config.d/10-passerelle.conf <<EOF
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
PubkeyAuthentication yes
MaxAuthTries 3
X11Forwarding no
EOF
if [ "$APPLIQUER" = 1 ] && [ -z "$RACINE" ]; then
    sshd -t && { systemctl reload ssh 2>/dev/null || systemctl reload sshd; echo "+ configuration SSH rechargée"; } || { rm -f /etc/ssh/sshd_config.d/10-passerelle.conf; echo "sshd -t a échoué : configuration retirée" >&2; exit 1; }
else echo "[à faire] sshd -t puis rechargement de SSH (retrait automatique du fichier si le test échoue)"; fi

echo; echo "== Pare-feu (ufw) =="
sh ufw default deny incoming
sh ufw default allow outgoing
sh ufw limit "$PORT_SSH"/tcp
sh ufw allow 80/tcp
sh ufw allow 443/tcp
sh ufw --force enable
echo "  NB : Docker publie ses ports en contournant ufw ; c'est sans danger ici car docker-compose.yml ne publie que 80 et 443."

echo; echo "== Mises à jour de sécurité automatiques =="
ecrire /etc/apt/apt.conf.d/20auto-upgrades <<EOF
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT::Periodic::AutocleanInterval "7";
EOF

echo; echo "== fail2ban : SSH seulement =="
ecrire /etc/fail2ban/jail.d/passerelle.local <<EOF
[DEFAULT]
bantime = 1h
findtime = 10m
maxretry = 5
[sshd]
enabled = true
port = $PORT_SSH
EOF
sh systemctl enable --now fail2ban chrony

echo; echo "== Docker (optionnel, si absent) =="
if command -v docker >/dev/null 2>&1; then echo "  Docker déjà installé : $(docker --version)"
else echo "  Docker absent. Installation conseillée via la documentation officielle (https://docs.docker.com/engine/install/ubuntu/) ;"
     echo "  ce script ne télécharge pas de script distant de lui-même."; fi

echo; [ "$APPLIQUER" = 1 ] && echo "Terminé. Ouvrez une 2e session SSH pour vérifier que vous pouvez encore vous connecter AVANT de fermer celle-ci." || echo "Simulation terminée : rien n'a été modifié. Relancer avec --appliquer pour appliquer."
