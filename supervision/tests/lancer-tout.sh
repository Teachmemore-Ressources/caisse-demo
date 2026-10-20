#!/bin/bash
#
# Lance les tests de la supervision et affiche un bilan.
#   bash supervision/tests/lancer-tout.sh            tests rapides (quelques minutes) : configurations, alertes, agent, scripts, Terraform, synchronisation
#   bash supervision/tests/lancer-tout.sh --complet  + bout en bout avec les vrais composants (Prometheus, Alloy, Loki, Grafana, Caddy, RustDesk) : plus long
# Premier lancement : les binaires de test sont téléchargés une fois dans ~/supervision-test (aucun droit root).
set -u
cd "$(dirname "${BASH_SOURCE[0]}")" || exit 1
COMPLET=0; [ "${1:-}" = "--complet" ] && COMPLET=1

bash fetch-tools.sh >/dev/null 2>&1 || { echo "Téléchargement des outils de test impossible (réseau ?) : bash supervision/tests/fetch-tools.sh"; exit 1; }

ECHECS=(); N=0
lancer() { # nom, commande...
    local nom="$1"; shift; N=$((N+1))
    printf '%-52s ' "$nom"
    if "$@" >"/tmp/test-$N.log" 2>&1; then echo "OK"; else echo "ECHEC  (détail : /tmp/test-$N.log)"; ECHECS+=("$nom"); fi
}

lancer "Configurations (Prometheus, Alertmanager, Loki…)" bash validate-configs.sh
lancer "Scripts de l'agent (inscription, mesures)"        bash test-agent-scripts.sh
lancer "Scripts du serveur (pare-feu, sauvegardes)"       bash test-scripts-serveur.sh
lancer "Scripts GLPI du serveur"                          bash test-glpi-serveur.sh
lancer "Synchronisation S3 -> serveur (relances ciblées)" bash test-sync.sh
lancer "Nettoyage sans état Terraform"                    bash test-nettoyage.sh
lancer "Terraform (hors ligne : fmt, validate, sécurité)" bash validate-terraform.sh
lancer "Simulateur de parc de la démo"                    bash test-simulateur.sh

if [ "$COMPLET" = 1 ]; then
    lancer "Bout en bout (agent -> Prometheus -> Grafana)"    bash e2e.sh
    lancer "Passerelle (Caddy, identifiants par enseigne)"    bash e2e-passerelle.sh
    lancer "Protection de Grafana (SSO / privé)"              bash test-grafana-protection.sh
    lancer "RustDesk (serveur réel)"                          bash test-rustdesk.sh
fi

echo
if [ "${#ECHECS[@]}" = 0 ]; then echo "TOUS LES TESTS PASSENT ($N)"; else echo "${#ECHECS[@]} ÉCHEC(S) sur $N :"; printf '  - %s\n' "${ECHECS[@]}"; exit 1; fi
