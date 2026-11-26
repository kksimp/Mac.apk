package android.telecom;

import android.net.Uri;
import android.os.Bundle;
import android.os.OutcomeReceiver;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Telephony call management.
 *
 * No Mac has a cellular radio, a dialer, or a Telecom system service. AOSP
 * annotates this whole class
 * {@code @RequiresFeature(PackageManager.FEATURE_TELECOM)}
 * (frameworks/base telecomm/java/android/telecom/TelecomManager.java:82). We
 * report that feature because SELF-MANAGED calls work end to end (see
 * "Self-managed calls are delivered for real" below). Everything else here --
 * managed (SIM/carrier) accounts, the dialer, addCall -- gets the answer for a
 * device without Telecom, as described next.
 *
 * <h3>The rule this class is built around: NEVER SUCCEED INERTLY.</h3>
 *
 * The read-only queries degrade quietly, because their empty/false/null
 * answers are complete and unambiguous: there are no SIM or carrier
 * accounts, no default dialer, and no calls but the app's own self-managed
 * ones. Apps check these before acting and the answers are true.
 *
 * The operations that would hand a call over to Telecom do NOT degrade
 * quietly. They throw. That is a deliberate, measured choice:
 *
 * <ul>
 *   <li>An app that registers a phone account and hands Telecom a call is then
 *       WAITING -- for Telecom to bind its ConnectionService, or to deliver a
 *       CallControl through addCall's OutcomeReceiver. If the call is accepted
 *       and no callback ever comes, the app does not fail, it HANGS, with its
 *       own calling stack disabled because it believes the platform has taken
 *       over.</li>
 *   <li>Throwing routes the app down the path it already has for devices that
 *       refuse system-integrated calling -- which is most of them, since
 *       self-managed calling is permission-gated and frequently unavailable.
 *       Calling apps wrap registration in a try/catch precisely for this.</li>
 * </ul>
 *
 * The exception types are AOSP's own documented ones, not invented:
 * {@code registerPhoneAccount} documents "@throws UnsupportedOperationException
 * if the caller cannot modify phone state and the device does not have the
 * Telecom feature" (TelecomManager.java:1642-1643), which describes this device
 * exactly; and {@code addCall} itself throws
 * {@code IllegalStateException("Telecom service is not present")} on AOSP when
 * the Telecom binder is absent (TelecomManager.java:2948), which is literally
 * our situation.
 *
 * <h3>Self-managed calls are delivered for real.</h3>
 *
 * A SELF-MANAGED account (PhoneAccount.CAPABILITY_SELF_MANAGED) is the app's
 * own: its component is the app's own ConnectionService, and the app does its
 * own audio and video. Telecom's part for such a call -- create that service
 * and call onCreateOutgoingConnection / onCreateIncomingConnection on it --
 * involves nothing outside the app's process, so {@link SelfManagedTelecom}
 * does it in-process and every callback the app waits for arrives. That is
 * real success, not inert success, so the rule above still holds: for a
 * self-managed account, registerPhoneAccount, placeCall and
 * addNewIncomingCall succeed and the account queries report it; for any other
 * account they refuse exactly as described above.
 *
 * Source: frameworks/base/telecomm/java/android/telecom/TelecomManager.java
 */
public class TelecomManager {

    public static final String ACTION_CHANGE_DEFAULT_DIALER =
            "android.telecom.action.CHANGE_DEFAULT_DIALER";
    public static final String EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME =
            "android.telecom.extra.CHANGE_DEFAULT_DIALER_PACKAGE_NAME";
    public static final String EXTRA_PHONE_ACCOUNT_HANDLE =
            "android.telecom.extra.PHONE_ACCOUNT_HANDLE";
    public static final String EXTRA_INCOMING_CALL_EXTRAS =
            "android.telecom.extra.INCOMING_CALL_EXTRAS";
    public static final String EXTRA_OUTGOING_CALL_EXTRAS =
            "android.telecom.extra.OUTGOING_CALL_EXTRAS";
    public static final String EXTRA_START_CALL_WITH_SPEAKERPHONE =
            "android.telecom.extra.START_CALL_WITH_SPEAKERPHONE";
    public static final String EXTRA_START_CALL_WITH_VIDEO_STATE =
            "android.telecom.extra.START_CALL_WITH_VIDEO_STATE";
    public static final String EXTRA_CALL_SUBJECT = "android.telecom.extra.CALL_SUBJECT";

    // The remaining public constants, completing the class against
    // frameworks/base/core/api/current.txt. Constants carry no device
    // dependency -- an action string is the same string on any device -- so
    // they are simply mirrored.
    public static final String ACTION_CHANGE_PHONE_ACCOUNTS =
            "android.telecom.action.CHANGE_PHONE_ACCOUNTS";
    public static final String ACTION_CONFIGURE_PHONE_ACCOUNT =
            "android.telecom.action.CONFIGURE_PHONE_ACCOUNT";
    public static final String ACTION_DEFAULT_CALL_SCREENING_APP_CHANGED =
            "android.telecom.action.DEFAULT_CALL_SCREENING_APP_CHANGED";
    public static final String ACTION_DEFAULT_DIALER_CHANGED =
            "android.telecom.action.DEFAULT_DIALER_CHANGED";
    /** @deprecated on AOSP. */
    @Deprecated
    public static final String ACTION_INCOMING_CALL = "android.telecom.action.INCOMING_CALL";
    public static final String ACTION_PHONE_ACCOUNT_REGISTERED =
            "android.telecom.action.PHONE_ACCOUNT_REGISTERED";
    public static final String ACTION_PHONE_ACCOUNT_UNREGISTERED =
            "android.telecom.action.PHONE_ACCOUNT_UNREGISTERED";
    public static final String ACTION_POST_CALL = "android.telecom.action.POST_CALL";
    public static final String ACTION_SHOW_CALL_ACCESSIBILITY_SETTINGS =
            "android.telecom.action.SHOW_CALL_ACCESSIBILITY_SETTINGS";
    public static final String ACTION_SHOW_CALL_SETTINGS =
            "android.telecom.action.SHOW_CALL_SETTINGS";
    public static final String ACTION_SHOW_MISSED_CALLS_NOTIFICATION =
            "android.telecom.action.SHOW_MISSED_CALLS_NOTIFICATION";
    public static final String ACTION_SHOW_RESPOND_VIA_SMS_SETTINGS =
            "android.telecom.action.SHOW_RESPOND_VIA_SMS_SETTINGS";
    public static final char DTMF_CHARACTER_PAUSE = ',';
    public static final char DTMF_CHARACTER_WAIT = ';';
    public static final int DURATION_LONG = 3;
    public static final int DURATION_MEDIUM = 2;
    public static final int DURATION_SHORT = 1;
    public static final int DURATION_VERY_SHORT = 0;
    public static final String EXTRA_CALL_BACK_NUMBER = "android.telecom.extra.CALL_BACK_NUMBER";
    public static final String EXTRA_CALL_DISCONNECT_CAUSE =
            "android.telecom.extra.CALL_DISCONNECT_CAUSE";
    public static final String EXTRA_CALL_DISCONNECT_MESSAGE =
            "android.telecom.extra.CALL_DISCONNECT_MESSAGE";
    public static final String EXTRA_CALL_DURATION = "android.telecom.extra.CALL_DURATION";
    public static final String EXTRA_CALL_LOG_URI = "android.telecom.extra.CALL_LOG_URI";
    public static final String EXTRA_CALL_NETWORK_TYPE = "android.telecom.extra.CALL_NETWORK_TYPE";
    public static final String EXTRA_DEFAULT_CALL_SCREENING_APP_COMPONENT_NAME =
            "android.telecom.extra.DEFAULT_CALL_SCREENING_APP_COMPONENT_NAME";
    public static final String EXTRA_DISCONNECT_CAUSE = "android.telecom.extra.DISCONNECT_CAUSE";
    public static final String EXTRA_HANDLE = "android.telecom.extra.HANDLE";
    public static final String EXTRA_HAS_PICTURE = "android.telecom.extra.HAS_PICTURE";
    public static final String EXTRA_INCOMING_CALL_ADDRESS =
            "android.telecom.extra.INCOMING_CALL_ADDRESS";
    public static final String EXTRA_INCOMING_VIDEO_STATE =
            "android.telecom.extra.INCOMING_VIDEO_STATE";
    public static final String EXTRA_IS_DEFAULT_CALL_SCREENING_APP =
            "android.telecom.extra.IS_DEFAULT_CALL_SCREENING_APP";
    public static final String EXTRA_LOCATION = "android.telecom.extra.LOCATION";
    public static final String EXTRA_NOTIFICATION_COUNT =
            "android.telecom.extra.NOTIFICATION_COUNT";
    public static final String EXTRA_NOTIFICATION_PHONE_NUMBER =
            "android.telecom.extra.NOTIFICATION_PHONE_NUMBER";
    public static final String EXTRA_OUTGOING_PICTURE = "android.telecom.extra.OUTGOING_PICTURE";
    public static final String EXTRA_PICTURE_URI = "android.telecom.extra.PICTURE_URI";
    public static final String EXTRA_PRIORITY = "android.telecom.extra.PRIORITY";
    public static final String EXTRA_START_CALL_WITH_RTT =
            "android.telecom.extra.START_CALL_WITH_RTT";
    public static final String EXTRA_USE_ASSISTED_DIALING =
            "android.telecom.extra.USE_ASSISTED_DIALING";
    public static final String GATEWAY_ORIGINAL_ADDRESS =
            "android.telecom.extra.GATEWAY_ORIGINAL_ADDRESS";
    public static final String GATEWAY_PROVIDER_PACKAGE =
            "android.telecom.extra.GATEWAY_PROVIDER_PACKAGE";
    public static final String METADATA_INCLUDE_EXTERNAL_CALLS =
            "android.telecom.INCLUDE_EXTERNAL_CALLS";
    public static final String METADATA_INCLUDE_SELF_MANAGED_CALLS =
            "android.telecom.INCLUDE_SELF_MANAGED_CALLS";
    public static final String METADATA_IN_CALL_SERVICE_CAR_MODE_UI =
            "android.telecom.IN_CALL_SERVICE_CAR_MODE_UI";
    public static final String METADATA_IN_CALL_SERVICE_RINGING =
            "android.telecom.IN_CALL_SERVICE_RINGING";
    public static final String METADATA_IN_CALL_SERVICE_UI = "android.telecom.IN_CALL_SERVICE_UI";
    public static final int PRESENTATION_UNAVAILABLE = 5;
    public static final int PRIORITY_NORMAL = 0;
    public static final int PRIORITY_URGENT = 1;

    public static final int PRESENTATION_ALLOWED = 1;
    public static final int PRESENTATION_RESTRICTED = 2;
    public static final int PRESENTATION_UNKNOWN = 3;
    public static final int PRESENTATION_PAYPHONE = 4;

    /** The message every throw below carries, so a log makes the cause
     *  obvious. It names what IS supported, since FEATURE_TELECOM is reported
     *  for self-managed calls. */
    private static final String NO_TELECOM =
            "Mac.apk supports only self-managed calls (PhoneAccount.CAPABILITY_SELF_MANAGED); "
                    + "there is no Telecom system service, SIM or dialer behind";

    public TelecomManager() { }

    // ---- Read-only queries. Empty/false/null are complete answers here. ----

    /** True while a self-managed call this app placed or received is not
     *  disconnected. TelecomServiceImpl.isInCall answers false to a caller
     *  without READ_PHONE_STATE (it also admits the default dialer and carrier
     *  privilege, neither of which exists here). */
    public boolean isInCall() {
        android.app.Application app = android.app.ActivityThread.currentApplication();
        return app != null
                && app.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED
                && SelfManagedTelecom.hasLiveCall();
    }

    public boolean isInManagedCall() { return false; }

    public boolean isRinging() { return false; }

    /** No dialer app exists on a Mac, and AOSP returns null when there is none. */
    public String getDefaultDialerPackage() { return null; }

    public String getSystemDialerPackage() { return null; }

    public List<PhoneAccountHandle> getCallCapablePhoneAccounts() {
        return Collections.emptyList();
    }

    /**
     * Every self-managed account on the device: here, the ones this app
     * registered. Requires READ_PHONE_STATE (SecurityException otherwise), as
     * TelecomServiceImpl does.
     */
    public List<PhoneAccountHandle> getSelfManagedPhoneAccounts() {
        return SelfManagedTelecom.allHandles();
    }

    /**
     * The app's own registered self-managed account, else null. Null rather
     * than a fabricated account: an app that got a non-null handle here would
     * go on to place a call through it.
     */
    public PhoneAccount getPhoneAccount(PhoneAccountHandle account) {
        return SelfManagedTelecom.get(account);
    }

    public PhoneAccountHandle getDefaultOutgoingPhoneAccount(String uriScheme) { return null; }

    public PhoneAccountHandle getSimCallManager() { return null; }

    public boolean isVoiceMailNumber(PhoneAccountHandle accountHandle, String number) {
        return false;
    }

    public String getVoiceMailNumber(PhoneAccountHandle accountHandle) { return null; }

    /** No MMI dialer to consume the string, so it was not handled. */
    public boolean handleMmi(String dialString) { return false; }

    /** Permitted for this app's registered self-managed accounts only. */
    public boolean isOutgoingCallPermitted(PhoneAccountHandle phoneAccountHandle) {
        return SelfManagedTelecom.isRegistered(phoneAccountHandle);
    }

    /**
     * The self-managed accounts registered by THIS app. This used to throw
     * AOSP's null-binder {@code IllegalStateException("Telecom is not
     * available")}, which no app meets on a phone; apps call it unguarded on
     * the way into their call screens (OurFamilyWizard 2026.11.1, from its
     * OFWTelecomHandler constructor), so the throw left those screens empty.
     * Self-managed accounts are now held in-process (SelfManagedTelecom), so
     * this answers what AOSP answers with Telecom present: the list, possibly
     * empty.
     */
    public List<PhoneAccountHandle> getOwnSelfManagedPhoneAccounts() {
        return SelfManagedTelecom.ownHandles();
    }

    /** The PhoneAccounts registered by this app: its self-managed ones. */
    public List<PhoneAccount> getRegisteredPhoneAccounts() {
        return SelfManagedTelecom.accounts();
    }

    /**
     * The work-profile-spanning twin of getCallCapablePhoneAccounts(). AOSP
     * throws {@code IllegalStateException("telecom service is null.")} here
     * when the binder is absent -- note this differs from
     * getCallCapablePhoneAccounts(), which returns an empty list in the same
     * state. That asymmetry is AOSP's, not ours, and both halves are mirrored
     * as written.
     *
     * @throws IllegalStateException always: there is no Telecom service.
     */
    public List<PhoneAccountHandle> getCallCapablePhoneAccountsAcrossProfiles() {
        throw new IllegalStateException("telecom service is null.");
    }

    /** No user has ever chosen an outgoing account, because there are none. */
    public PhoneAccountHandle getUserSelectedOutgoingPhoneAccount() { return null; }

    /** No SIM, so no per-subscription connection manager. */
    public PhoneAccountHandle getSimCallManagerForSubscription(int subscriptionId) {
        return null;
    }

    /**
     * The subscriber (SIM) number for an account. There is no SIM and no
     * account, so there is no number -- null is both the truth and AOSP's own
     * answer when the Telecom binder is absent.
     */
    @Deprecated
    public String getLine1Number(PhoneAccountHandle accountHandle) { return null; }

    /** @see #handleMmi(String) -- no MMI dialer, so the string was not handled. */
    public boolean handleMmi(String dialString, PhoneAccountHandle accountHandle) {
        return false;
    }

    /**
     * TTY (teletypewriter) is a TELEPHONY accessibility mode carried over the
     * voice channel. There is no voice channel, so false. AOSP also returns
     * false from its own null-binder branch.
     */
    public boolean isTtySupported() { return false; }

    /**
     * MANAGE_ONGOING_CALLS is granted to a companion-device app so it can
     * control calls. Nothing here can grant it (there is no call to manage and
     * no CompanionDeviceManager association -- see android.companion), so
     * false. AOSP also returns false from its own null-binder branch.
     */
    public boolean hasManageOngoingCallsPermission() { return false; }

    /**
     * An Intent for the system's blocked-numbers settings screen. There is no
     * such screen and no blocked-number provider on a Mac, so null -- which is
     * exactly what AOSP returns from its own null-binder branch, and callers
     * already handle it because AOSP can return null there too.
     */
    public android.content.Intent createManageBlockedNumbersIntent() { return null; }

    /**
     * The content URI of the SIM's ADN (Abbreviated Dialing Numbers) contact
     * list. AOSP's null-binder branch returns the fixed fallback
     * {@code content://icc/adn}, and that is what we return -- deliberately,
     * after considering null.
     *
     * <p>This is NOT fabricating a SIM. The value is a PROVIDER ADDRESS, not
     * an answer: nothing is claimed about what lives there, and this device
     * has no provider registered for the {@code icc} authority, so an app that
     * queries it gets no rows -- the truthful result for a device with no SIM.
     * Returning null instead would be LESS honest AND less safe: the method is
     * not @Nullable in frameworks/base/core/api/current.txt, so an app is
     * entitled to dereference the result.
     */
    public Uri getAdnUriForPhoneAccount(PhoneAccountHandle accountHandle) {
        return Uri.parse("content://icc/adn");
    }

    /** Permitted for this app's registered self-managed accounts only. */
    public boolean isIncomingCallPermitted(PhoneAccountHandle phoneAccountHandle) {
        return SelfManagedTelecom.isRegistered(phoneAccountHandle);
    }

    // ---- No-ops that cannot mislead: nothing is waiting on a result. ----

    public void cancelMissedCallsNotification() { }

    public void showInCallScreen(boolean showDialpad) { }

    /** No call is in progress, so there is nothing to end; false says so. */
    public boolean endCall() { return false; }

    public void acceptRingingCall() { }

    public void acceptRingingCall(int videoState) { }

    public void silenceRinger() { }

    /**
     * Unregistering is a REMOVAL, so it cannot create a false expectation of a
     * callback -- the distinction that makes it safe to no-op while
     * registerPhoneAccount throws. Removing an account that was never
     * registered is genuinely a no-op, and AOSP no-ops it too when the Telecom
     * binder is absent. Throwing here would instead propagate out of an app's
     * teardown path, which is a regression, not honesty.
     */
    public void unregisterPhoneAccount(PhoneAccountHandle accountHandle) {
        SelfManagedTelecom.unregister(accountHandle);
    }

    public void clearPhoneAccounts() {
        SelfManagedTelecom.clear();
    }

    // ---- Hand-off operations. These THROW. See the class javadoc. ----

    /**
     * Registers a self-managed account (see the class javadoc), with AOSP's
     * checks, each a SecurityException: the account must belong to this app,
     * the app must hold MANAGE_OWN_CALLS, the account may not also be a call
     * provider, connection manager or SIM account, and its component must be a
     * service the manifest guards with BIND_TELECOM_CONNECTION_SERVICE. One
     * check of Mac.apk's own: that service must be a ConnectionService.
     * SelfManagedTelecom.register has the sources.
     *
     * @throws UnsupportedOperationException for any other account, including
     * a transactional one (CAPABILITY_SUPPORTS_TRANSACTIONAL_OPERATIONS), per AOSP's
     * documented behaviour for a device without the Telecom feature.
     * Accepting it would tell the app the platform is now responsible for its
     * calls, after which it waits for callbacks that cannot arrive.
     */
    public void registerPhoneAccount(PhoneAccount account) {
        if (SelfManagedTelecom.register(account)) return;
        throw new UnsupportedOperationException(NO_TELECOM + " registerPhoneAccount");
    }

    /**
     * With EXTRA_PHONE_ACCOUNT_HANDLE naming one of this app's self-managed
     * accounts, delivers the call to the app's ConnectionService
     * (onCreateOutgoingConnection, on the main thread, after this returns).
     *
     * @throws UnsupportedOperationException for any other call. Silently
     * dropping a call the user asked to place is worse than refusing it: the
     * app shows a call in progress that never connects.
     */
    public void placeCall(Uri address, Bundle extras) {
        if (SelfManagedTelecom.placeCall(address, extras)) return;
        throw new UnsupportedOperationException(NO_TELECOM + " placeCall");
    }

    /**
     * For one of this app's self-managed accounts, delivers the call to the
     * app's ConnectionService (onCreateIncomingConnection, then
     * onShowIncomingCallUi once it rings), as Telecom does.
     *
     * @throws UnsupportedOperationException for any other account. On Android
     * this asks Telecom to bind the app's ConnectionService; for a managed
     * account nothing here can, so accepting the call would be exactly the
     * "accepted, then silent" shape that hangs an incoming-call flow.
     */
    public void addNewIncomingCall(PhoneAccountHandle phoneAccount, Bundle extras) {
        if (SelfManagedTelecom.addNewIncomingCall(phoneAccount, extras)) return;
        throw new UnsupportedOperationException(NO_TELECOM + " addNewIncomingCall");
    }

    /**
     * @throws UnsupportedOperationException always, for the same reason as
     * addNewIncomingCall.
     */
    public void addNewUnknownCall(PhoneAccountHandle phoneAccount, Bundle extras) {
        throw new UnsupportedOperationException(NO_TELECOM + " addNewUnknownCall");
    }

    /**
     * @throws UnsupportedOperationException always. The conference twin of
     * addNewIncomingCall: on Android this asks Telecom to bind the app's
     * ConnectionService and call onCreateIncomingConference, which cannot
     * happen here. Note AOSP's own null-binder branch silently no-ops instead;
     * we deviate DELIBERATELY, for the reason in the class javadoc -- a silent
     * no-op leaves the app waiting on a callback forever, and this class
     * already made that same trade for addNewIncomingCall and placeCall.
     */
    public void addNewIncomingConference(PhoneAccountHandle phoneAccount, Bundle extras) {
        throw new UnsupportedOperationException(NO_TELECOM + " addNewIncomingConference");
    }

    /**
     * @throws UnsupportedOperationException always. The conference twin of
     * placeCall; same deliberate deviation from AOSP's silent no-op as
     * addNewIncomingConference.
     */
    public void startConference(List<Uri> participants, Bundle extras) {
        throw new UnsupportedOperationException(NO_TELECOM + " startConference");
    }

    /**
     * Accepts a call handover from another app. On Android this makes Telecom
     * call onCreateIncomingHandoverConnection on the destination account's
     * ConnectionService; with no Telecom service that never happens, so the
     * app would sit waiting for a connection it will never be asked to make.
     *
     * <p>Same deliberate deviation from AOSP's silent no-op as
     * addNewIncomingConference, and for the same reason.
     *
     * @throws UnsupportedOperationException always.
     */
    public void acceptHandover(Uri srcAddr, int videoState, PhoneAccountHandle destAcct) {
        throw new UnsupportedOperationException(NO_TELECOM + " acceptHandover");
    }

    /**
     * The transactional calling API. AOSP itself throws
     * {@code IllegalStateException("Telecom service is not present")} here when
     * the Telecom binder is null (TelecomManager.java:2948), so this is AOSP's
     * own code path for our situation rather than a Mac.apk invention.
     *
     * <p>Note it must NOT report the failure through {@code pendingControl}
     * instead: AOSP throws synchronously, and an app that catches the throw
     * would never learn of an asynchronous error delivered afterwards.
     *
     * @throws IllegalStateException always.
     */
    public void addCall(CallAttributes callAttributes,
            Executor executor,
            OutcomeReceiver<CallControl, CallException> pendingControl,
            CallControlCallback handshakes,
            CallEventCallback events) {
        java.util.Objects.requireNonNull(callAttributes);
        java.util.Objects.requireNonNull(executor);
        java.util.Objects.requireNonNull(pendingControl);
        java.util.Objects.requireNonNull(handshakes);
        java.util.Objects.requireNonNull(events);
        throw new IllegalStateException("Telecom service is not present");
    }
}
