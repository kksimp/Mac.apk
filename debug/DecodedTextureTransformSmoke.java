import android.graphics.SurfaceTexture;
import java.lang.reflect.Method;

/** Decoded rows are top-down, so GL consumers need the producer's vertical transform. */
public final class DecodedTextureTransformSmoke {
    public static void main(String[] args) throws Exception {
        SurfaceTexture texture = new SurfaceTexture(0);
        float[] matrix = new float[16];
        texture.getTransformMatrix(matrix);
        if (matrix[5] != 1 || matrix[13] != 0)
            throw new AssertionError("An idle stream must retain the identity transform");
        Method queue = SurfaceTexture.class.getDeclaredMethod("macapkQueueDecodedFrame",
                int[].class, int.class, int.class, int.class);
        queue.setAccessible(true);
        int[] pixels = {0xffff0000, 0xff0000ff};
        queue.invoke(texture, pixels, 1, 2, 1);
        texture.getTransformMatrix(matrix);
        if (matrix[5] != 1) throw new AssertionError("An unlatched frame changed the transform");
        texture.updateTexImage();
        texture.getTransformMatrix(matrix);
        if (matrix[0] != 1 || matrix[5] != -1 || matrix[10] != 1
                || matrix[13] != 1 || matrix[15] != 1)
            throw new AssertionError("Decoded GL texture must map v to 1-v");
        if (pixels[0] != 0xffff0000 || pixels[1] != 0xff0000ff)
            throw new AssertionError("The software consumer's top-down pixels changed");
        texture.release();
        System.out.println("PASS: decoded texture transform is latched and preserves software pixel order");
    }
}
