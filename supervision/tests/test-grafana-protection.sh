#!/bin/bash
# Démarre le VRAI Grafana avec les variables d'environnement des deux modes de protection, lues dans les fichiers
# docker-compose livrés, et vérifie ce que Grafana en fait (formulaire local, bouton SSO, inscription libre, anonymes).
# Limite assumée : la connexion Google elle-même (échange avec Google) n'est pas testable ici.
set -u
SUP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="${SUPERVISION_TOOLS:-$HOME/supervision-test/bin}"; G="$BIN/grafana"; PORT=13050
T=$(mktemp -d); PID=""; FAIL=0
trap '[ -n "$PID" ] && kill "$PID" 2>/dev/null; sleep 1; pkill -9 -f "$T" 2>/dev/null; rm -rf "$T"' EXIT
ok() { echo "  OK    $*"; }; ko() { echo "  ECHEC $*"; FAIL=1; }
[ -x "$G/bin/grafana" ] || { echo "Grafana non installé (fetch-tools.sh)"; exit 2; }
if (exec 3<>/dev/tcp/127.0.0.1/$PORT) 2>/dev/null; then echo "Port $PORT occupé" >&2; exit 2; fi

# Variables d'environnement d'un service du compose (fusion de base + surcharge), avec ${VAR} remplacées par l'environnement
envs() { python3 -I - "$@" <<'EOF'
import sys, os, re
try:
    import yaml
except ImportError:
    sys.exit("PyYAML absent : apt install python3-yaml")
env = {}
for f in sys.argv[1:]:
    d = yaml.safe_load(open(f, encoding="utf-8"))
    e = d["services"]["grafana"].get("environment", {})
    env.update({k: str(v) for k, v in e.items()})
def sub(v): return re.sub(r"\$\{([A-Z_]+)(:?[-?][^}]*)?\}", lambda m: os.environ.get(m.group(1), (m.group(2) or "")[2:] if (m.group(2) or "")[1:2] == "-" else ""), v)
for k, v in env.items():
    if k.startswith("GF_SERVER_ROOT_URL") or True: print(f"{k}={sub(v)}")
EOF
}

lancer() {  # lancer <fichiers compose...> ; démarre Grafana avec ces variables
    [ -n "$PID" ] && { kill "$PID" 2>/dev/null; wait "$PID" 2>/dev/null; sleep 2; PID=""; }
    rm -rf "$T/g"; mkdir -p "$T/g/data" "$T/g/logs" "$T/g/plugins" "$T/g/prov"
    mapfile -t V < <(envs "$@") || return 1
    ( cd "$G" && export "${V[@]}" GF_PATHS_DATA="$T/g/data" GF_PATHS_LOGS="$T/g/logs" GF_PATHS_PLUGINS="$T/g/plugins" GF_PATHS_PROVISIONING="$T/g/prov" \
        GF_SERVER_HTTP_ADDR=127.0.0.1 GF_SERVER_HTTP_PORT=$PORT && exec ./bin/grafana server --homepath "$G" >"$T/grafana.log" 2>&1 ) &
    PID=$!
    for i in $(seq 1 60); do curl -fsS "http://127.0.0.1:$PORT/api/health" >/dev/null 2>&1 && return 0; sleep 2; done
    ko "Grafana ne démarre pas"; tail -8 "$T/grafana.log"; return 1
}
reglage() { curl -fsS -u "admin:$GRAFANA_ADMIN_PASSWORD" "http://127.0.0.1:$PORT/api/frontend/settings" | jq -r "$1"; }

export GRAFANA_ADMIN_PASSWORD=Mot-de-passe-de-test-long GRAFANA_DOMAIN=grafana.exemple.fr
export SSO_CLIENT_ID=id-test SSO_CLIENT_SECRET=secret-test SSO_DOMAINES=exemple.fr

echo "== Mode SSO (docker-compose.yml + docker-compose.sso.yml) =="
if lancer "$SUP/serveur/docker-compose.yml" "$SUP/serveur/docker-compose.sso.yml"; then
    [ "$(reglage '.disableLoginForm')" = "true" ] && ok "le formulaire de mot de passe local est désactivé" || ko "formulaire local encore actif"
    [ "$(reglage '.oauth.google.name // empty')" = "Compte d'entreprise" ] && ok "le bouton « Compte d'entreprise » (Google) est proposé" || ko "SSO Google absent ($(reglage '.oauth|keys'))"
    [ "$(reglage '.allowOrgCreate // false')" != "x" ] && true
    c=$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$PORT/login" -H 'Content-Type: application/json' -d '{"user":"admin","password":"Mot-de-passe-de-test-long"}')
    [ "$c" != "200" ] && ok "connexion par mot de passe refusée en mode SSO (HTTP $c)" || ko "connexion locale encore possible en mode SSO"
    c=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/api/dashboards/home"); [ "$c" = "401" ] && ok "accès anonyme refusé → 401" || ko "accès anonyme : $c"
    c=$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$PORT/api/user/signup" -H 'Content-Type: application/json' -d '{"email":"x@y.z","password":"azertyuiop"}'); [ "$c" != "200" ] && ok "inscription libre impossible (HTTP $c)" || ko "inscription libre possible"
    c=$(curl -s -o /dev/null -w '%{http_code}' --max-redirs 0 "http://127.0.0.1:$PORT/login/google"); [ "$c" = "302" ] && ok "/login/google redirige vers le fournisseur" || ko "/login/google : $c"
    loc=$(curl -s -o /dev/null -w '%{redirect_url}' --max-redirs 0 "http://127.0.0.1:$PORT/login/google"); echo "$loc" | grep -q 'redirect_uri=https%3A%2F%2Fgrafana.exemple.fr%2Flogin%2Fgoogle' && ok "URI de redirection = https://GRAFANA_DOMAIN/login/google (celle à déclarer chez Google)" || ko "URI de redirection inattendue : $loc"
fi

echo; echo "== Mode privé (docker-compose.yml + docker-compose.prive.yml) =="
if lancer "$SUP/serveur/docker-compose.yml" "$SUP/serveur/docker-compose.prive.yml"; then
    c=$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$PORT/login" -H 'Content-Type: application/json' -d '{"user":"admin","password":"Mot-de-passe-de-test-long"}'); [ "$c" = "200" ] && ok "connexion locale par mot de passe fonctionnelle (mode privé)" || ko "connexion locale : $c"
    [ "$(reglage '.oauth|length')" = "0" ] && ok "aucun fournisseur SSO actif" || ko "SSO actif en mode privé"
    c=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/api/dashboards/home"); [ "$c" = "401" ] && ok "accès anonyme refusé → 401" || ko "accès anonyme : $c"
fi
python3 -I - "$SUP/serveur/docker-compose.prive.yml" <<'EOF' && ok "mode privé : le port n'est publié que sur 127.0.0.1" || ko "mode privé : port publié sur toutes les interfaces"
import sys, yaml
p = yaml.safe_load(open(sys.argv[1]))["services"]["grafana"]["ports"]
assert all(x.startswith("127.0.0.1:") for x in p), p
EOF
python3 -I - "$SUP/serveur/docker-compose.yml" <<'EOF' && ok "compose de base : seuls 80 et 443 sont publiés (Prometheus, Loki, Grafana, Alertmanager restent internes)" || ko "ports publiés inattendus"
import sys, yaml
s = yaml.safe_load(open(sys.argv[1]))["services"]
pub = {n: v.get("ports") for n, v in s.items() if v.get("ports")}
assert list(pub) == ["caddy"] and sorted(pub["caddy"]) == ["443:443", "80:80"], pub
EOF
echo; [ "$FAIL" = 0 ] && echo "PROTECTION DE GRAFANA : TOUT EST OK" || { echo "PROTECTION DE GRAFANA : DES ECHECS"; exit 1; }
