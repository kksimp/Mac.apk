import android.content.ContentResolver;
import android.net.Uri;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Mirrors media clients that try a descriptor before opening the URL themselves. */
public final class ContentDescriptorFallbackSmoke {
    interface Open { Object run() throws Exception; }
    private static void missing(Open open) throws Exception {
        try {
            open.run();
            throw new AssertionError("Missing provider must throw to select the URL fallback");
        } catch (FileNotFoundException expected) { }
    }
    public static void main(String[] args) throws Exception {
        ContentResolver resolver = new ContentResolver();
        for (String value : new String[]{"https://example.invalid/video.mp4",
                "http://example.invalid/video.mp4", "content://missing.example/video/1"}) {
            Uri uri = Uri.parse(value);
            missing(() -> resolver.openAssetFileDescriptor(uri, "r"));
            missing(() -> resolver.openAssetFileDescriptor(uri, "w", null));
            missing(() -> resolver.openTypedAssetFileDescriptor(uri, "video/*", null));
            missing(() -> resolver.openTypedAssetFileDescriptor(uri, "video/*", null, null));
            missing(() -> resolver.openFileDescriptor(uri, "r"));
            boolean networkFallback = false;
            try {
                if (resolver.openAssetFileDescriptor(uri, "r") == null)
                    throw new AssertionError("A null result silently skips media URL fallback");
            } catch (IOException expected) { networkFallback = true; }
            if (!networkFallback) throw new AssertionError("URL fallback was not selected");
        }
        ContentResolver liveNull = ContentResolver.wrap(new android.content.ContentProvider() {
            public boolean onCreate() { return true; }
            public android.content.res.AssetFileDescriptor openAssetFile(Uri uri, String mode,
                    android.os.CancellationSignal signal) { return null; }
            public android.content.res.AssetFileDescriptor openTypedAssetFile(Uri uri, String type,
                    android.os.Bundle options, android.os.CancellationSignal signal) { return null; }
        });
        Uri supplied = Uri.parse("content://registered.example/video/1");
        if (liveNull.openAssetFileDescriptor(supplied, "r") != null
                || liveNull.openTypedAssetFileDescriptor(supplied, "video/*", null) != null)
            throw new AssertionError("A live provider's null result must be preserved");
        ContentResolver failure = ContentResolver.wrap(new android.content.ContentProvider() {
            public boolean onCreate() { return true; }
            public android.content.res.AssetFileDescriptor openAssetFile(Uri uri, String mode,
                    android.os.CancellationSignal signal) throws FileNotFoundException {
                throw new FileNotFoundException("provider failure");
            }
        });
        try { failure.openAssetFileDescriptor(supplied, "r");
            throw new AssertionError("Provider failure was swallowed");
        } catch (FileNotFoundException expected) {
            if (!"provider failure".equals(expected.getMessage())) throw expected;
        }
        System.out.println("PASS: missing descriptor providers throw and select the media URL fallback");
    }
}
