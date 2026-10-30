#pragma once
#include <cstdint>
#include <cstring>

namespace macapk::rwx {
struct Registers { uint64_t x[31], sp; uint8_t v[32][16]; };
struct Store {
    uint64_t address = 0, nextBase = 0;
    unsigned bytes = 0, baseReg = 0;
    bool writeback = false;
    uint8_t data[32]{};
};
inline int64_t signedBits(uint32_t x, unsigned bits) {
    return static_cast<int64_t>(x ^ (1u << (bits-1))) - (1u << (bits-1));
}
// Decode ordinary A64 stores only. Atomics, loads and unknown instructions
// are deliberately rejected; this never manufactures an exclusive-store success.
inline bool decode(uint32_t i, const Registers& r, Store& out) {
    Store s; bool pair=false;
    unsigned rn=(i>>5)&31, rt=i&31, size=i>>30, opc=(i>>22)&3;
    bool simd=(i>>26)&1;
    uint64_t base=rn==31?r.sp:r.x[rn];
    s.baseReg=rn;
    if ((i & 0x3b000000u)==0x39000000u || (i & 0x3b000000u)==0x38000000u) {
        if ((!simd && opc!=0) || (simd && (opc&1))) return false;
        if (simd && (opc&2) && size!=0) return false;
        unsigned scale=(simd && (opc&2))?4:size;
        s.bytes=1u<<scale;
        if ((i & 0x3b000000u)==0x39000000u) s.address=base+uint64_t((i>>10)&0xfff)*s.bytes;
        else if ((i & 0x00200c00u)==0x00200800u) {
            unsigned rm=(i>>16)&31, option=(i>>13)&7;
            uint64_t index=rm==31?0:r.x[rm];
            if(option==2) index=uint32_t(index);
            else if(option==6) index=int64_t(int32_t(index));
            else if(option==7) index=int64_t(index);
            else if(option!=3) return false;
            if((i>>12)&1) index<<=scale;
            s.address=base+index;
        } else {
            if(i & (1u<<21)) return false;
            unsigned mode=(i>>10)&3;
            if(mode==2) return false; // unprivileged STTR
            int64_t offset=signedBits((i>>12)&0x1ff,9);
            s.address=mode==1?base:base+offset;
            s.writeback=mode==1 || mode==3;
            s.nextBase=base+offset;
        }
        if(simd) std::memcpy(s.data,r.v[rt],s.bytes);
        else { uint64_t value=rt==31?0:r.x[rt]; std::memcpy(s.data,&value,s.bytes); }
    } else if ((i & 0x3a000000u)==0x28000000u && !(i & (1u<<22))) {
        pair=true;
        unsigned mode=(i>>23)&3, rt2=(i>>10)&31;
        if(mode==0 || (!simd && size!=0 && size!=2) || (simd && size==3)) return false;
        unsigned width=simd?(4u<<size):(size==2?8:4);
        int64_t offset=signedBits((i>>15)&0x7f,7)*width;
        s.bytes=width*2; s.address=mode==1?base:base+offset;
        s.writeback=mode==1 || mode==3; s.nextBase=base+offset;
        if(simd) { std::memcpy(s.data,r.v[rt],width); std::memcpy(s.data+width,r.v[rt2],width); }
        else { uint64_t a=rt==31?0:r.x[rt], b=rt2==31?0:r.x[rt2]; std::memcpy(s.data,&a,width); std::memcpy(s.data+width,&b,width); }
    } else return false;
    // Architecturally constrained-unpredictable overlapping writeback is not
    // emulated. SIMD register numbers do not alias the GPR base.
    if(s.writeback && !simd && rn!=31 && (rn==rt || (pair && rn==((i>>10)&31)))) return false;
    out=s; return true;
}
} // namespace macapk::rwx
