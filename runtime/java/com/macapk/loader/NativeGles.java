// JNI surface to the EGL / GLES wrapper sitting on top of ANGLE.
// Stubbed for now -- we'll grow into it once the boot path reaches
// android.opengl.GLES20 calls.
package com.macapk.loader;

public final class NativeGles {
    private NativeGles() {}

    public static native boolean init(long metalLayer, int widthPx, int heightPx);
    public static native void beginFrame();
    public static native void endFrame();

    // ---- EGLImage-backed external textures (native_methods.cpp,
    // MacapkUseExternalImages). Used by android.graphics.SurfaceTexture to
    // deliver frames the way GLConsumer does on a device. All four act on the
    // CALLING thread's current EGL context.

    /** True when the current context's GL backend can attach EGLImages to
     *  GL_TEXTURE_EXTERNAL_OES textures (ANGLE Vulkan); false where
     *  external textures are absent (ANGLE Metal) and names are translated
     *  to GL_TEXTURE_2D instead. Decided once per EGL display, on the first
     *  call made with a context current on it: a Vulkan process keeps its
     *  framework and EGL10 displays on Metal. */
    public static native boolean externalImagesSupported();

    /** EGLImage (as a handle) from level 0 of a complete GL_TEXTURE_2D; 0 on failure. */
    public static native long createImageFromTexture2D(int texture2d);

    /** Attach `image` to `externalTexture` as GL_TEXTURE_EXTERNAL_OES; the
     *  caller's external binding is restored. False on failure. */
    public static native boolean attachImageToExternal(int externalTexture, long image);

    /** Release an EGLImage from createImageFromTexture2D (0 is ignored). */
    public static native void destroyImage(long image);
}
