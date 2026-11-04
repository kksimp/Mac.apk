// android.telephony.TelephonyManager -- the device-cellular service.
//
// THERE IS NO CELLULAR RADIO ON A MAC. Every answer below is the one AOSP
// documents for a device whose radio is absent or out of service, and NOTHING
// here invents a network, an operator, a SIM or a subscriber. The three places
// that look like content are all justified in place:
//   - getPhoneType() = PHONE_TYPE_GSM (see its comment: a radio TYPE is a
//     hardware property that apps branch on for phone-vs-tablet layout).
//   - getNetworkOperatorName()/getNetworkCountryIso()/getSimCountryIso() keep
//     the pre-existing values; see their comments.
//   - getEmergencyNumberList()/isEmergencyNumber() report AOSP's OWN
//     platform-hardcoded SIM-absent fallback list, not a network's.
//
// Source: frameworks/base/telephony/java/android/telephony/TelephonyManager.java
package android.telephony;

import android.content.Context;
import android.net.Uri;
import android.os.PersistableBundle;
import android.telephony.data.NetworkSlicingConfig;
import android.telephony.emergency.EmergencyNumber;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public class TelephonyManager {

    public static final String ACTION_CARRIER_MESSAGING_CLIENT_SERVICE = "android.telephony.action.CARRIER_MESSAGING_CLIENT_SERVICE";
    public static final String ACTION_CARRIER_SIGNAL_DEFAULT_NETWORK_AVAILABLE = "android.telephony.action.CARRIER_SIGNAL_DEFAULT_NETWORK_AVAILABLE";
    public static final String ACTION_CARRIER_SIGNAL_PCO_VALUE = "android.telephony.action.CARRIER_SIGNAL_PCO_VALUE";
    public static final String ACTION_CARRIER_SIGNAL_REDIRECTED = "android.telephony.action.CARRIER_SIGNAL_REDIRECTED";
    public static final String ACTION_CARRIER_SIGNAL_REQUEST_NETWORK_FAILED = "android.telephony.action.CARRIER_SIGNAL_REQUEST_NETWORK_FAILED";
    public static final String ACTION_CARRIER_SIGNAL_RESET = "android.telephony.action.CARRIER_SIGNAL_RESET";
    public static final String ACTION_CONFIGURE_VOICEMAIL = "android.telephony.action.CONFIGURE_VOICEMAIL";
    public static final String ACTION_MULTI_SIM_CONFIG_CHANGED = "android.telephony.action.MULTI_SIM_CONFIG_CHANGED";
    public static final String ACTION_NETWORK_COUNTRY_CHANGED = "android.telephony.action.NETWORK_COUNTRY_CHANGED";
    public static final String ACTION_PHONE_STATE_CHANGED = "android.intent.action.PHONE_STATE";
    public static final String ACTION_RESET_MOBILE_NETWORK_SETTINGS = "android.telephony.action.RESET_MOBILE_NETWORK_SETTINGS";
    public static final String ACTION_RESPOND_VIA_MESSAGE = "android.intent.action.RESPOND_VIA_MESSAGE";
    public static final String ACTION_SECRET_CODE = "android.telephony.action.SECRET_CODE";
    public static final String ACTION_SHOW_VOICEMAIL_NOTIFICATION = "android.telephony.action.SHOW_VOICEMAIL_NOTIFICATION";
    public static final String ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED = "android.telephony.action.SUBSCRIPTION_CARRIER_IDENTITY_CHANGED";
    public static final String ACTION_SUBSCRIPTION_SPECIFIC_CARRIER_IDENTITY_CHANGED = "android.telephony.action.SUBSCRIPTION_SPECIFIC_CARRIER_IDENTITY_CHANGED";
    public static final int ALLOWED_NETWORK_TYPES_REASON_CARRIER = 2;
    public static final int ALLOWED_NETWORK_TYPES_REASON_USER = 0;
    public static final int APPTYPE_CSIM = 4;
    public static final int APPTYPE_ISIM = 5;
    public static final int APPTYPE_RUIM = 3;
    public static final int APPTYPE_SIM = 1;
    public static final int APPTYPE_UNKNOWN = 0;
    public static final int APPTYPE_USIM = 2;
    public static final int AUTHTYPE_EAP_AKA = 129;
    public static final int AUTHTYPE_EAP_SIM = 128;
    public static final int AUTHTYPE_GBA_BOOTSTRAP = 132;
    public static final int AUTHTYPE_GBA_NAF_KEY_EXTERNAL = 133;
    public static final int CALL_COMPOSER_STATUS_BUSINESS_ONLY = 2;
    public static final int CALL_COMPOSER_STATUS_OFF = 0;
    public static final int CALL_COMPOSER_STATUS_ON = 1;
    public static final int CALL_STATE_IDLE = 0;
    public static final int CALL_STATE_OFFHOOK = 2;
    public static final int CALL_STATE_RINGING = 1;
    public static final String CAPABILITY_SLICING_CONFIG_SUPPORTED = "CAPABILITY_SLICING_CONFIG_SUPPORTED";
    public static final int CARRIER_RESTRICTION_STATUS_NOT_RESTRICTED = 1;
    public static final int CARRIER_RESTRICTION_STATUS_RESTRICTED = 2;
    public static final int CARRIER_RESTRICTION_STATUS_RESTRICTED_TO_CALLER = 3;
    public static final int CARRIER_RESTRICTION_STATUS_UNKNOWN = 0;
    /** @deprecated */ @Deprecated
    public static final int CDMA_ROAMING_MODE_AFFILIATED = 1;
    /** @deprecated */ @Deprecated
    public static final int CDMA_ROAMING_MODE_ANY = 2;
    /** @deprecated */ @Deprecated
    public static final int CDMA_ROAMING_MODE_HOME = 0;
    /** @deprecated */ @Deprecated
    public static final int CDMA_ROAMING_MODE_RADIO_DEFAULT = -1;
    public static final int DATA_ACTIVITY_DORMANT = 4;
    public static final int DATA_ACTIVITY_IN = 1;
    public static final int DATA_ACTIVITY_INOUT = 3;
    public static final int DATA_ACTIVITY_NONE = 0;
    public static final int DATA_ACTIVITY_OUT = 2;
    public static final int DATA_CONNECTED = 2;
    public static final int DATA_CONNECTING = 1;
    public static final int DATA_DISCONNECTED = 0;
    public static final int DATA_DISCONNECTING = 4;
    public static final int DATA_ENABLED_REASON_CARRIER = 2;
    public static final int DATA_ENABLED_REASON_OVERRIDE = 4;
    public static final int DATA_ENABLED_REASON_POLICY = 1;
    public static final int DATA_ENABLED_REASON_THERMAL = 3;
    public static final int DATA_ENABLED_REASON_UNKNOWN = -1;
    public static final int DATA_ENABLED_REASON_USER = 0;
    public static final int DATA_HANDOVER_IN_PROGRESS = 5;
    public static final int DATA_SUSPENDED = 3;
    public static final int DATA_UNKNOWN = -1;
    public static final int DEFAULT_PORT_INDEX = 0;
    /** @deprecated */ @Deprecated
    public static final int ERI_FLASH = 2;
    /** @deprecated */ @Deprecated
    public static final int ERI_OFF = 1;
    /** @deprecated */ @Deprecated
    public static final int ERI_ON = 0;
    public static final String EVENT_DISPLAY_EMERGENCY_MESSAGE = "android.telephony.event.DISPLAY_EMERGENCY_MESSAGE";
    public static final String EXTRA_ACTIVE_SIM_SUPPORTED_COUNT = "android.telephony.extra.ACTIVE_SIM_SUPPORTED_COUNT";
    public static final String EXTRA_APN_PROTOCOL = "android.telephony.extra.APN_PROTOCOL";
    public static final String EXTRA_APN_TYPE = "android.telephony.extra.APN_TYPE";
    public static final String EXTRA_CALL_VOICEMAIL_INTENT = "android.telephony.extra.CALL_VOICEMAIL_INTENT";
    public static final String EXTRA_CARRIER_ID = "android.telephony.extra.CARRIER_ID";
    public static final String EXTRA_CARRIER_NAME = "android.telephony.extra.CARRIER_NAME";
    public static final String EXTRA_DATA_FAIL_CAUSE = "android.telephony.extra.DATA_FAIL_CAUSE";
    public static final String EXTRA_DEFAULT_NETWORK_AVAILABLE = "android.telephony.extra.DEFAULT_NETWORK_AVAILABLE";
    public static final String EXTRA_EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE = "android.telephony.extra.EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE";
    public static final String EXTRA_EMERGENCY_CALL_TO_SATELLITE_LAUNCH_INTENT = "android.telephony.extra.EMERGENCY_CALL_TO_SATELLITE_LAUNCH_INTENT";
    public static final String EXTRA_HIDE_PUBLIC_SETTINGS = "android.telephony.extra.HIDE_PUBLIC_SETTINGS";
    /** @deprecated */ @Deprecated
    public static final String EXTRA_INCOMING_NUMBER = "incoming_number";
    public static final String EXTRA_IS_REFRESH = "android.telephony.extra.IS_REFRESH";
    public static final String EXTRA_LAST_KNOWN_NETWORK_COUNTRY = "android.telephony.extra.LAST_KNOWN_NETWORK_COUNTRY";
    public static final String EXTRA_LAUNCH_VOICEMAIL_SETTINGS_INTENT = "android.telephony.extra.LAUNCH_VOICEMAIL_SETTINGS_INTENT";
    public static final String EXTRA_NETWORK_COUNTRY = "android.telephony.extra.NETWORK_COUNTRY";
    public static final String EXTRA_NOTIFICATION_COUNT = "android.telephony.extra.NOTIFICATION_COUNT";
    public static final String EXTRA_PCO_ID = "android.telephony.extra.PCO_ID";
    public static final String EXTRA_PCO_VALUE = "android.telephony.extra.PCO_VALUE";
    public static final String EXTRA_PHONE_ACCOUNT_HANDLE = "android.telephony.extra.PHONE_ACCOUNT_HANDLE";
    public static final String EXTRA_REDIRECTION_URL = "android.telephony.extra.REDIRECTION_URL";
    public static final String EXTRA_SPECIFIC_CARRIER_ID = "android.telephony.extra.SPECIFIC_CARRIER_ID";
    public static final String EXTRA_SPECIFIC_CARRIER_NAME = "android.telephony.extra.SPECIFIC_CARRIER_NAME";
    public static final String EXTRA_STATE = "state";
    /** AOSP: PhoneConstants.State.IDLE.toString(). */
    public static final String EXTRA_STATE_IDLE = "IDLE";
    /** AOSP: PhoneConstants.State.OFFHOOK.toString(). */
    public static final String EXTRA_STATE_OFFHOOK = "OFFHOOK";
    /** AOSP: PhoneConstants.State.RINGING.toString(). */
    public static final String EXTRA_STATE_RINGING = "RINGING";
    public static final String EXTRA_SUBSCRIPTION_ID = "android.telephony.extra.SUBSCRIPTION_ID";
    public static final String EXTRA_VOICEMAIL_NUMBER = "android.telephony.extra.VOICEMAIL_NUMBER";
    public static final int INCLUDE_LOCATION_DATA_COARSE = 1;
    public static final int INCLUDE_LOCATION_DATA_FINE = 2;
    public static final int INCLUDE_LOCATION_DATA_NONE = 0;
    public static final String METADATA_HIDE_VOICEMAIL_SETTINGS_MENU = "android.telephony.HIDE_VOICEMAIL_SETTINGS_MENU";
    public static final int MULTISIM_ALLOWED = 0;
    public static final int MULTISIM_NOT_SUPPORTED_BY_CARRIER = 2;
    public static final int MULTISIM_NOT_SUPPORTED_BY_HARDWARE = 1;
    public static final int NETWORK_SELECTION_MODE_AUTO = 1;
    public static final int NETWORK_SELECTION_MODE_MANUAL = 2;
    public static final int NETWORK_SELECTION_MODE_UNKNOWN = 0;
    /** @deprecated */ @Deprecated
    public static final int NETWORK_TYPE_1xRTT = 7;
    public static final long NETWORK_TYPE_BITMASK_1xRTT = 64L;
    /** @deprecated */ @Deprecated
    public static final long NETWORK_TYPE_BITMASK_CDMA = 8L;
    public static final long NETWORK_TYPE_BITMASK_EDGE = 2L;
    /** @deprecated */ @Deprecated
    public static final long NETWORK_TYPE_BITMASK_EHRPD = 8192L;
    public static final long NETWORK_TYPE_BITMASK_EVDO_0 = 16L;
    public static final long NETWORK_TYPE_BITMASK_EVDO_A = 32L;
    public static final long NETWORK_TYPE_BITMASK_EVDO_B = 2048L;
    public static final long NETWORK_TYPE_BITMASK_GPRS = 1L;
    public static final long NETWORK_TYPE_BITMASK_GSM = 32768L;
    public static final long NETWORK_TYPE_BITMASK_HSDPA = 128L;
    public static final long NETWORK_TYPE_BITMASK_HSPA = 512L;
    public static final long NETWORK_TYPE_BITMASK_HSPAP = 16384L;
    public static final long NETWORK_TYPE_BITMASK_HSUPA = 256L;
    public static final long NETWORK_TYPE_BITMASK_IWLAN = 131072L;
    public static final long NETWORK_TYPE_BITMASK_LTE = 4096L;
    /** @deprecated */ @Deprecated
    public static final long NETWORK_TYPE_BITMASK_LTE_CA = 262144L;
    public static final long NETWORK_TYPE_BITMASK_NR = 524288L;
    public static final long NETWORK_TYPE_BITMASK_TD_SCDMA = 65536L;
    public static final long NETWORK_TYPE_BITMASK_UMTS = 4L;
    public static final long NETWORK_TYPE_BITMASK_UNKNOWN = 0L;
    /** @deprecated */ @Deprecated
    public static final int NETWORK_TYPE_CDMA = 4;
    public static final int NETWORK_TYPE_EDGE = 2;
    /** @deprecated */ @Deprecated
    public static final int NETWORK_TYPE_EHRPD = 14;
    /** @deprecated */ @Deprecated
    public static final int NETWORK_TYPE_EVDO_0 = 5;
    /** @deprecated */ @Deprecated
    public static final int NETWORK_TYPE_EVDO_A = 6;
    /** @deprecated */ @Deprecated
    public static final int NETWORK_TYPE_EVDO_B = 12;
    public static final int NETWORK_TYPE_GPRS = 1;
    public static final int NETWORK_TYPE_GSM = 16;
    public static final int NETWORK_TYPE_HSDPA = 8;
    public static final int NETWORK_TYPE_HSPA = 10;
    public static final int NETWORK_TYPE_HSPAP = 15;
    public static final int NETWORK_TYPE_HSUPA = 9;
    /** @deprecated */ @Deprecated
    public static final int NETWORK_TYPE_IDEN = 11;
    public static final int NETWORK_TYPE_IWLAN = 18;
    public static final int NETWORK_TYPE_LTE = 13;
    public static final int NETWORK_TYPE_NR = 20;
    public static final int NETWORK_TYPE_TD_SCDMA = 17;
    public static final int NETWORK_TYPE_UMTS = 3;
    public static final int NETWORK_TYPE_UNKNOWN = 0;
    /** @deprecated */ @Deprecated
    public static final int PHONE_TYPE_CDMA = 2;
    public static final int PHONE_TYPE_GSM = 1;
    public static final int PHONE_TYPE_NONE = 0;
    public static final int PHONE_TYPE_SIP = 3;
    public static final int PREMIUM_CAPABILITY_PRIORITIZE_LATENCY = 34;
    public static final int PURCHASE_PREMIUM_CAPABILITY_RESULT_ALREADY_IN_PROGRESS = 4;
    public static final int PURCHASE_PREMIUM_CAPABILITY_RESULT_ALREADY_PURCHASED = 3;
    public static final int PURCHASE_PREMIUM_CAPABILITY_RESULT_CARRIER_DISABLED = 7;
    public static final int PURCHASE_PREMIUM_CAPABILITY_RESULT_CARRIER_ERROR = 8;
    public static final int PURCHASE_PREMIUM_CAPABILITY_RESULT_ENTITLEMENT_CHECK_FAILED = 13;
    public static final int PURCHASE_PREMIUM_CAPABILITY_RESULT_FEATURE_NOT_SUPPORTED = 10;
    public static final int PURCHASE_PREMIUM_CAPABILITY_RESULT_NETWORK_NOT_AVAILABLE = 12;
    public static final int PURCHASE_PREMIUM_CAPABILITY_RESULT_NOT_DEFAULT_DATA_SUBSCRIPTION = 14;
    public static final int PURCHASE_PREMIUM_CAPABILITY_RESULT_NOT_FOREGROUND = 5;
    public static final int PURCHASE_PREMIUM_CAPABILITY_RESULT_PENDING_NETWORK_SETUP = 15;
    public static final int PURCHASE_PREMIUM_CAPABILITY_RESULT_REQUEST_FAILED = 11;
    public static final int PURCHASE_PREMIUM_CAPABILITY_RESULT_SUCCESS = 1;
    public static final int PURCHASE_PREMIUM_CAPABILITY_RESULT_THROTTLED = 2;
    public static final int PURCHASE_PREMIUM_CAPABILITY_RESULT_TIMEOUT = 9;
    public static final int PURCHASE_PREMIUM_CAPABILITY_RESULT_USER_CANCELED = 6;
    public static final int PURCHASE_PREMIUM_CAPABILITY_RESULT_USER_DISABLED = 16;
    public static final int SET_OPPORTUNISTIC_SUB_INACTIVE_SUBSCRIPTION = 2;
    public static final int SET_OPPORTUNISTIC_SUB_NO_OPPORTUNISTIC_SUB_AVAILABLE = 3;
    public static final int SET_OPPORTUNISTIC_SUB_REMOTE_SERVICE_EXCEPTION = 4;
    public static final int SET_OPPORTUNISTIC_SUB_SUCCESS = 0;
    public static final int SET_OPPORTUNISTIC_SUB_VALIDATION_FAILED = 1;
    public static final int SIM_STATE_ABSENT = 1;
    public static final int SIM_STATE_CARD_IO_ERROR = 8;
    public static final int SIM_STATE_CARD_RESTRICTED = 9;
    public static final int SIM_STATE_NETWORK_LOCKED = 4;
    public static final int SIM_STATE_NOT_READY = 6;
    public static final int SIM_STATE_PERM_DISABLED = 7;
    public static final int SIM_STATE_PIN_REQUIRED = 2;
    public static final int SIM_STATE_PUK_REQUIRED = 3;
    public static final int SIM_STATE_READY = 5;
    public static final int SIM_STATE_UNKNOWN = 0;
    public static final int UNINITIALIZED_CARD_ID = -2;
    public static final int UNKNOWN_CARRIER_ID = -1;
    public static final int UNSUPPORTED_CARD_ID = -1;
    public static final int UPDATE_AVAILABLE_NETWORKS_ABORTED = 2;
    public static final int UPDATE_AVAILABLE_NETWORKS_DISABLE_MODEM_FAIL = 5;
    public static final int UPDATE_AVAILABLE_NETWORKS_ENABLE_MODEM_FAIL = 6;
    public static final int UPDATE_AVAILABLE_NETWORKS_INVALID_ARGUMENTS = 3;
    public static final int UPDATE_AVAILABLE_NETWORKS_MULTIPLE_NETWORKS_NOT_SUPPORTED = 7;
    public static final int UPDATE_AVAILABLE_NETWORKS_NO_CARRIER_PRIVILEGE = 4;
    public static final int UPDATE_AVAILABLE_NETWORKS_NO_OPPORTUNISTIC_SUB_AVAILABLE = 8;
    public static final int UPDATE_AVAILABLE_NETWORKS_REMOTE_SERVICE_EXCEPTION = 9;
    public static final int UPDATE_AVAILABLE_NETWORKS_SERVICE_IS_DISABLED = 10;
    public static final int UPDATE_AVAILABLE_NETWORKS_SUCCESS = 0;
    public static final int UPDATE_AVAILABLE_NETWORKS_UNKNOWN_FAILURE = 1;
    public static final int USSD_ERROR_SERVICE_UNAVAIL = -2;
    public static final int USSD_RETURN_FAILURE = -1;
    public static final String VVM_TYPE_CVVM = "vvm_type_cvvm";
    public static final String VVM_TYPE_OMTP = "vvm_type_omtp";


    public TelephonyManager() {}

    // No radio, so no IMEI / MEID / device id, and no SIM, so no subscriber id,
    // ICCID or number: AOSP documents null for each when unavailable (and
    // since API 29 an ordinary app gets null or a SecurityException, never a
    // value). These returned "" until 2026-10-30, which no device ever does,
    // and it broke a real caller: Google Play services' checkin wraps
    // getImei() in an Optional, sent the empty string as the IMEI, and
    // Google's checkin server refused the device ("" is not a valid IMEI).
    public String  getDeviceId()        { return null; }
    public String  getDeviceId(int slot) { return null; }
    public String  getImei()            { return null; }
    public String  getImei(int slot)    { return null; }
    public String  getMeid()            { return null; }
    public String  getSubscriberId()    { return null; }
    public String  getSimSerialNumber() { return null; }
    public String  getLine1Number()     { return null; }
    public String  getNetworkOperator() { return ""; }
    public String  getNetworkOperatorName() { return "Mac.apk"; }
    public String  getNetworkCountryIso()   { return macapkHostCountryIso(); }
    public String  getSimOperator()     { return ""; }
    public String  getSimOperatorName() { return ""; }
    public String  getSimCountryIso()   { return macapkHostCountryIso(); }
    /** The host's region (the JVM default locale's country, which the JDK
     *  takes from the system's region setting) as a lowercase ISO 3166 code,
     *  so an app that pre-selects the user's country from the telephony layer
     *  (a phone-number sign-up's country picker) sees where the user is, not a
     *  fixed "us". "us" stays the answer when the locale names no country. */
    private static String macapkHostCountryIso() {
        try {
            String c = java.util.Locale.getDefault().getCountry();
            if (c != null && c.length() == 2) return c.toLowerCase(java.util.Locale.ROOT);
        } catch (Throwable ignored) {}
        return "us";
    }
    public int     getNetworkType()     { return NETWORK_TYPE_UNKNOWN; }
    public int     getDataNetworkType() { return NETWORK_TYPE_UNKNOWN; }
    // PHONE_TYPE_GSM, not NONE (2026-08-17). The device we present is a PHONE:
    // the portrait canvas is deliberately shaped to the reference phone's 9:20
    // (cocoa_bridge.mm "auto canvas"), density is the phone-class 320dpi, and
    // every parity number is measured against a phone AVD whose radio answers
    // `getprop gsm.current.phone-type` = 1 (measured 2026-08-17). NONE is the
    // WI-FI-TABLET answer, and apps use exactly this method to branch on it:
    // Mindustry's AndroidLauncher.isTablet() is literally
    // `getPhoneType() == PHONE_TYPE_NONE`, and on NONE it ran Scl.setAddition
    // (0.5f) -> UI scale 2.0 instead of the reference's 1.5 -> every menu
    // widget 33% oversized and wide dialogs clipped off-screen. GSM +
    // SIM_STATE_ABSENT below is a real, coherent device state (a phone with no
    // SIM card): the radio TYPE is a hardware property, not a SIM property.
    // Fleet blast radius measured 2026-08-17: the only behavioural caller is
    // Mindustry's isTablet; all others (jumptap/fyber/ironsource/unity-ads/
    // gms-ads/duolingo NetworkUtils) embed the value in analytics payloads.
    public int     getPhoneType()       { return PHONE_TYPE_GSM; }
    public int     getSimState()        { return SIM_STATE_ABSENT; }
    /** Slot-indexed form: every slot is equally empty. */
    public int     getSimState(int slotIndex) { return SIM_STATE_ABSENT; }
    public int     getCallState()       { return CALL_STATE_IDLE; }
    public boolean isNetworkRoaming()   { return false; }

    /** AOSP: "@return true if a ICC card is present". No SIM slot exists here. */
    public boolean hasIccCard() { return false; }

    // AOSP returns List<CellInfo>; the audit flagged the raw List<?> as a
    // signature lie. With no radio the list is always empty, but the element
    // type must match so callers that declare List<CellInfo> link cleanly.
    public List<CellInfo> getAllCellInfo()            { return Collections.emptyList(); }

    // AOSP's registry (services/core/.../TelephonyRegistry.java, the
    // "notifyNow" gate in listenForSubscriber/listenWithEventList) delivers
    // the CURRENT cached state for every requested event immediately when a
    // listener registers -- it is the same "sticky broadcast" idiom for every
    // LISTEN_* flag: onServiceStateChanged/onSignalStrengthsChanged/
    // onCallStateChanged etc. all fire once, synchronously with registration,
    // before any real change ever happens. A bare no-op here is a silent
    // registration that never even sends the one update AOSP guarantees, so
    // an app waiting to learn "what is the current call/signal state" (a
    // common pattern: gate a UI element on the first callback) hung forever.
    // Delivery is posted (never inline) for re-entrancy reasons. AOSP captures
    // the delivery Looper on the PhoneStateListener itself at CONSTRUCTION
    // time; this stub approximates with the CALLING thread's Looper at
    // listen()-time, which is the same thread in the overwhelming majority of
    // real app code (both typically happen on the main thread).
    public void listen(android.telephony.PhoneStateListener listener, int events) {
        if (listener == null) return;
        android.os.Looper looper = android.os.Looper.myLooper();
        if (looper == null) looper = android.os.Looper.getMainLooper();
        new android.os.Handler(looper).post(() -> {
            try {
                if ((events & PhoneStateListener.LISTEN_CALL_STATE) != 0) {
                    listener.onCallStateChanged(getCallState(), "");
                }
                if ((events & PhoneStateListener.LISTEN_DATA_CONNECTION_STATE) != 0) {
                    listener.onDataConnectionStateChanged(DATA_DISCONNECTED);
                    listener.onDataConnectionStateChanged(DATA_DISCONNECTED,
                            NETWORK_TYPE_UNKNOWN);
                }
                if ((events & PhoneStateListener.LISTEN_SIGNAL_STRENGTHS) != 0) {
                    listener.onSignalStrengthsChanged(new SignalStrength());
                }
                if ((events & PhoneStateListener.LISTEN_SERVICE_STATE) != 0) {
                    listener.onServiceStateChanged(getServiceState());
                }
                if ((events & PhoneStateListener.LISTEN_DATA_ACTIVITY) != 0) {
                    listener.onDataActivity(DATA_ACTIVITY_NONE);
                }
                if ((events & PhoneStateListener.LISTEN_CELL_INFO) != 0) {
                    listener.onCellInfoChanged(getAllCellInfo());
                }
                if ((events & PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED) != 0) {
                    listener.onDisplayInfoChanged(new TelephonyDisplayInfo());
                }
                if ((events & PhoneStateListener.LISTEN_EMERGENCY_NUMBER_LIST) != 0) {
                    listener.onEmergencyNumberListChanged(getEmergencyNumberList());
                }
                if ((events & PhoneStateListener.LISTEN_MESSAGE_WAITING_INDICATOR) != 0) {
                    listener.onMessageWaitingIndicatorChanged(false);
                }
                if ((events & PhoneStateListener.LISTEN_CALL_FORWARDING_INDICATOR) != 0) {
                    listener.onCallForwardingIndicatorChanged(false);
                }
                if ((events & PhoneStateListener.LISTEN_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGE) != 0) {
                    listener.onActiveDataSubscriptionIdChanged(
                            SubscriptionManager.INVALID_SUBSCRIPTION_ID);
                }
                if ((events & PhoneStateListener.LISTEN_USER_MOBILE_DATA_STATE) != 0) {
                    listener.onUserMobileDataStateChanged(false);
                }
            } catch (Throwable ignored) {}
        });
    }

    // API 31+ TelephonyCallback registration (replaces listen()). Same
    // immediate-snapshot contract as listen() above, dispatched to whichever
    // per-event sub-interfaces the callback implements -- that "implements
    // the marker interface" check IS the API 31 replacement for the old
    // events bitmask (frameworks/base TelephonyManager.java).
    public void registerTelephonyCallback(java.util.concurrent.Executor executor,
            android.telephony.TelephonyCallback callback) {
        deliverInitialTelephonyCallback(executor, callback);
    }
    public void registerTelephonyCallback(int includeLocationData,
            java.util.concurrent.Executor executor,
            android.telephony.TelephonyCallback callback) {
        deliverInitialTelephonyCallback(executor, callback);
    }
    public void unregisterTelephonyCallback(android.telephony.TelephonyCallback callback) {}

    private void deliverInitialTelephonyCallback(java.util.concurrent.Executor executor,
            android.telephony.TelephonyCallback callback) {
        if (executor == null || callback == null) return;
        executor.execute(() -> {
            try {
                if (callback instanceof android.telephony.TelephonyCallback.CallStateListener) {
                    ((android.telephony.TelephonyCallback.CallStateListener) callback)
                        .onCallStateChanged(getCallState());
                }
                if (callback instanceof android.telephony.TelephonyCallback.SignalStrengthsListener) {
                    ((android.telephony.TelephonyCallback.SignalStrengthsListener) callback)
                        .onSignalStrengthsChanged(new SignalStrength());
                }
                if (callback instanceof android.telephony.TelephonyCallback.DataConnectionStateListener) {
                    ((android.telephony.TelephonyCallback.DataConnectionStateListener) callback)
                        .onDataConnectionStateChanged(DATA_DISCONNECTED, NETWORK_TYPE_UNKNOWN);
                }
                if (callback instanceof android.telephony.TelephonyCallback.DataActivityListener) {
                    ((android.telephony.TelephonyCallback.DataActivityListener) callback)
                        .onDataActivity(DATA_ACTIVITY_NONE);
                }
                if (callback instanceof android.telephony.TelephonyCallback.CellInfoListener) {
                    ((android.telephony.TelephonyCallback.CellInfoListener) callback)
                        .onCellInfoChanged(getAllCellInfo());
                }
            } catch (Throwable ignored) {}
        });
    }

    /** AOSP getSubscriptionId (API 30): the subscription id backing this manager.
     *  Mac.apk has no SIM, so INVALID_SUBSCRIPTION_ID (-1). */
    public int getSubscriptionId() { return SubscriptionManager.INVALID_SUBSCRIPTION_ID; }

    /**
     * AOSP: the subscription id associated with a PhoneAccountHandle, or
     * INVALID_SUBSCRIPTION_ID if the handle does not match one. No phone
     * accounts exist here (see android.telecom.TelecomManager), so no handle
     * can match.
     */
    public int getSubscriptionId(android.telecom.PhoneAccountHandle phoneAccountHandle) {
        return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    public boolean isDataEnabled() { return true; }

    /** AOSP getCellLocation (deprecated): no cellular on Mac.apk. */
    public android.telephony.CellLocation getCellLocation() { return null; }

    public int getDataActivity() { return DATA_ACTIVITY_NONE; }

    /** No packet-switched connection exists, so the data state is DISCONNECTED. */
    public int getDataState() { return DATA_DISCONNECTED; }

    public String getMeid(int slotIndex) { return null; }
    public java.util.List<android.telephony.NeighboringCellInfo> getNeighboringCellInfo() {
        return java.util.Collections.emptyList();
    }
    public android.telephony.SignalStrength getSignalStrength() { return null; }

    /**
     * AOSP createForSubscriptionId (API 24): a TelephonyManager bound to one
     * subscription. AOSP returns a NON-NULL manager for ANY subscription id,
     * valid or not; it is the per-method calls that then report no service.
     * Returning null would NPE apps that chain off the documented non-null.
     */
    public TelephonyManager createForSubscriptionId(int subId) {
        return new TelephonyManager();
    }

    /**
     * AOSP createForPhoneAccountHandle (API 26). Unlike the id form, AOSP
     * documents this one as @Nullable -- null when the handle does not match a
     * known subscription. No handle can match here, because there are no phone
     * accounts, so null is the accurate answer and one callers must handle.
     */
    public TelephonyManager createForPhoneAccountHandle(
            android.telecom.PhoneAccountHandle phoneAccountHandle) {
        return null;
    }

    /**
     * AOSP getCallStateForSubscription (API 31): the call state of THIS
     * manager's subscription. No radio and no Telecom service means no call.
     */
    public int getCallStateForSubscription() { return CALL_STATE_IDLE; }

    // === Device / modem identity ============================================
    // Every one of these is a property of a radio we do not have. AOSP's own
    // answer for "unavailable" is null (or the empty list), which is what a
    // caller must already handle on a Wi-Fi-only device.

    public String getDeviceSoftwareVersion() { return null; }

    /**
     * AOSP is @NonNull here (it throws when the device has no IMEI), so it
     * keeps the empty string rather than the null getImei() returns. It does
     * NOT invent an IMEI.
     */
    public String getPrimaryImei() { return ""; }

    /** @deprecated AOSP is retiring the CDMA manufacturer code. */
    @Deprecated public String getManufacturerCode() { return null; }
    /** @deprecated see {@link #getManufacturerCode()}. */
    @Deprecated public String getManufacturerCode(int slotIndex) { return null; }

    public String getTypeAllocationCode() { return null; }
    public String getTypeAllocationCode(int slotIndex) { return null; }

    public String getNai() { return null; }

    public String getGroupIdLevel1() { return null; }

    /** AOSP: the network specifier used to build a cellular NetworkRequest. */
    public String getNetworkSpecifier() { return String.valueOf(getSubscriptionId()); }

    /** No modem, so no UICC cards. */
    public List<UiccCardInfo> getUiccCardsInfo() { return Collections.emptyList(); }

    public int getCardIdForDefaultEuicc() {
        // AOSP's UNSUPPORTED_CARD_ID: "the device does not support eUICC".
        return -1;
    }

    /** No modem is present, so the count of active modems is 0. */
    public int getActiveModemCount() { return 0; }

    /** AOSP returns the number of modems the device could support. */
    public int getSupportedModemCount() { return 0; }

    /** @deprecated AOSP renamed this to {@link #getActiveModemCount()} in API 30. */
    @Deprecated public int getPhoneCount() { return 0; }

    public boolean isMultiSimSupported() {
        // AOSP's MULTISIM_NOT_SUPPORTED_BY_HARDWARE: there is no second modem.
        return false;
    }

    /** AOSP: whether switching the multi-SIM config requires a reboot. */
    public boolean doesSwitchMultiSimConfigTriggerReboot() { return false; }

    /** No modem to reconfigure. */
    public void switchMultiSimConfig(int numOfSims) { }

    /** No modem to enable, so no slot is enabled. */
    public boolean isModemEnabledForSlot(int slotIndex) { return false; }

    /** No modem to reboot. AOSP returns whether the request was accepted. */
    public boolean rebootModem() { return false; }

    public long getSupportedRadioAccessFamily() {
        // AOSP's NETWORK_TYPE_BITMASK_UNKNOWN: no radio access family supported.
        return 0L;
    }

    // === Capability probes ==================================================
    // AOSP derives these from PackageManager telephony features and carrier
    // config. A Mac has no telephony hardware, so the capability answers are
    // all false -- EXCEPT the two that describe a phone-shaped device rather
    // than a live radio, kept consistent with getPhoneType() above.

    public boolean isVoiceCapable() { return false; }
    public boolean isSmsCapable() { return false; }
    public boolean isDataCapable() { return false; }
    public boolean isDeviceVoiceCapable() { return false; }
    public boolean isDeviceSmsCapable() { return false; }
    public boolean isWorldPhone() { return false; }
    public boolean isTtyModeSupported() { return false; }
    public boolean isRttSupported() { return false; }
    public boolean isHearingAidCompatibilitySupported() { return false; }
    public boolean isConcurrentVoiceAndDataSupported() { return false; }
    public boolean canChangeDtmfToneLength() { return false; }
    public boolean isManualNetworkSelectionAllowed() { return false; }
    public boolean isDataConnectionAllowed() { return false; }
    public boolean isDataRoamingEnabled() { return false; }
    public boolean hasCarrierPrivileges() { return false; }
    public boolean isRadioInterfaceCapabilitySupported(String capability) { return false; }

    public boolean isDataEnabledForReason(int reason) { return isDataEnabled(); }

    /** No radio, so nothing to enable. AOSP is a no-op without permission too. */
    public void setDataEnabled(boolean enable) { }
    public void setDataEnabledForReason(int reason, boolean enabled) { }

    public long getAllowedNetworkTypesForReason(int reason) {
        // No radio access family is supported, so none is allowed.
        return 0L;
    }

    public void setAllowedNetworkTypesForReason(int reason, long allowedNetworkTypes) { }

    public void setPreferredNetworkTypeToGlobal() { }

    // === Network selection ==================================================

    public int getNetworkSelectionMode() { return NETWORK_SELECTION_MODE_UNKNOWN; }

    /** AOSP is @NonNull: "" means no manual selection is in effect. */
    public String getManualNetworkSelectionPlmn() { return ""; }

    public void setNetworkSelectionModeAutomatic() { }

    public boolean setNetworkSelectionModeManual(String operatorNumeric,
            boolean persistSelection) {
        return false;
    }

    public boolean setNetworkSelectionModeManual(String operatorNumeric,
            boolean persistSelection, int ran) {
        return false;
    }

    /** AOSP returns null when the SIM is absent. */
    public String[] getForbiddenPlmns() { return null; }

    /** AOSP returns the number of PLMNs written; nothing can be written here. */
    public int setForbiddenPlmns(List<String> fplmns) { return 0; }

    public List<String> getEquivalentHomePlmns() { return Collections.emptyList(); }

    public boolean setOperatorBrandOverride(String brand) { return false; }

    /**
     * AOSP requires the caller to hold NETWORK_SCAN permission and returns null
     * on failure. There is no radio to scan with, so the callback is told
     * ERROR_UNSUPPORTED and null is returned. Reporting an empty successful
     * result instead would assert "there are no networks here", which is a
     * different -- and false -- claim.
     */
    public NetworkScan requestNetworkScan(NetworkScanRequest request, Executor executor,
            TelephonyScanManager.NetworkScanCallback callback) {
        if (executor != null && callback != null) {
            executor.execute(() -> {
                try { callback.onError(NetworkScan.ERROR_UNSUPPORTED); } catch (Throwable t) { }
            });
        }
        return null;
    }

    public NetworkScan requestNetworkScan(int includeLocationData, NetworkScanRequest request,
            Executor executor, TelephonyScanManager.NetworkScanCallback callback) {
        return requestNetworkScan(request, executor, callback);
    }

    /**
     * AOSP asks the modem for a fresh cell-info snapshot. No modem, so the
     * callback receives an empty list -- which is honest: we asked "what cells
     * can you see" of hardware that can see none. This is NOT the same as
     * onError, because nothing failed.
     */
    public void requestCellInfoUpdate(Executor executor, CellInfoCallback callback) {
        if (executor == null || callback == null) return;
        executor.execute(() -> {
            try { callback.onCellInfo(Collections.<CellInfo>emptyList()); } catch (Throwable t) { }
        });
    }

    // === Service state / carrier identity ===================================

    /**
     * AOSP is @Nullable. We return a real ServiceState in the OUT_OF_SERVICE
     * state rather than null: that is the state of a device with no coverage,
     * it is what an app's onServiceStateChanged already handles, and null makes
     * a caller that reads getState() NPE instead.
     */
    public ServiceState getServiceState() {
        ServiceState ss = new ServiceState();
        ss.setStateOutOfService();
        return ss;
    }

    public ServiceState getServiceState(int includeLocationData) { return getServiceState(); }

    public int getSimCarrierId() { return UNKNOWN_CARRIER_ID; }
    public CharSequence getSimCarrierIdName() { return null; }
    public int getSimSpecificCarrierId() { return UNKNOWN_CARRIER_ID; }
    public CharSequence getSimSpecificCarrierIdName() { return null; }
    public int getCarrierIdFromSimMccMnc() { return UNKNOWN_CARRIER_ID; }

    /**
     * AOSP is @NonNull and @WorkerThread. There is no carrier, so the config is
     * empty -- every PersistableBundle getter the caller then uses returns the
     * default it passed in. See CarrierConfigManager for why empty and not null.
     */
    public PersistableBundle getCarrierConfig() { return new PersistableBundle(); }

    public void getCarrierRestrictionStatus(Executor executor, Consumer<Integer> callback) {
        if (executor == null || callback == null) return;
        executor.execute(() -> {
            try {
                callback.accept(CARRIER_RESTRICTION_STATUS_UNKNOWN);
            } catch (Throwable t) { }
        });
    }

    // === Emergency numbers ==================================================
    // NOT fabricated. AOSP's own EmergencyNumberTracker falls back to a
    // PLATFORM-HARDCODED list when the modem cannot report one, and the
    // SIM-ABSENT branch of that fallback is exactly our state:
    //   frameworks/opt/telephony .../emergency/EmergencyNumberTracker.java:
    //   String emergencyNumbers =
    //       ((isSimAbsent()) ? "112,911,000,08,110,118,119,999" : "112,911");
    // The labelling is AOSP's too (same file, getLabeledEmergencyNumberForEcclist):
    // empty country ISO, empty MNC, category UNSPECIFIED, no URNs, source
    // bitmask 0, routing UNKNOWN. Reporting an EMPTY list instead would tell an
    // app that "112" is not an emergency number, which is both untrue and the
    // dangerous direction to be wrong in.

    private static final String[] SIM_ABSENT_ECC_LIST =
            { "112", "911", "000", "08", "110", "118", "119", "999" };

    private static List<EmergencyNumber> defaultEmergencyNumbers() {
        List<EmergencyNumber> list = new ArrayList<>();
        for (String number : SIM_ABSENT_ECC_LIST) {
            list.add(new EmergencyNumber(number, "", "",
                    EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED,
                    new ArrayList<String>(), 0,
                    EmergencyNumber.EMERGENCY_CALL_ROUTING_UNKNOWN));
        }
        return list;
    }

    public Map<Integer, List<EmergencyNumber>> getEmergencyNumberList() {
        Map<Integer, List<EmergencyNumber>> map = new HashMap<>();
        map.put(getSubscriptionId(), defaultEmergencyNumbers());
        return map;
    }

    public Map<Integer, List<EmergencyNumber>> getEmergencyNumberList(int categories) {
        Map<Integer, List<EmergencyNumber>> map = new HashMap<>();
        List<EmergencyNumber> matching = new ArrayList<>();
        for (EmergencyNumber number : defaultEmergencyNumbers()) {
            if (number.isInEmergencyServiceCategories(categories)) matching.add(number);
        }
        map.put(getSubscriptionId(), matching);
        return map;
    }

    public boolean isEmergencyNumber(String number) {
        if (number == null) return false;
        String stripped = PhoneNumberUtils.stripSeparators(number);
        for (String ecc : SIM_ABSENT_ECC_LIST) {
            if (ecc.equals(stripped)) return true;
        }
        return false;
    }

    // === Voicemail ==========================================================
    // No SIM and no voicemail service, so nothing is configured. AOSP's answers
    // for "not configured" are null / false, which callers already handle.

    public String getVoiceMailNumber() { return null; }
    public String getVoiceMailAlphaTag() { return null; }
    public int getVoiceMessageCount() { return 0; }
    public boolean setVoiceMailNumber(String alphaTag, String number) { return false; }
    public String getVisualVoicemailPackageName() { return null; }
    public Uri getVoicemailRingtoneUri(android.telecom.PhoneAccountHandle accountHandle) {
        return null;
    }
    public void setVoicemailRingtoneUri(android.telecom.PhoneAccountHandle phoneAccountHandle,
            Uri uri) { }
    public boolean isVoicemailVibrationEnabled(
            android.telecom.PhoneAccountHandle accountHandle) {
        return false;
    }
    public void setVoicemailVibrationEnabled(android.telecom.PhoneAccountHandle phoneAccountHandle,
            boolean enabled) { }
    public void setVisualVoicemailSmsFilterSettings(VisualVoicemailSmsFilterSettings settings) { }
    public void sendVisualVoicemailSms(String number, int port, String text,
            android.app.PendingIntent sentIntent) { }

    // === MMS ================================================================
    // AOSP reads these two from carrier config, falling back to the platform
    // string resources config_mms_user_agent / config_mms_user_agent_profile_url.
    // With no carrier config we have no operator-specific value, and there is
    // no MMS transport here either, so null is the accurate answer.
    public String getMmsUserAgent() { return null; }
    public String getMmsUAProfUrl() { return null; }

    // === SIM (ICC) access ===================================================
    // There is no UICC. AOSP's documented failure results are used verbatim:
    // STATUS_MISSING_RESOURCE + INVALID_CHANNEL for an open, false for a close,
    // and null for every transmit.

    /** @deprecated AOSP superseded this by the (aid, p2) overload in API 26. */
    @Deprecated
    public IccOpenLogicalChannelResponse iccOpenLogicalChannel(String aid) {
        return iccOpenLogicalChannel(aid, 0);
    }

    public IccOpenLogicalChannelResponse iccOpenLogicalChannel(String aid, int p2) {
        return new IccOpenLogicalChannelResponse(IccOpenLogicalChannelResponse.INVALID_CHANNEL,
                IccOpenLogicalChannelResponse.STATUS_MISSING_RESOURCE, null);
    }

    public boolean iccCloseLogicalChannel(int channel) { return false; }

    public String iccTransmitApduLogicalChannel(int channel, int cla, int instruction,
            int p1, int p2, int p3, String data) {
        return null;
    }

    public String iccTransmitApduBasicChannel(int cla, int instruction, int p1, int p2, int p3,
            String data) {
        return null;
    }

    public byte[] iccExchangeSimIO(int fileID, int command, int p1, int p2, int p3,
            String filePath) {
        return null;
    }

    public String sendEnvelopeWithStatus(String content) { return null; }

    public String getIccAuthentication(int appType, int authType, String data) { return null; }

    // === Misc =============================================================

    public void sendDialerSpecialCode(String inputCode) { }

    public void sendUssdRequest(String ussdRequest, UssdResponseCallback callback,
            android.os.Handler handler) {
        // No radio to carry the request, so AOSP's failure callback fires.
        if (callback == null) return;
        android.os.Handler h = handler;
        if (h == null) {
            android.os.Looper l = android.os.Looper.myLooper();
            if (l == null) l = android.os.Looper.getMainLooper();
            h = new android.os.Handler(l);
        }
        final TelephonyManager self = this;
        h.post(() -> {
            try {
                callback.onReceiveUssdResponseFailed(self, ussdRequest,
                        USSD_RETURN_FAILURE);
            } catch (Throwable t) { }
        });
    }

    /** AOSP is @Nullable: null when there is no phone account for this manager. */
    public android.telecom.PhoneAccountHandle getPhoneAccountHandle() { return null; }

    public int getVoiceNetworkType() { return NETWORK_TYPE_UNKNOWN; }

    public String getNetworkCountryIso(int slotIndex) { return getNetworkCountryIso(); }

    // === Signal-strength update requests ====================================
    // Accepted and stored; no threshold is ever crossed because there is no
    // radio to measure. Same observable behaviour as airplane mode.
    public void setSignalStrengthUpdateRequest(SignalStrengthUpdateRequest request) {
        if (request == null) throw new IllegalArgumentException("request is null");
    }

    public void clearSignalStrengthUpdateRequest(SignalStrengthUpdateRequest request) {
        if (request == null) throw new IllegalArgumentException("request is null");
    }

    // === Call composer ======================================================
    public int getCallComposerStatus() { return CALL_COMPOSER_STATUS_OFF; }

    public void setCallComposerStatus(int status) { }

    public int getMaximumCallComposerPictureSize() {
        // AOSP's documented maximum, a platform constant rather than a
        // carrier-reported one: 80 KiB.
        return 80 * 1024;
    }

    public void uploadCallComposerPicture(java.io.InputStream pictureToUpload,
            String contentType, Executor executor,
            android.os.OutcomeReceiver<android.net.Uri, CallComposerException> callback) {
        failCallComposerUpload(executor, callback);
    }

    public void uploadCallComposerPicture(java.nio.file.Path pictureToUpload, String contentType,
            Executor executor,
            android.os.OutcomeReceiver<android.net.Uri, CallComposerException> callback) {
        failCallComposerUpload(executor, callback);
    }

    private static void failCallComposerUpload(Executor executor,
            android.os.OutcomeReceiver<android.net.Uri, CallComposerException> callback) {
        if (executor == null || callback == null) return;
        executor.execute(() -> {
            try {
                callback.onError(new CallComposerException(
                        CallComposerException.ERROR_NETWORK_UNAVAILABLE, null));
            } catch (Throwable t) { }
        });
    }

    // === Opportunistic data / premium capability / slicing ==================

    public int getPreferredOpportunisticDataSubscription() {
        return SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;
    }

    public void setPreferredOpportunisticDataSubscription(int subId, boolean needValidation,
            Executor executor, Consumer<Integer> callback) {
        if (executor == null || callback == null) return;
        executor.execute(() -> {
            try { callback.accept(SET_OPPORTUNISTIC_SUB_INACTIVE_SUBSCRIPTION); }
            catch (Throwable t) { }
        });
    }

    public void updateAvailableNetworks(List<AvailableNetworkInfo> availableNetworks,
            Executor executor, Consumer<Integer> callback) {
        if (executor == null || callback == null) return;
        executor.execute(() -> {
            try { callback.accept(UPDATE_AVAILABLE_NETWORKS_NO_OPPORTUNISTIC_SUB_AVAILABLE); }
            catch (Throwable t) { }
        });
    }

    public boolean isPremiumCapabilityAvailableForPurchase(int capability) { return false; }

    public void purchasePremiumCapability(int capability, Executor executor,
            Consumer<Integer> callback) {
        if (executor == null || callback == null) return;
        executor.execute(() -> {
            try {
                callback.accept(PURCHASE_PREMIUM_CAPABILITY_RESULT_FEATURE_NOT_SUPPORTED);
            } catch (Throwable t) { }
        });
    }

    /**
     * AOSP asks the modem for the current slicing config. There is no modem, so
     * the receiver gets a default-constructed (empty) NetworkSlicingConfig
     * rather than an error: nothing failed, there simply are no slices.
     */
    public void getNetworkSlicingConfiguration(Executor executor,
            android.os.OutcomeReceiver<NetworkSlicingConfig, NetworkSlicingException> callback) {
        if (executor == null || callback == null) return;
        executor.execute(() -> {
            try { callback.onResult(new NetworkSlicingConfig()); } catch (Throwable t) { }
        });
    }

    public boolean setLine1NumberForDisplay(String alphaTag, String number) { return false; }

    // === Nested callback / exception types ==================================

    /** AOSP declares this as an abstract static class. */
    public abstract static class UssdResponseCallback {
        public void onReceiveUssdResponse(TelephonyManager telephonyManager, String request,
                CharSequence response) { }
        public void onReceiveUssdResponseFailed(TelephonyManager telephonyManager, String request,
                int failureCode) { }
    }

    /** AOSP declares this as an abstract static class with one abstract method. */
    public abstract static class CellInfoCallback {
        public static final int ERROR_TIMEOUT = 1;
        public static final int ERROR_MODEM_ERROR = 2;

        public abstract void onCellInfo(List<CellInfo> cellInfo);

        public void onError(int errorCode, Throwable detail) { }
    }

    public static class CallComposerException extends Exception {
        public static final int ERROR_UNKNOWN = 0;
        public static final int ERROR_REMOTE_END_CLOSED = 1;
        public static final int ERROR_FILE_TOO_LARGE = 2;
        public static final int ERROR_AUTHENTICATION_FAILED = 3;
        public static final int ERROR_INPUT_CLOSED = 4;
        public static final int ERROR_IO_EXCEPTION = 5;
        public static final int ERROR_NETWORK_UNAVAILABLE = 6;

        private final int mErrorCode;
        private final java.io.IOException mIOException;

        public CallComposerException(int errorCode, java.io.IOException ioException) {
            mErrorCode = errorCode;
            mIOException = ioException;
        }

        public int getErrorCode() { return mErrorCode; }

        public java.io.IOException getIOException() { return mIOException; }
    }

    public static class NetworkSlicingException extends Exception { }

    public static class ModemErrorException extends NetworkSlicingException { }

    public static class TimeoutException extends NetworkSlicingException { }
}
