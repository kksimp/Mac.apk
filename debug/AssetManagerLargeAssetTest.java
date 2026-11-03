import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * AssetManager.open() must not materialise an asset on the Java heap. GameMaker's
 * RunnerActivity.checkIsYoYoRunner opens assets/game.droid (1 GB in shipped games) just to
 * test that it exists; buffering it threw OutOfMemoryError against the runtime's -Xmx1g and
 * aborted onCreate. The heap ceiling below reproduces that with an asset larger than the heap.
 * From the repository root, using the jars under test:
 * java -Xmx64m -cp runtime/build/lib/android-framework.jar:runtime/build/lib/macapk-loader.jar debug/AssetManagerLargeAssetTest.java
 */
public final class AssetManagerLargeAssetTest {
    private static final int LARGE = 96 << 20;
    private static final int SMALL = 64 << 10;
    private static int failures;

    public static void main(String[] args) throws IOException {
        if (Runtime.getRuntime().maxMemory() >= LARGE) {
            throw new AssertionError("run with -Xmx64m: the large asset must exceed the heap ("
                    + Runtime.getRuntime().maxMemory() + " >= " + LARGE + ")");
        }
        File apk = File.createTempFile("asset-test", ".apk");
        apk.deleteOnExit();
        writeApk(apk);
        AssetManager am = new AssetManager(apk.getPath());
        try {
            run("large asset opens and streams intact", () -> streamsIntact(am, "big.bin", LARGE));
            run("small asset opens and reads intact", () -> streamsIntact(am, "small.bin", SMALL));
            run("large asset available() is the remaining length", () -> available(am, "big.bin", LARGE));
            run("small asset available() is the remaining length", () -> available(am, "small.bin", SMALL));
            run("large asset mark/reset rereads the same bytes", () -> markReset(am, "big.bin"));
            run("small asset mark/reset rereads the same bytes", () -> markReset(am, "small.bin"));
            run("large asset skip lands on the right byte", () -> skip(am, "big.bin"));
            run("large asset read after close throws", () -> readAfterClose(am, "big.bin"));
        } finally {
            am.close();
        }
        if (failures != 0) throw new AssertionError(failures + " AssetManager.open regression(s) failed");
        System.out.println("AssetManagerLargeAssetTest PASS");
    }

    private interface Check { void run() throws IOException; }

    private static void run(String name, Check check) {
        try {
            check.run();
            System.out.println("PASS " + name);
        } catch (AssertionError | IOException | RuntimeException | OutOfMemoryError error) {
            failures++;
            System.err.println("FAIL " + name + ": " + error);
        }
    }

    private static int byteAt(long pos) {
        return (int) ((pos * 31 + (pos >>> 13)) & 0xff);
    }

    private static void writeApk(File apk) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(apk))) {
            writeEntry(zip, "assets/big.bin", LARGE);
            writeEntry(zip, "assets/small.bin", SMALL);
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, int size) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        byte[] chunk = new byte[1 << 16];
        for (long pos = 0; pos < size; pos += chunk.length) {
            int n = (int) Math.min(chunk.length, size - pos);
            for (int i = 0; i < n; i++) chunk[i] = (byte) byteAt(pos + i);
            zip.write(chunk, 0, n);
        }
        zip.closeEntry();
    }

    private static void streamsIntact(AssetManager am, String name, int size) throws IOException {
        try (InputStream in = am.open(name)) {
            byte[] buf = new byte[1 << 16];
            long pos = 0;
            for (int n; (n = in.read(buf)) > 0; pos += n) {
                for (int i = 0; i < n; i++) {
                    if ((buf[i] & 0xff) != byteAt(pos + i)) {
                        throw new AssertionError("byte " + (pos + i) + " differs");
                    }
                }
            }
            if (pos != size) throw new AssertionError("read " + pos + " bytes, expected " + size);
        }
    }

    private static void available(AssetManager am, String name, int size) throws IOException {
        try (InputStream in = am.open(name)) {
            expect(size, in.available(), "available() before reading");
            in.readNBytes(1000);
            expect(size - 1000, in.available(), "available() after 1000 bytes");
        }
    }

    private static void markReset(AssetManager am, String name) throws IOException {
        try (InputStream in = am.open(name)) {
            if (!in.markSupported()) throw new AssertionError("markSupported() is false");
            in.readNBytes(12345);
            in.mark(Integer.MAX_VALUE);
            byte[] first = in.readNBytes(4096);
            in.reset();
            byte[] again = in.readNBytes(4096);
            if (!Arrays.equals(first, again)) throw new AssertionError("bytes after reset differ");
            expect(byteAt(12345), first[0] & 0xff, "first byte after mark");
        }
    }

    private static void skip(AssetManager am, String name) throws IOException {
        try (InputStream in = am.open(name)) {
            long target = 50L << 20;
            long left = target;
            while (left > 0) {
                long s = in.skip(left);
                if (s <= 0) throw new AssertionError("skip stalled at " + (target - left));
                left -= s;
            }
            expect(byteAt(target), in.read(), "byte after skip");
        }
    }

    private static void readAfterClose(AssetManager am, String name) throws IOException {
        InputStream in = am.open(name);
        in.close();
        try {
            in.read();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("read() after close() did not throw");
    }

    private static void expect(long want, long got, String what) {
        if (want != got) throw new AssertionError(what + ": expected " + want + ", got " + got);
    }
}
