// The same probes on the real AOSP framework (Robolectric, native graphics),
// with the probe's own compiled resources. Writes the lines to the file the
// build names (probe.out), the pictures to probe.pictures.
import dev.winapk.probe.Probes;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.PrintWriter;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ProbeOracleTest {
    @Test
    public void probes() throws Exception {
        String pictures = System.getProperty("probe.pictures");
        try (PrintWriter out = new PrintWriter(System.getProperty("probe.out"), "UTF-8")) {
            for (String line : Probes.runAll(RuntimeEnvironment.getApplication(),
                                             pictures == null ? null : new File(pictures))) {
                out.println(line);
            }
            out.println("done");
        }
    }
}
