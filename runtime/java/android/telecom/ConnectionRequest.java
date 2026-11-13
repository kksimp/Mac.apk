// android.telecom.ConnectionRequest -- the parameters Telecom hands a
// ConnectionService when asking it to create a Connection.
//
// A REAL value object with no device dependency. It is INBOUND, though: on
// Android, Telecom constructs it and passes it to onCreateOutgoingConnection /
// onCreateIncomingConnection. With no Telecom service those callbacks never
// fire, so an app will not receive one here -- which is consistent with
// TelecomManager refusing the operations that would have produced it, rather
// than accepting them and going quiet.
//
// getRttTextStream() returns null: an RTT stream is a pair of file descriptors
// Telecom opens, and there is no Telecom to open them. isRequestingRtt() is
// therefore false, so an app checks and takes its non-RTT path.
//
// Mirrors telecomm/java/android/telecom/ConnectionRequest.java; public surface
// (including both public constructors) confirmed against
// frameworks/base/core/api/current.txt.
package android.telecom;

import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.List;

public final class ConnectionRequest implements Parcelable {

    private final PhoneAccountHandle mAccountHandle;
    private final Uri mAddress;
    private final Bundle mExtras;
    private final int mVideoState;
    private final String mTelecomCallId;
    private final boolean mShouldShowIncomingCallUi;
    private final boolean mIsAdhocConference;
    private final List<Uri> mParticipants;

    public ConnectionRequest(PhoneAccountHandle accountHandle, Uri handle, Bundle extras) {
        this(accountHandle, handle, extras, 0 /* VideoProfile.STATE_AUDIO_ONLY */,
                null, false, false, null);
    }

    public ConnectionRequest(PhoneAccountHandle accountHandle, Uri handle, Bundle extras,
            int videoState) {
        this(accountHandle, handle, extras, videoState, null, false, false, null);
    }

    /** Package-private (AOSP: the @hide constructor Telecom's Builder uses):
     *  SelfManagedTelecom sets shouldShowIncomingCallUi for incoming calls. */
    ConnectionRequest(PhoneAccountHandle accountHandle, Uri address, Bundle extras,
            int videoState, String telecomCallId, boolean shouldShowIncomingCallUi,
            boolean isAdhocConference, List<Uri> participants) {
        mAccountHandle = accountHandle;
        mAddress = address;
        mExtras = extras;
        mVideoState = videoState;
        mTelecomCallId = telecomCallId;
        mShouldShowIncomingCallUi = shouldShowIncomingCallUi;
        mIsAdhocConference = isAdhocConference;
        mParticipants = participants;
    }

    public PhoneAccountHandle getAccountHandle() {
        return mAccountHandle;
    }

    public Uri getAddress() {
        return mAddress;
    }

    public List<Uri> getParticipants() {
        return mParticipants;
    }

    public Bundle getExtras() {
        return mExtras;
    }

    public int getVideoState() {
        return mVideoState;
    }

    /** @hide on AOSP. */
    public String getTelecomCallId() {
        return mTelecomCallId;
    }

    /** @hide on AOSP. */
    public boolean shouldShowIncomingCallUi() {
        return mShouldShowIncomingCallUi;
    }

    public boolean isAdhocConferenceCall() {
        return mIsAdhocConference;
    }

    /** Null: the RTT pipe fds come from Telecom, and there is none. */
    public Connection.RttTextStream getRttTextStream() {
        return null;
    }

    /** False, consistently with getRttTextStream() returning null. */
    public boolean isRequestingRtt() {
        return false;
    }

    @Override public String toString() {
        return String.format("ConnectionRequest %s %s",
                mAddress == null
                        ? Uri.EMPTY
                        : android.util.Log.isLoggable("Telecom", android.util.Log.DEBUG)
                                ? mAddress : "***",
                mExtras == null ? "" : mExtras);
    }

    public static final Creator<ConnectionRequest> CREATOR = new Creator<ConnectionRequest>() {
        @Override public ConnectionRequest createFromParcel(Parcel source) {
            PhoneAccountHandle accountHandle =
                    source.readParcelable(getClass().getClassLoader());
            Uri handle = source.readParcelable(getClass().getClassLoader());
            Bundle extras = source.readBundle();
            int videoState = source.readInt();
            String telecomCallId = source.readString();
            boolean shouldShowIncomingCallUi = source.readInt() == 1;
            boolean isAdhocConference = source.readInt() == 1;
            return new ConnectionRequest(accountHandle, handle, extras, videoState, telecomCallId,
                    shouldShowIncomingCallUi, isAdhocConference, null);
        }

        @Override public ConnectionRequest[] newArray(int size) {
            return new ConnectionRequest[size];
        }
    };

    @Override public int describeContents() {
        return 0;
    }

    @Override public void writeToParcel(Parcel destination, int flags) {
        destination.writeParcelable(mAccountHandle, 0);
        destination.writeParcelable(mAddress, 0);
        destination.writeBundle(mExtras);
        destination.writeInt(mVideoState);
        destination.writeString(mTelecomCallId);
        destination.writeInt(mShouldShowIncomingCallUi ? 1 : 0);
        destination.writeInt(mIsAdhocConference ? 1 : 0);
    }
}
