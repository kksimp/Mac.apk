#!/usr/bin/env python3
"""Two independent real DEX files: a caller in A, a static interface in B."""
import hashlib
from pathlib import Path
import struct
import sys
import zlib


def uleb(value):
    result = bytearray()
    while True:
        part = value & 127
        value >>= 7
        result.append(part | (128 if value else 0))
        if not value:
            return result


def dex(provider):
    api = "Lfixture/CrossApi;"
    caller = "Lfixture/CrossCaller;"
    own = api if provider else caller
    types = sorted({"I", "Ljava/lang/Object;", api, own})
    strings = sorted(set(types) | {"value"})
    # A references CrossApi.value but has NO CrossApi class definition.
    methods = [api] if provider else sorted([api, caller])
    strings_off = 112
    types_off = strings_off + 4 * len(strings)
    proto_off = types_off + 4 * len(types)
    methods_off = proto_off + 12
    classes_off = methods_off + 8 * len(methods)
    data_off = classes_off + 32
    data = bytearray(data_off)
    string_offsets = []
    for item in strings:
        string_offsets.append(len(data))
        data += uleb(len(item)) + item.encode() + b"\0"
    while len(data) % 4:
        data.append(0)
    code_off = len(data)
    # const/16 v0,42; return v0 OR invoke-static CrossApi.value; move-result v0; return v0.
    instructions = [0x0013, 42, 0x000F] if provider else [0x0071, methods.index(api), 0, 0x000A, 0x000F]
    data += struct.pack("<HHHHII", 1, 0, 0, 0, 0, len(instructions))
    data += struct.pack("<" + "H" * len(instructions), *instructions)
    while len(data) % 4:
        data.append(0)
    class_data_off = len(data)
    data += bytes([0, 0, 1, 0]) + uleb(methods.index(own)) + uleb(9) + uleb(code_off)
    while len(data) % 4:
        data.append(0)
    map_off = len(data)
    sections = [(0, 1, 0), (1, len(strings), strings_off), (2, len(types), types_off),
                (3, 1, proto_off), (5, len(methods), methods_off), (6, 1, classes_off),
                (0x2002, len(strings), data_off), (0x2001, 1, code_off),
                (0x2000, 1, class_data_off), (0x1000, 1, map_off)]
    data += struct.pack("<I", len(sections))
    for kind, count, offset in sorted(sections, key=lambda item: item[2]):
        data += struct.pack("<HHII", kind, 0, count, offset)
    data[:8] = b"dex\n035\0"
    struct.pack_into("<20I", data, 32, len(data), 112, 0x12345678, 0, 0, map_off,
                     len(strings), strings_off, len(types), types_off, 1, proto_off,
                     0, 0, len(methods), methods_off, 1, classes_off, len(data) - data_off, data_off)
    for index, offset in enumerate(string_offsets):
        struct.pack_into("<I", data, strings_off + 4 * index, offset)
    for index, name in enumerate(types):
        struct.pack_into("<I", data, types_off + 4 * index, strings.index(name))
    struct.pack_into("<3I", data, proto_off, strings.index("I"), types.index("I"), 0)
    for index, owner in enumerate(methods):
        struct.pack_into("<HHI", data, methods_off + 8 * index, types.index(owner), 0, strings.index("value"))
    struct.pack_into("<8I", data, classes_off, types.index(own), 0x601 if provider else 1,
                     types.index("Ljava/lang/Object;"), 0, 0xFFFFFFFF, 0, class_data_off, 0)
    data[12:32] = hashlib.sha1(data[32:]).digest()
    struct.pack_into("<I", data, 8, zlib.adler32(data[12:]) & 0xFFFFFFFF)
    return data


if __name__ == "__main__":
    Path(sys.argv[1]).write_bytes(dex(False))
    Path(sys.argv[2]).write_bytes(dex(True))
