package android.graphics;

import com.macapk.graphics.MacApkSkia;
import org.jetbrains.skia.RuntimeEffectKt;
import org.jetbrains.skia.RuntimeShaderBuilderKt;
import org.jetbrains.skia.ImageKt;
import org.jetbrains.skia.DataKt;
import org.jetbrains.skia.impl.RefCnt;
import org.jetbrains.skia.impl.Managed;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * android.graphics.RuntimeShader -- AGSL programmable shader backed by Skia SkRuntimeEffect.
 */
public class RuntimeShader extends Shader {
    private static final Map<String, Long> sEffectCache = new ConcurrentHashMap<>();

    private final String agsl;
    private long mEffectPtr;
    private long mBuilderPtr;
    private long mShaderPtr;
    private ColorSpace mWorkingColorSpace;
    private Object mContentIdentity = new Object();
    private long mContentRevision;

    synchronized Object macapkContentIdentity() { return mContentIdentity; }
    synchronized long macapkContentRevision() { return mContentRevision; }

    public RuntimeShader(String agsl) {
        super();
        this.agsl = agsl;
        if (MacApkSkia.isAvailable() && agsl != null) {
            try {
                mEffectPtr = sEffectCache.computeIfAbsent(agsl, k -> {
                    try {
                        return RuntimeEffectKt._nMakeForShader(k);
                    } catch (Throwable t) {
                        System.err.println("[W] RuntimeShader compilation failed: " + t);
                        return 0L;
                    }
                });
                if (mEffectPtr != 0) {
                    mBuilderPtr = RuntimeShaderBuilderKt._nMakeFromRuntimeEffect(mEffectPtr);
                }
            } catch (Throwable t) {
                System.err.println("[W] RuntimeShader init error: " + t);
            }
        }
    }

    // Display lists keep the immutable native shader built at record time, not the mutable
    // builder which Compose reuses for later sizes/uniform values.
    private RuntimeShader(long shaderPtr) {
        agsl = null;
        mShaderPtr = shaderPtr;
    }

    synchronized RuntimeShader macapkSnapshot() {
        long shader = macapkMakeShaderSnapshot(null);
        RuntimeShader snapshot = new RuntimeShader(shader);
        snapshot.mContentIdentity = mContentIdentity;
        snapshot.mContentRevision = mContentRevision;
        Matrix local = new Matrix();
        if (getLocalMatrix(local)) snapshot.setLocalMatrix(local);
        return snapshot;
    }

    synchronized long macapkMakeShaderSnapshot(float[] localMatrix) {
        if (mBuilderPtr != 0) return RuntimeShaderBuilderKt._nMakeShader(mBuilderPtr, localMatrix);
        if (mShaderPtr == 0) return 0;
        float[] matrix = localMatrix != null ? localMatrix : new float[]{1,0,0,0,1,0,0,0,1};
        return org.jetbrains.skia.ShaderKt._nMakeWithLocalMatrix(mShaderPtr, matrix);
    }

    public synchronized long getNativeEffect() { return mEffectPtr; }
    public synchronized long getNativeBuilder() { return mBuilderPtr; }


    public synchronized void setFloatUniform(String uniformName, float value) {
        mContentRevision++;
        if (mBuilderPtr != 0) {
            RuntimeShaderBuilderKt._nUniformFloat(mBuilderPtr, uniformName, value);
        }
    }

    public synchronized void setFloatUniform(String uniformName, float value1, float value2) {
        mContentRevision++;
        if (mBuilderPtr != 0) {
            RuntimeShaderBuilderKt._nUniformFloat2(mBuilderPtr, uniformName, value1, value2);
        }
    }

    public synchronized void setFloatUniform(String uniformName, float value1, float value2, float value3) {
        mContentRevision++;
        if (mBuilderPtr != 0) {
            RuntimeShaderBuilderKt._nUniformFloat3(mBuilderPtr, uniformName, value1, value2, value3);
        }
    }

    public synchronized void setFloatUniform(String uniformName, float value1, float value2, float value3, float value4) {
        mContentRevision++;
        if (mBuilderPtr != 0) {
            RuntimeShaderBuilderKt._nUniformFloat4(mBuilderPtr, uniformName, value1, value2, value3, value4);
        }
    }

    public synchronized void setFloatUniform(String uniformName, float[] values) {
        mContentRevision++;
        if (mBuilderPtr != 0 && values != null) {
            RuntimeShaderBuilderKt._nUniformFloatArray(mBuilderPtr, uniformName, values, values.length);
        }
    }

    public synchronized void setIntUniform(String uniformName, int value) {
        mContentRevision++;
        if (mBuilderPtr != 0) {
            RuntimeShaderBuilderKt._nUniformInt(mBuilderPtr, uniformName, value);
        }
    }

    public synchronized void setIntUniform(String uniformName, int value1, int value2) {
        mContentRevision++;
        if (mBuilderPtr != 0) {
            RuntimeShaderBuilderKt._nUniformInt2(mBuilderPtr, uniformName, value1, value2);
        }
    }

    public synchronized void setIntUniform(String uniformName, int value1, int value2, int value3) {
        mContentRevision++;
        if (mBuilderPtr != 0) {
            RuntimeShaderBuilderKt._nUniformInt3(mBuilderPtr, uniformName, value1, value2, value3);
        }
    }

    public synchronized void setIntUniform(String uniformName, int value1, int value2, int value3, int value4) {
        mContentRevision++;
        if (mBuilderPtr != 0) {
            RuntimeShaderBuilderKt._nUniformInt4(mBuilderPtr, uniformName, value1, value2, value3, value4);
        }
    }

    public synchronized void setIntUniform(String uniformName, int[] values) {
        mContentRevision++;
        if (mBuilderPtr != 0 && values != null) {
            // The pinned JNI method copies count * 4 raw bytes via GetPrimitiveArrayCritical;
            // passing int[] preserves integer bits without a temporary float[] conversion.
            RuntimeShaderBuilderKt._nUniformFloatArray(mBuilderPtr, uniformName, values, values.length);
        }
    }

    public synchronized void setColorUniform(String uniformName, int color) {
        mContentRevision++;
        if (mBuilderPtr != 0) {
            float a = ((color >>> 24) & 0xff) / 255.0f;
            float r = ((color >>> 16) & 0xff) / 255.0f;
            float g = ((color >>> 8) & 0xff) / 255.0f;
            float b = (color & 0xff) / 255.0f;
            RuntimeShaderBuilderKt._nUniformFloat4(mBuilderPtr, uniformName, r, g, b, a);
        }
    }

    public synchronized void setColorUniform(String uniformName, long color) {
        setColorUniform(uniformName, Color.valueOf(color));
    }

    public synchronized void setColorUniform(String uniformName, Color color) {
        java.util.Objects.requireNonNull(color, "color");
        mContentRevision++;
        if (mBuilderPtr != 0) {
            // Skia layout(color) uniforms use unassociated extended-sRGB components.
            Color srgb = color.isSrgb() ? color
                    : color.convert(ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB));
            RuntimeShaderBuilderKt._nUniformFloat4(mBuilderPtr, uniformName,
                    srgb.red(), srgb.green(), srgb.blue(), srgb.alpha());
        }
    }

    public synchronized void setInputShader(String shaderName, Shader shader) {
        mContentRevision++;
        if (mBuilderPtr == 0 || shader == null) return;

        if (shader instanceof RuntimeShader) {
            RuntimeShader runtimeShader = (RuntimeShader) shader;
            synchronized (runtimeShader) {
                long childPtr = runtimeShader.macapkMakeShaderSnapshot(shaderLocalMatrix(runtimeShader));
                if (childPtr != 0) {
                    try {
                        RuntimeShaderBuilderKt._nChildShader(mBuilderPtr, shaderName, childPtr);
                    } finally {
                        RefCnt.unref(childPtr);
                    }
                }
            }
        } else if (shader instanceof BitmapShader) {
            BitmapShader bs = (BitmapShader) shader;
            Bitmap bmp = bs.getBitmap();
            if (bmp != null) {
                long skShader = createSkiaShaderFromBitmap(bmp, bs.tileX, bs.tileY,
                        bs.getFilterMode(), shaderLocalMatrix(bs));
                if (skShader != 0) {
                    try {
                        RuntimeShaderBuilderKt._nChildShader(mBuilderPtr, shaderName, skShader);
                    } finally {
                        RefCnt.unref(skShader);
                    }
                }
            }
        }
    }

    private static float[] shaderLocalMatrix(Shader shader) {
        Matrix matrix = new Matrix();
        if (!shader.getLocalMatrix(matrix)) return null;
        float[] values = new float[9];
        matrix.getValues(values);
        return values;
    }

    private static long createSkiaShaderFromBitmap(Bitmap bmp, Shader.TileMode tileX,
                                                   Shader.TileMode tileY, int filterMode, float[] localMatrix) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w <= 0 || h <= 0) return 0;

        int tmx = tileX != null ? tileX.ordinal() : 0;
        int tmy = tileY != null ? tileY.ordinal() : 0;
        int fmode = (filterMode == BitmapShader.FILTER_MODE_LINEAR) ? 1 : 0;
        long data = 0;
        long image = 0;
        try {
            if (bmp.getConfig() == Bitmap.Config.ALPHA_8) {
                byte[] alpha = bmp.macapkAlphaBytes();
                if (alpha != null) {
                    data = DataKt._nMakeFromBytes(alpha, 0, alpha.length);
                    image = ImageKt._nMakeRasterData(w, h, 1, 2, 0L, data, w);
                }
            } else {
                int[] argb = bmp.macapkArgbBuffer();
                if (argb != null) {
                    byte[] bytes = new byte[argb.length * 4];
                    for (int i = 0; i < argb.length; i++) {
                        int c = argb[i];
                        bytes[i * 4] = (byte) (c & 0xff);
                        bytes[i * 4 + 1] = (byte) ((c >>> 8) & 0xff);
                        bytes[i * 4 + 2] = (byte) ((c >>> 16) & 0xff);
                        bytes[i * 4 + 3] = (byte) ((c >>> 24) & 0xff);
                    }
                    data = DataKt._nMakeFromBytes(bytes, 0, bytes.length);
                    image = ImageKt._nMakeRasterData(w, h, 6, 3, 0L, data, w * 4);
                }
            }
            if (image == 0) return 0;
            return ImageKt.Image_nMakeShader(image, tmx, tmy, fmode, 0, localMatrix);
        } finally {
            if (image != 0) RefCnt.unref(image);
            DataKt.unref(data);
        }
    }

    public void setInputBuffer(String shaderName, BitmapShader shader) {
        setInputShader(shaderName, shader);
    }

    public void setInputColorFilter(String colorFilterName, ColorFilter colorFilter) {}
    public void setInputXfermode(String xfermodeName, RuntimeXfermode xfermode) {}

    public void setWorkingColorSpace(ColorSpace colorSpace) {
        mWorkingColorSpace = colorSpace;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            synchronized (this) {
                if (mShaderPtr != 0) {
                    RefCnt.unref(mShaderPtr);
                    mShaderPtr = 0;
                }
                if (mBuilderPtr != 0) {
                    Managed._nInvokeFinalizer(
                            RuntimeShaderBuilderKt.RuntimeShaderBuilder_nGetFinalizer(), mBuilderPtr);
                    mBuilderPtr = 0;
                }
            }
        } finally {
            super.finalize();
        }
    }
}
