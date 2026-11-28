#!/usr/bin/env python3
"""Build a large real DEX switch whose cases share an earlier terminal tail."""
import hashlib
from pathlib import Path
import struct
import sys
import zlib


def uleb(value):
    data = bytearray()
    while True:
        value, low = value >> 7, value & 127
        data.append(low | (128 if value else 0))
        if not value:
            return data


def build(count):
    own, oracle = 'Lfixture/TailSwitch;', 'Lfixture/TailOracle;'
    types = sorted(['I', 'Ljava/lang/Object;', own, oracle])
    strings = sorted(set(types) | {'III', 'finish', 'lookup'})
    methods = sorted([(types.index(oracle), 'finish'), (types.index(own), 'lookup')])
    so = 112
    to = so + len(strings) * 4
    po = to + len(types) * 4
    mo = po + 12
    co = mo + len(methods) * 8
    data_off = co + 32
    data = bytearray(data_off)
    offsets = []
    for name in strings:
        offsets.append(len(data))
        data += uleb(len(name)) + name.encode() + b'\0'
    while len(data) % 4:
        data.append(0)
    params = len(data)
    data += struct.pack('<IHH', 2, types.index('I'), types.index('I'))
    code_off = len(data)
    # v2 = key, v3 = mode. The default and all failing cases call finish.
    words = [0x022c, 0, 0]
    tail = len(words)
    words += [0x2071, methods.index((types.index(oracle), 'finish')), 0x0032, 0x000a, 0x000f]
    targets = []
    for key in range(count):
        targets.append(len(words))
        delta = tail - len(words)
        if key % 2 == 0 and -32768 <= delta <= 32767:
            words += [0x0339, delta & 0xffff]  # if-nez v3, shared tail
        else:
            words += [0x0338, 5, 0x002a]  # if-eqz v3, success; goto/32 tail
            delta = tail - (len(words) - 1)
            words += [delta & 0xffff, (delta >> 16) & 0xffff]
        words += [0x0013, key * 3]
        words += [0x00d8, 0x0100] * 12  # add-int/lit8 v0, v0, 1
        words += [0x000f]
    if len(words) % 2:
        words.append(0)
    payload = len(words)
    words[1:3] = [payload & 0xffff, payload >> 16]
    words += [0x0200, count]
    for values in (range(count), targets):
        for value in values:
            words += [value & 0xffff, (value >> 16) & 0xffff]
    data += struct.pack('<HHHHII', 4, 2, 2, 0, 0, len(words))
    data += struct.pack('<' + 'H' * len(words), *words)
    class_data = len(data)
    data += bytes([0, 0, 1, 0])
    data += uleb(methods.index((types.index(own), 'lookup'))) + uleb(9) + uleb(code_off)
    while len(data) % 4:
        data.append(0)
    map_off = len(data)
    sections = [(0, 1, 0), (1, len(strings), so), (2, len(types), to), (3, 1, po),
                (5, len(methods), mo), (6, 1, co), (0x2002, len(strings), data_off),
                (0x1001, 1, params), (0x2001, 1, code_off), (0x2000, 1, class_data),
                (0x1000, 1, map_off)]
    data += struct.pack('<I', len(sections))
    for kind, size, offset in sorted(sections, key=lambda item: item[2]):
        data += struct.pack('<HHII', kind, 0, size, offset)
    data[:8] = b'dex\n035\0'
    struct.pack_into('<20I', data, 32, len(data), 112, 0x12345678, 0, 0, map_off,
                     len(strings), so, len(types), to, 1, po, 0, 0, len(methods), mo,
                     1, co, len(data) - data_off, data_off)
    for i, offset in enumerate(offsets):
        struct.pack_into('<I', data, so + 4*i, offset)
    for i, name in enumerate(types):
        struct.pack_into('<I', data, to + 4*i, strings.index(name))
    struct.pack_into('<III', data, po, strings.index('III'), types.index('I'), params)
    for i, (owner, name) in enumerate(methods):
        struct.pack_into('<HHI', data, mo + 8*i, owner, 0, strings.index(name))
    struct.pack_into('<8I', data, co, types.index(own), 1, types.index('Ljava/lang/Object;'),
                     0, 0xffffffff, 0, class_data, 0)
    data[12:32] = hashlib.sha1(data[32:]).digest()
    struct.pack_into('<I', data, 8, zlib.adler32(data[12:]) & 0xffffffff)
    return data


if __name__ == '__main__':
    Path(sys.argv[1]).write_bytes(build(int(sys.argv[2]) if len(sys.argv) > 2 else 1000))
