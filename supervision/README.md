# Supervision des caisses avec Grafana

Supervision d'un parc de caisses **sans ouvrir aucun port chez le client** : chaque caisse envoie ses mesures et ses
journaux **en sortie**, en HTTPS, comme elle le fait déjà pour le SaaS.

```
 Caisse (magasin, derrière le routeur du client)                      Votre serveur (HTTPS)
 ┌────────────────────────────────────────────┐                      ┌─────────────────────────────┐
 │ application de caisse ─ écrit caisse.prom  │                      │ Caddy (TLS, identifiant)    │
 │ script système        ─ écrit kiosk.prom   │   mesures (HTTPS)    │   ├─ Prometheus  (stockage) │
 │ journald (storepos, lightdm, ssh)          │ ───────────────────► │   ├─ Loki        (journaux) │
 │        │                                   │   journaux (HTTPS)   │   ├─ Alertmanager (alertes) │
 │        ▼                                   │                      │   └─ Grafana     (écrans)   │
 │ Grafana Alloy (agent, en sortie seulement) │                      └─────────────────────────────┘
 └────────────────────────────────────────────┘
```

## Ce qui est testé, et ce qui ne l'est pas

Testé ici avec les **vrais composants** (Prometheus 3.15, Alloy 1.20, Loki 3.7, Alertmanager 0.34, Grafana 13.2) :

| Test | Commande | Ce qu'il prouve |
|---|---|---|
| Configurations | `bash supervision/tests/validate-configs.sh` | Prometheus, règles d'alerte, Alertmanager, Loki et Alloy acceptent les fichiers livrés |
| Alertes | `promtool test rules supervision/tests/caisses.rules.test.yml` | 6 scénarios : caisse muette, application figée, service arrêté, tickets en attente, disque plein, redémarrages en boucle |
| Agent | `bash supervision/tests/test-agent-scripts.sh` | inscription (valeurs dangereuses refusées, fichier protégé) et mesures système |
| **Bout en bout** | `bash supervision/tests/e2e.sh` | l'application écrit ses mesures → Alloy (config du paquet) → Prometheus → les 22 requêtes du tableau de bord renvoient des données → Grafana provisionne tout → une requête passe à travers Grafana |
| **Passerelle** | `bash supervision/tests/e2e-passerelle.sh` | le vrai Caddy devant Prometheus et Loki : un identifiant par enseigne (ajout à chaud, révocation), 404 sur tout sauf les 2 chemins d'envoi, 401 sans/avec mauvais mot de passe, limite de taille, de vrais agents Alloy, enseigne visible dans le journal d'accès sans mot de passe |
| **Protection de Grafana** | `bash supervision/tests/test-grafana-protection.sh` | le vrai Grafana avec les variables des fichiers `docker-compose.sso.yml` / `.prive.yml` : formulaire local désactivé ou non, SSO présent, anonymes et inscription refusés ; ports publiés |
| **Scripts serveur** | `bash supervision/tests/test-scripts-serveur.sh` | `securiser-hote.sh` (simulation, garde-fou clé SSH, ordre ufw) et `sauvegarder.sh` (contenu, rotation, échec signalé), avec des commandes factices |

**Non testé** (pas de Docker ni de droits root dans l'environnement de développement) : le lancement réel de `docker compose`,
un vrai serveur avec vrai DNS et certificat Let's Encrypt, la connexion Google du SSO, `securiser-hote.sh` et `sauvegarder.sh` sur un vrai serveur,
la procédure de restauration, l'installation du paquet `alloy` depuis le dépôt Grafana, les unités systemd et les scripts `postinst` sur une vraie caisse,
l'envoi des **journaux** depuis un vrai journald, et le comportement à grande échelle.
À valider sur un serveur de test puis 3 à 5 caisses avant tout déploiement.

## 1. Serveur (une seule fois)

**Guide pas à pas : [`serveur/GUIDE-SERVEUR.md`](serveur/GUIDE-SERVEUR.md)** (location du serveur, DNS, durcissement et pare-feu,
Docker, un identifiant par enseigne, protection de Grafana, alertes, sauvegardes/restauration, exploitation).

En résumé :
```bash
cd supervision/serveur
cp .env.example .env            # puis compléter : domaines, mot de passe Grafana
sudo bash scripts/securiser-hote.sh --appliquer      # pare-feu, SSH par clé, mises à jour (simulation par défaut)
docker compose -f docker-compose.yml -f docker-compose.sso.yml up -d     # ou docker-compose.prive.yml : Grafana jamais public
bash scripts/enseigne-ajouter.sh nomclient           # affiche la commande d'enrôlement des caisses
```
Trois noms DNS pointent vers ce serveur : `metrics.…`, `logs.…`, `grafana.…` (certificats Let's Encrypt automatiques).
Seuls les ports 80 et 443 sont publiés ; les caisses n'atteignent **que** `/api/v1/write` (mesures) et `/loki/api/v1/push` (journaux),
avec un identifiant par enseigne. Grafana : tableau de bord **Caisses → Parc de caisses**, déjà provisionné.

*Alternative sans serveur :* **Grafana Cloud** accepte le même agent. Mettez simplement l'adresse d'envoi, l'identifiant et le jeton
fournis par Grafana Cloud dans la configuration de l'agent (étape 2) ; les tableaux de bord et alertes se réimportent.

## 2. Sur chaque caisse

```bash
# Grafana Alloy (dépôt officiel de Grafana)
sudo mkdir -p /etc/apt/keyrings
wget -q -O - https://apt.grafana.com/gpg.key | gpg --dearmor | sudo tee /etc/apt/keyrings/grafana.gpg >/dev/null
echo "deb [signed-by=/etc/apt/keyrings/grafana.gpg] https://apt.grafana.com stable main" | sudo tee /etc/apt/sources.list.d/grafana.list
sudo apt update && sudo apt install alloy

# Paquet de supervision + application (version 1.3.0 ou plus : elle publie ses mesures)
sudo apt install /tmp/storepos-kiosk-monitoring_1.0.0_all.deb /tmp/caisse-demo_1.3.0_amd64.deb

# Inscription de CETTE caisse (après la restauration de l'image, jamais avant)
sudo storepos-kiosk-monitoring-enroll --enseigne LECLERC --magasin LYON-01 --poste 2 \
     --metrics-url https://metrics.exemple.fr/api/v1/write --logs-url https://logs.exemple.fr/loki/api/v1/push \
     --user agent-caisses --password '…'

sudo systemctl restart storepos          # hors ouverture du magasin : l'application publie ses mesures dès son démarrage
sudo storepos-kiosk-monitoring-check     # diagnostic : agent, fichiers de mesures, liaison avec le serveur
```
Le paquet crée l'utilisateur de l'agent, le dossier `/var/lib/storepos-kiosk/metrics`, l'unité `storepos-kiosk-alloy.service`
(durcie : lecture seule du système, aucun droit supplémentaire, **aucune télémétrie vers l'éditeur**), la minuterie des mesures
système, et un fichier d'environnement qui indique à l'application où écrire ses mesures.

**Image « ghost » :** installer le paquet avant la capture, mais **n'inscrire la caisse qu'après la restauration**. L'identifiant
machine (étiquette `machine`) vient de `/etc/machine-id` : vider ce fichier avant la capture, sinon toutes les caisses se confondent.

## Mesures publiées

| Série | Signification |
|---|---|
| `caisse_app_info{version, device_id, saas_magasin, saas_tpv}` | identité de l'application (valeur 1) |
| `caisse_app_last_write_timestamp_seconds` | l'application est vivante (sert à détecter un gel) |
| `caisse_tickets_pending` | tickets enregistrés localement, pas encore acquittés par le serveur |
| `caisse_tickets_today`, `caisse_sales_today_cents` | activité du jour (ventes, retours déduits) |
| `caisse_sync_state` | 1 en ligne, 0 hors ligne, -1 mode autonome |
| `caisse_last_sync_timestamp_seconds` | dernière synchronisation réussie |
| `caisse_catalog_products`, `caisse_catalog_customers`, `caisse_db_size_bytes`, `caisse_cashier_session` | état de la base et de la session |
| `kiosk_package_info{package, version}` | versions des paquets installés (application, kiosque, TeamViewer…) |
| `kiosk_service_restarts{unit}`, `kiosk_reboot_required`, `kiosk_usb_device_present{vendor}`, `kiosk_os_info` | santé du système et périphériques |
| `node_*` | processeur, mémoire, disque, réseau, températures, services systemd (storepos, lightdm, ssh…) |

Toutes les séries portent les étiquettes `enseigne`, `magasin`, `poste` et `machine` (ajoutées par l'agent) : on filtre et on alerte par magasin.
L'application ne publie **aucune donnée personnelle** (pas de noms de clients, pas de contenu de tickets) : uniquement des compteurs.

## Alertes (14 règles dont le « battement » Watchdog, `serveur/prometheus/rules/caisses.yml`)

| Alerte | Gravité | Déclencheur |
|---|---|---|
| CaisseMuette | critique | une caisse vue ces 24 h n'envoie plus rien depuis **20 min**, **en heures d'ouverture seulement** (une caisse éteinte la nuit n'est pas une panne ; réglable) |
| PanneGlobaleProbable | critique | plus de 30 % des caisses muettes en même temps : c'est le serveur de réception ou les identifiants, pas 30 magasins ; elle remplace alors les alertes individuelles |
| ApplicationFigee | critique | l'application n'a pas actualisé ses mesures depuis 3 min |
| ServiceCaisseArrete | critique | `storepos.service` inactif depuis 3 min |
| RedemarragesEnBoucle | critique | 4 redémarrages ou plus en 1 h |
| TicketsEnAttenteCritique | critique | plus de 200 tickets en attente depuis 15 min |
| TicketsEnAttente | avertissement | plus de 20 tickets en attente depuis 15 min |
| SynchronisationPerdue / Bloquee | avertissement | hors ligne depuis 30 min / en ligne mais plus de synchronisation depuis 15 min |
| DisquePresquePlein, MemoireElevee, TemperatureElevee | avertissement | seuils 90 % / 90 % / 85 °C |
| RedemarrageRequis | info | redémarrage en attente depuis 7 jours |

Alertmanager regroupe par magasin et **coupe les alertes secondaires** d'une caisse déjà signalée muette. Le récepteur fourni est un
webhook de démonstration : branchez votre messagerie (e-mail, Teams, Slack, GLPI par webhook) dans `serveur/alertmanager/alertmanager.yml`.

## Limites et points d'attention

- **Identifiant partagé** : une seule paire identifiant/mot de passe protège l'envoi. Prévoyez un identifiant **par enseigne** (plusieurs
  lignes dans le Caddyfile) pour pouvoir en révoquer un sans toucher aux autres. Un jeton **par caisse** demande un service d'authentification
  en plus (non fourni).
- **Dimensionnement** : un nœud Prometheus et un Loki sur fichier suffisent pour un pilote et quelques centaines de caisses. Pour 6000 caisses,
  prévoyez Mimir (ou Grafana Cloud) et un Loki distribué : calcul de capacité à faire sur le pilote, pas ici.
- **Coupure réseau** : l'agent garde les mesures sur disque quelques heures et les rejoue ; Prometheus accepte jusqu'à 30 minutes de retard.
  Au-delà, les trous sont normaux.
- **Pas de TLS entre l'application et l'agent** : ils échangent un fichier local, sans réseau.
- Ce dossier ne couvre ni les **mises à jour**, ni l'**inventaire GLPI**, ni l'**accès à distance** : étapes suivantes.

## Contenu

```
supervision/
  agent/pkg/        paquet storepos-kiosk-monitoring (config Alloy, unités systemd, scripts, drop-in de l'application)
  serveur/          pile Docker : Prometheus, Loki, Alertmanager, Grafana, Caddy + tableau de bord et alertes
  tests/            validations, tests d'alertes, test de bout en bout
```
Le tableau de bord est généré par `serveur/grafana/build_dashboard.py` (une seule définition des filtres) ;
reconstruire le paquet : `python3 tools/pkg_builder.py supervision/agent/pkg supervision/agent/dist`.
