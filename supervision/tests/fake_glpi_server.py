"""Faux serveur GLPI pour tests : journalise chaque requête reçue (méthode, chemin, en-têtes utiles, début du corps)
et répond comme le fait l'inventaire natif de GLPI (JSON). Usage : python3 fake_glpi_server.py PORT FICHIER_JOURNAL"""
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT, JOURNAL = int(sys.argv[1]), sys.argv[2]


class H(BaseHTTPRequestHandler):
    def _repondre(self):
        n = int(self.headers.get('Content-Length') or 0)
        corps = self.rfile.read(n) if n else b''
        if self.headers.get('Content-Encoding') == 'deflate':
            import zlib
            try: corps = zlib.decompress(corps)
            except Exception: pass
        enregistrement = {
            'methode': self.command, 'chemin': self.path,
            'authorization': self.headers.get('Authorization', ''),
            'content_type': self.headers.get('Content-Type', ''),
            'user_agent': self.headers.get('User-Agent', ''),
            'corps_debut': corps[:400].decode('utf-8', 'replace'), 'corps_taille': len(corps),
        }
        with open(JOURNAL, 'a', encoding='utf-8') as f: f.write(json.dumps(enregistrement, ensure_ascii=False) + '\n')
        if self.path.startswith('/front/inventory.php') or self.path == '/' or self.path.startswith('/glpi'):
            rep = json.dumps({'status': 'ok', 'expiration': 24}).encode()
            self.send_response(200); self.send_header('Content-Type', 'application/json')
        else:
            rep = b'not found'; self.send_response(404)
        self.send_header('Content-Length', str(len(rep))); self.end_headers(); self.wfile.write(rep)

    do_GET = do_POST = _repondre
    def log_message(self, *a): pass


HTTPServer(('127.0.0.1', PORT), H).serve_forever()
