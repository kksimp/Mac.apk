// native_media.cpp -- libmediandk's data symbols for an app's native code.
//
// AMediaCodec, AMediaExtractor and the rest of the NDK media API are not
// ported. What is here are the AMEDIAFORMAT_KEY_* variables, so that a
// library that imports them loads with the real key strings, as on a device:
// each is a `const char*` variable holding a key name (NdkMediaFormat.h),
// pointers on both sides. Bound to zeroed memory instead, every key was a
// null pointer. The names and strings are AOSP's
// (frameworks/av/media/ndk/NdkMediaFormat.cpp).

#include "native_media.h"

#include <cstring>

namespace winapk::host {

namespace {

struct FormatKey {
    const char* name;
    const char* value;  // the exported variable itself
};

FormatKey kFormatKeys[] = {
    {"AMEDIAFORMAT_KEY_AAC_DRC_ATTENUATION_FACTOR", "aac-drc-cut-level"},
    {"AMEDIAFORMAT_KEY_AAC_DRC_BOOST_FACTOR", "aac-drc-boost-level"},
    {"AMEDIAFORMAT_KEY_AAC_DRC_HEAVY_COMPRESSION", "aac-drc-heavy-compression"},
    {"AMEDIAFORMAT_KEY_AAC_DRC_TARGET_REFERENCE_LEVEL", "aac-target-ref-level"},
    {"AMEDIAFORMAT_KEY_AAC_ENCODED_TARGET_LEVEL", "aac-encoded-target-level"},
    {"AMEDIAFORMAT_KEY_AAC_MAX_OUTPUT_CHANNEL_COUNT", "aac-max-output-channel_count"},
    {"AMEDIAFORMAT_KEY_AAC_PROFILE", "aac-profile"},
    {"AMEDIAFORMAT_KEY_AAC_SBR_MODE", "aac-sbr-mode"},
    {"AMEDIAFORMAT_KEY_ALBUM", "album"},
    {"AMEDIAFORMAT_KEY_ALBUMART", "albumart"},
    {"AMEDIAFORMAT_KEY_ALBUMARTIST", "albumartist"},
    {"AMEDIAFORMAT_KEY_ALLOW_FRAME_DROP", "allow-frame-drop"},
    {"AMEDIAFORMAT_KEY_ARTIST", "artist"},
    {"AMEDIAFORMAT_KEY_AUDIO_PRESENTATION_INFO", "audio-presentation-info"},
    {"AMEDIAFORMAT_KEY_AUDIO_PRESENTATION_PRESENTATION_ID", "audio-presentation-presentation-id"},
    {"AMEDIAFORMAT_KEY_AUDIO_PRESENTATION_PROGRAM_ID", "audio-presentation-program-id"},
    {"AMEDIAFORMAT_KEY_AUDIO_SESSION_ID", "audio-session-id"},
    {"AMEDIAFORMAT_KEY_AUTHOR", "author"},
    {"AMEDIAFORMAT_KEY_BITRATE_MODE", "bitrate-mode"},
    {"AMEDIAFORMAT_KEY_BIT_RATE", "bitrate"},
    {"AMEDIAFORMAT_KEY_BITS_PER_SAMPLE", "bits-per-sample"},
    {"AMEDIAFORMAT_KEY_CAPTURE_RATE", "capture-rate"},
    {"AMEDIAFORMAT_KEY_CDTRACKNUMBER", "cdtracknum"},
    {"AMEDIAFORMAT_KEY_CHANNEL_COUNT", "channel-count"},
    {"AMEDIAFORMAT_KEY_CHANNEL_MASK", "channel-mask"},
    {"AMEDIAFORMAT_KEY_COLOR_FORMAT", "color-format"},
    {"AMEDIAFORMAT_KEY_COLOR_RANGE", "color-range"},
    {"AMEDIAFORMAT_KEY_COLOR_STANDARD", "color-standard"},
    {"AMEDIAFORMAT_KEY_COLOR_TRANSFER", "color-transfer"},
    {"AMEDIAFORMAT_KEY_COMPILATION", "compilation"},
    {"AMEDIAFORMAT_KEY_COMPLEXITY", "complexity"},
    {"AMEDIAFORMAT_KEY_COMPOSER", "composer"},
    {"AMEDIAFORMAT_KEY_CREATE_INPUT_SURFACE_SUSPENDED", "create-input-buffers-suspended"},
    {"AMEDIAFORMAT_KEY_CRYPTO_DEFAULT_IV_SIZE", "crypto-default-iv-size"},
    {"AMEDIAFORMAT_KEY_CRYPTO_ENCRYPTED_BYTE_BLOCK", "crypto-encrypted-byte-block"},
    {"AMEDIAFORMAT_KEY_CRYPTO_ENCRYPTED_SIZES", "crypto-encrypted-sizes"},
    {"AMEDIAFORMAT_KEY_CRYPTO_IV", "crypto-iv"},
    {"AMEDIAFORMAT_KEY_CRYPTO_KEY", "crypto-key"},
    {"AMEDIAFORMAT_KEY_CRYPTO_MODE", "crypto-mode"},
    {"AMEDIAFORMAT_KEY_CRYPTO_PLAIN_SIZES", "crypto-plain-sizes"},
    {"AMEDIAFORMAT_KEY_CRYPTO_SKIP_BYTE_BLOCK", "crypto-skip-byte-block"},
    {"AMEDIAFORMAT_KEY_CSD", "csd"},
    {"AMEDIAFORMAT_KEY_CSD_0", "csd-0"},
    {"AMEDIAFORMAT_KEY_CSD_1", "csd-1"},
    {"AMEDIAFORMAT_KEY_CSD_2", "csd-2"},
    {"AMEDIAFORMAT_KEY_CSD_AVC", "csd-avc"},
    {"AMEDIAFORMAT_KEY_CSD_HEVC", "csd-hevc"},
    {"AMEDIAFORMAT_KEY_D263", "d263"},
    {"AMEDIAFORMAT_KEY_DATE", "date"},
    {"AMEDIAFORMAT_KEY_DISCNUMBER", "discnum"},
    {"AMEDIAFORMAT_KEY_DISPLAY_CROP", "crop"},
    {"AMEDIAFORMAT_KEY_DISPLAY_HEIGHT", "display-height"},
    {"AMEDIAFORMAT_KEY_DISPLAY_WIDTH", "display-width"},
    {"AMEDIAFORMAT_KEY_DURATION", "durationUs"},
    {"AMEDIAFORMAT_KEY_ENCODER_DELAY", "encoder-delay"},
    {"AMEDIAFORMAT_KEY_ENCODER_PADDING", "encoder-padding"},
    {"AMEDIAFORMAT_KEY_ESDS", "esds"},
    {"AMEDIAFORMAT_KEY_EXIF_OFFSET", "exif-offset"},
    {"AMEDIAFORMAT_KEY_EXIF_SIZE", "exif-size"},
    {"AMEDIAFORMAT_KEY_FLAC_COMPRESSION_LEVEL", "flac-compression-level"},
    {"AMEDIAFORMAT_KEY_FRAME_COUNT", "frame-count"},
    {"AMEDIAFORMAT_KEY_FRAME_RATE", "frame-rate"},
    {"AMEDIAFORMAT_KEY_GENRE", "genre"},
    {"AMEDIAFORMAT_KEY_GRID_COLUMNS", "grid-cols"},
    {"AMEDIAFORMAT_KEY_GRID_ROWS", "grid-rows"},
    {"AMEDIAFORMAT_KEY_HAPTIC_CHANNEL_COUNT", "haptic-channel-count"},
    {"AMEDIAFORMAT_KEY_HDR_STATIC_INFO", "hdr-static-info"},
    {"AMEDIAFORMAT_KEY_HDR10_PLUS_INFO", "hdr10-plus-info"},
    {"AMEDIAFORMAT_KEY_HEIGHT", "height"},
    {"AMEDIAFORMAT_KEY_ICC_PROFILE", "icc-profile"},
    {"AMEDIAFORMAT_KEY_IMPORTANCE", "importance"},
    {"AMEDIAFORMAT_KEY_INTRA_REFRESH_PERIOD", "intra-refresh-period"},
    {"AMEDIAFORMAT_KEY_IS_ADTS", "is-adts"},
    {"AMEDIAFORMAT_KEY_IS_AUTOSELECT", "is-autoselect"},
    {"AMEDIAFORMAT_KEY_IS_DEFAULT", "is-default"},
    {"AMEDIAFORMAT_KEY_IS_FORCED_SUBTITLE", "is-forced-subtitle"},
    {"AMEDIAFORMAT_KEY_IS_SYNC_FRAME", "is-sync-frame"},
    {"AMEDIAFORMAT_KEY_I_FRAME_INTERVAL", "i-frame-interval"},
    {"AMEDIAFORMAT_KEY_LANGUAGE", "language"},
    {"AMEDIAFORMAT_KEY_LAST_SAMPLE_INDEX_IN_CHUNK", "last-sample-index-in-chunk"},
    {"AMEDIAFORMAT_KEY_LATENCY", "latency"},
    {"AMEDIAFORMAT_KEY_LEVEL", "level"},
    {"AMEDIAFORMAT_KEY_LOCATION", "location"},
    {"AMEDIAFORMAT_KEY_LOOP", "loop"},
    {"AMEDIAFORMAT_KEY_LOW_LATENCY", "low-latency"},
    {"AMEDIAFORMAT_KEY_LYRICIST", "lyricist"},
    {"AMEDIAFORMAT_KEY_MANUFACTURER", "manufacturer"},
    {"AMEDIAFORMAT_KEY_MAX_BIT_RATE", "max-bitrate"},
    {"AMEDIAFORMAT_KEY_MAX_B_FRAMES", "max-bframes"},
    {"AMEDIAFORMAT_KEY_MAX_FPS_TO_ENCODER", "max-fps-to-encoder"},
    {"AMEDIAFORMAT_KEY_MAX_HEIGHT", "max-height"},
    {"AMEDIAFORMAT_KEY_MAX_INPUT_SIZE", "max-input-size"},
    {"AMEDIAFORMAT_KEY_BUFFER_BATCH_MAX_OUTPUT_SIZE", "buffer-batch-max-output-size"},
    {"AMEDIAFORMAT_KEY_BUFFER_BATCH_THRESHOLD_OUTPUT_SIZE", "buffer-batch-threshold-output-size"},
    {"AMEDIAFORMAT_KEY_MAX_PTS_GAP_TO_ENCODER", "max-pts-gap-to-encoder"},
    {"AMEDIAFORMAT_KEY_MAX_WIDTH", "max-width"},
    {"AMEDIAFORMAT_KEY_MIME", "mime"},
    {"AMEDIAFORMAT_KEY_MPEG_USER_DATA", "mpeg-user-data"},
    {"AMEDIAFORMAT_KEY_MPEG2_STREAM_HEADER", "mpeg2-stream-header"},
    {"AMEDIAFORMAT_KEY_MPEGH_COMPATIBLE_SETS", "mpegh-compatible-sets"},
    {"AMEDIAFORMAT_KEY_MPEGH_PROFILE_LEVEL_INDICATION", "mpegh-profile-level-indication"},
    {"AMEDIAFORMAT_KEY_MPEGH_REFERENCE_CHANNEL_LAYOUT", "mpegh-reference-channel-layout"},
    {"AMEDIAFORMAT_KEY_OPERATING_RATE", "operating-rate"},
    {"AMEDIAFORMAT_KEY_PCM_ENCODING", "pcm-encoding"},
    {"AMEDIAFORMAT_KEY_PICTURE_TYPE", "picture-type"},
    {"AMEDIAFORMAT_KEY_PRIORITY", "priority"},
    {"AMEDIAFORMAT_KEY_PROFILE", "profile"},
    {"AMEDIAFORMAT_KEY_PCM_BIG_ENDIAN", "pcm-big-endian"},
    {"AMEDIAFORMAT_KEY_PSSH", "pssh"},
    {"AMEDIAFORMAT_KEY_PUSH_BLANK_BUFFERS_ON_STOP", "push-blank-buffers-on-shutdown"},
    {"AMEDIAFORMAT_KEY_REPEAT_PREVIOUS_FRAME_AFTER", "repeat-previous-frame-after"},
    {"AMEDIAFORMAT_KEY_ROTATION", "rotation-degrees"},
    {"AMEDIAFORMAT_KEY_SAMPLE_FILE_OFFSET", "sample-file-offset"},
    {"AMEDIAFORMAT_KEY_SAMPLE_RATE", "sample-rate"},
    {"AMEDIAFORMAT_KEY_SAMPLE_TIME_BEFORE_APPEND", "sample-time-before-append"},
    {"AMEDIAFORMAT_KEY_SAR_HEIGHT", "sar-height"},
    {"AMEDIAFORMAT_KEY_SAR_WIDTH", "sar-width"},
    {"AMEDIAFORMAT_KEY_SEI", "sei"},
    {"AMEDIAFORMAT_KEY_SLICE_HEIGHT", "slice-height"},
    {"AMEDIAFORMAT_KEY_SLOW_MOTION_MARKERS", "slow-motion-markers"},
    {"AMEDIAFORMAT_KEY_STRIDE", "stride"},
    {"AMEDIAFORMAT_KEY_TARGET_TIME", "target-time"},
    {"AMEDIAFORMAT_KEY_TEMPORAL_LAYER_COUNT", "temporal-layer-count"},
    {"AMEDIAFORMAT_KEY_TEMPORAL_LAYER_ID", "temporal-layer-id"},
    {"AMEDIAFORMAT_KEY_TEMPORAL_LAYERING", "ts-schema"},
    {"AMEDIAFORMAT_KEY_TEXT_FORMAT_DATA", "text-format-data"},
    {"AMEDIAFORMAT_KEY_THUMBNAIL_CSD_AV1C", "thumbnail-csd-av1c"},
    {"AMEDIAFORMAT_KEY_THUMBNAIL_CSD_HEVC", "thumbnail-csd-hevc"},
    {"AMEDIAFORMAT_KEY_THUMBNAIL_HEIGHT", "thumbnail-height"},
    {"AMEDIAFORMAT_KEY_THUMBNAIL_TIME", "thumbnail-time"},
    {"AMEDIAFORMAT_KEY_THUMBNAIL_WIDTH", "thumbnail-width"},
    {"AMEDIAFORMAT_KEY_TILE_HEIGHT", "tile-height"},
    {"AMEDIAFORMAT_KEY_TILE_WIDTH", "tile-width"},
    {"AMEDIAFORMAT_KEY_TIME_US", "timeUs"},
    {"AMEDIAFORMAT_KEY_TITLE", "title"},
    {"AMEDIAFORMAT_KEY_TRACK_ID", "track-id"},
    {"AMEDIAFORMAT_KEY_TRACK_INDEX", "track-index"},
    {"AMEDIAFORMAT_KEY_VALID_SAMPLES", "valid-samples"},
    {"AMEDIAFORMAT_KEY_VIDEO_ENCODING_STATISTICS_LEVEL", "video-encoding-statistics-level"},
    {"AMEDIAFORMAT_KEY_VIDEO_QP_AVERAGE", "video-qp-average"},
    {"AMEDIAFORMAT_KEY_WIDTH", "width"},
    {"AMEDIAFORMAT_KEY_XMP_OFFSET", "xmp-offset"},
    {"AMEDIAFORMAT_KEY_XMP_SIZE", "xmp-size"},
    {"AMEDIAFORMAT_KEY_YEAR", "year"},
};

}  // namespace

void* native_media_resolve(const char* name) {
    if (std::strncmp(name, "AMEDIAFORMAT_KEY_", 17) != 0) return nullptr;
    for (FormatKey& k : kFormatKeys) {
        if (std::strcmp(name, k.name) == 0) return &k.value;
    }
    return nullptr;
}

}  // namespace winapk::host
