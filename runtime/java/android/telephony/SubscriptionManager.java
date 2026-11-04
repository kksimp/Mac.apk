// android.telephony.SubscriptionManager
//
// AOSP's SIM-subscription service. Apps fetch it via getSystemService(
// TELEPHONY_SUBSCRIPTION_SERVICE) to enumerate active SIM subscriptions.
// Mac.apk has no telephony hardware, so there are NO subscriptions: every list
// is empty, every default id is INVALID_SUBSCRIPTION_ID, and every mutation
// fails. That is the honest "no SIM" result, and it is the state a dual-SIM-
// aware app already has to cope with on a Wi-Fi-only device.
//
// Source: frameworks/base/telephony/java/android/telephony/SubscriptionManager.java
package android.telephony;

import android.app.PendingIntent;
import android.content.Context;
import android.os.ParcelUuid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

public class SubscriptionManager {

    public static final String ACTION_DEFAULT_SMS_SUBSCRIPTION_CHANGED = "android.telephony.action.DEFAULT_SMS_SUBSCRIPTION_CHANGED";
    public static final String ACTION_DEFAULT_SUBSCRIPTION_CHANGED = "android.telephony.action.DEFAULT_SUBSCRIPTION_CHANGED";
    public static final String ACTION_MANAGE_SUBSCRIPTION_PLANS = "android.telephony.action.MANAGE_SUBSCRIPTION_PLANS";
    public static final String ACTION_REFRESH_SUBSCRIPTION_PLANS = "android.telephony.action.REFRESH_SUBSCRIPTION_PLANS";
    public static final int D2D_SHARING_ALL = 3;
    public static final int D2D_SHARING_ALL_CONTACTS = 1;
    public static final int D2D_SHARING_DISABLED = 0;
    public static final int D2D_SHARING_SELECTED_CONTACTS = 2;
    public static final String D2D_STATUS_SHARING = "d2d_sharing_status";
    public static final String D2D_STATUS_SHARING_SELECTED_CONTACTS = "d2d_sharing_contacts";
    public static final int DATA_ROAMING_DISABLE = 0;
    public static final int DATA_ROAMING_ENABLE = 1;
    public static final int DEFAULT_SUBSCRIPTION_ID = 2147483647;
    public static final String EXTRA_SLOT_INDEX = "android.telephony.extra.SLOT_INDEX";
    public static final String EXTRA_SUBSCRIPTION_INDEX = "android.telephony.extra.SUBSCRIPTION_INDEX";
    public static final int INVALID_SIM_SLOT_INDEX = -1;
    public static final int INVALID_SUBSCRIPTION_ID = -1;
    public static final int PHONE_NUMBER_SOURCE_CARRIER = 2;
    public static final int PHONE_NUMBER_SOURCE_IMS = 3;
    public static final int PHONE_NUMBER_SOURCE_UICC = 1;
    public static final int SERVICE_CAPABILITY_DATA = 3;
    public static final int SERVICE_CAPABILITY_SMS = 2;
    public static final int SERVICE_CAPABILITY_VOICE = 1;
    public static final int SUBSCRIPTION_TYPE_LOCAL_SIM = 0;
    public static final int SUBSCRIPTION_TYPE_REMOTE_SIM = 1;
    public static final int USAGE_SETTING_DATA_CENTRIC = 2;
    public static final int USAGE_SETTING_DEFAULT = 0;
    public static final int USAGE_SETTING_UNKNOWN = -1;
    public static final int USAGE_SETTING_VOICE_CENTRIC = 1;


    private static final CopyOnWriteArrayList<OnSubscriptionsChangedListener> sSubListeners =
            new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<OnOpportunisticSubscriptionsChangedListener>
            sOppListeners = new CopyOnWriteArrayList<>();

    public SubscriptionManager() {}
    public SubscriptionManager(Context context) {}

    public static SubscriptionManager from(Context context) {
        return new SubscriptionManager(context);
    }

    /**
     * AOSP returns a manager whose queries span every user profile. There is one
     * user here, so it is the same manager -- returning null would break the
     * documented non-null contract.
     */
    public SubscriptionManager createForAllUserProfiles() { return this; }

    // === Queries: no SIM, so everything is empty / invalid ==================

    public List<SubscriptionInfo> getActiveSubscriptionInfoList() { return new ArrayList<>(); }
    public SubscriptionInfo getActiveSubscriptionInfo(int subscriptionId) { return null; }
    public SubscriptionInfo getActiveSubscriptionInfoForSimSlotIndex(int slotIndex) { return null; }
    public int getActiveSubscriptionInfoCount() { return 0; }
    public int getActiveSubscriptionInfoCountMax() { return 1; }

    /** AOSP is @NonNull: includes inactive subscriptions too. There are none. */
    public List<SubscriptionInfo> getAllSubscriptionInfoList() { return new ArrayList<>(); }

    public List<SubscriptionInfo> getOpportunisticSubscriptions() { return new ArrayList<>(); }

    public static int getDefaultSubscriptionId() { return INVALID_SUBSCRIPTION_ID; }
    public static int getDefaultDataSubscriptionId() { return INVALID_SUBSCRIPTION_ID; }
    public static int getDefaultVoiceSubscriptionId() { return INVALID_SUBSCRIPTION_ID; }
    public static int getDefaultSmsSubscriptionId() { return INVALID_SUBSCRIPTION_ID; }

    /** AOSP returns the subscription currently serving data. There is none. */
    public static int getActiveDataSubscriptionId() { return INVALID_SUBSCRIPTION_ID; }

    /** AOSP getSlotIndex (API 29): SIM slot for a subscription id. */
    public static int getSlotIndex(int subscriptionId) { return INVALID_SIM_SLOT_INDEX; }

    /** AOSP: the subscription id in a given slot; no slot is populated. */
    public static int getSubscriptionId(int slotIndex) { return INVALID_SUBSCRIPTION_ID; }

    /** AOSP is @Nullable and returns null when the slot holds no subscription. */
    public int[] getSubscriptionIds(int slotIndex) { return null; }

    /** AOSP: valid means "not the INVALID sentinel", a pure range check. */
    public static boolean isValidSubscriptionId(int subscriptionId) {
        return subscriptionId > INVALID_SUBSCRIPTION_ID;
    }

    /** AOSP: usable means valid AND not one of the reserved negative ids. */
    public static boolean isUsableSubscriptionId(int subscriptionId) {
        return subscriptionId >= 0 && subscriptionId < DEFAULT_SUBSCRIPTION_ID;
    }

    /** No subscription is active, so no id is. */
    public boolean isActiveSubscriptionId(int subscriptionId) { return false; }

    /** No subscription exists to be associated with a user. */
    public boolean isSubscriptionAssociatedWithUser(int subscriptionId) { return false; }

    /**
     * AOSP checks whether the calling app has carrier privileges over the
     * subscription. It has none, because no subscription exists.
     */
    public boolean canManageSubscription(SubscriptionInfo info) { return false; }

    /** AOSP: roaming state of a subscription. Nothing is registered anywhere,
     *  so AOSP's own first branch answers: getPhoneId(subId) is < 0 for an
     *  unknown subscription and the method returns false (frameworks/base
     *  android14-release telephony/java/android/telephony/
     *  SubscriptionManager.java:2543-2550).
     *
     *  An INSTANCE method, as in AOSP and the SDK-34 android.jar ("public
     *  boolean isNetworkRoaming(int)"). It was static here, so every app call
     *  (invokevirtual on a SubscriptionManager from getSystemService) threw
     *  IncompatibleClassChangeError. Found by debug/apishape_gate.sh
     *  (KNOWN_OPEN, struck 2026-09-26). */
    public boolean isNetworkRoaming(int subId) { return false; }

    // AOSP getAccessibleSubscriptionInfoList (API 26): embedded (eSIM)
    // subscriptions the calling app has carrier privileges over. AOSP's own
    // body can legally return null here -- its javadoc documents null as
    // "current state is unknown, but if a listener is registered, a future
    // onSubscriptionsChanged will follow". Mac.apk never fires that listener
    // (there is no eSIM stack to raise a change event), so a null here would
    // promise an update that never comes. An empty list is the honest
    // TERMINAL answer instead.
    public List<SubscriptionInfo> getAccessibleSubscriptionInfoList() {
        return new ArrayList<>();
    }

    // AOSP getCompleteActiveSubscriptionInfoList (API 30, @NonNull): AOSP's own
    // body converts a null active list into `new ArrayList<>()` before
    // returning, so even on a device with zero active subscriptions the answer
    // is an empty list, never null.
    public List<SubscriptionInfo> getCompleteActiveSubscriptionInfoList() {
        return new ArrayList<>();
    }

    /** AOSP getPhoneNumber(int) (API 33, @NonNull): "" when not available. */
    public String getPhoneNumber(int subscriptionId) { return ""; }

    /** AOSP getPhoneNumber(int, int) with an explicit source. Same answer. */
    public String getPhoneNumber(int subscriptionId, int source) { return ""; }

    /** Nothing to set it on. AOSP throws only on a bad subId/permission. */
    public void setCarrierPhoneNumber(int subscriptionId, String number) { }

    // === Listeners ==========================================================
    // Registration is real so remove* works. Nothing ever fires, because the
    // subscription set is permanently empty and cannot change.
    //
    // ⚠ AOSP fires onSubscriptionsChanged ONCE on registration (its own
    // javadoc: "will be invoked ... when the subscription list changes AND on
    // registration"). We honour that -- an app that populates its SIM picker
    // from the first callback would otherwise wait forever -- delivering the
    // (empty) state on the supplied Executor.

    public void addOnSubscriptionsChangedListener(OnSubscriptionsChangedListener listener) {
        addOnSubscriptionsChangedListener(Runnable::run, listener);
    }

    public void addOnSubscriptionsChangedListener(Executor executor,
            OnSubscriptionsChangedListener listener) {
        if (listener == null) return;
        sSubListeners.add(listener);
        if (executor != null) {
            executor.execute(() -> {
                try { listener.onSubscriptionsChanged(); } catch (Throwable ignored) { }
            });
        }
    }

    public void removeOnSubscriptionsChangedListener(OnSubscriptionsChangedListener listener) {
        if (listener != null) sSubListeners.remove(listener);
    }

    public void addOnOpportunisticSubscriptionsChangedListener(Executor executor,
            OnOpportunisticSubscriptionsChangedListener listener) {
        if (listener == null) return;
        sOppListeners.add(listener);
        if (executor != null) {
            executor.execute(() -> {
                try { listener.onOpportunisticSubscriptionsChanged(); } catch (Throwable t) { }
            });
        }
    }

    public void removeOnOpportunisticSubscriptionsChangedListener(
            OnOpportunisticSubscriptionsChangedListener listener) {
        if (listener != null) sOppListeners.remove(listener);
    }

    /** AOSP declares this as a class (not an interface) with one overridable method. */
    public static class OnSubscriptionsChangedListener {
        public OnSubscriptionsChangedListener() { }
        public void onSubscriptionsChanged() { }
        /** @hide AOSP also exposes an add/remove-failure hook. */
        public void onAddListenerFailed() { }
    }

    /** Also a class in AOSP. */
    public static class OnOpportunisticSubscriptionsChangedListener {
        public OnOpportunisticSubscriptionsChangedListener() { }
        public void onOpportunisticSubscriptionsChanged() { }
    }

    // === Subscription groups ================================================
    // Grouping needs at least one real subscription. AOSP throws
    // IllegalArgumentException for an empty/invalid list, which is what every
    // call here hits.

    public ParcelUuid createSubscriptionGroup(List<Integer> subIdList) {
        if (subIdList == null || subIdList.isEmpty()) {
            throw new IllegalArgumentException("Invalid subIdList " + subIdList);
        }
        // Every id in the list is invalid here, so AOSP's own validation fails.
        throw new IllegalArgumentException("Invalid subId in subIdList " + subIdList);
    }

    public void addSubscriptionsIntoGroup(List<Integer> subIdList, ParcelUuid groupUuid) {
        if (subIdList == null || subIdList.isEmpty()) {
            throw new IllegalArgumentException("Invalid subIdList " + subIdList);
        }
        if (groupUuid == null) throw new IllegalArgumentException("Invalid groupUuid");
        throw new IllegalArgumentException("Invalid subId in subIdList " + subIdList);
    }

    public void removeSubscriptionsFromGroup(List<Integer> subIdList, ParcelUuid groupUuid) {
        if (subIdList == null || subIdList.isEmpty()) {
            throw new IllegalArgumentException("Invalid subIdList " + subIdList);
        }
        if (groupUuid == null) throw new IllegalArgumentException("Invalid groupUuid");
        throw new IllegalArgumentException("Invalid subId in subIdList " + subIdList);
    }

    /** AOSP is @NonNull: an unknown group is simply empty. */
    public List<SubscriptionInfo> getSubscriptionsInGroup(ParcelUuid groupUuid) {
        if (groupUuid == null) throw new IllegalArgumentException("Invalid groupUuid");
        return new ArrayList<>();
    }

    // === Plans / overrides / D2D ============================================

    /** AOSP is @NonNull: no plans have been set for a subscription that does not exist. */
    public List<SubscriptionPlan> getSubscriptionPlans(int subId) {
        return Collections.emptyList();
    }

    public void setSubscriptionPlans(int subId, List<SubscriptionPlan> plans) { }

    public void setSubscriptionPlans(int subId, List<SubscriptionPlan> plans,
            long expirationDurationMillis) { }

    public void setSubscriptionOverrideUnmetered(int subId, boolean overrideUnmetered,
            long expirationDurationMillis) { }

    public void setSubscriptionOverrideUnmetered(int subId, boolean overrideUnmetered,
            int[] networkTypes, long expirationDurationMillis) { }

    public void setSubscriptionOverrideCongested(int subId, boolean overrideCongested,
            long expirationDurationMillis) { }

    public void setSubscriptionOverrideCongested(int subId, boolean overrideCongested,
            int[] networkTypes, long expirationDurationMillis) { }

    public void setOpportunistic(boolean opportunistic, int subId) { }

    /** AOSP returns the sharing preference; nothing has ever been set. */
    public int getDeviceToDeviceStatusSharingPreference(int subscriptionId) {
        return D2D_SHARING_DISABLED;
    }

    public void setDeviceToDeviceStatusSharingPreference(int subscriptionId, int sharing) { }

    /** AOSP is @NonNull: the contact list is empty because none was ever set. */
    public List<android.net.Uri> getDeviceToDeviceStatusSharingContacts(int subscriptionId) {
        return Collections.emptyList();
    }

    public void setDeviceToDeviceStatusSharingContacts(int subscriptionId,
            List<android.net.Uri> contacts) { }

    /**
     * AOSP switches the active eSIM profile, reporting the outcome through the
     * PendingIntent. There is no eUICC, so nothing switches. The intent is NOT
     * fired: AOSP fires it from the LPA (the eSIM app), which is absent here,
     * and firing a success would be a lie while firing a failure would invent
     * an error code we did not receive.
     */
    public void switchToSubscription(int subId, PendingIntent callbackIntent) { }

    /** AOSP @hide / @SystemApi: the ids of the active subscriptions. A Mac has
     *  no SIM, so none (the same answer getActiveSubscriptionInfoList gives).
     *  Google Play services' checkin SIM helper calls it. */
    public int[] getActiveSubscriptionIdList() { return new int[0]; }
    public int[] getActiveSubscriptionIdList(boolean visibleOnly) { return new int[0]; }
}
