# Guide d'installation de la passerelle de supervision (serveur loué)

Ce guide installe, sur **un serveur loué**, tout ce qui reçoit les mesures et les journaux des caisses :

```
caisses (box ADSL, sortie HTTPS seule) ──HTTPS 443──►  Caddy ──► Prometheus (mesures)
                                                         │  └──► Loki       (journaux)
                                                         │
administrateurs ──(SSO ou tunnel SSH/VPN)──► Grafana ◄── Prometheus, Loki, Alertmanager
```

Rien ne contacte jamais une caisse : ce sont elles qui envoient. Aucun port à ouvrir chez le client, aucun VPN, aucune règle de pare-feu côté client.

## Ce qui a été testé, et ce qui ne l'a pas été

| Testé avec les vrais programmes (Caddy, Prometheus, Loki, Alloy, Alertmanager, Grafana) | Pas testé |
|---|---|
| Un identifiant par enseigne : création, refus sans/avec mauvais mot de passe, ajout à chaud, révocation, annulation si le rechargement échoue | Un vrai serveur loué, un vrai nom de domaine, le certificat Let's Encrypt réel (le test tourne en HTTP local) |
| Seuls 2 chemins d'envoi traversent ; lecture, interface et API d'administration répondent 404 ; limite de taille | **Docker / docker compose** : les fichiers `docker-compose*.yml` sont vérifiés (ports publiés, variables) mais jamais lancés |
| Des agents Alloy réels envoient à travers Caddy ; l'enseigne apparaît dans le journal d'accès, jamais le mot de passe | La connexion Google elle-même (SSO) : seule la configuration de Grafana est vérifiée |
| 14 règles d'alerte (tests unitaires + bout en bout), acheminement Alertmanager, tableau de bord | Les scripts `securiser-hote.sh` et `sauvegarder.sh` : logique testée avec des commandes factices, **pas sur un vrai serveur** |

Faites donc la première installation **sur un serveur de test jetable** et gardez la session SSH d'origine ouverte pendant le durcissement.

---

## 1. Choisir et commander le serveur

Un petit serveur virtuel (VPS) Linux suffit pour des milliers de caisses :

- **Système** : Ubuntu Server 24.04 LTS (ou Debian 12).
- **Taille de départ** : 4 vCPU, 8 Go de RAM, 100 Go de disque SSD. L'élément qui grossit est Prometheus (rétention 30 jours, plafonnée à 40 Go par `docker-compose.yml`) ; surveillez-le les premières semaines (§ 10).
- **Hébergeur** : n'importe quel hébergeur européen (OVHcloud, Scaleway, Hetzner…). Il faut une **adresse IPv4 fixe** et un accès **SSH par clé**. Pas besoin d'équilibreur ni de service managé.
- **Ne pas** acheter d'option « pare-feu managé qui filtre les sorties » : les caisses ont seulement besoin que 80 et 443 soient ouverts en entrée.

## 2. Noms DNS

Chez votre registrar, créez trois enregistrements **A** (et AAAA si vous avez de l'IPv6) vers l'adresse du serveur :

| Nom | Rôle |
|---|---|
| `metrics.exemple.fr` | les caisses y envoient les mesures |
| `logs.exemple.fr` | les caisses y envoient les journaux |
| `grafana.exemple.fr` | écran des administrateurs (inutile en mode privé) |

Attendez que `nslookup metrics.exemple.fr` renvoie la bonne adresse **avant** de lancer Caddy : sinon Let's Encrypt échoue et peut vous bloquer temporairement.

## 3. Première connexion et clé SSH

Depuis votre poste (Windows 11 : PowerShell ; Linux/Mac : terminal) :

```bash
ssh-keygen -t ed25519 -C "admin-passerelle"        # une seule fois, avec une phrase secrète
ssh-copy-id utilisateur@IP-DU-SERVEUR               # ou ajoutez la clé publique dans l'interface de l'hébergeu
ssh utilisateur@IP-DU-SERVEUR                       # doit fonctionner SANS mot de passe
```

Ne continuez pas tant que la connexion par clé ne marche pas : l'étape suivante désactive les mots de passe SSH.

## 4. Durcir le serveur (pare-feu, SSH, mises à jour)

Copiez le dossier `serveur/` sur le serveur (`scp -r serveur utilisateur@IP:~/supervision`), puis :

```bash
cd ~/supervision
sudo bash scripts/securiser-hote.sh              # SIMULATION : n'écrit rien, liste ce qui sera fait
sudo bash scripts/securiser-hote.sh --appliquer  # applique
```

Le script :
- **refuse de s'appliquer s'il ne trouve aucune clé SSH autorisée** (pour ne pas vous enfermer dehors) ;
- désactive les mots de passe SSH et la connexion directe de root par mot de passe (`sshd -t` avant rechargement, retrait automatique si le test échoue) ;
- configure `ufw` : tout refusé en entrée, sauf **80, 443** et **22** (limité en tentatives) ; le SSH est autorisé *avant* l'activation ;
- active les mises à jour de sécurité automatiques, la synchronisation d'horloge (chrony) et fail2ban **sur SSH uniquement**.

**Pourquoi pas de bannissement automatique sur Caddy ?** Derrière une box, tous les postes d'un magasin partagent une adresse publique, et des milliers de caisses se répartissent sur quelques centaines d'adresses. Bannir une adresse, ce serait couper un magasin entier à cause d'un seul poste mal configuré. La protection se fait par identifiant (§ 7), pas par adresse.

⚠️ **Gardez votre première session ouverte**, ouvrez-en une seconde pour vérifier que la connexion fonctionne encore, et seulement ensuite fermez la première.

Docker contourne `ufw` pour les ports qu'il publie : c'est sans conséquence ici, car `docker-compose.yml` ne publie que 80 et 443 (vérifié par un test).

## 5. Installer Docker

Suivez la documentation officielle pour votre distribution : <https://docs.docker.com/engine/install/ubuntu/> (dépôt apt de Docker, paquet `docker-ce` + `docker-compose-plugin`). Puis :

```bash
sudo usermod -aG docker $USER && newgrp docke
docker compose version
```

Le script d'installation n'est volontairement pas téléchargé automatiquement.

## 6. Configurer et démarre

```bash
cd ~/supervision
cp .env.example .env && chmod 600 .env
nano .env        # ACME_EMAIL, METRICS_DOMAIN, LOGS_DOMAIN, GRAFANA_DOMAIN, mot de passe administrateur Grafana
```

Choisissez **un** mode de protection pour Grafana (§ 8) puis lancez :

```bash
# Mode A — Grafana public, protégé par SSO (compte d'entreprise + double authentification)
docker compose -f docker-compose.yml -f docker-compose.sso.yml up -d
# Mode B — Grafana jamais public (tunnel SSH ou VPN d'administration)
docker compose -f docker-compose.yml -f docker-compose.prive.yml up -d

docker compose ps                    # les 6 services doivent être « running »
docker compose logs caddy | tail     # certificats obtenus ?
curl https://metrics.exemple.fr/healthz     # → ok
```

Sans identifiant, rien ne passe encore : c'est normal, vous n'avez créé aucune enseigne.

## 7. Un identifiant par enseigne

Chaque enseigne (chaque client) reçoit **son propre mot de passe**, partagé par toutes ses caisses. Il est conservé sous forme d'empreinte bcrypt : même vous ne pouvez plus le relire après coup.

```bash
bash scripts/enseigne-ajouter.sh nomclient     # 3 à 32 caractères : minuscules, chiffres, - ou _
```

Le script génère un mot de passe de 40 caractères, l'ajoute à `caddy/enseignes.caddy`, recharge Caddy **sans coupure** (et annule tout si le rechargement échoue), puis affiche :

```
Identifiant  : nomclient
Mot de passe : ............        <-- à noter MAINTENANT dans votre gestionnaire de mots de passe
sudo storepos-kiosk-monitoring-enroll --enseigne nomclient --magasin <MAGASIN> --poste <N> \
     --metrics-url https://metrics.exemple.fr/api/v1/write --logs-url https://logs.exemple.fr/loki/api/v1/push \
     --user nomclient              # le mot de passe est demandé (saisie masquée)
```

Exécutez cette commande **sur chaque caisse** (le paquet `storepos-kiosk-monitoring` installe l'agent). Le mot de passe est stocké en droits 0640 sur la caisse.

```bash
bash scripts/enseigne-lister.sh                # enseignes actives
bash scripts/enseigne-revoquer.sh nomclient    # coupe immédiatement cette enseigne, sans toucher aux autres
```

Pour **changer** un mot de passe : révoquer puis ajouter, puis relancer l'enrôlement sur ses caisses.

**Ce que cette protection garantit — et ses limites**
- Un inconnu ne peut rien envoyer ; lecture, interface Prometheus/Loki et API d'administration sont injoignables de l'extérieur (404).
- Une enseigne compromise se révoque seule, sans impact sur les autres.
- Limite : les étiquettes (`enseigne`, `magasin`…) sont posées par l'agent de la caisse. Une caisse malveillante de l'enseigne A pourrait **se faire passer pour l'enseigne B dans les graphiques** (mais elle n'a accès à rien d'autre). Si ce risque compte, il faudra imposer l'étiquette côté serveur (une règle par enseigne) ; ce n'est pas fait ici.
- Le mot de passe est le même sur toutes les caisses d'une enseigne : si une caisse est volée, révoquez l'enseigne et ré-enrôlez les autres.

Vérifier côté serveur qu'une caisse arrive bien :
```bash
docker compose logs caddy | grep '"user_id":"nomclient"' | tail -3
```
Dans Grafana, le tableau de bord *Santé du parc de caisses* liste les machines au bout de ~1 minute.

## 8. Protéger Grafana (jamais ouvert à tout le monde)

Les adresses des administrateurs changent : **on ne filtre donc pas par adresse IP**. Deux solutions, à choisir.

### Mode A — Public, SSO + double authentification (`docker-compose.sso.yml`)
1. Créez dans Google Cloud (*API et services › Identifiants*) un identifiant OAuth « Application Web » avec l'URI de redirection **`https://grafana.exemple.fr/login/google`**.
2. Renseignez dans `.env` : `SSO_CLIENT_ID`, `SSO_CLIENT_SECRET`, `SSO_DOMAINES` (domaines d'e-mail autorisés).
3. Dans la console d'administration Google, **imposez la validation en deux étapes** au groupe des administrateurs (c'est Google qui impose le 2FA ; Grafana libre n'en a pas).
4. `cp caddy/sites/grafana-public.caddy.exemple caddy/sites/grafana.caddy`, puis `docker compose -f docker-compose.yml -f docker-compose.sso.yml up -d` et `docker compose exec caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile`.

Vérifié avec le vrai Grafana : formulaire de mot de passe désactivé, bouton « Compte d'entreprise » présent, accès anonyme et inscription libre refusés, URI de redirection correcte. Les comptes sont créés à la première connexion, seulement pour vos domaines, avec le rôle *lecteur* ; promouvez les administrateurs à la main.
**Secours** : si Google est en panne, relancez sans `docker-compose.sso.yml` : le formulaire local (compte `GRAFANA_ADMIN_USER`) revient.

### Mode B — Jamais public (`docker-compose.prive.yml`)
Grafana n'écoute que sur `127.0.0.1:3000` du serveur ; ne créez pas `caddy/sites/grafana.caddy`. Chaque administrateur ouvre un tunnel depuis son poste :
```bash
ssh -L 3000:127.0.0.1:3000 utilisateur@IP-DU-SERVEUR     # puis http://localhost:3000
```
La clé SSH suffit, l'adresse du poste n'a aucune importance. Pour un accès plus confortable : un VPN d'administration (ex. Tailscale) installé **sur le serveur et sur les postes des administrateurs** — rien chez les clients.

Dans les deux modes : changez le mot de passe `GRAFANA_ADMIN_PASSWORD` par défaut, et ne publiez jamais Prometheus (9090), Loki (3100) ni Alertmanager (9093).

## 9. Alertes et surveillance de la passerelle elle-même

- Les 14 règles sont dans `prometheus/rules/caisses.yml` ; Alertmanager groupe les notifications et inhibe « caisse muette » quand une panne globale est probable. Le récepteur fourni (`webhook-demo`) ne fait qu'écrire dans ses journaux : **remplacez-le** par votre messagerie ou e-mail dans `alertmanager/alertmanager.yml` (puis `docker compose restart alertmanager`).
- **Qui surveille le surveillant ?** Si la passerelle tombe, personne ne reçoit d'alerte. Deux garde-fous :
  1. une sonde externe sur `https://metrics.exemple.fr/healthz` (un service gratuit type UptimeRobot, ou celui de votre hébergeur) ;
  2. la règle **Watchdog** (toujours active) : branchez le récepteur `battement` de `alertmanager.yml` sur un service de type « dead man's switch » (Healthchecks.io…) qui vous prévient quand le battement s'arrête.

## 10. Sauvegardes

```bash
bash scripts/sauvegarder.sh                      # → ./sauvegardes/passerelle-AAAAMMJJ-HHMMSS.tar (droits 600)
RETENTION=14 CHIFFRER_POUR=age1xxxxx bash scripts/sauvegarder.sh    # chiffrée avec « age » si installé
```

Elle contient : la configuration (`.env`, `enseignes.caddy`), un instantané cohérent de Prometheus, les volumes Grafana, Alertmanager, Loki et les **certificats Caddy**, puis fait tourner la rotation. Planification quotidienne :

```bash
( crontab -l 2>/dev/null; echo '30 3 * * * cd /home/UTILISATEUR/supervision && bash scripts/sauvegarder.sh >> /var/log/sauvegarde-passerelle.log 2>&1' ) | crontab -
```

⚠️ Une sauvegarde qui reste sur le même disque n'en est pas une : **copiez `sauvegardes/` hors du serveur** (autre machine, stockage objet — `rclone`, `restic`…), de préférence chiffrée, car elle contient `.env`. En complément, activez les instantanés de votre hébergeur.

**Restauration** (sur un serveur neuf après §§ 1–5) :
```bash
mkdir restauration && tar -xf passerelle-XXXX.tar -C restauration && cd restauration
mkdir ~/supervision && tar -xzf config.tar.gz -C ~/supervision
cd ~/supervision && docker compose -f docker-compose.yml -f docker-compose.<sso|prive>.yml create     # crée les volumes vides
for v in grafana_data alertmanager_data caddy_data loki_data; do
  docker run --rm -v supervision-caisses_$v:/donnees -v ~/restauration:/src alpine:3 tar -xzf /src/$v.tar.gz -C /donnees
done
docker run --rm -v supervision-caisses_prometheus_data:/prometheus -v ~/restauration:/src alpine:3 sh -c 'tar -xzf /src/prometheus.tar.gz -C /prometheus && mv /prometheus/*/* /prometheus/ 2>/dev/null; true'
docker compose -f docker-compose.yml -f docker-compose.<sso|prive>.yml up -d
```
La procédure de restauration n'a **pas** été exécutée sur un vrai Docker : faites un essai à blanc avant d'en avoir besoin. Le DNS doit pointer vers le nouveau serveur ; les caisses reprendront d'elles-mêmes (elles retentent l'envoi).

## 11. Mises à jour et exploitation

- Les versions sont figées dans `docker-compose.yml` (Caddy 2.11.7, Prometheus 3.15.0, Loki 3.7.8, Alertmanager 0.34.1, Grafana 13.2.4). Pour mettre à jour : modifier le numéro, `docker compose pull && docker compose up -d`, après une sauvegarde.
- Le système se met à jour seul pour la sécurité ; redémarrez le serveur de temps en temps (`sudo reboot`, tout redémarre : `restart: unless-stopped`).
- Surveillez le disque : `docker system df` et le tableau de bord ; Prometheus s'arrête de grossir à 40 Go (garde-fou), mais le disque doit être plus grand que la somme des volumes.
- Tests rejouables après toute modification de configuration (sur un poste Linux/WSL) : `supervision/tests/` — `validate-configs.sh`, `e2e.sh`, `e2e-passerelle.sh`, `test-grafana-protection.sh`, `test-scripts-serveur.sh`.

## 12. Dépannage rapide

| Symptôme | Piste |
|---|---|
| Caddy n'obtient pas de certificat | DNS pas encore propagé, ou ports 80/443 bloqués (`sudo ufw status`, pare-feu de l'hébergeur) |
| Une caisse reçoit 401 | mot de passe erroné ou enseigne révoquée : `enseigne-lister.sh`, puis ré-enrôler |
| Une caisse est « muette » dans Grafana | box éteinte (normal la nuit) ; sinon `sudo storepos-kiosk-monitoring-check` sur la caisse |
| Rien dans Grafana mais les caisses envoient | `docker compose logs prometheus | tail`, puis source de données dans Grafana › Connexions |
| Verrouillé hors de Grafana (SSO) | relancer sans `docker-compose.sso.yml` (§ 8) |
