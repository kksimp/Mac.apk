import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import com.macapk.graphics.MacApkSkia;
import java.util.Arrays;

/** Pixel-contract smoke test. Run against the built framework, not Android SDK stubs. */
public final class AgslRenderingSmoke {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void near(int actual, int expected, int tolerance, String message) {
        check(Math.abs(actual - expected) <= tolerance,
                message + ": expected " + expected + ", got " + actual);
    }

    private static int red(int pixel) { return (pixel >>> 16) & 255; }
    private static int alpha(int pixel) { return pixel >>> 24; }

    private static Bitmap draw(RuntimeShader shader, int w, int h) {
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint();
        paint.setShader(shader);
        new Canvas(bitmap).drawRect(0, 0, w, h, paint);
        return bitmap;
    }

    private static int[] filter(RenderEffect effect, int[] input, int w, int h) {
        int[] output = new int[input.length];
        check(MacApkSkia.applyFilter(effect.getNativeFilter(), input, w, h, output),
                "filter execution failed");
        return output;
    }

    private static void transparentTiles() {
        RuntimeShader opaque = new RuntimeShader("half4 main(float2 p) { return half4(1); }");
        RuntimeShader transparent = new RuntimeShader("half4 main(float2 p) { return half4(0); }");
        RuntimeShader edge = new RuntimeShader(
                "half4 main(float2 p) { half a=half(clamp(p.x/64.0,0.0,1.0)); return half4(a,0,0,a); }");
        for (int iteration = 0; iteration < 4; iteration++) {
            draw(opaque, 128, 64);
            Bitmap clear = draw(transparent, 97, 53);
            check(clear.getPixel(0, 0) == 0 && clear.getPixel(96, 52) == 0,
                    "opaque pixels survived transparent shader tile");
            Bitmap gradient = draw(edge, 128, 64);
            near(alpha(gradient.getPixel(32, 20)), 129, 2, "shader edge alpha");
            near(red(gradient.getPixel(32, 20)), 255, 1, "straight-alpha shader edge color");
        }
        Bitmap masked = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888);
        masked.eraseColor(0xFFFFFFFF);
        Paint mask = new Paint();
        mask.setShader(transparent);
        mask.setBlendMode(BlendMode.DST_IN);
        new Canvas(masked).drawRect(0, 0, 128, 64, mask);
        check(masked.getPixel(64, 32) == 0, "DstIn shader failed to remove content");
        System.out.println("PASS transparent tile reuse, straight alpha, DstIn mask");
    }

    private static void combinedTransforms() {
        RuntimeShader shader = new RuntimeShader(
                "half4 main(float2 p) { return half4(clamp(p.x/100.0,0.0,1.0),0,0,1); }");
        Matrix local = new Matrix();
        local.setTranslate(10, 0);
        shader.setLocalMatrix(local);
        Bitmap bitmap = Bitmap.createBitmap(300, 160, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.translate(30, 20);
        canvas.scale(2, 2);
        Paint paint = new Paint();
        paint.setShader(shader);
        canvas.drawRect(0, 0, 120, 50, paint);
        // shader x = (device x - 30)/2 - 10; Java2D may split the fill into tiles.
        near(red(bitmap.getPixel(150, 60)), 128, 3, "canvas x shader local matrix order");
        near(red(bitmap.getPixel(50, 60)), 0, 3, "translated shader origin");
        System.out.println("PASS translated/scaled canvas with shader local matrix");
    }
    private static void fourCornerMask() {
        RuntimeShader shader = new RuntimeShader("half4 main(float2 p) {"
                + "float2 d=min(p,float2(100,70)-p);"
                + "float a=(d.x<20 && d.y<20) ? smoothstep(0.5,-0.5,length(float2(20)-d)-20) : 1;"
                + "return half4(a); }");
        Bitmap target = Bitmap.createBitmap(160, 130, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(target);
        canvas.translate(23, 31);
        Paint fill = new Paint();
        fill.setColor(0xFFFFFFFF);
        canvas.drawRect(0, 0, 100, 70, fill);
        Paint mask = new Paint();
        mask.setShader(shader);
        mask.setBlendMode(BlendMode.DST_IN);
        for (int[] corner : new int[][]{{0,0},{80,0},{80,50},{0,50}}) {
            canvas.save();
            canvas.clipRect(corner[0], corner[1], corner[0]+20, corner[1]+20);
            canvas.drawRect(0, 0, 100, 70, mask);
            canvas.restore();
        }
        for (int[] corner : new int[][]{{23,31},{122,31},{122,100},{23,100}}) {
            check(alpha(target.getPixel(corner[0], corner[1])) == 0,
                    "translated corner remained opaque: " + Arrays.toString(corner));
        }
        check(target.getPixel(73,66) == 0xFFFFFFFF, "corner mask removed center content");
        System.out.println("PASS four independently clipped corner masks");
    }


    private static void filterReuseAndSnapshots() {
        RuntimeShader gain = new RuntimeShader("uniform shader child; uniform float gain; "
                + "half4 main(float2 p) { half4 c=child.eval(p); return half4(c.rgb*gain,c.a); }");
        gain.setFloatUniform("gain", 0.25f);
        RenderEffect quarter = RenderEffect.createRuntimeShaderEffect(gain, "child");
        gain.setFloatUniform("gain", 0.75f);
        RenderEffect threeQuarter = RenderEffect.createRuntimeShaderEffect(gain, "child");
        gain.setFloatUniform("gain", 1f);
        int[] white = new int[96 * 48];
        Arrays.fill(white, 0xFFFFFFFF);
        near(red(filter(quarter, white, 96, 48)[100]), 64, 2, "effect uniform snapshot quarter");
        near(red(filter(threeQuarter, white, 96, 48)[100]), 191, 2, "effect uniform snapshot threeQuarter");
        RuntimeShader identity = new RuntimeShader(
                "uniform shader child; half4 main(float2 p) { return child.eval(p); }");
        RenderEffect effect = RenderEffect.createRuntimeShaderEffect(identity, "child");
        filter(effect, white, 96, 48);
        int[] clear = new int[31 * 17];
        int[] result = filter(effect, clear, 31, 17);
        for (int pixel : result) check(pixel == 0, "filter retained unrelated prior output");
        int[] translucent = new int[31 * 17];
        Arrays.fill(translucent, 0x80FF0000);
        int pixel = filter(effect, translucent, 31, 17)[100];
        near(alpha(pixel), 128, 1, "identity filter alpha");
        near(red(pixel), 255, 1, "identity filter straight RGB");
        System.out.println("PASS filter buffer reuse, translucent input, immutable effect snapshots");
    }

    private static void renderNodeBlur() {
        RenderNode node = new RenderNode("agsl-smoke");
        node.setPosition(0, 0, 96, 48);
        node.setRenderEffect(RenderEffect.createBlurEffect(8f, 0f, Shader.TileMode.CLAMP));
        Canvas recording = node.beginRecording(96, 48);
        Paint paint = new Paint();
        paint.setColor(0xFFFFFFFF);
        recording.drawRect(0, 0, 48, 48, paint);
        paint.setColor(0xFF000000);
        recording.drawRect(48, 0, 96, 48, paint);
        node.endRecording();
        Bitmap target = Bitmap.createBitmap(96, 48, Bitmap.Config.ARGB_8888);
        new Canvas(target).drawRenderNode(node);
        int boundary = red(target.getPixel(48, 24));
        check(boundary > 30 && boundary < 220, "RenderNode blur did not blend source boundary");
        near(red(target.getPixel(5, 24)), 255, 2, "blur left source");
        near(red(target.getPixel(90, 24)), 0, 2, "blur right source");
        System.out.println("PASS RenderNode blur source and compositing");
    }
    private static void offsetBackdropSource() {
        RenderNode source = new RenderNode("backdrop-source");
        source.setPosition(0, 0, 96, 160);
        Canvas recording = source.beginRecording(96, 160);
        Paint fill = new Paint();
        fill.setColor(0xFFFF0000);
        recording.drawRect(0, 0, 96, 80, fill);
        fill.setColor(0xFF0000FF);
        recording.drawRect(0, 80, 96, 160, fill);
        source.endRecording();

        RenderNode bar = new RenderNode("offset-backdrop-consumer");
        bar.setPosition(0, 0, 96, 32);
        bar.setRenderEffect(RenderEffect.createBlurEffect(4f, 4f, Shader.TileMode.CLAMP));
        Canvas consumer = bar.beginRecording(96, 32);
        consumer.translate(0, -112);
        consumer.drawRenderNode(source);
        bar.endRecording();
        Bitmap target = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(target);
        canvas.translate(24, 40);
        canvas.drawRenderNode(bar);
        int pixel = target.getPixel(72, 56);
        near(red(pixel), 0, 2, "backdrop must not sample source's top red band");
        near(pixel & 255, 255, 2, "backdrop must sample translated blue source band");
        check(target.getPixel(0, 0) == 0, "backdrop escaped consumer bounds");
        System.out.println("PASS translated backdrop source through blurred RenderNode");
    }

    private static void foregroundLayerMask() {
        Bitmap target = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888);
        target.eraseColor(0xFF0066FF);
        Canvas canvas = new Canvas(target);
        Paint layer = new Paint();
        // A stale legacy mode must not override the modern DstIn request.
        layer.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_OVER));
        layer.setBlendMode(BlendMode.DST_IN);
        canvas.saveLayer(0, 0, 64, 32, layer);
        Paint content = new Paint();
        content.setColor(0xFFFFFFFF);
        canvas.drawRect(16, 8, 48, 24, content);
        canvas.restore();
        check(target.getPixel(2, 2) == 0, "foreground mask did not remove outside content");
        check(target.getPixel(32, 16) == 0xFF0066FF, "foreground mask replaced backdrop with mask color");
        target.eraseColor(0xFF0066FF);
        canvas.save();
        canvas.clipRect(8, 4, 56, 28);
        canvas.saveLayer(0, 0, 64, 32, layer);
        canvas.drawRect(16, 8, 48, 24, content);
        canvas.restore();
        canvas.restore();
        check(target.getPixel(2, 2) == 0xFF0066FF, "foreground mask erased outside parent clip");
        check(target.getPixel(10, 6) == 0, "foreground mask did not clear unmasked parent area");
        System.out.println("PASS foreground saveLayer DstIn and modern blend precedence");
    }

    private static void recordedShaderSnapshot() {
        RuntimeShader shader = new RuntimeShader("uniform float red; half4 main(float2 p){return half4(red,0,0,1);}");
        shader.setFloatUniform("red", 0.25f);
        RenderNode recorded = new RenderNode("recorded-uniform-snapshot");
        recorded.setPosition(0, 0, 64, 32);
        Paint paint = new Paint();
        paint.setShader(shader);
        recorded.beginRecording(64, 32).drawRect(0, 0, 64, 32, paint);
        recorded.endRecording();
        shader.setFloatUniform("red", 0.75f);
        Bitmap target = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888);
        new Canvas(target).drawRenderNode(recorded);
        near(red(target.getPixel(32, 16)), 64, 2, "recorded shader uniform changed before replay");
        System.out.println("PASS recorded shader immutable uniform snapshot");
    }

    private static void shaderResultInvalidation() {
        RuntimeShader shader = new RuntimeShader("uniform float red; half4 main(float2 p){return half4(red,0,0,1);}");
        shader.setFloatUniform("red", 0.25f);
        near(red(draw(shader, 100, 60).getPixel(50,30)), 64, 2, "initial shader result");
        shader.setFloatUniform("red", 0.75f);
        near(red(draw(shader, 100, 60).getPixel(50,30)), 191, 2, "shader result stayed stale after uniform mutation");
        RuntimeShader coordinates = new RuntimeShader("half4 main(float2 p){return half4(clamp(p.x/100.0,0.0,1.0),0,0,1);}");
        near(red(draw(coordinates, 100, 60).getPixel(50,30)), 129, 2, "initial shader local matrix");
        Matrix matrix = new Matrix();
        matrix.setTranslate(20, 0);
        coordinates.setLocalMatrix(matrix);
        near(red(draw(coordinates, 100, 60).getPixel(50,30)), 78, 2, "shader result stayed stale after matrix mutation");
        System.out.println("PASS shader reuse invalidation for uniforms and local matrix");
    }
    private static void uniformContracts() {
        RuntimeShader integers = new RuntimeShader("uniform int v[5]; half4 main(float2 p){"
                + "return half4(float(v[0]+v[4])/8,float(v[1]-v[2]+v[3])/8,0,1);}");
        integers.setIntUniform("v", new int[]{-1, 5, 2, -1, 3});
        int pixel = draw(integers, 8, 4).getPixel(3, 1);
        near(red(pixel), 64, 1, "signed integer array red");
        near((pixel >>> 8) & 255, 64, 1, "signed integer array green");

        RuntimeShader colors = new RuntimeShader("layout(color) uniform half4 c;"
                + "half4 main(float2 p){return half4(c.rgb,1);}");
        colors.setColorUniform("c", 0x80FF0000);
        check(draw(colors, 8, 4).getPixel(3, 1) == 0xFFFF0000,
                "color uniform was premultiplied before shader execution");
        colors.setColorUniform("c", Color.pack(0xFF336699));
        check(draw(colors, 8, 4).getPixel(3, 1) == 0xFF336699, "packed sRGB color lost its high bits");
        // Verify the Color and packed-wide-color overloads preserve float precision
        // through conversion from linear to the shader's sRGB working space.
        Color linear = Color.valueOf(0.25f, 0.5f, 0.75f, 0.5f,
                ColorSpace.get(ColorSpace.Named.LINEAR_SRGB));
        for (boolean packed : new boolean[]{false, true}) {
            if (packed) colors.setColorUniform("c", linear.pack());
            else colors.setColorUniform("c", linear);
            pixel = draw(colors, 8, 4).getPixel(3, 1);
            near(red(pixel), 137, 1, "linear color red conversion");
            near((pixel >>> 8) & 255, 188, 1, "linear color green conversion");
            near(pixel & 255, 225, 1, "linear color blue conversion");
        }
        System.out.println("PASS signed integer arrays, straight color uniforms, packed and linear colors");
    }

    private static void childShaderMatrices() {
        Bitmap image = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888);
        image.setPixel(0, 0, 0xFFFF0000);
        image.setPixel(1, 0, 0xFF0000FF);
        BitmapShader bitmap = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix scale = new Matrix();
        scale.setScale(10, 10);
        bitmap.setLocalMatrix(scale);
        RuntimeShader parent = new RuntimeShader("uniform shader child; half4 main(float2 p){return child.eval(p);}");
        parent.setInputShader("child", bitmap);
        Bitmap result = draw(parent, 20, 4);
        check(result.getPixel(5, 1) == 0xFFFF0000, "bitmap child ignored local scale");
        check(result.getPixel(15, 1) == 0xFF0000FF, "bitmap child sampled wrong second texel");
        bitmap.setLocalMatrix(null);
        parent.setInputShader("child", bitmap);
        check(draw(parent, 20, 4).getPixel(5, 1) == 0xFF0000FF, "bitmap child matrix update stayed cached");

        RuntimeShader child = new RuntimeShader("half4 main(float2 p){return half4(p.x<1?1:0,0,p.x<1?0:1,1);}");
        child.setLocalMatrix(scale);
        parent.setInputShader("child", child);
        result = draw(parent, 20, 4);
        check(result.getPixel(5, 1) == 0xFFFF0000, "runtime child ignored local scale");
        check(result.getPixel(15, 1) == 0xFF0000FF, "runtime child coordinate composition failed");
        System.out.println("PASS bitmap and runtime child shader matrices and input invalidation");
    }



    public static void main(String[] args) {
        check(MacApkSkia.isAvailable(), "Skia native library unavailable");
        check(MacApkSkia.backendName().startsWith("Metal:"), "AGSL silently fell back to a CPU backend");
        transparentTiles();
        combinedTransforms();
        fourCornerMask();
        filterReuseAndSnapshots();
        renderNodeBlur();
        offsetBackdropSource();
        foregroundLayerMask();
        recordedShaderSnapshot();
        shaderResultInvalidation();
        uniformContracts();
        childShaderMatrices();
    }
}
