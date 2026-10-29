package com.macapk.loader;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import java.lang.reflect.*;
import java.util.*;

/** Tests the production intent/result handler with a failing transport double.
 * This does not start OS authentication or mint any native authorization. */
public final class DeviceCredentialIntentSmoke {
    static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
    static final class Caller extends Activity {
        final List<String> events = new ArrayList<>();
        int results;
        @Override public void onActivityResult(int request, int result, Intent data) {
            check(request == 42 && result == Activity.RESULT_CANCELED && data == null, "failed auth must cancel");
            results++; events.add("result");
        }
    }
    public static void main(String[] args) throws Exception {
        Intent intent = new Intent(KeyguardManager.ACTION_CONFIRM_DEVICE_CREDENTIAL).setPackage("com.android.settings");
        check(DeviceCredentialHandler.isCredentialIntent(intent), "platform credential intent resolves");
        check(!DeviceCredentialHandler.isCredentialIntent(new Intent("unrelated")), "unrelated action rejected");
        check(!DeviceCredentialHandler.isCredentialIntent(new Intent(intent).setPackage("another.app")), "foreign package rejected");
        check(!DeviceCredentialHandler.isCredentialIntent(new Intent(intent).setData(android.net.Uri.parse("https://example.invalid"))), "unexpected data rejected");
        check(!DeviceCredentialHandler.isCredentialIntent(new Intent(intent).addCategory("another.category")), "unexpected category rejected");
        check(DeviceCredentialHandler.resolveInfo().activityInfo.packageName.equals("com.android.settings"), "query and start agree on Settings owner");
        Intent pinned = new Intent(intent).setComponent(new android.content.ComponentName(
                "com.android.settings", DeviceCredentialHandler.resolveInfo().activityInfo.name));
        check(DeviceCredentialHandler.isCredentialIntent(pinned), "resolved component can be pinned for start");
        check(!DeviceCredentialHandler.isCredentialIntent(new Intent(intent).setComponent(
                new android.content.ComponentName("com.android.settings", "com.android.settings.Unrelated"))), "other explicit components rejected");
        Caller caller = new Caller();
        Class<?> pending = Class.forName("android.app.Activity$PendingResult");
        Constructor<?> constructor = pending.getDeclaredConstructor(Activity.class, Intent.class, int.class);
        constructor.setAccessible(true);
        Field slot = Activity.class.getDeclaredField("sPendingResult"); slot.setAccessible(true);
        slot.set(null, constructor.newInstance(caller, intent, 42));
        SpecialAccessHandler.Cover cover = new SpecialAccessHandler.Cover() {
            public void cover() { caller.events.add("cover"); }
            public void uncover() { caller.events.add("resume"); }
        };
        check(DeviceCredentialHandler.handle(intent, intent, cover), "credential action handled");
        check(slot.get(null) == null, "pending result claimed synchronously");
        check(caller.results == 0, "no result before authentication callback");
        UserVerificationBridge.fail(); UserVerificationBridge.fail();
        check(caller.results == 1, "duplicate terminal callbacks settle once");
        check(caller.events.equals(List.of("cover", "result", "resume")), "result delivered before resume");
        System.out.println("device-credential-intent: PASS (resolution, claim, delayed failure, result ordering, exactly once; no authentication prompt)");
    }
}
