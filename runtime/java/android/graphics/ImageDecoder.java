package android.graphics;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.Size;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.Callable;

/**
 * AOSP-faithful android.graphics.ImageDecoder (API 28+). The modern decode API
 * Coil/Glide/Compose AsyncImage prefer. We have no incremental codec, so we
 * decode the full image through BitmapFactory and then apply the requested
 * target size / sample size / crop in software - observably identical output.
 * The OnHeaderDecodedListener callback fires after we know the source size, so
 * apps that call setTargetSize() in their listener still work. Mirrors
 * frameworks/base/graphics/java/android/graphics/ImageDecoder.java.
 */
public final class ImageDecoder implements AutoCloseable {

    public static final int ALLOCATOR_DEFAULT       = 0;
    public static final int ALLOCATOR_SOFTWARE      = 1;
    public static final int ALLOCATOR_SHARED_MEMORY = 2;
    public static final int ALLOCATOR_HARDWARE      = 3;

    public static final int MEMORY_POLICY_DEFAULT      = 1;
    public static final int MEMORY_POLICY_LOW_RAM      = 0;

    /** Abstract source. Concrete kinds are produced by the createSource factories. */
    public abstract static class Source {
        Source() {}
        /** Decode the full source to raw bytes our codec can read. */
        abstract byte[] readBytes() throws IOException;
        Resources getResources() { return null; }
    }

    /** A callback that runs after the header (size/type) is known. */
    public interface OnHeaderDecodedListener {
        void onHeaderDecoded(ImageDecoder decoder, ImageInfo info, Source source);
    }

    /** A callback for partially-decoded images (truncated streams). */
    public interface OnPartialImageListener {
        boolean onPartialImage(DecodeException e);
    }

    // AOSP-conformance (framework_lint vs android-30 stub jar): AOSP does not
    // mark this final. Final-when-AOSP-is-not is the dangerous direction -- an
    // app compiled against real Android that subclasses this fails to resolve
    // against our jar. See docs/aosp-conformance-audit_2026-08-23.md.
    public static class ImageInfo {
        private final Size mSize;
        private final String mMime;
        private final boolean mAnimated;
        private final ColorSpace mColorSpace;

        ImageInfo(Size size, String mime, boolean animated, ColorSpace cs) {
            mSize = size; mMime = mime; mAnimated = animated; mColorSpace = cs;
        }
        public Size getSize() { return mSize; }
        public String getMimeType() { return mMime; }
        public boolean isAnimated() { return mAnimated; }
        public ColorSpace getColorSpace() { return mColorSpace; }
    }

    public static final class DecodeException extends IOException {
        public static final int SOURCE_EXCEPTION   = 1;
        public static final int SOURCE_INCOMPLETE  = 2;
        public static final int SOURCE_MALFORMED_DATA = 3;
        private final int mError;
        private final Source mSource;
        DecodeException(int error, Throwable cause, Source source) {
            super("ImageDecoder decode failed", cause);
            mError = error; mSource = source;
        }
        public int getError() { return mError; }
        public Source getSource() { return mSource; }
    }

    // ---- createSource factories ----

    public static Source createSource(final byte[] data, final int offset, final int length) {
        return new Source() {
            byte[] readBytes() {
                byte[] out = new byte[length];
                System.arraycopy(data, offset, out, 0, length);
                return out;
            }
        };
    }

    public static Source createSource(byte[] data) {
        return createSource(data, 0, data.length);
    }

    public static Source createSource(final ByteBuffer buffer) {
        return new Source() {
            byte[] readBytes() {
                ByteBuffer b = buffer.duplicate();
                byte[] out = new byte[b.remaining()];
                b.get(out);
                return out;
            }
        };
    }

    public static Source createSource(final java.io.File file) {
        return new Source() {
            byte[] readBytes() throws IOException {
                return java.nio.file.Files.readAllBytes(file.toPath());
            }
        };
    }

    public static Source createSource(final Resources res, final int resId) {
        return new Source() {
            byte[] readBytes() throws IOException {
                try (InputStream is = res.openRawResource(resId)) {
                    return readAll(is);
                }
            }
            Resources getResources() { return res; }
        };
    }

    public static Source createSource(final ContentResolver cr, final Uri uri) {
        return new Source() {
            byte[] readBytes() throws IOException {
                try (InputStream is = cr.openInputStream(uri)) {
                    if (is == null) throw new IOException("could not open " + uri);
                    return readAll(is);
                }
            }
        };
    }

    public static Source createSource(ContentResolver cr, Uri uri, Resources res) {
        return createSource(cr, uri);
    }

    public static Source createSource(final AssetManager assets, final String fileName) {
        return new Source() {
            byte[] readBytes() throws IOException {
                try (InputStream is = assets.open(fileName)) {
                    return readAll(is);
                }
            }
        };
    }

    public static Source createSource(final Callable<AssetFileDescriptor> callable) {
        return new Source() {
            byte[] readBytes() throws IOException {
                try {
                    AssetFileDescriptor afd = callable.call();
                    try (InputStream is = afd.createInputStream()) {
                        return readAll(is);
                    }
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IOException(e);
                }
            }
        };
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    // ---- decode entry points ----

    public static Bitmap decodeBitmap(Source src) throws IOException {
        return decodeBitmap(src, null);
    }

    public static Bitmap decodeBitmap(Source src, OnHeaderDecodedListener listener)
            throws IOException {
        ImageDecoder dec = new ImageDecoder(src);
        return dec.doDecode(listener);
    }

    public static Drawable decodeDrawable(Source src) throws IOException {
        return decodeDrawable(src, null);
    }

    public static Drawable decodeDrawable(Source src, OnHeaderDecodedListener listener)
            throws IOException {
        ImageDecoder dec = new ImageDecoder(src);
        Bitmap bm = dec.doDecode(listener);
        // AOSP returns AnimatedImageDrawable for multi-frame GIF/WebP; callers
        // (e.g. ejoysdk LoadingDialogFragment) cast to it unconditionally.
        if (dec.mInfo != null && dec.mInfo.isAnimated()) {
            AnimatedImageDrawable aid = new AnimatedImageDrawable();
            aid.macapkSetBitmap(bm);
            return aid;
        }
        return new BitmapDrawable(src != null ? src.getResources() : null, bm);
    }

    public static ImageInfo decodeHeader(Source src) throws IOException {
        ImageDecoder dec = new ImageDecoder(src);
        dec.ensureBytesAndInfo();
        return dec.mInfo;
    }

    // ---- instance state / setters ----

    private final Source mSource;
    private byte[] mBytes;
    private ImageInfo mInfo;
    private int mTargetWidth, mTargetHeight;
    private int mSampleSize = 1;
    private boolean mMutableRequired;
    private boolean mUnpremultipliedRequired;
    private boolean mDecodeAsAlphaMask;
    private int mAllocator = ALLOCATOR_DEFAULT;
    private int mMemoryPolicy = MEMORY_POLICY_DEFAULT;
    private Rect mCrop;
    private PostProcessor mPostProcessor;
    private OnPartialImageListener mPartialListener;
    private ColorSpace mTargetColorSpace;

    private ImageDecoder(Source source) {
        mSource = source;
    }

    private void ensureBytesAndInfo() throws IOException {
        if (mBytes == null) {
            mBytes = mSource.readBytes();
        }
        if (mInfo == null) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(mBytes, 0, mBytes.length, opts);
            mInfo = new ImageInfo(new Size(opts.outWidth, opts.outHeight),
                    opts.outMimeType, isAnimated(mBytes), ColorSpace.get(ColorSpace.Named.SRGB));
        }
    }

    /** True for a GIF with more than one image descriptor, or a WebP whose
     *  VP8X header has the animation flag set. */
    private static boolean isAnimated(byte[] b) {
        if (b == null) return false;
        if (b.length >= 13 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') {
            int p = 13;
            if ((b[10] & 0x80) != 0) p += 3 * (1 << ((b[10] & 0x07) + 1));
            int frames = 0;
            while (p < b.length) {
                int tag = b[p] & 0xff;
                if (tag == 0x2C) { // image descriptor
                    if (++frames > 1) return true;
                    if (p + 10 > b.length) return false;
                    int flags = b[p + 9] & 0xff;
                    p += 10;
                    if ((flags & 0x80) != 0) p += 3 * (1 << ((flags & 0x07) + 1));
                    p = skipGifSubBlocks(b, p + 1); // skip LZW min code size
                } else if (tag == 0x21) { // extension
                    p = skipGifSubBlocks(b, p + 2);
                } else {
                    return false; // trailer (0x3B) or garbage
                }
                if (p < 0) return false;
            }
            return false;
        }
        if (b.length >= 21 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P'
                && b[12] == 'V' && b[13] == 'P' && b[14] == '8' && b[15] == 'X') {
            return (b[20] & 0x02) != 0;
        }
        return false;
    }

    private static int skipGifSubBlocks(byte[] b, int p) {
        while (p < b.length) {
            int n = b[p] & 0xff;
            p++;
            if (n == 0) return p;
            p += n;
        }
        return -1;
    }

    private Bitmap doDecode(OnHeaderDecodedListener listener) throws IOException {
        ensureBytesAndInfo();
        if (listener != null) {
            listener.onHeaderDecoded(this, mInfo, mSource);
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = Math.max(1, mSampleSize);
        opts.inMutable = mMutableRequired;
        Bitmap bm;
        try {
            bm = BitmapFactory.decodeByteArray(mBytes, 0, mBytes.length, opts);
        } catch (Throwable t) {
            if (mPartialListener != null) {
                mPartialListener.onPartialImage(
                        new DecodeException(DecodeException.SOURCE_EXCEPTION, t, mSource));
            }
            throw new DecodeException(DecodeException.SOURCE_MALFORMED_DATA, t, mSource);
        }
        if (bm == null) {
            throw new DecodeException(DecodeException.SOURCE_MALFORMED_DATA, null, mSource);
        }
        // setTargetSize takes precedence over sample size for the final scale.
        if (mTargetWidth > 0 && mTargetHeight > 0
                && (mTargetWidth != bm.getWidth() || mTargetHeight != bm.getHeight())) {
            bm = Bitmap.createScaledBitmap(bm, mTargetWidth, mTargetHeight, true);
        }
        if (mCrop != null) {
            bm = cropBitmap(bm, mCrop);
        }
        if (mPostProcessor != null) {
            Canvas c = new Canvas(bm);
            mPostProcessor.onPostProcess(c);
        }
        return bm;
    }

    private static Bitmap cropBitmap(Bitmap src, Rect crop) {
        int l = Math.max(0, crop.left), t = Math.max(0, crop.top);
        int r = Math.min(src.getWidth(), crop.right), b = Math.min(src.getHeight(), crop.bottom);
        int w = r - l, h = b - t;
        if (w <= 0 || h <= 0) return src;
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, l, t, w, h);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.macapkSetArgbBuffer(px);
        return out;
    }

    // ImageDecoder is deliberately NOT a builder: AOSP declares every one of
    // these setters `public void`, and the app calls them on the decoder handed
    // to OnHeaderDecodedListener.onHeaderDecoded rather than chaining. The JVM
    // resolves invokevirtual on the FULL descriptor including return type, so a
    // fluent `ImageDecoder setX(...)` has no matching target for any caller
    // compiled against the platform and throws NoSuchMethodError at the call
    // site. Keep these void.
    // AOSP: frameworks/base/graphics/java/android/graphics/ImageDecoder.java
    public void setTargetSize(int width, int height) {
        mTargetWidth = width; mTargetHeight = height;
    }
    public void setTargetSampleSize(int sampleSize) {
        mSampleSize = Math.max(1, sampleSize);
    }
    public void setAllocator(int allocator) { mAllocator = allocator; }
    public int getAllocator() { return mAllocator; }
    public void setMutableRequired(boolean mutable) { mMutableRequired = mutable; }
    public boolean isMutableRequired() { return mMutableRequired; }
    public void setUnpremultipliedRequired(boolean unpremul) {
        mUnpremultipliedRequired = unpremul;
    }
    public boolean isUnpremultipliedRequired() { return mUnpremultipliedRequired; }
    public void setPostProcessor(PostProcessor p) { mPostProcessor = p; }
    public PostProcessor getPostProcessor() { return mPostProcessor; }
    public void setOnPartialImageListener(OnPartialImageListener l) {
        mPartialListener = l;
    }
    public OnPartialImageListener getOnPartialImageListener() { return mPartialListener; }
    public void setCrop(Rect subset) { mCrop = subset; }
    public Rect getCrop() { return mCrop; }
    public void setMemorySizePolicy(int policy) { mMemoryPolicy = policy; }
    public int getMemorySizePolicy() { return mMemoryPolicy; }
    public void setDecodeAsAlphaMaskEnabled(boolean enabled) {
        mDecodeAsAlphaMask = enabled;
    }
    public boolean isDecodeAsAlphaMaskEnabled() { return mDecodeAsAlphaMask; }
    public void setTargetColorSpace(ColorSpace cs) { mTargetColorSpace = cs; }

    @Override
    public void close() {
        mBytes = null;
    }

    /** AOSP ImageDecoder.isMimeTypeSupported(String) (API 31) -- "Return if the
     *  given MIME type is a supported file format that can be decoded by this
     *  class."
     *
     *  Answered from what OUR decode path can actually do, never from AOSP's
     *  list: BitmapFactory tries javax.imageio first and falls back to the macOS
     *  native codec (CGImageSource) for anything ImageIO refuses, which is how
     *  WEBP/HEIC/AVIF decode here at all (see BitmapFactory.macapkNativeDecode).
     *  So the answer is "ImageIO has a reader" OR "it is one of the formats we
     *  deliberately route to the native codec".
     *
     *  ⚠ The second half is a HOST CAPABILITY CLAIM, not a probe: the CGImageSource
     *  type list is not reachable from Java, and macOS 26 (our hard floor) ships
     *  decoders for all four. If a decode of one of these ever fails on a
     *  supported host, this list is the thing to re-measure. */
    public static boolean isMimeTypeSupported(String mimeType) {
        if (mimeType == null) throw new NullPointerException("mimeType must not be null");
        String m = mimeType.toLowerCase(java.util.Locale.ROOT);
        try {
            if (javax.imageio.ImageIO.getImageReadersByMIMEType(m).hasNext()) return true;
        } catch (Throwable ignored) { /* fall through to the native-codec list */ }
        switch (m) {
            case "image/webp":
            case "image/heif":
            case "image/heic":
            case "image/avif":
                return true;
            default:
                return false;
        }
    }
}
