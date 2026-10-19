#!/usr/bin/env python3
"""Faux SaaS pour la démonstration de la caisse (aucune dépendance : bibliothèque standard).

    python3 mock_saas.py [--port 8080] [--data ./saas-data]

Il implémente le contrat décrit dans docs/API.md :
  GET  /api/v1/health
  POST /api/v1/tpv/activate        (code d'activation -> jeton)
  GET  /api/v1/sync/referentiel    (produits + caissiers, jeton requis)
  POST /api/v1/tickets             (réception des tickets, idempotent sur uuid)
et une page de suivi pour la démo :  GET /   (tickets reçus, mise à jour automatique)

Les produits/caissiers/codes se modifient dans <data>/referentiel.json : relancer n'est pas
nécessaire, la caisse récupère les changements à sa prochaine synchronisation.
Démonstration uniquement : pas de TLS, jetons en clair dans un fichier.
"""
import argparse
import hashlib
import html
import json
import secrets
import threading
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

LOCK = threading.Lock()


def pin_hash(pin: str) -> str:
    return hashlib.sha256(f"caisse-demo:{pin}".encode()).hexdigest()


def referentiel_par_defaut() -> dict:
    produits = [
        ("5000001", "Eau 50 cl", 120, 550, "Boissons"),
        ("5000002", "Soda 33 cl", 190, 2000, "Boissons"),
        ("5000003", "Café", 160, 550, "Boissons"),
        ("5000004", "Thé glacé", 220, 550, "Boissons"),
        ("5000010", "Baguette tradition", 125, 550, "Boulangerie"),
        ("5000011", "Croissant", 135, 550, "Boulangerie"),
        ("5000012", "Pain aux raisins", 150, 550, "Boulangerie"),
        ("5000013", "Sandwich poulet", 490, 1000, "Boulangerie"),
        ("5000020", "Pâtes 1 kg", 215, 550, "Épicerie"),
        ("5000021", "Lentilles 500 g", 235, 550, "Épicerie"),
        ("5000022", "Café moulu", 399, 550, "Épicerie"),
        ("5000030", "Piles x4", 549, 2000, "Divers"),
        ("5000031", "Magazine", 399, 210, "Divers"),
    ]
    return {
        "version": 1,
        "magasin": "Magasin Démo Lyon",
        # code d'activation -> numéro de caisse
        "codes": {"DEMO-0001": "Caisse 1", "DEMO-0002": "Caisse 2", "DEMO-0003": "Caisse 3"},
        "caissiers": [
            # "nom" = identité réelle (tickets, journal) ; "code" = nom de code affiché sur l'écran de connexion de la caisse
            {"id": 1, "nom": "Alice M.", "code": "Caissier 01", "role": "CAISSIER", "pin": "1234"},
            {"id": 2, "nom": "Armel NGANDO", "code": "Manager", "role": "RESPONSABLE", "pin": "0000"},
            {"id": 3, "nom": "Chloé D.", "code": "Caissier 02", "role": "CAISSIER", "pin": "4321"},
        ],
        "produits": [
            {"code": c, "nom": n, "prix_cents": p, "tva_bp": t, "categorie": k} for c, n, p, t, k in produits
        ],
    }


class Store:
    def __init__(self, data: Path):
        data.mkdir(parents=True, exist_ok=True)
        self.ref_file = data / "referentiel.json"
        self.tok_file = data / "tokens.json"
        self.tickets_file = data / "tickets.jsonl"
        if not self.ref_file.exists():
            self.ref_file.write_text(json.dumps(referentiel_par_defaut(), ensure_ascii=False, indent=2), encoding="utf-8")
        if not self.tok_file.exists():
            self.tok_file.write_text("{}", encoding="utf-8")
        self.tickets_file.touch()

    def referentiel(self) -> dict:
        return json.loads(self.ref_file.read_text(encoding="utf-8"))

    def tokens(self) -> dict:
        return json.loads(self.tok_file.read_text(encoding="utf-8"))

    def save_tokens(self, t: dict):
        self.tok_file.write_text(json.dumps(t, indent=2), encoding="utf-8")

    def tickets(self) -> list:
        with self.tickets_file.open(encoding="utf-8") as f:
            return [json.loads(l) for l in f if l.strip()]


def make_handler(store: Store):
    class Handler(BaseHTTPRequestHandler):
        server_version = "MockSaaS/1.0"

        def log_message(self, fmt, *args):
            print(f"{datetime.now():%H:%M:%S} {self.address_string()} {fmt % args}")

        # ---- utilitaires
        def _json(self, code: int, obj):
            body = json.dumps(obj, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _body(self) -> dict:
            n = int(self.headers.get("Content-Length") or 0)
            return json.loads(self.rfile.read(n) or b"{}")

        def _auth(self):
            h = self.headers.get("Authorization", "")
            tok = h[7:] if h.startswith("Bearer ") else ""
            return store.tokens().get(tok)

        # ---- routes
        def do_GET(self):
            if self.path == "/api/v1/health":
                return self._json(200, {"ok": True})
            if self.path == "/api/v1/sync/referentiel":
                if not self._auth():
                    return self._json(401, {"error": "jeton invalide"})
                ref = store.referentiel()
                return self._json(200, {
                    "version": ref.get("version", 1),
                    "produits": ref["produits"],
                    "caissiers": [
                        {"id": c["id"], "nom": c["nom"], "code": c.get("code", ""), "role": c["role"], "pin_sha256": pin_hash(c["pin"])}
                        for c in ref["caissiers"]
                    ],
                })
            if self.path == "/api/v1/tickets":
                return self._json(200, {"tickets": store.tickets()})
            if self.path in ("/", "/index.html"):
                return self._dashboard()
            self._json(404, {"error": "inconnu"})

        def do_POST(self):
            try:
                data = self._body()
            except json.JSONDecodeError:
                return self._json(400, {"error": "JSON invalide"})

            if self.path == "/api/v1/tpv/activate":
                ref = store.referentiel()
                tpv = ref.get("codes", {}).get(str(data.get("code", "")).strip())
                if not tpv:
                    return self._json(403, {"error": "code d'activation inconnu"})
                with LOCK:
                    tokens = store.tokens()
                    token = secrets.token_urlsafe(24)
                    tokens[token] = {"tpv": tpv, "device_id": data.get("device_id"), "hostname": data.get("hostname"),
                                     "activated": datetime.now().isoformat(timespec="seconds")}
                    store.save_tokens(tokens)
                print(f"  -> caisse activée : {tpv} ({data.get('hostname')})")
                return self._json(200, {"token": token, "magasin": ref["magasin"], "tpv": tpv})

            if self.path == "/api/v1/tickets":
                info = self._auth()
                if not info:
                    return self._json(401, {"error": "jeton invalide"})
                with LOCK:
                    connus = {t["uuid"] for t in store.tickets()}
                    accepted = []
                    with store.tickets_file.open("a", encoding="utf-8") as f:
                        for t in data.get("tickets", []):
                            if t["uuid"] not in connus:  # idempotent : un renvoi n'est pas dupliqué
                                t["recu_le"] = datetime.now().isoformat(timespec="seconds")
                                t["tpv"] = info["tpv"]
                                f.write(json.dumps(t, ensure_ascii=False) + "\n")
                            accepted.append(t["uuid"])
                return self._json(200, {"accepted": accepted})

            self._json(404, {"error": "inconnu"})

        # ---- page de suivi pour la démo
        def _dashboard(self):
            tickets = store.tickets()
            total = sum(t["total_cents"] for t in tickets)

            def eur(c):
                return f"{c // 100},{c % 100:02d} €"

            rows = "".join(
                f"<tr><td>{html.escape(t.get('recu_le', ''))}</td><td>{html.escape(t.get('tpv', ''))}</td>"
                f"<td>n° {t['numero']}</td><td>{html.escape(t['caissier'])}</td><td>{html.escape(t['mode'])}</td>"
                f"<td class='r'>{eur(t['total_cents'])}</td></tr>"
                for t in reversed(tickets[-50:])
            )
            page = f"""<!doctype html><html lang="fr"><head><meta charset="utf-8"><meta http-equiv="refresh" content="3">
<title>SaaS de démonstration</title><style>
body{{font-family:system-ui,sans-serif;background:#0f1b2d;color:#e9eef5;margin:0;padding:24px}}
h1{{color:#1fb5a5;margin:0 0 4px}} .k{{display:flex;gap:16px;margin:16px 0}}
.c{{background:#16263d;border-radius:12px;padding:14px 22px}} .c b{{display:block;font-size:28px;color:#1fb5a5}}
table{{width:100%;border-collapse:collapse;background:#16263d;border-radius:12px;overflow:hidden}}
td,th{{padding:10px 14px;text-align:left;border-bottom:1px solid #1f3452}} .r{{text-align:right}}
</style></head><body><h1>SaaS de démonstration</h1><div>Tickets reçus des caisses (actualisation toutes les 3 s)</div>
<div class="k"><div class="c">Tickets<b>{len(tickets)}</b></div><div class="c">Chiffre d'affaires<b>{eur(total)}</b></div>
<div class="c">Caisses activées<b>{len(store.tokens())}</b></div></div>
<table><tr><th>Reçu le</th><th>Caisse</th><th>Ticket</th><th>Caissier</th><th>Paiement</th><th class="r">Total</th></tr>{rows}</table>
</body></html>""".encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(page)))
            self.end_headers()
            self.wfile.write(page)

    return Handler


def main():
    ap = argparse.ArgumentParser(description="SaaS simulé pour la caisse de démonstration")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--data", default="./saas-data")
    a = ap.parse_args()
    store = Store(Path(a.data))
    srv = ThreadingHTTPServer((a.host, a.port), make_handler(store))
    print(f"SaaS simulé sur http://{a.host}:{a.port}/  (données : {Path(a.data).resolve()})")
    print("Codes d'activation : " + ", ".join(store.referentiel().get("codes", {})))
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\nArrêt.")


if __name__ == "__main__":
    main()
