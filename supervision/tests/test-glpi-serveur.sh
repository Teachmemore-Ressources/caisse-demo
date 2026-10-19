#!/bin/bash
# Teste, avec de fausses commandes (docker, compose), la LOGIQUE des scripts serveur liés à GLPI :
#   sauvegarder.sh (export SQL + fichiers de GLPI) et glpi-initialiser.sh (comptes par défaut, inventaire natif).
# Limite assumée : le SQL n'est pas exécuté sur une vraie base GLPI ; il est seulement vérifié tel qu'envoyé.
set -u
SUP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
FAIL=0; ok() { echo "  OK    $*"; }; ko() { echo "  ECHEC $*"; FAIL=1; }
export SHIM_LOG="$T/appels.log"; : > "$SHIM_LOG"
mkdir -p "$T/shim" "$T/projet/caddy" "$T/projet/sauvegardes"
echo "ACME_EMAIL=x" > "$T/projet/.env"; echo "contenu" > "$T/projet/caddy/enseignes.caddy"

echo "== sauvegarder.sh avec GLPI =="
cat > "$T/shim/docker" <<'EOF'
#!/bin/bash
echo "docker $*" >> "$SHIM_LOG"
case "$*" in
  *"compose exec -T prometheus wget"*) echo '{"status":"success","data":{"name":"20261018T000000Z-abc"}}';;
  *"volume inspect"*glpi_db_data*) [ -n "${SHIM_GLPI:-}" ] && exit 0 || exit 1;;
  *"compose exec -T glpi-db"*) [ -n "${SHIM_GLPI_PANNE:-}" ] && exit 1; echo "-- Dump MariaDB (simule)";;
  *"compose exec"*) ;;
  run*) for a in "$@"; do case "$a" in /sortie/*) f="$a";; *:/sortie) d="${a%%:*}";; esac; done
        [ -n "${f:-}" ] && [ -n "${d:-}" ] && echo fake > "$d/$(basename "$f")";;
esac
EOF
chmod +x "$T/shim/docker"
lancer() { (cd "$T/projet" && PATH="$T/shim:$PATH" "$@" bash "$SUP/serveur/scripts/sauvegarder.sh" >"$T/sauv.log" 2>&1); }
rm -f "$T"/projet/sauvegardes/*; env SHIM_GLPI= PATH="$T/shim:$PATH" true
(cd "$T/projet" && PATH="$T/shim:$PATH" bash "$SUP/serveur/scripts/sauvegarder.sh" >"$T/sauv0.log" 2>&1) && ok "sans GLPI : la sauvegarde ne change pas" || ko "sauvegarde sans GLPI"
a=$(ls -t "$T/projet/sauvegardes"/passerelle-*.tar | head -1); tar -tf "$a" | grep -q glpi && ko "GLPI sauvegardé alors qu'il n'est pas installé" || ok "sans GLPI : aucune trace de GLPI dans l'archive"
sleep 1; rm -f "$T"/projet/sauvegardes/*
(cd "$T/projet" && SHIM_GLPI=1 PATH="$T/shim:$PATH" bash "$SUP/serveur/scripts/sauvegarder.sh" >"$T/sauv1.log" 2>&1) && ok "avec GLPI : la sauvegarde réussit" || { ko "sauvegarde avec GLPI"; tail -5 "$T/sauv1.log"; }
a=$(ls -t "$T/projet/sauvegardes"/passerelle-*.tar | head -1); contenu=$(tar -tf "$a" | tr '\n' ' ')
echo "$contenu" | grep -q 'glpi_db.sql.gz' && echo "$contenu" | grep -q 'glpi_data.tar.gz' && ok "l'archive contient l'export SQL de GLPI et ses fichiers (dont la clé de chiffrement)" || ko "archive : $contenu"
mkdir -p "$T/x" && tar -xf "$a" -C "$T/x" && gunzip -c "$T/x/glpi_db.sql.gz" | grep -q 'Dump MariaDB' && ok "l'export SQL contient bien la sortie de mariadb-dump" || ko "export SQL vide"
echo "$contenu" | grep -q 'glpi_db_data.tar.gz' && ko "volume brut de la base copié à chaud (incohérent)" || ok "la base n'est pas copiée à chaud : seul l'export SQL est conservé"
sleep 1
(cd "$T/projet" && SHIM_GLPI=1 SHIM_GLPI_PANNE=1 PATH="$T/shim:$PATH" bash "$SUP/serveur/scripts/sauvegarder.sh" >"$T/sauv2.log" 2>&1); [ $? = 2 ] && ok "GLPI arrêté : la sauvegarde signale l'échec (code 2)" || ko "échec GLPI silencieux"

echo; echo "== glpi-initialiser.sh =="
G="$T/glpi"; mkdir -p "$G/scripts" "$T/shim2"; cp "$SUP/serveur/scripts/glpi-initialiser.sh" "$G/scripts/"
cat > "$T/shim2/compose" <<'EOF'
#!/bin/bash
echo "compose $*" >> "$SHIM_LOG"
case "$*" in
  *"SELECT COUNT(*)"*) echo 1 ;;
  *"-e SELECT"*) ;;
  *) cat >> "$SHIM_LOG.sql" ;;
esac
EOF
printf '#!/bin/bash
echo '"'"'$2a$14$HASHDETEST'"'"'
' > "$T/shim2/hash"; chmod +x "$T/shim2/hash" "$T/shim2/compose"; : > "$SHIM_LOG"; : > "$SHIM_LOG.sql"
printf 'GLPI_DB_ROOT_PASSWORD=RootMdpDeTest123   # commentaire\nGLPI_ADMIN_PASSWORD=AdminMdpDeTest1234\n' > "$G/.env"
(cd "$G" && COMPOSE_CMD="$T/shim2/compose" CADDY_HASH_CMD="$T/shim2/hash" bash scripts/glpi-initialiser.sh >"$T/init.log" 2>&1) && ok "initialisation exécutée" || { ko "initialisation"; cat "$T/init.log"; }
grep -q "UPDATE glpi_users SET password='\$2a\$14\$HASHDETEST'" "$T/appels.log.sql" 2>/dev/null || grep -q "UPDATE glpi_users SET password='\$2a\$14\$HASHDETEST'" "$SHIM_LOG.sql" && ok "mot de passe administrateur remplacé par une empreinte bcrypt" || { ko "mot de passe non remplacé"; cat "$SHIM_LOG.sql"; }
grep -q "WHERE name='glpi'" "$SHIM_LOG.sql" && ok "seul le compte « glpi » reçoit ce mot de passe" || ko "cible du mot de passe"
grep -q "is_active=0 WHERE name IN ('tech','normal','post-only')" "$SHIM_LOG.sql" && ok "comptes par défaut tech / normal / post-only désactivés" || ko "comptes par défaut encore actifs"
grep -q "'inventory', 'enabled_inventory', '1'" "$SHIM_LOG.sql" && ok "inventaire natif activé (glpi_configs)" || ko "inventaire non activé"
grep -q 'AdminMdpDeTest1234' "$T/init.log" && ko "le mot de passe fourni est réaffiché" || ok "le mot de passe issu de .env n'est pas réaffiché"
grep -q 'RootMdpDeTest123' "$T/init.log" && ko "mot de passe root affiché" || ok "le mot de passe root n'est jamais affiché"
printf 'GLPI_DB_ROOT_PASSWORD=RootMdpDeTest123\n' > "$G/.env"
(cd "$G" && COMPOSE_CMD="$T/shim2/compose" CADDY_HASH_CMD="$T/shim2/hash" bash scripts/glpi-initialiser.sh >"$T/init2.log" 2>&1); grep -q 'Mot de passe : ' "$T/init2.log" && ok "sans mot de passe fourni : il est généré et affiché une seule fois" || ko "génération du mot de passe"
printf 'GLPI_DB_ROOT_PASSWORD=RootMdpDeTest123\nGLPI_ADMIN_PASSWORD=court\n' > "$G/.env"
(cd "$G" && COMPOSE_CMD="$T/shim2/compose" CADDY_HASH_CMD="echo x" bash scripts/glpi-initialiser.sh >/dev/null 2>&1) && ko "mot de passe trop court accepté" || ok "mot de passe administrateur trop court refusé"
printf 'GLPI_ADMIN_PASSWORD=AdminMdpDeTest1234\n' > "$G/.env"
(cd "$G" && COMPOSE_CMD="$T/shim2/compose" CADDY_HASH_CMD="echo x" bash scripts/glpi-initialiser.sh >/dev/null 2>&1) && ko "accepté sans mot de passe root" || ok "refus si GLPI n'est pas configuré (pas de mot de passe root dans .env)"

echo; echo "== docker-compose.glpi.yml =="
python3 -I - "$SUP/serveur" <<'EOF' && ok "compose GLPI : interface sur 127.0.0.1 seulement, base non publiée, images versionnées, mots de passe obligatoires" || ko "compose GLPI non conforme"
import sys, yaml
d = sys.argv[1]
base = yaml.safe_load(open(d + "/docker-compose.yml"))["services"]
g = yaml.safe_load(open(d + "/docker-compose.glpi.yml"))
s = g["services"]
assert s["glpi"]["ports"] == ["127.0.0.1:8081:80"], s["glpi"]["ports"]
assert "ports" not in s["glpi-db"], "la base ne doit pas être publiée"
for n in ("glpi", "glpi-db"):
    img = s[n]["image"]; assert ":" in img and not img.endswith(":latest"), img
assert "GLPI_DB_PASSWORD" in s["glpi"]["environment"]["GLPI_DB_PASSWORD"] and ":?" in s["glpi"]["environment"]["GLPI_DB_PASSWORD"]
assert ":?" in s["glpi-db"]["environment"]["MARIADB_ROOT_PASSWORD"]
pub = {n: v.get("ports") for n, v in base.items() if v.get("ports")}
assert list(pub) == ["caddy"], pub        # le reste de la pile n'a pas changé
assert set(g["volumes"]) == {"glpi_data", "glpi_db_data"}
EOF

echo; [ "$FAIL" = 0 ] && echo "SCRIPTS GLPI SERVEUR : TOUT EST OK" || { echo "SCRIPTS GLPI SERVEUR : DES ECHECS"; exit 1; }
