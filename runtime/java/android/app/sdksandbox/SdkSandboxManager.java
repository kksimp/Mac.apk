// android.app.sdksandbox.SdkSandboxManager -- the API-33 Privacy Sandbox entry
// point, static surface only.
//
// AOSP: packages/modules/AdServices/sdksandbox/framework/java/android/app/
// sdksandbox/SdkSandboxManager.java (SDK_SANDBOX_SERVICE = "sdk_sandbox":85,
// SDK_SANDBOX_STATE_DISABLED = 0:226, SDK_SANDBOX_STATE_ENABLED_PROCESS_ISOLATION
// = 2:234, getSdkSandboxState():321).
//
// Apps probe getSdkSandboxState() before touching the sandbox (the Play Store
// does, and without this class the probe died with NoClassDefFoundError).
// AOSP main reports ENABLED unconditionally because every such device hosts
// the sandbox process; we host no sandbox and getSystemService("sdk_sandbox")
// answers null, so the honest state is DISABLED -- the answer a device without
// the sandbox gives, which callers already handle by not using it.
package android.app.sdksandbox;

public final class SdkSandboxManager {
    public static final String SDK_SANDBOX_SERVICE = "sdk_sandbox";
    public static final int SDK_SANDBOX_STATE_DISABLED = 0;
    public static final int SDK_SANDBOX_STATE_ENABLED_PROCESS_ISOLATION = 2;

    private SdkSandboxManager() {}

    public static int getSdkSandboxState() {
        return SDK_SANDBOX_STATE_DISABLED;
    }
}
