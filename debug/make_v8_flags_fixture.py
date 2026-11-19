#!/usr/bin/env python3
"""Generate original AArch64 ELF fixtures for both V8 flag-setter ABIs."""
from pathlib import Path
import struct
import sys

destination = Path(sys.argv[1])
destination.mkdir(parents=True, exist_ok=True)


def adr(register, pc, target):
    delta = (target - pc) & ((1 << 21) - 1)
    return 0x10000000 | ((delta & 3) << 29) | ((delta >> 2) << 5) | register


for owner in ("internal", "public"):
    for length in ("i", "m"):
        prefix = "_ZN2v88internal8FlagList" if owner == "internal" else "_ZN2v82V8"
        setter = (prefix + "18SetFlagsFromStringEPKc" + length).encode()
        flag = b"_ZN2v88internal12FLAG_jitlessE"
        names = b"\0" + setter + b"\0" + flag + b"\0"
        data = bytearray(0x5400)
        struct.pack_into('<16sHHIQQQIHHHHHH', data, 0,
                         b'\x7fELF\x02\x01\x01' + bytes(9), 3, 183, 1, 0,
                         64, 0x5000, 0, 64, 56, 3, 64, 9, 8)
        dynamic = [(4, 0x4100), (6, 0x4140), (5, 0x41c0), (10, len(names)),
                   (11, 24), (25, 0x4240), (27, 8), (7, 0x4300),
                   (8, 24), (9, 24), (0, 0)]
        headers = [(1, 5, 0, 0, 0, 0x600, 0x600, 0x4000),
                   (1, 6, 0x4000, 0x4000, 0x4000, 0x1000, 0x1000, 0x4000),
                   (2, 6, 0x4000, 0x4000, 0x4000, len(dynamic)*16, len(dynamic)*16, 8)]
        for index, header in enumerate(headers):
            struct.pack_into('<IIQQQQQQ', data, 64 + index*56, *header)
        for index, entry in enumerate(dynamic):
            struct.pack_into('<qQ', data, 0x4000 + index*16, *entry)
        struct.pack_into('<6I', data, 0x4100, 1, 3, 1, 0, 2, 0)
        struct.pack_into('<IBBHQQ', data, 0x4158, 1, 0x12, 0, 1, 0x500, 36)
        struct.pack_into('<IBBHQQ', data, 0x4170, names.index(flag), 0x11, 0, 2, 0x4400, 1)
        data[0x41c0:0x41c0 + len(names)] = names
        struct.pack_into('<QQq', data, 0x4300, 0x4240, 1027, 0x440)
        struct.pack_into('<I', data, 0x440, 0xd65f03c0)  # constructor: ret
        # The setter verifies length and a readable flag pointer, then records
        # application of the flag. Both signatures receive length in w1/x1.
        instructions = [0x7100243f, 0x540000e1, 0x39400008, 0x7100b51f,
                        0x54000081, adr(9, 0x514, 0x4400), 0x52800028,
                        0x39000128, 0xd65f03c0]
        struct.pack_into('<9I', data, 0x500, *instructions)
        section_names = b'\0.text\0.data\0.dynsym\0.dynstr\0.hash\0.dynamic\0.rela.dyn\0.shstrtab\0'
        data[0x4d00:0x4d00 + len(section_names)] = section_names
        sections = [(b'.text', 1, 6, 0x400, 0x400, 0x200, 0, 0, 4, 0),
                    (b'.data', 1, 3, 0x4400, 0x4400, 8, 0, 0, 8, 0),
                    (b'.dynsym', 11, 2, 0x4140, 0x4140, 72, 4, 1, 8, 24),
                    (b'.dynstr', 3, 2, 0x41c0, 0x41c0, len(names), 0, 0, 1, 0),
                    (b'.hash', 5, 2, 0x4100, 0x4100, 24, 3, 0, 8, 4),
                    (b'.dynamic', 6, 3, 0x4000, 0x4000, len(dynamic)*16, 4, 0, 8, 16),
                    (b'.rela.dyn', 4, 2, 0x4300, 0x4300, 24, 3, 0, 8, 24),
                    (b'.shstrtab', 3, 0, 0, 0x4d00, len(section_names), 0, 0, 1, 0)]
        for index, (name, *rest) in enumerate(sections, 1):
            struct.pack_into('<IIQQQQIIQQ', data, 0x5000 + index*64,
                             section_names.index(name), *rest)
        (destination / f'libv8_{owner}_{length}.so').write_bytes(data)
