#!/usr/bin/env python3
"""AArch64 ELF shared objects for the static-integrity `svc #0` regression.

usage: make_integrity_image_fixture.py <work-dir>

  <work-dir>/libintegrityimage.so
      A marker-delimited integrity region on the executable segment, laid out
      the way a static integrity self-check lays one out: a start word
      (F1 F6 77 FF), the bytes under check, an end word (F1 F6 77 F0). The bytes
      under check hold two `svc #0` words that are never executed, only hashed.
      integrity_probe() CRCs them at run time the way such a check does
      (CRC32, initial value 0x20190512, reflected polynomial 0xEDB88320), so
      the answer only matches the file when the loader left every byte alone.
      The image also carries an ordinary, executable `svc #0` outside the
      region; integrity_plain_svc_word() reads that word back, and
      integrity_plain_syscall() performs the syscall.

  <work-dir>/libplainimage.so
      The same executable `svc #0` (plain_svc_word() reads that word back), and no
      integrity region: the control that the rewrite still happens where
      nothing is self-checked.

The instructions are assembled by clang (an AArch64 ELF object, no sysroot
needed); the shared objects are then laid out byte for byte, as
debug/make_inline_file_fixture.py does, so the file is small and its bytes can
be read back by the test.
"""
from pathlib import Path
import struct
import subprocess
import sys
import tempfile

BODY_WORDS = [
    0xd2800020,  # mov  x0, #1
    0xd4000001,  # svc  #0        <- hashed, never executed
    0xd65f03c0,  # ret
    0xd4000001,  # svc  #0        <- hashed, never executed
    0x52800540,  # mov  w0, #42
    0xd503201f,  # nop
    0xd2801588,  # mov  x8, #172
    0xd65f03c0,  # ret
]
MARK_START = 0xFF77F6F1           # F1 F6 77 FF
MARK_END = 0xF077F6F1             # F1 F6 77 F0
BODY_BYTES = 4 * len(BODY_WORDS)

CRC_LOOP = """
    mov  w4, #8
.Lbit:
    and  w6, w0, #1
    lsr  w0, w0, #1
    neg  w6, w6
    and  w6, w6, w5
    eor  w0, w0, w6
    subs w4, w4, #1
    b.ne .Lbit
    subs x2, x2, #1
    b.ne .Lbyte
    ret
"""

INTEGRITY_TEMPLATE = """
.text
.p2align 2
.globl integrity_probe
.type integrity_probe, %function
integrity_probe:
    movz w0, #0x0512
    movk w0, #0x2019, lsl #16     // 0x20190512
    adr  x1, integrity_body
    movz x2, #@BODY_BYTES@
    movz w5, #0x8320
    movk w5, #0xEDB8, lsl #16     // 0xEDB88320
.Lbyte:
    ldrb w3, [x1], #1
    eor  w0, w0, w3
@CRC_LOOP@
.size integrity_probe, . - integrity_probe

.globl integrity_region_word
.type integrity_region_word, %function
integrity_region_word:             // w0 = word index -> w0 = that word of the region
    cmp  w0, #@BODY_WORDS@
    b.hs 2f
    adr  x1, integrity_body
    ldr  w0, [x1, x0, lsl #2]
    ret
2:  mov  w0, #0
    ret
.size integrity_region_word, . - integrity_region_word

.globl integrity_plain_svc_word
.type integrity_plain_svc_word, %function
integrity_plain_svc_word:          // -> the `svc #0` word of plain_svc_code, as loaded
    adr  x1, plain_svc_code
    ldr  w0, [x1, #8]
    ret
.size integrity_plain_svc_word, . - integrity_plain_svc_word

.globl integrity_plain_syscall
.type integrity_plain_syscall, %function
integrity_plain_syscall:           // -> getpid() through plain_svc_code, whatever became of its `svc #0`
    stp  x29, x30, [sp, #-16]!     // keep the caller's return address across the call
    bl   plain_svc_code
    ldp  x29, x30, [sp], #16
    ret
.size integrity_plain_syscall, . - integrity_plain_syscall

// Not exported: the loader plants a trampoline at every exported function's
// address, and this code has to stay readable as it was assembled.
plain_svc_code:                    // an ordinary Linux syscall, outside the region
    movz x8, #172                  // __NR_getpid: AArch64 Linux keeps the number in x8
    movn x16, #0                   // XNU reads x16 at `svc`; an invalid Mach trap number
    svc  #0                        //   makes it raise SIGSYS and leave the args alone
    ret

.p2align 2
integrity_start:
    .word @MARK_START@
integrity_body:
    .word @BODY_WORDS_LIST@
integrity_end:
    .word @MARK_END@
"""
# `.type name, %function` makes %-formatting unusable here; plain tokens instead.
INTEGRITY = (INTEGRITY_TEMPLATE
             .replace("@BODY_BYTES@", str(BODY_BYTES))
             .replace("@CRC_LOOP@", CRC_LOOP)
             .replace("@BODY_WORDS@", str(len(BODY_WORDS)))
             .replace("@MARK_START@", "0x%08X" % MARK_START)
             .replace("@MARK_END@", "0x%08X" % MARK_END)
             .replace("@BODY_WORDS_LIST@", ", ".join("0x%08X" % w for w in BODY_WORDS)))

PLAIN = """
.text
.p2align 2
plain_svc_code:
    movz x8, #172                  // __NR_getpid: AArch64 Linux keeps the number in x8
    movn x16, #0                   // XNU reads x16 at `svc`; an invalid Mach trap number
    svc  #0                        //   makes it raise SIGSYS and leave the args alone
    ret

.globl plain_svc_word
.type plain_svc_word, %function
plain_svc_word:                    // -> the `svc #0` word of plain_svc_code, as loaded
    adr  x1, plain_svc_code
    ldr  w0, [x1, #8]
    ret
.size plain_svc_word, . - plain_svc_word
"""


def assemble(source, work, name):
    """Assemble `source`; return (.text bytes, [global symbol names], {name: offset})."""
    s, o = work / (name + ".s"), work / (name + ".o")
    s.write_text(source)
    subprocess.run(["clang", "--target=aarch64-linux-gnu", "-c", str(s), "-o", str(o)], check=True)
    elf = o.read_bytes()
    e_shoff, = struct.unpack_from("<Q", elf, 0x28)
    e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", elf, 0x3A)
    shdrs = [struct.unpack_from("<IIQQQQIIQQ", elf, e_shoff + i * e_shentsize) for i in range(e_shnum)]
    strtab_off = shdrs[e_shstrndx][4]
    sections = {}
    for i, sh in enumerate(shdrs):
        nm = elf[strtab_off + sh[0]: elf.index(b"\0", strtab_off + sh[0])].decode()
        sections[nm] = (i, sh)
    text_index, text = sections[".text"]
    code = elf[text[4]: text[4] + text[5]]
    order = []
    symtab_index, symtab = sections[".symtab"]
    strtab = sections[".strtab"][1][4]
    symbols = {}
    for p in range(symtab[4], symtab[4] + symtab[5], 24):
        st_name, st_info, st_other, st_shndx, st_value, st_size = struct.unpack_from("<IBBHQQ", elf, p)
        # The functions this fixture exports: GLOBAL, defined in .text. Local
        # labels stay out, and the first function sits at offset 0.
        if st_shndx != text_index or (st_info >> 4) != 1:
            continue
        end = elf.index(b"\0", strtab + st_name)
        name = elf[strtab + st_name: end].decode()
        order.append(name)
        symbols[name] = st_value
    return code, order, symbols


def shared_object(code, order, symbols, names):
    """A minimal ELF64 AArch64 shared object: the code at 0x400, dynamic tables at 0x10000."""
    data = bytearray(0x14400)
    data[0x400: 0x400 + len(code)] = code
    struct.pack_into("<16sHHIQQQIHHHHHH", data, 0, b"\x7fELF\x02\x01\x01" + bytes(9),
                     3, 183, 1, 0, 64, 0x14000, 0, 64, 56, 3, 64, 8, 8)
    dyn = [(4, 0x10100), (6, 0x10200), (5, 0x10400), (10, len(names)), (11, 24), (0, 0)]
    for i, ph in enumerate([(1, 5, 0, 0, 0, 0x8000, 0x8000, 0x4000),
                            (1, 6, 0x10000, 0x10000, 0x10000, 0x4000, 0x4000, 0x4000),
                            (2, 6, 0x10000, 0x10000, 0x10000, len(dyn) * 16, len(dyn) * 16, 8)]):
        struct.pack_into("<IIQQQQQQ", data, 64 + i * 56, *ph)
    for i, item in enumerate(dyn):
        struct.pack_into("<qQ", data, 0x10000 + i * 16, *item)
    count = len(order) + 1
    chain = [0] + [i + 1 if i + 1 < count else 0 for i in range(1, count)]
    words = [1, count, 1] + chain
    struct.pack_into("<" + "I" * len(words), data, 0x10100, *words)
    for i, name in enumerate(order, 1):
        struct.pack_into("<IBBHQQ", data, 0x10200 + i * 24,
                         names.index(name.encode()), 0x12, 0, 1, 0x400 + symbols[name], 0)
    data[0x10400: 0x10400 + len(names)] = names
    section_names = b"\0.text\0.dynsym\0.dynstr\0.hash\0.dynamic\0.shstrtab\0"
    data[0x13C00: 0x13C00 + len(section_names)] = section_names
    #            name          type flags addr     offset   size              link info align entsize
    sections = [(b".text",     1, 6, 0x400,   0x400,   0x7C00,           0,   0,   4,    0),
                (b".dynsym",  11, 2, 0x10200, 0x10200, count * 24,       3,   1,   8,   24),
                (b".dynstr",   3, 2, 0x10400, 0x10400, len(names),       0,   0,   1,    0),
                (b".hash",     5, 2, 0x10100, 0x10100, len(words) * 4,   2,   0,   8,    4),
                (b".dynamic",  6, 3, 0x10000, 0x10000, len(dyn) * 16,    3,   0,   8,   16),
                (b".shstrtab", 3, 0, 0,       0x13C00, len(section_names), 0,  0,   1,    0)]
    for i, (name, *rest) in enumerate(sections, 1):
        struct.pack_into("<IIQQQQIIQQ", data, 0x14000 + i * 64, section_names.index(name), *rest)
    return data


def main():
    work = Path(sys.argv[1])
    work.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=work) as tmp:
        tmp = Path(tmp)
        code, order, symbols = assemble(INTEGRITY, tmp, "integrity")
        names = b"\0" + b"".join(n.encode() + b"\0" for n in order)     # a name at offset 0 is "no name"
        (work / "libintegrityimage.so").write_bytes(shared_object(code, order, symbols, names))
        code, order, symbols = assemble(PLAIN, tmp, "plain")
        names = b"\0" + b"".join(n.encode() + b"\0" for n in order)
        (work / "libplainimage.so").write_bytes(shared_object(code, order, symbols, names))


if __name__ == "__main__":
    main()
