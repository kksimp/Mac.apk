import android.graphics.SurfaceTexture;
import android.view.Surface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** A fullscreen GL output must not steal a decoder's input SurfaceTexture. */
public final class VideoSurfaceRoutingSmoke {
    public static void main(String[] args) throws Exception {
        SurfaceTexture decoder = new SurfaceTexture(0);
        Surface input = new Surface(decoder);
        SurfaceTexture fullscreenOutput = new SurfaceTexture(0);
        new Surface(fullscreenOutput); // Registers a newer, unrelated output.
        Method scope;
        try { scope = SurfaceTexture.class.getMethod("macapkSetVideoFrameSurface", Surface.class); }
        catch (NoSuchMethodException oldRuntime) {
            SurfaceTexture.macapkOfferDecodedFrame(new int[]{0xffff0000}, 1, 1, 1);
            Field wrong = SurfaceTexture.class.getDeclaredField("mHasVideoFrame");
            wrong.setAccessible(true);
            if (wrong.getBoolean(fullscreenOutput))
                throw new AssertionError("Fullscreen output stole the decoder's frame");
            throw oldRuntime;
        }
        Field queued = SurfaceTexture.class.getDeclaredField("mHasVideoFrame");
        queued.setAccessible(true);
        Surface previous = (Surface) scope.invoke(null, input);
        try {
            if (!SurfaceTexture.macapkHasActiveVideoTarget()
                    || !SurfaceTexture.macapkOfferDecodedFrame(new int[]{0xffff0000}, 1, 1, 1)
                    || !queued.getBoolean(decoder) || queued.getBoolean(fullscreenOutput))
                throw new AssertionError("Fullscreen output stole the decoder's frame");
            Surface nested = (Surface) scope.invoke(null, new Surface());
            try {
                if (SurfaceTexture.macapkHasActiveVideoTarget()
                        || SurfaceTexture.macapkOfferDecodedFrame(new int[]{0}, 1, 1, 1))
                    throw new AssertionError("A SurfaceView frame was sent to an unrelated texture");
            } finally { scope.invoke(null, nested); }
            if (!SurfaceTexture.macapkHasActiveVideoTarget())
                throw new AssertionError("Nested routing scope was not restored");
            decoder.release();
            if (SurfaceTexture.macapkHasActiveVideoTarget())
                throw new AssertionError("Abandoned decoder target is still live");
        } finally { scope.invoke(null, previous); }
        if (!SurfaceTexture.macapkHasActiveVideoTarget())
            throw new AssertionError("Legacy routing was not restored after the frame");
        fullscreenOutput.release();
        System.out.println("PASS: decoder surface routing survives fullscreen texture creation and nested scopes");
    }
}
