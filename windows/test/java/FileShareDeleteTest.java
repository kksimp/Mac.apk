// java.io's file opens let the file be renamed and deleted while open, as on
// Android (windows/host/java_io_open.cpp), and keep the JDK's behaviour
// otherwise. Run in winapk-binder-test, which binds those opens as
// winapk-runtime does, with Win.apk's java.base overlay:
//
//   WINAPK_TEST_JAVA_BASE_PATCH=<stage>\lib\java_base_patch winapk-binder-test <classpath> FileShareDeleteTest
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class FileShareDeleteTest {
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + what);
        if (!ok) failures++;
    }

    static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("share-delete-test").toFile();

        // Play services' Chimera config: write a pending file, rename it to the
        // live name before the stream that wrote it is closed.
        File pending = new File(dir, "config.pending");
        File live = new File(dir, "config.live");
        try (FileOutputStream out = new FileOutputStream(pending)) {
            out.write(bytes("first "));
            check(pending.renameTo(live), "a file is renamed while its FileOutputStream is open");
            out.write(bytes("second"));
        }
        check(!pending.exists() && live.exists(), "the old name is gone and the new one exists");
        check(Files.readString(live.toPath()).equals("first second"), "writes after the rename reach the file");

        // Deleted while a FileInputStream reads it.
        File doomed = new File(dir, "doomed.txt");
        Files.writeString(doomed.toPath(), "content");
        try (FileInputStream in = new FileInputStream(doomed)) {
            check(doomed.delete(), "a file is deleted while a FileInputStream has it open");
            check(new String(in.readAllBytes(), StandardCharsets.UTF_8).equals("content"),
                  "the open stream still reads the deleted file");
        }
        check(!doomed.exists(), "the deleted file is gone once closed");

        // Renamed while a RandomAccessFile has it open.
        File raf = new File(dir, "raf.bin");
        try (RandomAccessFile f = new RandomAccessFile(raf, "rw")) {
            f.write(bytes("abc"));
            File moved = new File(dir, "raf-moved.bin");
            check(raf.renameTo(moved), "a file is renamed while a RandomAccessFile has it open");
            f.seek(0);
            check(f.read() == 'a', "the RandomAccessFile still reads it");
            check(moved.length() == 3, "the renamed file has the content");
        }

        // A file renamed over one a stream has open (Win.apk's java.base
        // overlay, WinNTFileSystem: rename(2)'s replace).
        File current = new File(dir, "current.pb");
        File next = new File(dir, "pending.pb");
        Files.writeString(current.toPath(), "old");
        Files.writeString(next.toPath(), "new");
        try (FileInputStream reader = new FileInputStream(current)) {
            check(next.renameTo(current), "a file is renamed over one a FileInputStream has open");
            check(new String(reader.readAllBytes(), StandardCharsets.UTF_8).equals("old"),
                  "the open stream still reads the replaced file");
        }
        check(Files.readString(current.toPath()).equals("new") && !next.exists(), "the target holds the new file");

        // The JDK's behaviour otherwise.
        File append = new File(dir, "append.txt");
        try (FileOutputStream out = new FileOutputStream(append)) { out.write(bytes("one")); }
        try (FileOutputStream out = new FileOutputStream(append, true)) { out.write(bytes("two")); }
        check(Files.readString(append.toPath()).equals("onetwo"), "append mode appends");
        try (FileOutputStream out = new FileOutputStream(append)) { out.write(bytes("x")); }
        check(Files.readString(append.toPath()).equals("x"), "a FileOutputStream truncates");

        File missing = new File(dir, "missing.txt");
        try {
            new FileInputStream(missing).close();
            check(false, "opening a missing file throws");
        } catch (FileNotFoundException e) {
            check((missing.getPath() + " (The system cannot find the file specified)").equals(e.getMessage()),
                  "the FileNotFoundException reads as the JDK's: " + e.getMessage());
        }
        try {
            new RandomAccessFile(missing, "r").close();
            check(false, "a missing file opened \"r\" throws");
        } catch (FileNotFoundException e) {
            check(!missing.exists(), "a missing file opened \"r\" throws and is not created");
        }
        try (RandomAccessFile f = new RandomAccessFile(missing, "rw")) {
            check(missing.exists(), "\"rw\" creates the file");
        }
        try {
            new FileOutputStream(dir).close();
            check(false, "a directory cannot be opened for writing");
        } catch (FileNotFoundException e) {
            check(true, "a directory cannot be opened for writing: " + e.getMessage());
        }
        try {
            new FileInputStream((String) null);
            check(false, "a null name throws");
        } catch (NullPointerException e) {
            check(true, "a null name throws NullPointerException");
        }

        System.out.println(failures == 0 ? "FileShareDeleteTest: ALL PASSED" : "FileShareDeleteTest: " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }
}
