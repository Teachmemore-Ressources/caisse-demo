# Démo client : supervision d'un parc de caisses — procédure de A à Z

Durée de la démo : **30 minutes** (25 de démonstration, 5 de questions). Préparation : prévoir **une demi-journée** la première fois, puis 30 minutes avant chaque démo.

## 0. Le message à faire passer (3 phrases)

1. **Aucun port à ouvrir, aucun VPN, aucune règle de pare-feu chez vous** : chaque caisse envoie ses informations vers notre serveur, en HTTPS, comme elle le fait déjà pour le SaaS.
2. **Une seule vue pour tout le parc** : quelles machines sont en ligne, lesquelles ont un problème (processeur, mémoire, disque, services, application), avec l'historique. **On supervise la santé, pas l'activité commerciale de vos magasins** : aucune vente, aucun ticket, aucun client ne remonte.
3. **On est prévenu avant le magasin** : les pannes remontent sans que le caissier ait à appeler.

## 1. Ce que la démo montre — et ce qu'elle ne montre pas (à dire franchement)

| On montre | On ne prétend pas |
|---|---|
| Une vraie caisse + des magasins **simulés** (préfixe `SIM-`) dans un tableau de bord unique | Que 6 000 caisses sont déjà supervisées : l'échelle n'est pas testée. Proposer un **pilote par paliers** (5, 50, 500 caisses) |
| Alertes qui se déclenchent sur panne, avec des délais volontairement tolérants (box ADSL) | Des notifications finalisées : en démo elles arrivent sur un récepteur de test. L'e-mail/Teams/Slack se branche en une demi-journée |
| Sécurité par conception : sorties seulement, un identifiant par enseigne révocable | Les mises à jour logicielles : **prochaine étape**. L'inventaire GLPI (`GUIDE-GLPI.md`) et la prise en main à distance RustDesk (`GUIDE-RUSTDESK.md`) sont préparés mais jamais éprouvés avec un vrai GLPI ni une vraie session distante : ne les montrer que s'ils ont été validés en répétition |

**Toujours annoncer que les magasins `SIM-…` sont simulés.** Ce n'est pas un détail : un client qui découvre plus tard que des caisses étaient fictives perd confiance.

## 2. Matériel et accès nécessaires

- Le serveur AWS déployé (voir `terraform/README.md`), avec `metrics.teachmemore.fr` et `logs.teachmemore.fr` qui répondent `ok`. Toutes les commandes AWS : `AWS-COMMANDES.md`.
- **Une vraie caisse** : votre kiosk, avec l'agent de supervision 1.2.1 inscrit (`storepos-kiosk-monitoring-check` sans KO) et, de préférence, l'application `caisse-demo` 1.3.3 lancée (sinon l'état de l'application manque). Les paquets sont dans `livrables/`.
- **Un ordinateur de présentation** pour afficher Grafana. Deux façons d'y accéder (Grafana n'est pas public) :
  - *recommandé pour une démo* : tunnel Systems Manager (`AWS-COMMANDES.md` § 4), puis <http://localhost:3000>. Depuis Windows, ajouter `ssh -N -L 3000:127.0.0.1:3000 kiosk@192.168.56.3` ;
  - ou passer Grafana en mode `sso` (connexion Google) pour l'ouvrir de n'importe où.
- Une connexion Internet fiable dans la salle (partage de connexion 4G en secours).
- **Plan B** : captures d'écran et enregistrement vidéo (§ 8).

**Les deux schémas** à montrer (ou garder sous la main) : [`docs/schemas/deploiements.svg`](../../docs/schemas/deploiements.svg) (comment tout se déploie) et [`docs/schemas/infrastructure.svg`](../../docs/schemas/infrastructure.svg) (ce qui tourne, où). Explications : [`docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md).

## 3. Préparation, J-7 à J-1

**J-7 — Déployer l'infrastructure (si elle n'existe pas encore).**
Suivre `terraform/README.md`. Conserver le dossier `terraform/` **avec son `terraform.tfstate`** ; sans lui, on ne peut plus détruire proprement.
Cloudflare : enregistrements `metrics` et `logs` en **DNS only (nuage gris)**.
Test : `curl https://metrics.teachmemore.fr/healthz` → `ok`.

**J-5 — Créer l'enseigne de démo** (dans la session sur le serveur) :
```bash
aws ssm start-session --region eu-west-3 --target $I      # $I : voir AWS-COMMANDES.md § 0
cd /opt/passerelle && sudo bash scripts/enseigne-ajouter.sh demo
```
Noter le mot de passe. En cas de repasse, révoquer d'abord (`enseigne-revoquer.sh demo`).

**J-5 — Inscrire la vraie caisse** (kiosk) : installer Alloy, puis `livrables/storepos-kiosk-monitoring_1.2.1_all.deb`, puis `storepos-kiosk-monitoring-enroll` (commande affichée par `enseigne-ajouter.sh`) et vérifier :
```bash
sudo storepos-kiosk-monitoring-check        # aucun [ KO ]
```

**J-3 — Lancer l'application de caisse** sur le kiosk et faire quelques ventes : l'état de l'application (vivante ou figée, synchronisation, tickets en attente non transmis) ne s'alimente que si le fichier `caisse.prom` est écrit (`check` : « caisse.prom à jour »).

**J-4 — Inventaire GLPI (si vous le montrez)** : suivre `GUIDE-GLPI.md` (DNS en nuage gris, `glpi-initialiser.sh`, agent sur le kiosk, `sudo glpi-agent --force`) et vérifier que la caisse apparaît dans **Parc › Ordinateurs**. Ne montrer GLPI que si cette répétition a réussi.

**J-2 — Générer le parc simulé** (kiosk) : les magasins simulés prennent **l'enseigne de la caisse inscrite** (ici celle de `/etc/default/storepos-kiosk-monitoring`, par exemple `client-lyon`), pas forcément `demo`. Depuis `~/caisse-demo/supervision/demo` :
```bash
sudo bash simuler-parc.sh demarrer 6        # 6 magasins fictifs : SIM-PARIS-01, SIM-PARIS-02, SIM-NANTES-01…
sudo bash simuler-parc.sh etat              # demande le mot de passe sudo du kiosk (à taper vous-même)
```
Les caisses simulées sont de vrais agents qui envoient des mesures fictives ; leurs courbes processeur/mémoire sont celles du kiosk, donc **le détail d'une caisse se montre sur la vraie**. Le simulateur dépend du kiosk allumé : le laisser tourner jusqu'à la démo.

**J-1 — Répéter la démo en entier**, chronomètre en main (§ 5). Vérifier en particulier :
- le tunnel Grafana depuis l'ordinateur de présentation ;
- le tableau de bord *Caisses › Santé du parc de caisses* (34 panneaux, mise à jour : `AWS-COMMANDES.md` § 6) : 7 machines en ligne (1 réelle + 6 simulées), la situation par enseigne, les « 15 pires » processeur/mémoire/disque ;
- le délai réel des alertes (§ 6) : notez-le, ne comptez pas sur mes estimations ;
- que les heures de la démo tombent dans la fenêtre d'ouverture des alertes (6 h–19 h UTC) si vous voulez montrer « caisse muette ».

## 4. Juste avant la démo (30 minutes)

0. **Relancer Grafana s'il a été arrêté** (il l'est si on l'a coupé pour économiser) :
   ```bash
   cd ~/caisse-demo && bash tools/ssm-executer.sh - <<'EOF'
   cd /opt/passerelle && docker compose start grafana && docker compose ps --format '{{.Service}}: {{.Status}}'
   EOF
   ```
   Tous les services doivent être `Up` (GLPI et sa base aussi, si activé). Le serveur doit répondre : `curl https://metrics.teachmemore.fr/healthz` → `ok`.

1. **Ouvrir le tunnel** Grafana et se connecter (compte `admin`, mot de passe : `aws ssm get-parameter --region eu-west-3 --name /passerelle-caisses/grafana_admin_password --with-decryption --query Parameter.Value --output text`).
2. **Armer un incident** pour qu'une alerte soit déjà visible pendant la démo (certaines alertes demandent 15 à 30 minutes) :
   ```bash
   sudo bash simuler-parc.sh incident 2 attente      # SIM-PARIS-02 : tickets bloqués -> « Tickets en attente »
   sudo bash simuler-parc.sh incident 3 horsligne    # SIM-NANTES-01 : hors ligne -> « Hors ligne (serveur SaaS) »
   ```
   L'incident apparaît tout de suite sur les panneaux ; l'alerte passe en « firing » après **15 min** (tickets) ou **30 min** (hors ligne).
3. Ouvrir dans des onglets : le tableau de bord ; la vue des alertes de Grafana (*Alerting*) ; une session sur le serveur (`docker compose logs webhook-demo`) pour montrer l'alerte envoyée.
4. Mettre la plage de temps du tableau de bord sur **Dernières 3 heures** et activer l'actualisation toutes les **30 s**.

## 5. Déroulé de la démo (25 min)

| Min | Scène | Ce qu'on fait / dit |
|---|---|---|
| 0-3 | **Le problème** | « 6 000 caisses, derrière des box que vous ne contrôlez pas. Comment savoir lesquelles ont un souci, sans appel du magasin ? » |
| 3-6 | **L'architecture** (schéma §0) | Pas d'entrée chez vous. La caisse envoie, le serveur reçoit. Un identifiant par enseigne, révocable en une commande. |
| 6-11 | **Vue d'ensemble du parc** | Ouvrir *Santé du parc de caisses*. Désigner : caisses en ligne, muettes, machines à traiter (processeur, mémoire, disque), la situation par enseigne, les versions de l'application. Annoncer : « les magasins SIM- sont simulés ». |
| 11-15 | **Une caisse en détail** | Choisir la vraie machine (filtre « machine »). Processeur, mémoire, disque, services (état vert/rouge), journaux de la caisse. Montrer la courbe du processeur, de la mémoire et du disque de cette caisse. |
| 15-19 | **Les incidents** | Montrer SIM-PARIS-02 (tickets bloqués) et SIM-NANTES-01 (hors ligne) dans les tableaux « à surveiller ». Expliquer : « la caisse continue de vendre hors ligne, mais on voit les tickets qui s'accumulent ». Montrer l'alerte déjà déclenchée et sa notification dans les journaux du récepteur. *Si le temps le permet, panne en direct :* sur le kiosk `sudo systemctl stop storepos` (le service passe rouge, l'alerte suit après quelques minutes), puis `sudo systemctl start storepos`. |
| 19-22 | **L'inventaire (GLPI)** (si validé en répétition) | Tunnel vers GLPI (`AWS-COMMANDES.md` § 4), **Parc › Ordinateurs** : la caisse, son matériel, son système, ses logiciels, son étiquette enseigne/magasin/poste. « Grafana dit si ça marche, GLPI dit ce que c'est. » Rien n'est ouvert chez le client : l'agent envoie, comme pour la supervision. |
| 22-25 | **Sécurité et suite** | Sorties seulement, TLS, aucune donnée de paiement ni de client (uniquement des mesures techniques), identifiants révocables. Proposer le **pilote** et les prochaines étapes (mises à jour, inventaire, accès distant). |

## 6. Combien de temps pour chaque alerte (à vérifier en répétition)

Délais configurés dans `prometheus/rules/caisses.yml` (+ ~30 s de regroupement d'Alertmanager + l'intervalle de collecte de 60 s) :

| Scénario | Action | Alerte après environ |
|---|---|---|
| Service arrêté | `sudo systemctl stop storepos` | 3 min |
| Application figée / arrêtée | l'application n'écrit plus ses mesures | 3 min (inactivité) + 2 min |
| Tickets bloqués | `incident N attente` | 15 min |
| Synchronisation bloquée | en ligne mais plus de synchronisation | 10 min (perdue, hors ligne : 30 min) |
| Caisse hors ligne | `incident N horsligne` | 30 min |
| Caisse muette | `silence N` (arrête l'agent) | 20 min, **seulement entre 6 h et 19 h UTC** |
| Processeur saturé / disque lent | charge réelle de la machine | 15 min |
| Panne d'une enseigne | plus de 30 % de ses caisses muettes (enseigne de 5 caisses ou plus) | 10 min, heures d'ouverture (remplace les alertes individuelles) |

Seules les deux premières sont réalistes en direct ; **les autres se préparent à l'avance** (§4).
Où voir une alerte : dans Grafana (*Alerting*, si la liste des règles de la source Prometheus s'affiche, à vérifier) ou dans les journaux du récepteur de test :
```bash
sudo docker compose -f /opt/passerelle/docker-compose.yml logs webhook-demo --no-log-prefix | tail -n 20
```
Les notifications partent aujourd'hui vers ce récepteur de démonstration. Pour montrer un **e-mail** ou un message Teams/Slack, il faut configurer un récepteur dans `alertmanager/alertmanager.yml` (non fait ici).

## 7. Questions fréquentes

- **« Ça marche avec 6 000 caisses ? »** Honnêtement : non testé à cette échelle. L'architecture l'autorise (agent léger, séries réduites), mais on le valide par paliers sur un pilote.
- **« Qu'est-ce qui est collecté ? »** Des mesures techniques (processeur, mémoire, disque, état des services, version de l'application, nombre de tickets en attente d'envoi) et les journaux techniques du poste. **Aucune vente, aucun montant, aucun catalogue, aucune donnée de carte ni de client** : l'agent n'envoie que ce qui figure sur une liste blanche.
- **« Et si Internet tombe dans un magasin ? »** La caisse continue de vendre. L'agent garde les mesures quelques heures et les rejoue au retour du réseau ; si la coupure dure, on le voit (tickets en attente, caisse muette).
- **« Et si le serveur de supervision tombe ? »** Les caisses ne sont pas affectées. AWS relance le serveur en cas de panne matérielle ; une sonde externe et un « battement » surveillent le serveur lui-même (à brancher).
- **« Qui peut voir Grafana ? »** Aujourd'hui : seulement via un tunnel authentifié. En production : connexion Google avec double authentification ou VPN d'administration.
- **« Combien ça coûte ? »** Serveur d'essai ≈ 35-40 €/mois ; la production demande un serveur plus gros (≈ 150 €/mois, à confirmer sur la grille AWS) plus l'exploitation.

## 8. Plan B (si le réseau ou le tunnel lâche)

Avant la démo : **captures d'écran** du tableau de bord en pleine charge (vue d'ensemble, détail d'une caisse, alerte) et une **vidéo de 3 minutes** du déroulé. Si le tunnel ne s'ouvre pas : relancer la commande ; sinon passer sur la vidéo et dire simplement que la démo en direct reprendra en fin de réunion.

## 9. Après la démo

1. Arrêter les caisses simulées (sinon elles restent visibles) : `sudo bash simuler-parc.sh arreter`.
2. **Changer ou révoquer le mot de passe de l'enseigne `demo`** s'il a été montré à l'écran (`enseigne-revoquer.sh demo`).
3. Si Grafana n'est plus utile (économie, GLPI seul) : `docker compose stop grafana` (`AWS-COMMANDES.md` § 6b). Attention, `passerelle-sync` le relance.
4. Si le serveur ne sert plus : `terraform destroy`, ou `bash terraform/nettoyer-sans-etat.sh` si l'état a été perdu (simulation d'abord), puis supprimer les enregistrements DNS. **Le serveur d'essai coûte environ 1,2 € par jour** tant qu'il tourne.

## 10. Ce qui n'a pas été vérifié (à contrôler en répétition)

- Le simulateur et les incidents ont été testés avec les vrais Alloy et Prometheus, mais pas à travers la passerelle AWS : à lancer une fois sur le kiosk (J-2).
- **GLPI** : installation, initialisation, connexion et remontée de la caisse constatées le 20 octobre 2026 sur le serveur de test ; sauvegarde/restauration et mise à jour de GLPI non éprouvées.
- **RustDesk** (accès distant) : serveur testé en local ; pas de vraie session de prise en main sur une caisse. Ne pas le promettre en démo sans répétition.
- L'affichage des alertes dans l'interface de Grafana et les délais réels de bout en bout.
- L'application de caisse écrivant `caisse.prom` sur le kiosk (chemin et droits).
- Le tunnel Systems Manager depuis un autre ordinateur que le kiosk.
- La tenue dans le temps (plusieurs heures) du simulateur sur le kiosk.
