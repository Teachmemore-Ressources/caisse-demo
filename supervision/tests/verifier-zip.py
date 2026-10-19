"""Compare supervision-serveur-et-agent.zip avec le dossier supervision/ (tailles + empreintes)."""
import hashlib, os, sys, zipfile
src = sys.argv[1]; z = zipfile.ZipFile(sys.argv[2])
EXCLUS = ('dist', 'cloudflare', '.git', '.terraform')
disque = {}
for r, d, f in os.walk(src):
    d[:] = [x for x in d if x not in EXCLUS]
    for n in f:
        p = os.path.join(r, n); disque['supervision/' + os.path.relpath(p, src).replace(os.sep, '/')] = p
dans_zip = {i.filename: i for i in z.infolist()}
h = lambda b: hashlib.sha256(b).hexdigest()
diff = [k for k in disque if k in dans_zip and h(open(disque[k], 'rb').read()) != h(z.read(k))]
print('fichiers sur disque :', len(disque), '| dans le zip :', len(dans_zip))
print('absents du zip   :', sorted(set(disque) - set(dans_zip)) or 'aucun')
print('absents du disque:', sorted(set(dans_zip) - set(disque)) or 'aucun')
print('contenu différent:', diff or 'aucun')
for k in ('supervision/serveur/caddy/Caddyfile', 'supervision/serveur/docker-compose.yml', 'supervision/serveur/.env.example'):
    print(k, dans_zip[k].file_size, 'octets dans le zip')
