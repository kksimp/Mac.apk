// File.renameTo onto an existing file replaces it, as rename(2) does on
// Android (windows/java/java_base_patch/java/io/WinNTFileSystem.java). Run on
// Windows with Win.apk's java.base overlay:
//
//   java --patch-module java.base=<stage>\lib\java_base_patch FileRenameTest
import java.io.File;
import java.nio.file.Files;

public class FileRenameTest {
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("rename-test").toFile();
        File target = new File(dir, "icon.png");
        File tmp = new File(dir, "icon.png.tmp");
        Files.writeString(target.toPath(), "old");
        Files.writeString(tmp.toPath(), "new");
        check(tmp.renameTo(target), "a file renamed onto an existing file succeeds");
        check(Files.readString(target.toPath()).equals("new"), "the target holds the new content");
        check(!tmp.exists(), "the source is gone");

        File fresh = new File(dir, "fresh.txt");
        Files.writeString(tmp.toPath(), "x");
        check(tmp.renameTo(fresh) && fresh.exists(), "a rename to a new name still works");

        File sub = new File(dir, "sub");
        check(sub.mkdir(), "mkdir");
        Files.writeString(tmp.toPath(), "y");
        check(!tmp.renameTo(sub) && tmp.exists(), "a file is not renamed onto a directory");
        check(!new File(dir, "missing").renameTo(target) && target.exists(),
              "a missing source fails and leaves the target");

        System.out.println(failures == 0 ? "FileRenameTest: all passed" : "FileRenameTest: " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }
}
