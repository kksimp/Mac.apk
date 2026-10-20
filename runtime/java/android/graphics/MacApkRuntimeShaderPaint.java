package android.graphics;

import com.macapk.graphics.MacApkSkia;
import org.jetbrains.skia.impl.RefCnt;

import java.awt.PaintContext;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.ColorModel;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

/**
 * AWT Paint implementation for Android AGSL RuntimeShader.
 */
final class MacApkRuntimeShaderPaint implements java.awt.Paint {
    private final RuntimeShader mRuntimeShader;
    private static final int MAX_BLOCK_SIDE = 4096;
    private static final int MAX_BLOCK_PIXELS = 4 * 1024 * 1024;
    private static final long RESULT_CACHE_BYTES = 16L * 1024 * 1024;
    private static final int RESULT_CACHE_COUNT = 256;
    private static final java.util.LinkedHashMap<ResultKey, WritableRaster> sResults =
            new java.util.LinkedHashMap<>(32, 0.75f, true);
    private static long sResultBytes;

    private static final class ResultKey {
        final Object identity;
        final long revision;
        final int width;
        final int height;
        final int[] matrix;
        final int hash;

        ResultKey(Object identity, long revision, Rectangle bounds, float[] values) {
            this.identity = identity;
            this.revision = revision;
            width = bounds.width;
            height = bounds.height;
            matrix = new int[values.length];
            for (int i = 0; i < values.length; i++) matrix[i] = Float.floatToIntBits(values[i]);
            hash = 31 * (31 * (31 * System.identityHashCode(identity) + Long.hashCode(revision))
                    + width) + height + java.util.Arrays.hashCode(matrix);
        }

        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof ResultKey)) return false;
            ResultKey key = (ResultKey) other;
            return identity == key.identity && revision == key.revision && width == key.width
                    && height == key.height && java.util.Arrays.equals(matrix, key.matrix);
        }
    }

    private static void cacheResult(ResultKey key, WritableRaster raster) {
        synchronized (sResults) {
            WritableRaster previous = sResults.put(key, raster);
            long bytes = (long) key.width * key.height * 4;
            if (previous == null) sResultBytes += bytes;
            java.util.Iterator<java.util.Map.Entry<ResultKey, WritableRaster>> iterator =
                    sResults.entrySet().iterator();
            while ((sResultBytes > RESULT_CACHE_BYTES || sResults.size() > RESULT_CACHE_COUNT)
                    && iterator.hasNext()) {
                ResultKey evicted = iterator.next().getKey();
                sResultBytes -= (long) evicted.width * evicted.height * 4;
                iterator.remove();
            }
        }
    }
    MacApkRuntimeShaderPaint(RuntimeShader runtimeShader, Paint paint) {
        this.mRuntimeShader = runtimeShader;
    }

    @Override
    public int getTransparency() {
        return Transparency.TRANSLUCENT;
    }

    @Override
    public PaintContext createContext(ColorModel cm, Rectangle deviceBounds,
                                      Rectangle2D userBounds, AffineTransform xform,
                                      RenderingHints hints) {
        MacApkSkia.backendName();
        AffineTransform total = xform == null ? new AffineTransform() : new AffineTransform(xform);
        AffineTransform shaderLocal = mRuntimeShader.macapkLocalAffine();
        if (shaderLocal != null) total.concatenate(shaderLocal);
        Rectangle bounds = deviceBounds == null ? new Rectangle() : new Rectangle(deviceBounds);
        if (total.getDeterminant() == 0) return new RuntimeShaderContext(0, bounds, null, null);
        // Normalize away screen placement before both keying and rendering. The
        // local matrix now maps shader coordinates directly into the clipped result.
        AffineTransform normalized = AffineTransform.getTranslateInstance(-(double) bounds.x, -(double) bounds.y);
        normalized.concatenate(total);
        float[] matrix = {
            (float) normalized.getScaleX(), (float) normalized.getShearX(), (float) normalized.getTranslateX(),
            (float) normalized.getShearY(), (float) normalized.getScaleY(), (float) normalized.getTranslateY(),
            0f, 0f, 1f
        };
        synchronized (mRuntimeShader) {
            ResultKey key = null;
            if (bounds.width > 0 && bounds.height > 0 && bounds.width <= MAX_BLOCK_SIDE
                    && bounds.height <= MAX_BLOCK_SIDE
                    && (long) bounds.width * bounds.height <= MAX_BLOCK_PIXELS) {
                key = new ResultKey(mRuntimeShader.macapkContentIdentity(),
                        mRuntimeShader.macapkContentRevision(), bounds, matrix);
                synchronized (sResults) {
                    WritableRaster cached = sResults.get(key);
                    if (cached != null) return new RuntimeShaderContext(0, bounds, key, cached);
                }
            }
            long shader = mRuntimeShader.macapkMakeShaderSnapshot(matrix);
            return new RuntimeShaderContext(shader, bounds, key, null);
        }
    }

    private static final class RuntimeShaderContext implements PaintContext {
        // Bound both texture dimensions and retained CPU output. Normal clipped
        // fills render once; exceptionally large fills use bounded GPU blocks.
        private final ResultKey mResultKey;
        private long mShader;
        private final Rectangle mBounds;
        private final int mBlockWidth;
        private final int mBlockHeight;
        private WritableRaster mBlock;
        private Rectangle mBlockBounds;

        RuntimeShaderContext(long shader, Rectangle bounds, ResultKey key, WritableRaster cached) {
            mShader = shader;
            mResultKey = key;
            mBounds = new Rectangle(bounds);
            mBlock = cached;
            if (cached != null) mBlockBounds = new Rectangle(bounds);
            mBlockWidth = Math.max(1, Math.min(MAX_BLOCK_SIDE, mBounds.width));
            mBlockHeight = Math.max(1, Math.min(Math.min(MAX_BLOCK_SIDE, mBounds.height),
                    MAX_BLOCK_PIXELS / mBlockWidth));
        }

        @Override
        public void dispose() {
            if (mShader != 0) {
                RefCnt.unref(mShader);
                mShader = 0;
            }
            mBlock = null;
            mBlockBounds = null;
        }

        @Override
        public ColorModel getColorModel() {
            return ColorModel.getRGBdefault();
        }

        private void renderBlock(int x, int y) {
            int bx = mBounds.x + Math.floorDiv(x - mBounds.x, mBlockWidth) * mBlockWidth;
            int by = mBounds.y + Math.floorDiv(y - mBounds.y, mBlockHeight) * mBlockHeight;
            int bw = (int) Math.min(mBlockWidth, (long) mBounds.x + mBounds.width - bx);
            int bh = (int) Math.min(mBlockHeight, (long) mBounds.y + mBounds.height - by);
            mBlockBounds = new Rectangle(bx, by, bw, bh);
            mBlock = getColorModel().createCompatibleWritableRaster(bw, bh);
            int[] pixels = ((DataBufferInt) mBlock.getDataBuffer()).getData();
            if (!MacApkSkia.evalShaderRegion(mShader, bx - mBounds.x, by - mBounds.y, bw, bh, pixels)) {
                throw new IllegalStateException("Skia Metal shader evaluation failed");
            }
            // Published pixels are never written again. Keys hold an identity
            // token, never a RuntimeShader or native pointer.
            if (mResultKey != null) cacheResult(mResultKey, mBlock);
        }

        @Override
        public Raster getRaster(int x, int y, int rw, int rh) {
            Rectangle requested = new Rectangle(x, y, rw, rh);
            if ((mShader != 0 || mBlock != null) && mBounds.contains(requested)) {
                if (mBlockBounds == null || !mBlockBounds.contains(x, y)) renderBlock(x, y);
                if (mBlockBounds.contains(requested)) {
                    // Returning a child avoids allocating/copying each Java2D tile.
                    return mBlock.createChild(x - mBlockBounds.x, y - mBlockBounds.y,
                            rw, rh, 0, 0, null);
                }
            }
            WritableRaster out = getColorModel().createCompatibleWritableRaster(rw, rh);
            if (mShader == 0 && mBlock == null) return out;
            Rectangle clipped = requested.intersection(mBounds);
            if (clipped.isEmpty()) return out;
            int[] dest = ((DataBufferInt) out.getDataBuffer()).getData();
            int endY = clipped.y + clipped.height;
            int endX = clipped.x + clipped.width;
            for (int cy = clipped.y; cy < endY;) {
                int nextY = endY;
                for (int cx = clipped.x; cx < endX;) {
                    if (mBlockBounds == null || !mBlockBounds.contains(cx, cy)) renderBlock(cx, cy);
                    int right = Math.min(endX, mBlockBounds.x + mBlockBounds.width);
                    int bottom = Math.min(endY, mBlockBounds.y + mBlockBounds.height);
                    int[] source = ((DataBufferInt) mBlock.getDataBuffer()).getData();
                    for (int row = cy; row < bottom; row++) {
                        System.arraycopy(source, (row - mBlockBounds.y) * mBlockBounds.width
                                + cx - mBlockBounds.x, dest, (row - y) * rw + cx - x, right - cx);
                    }
                    nextY = Math.min(nextY, bottom);
                    cx = right;
                }
                cy = nextY;
            }
            return out;
        }
    }
}
