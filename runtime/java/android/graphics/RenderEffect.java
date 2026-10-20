package android.graphics;

import com.macapk.graphics.MacApkSkia;
import org.jetbrains.skia.ImageFilterKt;
import org.jetbrains.skia.impl.RefCnt;
import java.lang.ref.Reference;

/**
 * android.graphics.RenderEffect -- post-processing effect attached to RenderNode,
 * backed by Skia ImageFilter DAG for blur, runtime shader, offset, blend, and chain.
 */
public class RenderEffect {
    private boolean blur;
    private float blurRadiusX, blurRadiusY;
    private ColorFilter colorFilter;
    private long mNativeFilterPtr;
    private boolean mOwnsNativeFilter;
    // Keep the original owner alive when this wrapper borrows its filter pointer.
    private RenderEffect mNativeFilterOwner;

    public RenderEffect() {}

    private void borrowNativeFilter(RenderEffect owner) {
        mNativeFilterPtr = owner != null ? owner.mNativeFilterPtr : 0L;
        mOwnsNativeFilter = false;
        mNativeFilterOwner = owner;
    }

    private void takeNativeFilter(long nativeFilterPtr) {
        long oldPtr = mNativeFilterPtr;
        boolean ownedOldPtr = mOwnsNativeFilter;
        mNativeFilterPtr = nativeFilterPtr;
        mOwnsNativeFilter = nativeFilterPtr != 0L;
        mNativeFilterOwner = null;
        if (ownedOldPtr) RefCnt.unref(oldPtr);
    }

    private static float radiusToSigma(float radius) {
        return radius > 0f ? radius * 0.57735f + 0.5f : 0f;
    }

    public long getNativeFilter() {
        return mNativeFilterPtr;
    }

    public boolean hasSkiaFilter() {
        return mNativeFilterPtr != 0;
    }

    public static RenderEffect createBlurEffect(float radiusX, float radiusY, Shader.TileMode edgeTreatment) {
        RenderEffect e = new RenderEffect();
        e.blur = true;
        e.blurRadiusX = radiusX;
        e.blurRadiusY = radiusY;
        if (MacApkSkia.isAvailable() && (radiusX > 0f || radiusY > 0f)) {
            try {
                int tm = (edgeTreatment != null) ? edgeTreatment.ordinal() : 0;
                float sx = radiusToSigma(radiusX);
                float sy = radiusToSigma(radiusY);
                e.takeNativeFilter(ImageFilterKt._nMakeBlur(sx, sy, tm, 0L, null));
            } catch (Throwable t) {
                System.err.println("[W] RenderEffect.createBlurEffect error: " + t);
            }
        }
        return e;
    }

    public static RenderEffect createBlurEffect(float radiusX, float radiusY,
                                                RenderEffect inputEffect, Shader.TileMode edgeTreatment) {
        RenderEffect e = createBlurEffect(radiusX, radiusY, edgeTreatment);
        if (inputEffect != null && MacApkSkia.isAvailable()) {
            try {
                long inputFilter = inputEffect.getNativeFilter();
                if (inputFilter != 0) {
                    int tm = (edgeTreatment != null) ? edgeTreatment.ordinal() : 0;
                    float sx = radiusToSigma(radiusX);
                    float sy = radiusToSigma(radiusY);
                    e.takeNativeFilter(ImageFilterKt._nMakeBlur(sx, sy, tm, inputFilter, null));
                }
            } catch (Throwable t) {
                System.err.println("[W] RenderEffect.createBlurEffect with input error: " + t);
            } finally {
                Reference.reachabilityFence(inputEffect);
            }
        }
        return e;
    }


    public static RenderEffect createOffsetEffect(float offsetX, float offsetY) {
        RenderEffect e = new RenderEffect();
        if (MacApkSkia.isAvailable()) {
            try {
                e.takeNativeFilter(ImageFilterKt._nMakeOffset(offsetX, offsetY, 0L, null));
            } catch (Throwable t) {
                System.err.println("[W] RenderEffect.createOffsetEffect error: " + t);
            }
        }
        return e;
    }

    public static RenderEffect createOffsetEffect(float offsetX, float offsetY, RenderEffect input) {
        RenderEffect e = new RenderEffect();
        if (MacApkSkia.isAvailable()) {
            try {
                long inputPtr = (input != null) ? input.getNativeFilter() : 0L;
                e.takeNativeFilter(ImageFilterKt._nMakeOffset(offsetX, offsetY, inputPtr, null));
            } catch (Throwable t) {
                System.err.println("[W] RenderEffect.createOffsetEffect error: " + t);
            } finally {
                Reference.reachabilityFence(input);
            }
        }
        return e;
    }

    public static RenderEffect createColorFilterEffect(ColorFilter colorFilter) {
        RenderEffect e = new RenderEffect();
        e.colorFilter = colorFilter;
        return e;
    }

    public static RenderEffect createColorFilterEffect(ColorFilter colorFilter, RenderEffect input) {
        RenderEffect e = new RenderEffect();
        if (input != null) {
            e.blur = input.blur;
            e.blurRadiusX = input.blurRadiusX;
            e.blurRadiusY = input.blurRadiusY;
            e.borrowNativeFilter(input);
        }
        e.colorFilter = colorFilter;
        return e;
    }

    public static RenderEffect createShaderEffect(Shader shader) {
        return new RenderEffect();
    }

    public static RenderEffect createChainEffect(RenderEffect outer, RenderEffect inner) {
        if (outer == null) return inner;
        if (inner == null) return outer;

        RenderEffect e = new RenderEffect();
        e.blur = outer.blur || inner.blur;
        e.blurRadiusX = Math.max(outer.blurRadiusX, inner.blurRadiusX);
        e.blurRadiusY = Math.max(outer.blurRadiusY, inner.blurRadiusY);
        e.colorFilter = outer.colorFilter != null ? outer.colorFilter : inner.colorFilter;

        if (MacApkSkia.isAvailable()) {
            long outerPtr = outer.getNativeFilter();
            long innerPtr = inner.getNativeFilter();
            if (outerPtr != 0 && innerPtr != 0) {
                try {
                    e.takeNativeFilter(ImageFilterKt._nMakeCompose(outerPtr, innerPtr));
                } catch (Throwable t) {
                    System.err.println("[W] RenderEffect.createChainEffect compose error: " + t);
                    e.borrowNativeFilter(outer);
                } finally {
                    Reference.reachabilityFence(outer);
                    Reference.reachabilityFence(inner);
                }
            } else {
                e.borrowNativeFilter(outerPtr != 0 ? outer : inner);
            }
        }
        return e;
    }

    public static RenderEffect createBitmapEffect(Bitmap bitmap) { return new RenderEffect(); }
    public static RenderEffect createBitmapEffect(Bitmap bitmap, Rect src, Rect dst) { return new RenderEffect(); }

    public static RenderEffect createBlendModeEffect(RenderEffect dst, RenderEffect src, BlendMode blendMode) {
        RenderEffect e = new RenderEffect();
        if (src != null) {
            e.blur = src.blur;
            e.blurRadiusX = src.blurRadiusX;
            e.blurRadiusY = src.blurRadiusY;
        } else if (dst != null) {
            e.blur = dst.blur;
            e.blurRadiusX = dst.blurRadiusX;
            e.blurRadiusY = dst.blurRadiusY;
        }

        if (MacApkSkia.isAvailable()) {
            long dstPtr = (dst != null) ? dst.getNativeFilter() : 0L;
            long srcPtr = (src != null) ? src.getNativeFilter() : 0L;
            int skBlendMode = (blendMode != null) ? blendMode.ordinal() : 3; // kSrcOver = 3
            try {
                e.takeNativeFilter(ImageFilterKt._nMakeBlend(skBlendMode, dstPtr, srcPtr, null));
            } catch (Throwable t) {
                System.err.println("[W] RenderEffect.createBlendModeEffect error: " + t);
                e.borrowNativeFilter(srcPtr != 0 ? src : dst);
            } finally {
                Reference.reachabilityFence(dst);
                Reference.reachabilityFence(src);
            }
        }
        return e;
    }

    public static RenderEffect createRuntimeShaderEffect(RuntimeShader shader, String uniformShaderName) {
        RenderEffect e = new RenderEffect();
        if (shader != null && MacApkSkia.isAvailable()) {
            synchronized (shader) {
                long builder = shader.getNativeBuilder();
                if (builder != 0) {
                    try {
                        e.takeNativeFilter(ImageFilterKt._nMakeRuntimeShader(
                                builder, uniformShaderName != null ? uniformShaderName : "child", 0L));
                        e.blur = true; // Mark as layer effect requiring offscreen composite
                    } catch (Throwable t) {
                        System.err.println("[W] RenderEffect.createRuntimeShaderEffect error: " + t);
                    } finally {
                        Reference.reachabilityFence(shader);
                    }
                }
            }
        }
        return e;
    }


    public boolean macapkIsBlur() { return blur || hasSkiaFilter(); }
    public float macapkBlurRadiusX() { return blurRadiusX; }
    public float macapkBlurRadiusY() { return blurRadiusY; }
    public ColorFilter macapkColorFilter() { return colorFilter; }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mOwnsNativeFilter) {
                long nativeFilterPtr = mNativeFilterPtr;
                mNativeFilterPtr = 0;
                mOwnsNativeFilter = false;
                RefCnt.unref(nativeFilterPtr);
            }
        } finally {
            super.finalize();
        }
    }
}
