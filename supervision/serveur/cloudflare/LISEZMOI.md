# Rendre la supervision joignable par Cloudflare (sans ouvrir de port)

> Non testé ici : il n'y a pas de compte Cloudflare dans l'environnement de développement. Les noms d'écrans et de réglages de
> Cloudflare changent : suivez-les avec la documentation officielle de **Cloudflare Tunnel** et de **Cloudflare Access**.
> Ce qui est testé : l'agent de la caisse envoie bien les en-têtes du jeton de service, et l'envoi est refusé sans eux
> (`bash supervision/tests/e2e.sh`, étape 7b, avec un proxy qui imite Access).

## Principe

```
Caisse ──HTTPS 443──► Cloudflare (Access vérifie le jeton) ──tunnel sortant──► cloudflared ──► Prometheus / Loki / Grafana
                                                                                  (conteneur chez vous, réseau local)
```
- Les caisses n'atteignent que **deux chemins d'envoi**. Tout le reste répond 404.
- Votre équipe se connecte à Grafana par **Cloudflare Access** (e-mail, Google, etc.), puis par le login Grafana.
- Les certificats HTTPS sont gérés par Cloudflare : rien à renouveler.

## 0. Avant de commencer : vérifier les conteneurs

**Prometheus** doit accepter les mesures poussées. Dans sa commande de démarrage :
```
--web.enable-remote-write-receiver
```
Et dans `prometheus.yml` (accepte les mesures en retard après une coupure réseau) :
```yaml
storage:
  tsdb:
    out_of_order_time_window: 30m
```
Contrôle : `docker inspect <prometheus> --format '{{.Args}}'` doit contenir `--web.enable-remote-write-receiver`.

**Loki** reçoit sur `/loki/api/v1/push` (rien de spécial). **Aucun des deux ne doit publier de port sur Internet** : ils ne sont joints que par `cloudflared`.

## 1. Créer le tunnel (tableau de bord Cloudflare)

Zero Trust → Networks → **Tunnels** → Create a tunnel → *Cloudflared* → nommez-le (`supervision-caisses`) → copiez le **jeton** qu'il affiche.

Dans l'onglet **Public Hostname** du tunnel, ajoutez trois entrées (le domaine doit être géré par Cloudflare) :

| Nom de domaine public | Chemin | Service (adresse interne) |
|---|---|---|
| `grafana.exemple.fr` | *(vide)* | `http://grafana:3000` |
| `metrics.exemple.fr` | `^/api/v1/write$` | `http://prometheus:9090` |
| `logs.exemple.fr` | `^/loki/api/v1/push$` | `http://loki:3100` |

Le champ **Chemin** limite ce qui traverse : un envoi de caisse atteint Prometheus, mais pas son interface ni son API de lecture.
(Les noms `grafana`, `prometheus`, `loki` sont ceux de vos conteneurs sur le réseau Docker : adaptez-les.)

## 2. Lancer `cloudflared` à côté de vos conteneurs

```bash
cd supervision/serveur/cloudflare
cp .env.example .env        # y mettre TUNNEL_TOKEN et le nom du réseau Docker
docker compose -f docker-compose.cloudflared.yml --env-file .env up -d
docker logs -f tunnel-cloudflare-cloudflared-1     # doit indiquer « Registered tunnel connection »
```
Le conteneur n'écoute rien : il ne fait que sortir.

## 3. Protéger avec Cloudflare Access (indispensable)

**Prometheus et Loki n'ont pas de mot de passe.** Sans protection, n'importe qui pourrait leur envoyer de fausses mesures.
Zero Trust → Access → **Applications** → Add → *Self-hosted*, une application par nom de domaine :

1. **grafana.exemple.fr** — politique *Allow* : les adresses e-mail de votre équipe (ou votre domaine d'entreprise).
2. **metrics.exemple.fr** et **logs.exemple.fr** — politique de type **Service Auth** (« Service Token ») :
   - Zero Trust → Access → **Service Auth** → Create Service Token → `agents-caisses` → copiez **Client ID** et **Client Secret**
     (le secret n'est affiché qu'une fois).
   - Rattachez ce jeton à la politique des deux applications (action *Service Auth*).
   - Prévoyez **un jeton par enseigne** et notez leur date d'expiration : on en révoque un sans toucher aux autres.

## 4. Sur chaque caisse

Le paquet `storepos-kiosk-monitoring` (version 1.1.0 ou plus) sait envoyer le jeton :
```bash
sudo storepos-kiosk-monitoring-enroll --enseigne LECLERC --magasin LYON-01 --poste 2 \
     --metrics-url https://metrics.exemple.fr/api/v1/write --logs-url https://logs.exemple.fr/loki/api/v1/push \
     --cf-access-id <Client ID> --cf-access-secret <Client Secret>
sudo storepos-kiosk-monitoring-check
```
Le contrôle envoie une requête vide avec le jeton et traduit la réponse : joint, refusé par Access, ou tunnel indisponible.

## 5. Vérifier de l'extérieur (depuis n'importe quel poste)

| Commande | Réponse attendue |
|---|---|
| `curl -sI https://metrics.exemple.fr/api/v1/write` | **302** ou **403** (Access refuse, pas de jeton) |
| `curl -sI https://metrics.exemple.fr/api/v1/query?query=up` | **404** ou **403** : la lecture n'est PAS exposée |
| `curl -s -o /dev/null -w '%{http_code}' -X POST -H "CF-Access-Client-Id: …" -H "CF-Access-Client-Secret: …" https://metrics.exemple.fr/api/v1/write` | **400** (corps vide, mais le jeton est accepté) |
| `curl -sI https://grafana.exemple.fr` | **302** vers la connexion Cloudflare Access |

Puis, dans Grafana : tableau de bord *Parc de caisses* → la caisse apparaît sous 2 minutes.

## À savoir

- **Heure de la caisse** : une horloge fausse fait échouer le HTTPS. Si seul le 443 est autorisé chez le client, vérifiez que la
  synchronisation d'heure passe.
- **Limites des offres Cloudflare** (taille des requêtes, nombre d'utilisateurs Access gratuits) : à vérifier sur leur grille actuelle ;
  à 6000 caisses, une offre payante est probable.
- **Rotation** : les jetons expirent ; changez-les en réinscrivant la caisse (`enroll --cf-access-id … --cf-access-secret …`).
- Si le tunnel tombe, les caisses gardent leurs mesures sur disque quelques heures et les rejouent ; passé ce délai, il y a des trous.
