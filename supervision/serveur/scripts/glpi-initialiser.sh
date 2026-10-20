#!/bin/bash
#
# Premier réglage de GLPI, à lancer UNE FOIS après le premier démarrage de la pile avec GLPI (puis après toute réinstallation) :
#   1. remplace le mot de passe du compte administrateur « glpi » (l'installation crée des comptes aux mots de passe connus :
#      glpi/glpi, tech/tech, normal/normal, post-only/postonly) ;
#   2. désactive les trois autres comptes par défaut ;
#   3. active l'inventaire natif (Administration > Inventaire > Activer l'inventaire) pour que GLPI accepte les agents.
#
#   cd /opt/passerelle && sudo bash scripts/glpi-initialiser.sh
#
# Le mot de passe administrateur est lu dans .env (GLPI_ADMIN_PASSWORD, généré par Terraform) ou, à défaut, généré et affiché une fois.
# Le script lit/écrit directement la base de GLPI. Il affiche ce qu'il a changé : VÉRIFIER dans l'interface (tunnel) que
# « Administration > Inventaire > Activer l'inventaire » est coché et que la connexion avec le nouveau mot de passe fonctionne.
set -euo pipefail
RACINE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE=${COMPOSE_CMD:-docker compose}
cd "$RACINE"

lire_env() { grep -E "^$1=" .env 2>/dev/null | head -1 | cut -d= -f2- | sed -e 's/[[:space:]]*#.*$//' -e 's/[[:space:]]*$//' || true; }
ROOT_PW=$(lire_env GLPI_DB_ROOT_PASSWORD)
[ -n "$ROOT_PW" ] || { echo "GLPI_DB_ROOT_PASSWORD absent de .env : GLPI est-il activé ?" >&2; exit 1; }
ADMIN_PW=$(lire_env GLPI_ADMIN_PASSWORD); AFFICHER=0
if [ -z "$ADMIN_PW" ]; then ADMIN_PW=$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 24); AFFICHER=1; fi
[ "${#ADMIN_PW}" -ge 12 ] || { echo "Le mot de passe administrateur doit faire au moins 12 caractères." >&2; exit 1; }
case "$ADMIN_PW" in *[!A-Za-z0-9]*) echo "Le mot de passe administrateur ne doit contenir que des lettres et des chiffres." >&2; exit 1 ;; esac

sql() { $COMPOSE exec -T glpi-db mariadb -uroot "-p$ROOT_PW" glpi "$@"; }

echo "== Attente de l'installation de GLPI (jusqu'à 5 minutes) =="
pret=0
for _ in $(seq 1 60); do
    if sql -N -e "SELECT COUNT(*) FROM glpi_users WHERE name='glpi'" 2>/dev/null | grep -q '^1$'; then pret=1; break; fi
    sleep 5
done
[ "$pret" = 1 ] || { echo "GLPI n'est pas installé après 5 minutes : voir  $COMPOSE logs glpi glpi-db" >&2; exit 1; }
echo "  installation détectée"

# L'empreinte doit être calculée PAR PHP (password_hash, préfixe $2y$) : GLPI juge « périmée » toute autre empreinte (par exemple le $2a$ de Caddy)
# et répond « votre mot de passe a expiré » à la connexion. CADDY_HASH_CMD : remplacement réservé aux tests.
if [ -n "${CADDY_HASH_CMD:-}" ]; then
    HASH=$($CADDY_HASH_CMD "$ADMIN_PW")
else
    HASH=$($COMPOSE exec -T -e GLPI_PW="$ADMIN_PW" glpi php -r 'echo password_hash(getenv("GLPI_PW"), PASSWORD_DEFAULT);' | tr -d '\r\n')
fi
case "$HASH" in '$2'*) ;; *) echo "Calcul de l'empreinte impossible." >&2; exit 1 ;; esac

echo "== Sécurisation des comptes et activation de l'inventaire =="
sql <<SQL
UPDATE glpi_users SET password='$HASH', password_last_update=NOW() WHERE name='glpi';
UPDATE glpi_users SET is_active=0 WHERE name IN ('tech','normal','post-only');
INSERT INTO glpi_configs (context, name, value) VALUES ('inventory', 'enabled_inventory', '1')
    ON DUPLICATE KEY UPDATE value='1';
SQL

echo "== Vérification =="
sql -e "SELECT name AS compte, is_active AS actif FROM glpi_users WHERE name IN ('glpi','tech','normal','post-only') ORDER BY name;"
sql -e "SELECT context, name, value FROM glpi_configs WHERE context='inventory' AND name LIKE '%enabled%';"

echo
echo "Terminé. Compte administrateur : glpi"
if [ "$AFFICHER" = 1 ]; then
    echo "Mot de passe : $ADMIN_PW        <-- à noter MAINTENANT : il n'est conservé nulle part"
else
    echo "Mot de passe : celui de GLPI_ADMIN_PASSWORD (Terraform : aws ssm get-parameter --name /<nom>/glpi_admin_password --with-decryption)"
fi
echo "Ouvrir GLPI par le tunnel (http://localhost:8081) et vérifier : Administration > Inventaire > « Activer l'inventaire » coché."
