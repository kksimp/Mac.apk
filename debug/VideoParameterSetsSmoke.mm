#include "../runtime/src/video_toolbox.mm"
#include <fstream>
#include <iterator>

// The tests exercise format-description construction, not presentation.
extern "C" void* macapk_vd_present_create(void*) { return nullptr; }
extern "C" int macapk_vd_present_to_layer(void*, void*, void*) { return 0; }
extern "C" void CocoaPresentVideoFrame(void*, int, int) {}
extern "C" int macapk_vtcaps_probe(const char*, int*, int) { return 0; }

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    std::ifstream stream(argv[1], std::ios::binary);
    std::vector<uint8_t> frame((std::istreambuf_iterator<char>(stream)), {});
    std::vector<uint8_t> sps, pps;
    for (const auto& nal : SplitAnnexB(frame.data(), frame.size())) {
        auto* destination = H264NalType(nal) == 7 ? &sps : H264NalType(nal) == 8 ? &pps : nullptr;
        if (destination && destination->empty()) {
            destination->insert(destination->end(), {0, 0, 0, 1});
            destination->insert(destination->end(), nal.ptr, nal.ptr + nal.len);
        }
    }
    if (sps.empty() || pps.empty()) return 2;
    auto check = [](const std::vector<uint8_t>& a, const std::vector<uint8_t>& b,
                    bool expected, const char* label) {
        CMVideoFormatDescriptionRef description = nullptr;
        bool result = BuildFormatDescriptionH264(a.data(), (int)a.size(),
                                                 b.data(), (int)b.size(), &description);
        if (description) CFRelease(description);
        if (result != expected) { std::fprintf(stderr, "FAIL: %s\n", label); return false; }
        std::printf("PASS: %s\n", label);
        return true;
    };
    auto combined = sps;
    combined.insert(combined.end(), pps.begin(), pps.end());
    if (!check(sps, pps, true, "separate SPS/PPS")) return 1;
    if (!check(combined, {}, true, "combined SPS/PPS in csd-0")) return 1;
    if (!check({}, combined, true, "combined SPS/PPS in csd-1")) return 1;
    if (!check(sps, {}, false, "reject missing PPS")) return 1;
    if (!check({}, pps, false, "reject missing SPS")) return 1;
    return 0;
}
