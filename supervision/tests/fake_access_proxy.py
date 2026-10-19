#!/usr/bin/env python3
"""Proxy de test qui imite Cloudflare Access « Service Auth » devant le point d'entrée des mesures.

    python3 fake_access_proxy.py <port> <cible http://127.0.0.1:9090> <id attendu> <secret attendu> <dossier de compteurs>

Il n'accepte un envoi (POST /api/v1/write) que si les en-têtes CF-Access-Client-Id et CF-Access-Client-Secret
correspondent ; sinon il répond 403, comme le ferait Cloudflare. Il écrit une ligne dans « acceptes » ou « refuses ».
Il ne sert qu'aux tests : il prouve que l'agent envoie bien les en-têtes configurés.
"""
import sys
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PORT, CIBLE, ID, SECRET, DOSSIER = int(sys.argv[1]), sys.argv[2].rstrip("/"), sys.argv[3], sys.argv[4], Path(sys.argv[5])
DOSSIER.mkdir(parents=True, exist_ok=True)


def compter(nom: str, detail: str):
    with (DOSSIER / nom).open("a", encoding="utf-8") as f:
        f.write(detail + "\n")


class Proxy(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        corps = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        if self.path != "/api/v1/write":
            self.send_response(404); self.end_headers(); return
        if self.headers.get("CF-Access-Client-Id") != ID or self.headers.get("CF-Access-Client-Secret") != SECRET:
            compter("refuses", f"id={self.headers.get('CF-Access-Client-Id')!r}")
            self.send_response(403); self.end_headers(); self.wfile.write(b"Forbidden"); return
        compter("acceptes", "ok")
        req = urllib.request.Request(CIBLE + self.path, data=corps, method="POST")
        for h in ("Content-Type", "Content-Encoding", "X-Prometheus-Remote-Write-Version", "User-Agent"):
            if self.headers.get(h):
                req.add_header(h, self.headers[h])
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                self.send_response(r.status); self.end_headers(); self.wfile.write(r.read())
        except urllib.error.HTTPError as e:
            self.send_response(e.code); self.end_headers(); self.wfile.write(e.read())


ThreadingHTTPServer(("127.0.0.1", PORT), Proxy).serve_forever()
