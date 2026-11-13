// android.telecom.Connection -- one call owned by a ConnectionService.
//
// WHY THIS IS A REAL LOCAL STATE MACHINE, NOT A STUB. A Connection is an object
// the APP subclasses and drives: it calls setActive()/setRinging()/
// setDisconnected() and then reads getState()/getDisconnectCause() back on its
// own code paths. That bookkeeping is not a device capability -- it is
// ordinary object state, and AOSP keeps it in exactly these fields. So the
// setters really do move the state machine here, and the getters really do
// report it. An app that calls setActive() and asserts getState() ==
// STATE_ACTIVE behaves the same on Mac.apk as on Android.
//
// WHAT IS HONESTLY ABSENT: the half that talks to the Telecom SERVICE. On
// Android each setter also notifies Telecom, which drives the system in-call
// UI, audio routing and the call log. A Mac has no Telecom service, so:
//   * there are no listeners to notify (AOSP notifies a listener set that is
//     empty until Telecom binds the ConnectionService -- so an empty set is a
//     state AOSP itself passes through, not an invented one),
//   * getCallAudioState() returns null until Telecom pushes a state in, which
//     it never does here. AOSP also returns null before that push, and the
//     method is @Deprecated on AOSP for exactly this unreliability.
//   * onAnswer/onDisconnect/onHold and the other on* callbacks are inbound
//     from Telecom, so they are never invoked. They exist to be OVERRIDDEN --
//     which is the whole reason the class must link. The one exception is
//     onShowIncomingCallUi(): for a SELF-MANAGED incoming call delivered
//     through SelfManagedTelecom, it fires once, right after
//     onCreateIncomingConnection returns, as ConnectionService.createConnection
//     does on a device -- that request involves only the app.
//
// A connection SelfManagedTelecom delivered is "attached" to its
// ConnectionService: it is listed in getAllConnections() until destroy(), as
// AOSP keeps it until the onDestroyed listener removes it.
//
// Constants and public member list taken from
// frameworks/base/core/api/current.txt (the authoritative public-API list),
// with behaviour from telecomm/java/android/telecom/Connection.java. The
// AUDIO_CODEC_* values come from current.txt rather than the source because the
// source defines them via ImsStreamMediaProfile, a telephony class we do not
// have; current.txt carries the resolved literals.
//
// THE CONFERENCE / VIDEO / LOCATION HALVES, added when the package was
// completed: getConference()/setConference is real local bookkeeping (Conference
// installs the back-pointer, see Conference.addConnection). getVideoProvider()/
// setVideoProvider() store the provider the app installs and hand it straight
// back -- what is absent is a cellular video-call path to DRIVE it, so none of
// its onSet*/onRequest* callbacks ever fires. queryLocationForEmergency() and
// requestCallEndpointChange() are the two that must NOT succeed inertly: both
// take an OutcomeReceiver, so an app that got no callback would wait forever.
// They report AOSP's own documented error for this device instead -- see each
// method.
package android.telecom;

import android.bluetooth.BluetoothDevice;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.view.Surface;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

public abstract class Connection extends Conferenceable {

    public static final int STATE_INITIALIZING = 0;
    public static final int STATE_NEW = 1;
    public static final int STATE_RINGING = 2;
    public static final int STATE_DIALING = 3;
    public static final int STATE_ACTIVE = 4;
    public static final int STATE_HOLDING = 5;
    public static final int STATE_DISCONNECTED = 6;
    public static final int STATE_PULLING_CALL = 7;

    public static final int VERIFICATION_STATUS_NOT_VERIFIED = 0;
    public static final int VERIFICATION_STATUS_PASSED = 1;
    public static final int VERIFICATION_STATUS_FAILED = 2;

    public static final int CAPABILITY_HOLD = 0x00000001;
    public static final int CAPABILITY_SUPPORT_HOLD = 0x00000002;
    public static final int CAPABILITY_MERGE_CONFERENCE = 0x00000004;
    public static final int CAPABILITY_SWAP_CONFERENCE = 0x00000008;
    public static final int CAPABILITY_RESPOND_VIA_TEXT = 0x00000020;
    public static final int CAPABILITY_MUTE = 0x00000040;
    public static final int CAPABILITY_MANAGE_CONFERENCE = 0x00000080;
    public static final int CAPABILITY_SUPPORTS_VT_LOCAL_RX = 0x00000100;
    public static final int CAPABILITY_SUPPORTS_VT_LOCAL_TX = 0x00000200;
    public static final int CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL =
            CAPABILITY_SUPPORTS_VT_LOCAL_RX | CAPABILITY_SUPPORTS_VT_LOCAL_TX;
    public static final int CAPABILITY_SUPPORTS_VT_REMOTE_RX = 0x00000400;
    public static final int CAPABILITY_SUPPORTS_VT_REMOTE_TX = 0x00000800;
    public static final int CAPABILITY_SUPPORTS_VT_REMOTE_BIDIRECTIONAL =
            CAPABILITY_SUPPORTS_VT_REMOTE_RX | CAPABILITY_SUPPORTS_VT_REMOTE_TX;
    public static final int CAPABILITY_SEPARATE_FROM_CONFERENCE = 0x00001000;
    public static final int CAPABILITY_DISCONNECT_FROM_CONFERENCE = 0x00002000;
    /** @deprecated on AOSP. */
    @Deprecated
    public static final int CAPABILITY_CAN_UPGRADE_TO_VIDEO = 0x00080000;
    public static final int CAPABILITY_CAN_PAUSE_VIDEO = 0x00100000;
    public static final int CAPABILITY_CAN_SEND_RESPONSE_VIA_CONNECTION = 0x00400000;
    public static final int CAPABILITY_CANNOT_DOWNGRADE_VIDEO_TO_AUDIO = 0x00800000;
    public static final int CAPABILITY_CAN_PULL_CALL = 0x01000000;
    public static final int CAPABILITY_SUPPORT_DEFLECT = 0x02000000;
    public static final int CAPABILITY_ADD_PARTICIPANT = 0x04000000;
    public static final int CAPABILITY_REMOTE_PARTY_SUPPORTS_RTT = 0x20000000;

    public static final int PROPERTY_HIGH_DEF_AUDIO = 1 << 2;
    public static final int PROPERTY_WIFI = 1 << 3;
    public static final int PROPERTY_IS_EXTERNAL_CALL = 1 << 4;
    public static final int PROPERTY_HAS_CDMA_VOICE_PRIVACY = 1 << 5;
    public static final int PROPERTY_SELF_MANAGED = 1 << 7;
    public static final int PROPERTY_IS_RTT = 1 << 8;
    public static final int PROPERTY_ASSISTED_DIALING = 1 << 9;
    public static final int PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL = 1 << 10;
    public static final int PROPERTY_IS_ADHOC_CONFERENCE = 1 << 12;
    public static final int PROPERTY_CROSS_SIM = 1 << 13;

    public static final int AUDIO_CODEC_NONE = 0;
    public static final int AUDIO_CODEC_AMR = 1;
    public static final int AUDIO_CODEC_AMR_WB = 2;
    public static final int AUDIO_CODEC_QCELP13K = 3;
    public static final int AUDIO_CODEC_EVRC = 4;
    public static final int AUDIO_CODEC_EVRC_B = 5;
    public static final int AUDIO_CODEC_EVRC_WB = 6;
    public static final int AUDIO_CODEC_EVRC_NW = 7;
    public static final int AUDIO_CODEC_GSM_EFR = 8;
    public static final int AUDIO_CODEC_GSM_FR = 9;
    public static final int AUDIO_CODEC_GSM_HR = 10;
    public static final int AUDIO_CODEC_G711U = 11;
    public static final int AUDIO_CODEC_G723 = 12;
    public static final int AUDIO_CODEC_G711A = 13;
    public static final int AUDIO_CODEC_G722 = 14;
    public static final int AUDIO_CODEC_G711AB = 15;
    public static final int AUDIO_CODEC_G729 = 16;
    public static final int AUDIO_CODEC_EVS_NB = 17;
    public static final int AUDIO_CODEC_EVS_WB = 18;
    public static final int AUDIO_CODEC_EVS_SWB = 19;
    public static final int AUDIO_CODEC_EVS_FB = 20;

    public static final String EXTRA_LAST_FORWARDED_NUMBER =
            "android.telecom.extra.LAST_FORWARDED_NUMBER";
    public static final String EXTRA_CHILD_ADDRESS = "android.telecom.extra.CHILD_ADDRESS";
    public static final String EXTRA_CALL_SUBJECT = "android.telecom.extra.CALL_SUBJECT";
    public static final String EXTRA_ANSWERING_DROPS_FG_CALL =
            "android.telecom.extra.ANSWERING_DROPS_FG_CALL";
    public static final String EXTRA_ANSWERING_DROPS_FG_CALL_APP_NAME =
            "android.telecom.extra.ANSWERING_DROPS_FG_CALL_APP_NAME";
    public static final String EXTRA_AUDIO_CODEC = "android.telecom.extra.AUDIO_CODEC";
    public static final String EXTRA_AUDIO_CODEC_BANDWIDTH_KHZ =
            "android.telecom.extra.AUDIO_CODEC_BANDWIDTH_KHZ";
    public static final String EXTRA_AUDIO_CODEC_BITRATE_KBPS =
            "android.telecom.extra.AUDIO_CODEC_BITRATE_KBPS";
    public static final String EXTRA_IS_RTT_AUDIO_PRESENT =
            "android.telecom.extra.IS_RTT_AUDIO_PRESENT";
    public static final String EXTRA_LAST_KNOWN_CELL_IDENTITY =
            "android.telecom.extra.LAST_KNOWN_CELL_IDENTITY";
    public static final String EXTRA_SIP_INVITE = "android.telecom.extra.SIP_INVITE";

    public static final String EVENT_ON_HOLD_TONE_START =
            "android.telecom.event.ON_HOLD_TONE_START";
    public static final String EVENT_ON_HOLD_TONE_END = "android.telecom.event.ON_HOLD_TONE_END";
    public static final String EVENT_CALL_PULL_FAILED = "android.telecom.event.CALL_PULL_FAILED";
    public static final String EVENT_CALL_MERGE_FAILED = "android.telecom.event.CALL_MERGE_FAILED";
    public static final String EVENT_CALL_HOLD_FAILED = "android.telecom.event.CALL_HOLD_FAILED";
    public static final String EVENT_CALL_SWITCH_FAILED =
            "android.telecom.event.CALL_SWITCH_FAILED";
    public static final String EVENT_CALL_REMOTELY_HELD =
            "android.telecom.event.CALL_REMOTELY_HELD";
    public static final String EVENT_CALL_REMOTELY_UNHELD =
            "android.telecom.event.CALL_REMOTELY_UNHELD";
    public static final String EVENT_MERGE_START = "android.telecom.event.MERGE_START";
    public static final String EVENT_MERGE_COMPLETE = "android.telecom.event.MERGE_COMPLETE";
    public static final String EVENT_RTT_AUDIO_INDICATION_CHANGED =
            "android.telecom.event.RTT_AUDIO_INDICATION_CHANGED";

    /**
     * The pipe an RTT call reads/writes real-time text over. On Android the two
     * file descriptors come from Telecom; nothing here creates one, so a
     * Connection never receives an RttTextStream. The type exists because
     * ConnectionRequest.getRttTextStream() and onStartRtt() name it.
     */
    public static final class RttTextStream {
        public void write(String input) throws IOException {
        }

        public String read() throws IOException {
            return null;
        }

        public String readImmediately() throws IOException {
            return null;
        }
    }

    /**
     * The status codes an RTT session-modify request can come back with.
     * A constants holder on AOSP too -- no behaviour, so nothing here is
     * device-dependent.
     */
    public static final class RttModifyStatus {
        public static final int SESSION_MODIFY_REQUEST_SUCCESS = 1;
        public static final int SESSION_MODIFY_REQUEST_FAIL = 2;
        public static final int SESSION_MODIFY_REQUEST_INVALID = 3;
        public static final int SESSION_MODIFY_REQUEST_TIMED_OUT = 4;
        public static final int SESSION_MODIFY_REQUEST_REJECTED_BY_REMOTE = 5;

        private RttModifyStatus() {}
    }

    /**
     * The app-side half of a video call: Telecom asks the provider to point a
     * camera at a Surface, and the provider pushes dimension/quality/data-usage
     * changes back.
     *
     * <p>A Mac has no cellular video-call path, so NOTHING EVER DRIVES a
     * provider here: the abstract on* methods below are inbound from Telecom
     * and are never invoked. They are abstract for exactly the reason they are
     * on AOSP -- an app subclasses VideoProvider to receive them, which is why
     * the type must link.
     *
     * <p>The concrete change / receive / set methods are the OUTBOUND half. On
     * AOSP each one notifies Telecom's video callback, which drives the in-call
     * UI. With no Telecom service there is no callback registered -- AOSP's own
     * implementation no-ops in that state too (it null-checks mVideoCallbacks
     * before dispatching), so this is a state AOSP passes through rather than
     * one we invented.
     */
    public abstract static class VideoProvider {

        public static final int SESSION_MODIFY_REQUEST_SUCCESS = 1;
        public static final int SESSION_MODIFY_REQUEST_FAIL = 2;
        public static final int SESSION_MODIFY_REQUEST_INVALID = 3;
        public static final int SESSION_MODIFY_REQUEST_TIMED_OUT = 4;
        public static final int SESSION_MODIFY_REQUEST_REJECTED_BY_REMOTE = 5;

        public static final int SESSION_EVENT_RX_PAUSE = 1;
        public static final int SESSION_EVENT_RX_RESUME = 2;
        public static final int SESSION_EVENT_TX_START = 3;
        public static final int SESSION_EVENT_TX_STOP = 4;
        public static final int SESSION_EVENT_CAMERA_FAILURE = 5;
        public static final int SESSION_EVENT_CAMERA_READY = 6;
        public static final int SESSION_EVENT_CAMERA_PERMISSION_ERROR = 7;

        public VideoProvider() {
        }

        // Inbound from Telecom. Never invoked here; abstract so an app's
        // subclass declares them, which is the point of the type.

        public abstract void onSetCamera(String cameraId);

        public abstract void onSetPreviewSurface(Surface surface);

        public abstract void onSetDisplaySurface(Surface surface);

        public abstract void onSetDeviceOrientation(int rotation);

        public abstract void onSetZoom(float value);

        public abstract void onSendSessionModifyRequest(VideoProfile fromProfile,
                VideoProfile toProfile);

        public abstract void onSendSessionModifyResponse(VideoProfile responseProfile);

        public abstract void onRequestCameraCapabilities();

        public abstract void onRequestConnectionDataUsage();

        public abstract void onSetPauseImage(Uri uri);

        // Outbound to Telecom's video callback. No callback is registered, and
        // AOSP no-ops in that state too.

        public void receiveSessionModifyRequest(VideoProfile videoProfile) {
        }

        public void receiveSessionModifyResponse(int status, VideoProfile requestedProfile,
                VideoProfile responseProfile) {
        }

        public void handleCallSessionEvent(int event) {
        }

        public void changePeerDimensions(int width, int height) {
        }

        public void setCallDataUsage(long dataUsage) {
        }

        public void changeCameraCapabilities(VideoProfile.CameraCapabilities capabilities) {
        }

        public void changeVideoQuality(int videoQuality) {
        }
    }

    private int mState = STATE_NEW;
    private Uri mAddress;
    private int mAddressPresentation;
    private String mCallerDisplayName;
    private int mCallerDisplayNamePresentation;
    private boolean mRingbackRequested = false;
    private boolean mAudioModeIsVoip = false;
    private StatusHints mStatusHints;
    private int mVideoState;
    private DisconnectCause mDisconnectCause;
    private int mConnectionCapabilities;
    private int mConnectionProperties;
    private int mSupportedAudioRoutes = CallAudioState.ROUTE_WIRED_OR_EARPIECE
            | CallAudioState.ROUTE_SPEAKER | CallAudioState.ROUTE_BLUETOOTH;
    private final List<Conferenceable> mConferenceables = new ArrayList<>();
    private final List<Conferenceable> mUnmodifiableConferenceables =
            Collections.unmodifiableList(mConferenceables);
    private Bundle mExtras;
    private int mCallerNumberVerificationStatus;
    private CallEndpoint mCallEndpoint;
    private Conference mConference;
    private VideoProvider mVideoProvider;

    public Connection() {
    }

    public final Uri getAddress() {
        return mAddress;
    }

    public final int getAddressPresentation() {
        return mAddressPresentation;
    }

    public final String getCallerDisplayName() {
        return mCallerDisplayName;
    }

    public final int getCallerDisplayNamePresentation() {
        return mCallerDisplayNamePresentation;
    }

    public final int getState() {
        return mState;
    }

    public final int getVideoState() {
        return mVideoState;
    }

    /**
     * @deprecated on AOSP. Telecom pushes the audio state in; with no Telecom
     * service this stays null, which is also what AOSP returns before the push.
     */
    @Deprecated
    public final CallAudioState getCallAudioState() {
        return null;
    }

    /** @see #getCallAudioState() -- no Telecom service means no endpoint. */
    public final CallEndpoint getCurrentCallEndpoint() {
        return mCallEndpoint;
    }

    public final List<Conferenceable> getConferenceables() {
        return mUnmodifiableConferenceables;
    }

    /**
     * The Conference this Connection was merged into, or null. Real local
     * bookkeeping: Conference.addConnection installs the back-pointer and
     * Conference.removeConnection clears it, exactly as AOSP does.
     */
    public final Conference getConference() {
        return mConference;
    }

    /**
     * AOSP (@hide, package-private): accepts the conference only if this
     * connection is not already in one, and reports whether it took. The
     * refusal matters -- Conference.addConnection relies on the false return
     * to keep a connection out of two conferences at once.
     */
    boolean setConference(Conference conference) {
        if (mConference == null) {
            mConference = conference;
            return true;
        }
        return false;
    }

    /** AOSP (@hide, package-private): drops the back-pointer. */
    void resetConference() {
        mConference = null;
    }

    /**
     * The video provider the app installed, or null. A Mac has no cellular
     * video-call path, so nothing ever DRIVES the provider -- but an app that
     * calls setVideoProvider() and reads it straight back gets its own object,
     * which is what AOSP does too.
     */
    public final VideoProvider getVideoProvider() {
        return mVideoProvider;
    }

    public final void setVideoProvider(VideoProvider videoProvider) {
        mVideoProvider = videoProvider;
    }

    public final boolean isRingbackRequested() {
        return mRingbackRequested;
    }

    public final boolean getAudioModeIsVoip() {
        return mAudioModeIsVoip;
    }

    public final StatusHints getStatusHints() {
        return mStatusHints;
    }

    public final Bundle getExtras() {
        return mExtras;
    }

    public final DisconnectCause getDisconnectCause() {
        return mDisconnectCause;
    }

    public final int getConnectionCapabilities() {
        return mConnectionCapabilities;
    }

    public final int getConnectionProperties() {
        return mConnectionProperties;
    }

    public final int getCallerNumberVerificationStatus() {
        return mCallerNumberVerificationStatus;
    }

    public final void setAddress(Uri address, int presentation) {
        mAddress = address;
        mAddressPresentation = presentation;
    }

    public final void setCallerDisplayName(String callerDisplayName, int presentation) {
        mCallerDisplayName = callerDisplayName;
        mCallerDisplayNamePresentation = presentation;
    }

    public final void setVideoState(int videoState) {
        mVideoState = videoState;
    }

    public final void setActive() {
        setState(STATE_ACTIVE);
    }

    public final void setRinging() {
        setState(STATE_RINGING);
    }

    public final void setInitializing() {
        setState(STATE_INITIALIZING);
    }

    public final void setInitialized() {
        setState(STATE_NEW);
    }

    public final void setDialing() {
        setState(STATE_DIALING);
    }

    public final void setPulling() {
        setState(STATE_PULLING_CALL);
    }

    public final void setOnHold() {
        setState(STATE_HOLDING);
    }

    public final void setDisconnected(DisconnectCause disconnectCause) {
        mDisconnectCause = disconnectCause;
        setState(STATE_DISCONNECTED);
    }

    /**
     * AOSP refuses every transition out of STATE_DISCONNECTED and logs instead
     * of throwing. Copied, because an app that disconnects and then races a
     * setActive() must not come back to life.
     */
    private void setState(int state) {
        if (mState == STATE_DISCONNECTED && mState != state) {
            return;
        }
        if (mState != state) {
            mState = state;
            onStateChanged(state);
            // A delivered call that ends gives up Telecom's binding to its
            // ConnectionService (see SelfManagedTelecom.onDisconnected).
            if (state == STATE_DISCONNECTED && mMacapkService != null) {
                SelfManagedTelecom.onDisconnected(this);
            }
        }
    }

    // ---- Self-managed delivery (SelfManagedTelecom) ----

    /** The ConnectionService this connection was delivered for, or null. */
    // Volatile: set on the main thread by SelfManagedTelecom, read by
    // setState() on whatever thread the app disconnects from.
    private volatile ConnectionService mMacapkService;

    void macapkAttach(ConnectionService service) {
        mMacapkService = service;
    }

    public final void setPostDialWait(String remaining) {
    }

    public final void setNextPostDialChar(char nextChar) {
    }

    public final void setRingbackRequested(boolean ringback) {
        mRingbackRequested = ringback;
    }

    public final void setConnectionCapabilities(int connectionCapabilities) {
        mConnectionCapabilities = connectionCapabilities;
    }

    public final void setConnectionProperties(int connectionProperties) {
        mConnectionProperties = connectionProperties;
    }

    /** @hide on AOSP but paired with getSupportedAudioRoutes below. */
    public final void setSupportedAudioRoutes(int supportedAudioRoutes) {
        mSupportedAudioRoutes = supportedAudioRoutes;
    }

    /** @hide on AOSP. */
    public final int getSupportedAudioRoutes() {
        return mSupportedAudioRoutes;
    }

    /**
     * AOSP moves the connection to STATE_DISCONNECTED (if it is not already)
     * and notifies listeners so Telecom and the ConnectionService drop it. An
     * attached (self-managed) connection is dropped the same way; otherwise
     * the observable part is the state move, which we keep.
     */
    public final void destroy() {
        if (mState != STATE_DISCONNECTED) {
            setState(STATE_DISCONNECTED);
        }
        ConnectionService service = mMacapkService;
        if (service != null) {
            mMacapkService = null;
            SelfManagedTelecom.onDestroyed(this, service);
        }
    }

    public final void setAudioModeIsVoip(boolean isVoip) {
        mAudioModeIsVoip = isVoip;
    }

    public final void setStatusHints(StatusHints statusHints) {
        mStatusHints = statusHints;
    }

    public final void setConferenceableConnections(List<Connection> conferenceableConnections) {
        mConferenceables.clear();
        mConferenceables.addAll(conferenceableConnections);
    }

    public final void setConferenceables(List<Conferenceable> conferenceables) {
        mConferenceables.clear();
        mConferenceables.addAll(conferenceables);
    }

    public final void setExtras(Bundle extras) {
        mExtras = extras;
    }

    /**
     * AOSP MERGES into the existing extras rather than replacing them, and
     * copies the incoming Bundle so a later caller mutation cannot be seen.
     * Both matter: Signal calls putExtras() more than once on one Connection.
     */
    public final void putExtras(Bundle extras) {
        if (extras == null) {
            return;
        }
        Bundle copy = new Bundle(extras);
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putAll(copy);
    }

    public final void removeExtras(List<String> keys) {
        if (mExtras != null && keys != null) {
            for (String key : keys) {
                mExtras.remove(key);
            }
        }
    }

    public final void removeExtras(String... keys) {
        removeExtras(java.util.Arrays.asList(keys));
    }

    /**
     * @deprecated on AOSP in favour of requestCallEndpointChange. Telecom owns
     * the audio route; with no Telecom service there is no route to change, so
     * this records nothing. Apps treat routing as advisory and re-read the
     * route from onCallAudioStateChanged, which simply never fires.
     */
    @Deprecated
    public final void setAudioRoute(int route) {
    }

    /**
     * @deprecated on AOSP. A Mac routes call audio through CoreAudio, not
     * through Telecom's Bluetooth SCO management, so there is no per-call
     * device to select.
     */
    @Deprecated
    public void requestBluetoothAudio(BluetoothDevice bluetoothDevice) {
    }

    public final void sendRttInitiationSuccess() {
    }

    public final void sendRttInitiationFailure(int reason) {
    }

    public final void sendRttSessionRemotelyTerminated() {
    }

    public final void sendRemoteRttRequest() {
    }

    public final void notifyConferenceMergeFailed() {
    }

    public void sendConnectionEvent(String event, Bundle extras) {
    }

    public final void setCallerNumberVerificationStatus(int callerNumberVerificationStatus) {
        mCallerNumberVerificationStatus = callerNumberVerificationStatus;
    }

    /**
     * AOSP asks the CELLULAR network for the device's location during an
     * EMERGENCY call, and documents that it is "only allowed for emergency
     * connections" -- AOSP itself fails the request with
     * ERROR_NOT_ALLOWED_FOR_NON_EMERGENCY_CONNECTIONS otherwise. No Connection
     * on a Mac is an emergency connection (there is no radio and no Telecom
     * service to mark one), so that code is literally the correct answer here,
     * not an invented one.
     *
     * <p>It must NOT complete inertly: the caller is holding an
     * OutcomeReceiver and would otherwise wait forever. We report the failure
     * through the receiver on the caller's Executor, which is the contract
     * AOSP uses for every failure on this path.
     *
     * <p>AOSP validates its arguments before consulting the network, so the
     * range check and the null checks below run first and throw the same way.
     */
    public final void queryLocationForEmergency(long timeoutMillis, String provider,
            Executor executor, OutcomeReceiver<Location, QueryLocationException> callback) {
        if (timeoutMillis < 100 || timeoutMillis > 5000) {
            throw new IllegalArgumentException("Invalid timeout provided.");
        }
        java.util.Objects.requireNonNull(provider);
        java.util.Objects.requireNonNull(executor);
        java.util.Objects.requireNonNull(callback);
        executor.execute(() -> callback.onError(new QueryLocationException(
                "This connection is not an emergency connection; this device has no "
                        + "cellular radio and no Telecom service.",
                QueryLocationException.ERROR_NOT_ALLOWED_FOR_NON_EMERGENCY_CONNECTIONS)));
    }

    /**
     * Asks Telecom to route this call's audio to a different CallEndpoint.
     * There is no Telecom service, so the requested endpoint does not exist as
     * far as this device is concerned -- ERROR_ENDPOINT_DOES_NOT_EXIST is
     * AOSP's own code for that, and it is the truthful one: we never publish an
     * endpoint (getCurrentCallEndpoint() is null, and
     * onAvailableCallEndpointsChanged never fires).
     *
     * <p>As with queryLocationForEmergency, silently succeeding would leave the
     * caller waiting on an onCallEndpointChanged that cannot arrive, so the
     * failure goes back through the receiver on the caller's Executor.
     */
    public final void requestCallEndpointChange(CallEndpoint endpoint, Executor executor,
            OutcomeReceiver<Void, CallEndpointException> callback) {
        java.util.Objects.requireNonNull(endpoint);
        java.util.Objects.requireNonNull(executor);
        java.util.Objects.requireNonNull(callback);
        executor.execute(() -> callback.onError(new CallEndpointException(
                "This device has no Telecom service, so it publishes no call endpoints.",
                CallEndpointException.ERROR_ENDPOINT_DOES_NOT_EXIST)));
    }

    // Inbound callbacks from Telecom. None of these are invoked here (there is
    // no Telecom service to invoke them); they exist to be overridden, which is
    // why the class had to link in the first place.

    /** @deprecated on AOSP alongside getCallAudioState(). */
    @Deprecated
    public void onCallAudioStateChanged(CallAudioState state) {}

    public void onCallEndpointChanged(CallEndpoint callEndpoint) {}

    public void onAvailableCallEndpointsChanged(List<CallEndpoint> availableEndpoints) {}

    public void onMuteStateChanged(boolean isMuted) {}

    public void onStateChanged(int state) {}

    public void onPlayDtmfTone(char c) {}

    public void onStopDtmfTone() {}

    public void onDisconnect() {}

    public void onSeparate() {}

    public void onAddConferenceParticipants(List<Uri> participants) {}

    public void onAbort() {}

    public void onHold() {}

    public void onUnhold() {}

    public void onAnswer(int videoState) {}

    public void onAnswer() {
        onAnswer(0);
    }

    public void onDeflect(Uri address) {}

    public void onReject() {}

    public void onReject(int rejectReason) {
        onReject();
    }

    public void onReject(String replyMessage) {}

    public void onSilence() {}

    public void onPostDialContinue(boolean proceed) {}

    public void onPullExternalCall() {}

    public void onCallEvent(String event, Bundle extras) {}

    public void onHandoverComplete() {}

    public void onExtrasChanged(Bundle extras) {}

    public void onShowIncomingCallUi() {}

    public void onStartRtt(RttTextStream rttTextStream) {}

    public void onStopRtt() {}

    public void handleRttUpgradeResponse(RttTextStream rttTextStream) {}

    public void onTrackedByNonUiService(boolean isTracked) {}

    public void onUsingAlternativeUi(boolean isUsingAlternativeUi) {}

    /**
     * AOSP returns a Connection already in STATE_DISCONNECTED carrying the
     * given cause, used by a ConnectionService to reject a create request.
     * Real behaviour, no device dependency.
     */
    public static Connection createFailedConnection(DisconnectCause disconnectCause) {
        return new FailureSignalingConnection(disconnectCause);
    }

    /** AOSP's canceled connection is a failed one with a CANCELED cause. */
    public static Connection createCanceledConnection() {
        return new FailureSignalingConnection(new DisconnectCause(DisconnectCause.CANCELED));
    }

    private static final class FailureSignalingConnection extends Connection {
        private FailureSignalingConnection(DisconnectCause disconnectCause) {
            setDisconnected(disconnectCause);
        }
    }

    public static String stateToString(int state) {
        switch (state) {
            case STATE_INITIALIZING: return "INITIALIZING";
            case STATE_NEW: return "NEW";
            case STATE_RINGING: return "RINGING";
            case STATE_DIALING: return "DIALING";
            case STATE_PULLING_CALL: return "PULLING_CALL";
            case STATE_ACTIVE: return "ACTIVE";
            case STATE_HOLDING: return "HOLDING";
            case STATE_DISCONNECTED: return "DISCONNECTED";
            default: return "UNKNOWN (" + state + ")";
        }
    }

    public static String capabilitiesToString(int capabilities) {
        StringBuilder builder = new StringBuilder();
        builder.append("[Capabilities:");
        appendIf(builder, capabilities, CAPABILITY_HOLD, " CAPABILITY_HOLD");
        appendIf(builder, capabilities, CAPABILITY_SUPPORT_HOLD, " CAPABILITY_SUPPORT_HOLD");
        appendIf(builder, capabilities, CAPABILITY_MERGE_CONFERENCE,
                " CAPABILITY_MERGE_CONFERENCE");
        appendIf(builder, capabilities, CAPABILITY_SWAP_CONFERENCE, " CAPABILITY_SWAP_CONFERENCE");
        appendIf(builder, capabilities, CAPABILITY_RESPOND_VIA_TEXT,
                " CAPABILITY_RESPOND_VIA_TEXT");
        appendIf(builder, capabilities, CAPABILITY_MUTE, " CAPABILITY_MUTE");
        appendIf(builder, capabilities, CAPABILITY_MANAGE_CONFERENCE,
                " CAPABILITY_MANAGE_CONFERENCE");
        appendIf(builder, capabilities, CAPABILITY_CAN_PULL_CALL, " CAPABILITY_CAN_PULL_CALL");
        appendIf(builder, capabilities, CAPABILITY_SUPPORT_DEFLECT, " CAPABILITY_SUPPORT_DEFLECT");
        appendIf(builder, capabilities, CAPABILITY_ADD_PARTICIPANT, " CAPABILITY_ADD_PARTICIPANT");
        builder.append("]");
        return builder.toString();
    }

    public static String propertiesToString(int properties) {
        StringBuilder builder = new StringBuilder();
        builder.append("[Properties:");
        appendIf(builder, properties, PROPERTY_SELF_MANAGED, " PROPERTY_SELF_MANAGED");
        appendIf(builder, properties, PROPERTY_HIGH_DEF_AUDIO, " PROPERTY_HIGH_DEF_AUDIO");
        appendIf(builder, properties, PROPERTY_WIFI, " PROPERTY_WIFI");
        appendIf(builder, properties, PROPERTY_IS_EXTERNAL_CALL, " PROPERTY_IS_EXTERNAL_CALL");
        appendIf(builder, properties, PROPERTY_HAS_CDMA_VOICE_PRIVACY,
                " PROPERTY_HAS_CDMA_VOICE_PRIVACY");
        appendIf(builder, properties, PROPERTY_IS_RTT, " PROPERTY_IS_RTT");
        appendIf(builder, properties, PROPERTY_IS_ADHOC_CONFERENCE,
                " PROPERTY_IS_ADHOC_CONFERENCE");
        builder.append("]");
        return builder.toString();
    }

    private static void appendIf(StringBuilder b, int bits, int mask, String name) {
        if ((bits & mask) == mask) {
            b.append(name);
        }
    }
}
