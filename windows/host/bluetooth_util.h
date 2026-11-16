// bluetooth_util.h -- the pure parts of the Bluetooth host (native_bluetooth.cpp):
// addresses and UUIDs between their Android text forms and Windows' binary
// ones, the advertisement bytes an LE scan result carries, and the IIDs of
// WinRT's parameterized interfaces (IVectorView<T>, TypedEventHandler<S, A>,
// AsyncOperationCompletedHandler<T>), which no header provides and which are
// derived from the type signature as the WinRT type system does. No Windows
// header is used, so test/bluetooth_test.cpp checks all of it on any host.

#pragma once

#include <array>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace winapk::host::bt {

// A GUID in memory layout (the fields of Windows' GUID struct).
struct Guid {
    uint32_t data1 = 0;
    uint16_t data2 = 0;
    uint16_t data3 = 0;
    uint8_t data4[8] = {};
};

inline bool operator==(const Guid& a, const Guid& b) { return std::memcmp(&a, &b, sizeof(Guid)) == 0; }

// ---- SHA-1 (FIPS 180-4), for the parameterized IIDs ------------------------

inline std::array<uint8_t, 20> sha1(const uint8_t* data, size_t len) {
    uint32_t h[5] = {0x67452301u, 0xEFCDAB89u, 0x98BADCFEu, 0x10325476u, 0xC3D2E1F0u};
    std::vector<uint8_t> m(data, data + len);
    m.push_back(0x80);
    while (m.size() % 64 != 56) m.push_back(0);
    const uint64_t bits = static_cast<uint64_t>(len) * 8;
    for (int i = 7; i >= 0; --i) m.push_back(static_cast<uint8_t>(bits >> (i * 8)));
    auto rol = [](uint32_t v, int n) { return (v << n) | (v >> (32 - n)); };
    for (size_t off = 0; off < m.size(); off += 64) {
        uint32_t w[80];
        for (int i = 0; i < 16; ++i) {
            w[i] = (static_cast<uint32_t>(m[off + i * 4]) << 24) | (static_cast<uint32_t>(m[off + i * 4 + 1]) << 16) |
                   (static_cast<uint32_t>(m[off + i * 4 + 2]) << 8) | m[off + i * 4 + 3];
        }
        for (int i = 16; i < 80; ++i) w[i] = rol(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
        uint32_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4];
        for (int i = 0; i < 80; ++i) {
            uint32_t f, k;
            if (i < 20) {
                f = (b & c) | (~b & d);
                k = 0x5A827999u;
            } else if (i < 40) {
                f = b ^ c ^ d;
                k = 0x6ED9EBA1u;
            } else if (i < 60) {
                f = (b & c) | (b & d) | (c & d);
                k = 0x8F1BBCDCu;
            } else {
                f = b ^ c ^ d;
                k = 0xCA62C1D6u;
            }
            const uint32_t t = rol(a, 5) + f + e + k + w[i];
            e = d;
            d = c;
            c = rol(b, 30);
            b = a;
            a = t;
        }
        h[0] += a;
        h[1] += b;
        h[2] += c;
        h[3] += d;
        h[4] += e;
    }
    std::array<uint8_t, 20> out{};
    for (int i = 0; i < 5; ++i) {
        out[i * 4] = static_cast<uint8_t>(h[i] >> 24);
        out[i * 4 + 1] = static_cast<uint8_t>(h[i] >> 16);
        out[i * 4 + 2] = static_cast<uint8_t>(h[i] >> 8);
        out[i * 4 + 3] = static_cast<uint8_t>(h[i]);
    }
    return out;
}

// ---- GUID text -------------------------------------------------------------

inline int hex_digit(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

// "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" (java.util.UUID.toString, any case,
// braces allowed) to a GUID. False when the text is not one.
inline bool guid_parse(const std::string& text, Guid* out) {
    std::string hex;
    for (char c : text) {
        if (c == '-' || c == '{' || c == '}') continue;
        if (hex_digit(c) < 0) return false;
        hex += c;
    }
    if (hex.size() != 32) return false;
    uint8_t b[16];
    for (int i = 0; i < 16; ++i) b[i] = static_cast<uint8_t>(hex_digit(hex[i * 2]) * 16 + hex_digit(hex[i * 2 + 1]));
    out->data1 = (static_cast<uint32_t>(b[0]) << 24) | (static_cast<uint32_t>(b[1]) << 16) |
                 (static_cast<uint32_t>(b[2]) << 8) | b[3];
    out->data2 = static_cast<uint16_t>((b[4] << 8) | b[5]);
    out->data3 = static_cast<uint16_t>((b[6] << 8) | b[7]);
    std::memcpy(out->data4, b + 8, 8);
    return true;
}

// The lower-case text java.util.UUID.toString gives.
inline std::string guid_text(const Guid& g) {
    char out[40];
    std::snprintf(out, sizeof out, "%08x-%04x-%04x-%02x%02x-%02x%02x%02x%02x%02x%02x", g.data1, g.data2, g.data3,
                  g.data4[0], g.data4[1], g.data4[2], g.data4[3], g.data4[4], g.data4[5], g.data4[6], g.data4[7]);
    return out;
}

// The Bluetooth base UUID with a 16- or 32-bit UUID in it:
// 0000xxxx-0000-1000-8000-00805F9B34FB.
inline Guid bluetooth_uuid(uint32_t uuid) {
    Guid g;
    g.data1 = uuid;
    g.data2 = 0;
    g.data3 = 0x1000;
    const uint8_t base[8] = {0x80, 0x00, 0x00, 0x80, 0x5F, 0x9B, 0x34, 0xFB};
    std::memcpy(g.data4, base, 8);
    return g;
}

// ---- parameterized IIDs ------------------------------------------------------

// The IID of a WinRT parameterized interface instance, from its signature:
// "pinterface({base-iid};arg;arg)", where a class argument is
// "rc(Namespace.Class;{default-interface-iid})", an enum
// "enum(Namespace.Enum;i4)", a struct "struct(...)", IInspectable
// "cinterface(IInspectable)" and the primitives "u1", "i2", "u4", "g16"... It
// is UUID version 5 (RFC 4122) of the signature in the pinterface namespace
// {11f47ad5-7b73-42c0-abae-878b1e16adee}.
inline Guid parameterized_iid(const std::string& signature) {
    const uint8_t ns[16] = {0x11, 0xf4, 0x7a, 0xd5, 0x7b, 0x73, 0x42, 0xc0,
                            0xab, 0xae, 0x87, 0x8b, 0x1e, 0x16, 0xad, 0xee};
    std::vector<uint8_t> m(ns, ns + 16);
    m.insert(m.end(), signature.begin(), signature.end());
    std::array<uint8_t, 20> h = sha1(m.data(), m.size());
    h[6] = static_cast<uint8_t>((h[6] & 0x0F) | 0x50);
    h[8] = static_cast<uint8_t>((h[8] & 0x3F) | 0x80);
    Guid g;
    g.data1 = (static_cast<uint32_t>(h[0]) << 24) | (static_cast<uint32_t>(h[1]) << 16) |
              (static_cast<uint32_t>(h[2]) << 8) | h[3];
    g.data2 = static_cast<uint16_t>((h[4] << 8) | h[5]);
    g.data3 = static_cast<uint16_t>((h[6] << 8) | h[7]);
    std::memcpy(g.data4, h.data() + 8, 8);
    return g;
}

// The signature of a runtime class, for parameterized_iid.
inline std::string class_signature(const std::string& name, const Guid& default_interface) {
    return "rc(" + name + ";{" + guid_text(default_interface) + "})";
}

// ---- addresses --------------------------------------------------------------

// "AA:BB:CC:DD:EE:FF" (Android's form, most significant byte first) to the
// 48-bit number Windows uses (BTH_ADDR, BluetoothAddress). False when the
// text is not an address.
inline bool address_parse(const std::string& text, uint64_t* out) {
    if (text.size() != 17) return false;
    uint64_t v = 0;
    for (int i = 0; i < 6; ++i) {
        const int hi = hex_digit(text[i * 3]), lo = hex_digit(text[i * 3 + 1]);
        if (hi < 0 || lo < 0) return false;
        if (i < 5 && text[i * 3 + 2] != ':') return false;
        v = (v << 8) | static_cast<uint64_t>(hi * 16 + lo);
    }
    *out = v;
    return true;
}

// The number back to Android's upper-case text.
inline std::string address_text(uint64_t a) {
    char out[18];
    std::snprintf(out, sizeof out, "%02X:%02X:%02X:%02X:%02X:%02X", static_cast<unsigned>((a >> 40) & 0xFF),
                  static_cast<unsigned>((a >> 32) & 0xFF), static_cast<unsigned>((a >> 24) & 0xFF),
                  static_cast<unsigned>((a >> 16) & 0xFF), static_cast<unsigned>((a >> 8) & 0xFF),
                  static_cast<unsigned>(a & 0xFF));
    return out;
}

// ---- advertisements -----------------------------------------------------------

// One AD structure of an advertisement, as Windows hands them out
// (BluetoothLEAdvertisementDataSection): the type and the bytes after it.
struct AdSection {
    uint8_t type;
    std::vector<uint8_t> data;
};

// The raw advertising data Android's ScanRecord is parsed from: the
// structures back in wire form, {length, type, data...} each, with a structure
// too long for one (over 254 bytes of data) left out rather than written with
// a wrong length. Windows parses the packet into sections; this is the
// inverse, so apps that read ScanRecord.getBytes() get what they get on a
// phone.
inline std::vector<uint8_t> advertisement_bytes(const std::vector<AdSection>& sections) {
    std::vector<uint8_t> out;
    for (const AdSection& s : sections) {
        if (s.data.size() > 254) continue;
        out.push_back(static_cast<uint8_t>(s.data.size() + 1));
        out.push_back(s.type);
        out.insert(out.end(), s.data.begin(), s.data.end());
    }
    return out;
}

}  // namespace winapk::host::bt
