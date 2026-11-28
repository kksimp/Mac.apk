import com.macapk.dex.DexConverter;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

public final class TerminalTailSplitSmoke extends ClassLoader {
    public static void main(String[] args) throws Exception {
        int count = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
        byte[] result = DexConverter.convertClass(Files.readAllBytes(Path.of(args[0])), "fixture/TailSwitch");
        if (result == null || new String(result, StandardCharsets.ISO_8859_1).contains("needs code_length"))
            throw new AssertionError("Oversized shared-tail method was not translated");
        Class<?> cls = new TerminalTailSplitSmoke().defineClass("fixture.TailSwitch", result, 0, result.length);
        var lookup = cls.getMethod("lookup", int.class, int.class);
        for (int key = 0; key < count; key++) {
            if (!Integer.valueOf(key * 3 + 12).equals(lookup.invoke(null, key, 0)))
                throw new AssertionError("Wrong successful case " + key);
            int before = fixture.TailOracle.calls;
            if (!Integer.valueOf(-key - 7).equals(lookup.invoke(null, key, 1))
                    || fixture.TailOracle.calls != before + 1)
                throw new AssertionError("Shared tail changed result or side effects at " + key);
        }
        for (int key : new int[]{-1, count + 1})
            if (!Integer.valueOf(-key - 7).equals(lookup.invoke(null, key, 0)))
                throw new AssertionError("Wrong default case");
        for (int key : new int[]{0, count / 2, count - 1}) {
            int before = fixture.TailOracle.calls;
            try { lookup.invoke(null, key, 2); throw new AssertionError("Tail exception was swallowed"); }
            catch (InvocationTargetException expected) {
                if (expected.getCause() != fixture.TailOracle.failure) throw expected;
            }
            if (fixture.TailOracle.calls != before + 1)
                throw new AssertionError("Tail exception changed side effects at " + key);
        }
        System.out.println("PASS: " + count + " switch cases preserve shared-tail results, side effects, and exception identity");
    }
}
