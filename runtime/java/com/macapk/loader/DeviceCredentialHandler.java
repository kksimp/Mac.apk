package com.macapk.loader;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.hardware.biometrics.BiometricManager;

/** Android's confirm-device-credential activity backed by the Mac's real
 * password check. A result is delivered only after the OS prompt finishes. */
final class DeviceCredentialHandler {
    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final String COMPONENT = SETTINGS_PACKAGE + ".password.ConfirmDeviceCredentialActivity";

    private DeviceCredentialHandler() {}

    static boolean isCredentialIntent(Intent intent) {
        if (!UserVerificationBridge.available() || intent == null
                || !KeyguardManager.ACTION_CONFIRM_DEVICE_CREDENTIAL.equals(intent.getAction())
                || intent.getData() != null || intent.getType() != null) return false;
        android.content.ComponentName component = intent.getComponent();
        if (component != null && (!SETTINGS_PACKAGE.equals(component.getPackageName())
                || !COMPONENT.equals(component.getClassName()))) return false;
        String target = intent.getPackage();
        if (target != null && !SETTINGS_PACKAGE.equals(target)) return false;
        java.util.Set<String> categories = intent.getCategories();
        if (categories != null) for (String category : categories)
            if (!Intent.CATEGORY_DEFAULT.equals(category)) return false;
        return true;
    }

    static ResolveInfo resolveInfo() {
        ResolveInfo result = new ResolveInfo();
        result.activityInfo = new ActivityInfo();
        result.activityInfo.packageName = SETTINGS_PACKAGE;
        result.activityInfo.name = COMPONENT;
        result.activityInfo.exported = true;
        result.activityInfo.enabled = true;
        result.activityInfo.applicationInfo = new android.content.pm.ApplicationInfo();
        result.activityInfo.applicationInfo.packageName = SETTINGS_PACKAGE;
        result.activityInfo.applicationInfo.flags |= android.content.pm.ApplicationInfo.FLAG_SYSTEM;
        result.filter = new IntentFilter(KeyguardManager.ACTION_CONFIRM_DEVICE_CREDENTIAL);
        result.filter.addCategory(Intent.CATEGORY_DEFAULT);
        result.isDefault = true;
        return result;
    }

    static boolean handle(Intent outer, Intent real, SpecialAccessHandler.Cover cover) {
        if (!isCredentialIntent(real)) return false;
        Activity.MacApkHostResult token = Activity.macapkClaimHostResult(outer);
        CharSequence title = real.getCharSequenceExtra(KeyguardManager.EXTRA_TITLE);
        CharSequence description = real.getCharSequenceExtra(KeyguardManager.EXTRA_DESCRIPTION);
        String reason = description != null && description.length() != 0 ? description.toString()
                : title != null && title.length() != 0 ? title.toString() : "验证身份以继续此应用的操作";
        cover.cover();
        java.util.concurrent.atomic.AtomicBoolean delivered = new java.util.concurrent.atomic.AtomicBoolean();
        UserVerificationBridge.Callback result = (success, type, error, message) -> {
            if (delivered.compareAndSet(false, true)) {
                SpecialAccessHandler.finishWithResult(cover, token,
                        success && error == 0 && type == android.hardware.biometrics.BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL
                                ? Activity.RESULT_OK : Activity.RESULT_CANCELED, null);
            }
        };
        try {
            UserVerificationBridge.authenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                    reason, null, Runnable::run, result);
        } catch (RuntimeException | LinkageError failure) {
            result.completed(false, 0, 1, "Device authentication is unavailable");
        }
        return true;
    }
}
