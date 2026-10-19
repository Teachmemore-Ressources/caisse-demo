#!/usr/bin/env python3
"""Construit un .deb « arch: all » à partir d'un dossier pkg/ (DEBIAN/ + arborescence), sans dpkg-deb ni fakeroot.

    python3 tools/pkg_builder.py <dossier pkg> <dossier de sortie>

Droits : scripts DEBIAN et fichiers sous usr/sbin, usr/bin, usr/lib/*/ (*.sh) en 0755 ; sudoers.d en 0440 ;
le reste en 0644. Propriétaire root. Le champ Installed-Size est calculé.
"""
import gzip
import hashlib
import io
import re
import sys
import tarfile
import time
from pathlib import Path

MTIME = int(time.time())
SCRIPTS = {"postinst", "prerm", "postrm", "preinst"}
EXEC_PREFIXES = ("usr/sbin/", "usr/bin/")


def mode_for(rel: str, debian: bool) -> int:
    name = rel.split("/")[-1]
    if debian:
        return 0o755 if name in SCRIPTS else 0o644
    if rel.startswith("etc/sudoers.d/"):
        return 0o440
    if rel.startswith(EXEC_PREFIXES) or (rel.startswith("usr/lib/") and rel.endswith(".sh")):
        return 0o755
    return 0o644


def lf(b: bytes) -> bytes:
    return b.replace(b"\r\n", b"\n")


def ti(name, kind=tarfile.REGTYPE, mode=0o644, size=0):
    t = tarfile.TarInfo(name)
    t.type, t.mode, t.size, t.mtime = kind, mode, size, MTIME
    t.uid = t.gid = 0
    t.uname = t.gname = "root"
    return t


def tgz(entries, dirs) -> bytes:
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode="w", format=tarfile.GNU_FORMAT) as tar:
        tar.addfile(ti("./", tarfile.DIRTYPE, 0o755))
        for d in sorted(dirs):
            tar.addfile(ti("./" + d, tarfile.DIRTYPE, 0o755))
        for name, data, mode in entries:
            tar.addfile(ti("./" + name, mode=mode, size=len(data)), io.BytesIO(data))
    out = io.BytesIO()
    with gzip.GzipFile(fileobj=out, mode="wb", mtime=MTIME, compresslevel=9) as gz:
        gz.write(raw.getvalue())
    return out.getvalue()


def ar_member(name: str, data: bytes) -> bytes:
    head = f"{name:<16}{MTIME:<12}{0:<6}{0:<6}{'100644':<8}{len(data):<10}`\n".encode()
    assert len(head) == 60
    return head + data + (b"\n" if len(data) % 2 else b"")


def build(pkg: Path, out_dir: Path) -> Path:
    control = (pkg / "DEBIAN" / "control").read_text(encoding="utf-8")
    name = re.search(r"^Package: (.+)$", control, re.M).group(1)
    version = re.search(r"^Version: (.+)$", control, re.M).group(1)
    arch = re.search(r"^Architecture: (.+)$", control, re.M).group(1)

    data_entries, dirs = [], set()
    for p in sorted(pkg.rglob("*")):
        rel = p.relative_to(pkg).as_posix()
        if rel.startswith("DEBIAN"):
            continue
        if p.is_dir():
            dirs.add(rel)
        else:
            data_entries.append((rel, lf(p.read_bytes()), mode_for(rel, False)))

    size_kb = max(1, sum(len(d) for _, d, _ in data_entries) // 1024)
    control = re.sub(r"^Installed-Size:.*\n", "", control, flags=re.M)
    control = control.replace(f"Architecture: {arch}\n", f"Architecture: {arch}\nInstalled-Size: {size_kb}\n")
    md5s = "".join(f"{hashlib.md5(d).hexdigest()}  {n}\n" for n, d, _ in data_entries)

    control_entries = [("control", lf(control.encode()), 0o644), ("md5sums", md5s.encode(), 0o644)]
    for f in sorted((pkg / "DEBIAN").iterdir()):
        if f.name != "control":
            control_entries.append((f.name, lf(f.read_bytes()), mode_for(f.name, True)))

    deb = b"!<arch>\n" + ar_member("debian-binary", b"2.0\n")
    deb += ar_member("control.tar.gz", tgz(control_entries, set())) + ar_member("data.tar.gz", tgz(data_entries, dirs))
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / f"{name}_{version}_{arch}.deb"
    out.write_bytes(deb)
    print(f"OK : {out} ({len(deb)} octets, {len(data_entries)} fichiers)")
    return out


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    build(Path(sys.argv[1]), Path(sys.argv[2]))
