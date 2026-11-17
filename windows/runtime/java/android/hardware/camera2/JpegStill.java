// android.hardware.camera2.JpegStill -- NOT an AOSP class; package-private.
//
// The still-capture encoder behind an ImageFormat.JPEG output: one captured
// frame (ARGB, as CameraBridge.copyLatestArgb hands it out) becomes the JPEG
// byte stream an ImageReader(JPEG) delivers in its single plane. On a device
// this is the camera HAL's JPEG (BLOB) stream.
//
// The encoder is Bitmap.compress(JPEG), the same one android.hardware.Camera
// .takePicture uses (Camera.captureJpeg), so both camera APIs write the same
// files. Nothing here touches the capture natives: encode() takes pixels, which
// is what lets windows/test/java/Camera2StillTest.java drive it on a plain JVM.
//
// What a request controls, as on a device:
//   * the SIZE is the output's (the ImageReader's). The session runs one
//     native stream, so a still of another size is cut from the centre of the
//     frame to the output's aspect ratio and resampled -- what a HAL does when
//     two streams of different shapes share the sensor;
//   * JPEG_QUALITY is the encoder's quality;
//   * JPEG_ORIENTATION is written as the EXIF Orientation tag and the pixels
//     are left as captured. AOSP's JPEG_ORIENTATION documentation allows either
//     ("the camera device will either ... rotate the image data ... or ... set
//     the EXIF orientation"), and apps therefore read the tag (CameraX,
//     ExifInterface, every gallery);
//   * JPEG_THUMBNAIL_SIZE, when not (0, 0), embeds a thumbnail of that size in
//     the EXIF block's second IFD.
package android.hardware.camera2;

import android.graphics.Bitmap;
import android.util.Size;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class JpegStill {

    private JpegStill() { }

    /** The quality used when a request carries no JPEG_QUALITY: what AOSP's
     *  camera2-over-Camera1 layer, the LEGACY device this backend reports
     *  itself as, falls back to. Request templates are seeded with it too. */
    static final byte DEFAULT_QUALITY = 85;

    /** JPEG_THUMBNAIL_QUALITY's fallback, likewise. */
    static final byte DEFAULT_THUMBNAIL_QUALITY = 80;

    /** An APP1 segment's length field is 16 bits and counts itself. */
    private static final int MAX_APP1_PAYLOAD = 0xFFFF - 2;

    /**
     * @param argb        the frame, 0xAARRGGBB, tightly packed, srcW * srcH
     * @param outW        the output's width (the ImageReader's)
     * @param outH        the output's height
     * @param quality     1..100; anything else means "not set"
     * @param orientation JPEG_ORIENTATION, degrees clockwise
     * @param thumbnail   JPEG_THUMBNAIL_SIZE; null or (0, 0) for none
     * @return the JPEG file's bytes, or null when the encoder failed
     */
    static byte[] encode(int[] argb, int srcW, int srcH, int outW, int outH, int quality,
            int orientation, Size thumbnail, int thumbnailQuality) {
        if (argb == null || srcW <= 0 || srcH <= 0 || argb.length < srcW * srcH) return null;
        if (outW <= 0 || outH <= 0) { outW = srcW; outH = srcH; }
        if (quality < 1 || quality > 100) quality = DEFAULT_QUALITY;
        if (thumbnailQuality < 1 || thumbnailQuality > 100) thumbnailQuality = DEFAULT_THUMBNAIL_QUALITY;

        Bitmap frame = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888);
        frame.setPixels(argb, 0, srcW, 0, 0, srcW, srcH);
        Bitmap still = fit(frame, outW, outH);

        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(4096, outW * outH / 8));
        if (!still.compress(Bitmap.CompressFormat.JPEG, quality, bos)) return null;
        byte[] jpeg = bos.toByteArray();

        byte[] thumb = null;
        if (thumbnail != null && thumbnail.getWidth() > 0 && thumbnail.getHeight() > 0) {
            Bitmap small = fit(still, thumbnail.getWidth(), thumbnail.getHeight());
            // The thumbnail lives inside the APP1 segment, which holds 64 KB:
            // one that does not fit is encoded again at a lower quality, and
            // left out if it still does not (a file without a thumbnail is
            // valid; a truncated one is not).
            for (int q = thumbnailQuality; q >= 20 && thumb == null; q -= 20) {
                ByteArrayOutputStream tb = new ByteArrayOutputStream(16384);
                if (!small.compress(Bitmap.CompressFormat.JPEG, q, tb)) break;
                if (tb.size() <= MAX_APP1_PAYLOAD - 6 - THUMBNAIL_OFFSET) thumb = tb.toByteArray();
            }
        }
        return withExif(jpeg, exifOrientation(orientation), thumb);
    }

    /** src at w x h: the centre of src with w:h's shape, resampled. */
    private static Bitmap fit(Bitmap src, int w, int h) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        if (sw == w && sh == h) return src;
        int cw = sw;
        int ch = sh;
        if ((long) sw * h > (long) sh * w) {
            cw = Math.max(1, (int) ((long) sh * w / h));   // src is wider: cut the sides
        } else if ((long) sw * h < (long) sh * w) {
            ch = Math.max(1, (int) ((long) sw * h / w));   // src is taller: cut top and bottom
        }
        Bitmap cut = (cw == sw && ch == sh) ? src
                : Bitmap.createBitmap(src, (sw - cw) / 2, (sh - ch) / 2, cw, ch);
        return (cw == w && ch == h) ? cut : Bitmap.createScaledBitmap(cut, w, h, true);
    }

    /** JPEG_ORIENTATION (degrees clockwise the image must be turned to be
     *  upright) as the EXIF Orientation value that says the same. AOSP takes
     *  multiples of 90 only; anything else is "upright", as is 0. */
    static int exifOrientation(int degrees) {
        switch (((degrees % 360) + 360) % 360) {
            case 90:  return 6;   // rotate 90 CW to view
            case 180: return 3;
            case 270: return 8;
            default:  return 1;
        }
    }

    // TIFF layout of the EXIF block, little-endian. IFD0 holds Orientation;
    // IFD1, present only with a thumbnail, points at the thumbnail's JPEG.
    private static final int IFD0_OFFSET = 8;
    private static final int IFD1_OFFSET = IFD0_OFFSET + 2 + 12 + 4;
    private static final int THUMBNAIL_OFFSET = IFD1_OFFSET + 2 + 3 * 12 + 4;

    /** jpeg with an APP1 "Exif" segment added. It goes after the encoder's
     *  JFIF APP0, which JFIF requires to come first and which some readers
     *  (javax.imageio's metadata parser among them) insist on. */
    static byte[] withExif(byte[] jpeg, int exifOrientation, byte[] thumbnail) {
        if (jpeg == null || jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            return jpeg;
        }
        int tiffLen = thumbnail != null ? THUMBNAIL_OFFSET + thumbnail.length : IFD1_OFFSET;
        ByteBuffer t = ByteBuffer.allocate(tiffLen).order(ByteOrder.LITTLE_ENDIAN);
        t.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(IFD0_OFFSET);
        t.putShort((short) 1);
        entry(t, 0x0112, 3, exifOrientation);                    // Orientation, SHORT
        t.putInt(thumbnail != null ? IFD1_OFFSET : 0);
        if (thumbnail != null) {
            t.putShort((short) 3);
            entry(t, 0x0103, 3, 6);                              // Compression: JPEG
            entry(t, 0x0201, 4, THUMBNAIL_OFFSET);               // JPEGInterchangeFormat
            entry(t, 0x0202, 4, thumbnail.length);               // JPEGInterchangeFormatLength
            t.putInt(0);
            t.put(thumbnail);
        }

        int at = 2;
        if (jpeg.length >= 6 && (jpeg[2] & 0xFF) == 0xFF && (jpeg[3] & 0xFF) == 0xE0) {
            at = 4 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF));
            if (at > jpeg.length) at = 2;
        }
        int segLen = 2 + 6 + tiffLen;                            // the length field counts itself
        ByteBuffer out = ByteBuffer.allocate(jpeg.length + 2 + segLen);   // big-endian, as JPEG is
        out.put(jpeg, 0, at);
        out.put((byte) 0xFF).put((byte) 0xE1).putShort((short) segLen);
        out.put(new byte[] { 'E', 'x', 'i', 'f', 0, 0 });
        out.put(t.array());
        out.put(jpeg, at, jpeg.length - at);
        return out.array();
    }

    /** One 12-byte IFD entry with a single SHORT (type 3) or LONG (type 4)
     *  value, which fits in the entry's value field. */
    private static void entry(ByteBuffer t, int tag, int type, int value) {
        t.putShort((short) tag).putShort((short) type).putInt(1);
        if (type == 3) t.putShort((short) value).putShort((short) 0);
        else t.putInt(value);
    }
}
