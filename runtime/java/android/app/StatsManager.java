// android.app.StatsManager -- AOSP @SystemApi (frameworks/base core/java/android/
// app/StatsManager.java): the client of the statsd metrics daemon. There is no
// statsd here, so every call throws StatsUnavailableException, which is exactly
// what AOSP throws when statsd cannot be reached -- callers (Google Play
// services' AnomalyConfigIntentOperation) catch it. Missing entirely, the class
// was a NoClassDefFoundError on GMS's intent-operation executor.
package android.app;

import android.util.AndroidException;

public final class StatsManager {
    public static final String ACTION_STATSD_STARTED = "android.app.action.STATSD_STARTED";
    public static final String EXTRA_STATS_CONFIG_UID = "android.app.extra.STATS_CONFIG_UID";
    public static final String EXTRA_STATS_CONFIG_KEY = "android.app.extra.STATS_CONFIG_KEY";
    public static final String EXTRA_STATS_SUBSCRIPTION_ID = "android.app.extra.STATS_SUBSCRIPTION_ID";
    public static final String EXTRA_STATS_SUBSCRIPTION_RULE_ID = "android.app.extra.STATS_SUBSCRIPTION_RULE_ID";
    public static final String EXTRA_STATS_ACTIVE_CONFIG_KEYS = "android.app.extra.STATS_ACTIVE_CONFIG_KEYS";
    public static final String EXTRA_STATS_BROADCAST_SUBSCRIBER_COOKIES =
            "android.app.extra.STATS_BROADCAST_SUBSCRIBER_COOKIES";
    public static final String EXTRA_STATS_DIMENSIONS_VALUE = "android.app.extra.STATS_DIMENSIONS_VALUE";

    public static class StatsUnavailableException extends AndroidException {
        public StatsUnavailableException(String reason) { super("Failed to connect to statsd: " + reason); }
        public StatsUnavailableException(String reason, Throwable e) {
            super("Failed to connect to statsd: " + reason, e);
        }
    }

    StatsManager() {}

    private static StatsUnavailableException none() {
        return new StatsUnavailableException("statsd is not available on this device");
    }

    public void addConfig(long configKey, byte[] config) throws StatsUnavailableException { throw none(); }
    public void removeConfig(long configKey) throws StatsUnavailableException { throw none(); }
    public byte[] getReports(long configKey) throws StatsUnavailableException { throw none(); }
    public byte[] getStatsMetadata() throws StatsUnavailableException { throw none(); }
    public long[] getRegisteredExperimentIds() throws StatsUnavailableException { throw none(); }
    public void setBroadcastSubscriber(PendingIntent pendingIntent, long configKey, long subscriberId)
            throws StatsUnavailableException { throw none(); }
    public void setFetchReportsOperation(PendingIntent pendingIntent, long configKey)
            throws StatsUnavailableException { throw none(); }
    public long[] setActiveConfigsChangedOperation(PendingIntent pendingIntent)
            throws StatsUnavailableException { throw none(); }
}
