# Contrat d'API caisse ⇄ SaaS (v1)

La caisse est un **client** : toutes les connexions partent d'elle (HTTP/HTTPS sortant). Rien à ouvrir
sur le réseau du magasin. Formats : JSON UTF-8. Montants : **entiers en centimes**. TVA : **points de
base** (550 = 5,5 %, 2000 = 20 %).

Authentification : `Authorization: Bearer <jeton>` (jeton obtenu à l'activation), sauf `health` et `activate`.

## `GET /api/v1/health`
Sert au test de joignabilité. Réponse `200 {"ok": true}`.

## `POST /api/v1/tpv/activate`
Enregistre une caisse avec un code remis par l'exploitant.
```json
{ "code": "DEMO-0001", "device_id": "uuid-de-la-caisse", "hostname": "caisse-lyon-01" }
```
Réponse `200` : `{ "token": "…", "magasin": "Magasin Démo Lyon", "tpv": "Caisse 1" }`
Erreur `403` si le code est inconnu.

## `GET /api/v1/sync/referentiel`
Produits et caissiers du magasin (la caisse remplace sa base locale).
```json
{
  "version": 1,
  "produits":  [ { "code": "5000001", "nom": "Eau 50 cl", "prix_cents": 120, "tva_bp": 550, "categorie": "Boissons" } ],
  "caissiers": [ { "id": 1, "nom": "Alice M.", "code": "Caissier 01", "role": "CAISSIER", "pin_sha256": "…" } ]
}
```
`nom` : identité réelle, utilisée partout dans le système (tickets, journal, comptages). `code` (facultatif) : nom de code affiché
sur l'écran de connexion de la caisse, pour ne pas exposer l'identité des personnes (par défaut « Manager » ou « Caissier NN »).
`role` : `CAISSIER` ou `RESPONSABLE`. `pin_sha256` = SHA-256 hexadécimal de la chaîne `"caisse-demo:" + PIN`
(le PIN n'est jamais transmis en clair). Appelé au plus toutes les 5 minutes, et à la demande.

## `POST /api/v1/tickets`
Envoi des ventes (lots de 50 maximum).
```json
{ "tickets": [ {
  "uuid": "…", "numero": 12, "date": "2026-10-17T10:15:30", "caissier": "Alice Martin", "client": "Marie Dubois",
  "lignes": [ { "code": "5000001", "nom": "Eau 50 cl", "qte": 2, "prix_cents": 120, "tva_bp": 550, "remise_pct": 0, "type": "PRODUIT" } ],
  "remise_cents": 0, "total_cents": 240,
  "mode": "MIXTE",
  "paiements": [ { "mode": "ESPECES", "montant_cents": 100, "rendu_cents": 0 }, { "mode": "CARTE", "montant_cents": 140, "rendu_cents": 0 } ],
  "recu_cents": 240, "rendu_cents": 0, "retour_de": null, "device_id": "…" } ] }
```
`type` d'une ligne : `PRODUIT` ou `RETOUR` (quantité positive, total négatif). `total_cents` négatif = ticket de retour
(`retour_de` = numéro du ticket d'origine). `mode` : `ESPECES`, `CARTE`, `CHEQUE`, `TICKET_RESTO` ou `MIXTE`
(détail dans `paiements`, montants négatifs pour un remboursement). `montant_cents` est net du rendu de monnaie.
Réponse `200` : `{ "accepted": ["<uuid>", …] }` — **tous les uuid traités**, y compris ceux déjà reçus
(le serveur doit être **idempotent** sur `uuid` : la caisse peut renvoyer un ticket si l'accusé s'est perdu).

## Mode hors ligne
Chaque vente est écrite dans la base locale (SQLite) **avant** tout envoi. Un ticket reste « en attente »
jusqu'à son accusé dans `accepted`. Le réseau coupé n'empêche donc jamais de vendre ; au retour du réseau
(vérifié toutes les 30 s), la file est vidée par lots, dans l'ordre des numéros.

## À durcir pour la production
- HTTPS obligatoire (certificat valide) ; jetons à durée limitée et révocables ; limitation de débit ;
- signature ou numérotation continue des tickets selon les exigences légales du pays ;
- le démonstrateur ne gère ni la monnaie étrangère, ni les retours, ni les paiements fractionnés.
