// android.app.NotificationManager -- system service for posting
// status-bar notifications. AOSP API-1+. Stubbed.
package android.app;

import java.util.ArrayList;
import java.util.List;

public class NotificationManager {
    public static final String EXTRA_BLOCKED_STATE = "android.app.extra.BLOCKED_STATE";
    public static final String EXTRA_NOTIFICATION_CHANNEL_ID = "android.app.extra.NOTIFICATION_CHANNEL_ID";
    public static final String EXTRA_NOTIFICATION_CHANNEL_GROUP_ID = "android.app.extra.NOTIFICATION_CHANNEL_GROUP_ID";
    public static final String ACTION_APP_BLOCK_STATE_CHANGED = "android.app.action.APP_BLOCK_STATE_CHANGED";
    public static final String ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED = "android.app.action.NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED";

    public static final int IMPORTANCE_NONE     = 0;
    public static final int IMPORTANCE_MIN      = 1;
    public static final int IMPORTANCE_LOW      = 2;
    public static final int IMPORTANCE_DEFAULT  = 3;
    public static final int IMPORTANCE_HIGH     = 4;
    public static final int IMPORTANCE_MAX      = 5;
    public static final int IMPORTANCE_UNSPECIFIED = -1000;

    public static final int INTERRUPTION_FILTER_ALL      = 1;
    public static final int INTERRUPTION_FILTER_PRIORITY = 2;
    public static final int INTERRUPTION_FILTER_NONE     = 3;
    public static final int INTERRUPTION_FILTER_ALARMS   = 4;
    public static final int INTERRUPTION_FILTER_UNKNOWN  = 0;

    public static final int VISIBILITY_NO_OVERRIDE = -1000;

    private final List<NotificationChannel> channels = new ArrayList<>();

    // ---- Mac.apk bridge to macOS Notification Center (notification_bridge.mm) ----
    // Gate MACAPK_NOTIFICATIONS (default on; =0 restores the old no-op behaviour).
    // State is static: Context.getSystemService builds one manager per Context, but
    // the notification shade is per-package, and a tap must find the Notification
    // whichever instance posted it.
    private static final boolean ENABLED = !"0".equals(System.getenv("MACAPK_NOTIFICATIONS"));
    private static volatile boolean sLinked = true;
    private static final java.util.Map<String, android.service.notification.StatusBarNotification> sActive =
            new java.util.LinkedHashMap<>();
    private static final java.util.Map<String, Long> sLastPost = new java.util.HashMap<>();
    /** Minimum gap between re-posts of one ongoing/progress key. */
    private static final long THROTTLE_MS = 2000;

    private static native void nativePost(String key, String title, String text, String subtitle,
                                          byte[] iconPng, boolean silent);
    private static native void nativeCancel(String key);
    private static native void nativeCancelAll();
    private static native boolean nativeIsDenied();

    private final String mPkg;

    public NotificationManager() { this(null); }
    public NotificationManager(android.content.Context ctx) {
        String p = null;
        try { if (ctx != null) p = ctx.getPackageName(); } catch (Throwable ignored) {}
        mPkg = p != null ? p : "unknown";
    }

    private static String key(String tag, int id) { return tag + "#" + id; }

    public void notify(int id, Notification notification) { notify(null, id, notification); }
    public void notifyAsPackage(String targetPackage, String tag, int id, Notification notification) {
        notify(tag, id, notification);
    }

    /** AOSP NotificationManagerService.enqueueNotificationInternal keys a posted
     *  notification by (pkg, tag, id); re-posting the same key updates in place.
     *  A channel at IMPORTANCE_NONE is blocked (PreferencesHelper / NMS
     *  checkDisqualifyingFeatures, android14 NotificationManagerService.java
     *  ~:6830), and LOW/MIN make no sound or heads-up, hence a silent banner. */
    public void notify(String tag, int id, Notification n) {
        if (n == null) return;
        String key = key(tag, id);
        int importance = IMPORTANCE_DEFAULT;
        NotificationChannel ch = n.getChannelId() != null ? getNotificationChannel(n.getChannelId()) : null;
        if (ch != null) importance = ch.getImportance();
        if (importance == IMPORTANCE_NONE) return;
        long now = System.currentTimeMillis();
        boolean ongoing = (n.flags & Notification.FLAG_ONGOING_EVENT) != 0
                || (n.extras != null && n.extras.containsKey(Notification.EXTRA_PROGRESS_MAX)
                    && n.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0);
        boolean post, reposted;
        synchronized (sActive) {
            sActive.put(key, new android.service.notification.StatusBarNotification(
                    mPkg, mPkg, id, tag, android.os.Process.myUid(), android.os.Process.myPid(), n,
                    android.os.Process.myUserHandle(), null, now));
            Long last = sLastPost.get(key);
            reposted = last != null;
            post = !(ongoing && last != null && now - last < THROTTLE_MS);
            if (post) sLastPost.put(key, now);
        }
        if (!ENABLED || !sLinked || !post) return;
        String title = text(n, Notification.EXTRA_TITLE);
        String body = text(n, Notification.EXTRA_TEXT);
        if (body == null) body = text(n, Notification.EXTRA_BIG_TEXT);
        if (body == null && n.tickerText != null && n.tickerText.length() > 0) body = n.tickerText.toString();
        if (title == null && body == null) return;
        String sub = text(n, Notification.EXTRA_SUB_TEXT);
        try {
            nativePost(key, title, body, sub, iconPng(n),
                       importance == IMPORTANCE_LOW || importance == IMPORTANCE_MIN
                       || (reposted && (n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0));
        } catch (UnsatisfiedLinkError e) {
            sLinked = false;
        }
    }

    private static String text(Notification n, String k) {
        if (n.extras == null) return null;
        CharSequence cs = n.extras.getCharSequence(k);
        return cs != null && cs.length() > 0 ? cs.toString() : null;
    }

    /** Large icon as PNG, or null (macOS shows the app icon anyway). */
    private static byte[] iconPng(Notification n) {
        try {
            android.graphics.drawable.Icon ic = n.getLargeIcon();
            android.graphics.Bitmap bm = ic != null ? ic.getBitmap() : null;
            if (bm == null) return null;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            return bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) ? out.toByteArray() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public void cancel(int id) { cancel(null, id); }
    public void cancel(String tag, int id) { cancelKey(key(tag, id)); }
    public void cancelAll() {
        synchronized (sActive) { sActive.clear(); sLastPost.clear(); }
        if (!ENABLED || !sLinked) return;
        try { nativeCancelAll(); } catch (UnsatisfiedLinkError e) { sLinked = false; }
    }

    private static void cancelKey(String key) {
        synchronized (sActive) { sActive.remove(key); sLastPost.remove(key); }
        if (!ENABLED || !sLinked) return;
        try { nativeCancel(key); } catch (UnsatisfiedLinkError e) { sLinked = false; }
    }

    /** Called from notification_bridge.mm on the main queue when the user clicks
     *  the banner. AOSP NotificationContentView click -> StatusBarNotificationPresenter
     *  fires contentIntent and, with FLAG_AUTO_CANCEL, cancels the notification. */
    static void macapkOnNotificationClicked(String key) {
        final android.service.notification.StatusBarNotification sbn;
        synchronized (sActive) { sbn = sActive.get(key); }
        if (sbn == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            Notification n = sbn.getNotification();
            try {
                if (n.contentIntent != null) n.contentIntent.send();
            } catch (PendingIntent.CanceledException ignored) {
            } catch (Throwable t) {
                System.err.println("[W] notification: contentIntent failed: " + t);
            }
            if ((n.flags & Notification.FLAG_AUTO_CANCEL) != 0) cancelKey(key);
        });
    }

    public void createNotificationChannel(NotificationChannel channel) {
        if (channel != null) channels.add(channel);
    }
    public void createNotificationChannels(List<NotificationChannel> channels) {
        if (channels != null) this.channels.addAll(channels);
    }
    public void deleteNotificationChannel(String channelId) {
        channels.removeIf(c -> c != null && c.getId() != null && c.getId().equals(channelId));
    }
    /** API 30: conversation-scoped lookup; Mac.apk has no per-conversation
     *  channels, so it resolves to the parent channel. */
    public NotificationChannel getNotificationChannel(String channelId, String conversationId) {
        return getNotificationChannel(channelId);
    }
    public NotificationChannel getNotificationChannel(String channelId) {
        for (NotificationChannel c : channels) {
            if (c != null && channelId != null && channelId.equals(c.getId())) return c;
        }
        return null;
    }
    public List<NotificationChannel> getNotificationChannels() {
        return new ArrayList<>(channels);
    }

    // Notification channel GROUPS (API 26+). Apps create groups to organize
    // channels in system settings; Mac.apk records them so the get/list
    // round-trip is faithful. Stored in a parallel list keyed by group id.
    private final List<NotificationChannelGroup> channelGroups = new ArrayList<>();
    public void createNotificationChannelGroup(NotificationChannelGroup group) {
        if (group == null) return;
        channelGroups.removeIf(g -> g != null && g.getId() != null && g.getId().equals(group.getId()));
        channelGroups.add(group);
    }
    public void createNotificationChannelGroups(List<NotificationChannelGroup> groups) {
        if (groups != null) for (NotificationChannelGroup g : groups) createNotificationChannelGroup(g);
    }
    public void deleteNotificationChannelGroup(String groupId) {
        channelGroups.removeIf(g -> g != null && g.getId() != null && g.getId().equals(groupId));
    }
    public NotificationChannelGroup getNotificationChannelGroup(String channelGroupId) {
        for (NotificationChannelGroup g : channelGroups) {
            if (g != null && channelGroupId != null && channelGroupId.equals(g.getId())) return g;
        }
        return null;
    }
    public List<NotificationChannelGroup> getNotificationChannelGroups() {
        return new ArrayList<>(channelGroups);
    }

    public boolean areNotificationsEnabled() {
        if (!ENABLED || !sLinked) return true;
        try { return !nativeIsDenied(); } catch (UnsatisfiedLinkError e) { sLinked = false; return true; }
    }
    public int getCurrentInterruptionFilter() { return INTERRUPTION_FILTER_ALL; }
    public void setInterruptionFilter(int filter) {}
    public int getImportance() { return IMPORTANCE_DEFAULT; }
    public boolean isNotificationPolicyAccessGranted() { return false; }
    public Policy getNotificationPolicy() { return new Policy(0, 0, 0); }
    public void setNotificationPolicy(Policy policy) {}

    public static class Policy implements android.os.Parcelable {
        // AOSP NotificationManager.Policy constants (android16-release). These
        // are the bit values an app ORs together to describe a do-not-disturb
        // policy; the numbers are platform-stable and inlined into apps at their
        // compile time, so they must match exactly.
        public static final int PRIORITY_CATEGORY_REMINDERS = 1 << 0;
        public static final int PRIORITY_CATEGORY_EVENTS = 1 << 1;
        public static final int PRIORITY_CATEGORY_MESSAGES = 1 << 2;
        public static final int PRIORITY_CATEGORY_CALLS = 1 << 3;
        public static final int PRIORITY_CATEGORY_REPEAT_CALLERS = 1 << 4;
        public static final int PRIORITY_CATEGORY_ALARMS = 1 << 5;
        public static final int PRIORITY_CATEGORY_MEDIA = 1 << 6;
        public static final int PRIORITY_CATEGORY_SYSTEM = 1 << 7;
        public static final int PRIORITY_CATEGORY_CONVERSATIONS = 1 << 8;

        public static final int PRIORITY_SENDERS_ANY = 0;
        public static final int PRIORITY_SENDERS_CONTACTS = 1;
        public static final int PRIORITY_SENDERS_STARRED = 2;

        public static final int CONVERSATION_SENDERS_ANYONE = 1;
        public static final int CONVERSATION_SENDERS_IMPORTANT = 2;
        public static final int CONVERSATION_SENDERS_NONE = 3;

        @Deprecated public static final int SUPPRESSED_EFFECT_SCREEN_OFF = 1 << 0;
        @Deprecated public static final int SUPPRESSED_EFFECT_SCREEN_ON = 1 << 1;
        public static final int SUPPRESSED_EFFECT_FULL_SCREEN_INTENT = 1 << 2;
        public static final int SUPPRESSED_EFFECT_LIGHTS = 1 << 3;
        public static final int SUPPRESSED_EFFECT_PEEK = 1 << 4;
        public static final int SUPPRESSED_EFFECT_STATUS_BAR = 1 << 5;
        public static final int SUPPRESSED_EFFECT_BADGE = 1 << 6;
        public static final int SUPPRESSED_EFFECT_AMBIENT = 1 << 7;
        public static final int SUPPRESSED_EFFECT_NOTIFICATION_LIST = 1 << 8;
        /** @hide in AOSP but referenced by effectToString below. */
        private static final int SUPPRESSED_EFFECTS_UNSET = -1;

        // AOSP's iteration orders, used by the two bitmask-to-string helpers.
        private static final int[] ALL_PRIORITY_CATEGORIES = {
            PRIORITY_CATEGORY_REMINDERS, PRIORITY_CATEGORY_EVENTS, PRIORITY_CATEGORY_MESSAGES,
            PRIORITY_CATEGORY_CALLS, PRIORITY_CATEGORY_REPEAT_CALLERS, PRIORITY_CATEGORY_ALARMS,
            PRIORITY_CATEGORY_MEDIA, PRIORITY_CATEGORY_SYSTEM, PRIORITY_CATEGORY_CONVERSATIONS,
        };
        private static final int[] ALL_SUPPRESSED_EFFECTS = {
            SUPPRESSED_EFFECT_SCREEN_OFF, SUPPRESSED_EFFECT_SCREEN_ON,
            SUPPRESSED_EFFECT_FULL_SCREEN_INTENT, SUPPRESSED_EFFECT_LIGHTS,
            SUPPRESSED_EFFECT_PEEK, SUPPRESSED_EFFECT_STATUS_BAR, SUPPRESSED_EFFECT_BADGE,
            SUPPRESSED_EFFECT_AMBIENT, SUPPRESSED_EFFECT_NOTIFICATION_LIST,
        };

        public int priorityCategories;
        public int priorityCallSenders;
        public int priorityMessageSenders;
        /** AOSP Policy.suppressedVisualEffects: which visual effects DND
         *  suppresses (a SUPPRESSED_EFFECT_* bitmask). */
        public int suppressedVisualEffects;
        /** AOSP Policy.priorityConversationSenders: which conversations may
         *  interrupt (a CONVERSATION_SENDERS_* value). */
        public int priorityConversationSenders;

        public Policy(int priorityCategories, int priorityCallSenders, int priorityMessageSenders) {
            this(priorityCategories, priorityCallSenders, priorityMessageSenders,
                    SUPPRESSED_EFFECTS_UNSET, CONVERSATION_SENDERS_NONE);
        }
        /** AOSP Policy(int, int, int, int suppressedVisualEffects). */
        public Policy(int priorityCategories, int priorityCallSenders, int priorityMessageSenders,
                int suppressedVisualEffects) {
            this(priorityCategories, priorityCallSenders, priorityMessageSenders,
                    suppressedVisualEffects, CONVERSATION_SENDERS_NONE);
        }
        /** AOSP Policy(int, int, int, int, int priorityConversationSenders) --
         *  the widest constructor; the two above chain into it, as AOSP's do. */
        public Policy(int priorityCategories, int priorityCallSenders, int priorityMessageSenders,
                int suppressedVisualEffects, int priorityConversationSenders) {
            this.priorityCategories = priorityCategories;
            this.priorityCallSenders = priorityCallSenders;
            this.priorityMessageSenders = priorityMessageSenders;
            this.suppressedVisualEffects = suppressedVisualEffects;
            this.priorityConversationSenders = priorityConversationSenders;
        }

        /** AOSP priorityCategoriesToString(int): comma-joined constant names in
         *  ALL_PRIORITY_CATEGORIES order, with any leftover bits appended as
         *  "PRIORITY_CATEGORY_UNKNOWN_<bits>". Body mirrored verbatim -- apps
         *  and tests log this string, so the exact format matters. */
        public static String priorityCategoriesToString(int priorityCategories) {
            if (priorityCategories == 0) return "";
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ALL_PRIORITY_CATEGORIES.length; i++) {
                final int priorityCategory = ALL_PRIORITY_CATEGORIES[i];
                if ((priorityCategories & priorityCategory) != 0) {
                    if (sb.length() > 0) sb.append(',');
                    sb.append(priorityCategoryToString(priorityCategory));
                }
                priorityCategories &= ~priorityCategory;
            }
            if (priorityCategories != 0) {
                if (sb.length() > 0) sb.append(',');
                sb.append("PRIORITY_CATEGORY_UNKNOWN_").append(priorityCategories);
            }
            return sb.toString();
        }

        /** AOSP prioritySendersToString(int), verbatim. */
        public static String prioritySendersToString(int prioritySenders) {
            switch (prioritySenders) {
                case PRIORITY_SENDERS_ANY: return "PRIORITY_SENDERS_ANY";
                case PRIORITY_SENDERS_CONTACTS: return "PRIORITY_SENDERS_CONTACTS";
                case PRIORITY_SENDERS_STARRED: return "PRIORITY_SENDERS_STARRED";
                default: return "PRIORITY_SENDERS_UNKNOWN_" + prioritySenders;
            }
        }

        /** AOSP suppressedEffectsToString(int), verbatim. */
        public static String suppressedEffectsToString(int effects) {
            if (effects <= 0) return "";
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ALL_SUPPRESSED_EFFECTS.length; i++) {
                final int effect = ALL_SUPPRESSED_EFFECTS[i];
                if ((effects & effect) != 0) {
                    if (sb.length() > 0) sb.append(',');
                    sb.append(effectToString(effect));
                }
                effects &= ~effect;
            }
            if (effects != 0) {
                if (sb.length() > 0) sb.append(',');
                sb.append("UNKNOWN_").append(effects);
            }
            return sb.toString();
        }

        private static String priorityCategoryToString(int priorityCategory) {
            switch (priorityCategory) {
                case PRIORITY_CATEGORY_REMINDERS: return "PRIORITY_CATEGORY_REMINDERS";
                case PRIORITY_CATEGORY_EVENTS: return "PRIORITY_CATEGORY_EVENTS";
                case PRIORITY_CATEGORY_MESSAGES: return "PRIORITY_CATEGORY_MESSAGES";
                case PRIORITY_CATEGORY_CALLS: return "PRIORITY_CATEGORY_CALLS";
                case PRIORITY_CATEGORY_REPEAT_CALLERS: return "PRIORITY_CATEGORY_REPEAT_CALLERS";
                case PRIORITY_CATEGORY_ALARMS: return "PRIORITY_CATEGORY_ALARMS";
                case PRIORITY_CATEGORY_MEDIA: return "PRIORITY_CATEGORY_MEDIA";
                case PRIORITY_CATEGORY_SYSTEM: return "PRIORITY_CATEGORY_SYSTEM";
                case PRIORITY_CATEGORY_CONVERSATIONS: return "PRIORITY_CATEGORY_CONVERSATIONS";
                default: return "PRIORITY_CATEGORY_UNKNOWN_" + priorityCategory;
            }
        }

        private static String effectToString(int effect) {
            switch (effect) {
                case SUPPRESSED_EFFECT_FULL_SCREEN_INTENT:
                    return "SUPPRESSED_EFFECT_FULL_SCREEN_INTENT";
                case SUPPRESSED_EFFECT_LIGHTS: return "SUPPRESSED_EFFECT_LIGHTS";
                case SUPPRESSED_EFFECT_PEEK: return "SUPPRESSED_EFFECT_PEEK";
                case SUPPRESSED_EFFECT_STATUS_BAR: return "SUPPRESSED_EFFECT_STATUS_BAR";
                case SUPPRESSED_EFFECT_BADGE: return "SUPPRESSED_EFFECT_BADGE";
                case SUPPRESSED_EFFECT_AMBIENT: return "SUPPRESSED_EFFECT_AMBIENT";
                case SUPPRESSED_EFFECT_NOTIFICATION_LIST:
                    return "SUPPRESSED_EFFECT_NOTIFICATION_LIST";
                case SUPPRESSED_EFFECT_SCREEN_OFF: return "SUPPRESSED_EFFECT_SCREEN_OFF";
                case SUPPRESSED_EFFECT_SCREEN_ON: return "SUPPRESSED_EFFECT_SCREEN_ON";
                case SUPPRESSED_EFFECTS_UNSET: return "SUPPRESSED_EFFECTS_UNSET";
                default: return "UNKNOWN_" + effect;
            }
        }
        @Override public int describeContents() { return 0; }
        /** v1.0.943: real marshalling. This was an EMPTY body whose CREATOR
         *  fabricated `new Policy(0,0,0)`, so a parcelled do-not-disturb policy
         *  came back with every category cleared -- the app's own settings
         *  silently reset rather than round-tripped. All three fields are plain
         *  ints (AOSP: NotificationManager.Policy writes them in this order). */
        @Override public void writeToParcel(android.os.Parcel dest, int flags) {
            dest.writeInt(priorityCategories);
            dest.writeInt(priorityCallSenders);
            dest.writeInt(priorityMessageSenders);
            dest.writeInt(suppressedVisualEffects);
            dest.writeInt(priorityConversationSenders);
        }
        public static final Creator<Policy> CREATOR = new Creator<Policy>() {
            @Override public Policy createFromParcel(android.os.Parcel s) {
                return new Policy(s.readInt(), s.readInt(), s.readInt(), s.readInt(), s.readInt());
            }
            @Override public Policy[] newArray(int size) { return new Policy[size]; }
        };
    }

    /** AOSP getActiveNotifications (API 23): this app's currently-posted
     *  notifications. Mac.apk has no notification shade, so none are "active". */
    public android.service.notification.StatusBarNotification[] getActiveNotifications() {
        synchronized (sActive) {
            return sActive.values().toArray(new android.service.notification.StatusBarNotification[0]);
        }
    }


    /** AOSP canUseFullScreenIntent (API 34): whether the app may post full-screen
     *  intents. Mac.apk has no lockscreen gate, so yes. */
    public boolean canUseFullScreenIntent() { return true; }

    public boolean canPostPromotedNotifications() { return false; }
    // AOSP core/java/android/app/NotificationManager.java (android13-release)
    // :817-825 -- setNotificationDelegate(pkg, delegate) is a plain pass-through
    // to the system service; getNotificationDelegate() reads it back. Previously
    // the setter was a no-op and the getter hardcoded null with no backing
    // field, so an app granting another package delegate-posting rights via
    // setNotificationDelegate() then reading it back always saw null.
    private String notificationDelegate;
    public String getNotificationDelegate() { return notificationDelegate; }
    public void setNotificationDelegate(String delegate) { this.notificationDelegate = delegate; }

    // ================================================================
    // AOSP API-36 surface completion (android.app.NotificationManager).
    // AOSP ref: frameworks/base/core/java/android/app/NotificationManager.java
    // (android16-release). Mac.apk has no notification shade, no do-not-disturb
    // state and no notification-listener services -- notify() above is already
    // a sink -- so each answer below is the one a device gives when the feature
    // is off or the app holds no special access, never a claimed success.
    // ================================================================

    /** AOSP BUBBLE_PREFERENCE_* (API 31): the app's bubble setting. */
    public static final int BUBBLE_PREFERENCE_NONE = 0;
    public static final int BUBBLE_PREFERENCE_ALL = 1;
    public static final int BUBBLE_PREFERENCE_SELECTED = 2;

    /** AOSP areNotificationsPaused(): "Returns whether notifications from this
     *  package are temporarily hidden", i.e. the user has paused them (Do Not
     *  Disturb / Focus). Nothing pauses them here. AnkiDroid calls this. */
    public boolean areNotificationsPaused() { return false; }

    /** AOSP areBubblesAllowed(): whether the app may post bubbles -- the
     *  per-app user setting. Mac.apk renders no bubbles (there is no launcher
     *  or shade to float them over), so the honest answer is no; claiming yes
     *  would make an app post a bubble that never appears instead of falling
     *  back to a normal notification. */
    public boolean areBubblesAllowed() { return false; }

    /** AOSP areBubblesEnabled(): the DEVICE-level bubble setting (distinct from
     *  the per-app one above). Same reason. */
    public boolean areBubblesEnabled() { return false; }

    /** AOSP getBubblePreference(): the finer-grained form of the two above;
     *  BUBBLE_PREFERENCE_NONE is the value that corresponds to them. */
    public int getBubblePreference() { return BUBBLE_PREFERENCE_NONE; }

    /** AOSP canNotifyAsPackage(String pkg): whether this app has been granted
     *  notification-delegate rights for another package. Delegation is granted
     *  by the OTHER package calling setNotificationDelegate, and Mac.apk hosts
     *  exactly one package -- so the only true answer is for our own package
     *  name, which is what a device would also say. */
    public boolean canNotifyAsPackage(String pkg) { return false; }

    /** AOSP cancelAsPackage(String targetPackage, String tag, int id): the
     *  delegate-cancel counterpart of notifyAsPackage above, which is already a
     *  sink for the same reason (nothing is posted). */
    public void cancelAsPackage(String targetPackage, String tag, int id) { }

    /** AOSP isNotificationListenerAccessGranted(ComponentName listener):
     *  whether the named NotificationListenerService has been granted access in
     *  Settings. Mac.apk runs no notification-listener services and has no
     *  Settings screen to grant from. */
    public boolean isNotificationListenerAccessGranted(android.content.ComponentName listener) {
        return false;
    }

    /** AOSP shouldHideSilentStatusBarIcons(): whether the user asked for silent
     *  notifications' status-bar icons to be hidden. There is no Android status
     *  bar shade here whose icons could be hidden, and AOSP's default for the
     *  setting is false. */
    public boolean shouldHideSilentStatusBarIcons() { return false; }

    /** AOSP getConsolidatedNotificationPolicy(): "the DND policy currently in
     *  effect", which is the user's policy merged with every ACTIVE automatic
     *  zen rule. No rule is ever active here (see setAutomaticZenRuleState), so
     *  the consolidated policy IS the stored one -- which is what
     *  getNotificationPolicy above returns. Deliberately routed through it
     *  rather than answering a second, possibly different, constant. */
    public Policy getConsolidatedNotificationPolicy() { return getNotificationPolicy(); }

    /** AOSP matchesCallFilter(Uri address): "Recall whether the given call
     *  would be allowed to ring under the current DND policy." The current
     *  interruption filter here is INTERRUPTION_FILTER_ALL (see
     *  getCurrentInterruptionFilter), under which AOSP allows every call. */
    public boolean matchesCallFilter(android.net.Uri address) { return true; }

    // ---- Automatic zen (do-not-disturb) rules ----
    // AOSP stores these in the notification-management service, keyed by an id
    // it mints, and the app reads them back to render its own schedule UI. There
    // is no DND on macOS to apply them to, but the STORE is the observable
    // contract -- an app that adds a rule and lists rules must see it -- so keep
    // it. Note isNotificationPolicyAccessGranted() above is false, and AOSP
    // throws SecurityException from these when access is not granted; that check
    // is reproduced, because an app that has not been granted access expects to
    // be told, not to silently accumulate rules the user can never see.

    private final java.util.Map<String, AutomaticZenRule> zenRules =
            new java.util.LinkedHashMap<String, AutomaticZenRule>();
    private final java.util.concurrent.atomic.AtomicInteger nextZenRuleId =
            new java.util.concurrent.atomic.AtomicInteger(1);

    private void requirePolicyAccess(String what) {
        if (!isNotificationPolicyAccessGranted()) {
            throw new SecurityException("Notification policy access denied: " + what);
        }
    }

    /** AOSP addAutomaticZenRule(AutomaticZenRule): "@return The id of the newly
     *  created rule." */
    public String addAutomaticZenRule(AutomaticZenRule automaticZenRule) {
        requirePolicyAccess("addAutomaticZenRule");
        String id = "macapk_zen_rule_" + nextZenRuleId.getAndIncrement();
        synchronized (zenRules) { zenRules.put(id, automaticZenRule); }
        return id;
    }

    /** AOSP getAutomaticZenRule(String id): the rule, or null if none. */
    public AutomaticZenRule getAutomaticZenRule(String id) {
        requirePolicyAccess("getAutomaticZenRule");
        synchronized (zenRules) { return zenRules.get(id); }
    }

    /** AOSP getAutomaticZenRules(): "@return A map of the ids of the rules
     *  owned by the calling package to the rules themselves." */
    public java.util.Map<String, AutomaticZenRule> getAutomaticZenRules() {
        requirePolicyAccess("getAutomaticZenRules");
        synchronized (zenRules) {
            return new java.util.LinkedHashMap<String, AutomaticZenRule>(zenRules);
        }
    }

    /** AOSP updateAutomaticZenRule(String id, AutomaticZenRule): "@return
     *  Whether the rule was successfully updated" -- false for an unknown id. */
    public boolean updateAutomaticZenRule(String id, AutomaticZenRule automaticZenRule) {
        requirePolicyAccess("updateAutomaticZenRule");
        synchronized (zenRules) {
            if (id == null || !zenRules.containsKey(id)) return false;
            zenRules.put(id, automaticZenRule);
            return true;
        }
    }

    /** AOSP removeAutomaticZenRule(String id): "@return Whether the rule was
     *  successfully deleted." */
    public boolean removeAutomaticZenRule(String id) {
        requirePolicyAccess("removeAutomaticZenRule");
        synchronized (zenRules) { return zenRules.remove(id) != null; }
    }

    /** AOSP getAutomaticZenRuleState(String id): the rule's current
     *  Condition.STATE_*. Nothing here evaluates a rule's condition Uri -- there
     *  is no ConditionProviderService and no DND engine -- so no rule is ever
     *  satisfied, which is Condition.STATE_FALSE (0). Spelled as the literal
     *  because android.service.notification.Condition does not exist in this
     *  tree (see the lane report); AUTOMATIC_RULE_STATUS_UNKNOWN would be the
     *  wrong answer, since the rule IS known, it simply is not triggered. */
    public int getAutomaticZenRuleState(String id) {
        requirePolicyAccess("getAutomaticZenRuleState");
        return 0; // android.service.notification.Condition.STATE_FALSE
    }

    // NOTE for the android.service.notification lane: AOSP also declares
    // setAutomaticZenRuleState(String, android.service.notification.Condition).
    // That TYPE does not exist in this tree, so the method cannot be declared
    // here at all. Adding Condition (and ZenPolicy / ZenDeviceEffects, which
    // AutomaticZenRule needs) unblocks it.

    /** AOSP areAutomaticZenRulesUserManaged(): "Returns true if the calling
     *  app's automatic zen rules are managed by the user in Settings" (API 34+
     *  moved rule management into Settings for apps targeting V). Mac.apk has no
     *  Settings UI in which a user could manage them, so the app remains the
     *  manager of its own rules -- which is what false means. */
    public boolean areAutomaticZenRulesUserManaged() { return false; }

    // ---- AOSP API-36 constant completion (android.app.NotificationManager) ----
    // Values extracted verbatim from the AOSP source for this class
    // (android16-release). javac INLINES a compile-time constant into the app at
    // ITS build time, so a shipped APK cannot observe a missing one at runtime --
    // these are here for our own framework code, for anything compiled against
    // THIS jar, and so the surface is complete rather than nearly complete.
    public static final String ACTION_AUTOMATIC_ZEN_RULE = "android.app.action.AUTOMATIC_ZEN_RULE";
    public static final String ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED = "android.app.action.AUTOMATIC_ZEN_RULE_STATUS_CHANGED";
    public static final String ACTION_CONSOLIDATED_NOTIFICATION_POLICY_CHANGED = "android.app.action.CONSOLIDATED_NOTIFICATION_POLICY_CHANGED";
    public static final String ACTION_INTERRUPTION_FILTER_CHANGED = "android.app.action.INTERRUPTION_FILTER_CHANGED";
    public static final String ACTION_NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED = "android.app.action.NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED";
    public static final String ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED = "android.app.action.NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED";
    public static final String ACTION_NOTIFICATION_POLICY_CHANGED = "android.app.action.NOTIFICATION_POLICY_CHANGED";
    public static final int AUTOMATIC_RULE_STATUS_ACTIVATED = 4;
    public static final int AUTOMATIC_RULE_STATUS_DEACTIVATED = 5;
    public static final int AUTOMATIC_RULE_STATUS_DISABLED = 2;
    public static final int AUTOMATIC_RULE_STATUS_ENABLED = 1;
    public static final int AUTOMATIC_RULE_STATUS_REMOVED = 3;
    public static final int AUTOMATIC_RULE_STATUS_UNKNOWN = -1;
    public static final String EXTRA_AUTOMATIC_RULE_ID = "android.app.extra.AUTOMATIC_RULE_ID";
    public static final String EXTRA_AUTOMATIC_ZEN_RULE_ID = "android.app.extra.AUTOMATIC_ZEN_RULE_ID";
    public static final String EXTRA_AUTOMATIC_ZEN_RULE_STATUS = "android.app.extra.AUTOMATIC_ZEN_RULE_STATUS";
    public static final String EXTRA_NOTIFICATION_POLICY = "android.app.extra.NOTIFICATION_POLICY";
    public static final String META_DATA_AUTOMATIC_RULE_TYPE = "android.service.zen.automatic.ruleType";
    public static final String META_DATA_RULE_INSTANCE_LIMIT = "android.service.zen.automatic.ruleInstanceLimit";
}
