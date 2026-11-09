import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import java.nio.file.Files;
import java.nio.file.Path;

/** The protected-app wrapper creates a second Application and attaches the real base. */
public final class WrappedApplicationInfoSmoke {
    static final class InstalledContext extends ContextWrapper {
        private final ApplicationInfo info;
        InstalledContext(ApplicationInfo info) { super(null); this.info = info; }
        @Override public ApplicationInfo getApplicationInfo() { return info; }
        @Override public String getPackageName() { return info.packageName; }
    }
    static final class DelegateApplication extends Application {
        void attachTo(Context base) { super.attachBaseContext(base); }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("macapk-wrapped-app-");
        try {
            Path libraries = Files.createDirectory(root.resolve("lib"));
            ApplicationInfo installed = new ApplicationInfo();
            installed.packageName = "fixture.wrapped";
            installed.sourceDir = root.resolve("base.apk").toString();
            installed.dataDir = root.toString();
            installed.nativeLibraryDir = libraries.toString();
            DelegateApplication delegate = new DelegateApplication();
            // An early self-query must not pin an empty record after attach.
            delegate.getApplicationInfo();
            delegate.attachTo(new InstalledContext(installed));
            ApplicationInfo actual = delegate.getApplicationInfo();
            if (actual != installed || !new java.io.File(actual.nativeLibraryDir).isDirectory())
                throw new AssertionError("attached Application must expose installed native-library metadata");
            if (delegate.getBaseContext().getApplicationInfo() != installed)
                throw new AssertionError("Application and base Context must agree");
            for (int mode = 0; mode < 3; mode++) {
                final int behavior = mode;
                DelegateApplication fallback = new DelegateApplication();
                fallback.macapkSetDataDir(root.toFile());
                fallback.attachTo(new ContextWrapper(null) {
                    @Override public ApplicationInfo getApplicationInfo() {
                        if (behavior == 0) return null;
                        if (behavior == 1) throw new IllegalStateException("fixture");
                        return new ApplicationInfo();
                    }
                });
                ApplicationInfo safe = fallback.getApplicationInfo();
                if (safe == null || !root.toString().equals(safe.dataDir))
                    throw new AssertionError("delegate fallback/dataDir repair");
            }
            System.out.println("wrapped-app-info: PASS (attached record, cached pre-attach query, real library path)");
        } finally {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }
}
