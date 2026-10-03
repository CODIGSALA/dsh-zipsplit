#!/usr/bin/env python3
"""Generate an app icon (PNG) and a minimal resources.arsc -- no aapt2 needed."""
import os
import struct
import sys
import zlib

# ------------------------------------------------------------------ PNG ----
def png_rgba(w, h, rows):
    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))
    raw = b"".join(b"\x00" + bytes(r) for r in rows)
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def _rrect(cov, S, x0, y0, x1, y1, rad):
    """Mark coverage of a rounded rectangle into the S x S coverage list."""
    x0 = max(0, int(x0)); y0 = max(0, int(y0))
    x1 = min(S, int(x1 + 0.999)); y1 = min(S, int(y1 + 0.999))
    for y in range(y0, y1):
        cy = min(max(y + 0.5, y0 + rad), y1 - rad)
        for x in range(x0, x1):
            cx = min(max(x + 0.5, x0 + rad), x1 - rad)
            dx = x + 0.5 - cx
            dy = y + 0.5 - cy
            if dx * dx + dy * dy <= rad * rad:
                cov[y * S + x] = 1


def draw_icon(size=192, ss=4):
    S = size * ss
    bg = [0] * (S * S)
    _rrect(bg, S, 0, 0, S, S, S * 0.225)
    bars = [0] * (S * S)
    # three "slices" of a file
    u = S / 192.0
    _rrect(bars, S, 44 * u, 48 * u, 148 * u, 70 * u, 7 * u)
    _rrect(bars, S, 52 * u, 87 * u, 156 * u, 109 * u, 7 * u)
    _rrect(bars, S, 44 * u, 126 * u, 148 * u, 148 * u, 7 * u)

    rows = []
    for y in range(size):
        row = bytearray()
        for x in range(size):
            br = ag = bl = ba = 0
            fr = fg = fb = fa = 0
            for dy in range(ss):
                base = (y * ss + dy) * S + x * ss
                for dx in range(ss):
                    i = base + dx
                    if bg[i]:
                        # vertical gradient  #4C8BFF -> #1D53CC
                        t = ((y * ss + dy) + 0.5) / S
                        br += int(0x4C + (0x1D - 0x4C) * t)
                        ag += int(0x8B + (0x53 - 0x8B) * t)
                        bl += int(0xFF + (0xCC - 0xFF) * t)
                        ba += 255
                    if bars[i]:
                        fr += 255; fg += 255; fb += 255; fa += 235
            n = ss * ss
            a_b = ba / n
            if a_b <= 0:
                row += bytes((0, 0, 0, 0))
                continue
            r = br / n; g = ag / n; b = bl / n
            if fa > 0:
                a_f = fa / n
                r = r * (1 - a_f) + fr / n * a_f
                g = g * (1 - a_f) + fg / n * a_f
                b = b * (1 - a_f) + fb / n * a_f

            def cl(v):
                v = int(v + .5)
                return 0 if v < 0 else (255 if v > 255 else v)
            row += bytes((cl(r), cl(g), cl(b), cl(a_b)))
        rows.append(row)
    return png_rgba(size, size, rows)


# ----------------------------------------------------------------- arsc ----
RES_STRING_POOL_TYPE = 0x0001
RES_TABLE_TYPE = 0x0002
RES_TABLE_PACKAGE_TYPE = 0x0200
RES_TABLE_TYPE_SPEC_TYPE = 0x0202
RES_TABLE_TYPE_TYPE = 0x0201
TYPE_STRING = 0x03


def _chunk(ctype, header_size, body):
    return struct.pack("<HHI", ctype, header_size, 8 + len(body)) + body


def _enc_len(n):
    """AOSP's variable length prefix (big-endian, 0x80 continuation flag)."""
    if n > 0x7F:
        return bytes((((n >> 8) & 0x7F) | 0x80, n & 0xFF))
    return bytes((n,))


def _spool(strings, utf8=True):
    offsets = []
    blob = bytearray()
    for s in strings:
        offsets.append(len(blob))
        if utf8:
            b = s.encode("utf-8")
            # AOSP stores TWO length prefixes: UTF-16 unit count, then UTF-8 byte count
            u16len = len(s.encode("utf-16-le")) // 2
            blob += _enc_len(u16len) + _enc_len(len(b)) + b + b"\x00"
        else:
            u = s.encode("utf-16-le")
            n = len(u) // 2
            blob += struct.pack("<H", n) + u + b"\x00\x00"
    while len(blob) % 4:
        blob.append(0)
    cnt = len(strings)
    sstart = 28 + 4 * cnt
    flags = (1 << 8) if utf8 else 0
    size = sstart + len(blob)
    return (struct.pack("<HHIIIIII", RES_STRING_POOL_TYPE, 28, size,
                        cnt, 0, flags, sstart, 0)
            + b"".join(struct.pack("<I", o) for o in offsets) + bytes(blob))


def _config(sdk=0, density=0):
    """ResTable_config, size = 64 (same shape aapt2 emits)."""
    b = bytearray(64)
    struct.pack_into("<I", b, 0, 64)        # size
    struct.pack_into("<H", b, 14, density)  # screenType.density
    struct.pack_into("<H", b, 24, sdk)      # version.sdkVersion
    return bytes(b)


def build_arsc(pkg_name, res_files, values=None):
    """res_files: {'mipmap': {'ic_launcher': 'res/mipmap-hdpi/ic_launcher.png'}}
    values:    {'string': {'app_name': 'X'}}  (optional)"""
    values = values or {}
    # global value pool holds every string value, in encounter order
    gstrings = []

    def gidx(s):
        if s not in gstrings:
            gstrings.append(s)
        return gstrings.index(s)

    types = []          # ordered list of (type_name, {key: (value_string)})
    for tn, entries in list(res_files.items()) + list(values.items()):
        types.append((tn, entries))
    for _, entries in types:
        for v in entries.values():
            gidx(v)

    # package id 0x7f, type ids start at 1
    type_pool = _spool([t[0] for t in types])
    key_names = []
    for _, entries in types:
        for k in entries:
            if k not in key_names:
                key_names.append(k)
    key_pool = _spool(key_names)

    pkg_body = struct.pack("<I", 0x7F)
    nm = pkg_name.encode("utf-16-le")[:254]
    pkg_body += nm + b"\x00" * (256 - len(nm))
    type_strings_off = 288
    key_strings_off = 288 + len(type_pool)
    pkg_body += struct.pack("<IIII", type_strings_off, len(types),
                            key_strings_off, len(key_names))
    pkg_body += struct.pack("<I", 0)      # typeIdOffset
    assert len(pkg_body) == 288 - 8, len(pkg_body)
    pkg_body += type_pool + key_pool

    for tid, (tn, entries) in enumerate(types, start=1):
        keys = list(entries.keys())
        ec = len(keys)
        # typeSpec
        pkg_body += _chunk(RES_TABLE_TYPE_SPEC_TYPE, 16,
                           struct.pack("<BBHI", tid, 0, 0, ec)
                           + b"".join(struct.pack("<I", 0) for _ in range(ec)))
        # type (default config)
        cfg = _config()
        header_size = 20 + len(cfg)
        entries_start = header_size + 4 * ec
        ents = b""
        offs = []
        for k in keys:
            offs.append(len(ents))
            ents += struct.pack("<HHI", 8, 0, key_names.index(k))
            ents += struct.pack("<HBBI", 8, 0, TYPE_STRING, gidx(entries[k]))
        body = struct.pack("<BBHII", tid, 0, 0, ec, entries_start) + cfg
        body += b"".join(struct.pack("<I", o) for o in offs)
        body += ents
        pkg_body += _chunk(RES_TABLE_TYPE_TYPE, header_size, body)

    pkg = _chunk(RES_TABLE_PACKAGE_TYPE, 0x120, pkg_body)
    global_pool = _spool(gstrings, utf8=True)
    table = _chunk(RES_TABLE_TYPE, 12, struct.pack("<I", 1) + global_pool + pkg)
    return table


# ------------------------------------------------------------------ main ----
def main(app):
    res_dir = os.path.join(app, "res", "mipmap-hdpi")
    os.makedirs(res_dir, exist_ok=True)
    with open(os.path.join(res_dir, "ic_launcher.png"), "wb") as f:
        f.write(draw_icon(192))
    arsc = build_arsc("com.dsh.zipsplit",
                      {"mipmap": {"ic_launcher": "res/mipmap-hdpi/ic_launcher.png"}},
                      {"string": {"app_name": "文件分割合并"}})
    out = os.path.join(app, "out", "apk", "resources.arsc")
    with open(out, "wb") as f:
        f.write(arsc)
    print("[icon] res/mipmap-hdpi/ic_launcher.png  %d bytes"
          % os.path.getsize(os.path.join(res_dir, "ic_launcher.png")))
    print("[arsc] resources.arsc  %d bytes" % len(arsc))
    return arsc


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "/storage/emulated/0/DSH/zip2apk/app")
