#!/usr/bin/env python3
"""Construit storepos-kiosk_<version>_all.deb depuis le dossier pkg/ (sans dpkg-deb, donc
utilisable sous Windows). Usage : python build-deb.py"""
import gzip
import hashlib
import io
import re
import tarfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PKG = ROOT / "pkg"
MTIME = int(time.time())

EXEC_PREFIXES = ("usr/sbin/", "usr/bin/", "usr/lib/storepos-kiosk/", "usr/share/storepos-kiosk/openbox-autostart")
SCRIPTS = {"postinst", "prerm", "postrm", "preinst"}


def read_control():
    text = (PKG / "DEBIAN" / "control").read_text(encoding="utf-8")
    return text, re.search(r"^Version: (.+)$", text, re.M).group(1), re.search(r"^Package: (.+)$", text, re.M).group(1)


def lf(data: bytes) -> bytes:
    return data.replace(b"\r\n", b"\n")


def mode_for(rel: str, debian: bool) -> int:
    name = rel.split("/")[-1]
    if debian:
        return 0o755 if name in SCRIPTS else 0o644
    if rel.startswith("etc/sudoers.d/"):
        return 0o440
    return 0o755 if rel.startswith(EXEC_PREFIXES) else 0o644


def add_dir(tar, name):
    ti = tarfile.TarInfo(name)
    ti.type, ti.mode, ti.mtime = tarfile.DIRTYPE, 0o755, MTIME
    ti.uid = ti.gid = 0
    ti.uname = ti.gname = "root"
    tar.addfile(ti)


def add_file(tar, name, data, mode):
    ti = tarfile.TarInfo(name)
    ti.size, ti.mode, ti.mtime = len(data), mode, MTIME
    ti.uid = ti.gid = 0
    ti.uname = ti.gname = "root"
    tar.addfile(ti, io.BytesIO(data))


def make_tgz(entries, dirs):
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode="w", format=tarfile.GNU_FORMAT) as tar:
        add_dir(tar, "./")
        for d in sorted(dirs):
            add_dir(tar, "./" + d)
        for name, data, mode in entries:
            add_file(tar, "./" + name, data, mode)
    out = io.BytesIO()
    with gzip.GzipFile(fileobj=out, mode="wb", mtime=MTIME, compresslevel=9) as gz:
        gz.write(raw.getvalue())
    return out.getvalue()


def ar_member(name, data):
    header = (
        name.ljust(16)
        + str(MTIME).ljust(12)
        + "0".ljust(6)
        + "0".ljust(6)
        + "100644".ljust(8)
        + str(len(data)).ljust(10)
        + "`\n"
    ).encode("ascii")
    assert len(header) == 60
    return header + data + (b"\n" if len(data) % 2 else b"")


def main():
    control_text, version, package = read_control()

    data_entries, dirs = [], set()
    for path in sorted(PKG.rglob("*")):
        rel = path.relative_to(PKG).as_posix()
        if rel.startswith("DEBIAN"):
            continue
        if path.is_dir():
            dirs.add(rel)
            continue
        data_entries.append((rel, lf(path.read_bytes()), mode_for(rel, False)))

    installed_kb = max(1, sum(len(d) for _, d, _ in data_entries) // 1024)
    control_text = re.sub(r"^Installed-Size:.*\n", "", control_text, flags=re.M)
    control_text = control_text.replace("Architecture: all\n", f"Architecture: all\nInstalled-Size: {installed_kb}\n")

    md5s = "".join(f"{hashlib.md5(d).hexdigest()}  {n}\n" for n, d, _ in data_entries)

    control_entries = [("control", lf(control_text.encode("utf-8")), 0o644), ("md5sums", md5s.encode(), 0o644)]
    for f in sorted((PKG / "DEBIAN").iterdir()):
        if f.name == "control":
            continue
        control_entries.append((f.name, lf(f.read_bytes()), mode_for(f.name, True)))

    deb = b"!<arch>\n"
    deb += ar_member("debian-binary", b"2.0\n")
    deb += ar_member("control.tar.gz", make_tgz(control_entries, set()))
    deb += ar_member("data.tar.gz", make_tgz(data_entries, dirs))

    out = ROOT / f"{package}_{version}_all.deb"
    out.write_bytes(deb)
    print(f"OK : {out} ({len(deb)} octets, {len(data_entries)} fichiers)")


if __name__ == "__main__":
    main()
