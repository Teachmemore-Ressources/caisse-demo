#!/bin/bash
# Teste securiser-hote.sh et sauvegarder.sh avec des commandes factices (docker, ufw, apt-get...) qui enregistrent ce qu'on leur demande.
# Limite assumée : ce test vérifie la LOGIQUE des scripts, pas leur effet sur un vrai serveur ni un vrai Docker.
set -u
SUP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
FAIL=0; ok() { echo "  OK    $*"; }; ko() { echo "  ECHEC $*"; FAIL=1; }
mkdir -p "$T/shim"
for c in apt-get ufw systemctl sshd fail2ban; do printf '#!/bin/bash\necho "%s $*" >> "$SHIM_LOG"\n' "$c" > "$T/shim/$c"; chmod +x "$T/shim/$c"; done
export SHIM_LOG="$T/appels.log"; : > "$SHIM_LOG"

echo "== securiser-hote.sh =="
mkdir -p "$T/racine/root/.ssh" "$T/vide"
out=$(HOTE_ROOT="$T/vide" PATH="$T/shim:$PATH" bash "$SUP/serveur/scripts/securiser-hote.sh" 2>&1)
echo "$out" | grep -q 'ATTENTION : aucune clé SSH' && ok "simulation : avertit qu'aucune clé SSH n'est installée" || ko "pas d'avertissement"
[ ! -s "$SHIM_LOG" ] && [ -z "$(find "$T/vide" -type f)" ] && ok "simulation : aucune commande lancée, aucun fichier écrit" || ko "la simulation a modifié quelque chose"
echo "$out" | grep -q 'ufw allow 443/tcp' && echo "$out" | grep -q 'ufw limit 22/tcp' && ok "la simulation liste les règles de pare-feu" || ko "règles absentes"
HOTE_ROOT="$T/vide" PATH="$T/shim:$PATH" bash "$SUP/serveur/scripts/securiser-hote.sh" --appliquer >/dev/null 2>&1 && ko "appliqué SANS clé SSH (risque d'enfermement)" || ok "--appliquer refusé sans clé SSH"
[ ! -s "$SHIM_LOG" ] && ok "rien n'a été exécuté lors du refus" || ko "commandes lancées malgré le refus"

echo "ssh-ed25519 AAAA test" > "$T/racine/root/.ssh/authorized_keys"
HOTE_ROOT="$T/racine" PATH="$T/shim:$PATH" bash "$SUP/serveur/scripts/securiser-hote.sh" --appliquer >"$T/appli.log" 2>&1 && ok "--appliquer accepté avec une clé SSH" || { ko "application"; tail -5 "$T/appli.log"; }
grep -q '^PasswordAuthentication no' "$T/racine/etc/ssh/sshd_config.d/10-passerelle.conf" && ok "SSH : mots de passe désactivés" || ko "conf SSH"
grep -q '^enabled = true' "$T/racine/etc/fail2ban/jail.d/passerelle.local" && ! grep -qi caddy "$T/racine/etc/fail2ban/jail.d/passerelle.local" && ok "fail2ban : SSH seulement, rien sur Caddy (NAT partagé)" || ko "conf fail2ban"
grep -q 'Unattended-Upgrade "1"' "$T/racine/etc/apt/apt.conf.d/20auto-upgrades" && ok "mises à jour automatiques activées" || ko "auto-upgrades"
l=$(cat "$SHIM_LOG"); echo "$l" | grep -q 'ufw default deny incoming' && echo "$l" | grep -q 'ufw allow 80/tcp' && echo "$l" | grep -q 'ufw allow 443/tcp' && echo "$l" | grep -q 'ufw --force enable' && ok "ufw : refus par défaut, 80/443 ouverts, activé" || ko "ufw"
ordre=$(grep -n 'ufw limit 22/tcp\|ufw --force enable' "$SHIM_LOG" | cut -d: -f1 | head -2 | tr '\n' ' '); set -- $ordre; [ "${1:-9}" -lt "${2:-0}" ] && ok "ufw : SSH autorisé AVANT l'activation (pas d'enfermement)" || ko "ordre ufw ($ordre)"
echo "$l" | grep -q 'ufw allow 22\b' && ko "SSH ouvert sans limitation" || ok "SSH limité en tentatives (ufw limit)"

echo; echo "== sauvegarder.sh =="
mkdir -p "$T/projet/caddy" "$T/projet/sauvegardes"; echo "ACME_EMAIL=x" > "$T/projet/.env"; echo "contenu" > "$T/projet/caddy/enseignes.caddy"
cat > "$T/shim/docker" <<'EOF'
#!/bin/bash
echo "docker $*" >> "$SHIM_LOG"
case "$*" in
  *"compose exec -T prometheus wget"*) echo '{"status":"success","data":{"name":"20261017T000000Z-abc"}}';;
  *"volume inspect"*) exit 1;;    # pas de GLPI dans ce test (voir test-glpi-serveur.sh)
  *"compose exec"*) ;;
  run*) # simule le conteneur alpine : créer le fichier tar demandé dans le dossier monté sur /sortie
        for a in "$@"; do case "$a" in /sortie/*) f="$a";; *:/sortie) d="${a%%:*}";; esac; done
        [ -n "${f:-}" ] && [ -n "${d:-}" ] && echo fake > "$d/$(basename "$f")";;
esac
EOF
chmod +x "$T/shim/docker"
: > "$SHIM_LOG"
for i in 1 2 3 4; do sleep 1; (cd "$T/projet" && PATH="$T/shim:$PATH" RETENTION=3 bash "$SUP/serveur/scripts/sauvegarder.sh" >"$T/sauv$i.log" 2>&1) || { ko "sauvegarde $i en erreur"; tail -5 "$T/sauv$i.log"; }; done
n=$(ls "$T/projet/sauvegardes"/passerelle-*.tar | wc -l); [ "$n" = "3" ] && ok "rotation : 4 sauvegardes faites, 3 conservées" || ko "rotation : $n fichiers"
a=$(ls -t "$T/projet/sauvegardes"/passerelle-*.tar | head -1); contenu=$(tar -tf "$a" | tr '\n' ' ')
for f in config.tar.gz prometheus.tar.gz grafana_data.tar.gz alertmanager_data.tar.gz caddy_data.tar.gz loki_data.tar.gz; do echo "$contenu" | grep -q "$f" || ko "absent de l'archive : $f"; done; ok "l'archive contient configuration, Prometheus et les 4 volumes"
mkdir -p "$T/x" && tar -xf "$a" -C "$T/x" && tar -tzf "$T/x/config.tar.gz" | grep -q 'enseignes.caddy' && tar -tzf "$T/x/config.tar.gz" | grep -q '\.env' && ok "la configuration (.env, enseignes) est incluse" || ko "config incomplète"
tar -tzf "$T/x/config.tar.gz" | grep -q 'sauvegardes/passerelle' && ko "les anciennes sauvegardes sont ré-incluses (taille qui explose)" || ok "les sauvegardes précédentes ne sont pas ré-incluses"
[ "$(stat -c %a "$a")" = "600" ] && ok "archive en droits 600" || ko "droits de l'archive"
grep -q 'snapshots/20261017T000000Z-abc' "$SHIM_LOG" && grep -q 'rm -rf /prometheus/snapshots/20261017T000000Z-abc' "$SHIM_LOG" && ok "instantané Prometheus pris puis supprimé du volume" || ko "gestion de l'instantané"
# échec de l'instantané → code de sortie non nul
sed -i 's#\*"compose exec -T prometheus wget"\*) echo .*;;#*"compose exec -T prometheus wget"*) exit 1;;#' "$T/shim/docker"
(cd "$T/projet" && PATH="$T/shim:$PATH" bash "$SUP/serveur/scripts/sauvegarder.sh" >"$T/echec.log" 2>&1); [ $? = 2 ] && ok "instantané impossible → la sauvegarde signale l'échec (code 2)" || ko "échec silencieux"

echo; [ "$FAIL" = 0 ] && echo "SCRIPTS SERVEUR : TOUT EST OK" || { echo "SCRIPTS SERVEUR : DES ECHECS"; exit 1; }
