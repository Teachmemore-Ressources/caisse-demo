# Tests de la supervision

Tout se lance depuis la racine du dépôt. Les tests utilisent les **vrais composants** (binaires téléchargés une fois dans `~/supervision-test`, sans droit root) ou, pour tout ce qui touche AWS et Docker, de fausses commandes qui enregistrent ce qu'on leur demande.

```bash
bash supervision/tests/lancer-tout.sh              # rapide : bilan OK / ECHEC par suite
bash supervision/tests/lancer-tout.sh --complet    # + bout en bout (plus long)
```

## Les suites

| Fichier | Ce qu'il prouve | Composants |
|---|---|---|
| `validate-configs.sh` | Prometheus, règles, Alertmanager, Loki et Alloy acceptent les fichiers livrés ; format des mesures ; tests d'alertes (`caisses.rules.test.yml`) | vrais |
| `caisses.rules.test.yml` | scénarios d'alertes et règles d'enregistrement (`promtool test rules`) | vrai promtool |
| `test-agent-scripts.sh` | inscription d'une caisse (valeurs dangereuses refusées), mesures système | bac à sable |
| `test-scripts-serveur.sh` | `securiser-hote.sh`, `sauvegarder.sh` | commandes factices |
| `test-glpi-serveur.sh` | scripts GLPI du serveur (sauvegarde, initialisation) | commandes factices |
| `test-sync.sh` | `passerelle-sync` : relance seulement les services dont la configuration change, protège `enseignes.caddy` | faux `aws` / `docker` |
| `test-nettoyage.sh` | `terraform/nettoyer-sans-etat.sh` (simulation, ordre, filtres) | faux `aws` |
| `validate-terraform.sh` | formatage, `init`, `validate`, scripts embarqués, taille du `user_data`, aucun secret en clair, aucun port 22 | vrai Terraform, hors ligne |
| `test-simulateur.sh` | `demo/simuler-parc.sh` | vrais Alloy et Prometheus |
| `e2e.sh` | application → Alloy (config du paquet) → Prometheus → requêtes du tableau de bord (34 panneaux) → Grafana ; aucune mesure commerciale ; ≤ 80 séries par caisse | vrais |
| `e2e-passerelle.sh` | Caddy devant Prometheus/Loki : un identifiant par enseigne (ajout, révocation), 404 hors des 2 chemins d'envoi, 401, limite de taille | vrais |
| `test-grafana-protection.sh` | modes de protection de Grafana (SSO / privé) | vrai Grafana |
| `test-rustdesk.sh` | annuaire et relais RustDesk, clé, mots de passe dérivés | vrai serveur RustDesk |
| `test-glpi.sh` | agent GLPI → faux serveur d'inventaire natif | vrai agent GLPI |

## Outils

| Fichier | Rôle |
|---|---|
| `fetch-tools.sh`, `fetch-glpi-agent.sh`, `fetch-glpi-perl.sh`, `fetch-rustdesk.sh` | téléchargent (une fois) les binaires de test |
| `generer-verrou-terraform.sh` | régénère `terraform/.terraform.lock.hcl` |
| `mesurer-series.sh` | mesure le nombre de séries envoyées par une caisse (dimensionnement) |
| `essai-visuel-grafana.sh`, `recharger-tableau-visuel.sh`, `attendre-donnees-visuel.sh` | montent un Grafana local avec des caisses simulées pour **voir** le tableau de bord |
| `essai-agent-glpi.sh`, `completer-perl-glpi.sh`, `caddy-sans-variables.sh` | essais ponctuels (GLPI, Caddy) |
| `fake_glpi_server.py`, `fake_access_proxy.py` | faux serveurs utilisés par les tests |

## Ce qui n'est pas testé ici

Un vrai `terraform apply`, un vrai Docker Compose en production, Let's Encrypt, la connexion Google (SSO), une vraie session RustDesk, un vrai serveur GLPI, la charge à 6000 caisses.
Ces points se valident sur le serveur de test AWS puis sur 3 à 5 caisses (`../guides/DEMO-CLIENT.md` § 10).
