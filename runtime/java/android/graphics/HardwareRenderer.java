package android.graphics;

import android.view.Surface;
import java.util.concurrent.Executor;

/**
 * AOSP-faithful android.graphics.HardwareRenderer (API 29). Drives a RenderNode
 * tree onto a Surface using a hardware-accelerated context. Mac.apk has no
 * standalone HW renderer (our View redraw goes through ViewCanvasDriver +
 * software RenderNode replay), so this is a faithful no-op surface: properties
 * round-trip and FrameRenderRequest.syncAndDraw() returns SYNC_OK. Mirrors
 * frameworks/base/graphics/java/android/graphics/HardwareRenderer.java.
 */
public class HardwareRenderer {
    public static final int SYNC_OK = 0;
    public static final int SYNC_REDRAW_REQUESTED = 1 << 0;
    public static final int SYNC_LOST_SURFACE_REWARD_IF_FOUND = 1 << 1;
    public static final int SYNC_CONTEXT_IS_STOPPED = 1 << 2;
    public static final int SYNC_FRAME_DROPPED = 1 << 3;

    private RenderNode mContent;
    private Surface mSurface;
    private final FrameRenderRequest mRenderRequest = new FrameRenderRequest();

    public HardwareRenderer() {}

    public void setContentRoot(RenderNode content) { mContent = content; }
    public void setSurface(Surface surface) { mSurface = surface; }
    public void setSurface(Surface surface, boolean discardBuffer) { mSurface = surface; }
    public void destroy() { mContent = null; mSurface = null; }

    public void setLightSourceGeometry(float lightX, float lightY, float lightZ,
            float lightRadius) {}
    public void setLightSourceAlpha(float ambientShadowAlpha, float spotShadowAlpha) {}
    private boolean mOpaque = true;
    private int mSpotShadowColor;
    private int mAmbientShadowColor;
    public void setOpaque(boolean opaque) { mOpaque = opaque; }
    public boolean isOpaque() { return mOpaque; }
    public void setName(String name) {}
    public void start() {}
    public void stop() {}
    public void clearContent() { mContent = null; }
    public void setStopped(boolean stopped) {}
    public void notifyFramePending() {}
    public void setSpotShadowColor(int color) { mSpotShadowColor = color; }
    public int getSpotShadowColor() { return mSpotShadowColor; }
    public void setAmbientShadowColor(int color) { mAmbientShadowColor = color; }
    public int getAmbientShadowColor() { return mAmbientShadowColor; }

    public FrameRenderRequest createRenderRequest() {
        mRenderRequest.reset();
        return mRenderRequest;
    }

    /** A pending frame draw. On real Android this synchronizes the RenderNode
     *  tree to the render thread and draws; here it is a no-op returning OK. */
    public final class FrameRenderRequest {
        private long mVsyncTime;
        private boolean mWaitForPresent;

        private void reset() {
            mVsyncTime = 0;
            mWaitForPresent = false;
        }

        public FrameRenderRequest setVsyncTime(long vsyncTime) {
            mVsyncTime = vsyncTime;
            return this;
        }

        public FrameRenderRequest setFrameCommitCallback(Executor executor,
                Runnable frameCommitCallback) {
            // No render thread; invoke the commit callback immediately so apps
            // that block on it do not stall.
            if (executor != null && frameCommitCallback != null) {
                executor.execute(frameCommitCallback);
            }
            return this;
        }

        public FrameRenderRequest setWaitForPresent(boolean shouldWait) {
            mWaitForPresent = shouldWait;
            return this;
        }

        public int syncAndDraw() {
            return SYNC_OK;
        }
    }

    /** AOSP HardwareRenderer.isDrawingEnabled() (API 30) -- STATIC, and false
     *  only when something (a test harness, a VR headset takeover) has called
     *  setDrawingEnabled(false). Our renderer is always live unless an app
     *  turns it off, so the default is true. */
    public static boolean isDrawingEnabled() { return sDrawingEnabled; }

    /** AOSP HardwareRenderer.setDrawingEnabled(boolean) -- STATIC, process-wide. */
    public static void setDrawingEnabled(boolean drawingEnabled) {
        sDrawingEnabled = drawingEnabled;
    }
    private static volatile boolean sDrawingEnabled = true;
}
