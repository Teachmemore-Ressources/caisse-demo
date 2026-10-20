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
| Alertes | `promtool test rules supervision/tests/caisses.rules.test.yml` | caisse muette, application figée, service arrêté, tickets en attente, disque plein, redémarrages en boucle, processeur saturé, panne d'enseigne, règles d'enregistrement |
| Agent | `bash supervision/tests/test-agent-scripts.sh` | inscription (valeurs dangereuses refusées, fichier protégé) et mesures système |
| **Bout en bout** | `bash supervision/tests/e2e.sh` | l'application écrit ses mesures → Alloy (config du paquet) → Prometheus → les requêtes du tableau de bord (34 panneaux) renvoient des données → Grafana provisionne tout → une requête passe à travers Grafana |
| **Passerelle** | `bash supervision/tests/e2e-passerelle.sh` | le vrai Caddy devant Prometheus et Loki : un identifiant par enseigne (ajout à chaud, révocation), 404 sur tout sauf les 2 chemins d'envoi, 401 sans/avec mauvais mot de passe, limite de taille, de vrais agents Alloy, enseigne visible dans le journal d'accès sans mot de passe |
| **Protection de Grafana** | `bash supervision/tests/test-grafana-protection.sh` | le vrai Grafana avec les variables des fichiers `docker-compose.sso.yml` / `.prive.yml` : formulaire local désactivé ou non, SSO présent, anonymes et inscription refusés ; ports publiés |
| **Scripts serveur** | `bash supervision/tests/test-scripts-serveur.sh` | `securiser-hote.sh` (simulation, garde-fou clé SSH, ordre ufw) et `sauvegarder.sh` (contenu, rotation, échec signalé), avec des commandes factices |
| **Synchronisation** | `bash supervision/tests/test-sync.sh` | `passerelle-sync` : relance seulement Prometheus / Alertmanager / Loki / Grafana quand *leurs* fichiers changent, protège les identifiants des enseignes |

Tout d'un coup : `bash supervision/tests/lancer-tout.sh` (ajouter `--complet` pour les tests de bout en bout). Détail : [`tests/README.md`](tests/README.md).

**Non testé** (pas de Docker ni de droits root dans l'environnement de développement) : le lancement réel de `docker compose`,
un vrai serveur avec vrai DNS et certificat Let's Encrypt, la connexion Google du SSO, `securiser-hote.sh` et `sauvegarder.sh` sur un vrai serveur,
la procédure de restauration, l'installation du paquet `alloy` depuis le dépôt Grafana, les unités systemd et les scripts `postinst` sur une vraie caisse,
l'envoi des **journaux** depuis un vrai journald, et le comportement à grande échelle.
À valider sur un serveur de test puis 3 à 5 caisses avant tout déploiement.

## 1. Serveur (une seule fois)

**Guide pas à pas : [`guides/GUIDE-SERVEUR.md`](guides/GUIDE-SERVEUR.md)** (déploiement automatisé sur AWS : [`terraform/README.md`](terraform/README.md)) (location du serveur, DNS, durcissement et pare-feu,
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
avec un identifiant par enseigne. Grafana : tableau de bord **Caisses → Santé du parc de caisses**, déjà provisionné.

*Alternative sans serveur :* **Grafana Cloud** accepte le même agent. Mettez simplement l'adresse d'envoi, l'identifiant et le jeton
fournis par Grafana Cloud dans la configuration de l'agent (étape 2) ; les tableaux de bord et alertes se réimportent.

## 2. Sur chaque caisse

```bash
# Grafana Alloy (dépôt officiel de Grafana)
sudo mkdir -p /etc/apt/keyrings
wget -q -O - https://apt.grafana.com/gpg.key | gpg --dearmor | sudo tee /etc/apt/keyrings/grafana.gpg >/dev/null
echo "deb [signed-by=/etc/apt/keyrings/grafana.gpg] https://apt.grafana.com stable main" | sudo tee /etc/apt/sources.list.d/grafana.list
sudo apt update && sudo apt install alloy

# Paquet de supervision + application (1.3.0 ou plus : elle publie son état)
sudo apt install ./storepos-kiosk-monitoring_1.2.1_all.deb ./caisse-demo_1.3.3_amd64.deb      # dans livrables/

# Inscription de CETTE caisse (après la restauration de l'image, jamais avant)
sudo storepos-kiosk-monitoring-enroll --enseigne LECLERC --magasin LYON-01 --poste 2 \
     --metrics-url https://metrics.exemple.fr/api/v1/write --logs-url https://logs.exemple.fr/loki/api/v1/push \
     --user agent-caisses                 # le mot de passe est demandé (saisie masquée) ; ou --password-file

sudo systemctl restart storepos          # hors ouverture du magasin : l'application publie ses mesures dès son démarrage
sudo storepos-kiosk-monitoring-check     # diagnostic : agent, fichiers de mesures, liaison avec le serveur
```
Le paquet crée l'utilisateur de l'agent, le dossier `/var/lib/storepos-kiosk/metrics`, l'unité `storepos-kiosk-alloy.service`
(durcie : lecture seule du système, aucun droit supplémentaire, **aucune télémétrie vers l'éditeur**), la minuterie des mesures
système, et un fichier d'environnement qui indique à l'application où écrire ses mesures.

**Image « ghost » :** installer le paquet avant la capture, mais **n'inscrire la caisse qu'après la restauration**. L'identifiant
machine (étiquette `machine`) vient de `/etc/machine-id` : vider ce fichier avant la capture, sinon toutes les caisses se confondent.

## Enregistrement des caisses : comment ça marche, ce qui est vérifié, ce qui manque

**Le parcours.**
1. Sur le serveur, `enseigne-ajouter.sh nomclient` crée un identifiant par enseigne (mot de passe aléatoire affiché une fois ; seule son empreinte bcrypt est conservée dans `caddy/enseignes.caddy`).
2. Sur chaque caisse, `storepos-kiosk-monitoring-enroll` écrit les réglages de l'agent (enseigne, magasin, poste, identifiant machine). **Depuis la 1.2.1 il teste d'abord l'identifiant auprès du serveur** : mot de passe faux ou adresse incorrecte = message clair et *rien n'est écrit* ; serveur injoignable = avertissement, l'installation hors ligne reste possible (`--no-verify`). Le mot de passe se saisit au clavier ou vient d'un fichier (`--password-file`), plus sur la ligne de commande.
3. L'agent envoie ses mesures ; Caddy contrôle l'identifiant, Prometheus stocke. **La caisse apparaît seule dans Grafana** (pas de fiche à créer côté serveur).
4. Le serveur tient un inventaire des caisses *connues* (`caisse:connue` : vues au moins une fois ces 7 derniers jours) et alerte quand l'une d'elles se tait en heures d'ouverture. Avant la 1.2.1 la fenêtre n'était que de 24 h : une caisse tombée en panne le samedi soir dans un magasin fermé le dimanche n'était **pas** signalée le lundi (corrigé, testé). Contrepartie : une caisse retirée du parc alerte encore jusqu'à 7 jours, à masquer par un silence Alertmanager.

**Limites connues (à connaître avant de promettre quoi que ce soit au client).**

| Limite | Conséquence | Piste |
|---|---|---|
| Les étiquettes `enseigne`, `magasin`, `poste` sont **déclarées par la caisse** | l'identifiant d'une enseigne pourrait écrire sous le nom d'une autre (erreur ou malveillance) ; le journal d'accès Caddy garde l'enseigne réellement authentifiée, mais Grafana ne la contrôle pas | isolation par locataire : Caddy pose l'enseigne authentifiée dans l'en-tête `X-Scope-OrgID`, que Loki et Mimir savent exploiter (Prometheus seul non) |
| **Un mot de passe par enseigne**, partagé par toutes ses caisses | une caisse volée = changer le mot de passe de toute l'enseigne | jeton d'enrôlement à usage limité qui s'échange contre un identifiant **par caisse** (service à écrire) |
| L'identifiant machine vient de `/etc/machine-id` (12 premiers caractères) | deux caisses clonées sans vider ce fichier se confondent : leurs séries se mélangent, sans alerte | vider `/etc/machine-id` avant la capture de l'image, puis inscrire **après** la restauration |
| Pas de fiche de caisse côté serveur | on ne sait pas qu'une caisse « devrait » exister si elle n'a jamais envoyé | rapprocher de l'inventaire GLPI (`guides/GUIDE-GLPI.md`) ou d'une liste fournie par le client |
| Pas de configuration distante | changer l'agent = redéployer le paquet | Grafana Fleet Management / `remotecfg` d'Alloy (voir ci-dessous) |

**Comment font les solutions de supervision de parc que j'ai pu consulter** (documentation publique des éditeurs ; je n'ai pas pu examiner vos concurrents directs dans la caisse, que je ne connais pas) :

| | Notre passerelle | [Grafana Fleet Management](https://grafana.com/docs/grafana-cloud/send-data/fleet-management/set-up/onboard-collectors/standalone-alloy.md) | [Elastic Fleet](https://www.elastic.co/docs/reference/fleet/fleet-enrollment-tokens) | [Datadog Fleet Automation](https://docs.datadoghq.com/agent/fleet_automation/remote_management) |
|---|---|---|---|---|
| Secret d'inscription | mot de passe par enseigne | jeton d'accès au périmètre limité, bloc `remotecfg` dans Alloy | jeton d'enrôlement rattaché à **une politique** | clé d'API passée à l'installation |
| Identité par appareil | étiquettes déclarées + identifiant machine | identifiant du collecteur (`id`) | **clé de communication propre à chaque agent**, émise après l'enrôlement | non vérifié |
| Inventaire central | déduit des mesures reçues | oui (onglet Inventory) | oui | oui |
| Configuration à distance | non | oui, sans redémarrage | oui (politique) | partielle (pas les secrets) |
| Révocation | par enseigne | par jeton | du jeton : bloque les **nouvelles** inscriptions (les agents déjà inscrits continuent, d'après la documentation) | non vérifié |

Le point commun : un secret *d'amorçage* partagé, puis une identité propre à chaque appareil et un inventaire central. C'est exactement ce qui manque ici, et c'est l'évolution à prévoir pour 6000 caisses de clients différents. Elle n'est **pas** faite : elle demande un petit service d'enrôlement et un choix de stockage (Mimir ou VictoriaMetrics) à valider par un essai.

## Mesures publiées

| Série | Signification |
|---|---|
| `up` | la caisse envoie ses mesures (sert à détecter une caisse « muette ») |
| `node_cpu_seconds_total{mode="idle"\|"iowait"}` | processeur : utilisation et attente du disque (par cœur) |
| `node_load1`, `node_load5`, `node_load15` | charge du système |
| `node_memory_{MemTotal,MemAvailable,SwapTotal,SwapFree}_bytes` | mémoire et espace d'échange |
| `node_filesystem_{avail,size}_bytes{mountpoint="/"}` | disque système (la partition `/` seulement) |
| `node_boot_time_seconds`, `node_time_seconds` | durée depuis le dernier démarrage |
| `node_systemd_unit_state{state="active"}` | services : storepos, lightdm, ssh, TeamViewer, RustDesk, agent GLPI, agent de supervision |
| `node_hwmon_temp_celsius` | températures |
| `node_uname_info`, `node_os_info`, `node_textfile_scrape_error` | noyau, système, bon fonctionnement de la collecte |
| `caisse_app_info{version, device_id, saas_magasin, saas_tpv}` | identité de l'application (valeur 1) |
| `caisse_app_last_write_timestamp_seconds`, `caisse_app_start_time_seconds` | l'application est vivante (gel) ; redémarrages de l'application |
| `caisse_tickets_pending` | tickets enregistrés localement, pas encore acquittés par le serveur |
| `caisse_sync_state`, `caisse_last_sync_timestamp_seconds` | 1 en ligne, 0 hors ligne, -1 mode autonome ; dernière synchronisation réussie |
| `caisse_db_size_bytes` | taille de la base locale |
| `kiosk_package_info`, `kiosk_service_restarts`, `kiosk_reboot_required`, `kiosk_usb_device_present`, `kiosk_os_info` | versions des paquets, plantages en boucle, redémarrage en attente, périphériques USB |

Toutes les séries portent les étiquettes `enseigne`, `magasin`, `poste` et `machine` (ajoutées par l'agent) : on filtre et on alerte par enseigne ou par magasin.

**Santé seulement, par conception.** L'agent applique une **liste blanche** : tout ce qui n'est pas dans le tableau ci-dessus n'est pas envoyé. En particulier,
l'**activité commerciale de vos clients** (tickets et ventes du jour, catalogue, session du caissier) n'est **jamais remontée**, même si l'application la calcule,
et le trafic réseau ou les statistiques par disque sont écartés (ils pèsent lourd à 6000 caisses sans dire rien de la santé).
Pour ajouter une métrique, la déclarer dans `agent/pkg/etc/storepos-kiosk/alloy/10-metriques.alloy` (règle « keep »).
L'application ne publie **aucune donnée personnelle** (pas de noms de clients, pas de contenu de tickets) : uniquement des compteurs.

## Dimensionnement : 6000 caisses

Mesuré avec le vrai agent (voir `tests/mesurer-series.sh`) : **environ 45 séries par caisse** de 8 cœurs (moins sur une caisse de 2 à 4 cœurs : chaque cœur ajoute 2 séries),
contre 350 avant la liste blanche. Le serveur ajoute 6 séries calculées par caisse (règles `caisse:…`, voir `serveur/prometheus/rules/enregistrements.yml`).

| | Par caisse | 6000 caisses |
|---|---|---|
| Séries actives (mesure) | 45 + 6 calculées | ≈ 300 000 |
| Échantillons reçus (1 par minute) | ≈ 51 / min | ≈ 5 000 / s, soit ≈ 100 envois HTTPS / s |
| Mémoire de Prometheus (estimation : 3 à 4 Ko par série) | | ≈ 1 à 1,5 Go |
| Disque, 30 jours (estimation : 1 à 2 octets par échantillon) | | ≈ 15 à 25 Go (plafonné à 40 Go par `docker-compose.yml`) |

**Ce qui est mesuré** : le nombre de séries. **Ce qui est estimé, pas mesuré** : la mémoire, le disque et le débit à 6000 caisses (d'après des ordres de grandeur courants pour Prometheus). Aucun essai de charge n'a été fait.
À prévoir avant un déploiement complet : un essai de charge (envoyer des mesures de 6000 caisses simulées), puis un déploiement par paliers (50, 500, 2000, 6000) en surveillant la mémoire, le disque et la latence d'envoi de Prometheus.
Si un seul Prometheus ne suffit plus, deux pistes : séparer les enseignes sur plusieurs Prometheus, ou passer à un stockage conçu pour ce volume (VictoriaMetrics en un seul exécutable, ou Mimir), compatibles avec le même agent.

## Alertes (17 règles dont le « battement » Watchdog, `serveur/prometheus/rules/caisses.yml`)

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
| PanneEnseigne | critique | plus de 30 % des caisses d'une enseigne (d'au moins 5 caisses) muettes depuis 10 min, en heures d'ouverture : remplace les alertes individuelles de cette enseigne |
| DisquePresquePlein, MemoireElevee, TemperatureElevee | avertissement | seuils 90 % / 90 % / 85 °C |
| ProcesseurSature, DisqueLent | avertissement | processeur saturé / attente disque élevée depuis 15 min |
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
- Ce dossier ne couvre pas les **mises à jour logicielles** des caisses : étape suivante. L'inventaire GLPI et l'accès à distance ont leurs guides.

## Contenu

```
supervision/
  guides/           DEMO-CLIENT (démo de A à Z), AWS-COMMANDES, GUIDE-SERVEUR, GUIDE-GLPI, GUIDE-RUSTDESK
  serveur/          pile Docker : Prometheus, Loki, Alertmanager, Grafana, Caddy + tableau de bord et alertes
  terraform/        déploiement AWS de la pile (README.md)
  agent/pkg/        paquet storepos-kiosk-monitoring (config Alloy, unités systemd, scripts, drop-in de l'application)
  remote-agent/     paquet RustDesk ; glpi-agent/ : paquet GLPI
  demo/             simulateur de parc (magasins fictifs « SIM- ») pour la démo
  tests/            validations, tests d'alertes, bout en bout (README.md)
```
Le tableau de bord est généré par `serveur/grafana/build_dashboard.py` (une seule définition des filtres) ;
reconstruire le paquet : `python3 tools/pkg_builder.py supervision/agent/pkg supervision/agent/dist`, puis copier le `.deb` dans `livrables/`.

## Guides

- [`guides/DEMO-CLIENT.md`](guides/DEMO-CLIENT.md) : procédure de démonstration, de A à Z.
- [`guides/AWS-COMMANDES.md`](guides/AWS-COMMANDES.md) : toutes les commandes AWS (session, tunnel, mise à jour, secrets, sauvegardes, coûts, dépannage).
- [`guides/GUIDE-SERVEUR.md`](guides/GUIDE-SERVEUR.md) : installation pas à pas de la passerelle, à la main ; [`terraform/README.md`](terraform/README.md) : la même chose sur AWS.
- [`guides/GUIDE-GLPI.md`](guides/GUIDE-GLPI.md) : inventaire du parc avec GLPI.
- [`guides/GUIDE-RUSTDESK.md`](guides/GUIDE-RUSTDESK.md) : prise en main à distance des caisses (RustDesk), sans SSH ni port ouvert chez le client.
