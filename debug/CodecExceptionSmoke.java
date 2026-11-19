import android.media.MediaCodec;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/** Exercises the fields read by native MediaCodec clients after a failure. */
public final class CodecExceptionSmoke {
    public static void main(String[] args) throws Exception {
        Class<MediaCodec.CodecException> type = MediaCodec.CodecException.class;
        Field action = type.getDeclaredField("mActionCode");
        Field error = type.getDeclaredField("mErrorCode");
        Field diagnostic = type.getDeclaredField("mDiagnosticInfo");
        action.setAccessible(true);
        error.setAccessible(true);
        diagnostic.setAccessible(true);
        Constructor<MediaCodec.CodecException> constructor =
                type.getDeclaredConstructor(int.class, int.class, String.class);
        constructor.setAccessible(true);
        for (int code : new int[]{0, 1, 2}) {
            MediaCodec.CodecException exception = constructor.newInstance(-42, code, "decoder failure");
            if (action.getInt(exception) != code || error.getInt(exception) != -42
                    || exception.getErrorCode() != -42
                    || exception.isTransient() != (code == 1)
                    || exception.isRecoverable() != (code == 2)
                    || !"android.media.MediaCodec.error_neg_42".equals(diagnostic.get(exception))
                    || !exception.getDiagnosticInfo().equals(diagnostic.get(exception))
                    || !"decoder failure".equals(exception.getMessage()))
                throw new AssertionError("Incorrect CodecException contract for action " + code);
        }
        System.out.println("PASS: CodecException JNI fields, action codes, and diagnostics");
    }
}
