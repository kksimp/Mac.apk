// android.provider.DeviceConfig -- AOSP's device-wide feature-flag store
// (android14: packages/modules/ConfigInfrastructure/framework/java/android/
// provider/DeviceConfig.java, a @SystemApi used by system and Google apps).
//
// AOSP keeps the values in Settings.Config ("namespace/name" keys), written
// by the server-pushed flag sync and by `adb shell device_config`; getters
// return the caller's default for anything not set. Here the same keys live
// in the settings store (SettingsStore, "config/" namespace) and nothing is
// pushed, so a device with no flag overrides is what apps see -- which is what
// they see on a device that has not synced any. Missing entirely, the class
// threw NoClassDefFoundError: Google Services Framework's GservicesProvider
// asks DeviceConfig.getBoolean("gservices", "enable_gmscore_gservices_storage",
// false) in onCreate, so onCreate aborted, its value cache stayed null, and
// every gservices prefix query (the Play Store's) died with an NPE.
package android.provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

public final class DeviceConfig {
    private DeviceConfig() {}

    public static final String NAMESPACE_ACCESSIBILITY = "accessibility";
    public static final String NAMESPACE_ACTIVITY_MANAGER = "activity_manager";
    public static final String NAMESPACE_ACTIVITY_MANAGER_COMPONENT_ALIAS = "activity_manager_ca";
    public static final String NAMESPACE_AUTO_PIN_CONFIRMATION = "auto_pin_confirmation";
    public static final String NAMESPACE_ACTIVITY_MANAGER_NATIVE_BOOT = "activity_manager_native_boot";
    public static final String NAMESPACE_ALARM_MANAGER = "alarm_manager";
    public static final String NAMESPACE_APP_COMPAT = "app_compat";
    public static final String NAMESPACE_APP_HIBERNATION = "app_hibernation";
    public static final String NAMESPACE_APPSEARCH = "appsearch";
    public static final String NAMESPACE_APP_STANDBY = "app_standby";
    public static final String NAMESPACE_APP_CLONING = "app_cloning";
    public static final String NAMESPACE_ATTENTION_MANAGER_SERVICE = "attention_manager_service";
    public static final String NAMESPACE_AUTOFILL = "autofill";
    public static final String NAMESPACE_BATTERY_SAVER = "battery_saver";
    public static final String NAMESPACE_BATTERY_STATS = "battery_stats";
    public static final String NAMESPACE_BLOBSTORE = "blobstore";
    public static final String NAMESPACE_BLUETOOTH = "bluetooth";
    public static final String NAMESPACE_CAMERA_NATIVE = "camera_native";
    public static final String NAMESPACE_CELLULAR_SECURITY = "cellular_security";
    public static final String NAMESPACE_CLIPBOARD = "clipboard";
    public static final String NAMESPACE_CONNECTIVITY = "connectivity";
    public static final String NAMESPACE_CAPTIVEPORTALLOGIN = "captive_portal_login";
    public static final String NAMESPACE_EDGETPU_NATIVE = "edgetpu_native";
    public static final String NAMESPACE_HEALTH_FITNESS = "health_fitness";
    public static final String NAMESPACE_TETHERING = "tethering";
    public static final String NAMESPACE_NEARBY = "nearby";
    public static final String NAMESPACE_CONTENT_CAPTURE = "content_capture";
    public static final String NAMESPACE_CREDENTIAL = "credential_manager";
    public static final String NAMESPACE_DEVICE_IDLE = "device_idle";
    public static final String NAMESPACE_DEX_BOOT = "dex_boot";
    public static final String NAMESPACE_DISPLAY_MANAGER = "display_manager";
    public static final String NAMESPACE_GAME_DRIVER = "game_driver";
    public static final String NAMESPACE_HDMI_CONTROL = "hdmi_control";
    public static final String NAMESPACE_INPUT_NATIVE_BOOT = "input_native_boot";
    public static final String NAMESPACE_INTELLIGENCE_ATTENTION = "intelligence_attention";
    public static final String NAMESPACE_INTELLIGENCE_CONTENT_SUGGESTIONS = "intelligence_content_suggestions";
    public static final String NAMESPACE_JOB_SCHEDULER = "jobscheduler";
    public static final String NAMESPACE_LMKD_NATIVE = "lmkd_native";
    public static final String NAMESPACE_LOCATION = "location";
    public static final String NAMESPACE_MEDIA = "media";
    public static final String NAMESPACE_MEDIA_NATIVE = "media_native";
    public static final String NAMESPACE_MGLRU_NATIVE = "mglru_native";
    public static final String NAMESPACE_NETD_NATIVE = "netd_native";
    public static final String NAMESPACE_NNAPI_NATIVE = "nnapi_native";
    public static final String NAMESPACE_ON_DEVICE_PERSONALIZATION = "on_device_personalization";
    public static final String NAMESPACE_PACKAGE_MANAGER_SERVICE = "package_manager_service";
    public static final String NAMESPACE_PROFCOLLECT_NATIVE_BOOT = "profcollect_native_boot";
    public static final String NAMESPACE_REBOOT_READINESS = "reboot_readiness";
    public static final String NAMESPACE_REMOTE_KEY_PROVISIONING_NATIVE = "remote_key_provisioning_native";
    public static final String NAMESPACE_ROLLBACK = "rollback";
    public static final String NAMESPACE_ROLLBACK_BOOT = "rollback_boot";
    public static final String NAMESPACE_ROTATION_RESOLVER = "rotation_resolver";
    public static final String NAMESPACE_RUNTIME = "runtime";
    public static final String NAMESPACE_RUNTIME_NATIVE = "runtime_native";
    public static final String NAMESPACE_RUNTIME_NATIVE_BOOT = "runtime_native_boot";
    public static final String NAMESPACE_SCHEDULER = "scheduler";
    public static final String NAMESPACE_SDK_SANDBOX = "sdk_sandbox";
    public static final String NAMESPACE_SETTINGS_STATS = "settings_stats";
    public static final String NAMESPACE_STATSD_JAVA = "statsd_java";
    public static final String NAMESPACE_STATSD_JAVA_BOOT = "statsd_java_boot";
    public static final String NAMESPACE_STATSD_NATIVE = "statsd_native";
    public static final String NAMESPACE_STATSD_NATIVE_BOOT = "statsd_native_boot";
    public static final String NAMESPACE_STORAGE = "storage";
    public static final String NAMESPACE_STORAGE_NATIVE_BOOT = "storage_native_boot";
    public static final String NAMESPACE_ADSERVICES = "adservices";
    public static final String NAMESPACE_SURFACE_FLINGER_NATIVE_BOOT = "surface_flinger_native_boot";
    public static final String NAMESPACE_SWCODEC_NATIVE = "swcodec_native";
    public static final String NAMESPACE_SYSTEMUI = "systemui";
    public static final String NAMESPACE_SYSTEM_TIME = "system_time";
    public static final String NAMESPACE_TARE = "tare";
    public static final String NAMESPACE_TELEPHONY = "telephony";
    public static final String NAMESPACE_TEXTCLASSIFIER = "textclassifier";
    public static final String NAMESPACE_CONTACTS_PROVIDER = "contacts_provider";
    public static final String NAMESPACE_SETTINGS_UI = "settings_ui";
    public static final String NAMESPACE_ANDROID = "android";
    public static final String NAMESPACE_WINDOW_MANAGER = "window_manager";
    public static final String NAMESPACE_WINDOW_MANAGER_NATIVE_BOOT = "window_manager_native_boot";
    public static final String NAMESPACE_SELECTION_TOOLBAR = "selection_toolbar";
    public static final String NAMESPACE_VOICE_INTERACTION = "voice_interaction";
    public static final String NAMESPACE_DEVICE_POLICY_MANAGER = "device_policy_manager";
    public static final String NAMESPACE_PRIVACY = "privacy";
    public static final String NAMESPACE_BIOMETRICS = "biometrics";
    public static final String NAMESPACE_PERMISSIONS = "permissions";
    public static final String NAMESPACE_OTA = "ota";
    public static final String NAMESPACE_WIDGET = "widget";
    public static final String NAMESPACE_CONNECTIVITY_THERMAL_POWER_MANAGER = "connectivity_thermal_power_manager";
    public static final String NAMESPACE_CONFIGURATION = "configuration";
    public static final String NAMESPACE_LATENCY_TRACKER = "latency_tracker";
    public static final String NAMESPACE_INTERACTION_JANK_MONITOR = "interaction_jank_monitor";
    public static final String NAMESPACE_GAME_OVERLAY = "game_overlay";
    public static final String NAMESPACE_VIRTUALIZATION_FRAMEWORK_NATIVE = "virtualization_framework_native";
    public static final String NAMESPACE_CONSTRAIN_DISPLAY_APIS = "constrain_display_apis";
    public static final String NAMESPACE_APP_COMPAT_OVERRIDES = "app_compat_overrides";
    public static final String NAMESPACE_UWB = "uwb";
    public static final String NAMESPACE_AMBIENT_CONTEXT_MANAGER_SERVICE = "ambient_context_manager_service";
    public static final String NAMESPACE_WEARABLE_SENSING = "wearable_sensing";
    public static final String NAMESPACE_VENDOR_SYSTEM_NATIVE = "vendor_system_native";
    public static final String NAMESPACE_VENDOR_SYSTEM_NATIVE_BOOT = "vendor_system_native_boot";
    public static final String NAMESPACE_MEMORY_SAFETY_NATIVE_BOOT = "memory_safety_native_boot";
    public static final String NAMESPACE_MEMORY_SAFETY_NATIVE = "memory_safety_native";
    public static final String NAMESPACE_WEAR = "wear";
    public static final String NAMESPACE_INPUT_METHOD_MANAGER = "input_method_manager";
    public static final String NAMESPACE_BACKUP_AND_RESTORE = "backup_and_restore";
    public static final String NAMESPACE_ARC_APP_COMPAT = "arc_app_compat";
    public static final String NAMESPACE_REMOTE_AUTH = "remote_auth";

    public static final int SYNC_DISABLED_MODE_NONE = 0;
    public static final int SYNC_DISABLED_MODE_PERSISTENT = 1;
    public static final int SYNC_DISABLED_MODE_UNTIL_REBOOT = 2;

    private static final String NS = "config/";

    private static String key(String namespace, String name) {
        return Objects.requireNonNull(namespace) + "/" + Objects.requireNonNull(name);
    }

    public static String getProperty(String namespace, String name) {
        return getProperties(namespace, name).getString(name, null);
    }

    public static Properties getProperties(String namespace, String... names) {
        Map<String, String> m = new HashMap<>();
        for (String n : names) {
            String v = SettingsStore.getString(NS, key(namespace, n));
            if (v != null) m.put(n, v);
        }
        return new Properties(namespace, m);
    }

    public static String getString(String namespace, String name, String defaultValue) {
        String value = getProperty(namespace, name);
        return value != null ? value : defaultValue;
    }

    public static boolean getBoolean(String namespace, String name, boolean defaultValue) {
        String value = getProperty(namespace, name);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }

    public static int getInt(String namespace, String name, int defaultValue) {
        String value = getProperty(namespace, name);
        if (value == null) return defaultValue;
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { return defaultValue; }
    }

    public static long getLong(String namespace, String name, long defaultValue) {
        String value = getProperty(namespace, name);
        if (value == null) return defaultValue;
        try { return Long.parseLong(value); } catch (NumberFormatException e) { return defaultValue; }
    }

    public static float getFloat(String namespace, String name, float defaultValue) {
        String value = getProperty(namespace, name);
        if (value == null) return defaultValue;
        try { return Float.parseFloat(value); } catch (NumberFormatException e) { return defaultValue; }
    }

    /** AOSP requires WRITE_DEVICE_CONFIG (system / Google apps hold it). */
    public static boolean setProperty(String namespace, String name, String value, boolean makeDefault) {
        boolean ok = SettingsStore.putString(NS, key(namespace, name), value);
        if (ok) notifyChanged(namespace, Collections.singletonMap(name, value));
        return ok;
    }

    public static boolean setProperties(Properties properties) throws BadConfigException {
        Map<String, String> changed = new HashMap<>();
        for (String n : properties.getKeyset()) {
            String v = properties.getString(n, null);
            if (!SettingsStore.putString(NS, key(properties.getNamespace(), n), v)) return false;
            changed.put(n, v);
        }
        notifyChanged(properties.getNamespace(), changed);
        return true;
    }

    public static boolean deleteProperty(String namespace, String name) {
        boolean ok = SettingsStore.putString(NS, key(namespace, name), null);
        if (ok) notifyChanged(namespace, Collections.singletonMap(name, null));
        return ok;
    }

    public static void resetToDefaults(int resetMode, String namespace) {}
    public static void setSyncDisabledMode(int syncDisabledMode) {}
    public static int getSyncDisabledMode() { return SYNC_DISABLED_MODE_NONE; }
    public static List<String> getPublicNamespaces() { return new ArrayList<>(); }
    public static Set<String> getAdbWritableFlags() { return Collections.emptySet(); }

    // ---- listeners ---------------------------------------------------------------
    public interface OnPropertiesChangedListener {
        void onPropertiesChanged(Properties properties);
    }

    private static final class Reg {
        final String namespace; final Executor executor; final OnPropertiesChangedListener l;
        Reg(String n, Executor e, OnPropertiesChangedListener l) { namespace = n; executor = e; this.l = l; }
    }
    private static final List<Reg> sListeners = new ArrayList<>();

    public static void addOnPropertiesChangedListener(String namespace, Executor executor,
            OnPropertiesChangedListener onPropertiesChangedListener) {
        synchronized (sListeners) {
            removeOnPropertiesChangedListener(onPropertiesChangedListener);
            sListeners.add(new Reg(namespace, executor, onPropertiesChangedListener));
        }
    }

    public static void removeOnPropertiesChangedListener(
            OnPropertiesChangedListener onPropertiesChangedListener) {
        synchronized (sListeners) {
            sListeners.removeIf(r -> r.l == onPropertiesChangedListener);
        }
    }

    private static void notifyChanged(String namespace, Map<String, String> changed) {
        List<Reg> rs;
        synchronized (sListeners) { rs = new ArrayList<>(sListeners); }
        Properties p = new Properties(namespace, changed);
        for (Reg r : rs) {
            if (!r.namespace.equals(namespace)) continue;
            r.executor.execute(() -> r.l.onPropertiesChanged(p));
        }
    }

    public static class BadConfigException extends Exception {}

    /** AOSP DeviceConfig.Properties, verbatim semantics. */
    public static class Properties {
        private final String mNamespace;
        private final HashMap<String, String> mMap;
        private Set<String> mKeyset;

        public Properties(String namespace, Map<String, String> keyValueMap) {
            Objects.requireNonNull(namespace);
            mNamespace = namespace;
            mMap = new HashMap<>();
            if (keyValueMap != null) mMap.putAll(keyValueMap);
        }
        public String getNamespace() { return mNamespace; }
        public Set<String> getKeyset() {
            if (mKeyset == null) mKeyset = Collections.unmodifiableSet(mMap.keySet());
            return mKeyset;
        }
        public String getString(String name, String defaultValue) {
            Objects.requireNonNull(name);
            String value = mMap.get(name);
            return value != null ? value : defaultValue;
        }
        public boolean getBoolean(String name, boolean defaultValue) {
            Objects.requireNonNull(name);
            String value = mMap.get(name);
            return value != null ? Boolean.parseBoolean(value) : defaultValue;
        }
        public int getInt(String name, int defaultValue) {
            String value = mMap.get(Objects.requireNonNull(name));
            if (value == null) return defaultValue;
            try { return Integer.parseInt(value); } catch (NumberFormatException e) { return defaultValue; }
        }
        public long getLong(String name, long defaultValue) {
            String value = mMap.get(Objects.requireNonNull(name));
            if (value == null) return defaultValue;
            try { return Long.parseLong(value); } catch (NumberFormatException e) { return defaultValue; }
        }
        public float getFloat(String name, float defaultValue) {
            String value = mMap.get(Objects.requireNonNull(name));
            if (value == null) return defaultValue;
            try { return Float.parseFloat(value); } catch (NumberFormatException e) { return defaultValue; }
        }

        public static final class Builder {
            private final String mNamespace;
            private final Map<String, String> mKeyValues = new HashMap<>();
            public Builder(String namespace) { mNamespace = namespace; }
            public Builder setString(String name, String value) { mKeyValues.put(name, value); return this; }
            public Builder setBoolean(String name, boolean value) { mKeyValues.put(name, Boolean.toString(value)); return this; }
            public Builder setInt(String name, int value) { mKeyValues.put(name, Integer.toString(value)); return this; }
            public Builder setLong(String name, long value) { mKeyValues.put(name, Long.toString(value)); return this; }
            public Builder setFloat(String name, float value) { mKeyValues.put(name, Float.toString(value)); return this; }
            public Properties build() { return new Properties(mNamespace, mKeyValues); }
        }
    }
}
