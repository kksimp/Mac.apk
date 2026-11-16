// bluetooth_test.cpp -- winapk-bluetooth-test: the pure parts of the
// Bluetooth host (host/bluetooth_util.h), without a radio.
//
//   winapk-bluetooth-test
//
// Checks: SHA-1 against FIPS vectors; the parameterized-IID derivation against
// IIDs the SDK headers publish (IAsyncOperation<boolean>, IReference<int>,
// TypedEventHandler<IInspectable, IInspectable>) and the two Geolocator
// handlers native_location.cpp already uses; the UUID and address
// conversions both ways; the advertisement bytes a scan result carries.
// Builds on any host: no Windows header is involved.

#include <cstdio>
#include <string>
#include <vector>

#include "../host/bluetooth_util.h"

namespace bt = winapk::host::bt;

namespace {

int g_failures = 0;

void check(const char* what, bool ok) {
    std::printf("  [%s] %s\n", ok ? "PASS" : "FAIL", what);
    if (!ok) ++g_failures;
}

std::string hex(const uint8_t* b, size_t n) {
    std::string s;
    char buf[3];
    for (size_t i = 0; i < n; ++i) {
        std::snprintf(buf, sizeof buf, "%02x", b[i]);
        s += buf;
    }
    return s;
}

std::string sha1_hex(const std::string& s) {
    const auto h = bt::sha1(reinterpret_cast<const uint8_t*>(s.data()), s.size());
    return hex(h.data(), h.size());
}

}  // namespace

int main() {
    std::printf("SHA-1\n");
    check("empty", sha1_hex("") == "da39a3ee5e6b4b0d3255bfef95601890afd80709");
    check("abc", sha1_hex("abc") == "a9993e364706816aba3e25717850c26c9cd0d89d");
    check("two blocks",
          sha1_hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq") ==
              "84983e441c3bd26ebaae4aa1f95129e5e54670f1");
    check("million a", [] {
        std::string s(1000000, 'a');
        return sha1_hex(s) == "34aa973cd4c4daa4f61eeb2bdbad27316534016f";
    }());

    std::printf("parameterized IIDs\n");
    check("IAsyncOperation<boolean>",
          bt::guid_text(bt::parameterized_iid("pinterface({9fc2b0bb-e446-44e2-aa61-9cab8f636af2};b1)")) ==
              "cdb5efb3-5788-509d-9be1-71ccb8a3362a");
    check("IReference<int>",
          bt::guid_text(bt::parameterized_iid("pinterface({61c17706-2d65-11e0-9ae8-d48564015472};i4)")) ==
              "548cefbd-bc8a-5fa0-8df2-957440fc8bf4");
    check("TypedEventHandler<IInspectable, IInspectable>",
          bt::guid_text(bt::parameterized_iid("pinterface({9de1c534-6ae1-11e0-84e1-18a905bcc53f};"
                                              "cinterface(IInspectable);cinterface(IInspectable))")) ==
              "c7e65ce2-fad5-5e3b-9c58-186ca8c1dd57");
    bt::Guid geolocator, position_args, status_args;
    bt::guid_parse("a9c3bf62-4524-4989-8aa9-de019d2e551f", &geolocator);
    bt::guid_parse("37859ce5-9d1e-46c5-bf3b-6ad8cac1a093", &position_args);
    bt::guid_parse("3453d2da-8c93-4111-a205-9aecfc9be5c0", &status_args);
    const std::string handler = "pinterface({9de1c534-6ae1-11e0-84e1-18a905bcc53f};";
    check("TypedEventHandler<Geolocator, PositionChangedEventArgs> (native_location.cpp)",
          bt::guid_text(bt::parameterized_iid(
              handler + bt::class_signature("Windows.Devices.Geolocation.Geolocator", geolocator) + ";" +
              bt::class_signature("Windows.Devices.Geolocation.PositionChangedEventArgs", position_args) + ")")) ==
              "df3c6164-4e7b-5e8e-9a7e-13da059dec1e");
    check("TypedEventHandler<Geolocator, StatusChangedEventArgs> (native_location.cpp)",
          bt::guid_text(bt::parameterized_iid(
              handler + bt::class_signature("Windows.Devices.Geolocation.Geolocator", geolocator) + ";" +
              bt::class_signature("Windows.Devices.Geolocation.StatusChangedEventArgs", status_args) + ")")) ==
              "97fcf582-de6b-5cd3-9690-e2ecbb66da4d");

    std::printf("UUIDs\n");
    bt::Guid g;
    check("parse", bt::guid_parse("00001101-0000-1000-8000-00805F9B34FB", &g) && g.data1 == 0x1101 && g.data2 == 0 &&
                       g.data3 == 0x1000 && g.data4[0] == 0x80 && g.data4[7] == 0xFB);
    check("parse braces", bt::guid_parse("{6E400001-B5A3-F393-E0A9-E50E24DCCA9E}", &g) && g.data1 == 0x6E400001 &&
                              g.data2 == 0xB5A3 && g.data3 == 0xF393 && g.data4[0] == 0xE0 && g.data4[1] == 0xA9);
    check("text", bt::guid_text(g) == "6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    check("round trip",
          bt::guid_parse(bt::guid_text(g), &g) && bt::guid_text(g) == "6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    check("16-bit", bt::guid_text(bt::bluetooth_uuid(0x180F)) == "0000180f-0000-1000-8000-00805f9b34fb");
    check("16-bit equals parsed",
          bt::guid_parse("0000180F-0000-1000-8000-00805F9B34FB", &g) && g == bt::bluetooth_uuid(0x180F));
    check("not a uuid", !bt::guid_parse("hello", &g) && !bt::guid_parse("00001101-0000-1000-8000-00805F9B34F", &g));

    std::printf("addresses\n");
    uint64_t a = 0;
    check("parse", bt::address_parse("A4:C1:38:9E:00:7F", &a) && a == 0xA4C1389E007FULL);
    check("parse lower case", bt::address_parse("a4:c1:38:9e:00:7f", &a) && a == 0xA4C1389E007FULL);
    check("text", bt::address_text(0xA4C1389E007FULL) == "A4:C1:38:9E:00:7F");
    check("text of zero", bt::address_text(0) == "00:00:00:00:00:00");
    check("rejects", !bt::address_parse("A4C1389E007F", &a) && !bt::address_parse("A4:C1:38:9E:00:7G", &a) &&
                         !bt::address_parse("A4-C1-38-9E-00-7F", &a));

    std::printf("advertisements\n");
    const std::vector<bt::AdSection> sections = {
        {0x01, {0x06}},
        {0x03, {0x0F, 0x18}},
        {0x09, {'H', 'R', 'M'}},
        {0xFF, {0x4C, 0x00, 0x02, 0x15}},
    };
    const std::vector<uint8_t> want = {0x02, 0x01, 0x06, 0x03, 0x03, 0x0F, 0x18, 0x04, 0x09,
                                       'H',  'R',  'M',  0x05, 0xFF, 0x4C, 0x00, 0x02, 0x15};
    check("wire form", bt::advertisement_bytes(sections) == want);
    check("empty", bt::advertisement_bytes({}).empty());
    check("oversized section left out",
          bt::advertisement_bytes({{0x16, std::vector<uint8_t>(255, 1)}, {0x01, {0x06}}}) ==
              std::vector<uint8_t>({0x02, 0x01, 0x06}));

    std::printf("%s\n", g_failures ? "FAILED" : "OK");
    return g_failures ? 1 : 0;
}
