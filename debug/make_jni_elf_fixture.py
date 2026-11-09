#!/usr/bin/env python3
"""Generate tiny AArch64 ELF fixtures with a real constructor publishing JNI."""
import pathlib
import struct
import sys

dest = pathlib.Path(sys.argv[1])
dest.mkdir(parents=True, exist_ok=True)

def adr(reg, pc, target):
    delta = (target - pc) & ((1 << 21) - 1)
    return 0x10000000 | ((delta & 3) << 29) | ((delta >> 2) << 5) | reg

for mode in ['deferred', 'normal', 'absent']:
    data = bytearray(0x5200)
    names = b'\0' + (b'' if mode == 'absent' else (b'obfuscated\0' if mode == 'deferred' else b'JNI_OnLoad\0')) + b'fixture_value\0'
    syms = [(names.index(b'fixture_value'), 0x550, 12)]
    if mode != 'absent':
        syms.insert(0, (1, 0x501 if mode == 'deferred' else 0x500, 28))
    count = len(syms) + 1
    struct.pack_into('<16sHHIQQQIHHHHHH', data, 0, b'\x7fELF\x02\x01\x01'+bytes(9),
                     3, 183, 1, 0, 64, 0x5000, 0, 64, 56, 3, 64, 8, 7)
    dyn = [(4, 0x4100), (6, 0x4140), (5, 0x41c0), (10, len(names)), (11, 24),
           (25, 0x4240), (27, 8), (7, 0x4300), (8, 24), (9, 24), (0, 0)]
    for i, ph in enumerate([(1, 5, 0, 0, 0, 0x600, 0x600, 0x4000),
                            (1, 6, 0x4000, 0x4000, 0x4000, 0x1000, 0x1000, 0x4000),
                            (2, 6, 0x4000, 0x4000, 0x4000, len(dyn)*16, len(dyn)*16, 8)]):
        struct.pack_into('<IIQQQQQQ', data, 64+i*56, *ph)
    for i, entry in enumerate(dyn): struct.pack_into('<qQ', data, 0x4000+i*16, *entry)
    chain = [0] + [i+1 if i+1 < count else 0 for i in range(1, count)]
    hash_words = [1, count, 1] + chain
    struct.pack_into('<'+'I'*len(hash_words), data, 0x4100, *hash_words)
    for i, (name, value, size) in enumerate(syms, 1):
        struct.pack_into('<IBBHQQ', data, 0x4140+i*24, name, 0x12, 0, 1, value, size)
    data[0x41c0:0x41c0+len(names)] = names
    struct.pack_into('<QQq', data, 0x4300, 0x4240, 1027, 0x440)
    ctor = []
    def emit(word): ctor.append(word)
    if mode == 'deferred':
        emit(adr(8, 0x440, 0x41c1))
        value = int.from_bytes(b'JNI_OnLo', 'little')
        for n in range(4): emit((0xd2800000 if n == 0 else 0xf2800000) | (n << 21) | (((value >> (16*n)) & 0xffff) << 5) | 9)
        emit(0xf9000000 | (8 << 5) | 9)
        emit(0x52800000 | (int.from_bytes(b'ad', 'little') << 5) | 9)
        emit(0x79000000 | (4 << 10) | (8 << 5) | 9)
        emit(0x39000000 | (10 << 10) | (8 << 5) | 31)
        emit(adr(8, 0x440+4*len(ctor), 0x4160))
        emit(0xd2800000 | (0x500 << 5) | 9)
        emit(0xf9000000 | (8 << 5) | 9)
    emit(0xd65f03c0)
    struct.pack_into('<'+'I'*len(ctor), data, 0x440, *ctor)
    onload = [adr(9, 0x500, 0x4280), 0xb9400128, 0x11000508, 0xb9000128,
              0x528000c0, 0x72a00020, 0xd65f03c0]
    struct.pack_into('<7I', data, 0x500, *onload)
    struct.pack_into('<3I', data, 0x550, adr(9, 0x550, 0x4280), 0xb9400120, 0xd65f03c0)
    section_names = b'\0.text\0.dynsym\0.dynstr\0.hash\0.dynamic\0.rela.dyn\0.shstrtab\0'
    data[0x4d00:0x4d00+len(section_names)] = section_names
    sections = [(b'.text',1,6,0x400,0x400,0x200,0,0,4,0),
                (b'.dynsym',11,2,0x4140,0x4140,count*24,3,1,8,24),
                (b'.dynstr',3,2,0x41c0,0x41c0,len(names),0,0,1,0),
                (b'.hash',5,2,0x4100,0x4100,len(hash_words)*4,2,0,8,4),
                (b'.dynamic',6,3,0x4000,0x4000,len(dyn)*16,3,0,8,16),
                (b'.rela.dyn',4,2,0x4300,0x4300,24,2,0,8,24),
                (b'.shstrtab',3,0,0,0x4d00,len(section_names),0,0,1,0)]
    for i, section in enumerate(sections, 1):
        name, *rest = section
        struct.pack_into('<IIQQQQIIQQ', data, 0x5000+i*64, section_names.index(name), *rest)
    (dest/f'lib{mode}.so').write_bytes(data)
