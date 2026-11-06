#!/usr/bin/env python3
"""Two real DEX classes whose method preparation can finish after factory return."""
import hashlib,struct,sys,zlib
from pathlib import Path
def uleb(v):
 out=bytearray()
 while True:
  b=v&127;v>>=7;out.append(b|(128 if v else 0))
  if not v:return out
names=['Lfixture/DynamicAlpha;','Lfixture/DynamicBeta;']
strings=sorted(['I','Ljava/lang/Object;','value']+names);types=sorted(['I','Ljava/lang/Object;']+names)
s_off=112;t_off=s_off+4*len(strings);p_off=t_off+4*len(types);m_off=p_off+12;c_off=m_off+16;data_off=c_off+64
b=bytearray(data_off);str_offsets=[]
for s in strings:str_offsets.append(len(b));b+=uleb(len(s))+s.encode()+b'\0'
while len(b)%4:b.append(0)
code_offsets=[]
for value in [42,43]:
 code_offsets.append(len(b));b+=struct.pack('<HHHHII3H',1,0,0,0,0,3,0x13,value,0x0f)
 while len(b)%4:b.append(0)
class_offsets=[]
for i in range(2):
 class_offsets.append(len(b));b+=bytes([0,0,1,0])+uleb(i)+uleb(9)+uleb(code_offsets[i])
while len(b)%4:b.append(0)
map_off=len(b);sections=[(0,1,0),(1,len(strings),s_off),(2,len(types),t_off),(3,1,p_off),(5,2,m_off),(6,2,c_off),(0x2002,len(strings),data_off),(0x2001,2,code_offsets[0]),(0x2000,2,class_offsets[0]),(0x1000,1,map_off)]
b+=struct.pack('<I',len(sections))
for k,n,o in sorted(sections,key=lambda x:x[2]):b+=struct.pack('<HHII',k,0,n,o)
b[:8]=b'dex\n035\0';struct.pack_into('<20I',b,32,len(b),112,0x12345678,0,0,map_off,len(strings),s_off,len(types),t_off,1,p_off,0,0,2,m_off,2,c_off,len(b)-data_off,data_off)
for i,o in enumerate(str_offsets):struct.pack_into('<I',b,s_off+4*i,o)
for i,s in enumerate(types):struct.pack_into('<I',b,t_off+4*i,strings.index(s))
struct.pack_into('<3I',b,p_off,strings.index('I'),types.index('I'),0)
for i,name in enumerate(names):
 struct.pack_into('<HHI',b,m_off+8*i,types.index(name),0,strings.index('value'))
 struct.pack_into('<8I',b,c_off+32*i,types.index(name),1,types.index('Ljava/lang/Object;'),0,0xffffffff,0,class_offsets[i],0)
b[12:32]=hashlib.sha1(b[32:]).digest();struct.pack_into('<I',b,8,zlib.adler32(b[12:])&0xffffffff)
Path(sys.argv[1]).write_bytes(b)
Path(sys.argv[2]).write_text(' '.join(str(x+16) for x in code_offsets))
