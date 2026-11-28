#!/usr/bin/env python3
"""Build small, real multidex input for in-memory DEX loader regression tests."""
import hashlib
from pathlib import Path
import struct
import sys
import zipfile
import zlib


def dex(names):
    descriptors = sorted(set(names) | {"Ljava/lang/Object;"})
    strings_off = 0x70
    types_off = strings_off + 4 * len(descriptors)
    classes_off = types_off + 4 * len(descriptors)
    data_off = classes_off + 32 * len(names)
    body = bytearray(data_off)
    string_offsets = []
    for name in descriptors:
        assert len(name) < 128
        string_offsets.append(len(body))
        body.extend(bytes([len(name)]) + name.encode("ascii") + b"\0")
    while len(body) % 4:
        body.append(0)
    map_off = len(body)
    sections = [(0, 1, 0), (1, len(descriptors), strings_off),
                (2, len(descriptors), types_off), (6, len(names), classes_off),
                (0x2002, len(descriptors), data_off), (0x1000, 1, map_off)]
    body.extend(struct.pack("<I", len(sections)))
    for kind, count, offset in sections:
        body.extend(struct.pack("<HHII", kind, 0, count, offset))
    body[:8] = b"dex\n035\0"
    struct.pack_into("<20I", body, 32, len(body), 0x70, 0x12345678,
                     0, 0, map_off, len(descriptors), strings_off,
                     len(descriptors), types_off, 0, 0, 0, 0, 0, 0,
                     len(names), classes_off, len(body) - data_off, data_off)
    for i, offset in enumerate(string_offsets):
        struct.pack_into("<I", body, strings_off + i * 4, offset)
        struct.pack_into("<I", body, types_off + i * 4, i)
    for i, name in enumerate(sorted(names)):
        struct.pack_into("<8I", body, classes_off + i * 32,
                         descriptors.index(name), 1,
                         descriptors.index("Ljava/lang/Object;"),
                         0, 0xFFFFFFFF, 0, 0, 0)
    body[12:32] = hashlib.sha1(body[32:]).digest()
    struct.pack_into("<I", body, 8, zlib.adler32(body[12:]) & 0xFFFFFFFF)
    return body


if __name__ == "__main__":
    out = Path(sys.argv[1])
    with zipfile.ZipFile(out, "w") as archive:
        archive.writestr("classes.dex", dex(["Lfixture/Zed;", "Lfixture/Alpha;"]))
        archive.writestr("classes2.dex", dex(["Lfixture/Alpha;", "Lfixture/Beta;"]))
        archive.writestr("not-a-class.txt", "This must not be enumerated.")
