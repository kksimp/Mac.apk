package dev.macapk.fixture.nativeload;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import dalvik.system.DexClassLoader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Independently authored Android API contract fixture. */
public final class NativeLoadActivity extends Activity {
    private static final String TAG = "NativeLoadFixture";

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        TextView text = new TextView(this);
        setContentView(text);
        String result;
        try {
            File dex = new File(getCodeCacheDir(), "child.dex");
            try (InputStream in = getAssets().open("child.dex");
                 FileOutputStream out = new FileOutputStream(dex)) {
                byte[] buffer = new byte[4096];
                for (int n; (n = in.read(buffer)) != -1;) out.write(buffer, 0, n);
            }
            if (!dex.setReadOnly()) throw new AssertionError("read-only child DEX");
            String directory = getApplicationInfo().nativeLibraryDir;
            ClassLoader child = new DexClassLoader(dex.getAbsolutePath(),
                    getCodeCacheDir().getAbsolutePath(), directory, getClassLoader());
            check(child, "ChildSystem", directory);
            Log.i(TAG, "CHECK: System.load, dependency closure, JNI_OnLoad timing and API errors");
            check(child, "ChildRuntime", directory);
            Log.i(TAG, "CHECK: Runtime.load and independent child-loader JNI callback");
            result = "PASS: Android native naming, loading, dependencies and child JNI";
        } catch (Throwable t) {
            result = "FAIL: " + t;
            Log.e(TAG, result, t);
        }
        System.out.println(TAG + " " + result);
        text.setText(result);
    }

    private static void check(ClassLoader child, String name, String directory) throws Exception {
        Class<?> type = Class.forName("dev.macapk.fixture.nativeload." + name, true, child);
        if (type.getClassLoader() != child) throw new AssertionError("child defining loader");
        type.getMethod("check", String.class).invoke(null, directory);
    }
}
