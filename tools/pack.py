#!/usr/bin/env python3
"""Pack out/apk/ into an unsigned, zipaligned APK (no aapt2 / zipalign needed).

Every entry's data offset is aligned to 4 bytes (padding carried in a zip
"extra" field), which is what AssetManager needs to mmap resources.arsc.
"""
import os
import struct
import sys
import zipfile

APP = sys.argv[1] if len(sys.argv) > 1 else "/storage/emulated/0/DSH/zip2apk/app"
root = os.path.join(APP, "out", "apk")
out = os.path.join(APP, "out", "zipsplit-unsigned.apk")

STORE = {"AndroidManifest.xml", "resources.arsc"}
ALIGN = 4


def rank(rel):
    if rel == "AndroidManifest.xml":
        return (0, rel)
    if rel == "resources.arsc":
        return (1, rel)
    if rel.endswith(".dex"):
        return (2, rel)
    return (3, rel)


def extra_for(pos, namelen):
    """Return an extra-field blob so that pos+30+namelen+len(extra) % ALIGN == 0."""
    base = pos + 30 + namelen
    if base % ALIGN == 0:
        return b""
    pad = (-(base + 4)) % ALIGN
    return b"\x00\x00" + struct.pack("<H", pad) + b"\x00" * pad


def main():
    files = []
    for base, dirs, names in os.walk(root):
        dirs.sort()
        for n in sorted(names):
            p = os.path.join(base, n)
            files.append((os.path.relpath(p, root).replace(os.sep, "/"), p))
    files.sort(key=lambda it: rank(it[0]))

    if os.path.exists(out):
        os.remove(out)

    with zipfile.ZipFile(out, "w") as z:
        report = []
        for rel, p in files:
            ct = zipfile.ZIP_STORED if rel in STORE else zipfile.ZIP_DEFLATED
            data = open(p, "rb").read()
            pos = z.fp.tell()
            zi = zipfile.ZipInfo(rel, date_time=(2024, 1, 1, 0, 0, 0))
            zi.compress_type = ct
            zi.external_attr = 0o644 << 16
            nb = rel.encode("utf-8")
            zi.extra = extra_for(pos, len(nb))
            z.writestr(zi, data)
            report.append((rel, pos + 30 + len(nb) + len(zi.extra), len(zi.extra)))
        print("[pack] %s  %d bytes" % (out, os.path.getsize(out)))
        for rel, off, ex in report:
            flag = "OK " if off % ALIGN == 0 else "BAD"
            print("       %s %-40s data@%-7d pad=%d" % (flag, rel, off, ex))


if __name__ == "__main__":
    main()
