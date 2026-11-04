package android.app.sdksandbox.sdkprovider;
import java.util.Collections;
import java.util.List;
/** In-sandbox controller an SDK uses to query its host. Mac.apk loads SDKs
 *  in-process (no sandbox), so it reports the app's own package as the client
 *  and no co-loaded sandboxed SDKs. API 34.
 *  Source: frameworks/base/sdksandbox/.../SdkSandboxController.java */
public final class SdkSandboxController {
    public SdkSandboxController() { }
    public String getClientPackageName() {
        String pkg = com.macapk.loader.Boot.packageName();
        return pkg != null ? pkg : "";
    }
    public List<android.app.sdksandbox.SandboxedSdk> getSandboxedSdks() {
        return Collections.emptyList();
    }
}
