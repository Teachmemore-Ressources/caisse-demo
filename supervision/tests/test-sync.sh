#!/bin/bash
#
# Teste passerelle-sync.sh (synchronisation de la configuration depuis S3) avec de FAUSSES commandes aws et docker :
# la logique de relance ciblée (Prometheus, Alertmanager, Loki, Grafana) et la protection des identifiants des enseignes.
# Limite assumée : aucun vrai S3 ni Docker ; on vérifie les commandes demandées, pas leur effet.
set -u
SUP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
FAIL=0; ok() { echo "  OK    $*"; }; ko() { echo "  ECHEC $*"; FAIL=1; }
mkdir -p "$T/shim" "$T/s3/serveur" "$T/opt" "$T/donnees/sauvegardes"
cp -r "$SUP/serveur/"* "$T/s3/serveur/"; cp "$SUP/serveur/.env.example" "$T/s3/serveur/"
rm -rf "$T/s3/serveur/cloudflare"
cat > "$T/passerelle.env" <<'EOF'
REGION='eu-west-3'
NOM='passerelle-caisses'
CONFIG_BUCKET='bucket-test'
GRAFANA_MODE='prive'
ACME_EMAIL='a@b.fr'
METRICS_DOMAIN='m.exemple.fr'
LOGS_DOMAIN='l.exemple.fr'
GRAFANA_DOMAIN=''
GRAFANA_ADMIN_USER='admin'
EOF
export SHIM_LOG="$T/appels.log" S3="$T/s3"
cat > "$T/shim/aws" <<'EOF'
#!/bin/bash
echo "aws $*" >> "$SHIM_LOG"
case "$1 $2" in
  "s3 sync")  # copie « bucket » -> dossier, en respectant --exclude (suffisant pour ce test) ; --delete ignoré
      dest="${@: -1}"; src="${@: -2:1}"; src="$S3/${src#s3://bucket-test/}"
      excl=(); while [ $# -gt 0 ]; do [ "$1" = "--exclude" ] && excl+=("$2"); shift; done
      ( cd "$src" && find . -type f | sed 's#^\./##' ) | while read -r f; do
          sauter=0; for e in "${excl[@]}"; do case "$f" in $e) sauter=1;; esac; done
          [ "$sauter" = 1 ] || { mkdir -p "$dest/$(dirname "$f")"; cp "$src/$f" "$dest/$f"; }
      done ;;
  "s3 cp") src="${@: -2:1}"; dest="${@: -1}"; cp "$S3/${src#s3://bucket-test/}" "$dest" ;;
  "ssm get-parameter") echo "valeur-secrete-de-test-0123456789" ;;
esac
exit 0
EOF
printf '#!/bin/bash\necho "docker $*" >> "$SHIM_LOG"\nexit 0\n' > "$T/shim/docker"
chmod +x "$T/shim/aws" "$T/shim/docker"
lancer() { PATH="$T/shim:$PATH" PASSERELLE_ENV="$T/passerelle.env" PASSERELLE_DIR="$T/opt" PASSERELLE_DONNEES="$T/donnees" bash "$SUP/terraform/files/passerelle-sync.sh" >"$T/sortie.log" 2>&1; }
redemarre() { grep -c "docker compose .* restart $1" "$SHIM_LOG" 2>/dev/null || true; }

echo "== Première installation =="
: > "$SHIM_LOG"; lancer && ok "synchronisation réussie" || { ko "synchronisation"; tail -5 "$T/sortie.log"; }
[ "$(redemarre prometheus)" = 0 ] && [ "$(redemarre alertmanager)" = 0 ] && ok "première installation : aucune relance inutile" || ko "relance à la première installation"
grep -q "docker compose .* up -d" "$SHIM_LOG" && ok "la pile est démarrée (docker compose up -d)" || ko "pas de up -d"
[ "$(stat -c %a "$T/opt/.env")" = 600 ] && grep -q '^COMPOSE_FILE=docker-compose.yml:docker-compose.prive.yml' "$T/opt/.env" && ok ".env en 0600 avec la liste des fichiers compose" || ko ".env"
[ -f "$T/opt/prometheus/rules/enregistrements.yml" ] && ok "les règles d'enregistrement sont synchronisées" || ko "règles absentes"

echo; echo "== Synchronisation sans changement =="
: > "$SHIM_LOG"; lancer
[ "$(redemarre prometheus)" = 0 ] && [ "$(redemarre alertmanager)" = 0 ] && [ "$(redemarre loki)" = 0 ] && [ "$(redemarre grafana)" = 0 ] && ok "rien n'a changé : aucun service relancé" || ko "relance sans changement"

echo; echo "== Nouvelle règle d'alerte : seul Prometheus est relancé =="
echo "# modification" >> "$T/s3/serveur/prometheus/rules/caisses.yml"
: > "$SHIM_LOG"; lancer
[ "$(redemarre prometheus)" = 1 ] && ok "règle modifiée : Prometheus relancé" || ko "Prometheus non relancé"
[ "$(redemarre alertmanager)" = 0 ] && [ "$(redemarre loki)" = 0 ] && [ "$(redemarre grafana)" = 0 ] && ok "les autres services ne sont pas touchés" || ko "relance de services non concernés"
grep -q "Configuration de prometheus modifiée" "$T/sortie.log" && ok "la relance est annoncée dans la sortie" || ko "relance non annoncée"

echo; echo "== Alertmanager et tableau de bord =="
echo "# modification" >> "$T/s3/serveur/alertmanager/alertmanager.yml"
echo '{"modifie":true}' > "$T/s3/serveur/grafana/dashboards/parc-caisses.json"
: > "$SHIM_LOG"; lancer
[ "$(redemarre alertmanager)" = 1 ] && ok "Alertmanager modifié : relancé" || ko "Alertmanager non relancé"
[ "$(redemarre grafana)" = 0 ] && ok "nouveau tableau de bord : Grafana n'est PAS relancé (il relit ses tableaux seul)" || ko "Grafana relancé pour un simple tableau de bord"
grep -q '"modifie":true' "$T/opt/grafana/dashboards/parc-caisses.json" && ok "le tableau de bord est bien mis à jour sur le serveur" || ko "tableau de bord non copié"

echo; echo "== Sources de données de Grafana =="
echo "# modification" >> "$T/s3/serveur/grafana/provisioning/datasources/datasources.yml"
: > "$SHIM_LOG"; lancer
[ "$(redemarre grafana)" = 1 ] && ok "sources de données modifiées : Grafana relancé" || ko "Grafana non relancé"

echo; echo "== Identifiants des enseignes protégés =="
printf 'client1 $2a$14$HASHDETEST\n' >> "$T/opt/caddy/enseignes.caddy"
echo "# écrasé ?" >> "$T/s3/serveur/caddy/enseignes.caddy"
lancer
grep -q 'client1 \$2a\$14\$HASHDETEST' "$T/opt/caddy/enseignes.caddy" && ok "enseignes.caddy n'est jamais écrasé par la synchronisation" || ko "enseignes.caddy écrasé !"

echo; [ "$FAIL" = 0 ] && echo "SYNCHRONISATION : TOUT EST OK" || { echo "SYNCHRONISATION : DES ECHECS"; exit 1; }
