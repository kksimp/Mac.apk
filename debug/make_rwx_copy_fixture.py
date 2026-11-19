#!/usr/bin/env python3
"""AArch64 ELF whose imports exercise real mprotect/memcpy/memmove resolution."""
from pathlib import Path
import struct
import sys

data = bytearray(0x14400)
names = b"\0memcpy\0memmove\0mprotect\0memset\0fixture_copy\0fixture_move\0fixture_protect\0fixture_fill\0fixture_base\0fixture_value\0"
symbols = [("memcpy",0,0,0), ("memmove",0,0,0), ("mprotect",0,0,0), ("memset",0,0,0),
           ("fixture_copy",0x440,12,1), ("fixture_move",0x450,12,1),
           ("fixture_protect",0x460,12,1), ("fixture_fill",0x480,12,1), ("fixture_base",0x470,8,1),
           ("fixture_value",0x2000,8,1)]
dyn = [(4,0x10100), (6,0x10200), (5,0x10400), (10,len(names)),
       (11,24), (7,0x10800), (8,96), (9,24), (0,0)]
struct.pack_into('<16sHHIQQQIHHHHHH',data,0,b'\x7fELF\x02\x01\x01'+bytes(9),
                 3,183,1,0,64,0x14000,0,64,56,3,64,8,7)
for i,ph in enumerate([(1,5,0,0,0,0x8000,0x8000,0x4000),
                       (1,6,0x10000,0x10000,0x10000,0x4000,0x4000,0x4000),
                       (2,6,0x10000,0x10000,0x10000,len(dyn)*16,len(dyn)*16,8)]):
    struct.pack_into('<IIQQQQQQ',data,64+i*56,*ph)
for i,item in enumerate(dyn):struct.pack_into('<qQ',data,0x10000+i*16,*item)
count=len(symbols)+1
chain=[0]+[i+1 if i+1<count else 0 for i in range(1,count)]
words=[1,count,1]+chain
struct.pack_into('<'+'I'*len(words),data,0x10100,*words)
for i,(name,value,size,section) in enumerate(symbols,1):
    struct.pack_into('<IBBHQQ',data,0x10200+i*24,names.index(name.encode()),0x12,0,section,value,size)
data[0x10400:0x10400+len(names)]=names
def adr(reg,pc,target):
    delta=(target-pc)&((1<<21)-1)
    return 0x10000000|((delta&3)<<29)|((delta>>2)<<5)|reg
for i,pc in enumerate([0x440,0x450,0x460,0x480]):
    struct.pack_into('<3I',data,pc,adr(16,pc,0x10600+i*8),0xf9400210,0xd61f0200)
    struct.pack_into('<QQq',data,0x10800+i*24,0x10600+i*8,((i+1)<<32)|1025,0)
struct.pack_into('<2I',data,0x470,adr(0,0x470,0),0xd65f03c0)
struct.pack_into('<2I',data,0x2000,0x528000e0,0xd65f03c0)
section_names=b'\0.text\0.dynsym\0.dynstr\0.hash\0.dynamic\0.rela.dyn\0.shstrtab\0'
data[0x13c00:0x13c00+len(section_names)]=section_names
sections=[(b'.text',1,6,0x400,0x400,0x7c00,0,0,4,0),
          (b'.dynsym',11,2,0x10200,0x10200,count*24,3,1,8,24),
          (b'.dynstr',3,2,0x10400,0x10400,len(names),0,0,1,0),
          (b'.hash',5,2,0x10100,0x10100,len(words)*4,2,0,8,4),
          (b'.dynamic',6,3,0x10000,0x10000,len(dyn)*16,3,0,8,16),
          (b'.rela.dyn',4,2,0x10800,0x10800,96,2,0,8,24),
          (b'.shstrtab',3,0,0,0x13c00,len(section_names),0,0,1,0)]
for i,(name,*rest) in enumerate(sections,1):
    struct.pack_into('<IIQQQQIIQQ',data,0x14000+i*64,section_names.index(name),*rest)
Path(sys.argv[1]).write_bytes(data)
