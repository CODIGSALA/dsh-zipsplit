#!/usr/bin/env python3
"""Minimal Android binary XML (AXML) writer + reader -- no aapt2 required."""
import struct
import xml.etree.ElementTree as ET

RES_STRING_POOL_TYPE = 0x0001
RES_XML_TYPE = 0x0003
RES_XML_START_NAMESPACE_TYPE = 0x0100
RES_XML_END_NAMESPACE_TYPE = 0x0101
RES_XML_START_ELEMENT_TYPE = 0x0102
RES_XML_END_ELEMENT_TYPE = 0x0103
RES_XML_RESOURCE_MAP_TYPE = 0x0180

TYPE_NULL = 0x00
TYPE_REFERENCE = 0x01
TYPE_STRING = 0x03
TYPE_FLOAT = 0x04
TYPE_INT_DEC = 0x10
TYPE_INT_HEX = 0x11
TYPE_INT_BOOLEAN = 0x12

ANDROID_NS = "http://schemas.android.com/apk/res/android"
NO_NS = 0xFFFFFFFF

INT_ATTRS = {
    "versionCode", "minSdkVersion", "targetSdkVersion", "screenOrientation",
    "configChanges", "layout_width", "layout_height", "gravity", "orientation",
    "inputType", "maxLines", "textSize", "padding", "minHeight", "numColumns",
}
BOOL_ATTRS = {
    "exported", "allowBackup", "debuggable", "largeHeap", "hardwareAccelerated",
    "extractNativeLibs", "enabled", "allowClearUserData", "usesCleartextTraffic",
    "requestLegacyExternalStorage", "supportsRtl",
}

ANDROID_ATTRS = {
    "theme": 0x01010000, "label": 0x01010001, "icon": 0x01010002, "name": 0x01010003,
    "permission": 0x01010006, "exported": 0x01010010, "debuggable": 0x0101000f,
    "screenOrientation": 0x0101001e, "configChanges": 0x0101001f,
    "versionCode": 0x0101021b, "versionName": 0x0101021c,
    "minSdkVersion": 0x0101020c, "targetSdkVersion": 0x01010270,
    "allowBackup": 0x01010280, "extractNativeLibs": 0x010104ea,
    "hardwareAccelerated": 0x010102d3, "largeHeap": 0x0101035a,
    "requestLegacyExternalStorage": 0x01010603,
    "supportsRtl": 0x010103af,
}


def _chunk(ctype, header_size, body):
    return struct.pack("<HHI", ctype, header_size, 8 + len(body)) + body


class Pool:
    def __init__(self):
        self.strings = []
        self.index = {}
        self.attr_ids = []

    def add(self, s, attr_id=0):
        if s in self.index:
            i = self.index[s]
            if attr_id and not self.attr_ids[i]:
                self.attr_ids[i] = attr_id
            return i
        i = len(self.strings)
        self.strings.append(s)
        self.index[s] = i
        self.attr_ids.append(attr_id)
        return i

    def idx(self, s):
        return self.index[s]


def _pool_bytes(strings):
    """UTF-16LE pool, matching what aapt2 emits (flags = 0)."""
    offsets = []
    blob = bytearray()
    for s in strings:
        offsets.append(len(blob))
        u = s.encode("utf-16-le")
        n = len(u) // 2
        if n > 0x7FFF:
            blob += struct.pack("<HH", (n >> 16) | 0x8000, n & 0xFFFF)
        else:
            blob += struct.pack("<H", n)
        blob += u
        blob += b"\x00\x00"
    while len(blob) % 4:
        blob.append(0)
    count = len(strings)
    strings_start = 28 + 4 * count
    size = strings_start + len(blob)
    head = struct.pack("<HHIIIIII", RES_STRING_POOL_TYPE, 28, size,
                       count, 0, 0, strings_start, 0)
    return head + b"".join(struct.pack("<I", o) for o in offsets) + bytes(blob)


def _xml_node(ctype, line_no, body):
    """XML tree node: 16-byte header (chunk hdr + lineNumber + comment)."""
    return struct.pack("<HHIII", ctype, 16, 16 + len(body), line_no, NO_NS) + body


def build(root, attr_ids=ANDROID_ATTRS, android_ns=ANDROID_NS):
    pool = Pool()

    # 1) pre-register attribute names so the resource map is dense
    used = set()
    def scan(el):
        for k in el.attrib:
            if k.startswith("{%s}" % android_ns):
                used.add(k[len(android_ns) + 2:])
        for c in el:
            scan(c)
    scan(root)
    for nm in sorted(used):
        if nm in attr_ids:
            pool.add(nm, attr_ids[nm])
    pool.add("android")
    pool.add(android_ns)

    chunks = []

    def start_ns():
        chunks.append(_xml_node(RES_XML_START_NAMESPACE_TYPE, 1,
                                struct.pack("<II", pool.idx("android"), pool.idx(android_ns))))

    def end_ns():
        chunks.append(_xml_node(RES_XML_END_NAMESPACE_TYPE, 1,
                                struct.pack("<II", pool.idx("android"), pool.idx(android_ns))))

    def attr_bytes(el):
        out = []
        for k, v in el.attrib.items():
            if k.startswith("{%s}" % android_ns):
                local = k[len(android_ns) + 2:]
                ns_i = pool.idx(android_ns)
                name_i = pool.add(local, attr_ids.get(local, 0))
            else:
                local = k
                ns_i = NO_NS
                name_i = pool.add(local)
            raw = NO_NS
            if v.startswith("@android:"):
                typ, data = TYPE_REFERENCE, int(v.split(":")[1], 16)
            elif local in BOOL_ATTRS or (isinstance(v, str) and v in ("true", "false")):
                typ, data = TYPE_INT_BOOLEAN, (1 if v in ("true", True) else 0)
            elif local in INT_ATTRS or (isinstance(v, str) and v.isdigit()):
                typ, data = TYPE_INT_DEC, int(v)
            else:
                si = pool.add(v)
                raw, typ, data = si, TYPE_STRING, si
            out.append(struct.pack("<III", ns_i, name_i, raw))
            out.append(struct.pack("<HBB", 8, 0, typ))
            out.append(struct.pack("<I", data))
        return b"".join(out)

    def start_el(el):
        tag = el.tag.split("}")[-1]
        attrs = attr_bytes(el)
        head = struct.pack("<IIHHHHHH", NO_NS, pool.add(tag), 20, 20,
                           len(el.attrib), 0, 0, 0)
        chunks.append(_xml_node(RES_XML_START_ELEMENT_TYPE, 1, head + attrs))

    def end_el(el):
        tag = el.tag.split("}")[-1]
        chunks.append(_xml_node(RES_XML_END_ELEMENT_TYPE, 1,
                                struct.pack("<II", NO_NS, pool.add(tag))))

    start_ns()
    def walk(el):
        start_el(el)
        for c in el:
            walk(c)
        end_el(el)
    walk(root)
    end_ns()

    inner = _pool_bytes(pool.strings)
    ids = list(pool.attr_ids)
    while ids and ids[-1] == 0:
        ids.pop()
    rmap = b"".join(struct.pack("<I", i) for i in ids)
    inner += _chunk(RES_XML_RESOURCE_MAP_TYPE, 8, rmap)
    inner += b"".join(chunks)
    return struct.pack("<HHI", RES_XML_TYPE, 8, 8 + len(inner)) + inner


# ----------------------------- reader / verifier -----------------------------
def _r_pool(d, off):
    typ, hs, size = struct.unpack_from("<HHI", d, off)
    assert typ == RES_STRING_POOL_TYPE, hex(typ)
    cnt, styc, flags, sstart, stystart = struct.unpack_from("<IIIII", d, off + 8)
    utf8 = bool(flags & (1 << 8))
    offs = struct.unpack_from("<%dI" % cnt, d, off + 28)
    out = []
    for o in offs:
        p = off + sstart + o
        if utf8:
            n = d[p]
            if n & 0x80:
                n = ((n & 0x7F) << 8) | d[p + 1]
                p += 2
            else:
                p += 1
            m = d[p]                     # second prefix: UTF-8 byte count
            if m & 0x80:
                m = ((m & 0x7F) << 8) | d[p + 1]
                p += 2
            else:
                p += 1
            out.append(d[p:p + m].decode("utf-8", "replace"))
        else:
            n = struct.unpack_from("<H", d, p)[0]
            out.append(d[p + 2:p + 2 + n * 2].decode("utf-16-le", "replace"))
    return out, off + size


def parse_axml(d):
    typ, hs, size = struct.unpack_from("<HHI", d, 0)
    assert typ == RES_XML_TYPE and size == len(d), (hex(typ), size, len(d))
    pool, pos = _r_pool(d, 8)
    rmap = []
    typ, hs2, sz = struct.unpack_from("<HHI", d, pos)
    if typ == RES_XML_RESOURCE_MAP_TYPE:
        rmap = list(struct.unpack_from("<%dI" % ((sz - 8) // 4), d, pos + 8))
        pos += sz
    out = []
    while pos < len(d):
        typ, hsz, sz = struct.unpack_from("<HHI", d, pos)
        body = pos + hsz
        if typ == RES_XML_START_ELEMENT_TYPE:
            ns, nm, astart, asize, acnt, idi, clsi, styi = struct.unpack_from(
                "<IIHHHHHH", d, body)
            attrs = []
            for i in range(acnt):
                a = body + astart + i * asize
                ans, anm, araw = struct.unpack_from("<III", d, a)
                vsz, res0, vtyp, vdata = struct.unpack_from("<HBBI", d, a + 12)
                attrs.append((pool[anm], vtyp, vdata,
                              pool[araw] if araw != NO_NS else None,
                              rmap[anm] if anm < len(rmap) else 0))
            out.append(("start", pool[nm], attrs))
        elif typ == RES_XML_END_ELEMENT_TYPE:
            ns, nm = struct.unpack_from("<II", d, body)
            out.append(("end", pool[nm], None))
        pos += sz
    return pool, rmap, out


def pretty(d):
    pool, rmap, nodes = parse_axml(d)
    lines = ["STRINGS(%d)=%s" % (len(pool), pool),
             "RESMAP=%s" % [hex(x) for x in rmap]]
    for kind, name, attrs in nodes:
        if kind == "start":
            lines.append("<%s>" % name)
            for an, vt, vd, raw, rid in attrs:
                lines.append("    %-14s type=0x%02x data=0x%x raw=%r rid=0x%08x"
                             % (an, vt, vd, raw, rid))
        else:
            lines.append("</%s>" % name)
    return "\n".join(lines)


def compile_file(src, dst):
    root = ET.parse(src).getroot()
    data = build(root)
    with open(dst, "wb") as f:
        f.write(data)
    return data


if __name__ == "__main__":
    import sys
    if sys.argv[1] == "-d":
        print(pretty(open(sys.argv[2], "rb").read()))
    else:
        data = compile_file(sys.argv[1], sys.argv[2])
        print("AXML %s (%d bytes)" % (sys.argv[2], len(data)))
