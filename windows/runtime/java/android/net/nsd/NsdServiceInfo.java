package android.net.nsd;
import java.util.HashMap;
import java.util.Map;
/** Network service discovery (mDNS/Bonjour) descriptor. A value holder the app
 *  fills in for register/resolve. The atlas notes mDNS maps to the host's
 *  mDNSResponder (dns_sd.h) -- that wiring is future work; the descriptor itself
 *  round-trips. Source: frameworks/base/core/java/android/net/nsd/NsdServiceInfo.java */
public final class NsdServiceInfo implements android.os.Parcelable {
    private String mServiceName;
    private String mServiceType;
    private int mPort;
    private java.net.InetAddress mHost;
    private final Map<String, byte[]> mTxtRecord = new HashMap<>();
    public NsdServiceInfo() { }
    public String getServiceName() { return mServiceName; }
    public void setServiceName(String s) { mServiceName = s; }
    public String getServiceType() { return mServiceType; }
    public void setServiceType(String s) { mServiceType = s; }
    public int getPort() { return mPort; }
    public void setPort(int p) { mPort = p; }
    public java.net.InetAddress getHost() { return mHost; }
    public void setHost(java.net.InetAddress h) { mHost = h; }
    public void setAttribute(String key, String value) {
        mTxtRecord.put(key, value != null ? value.getBytes() : null);
    }
    public void removeAttribute(String key) { mTxtRecord.remove(key); }
    public Map<String, byte[]> getAttributes() { return mTxtRecord; }

    // AOSP's NsdServiceInfo is Parcelable (apps put one in an Intent or a saved
    // state Bundle): name, type, host address, port, then the TXT record.
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(android.os.Parcel dest, int flags) {
        dest.writeString(mServiceName);
        dest.writeString(mServiceType);
        dest.writeByteArray(mHost != null ? mHost.getAddress() : null);
        dest.writeInt(mPort);
        dest.writeInt(mTxtRecord.size());
        for (Map.Entry<String, byte[]> e : mTxtRecord.entrySet()) {
            dest.writeString(e.getKey());
            dest.writeByteArray(e.getValue());
        }
    }
    public static final android.os.Parcelable.Creator<NsdServiceInfo> CREATOR =
            new android.os.Parcelable.Creator<NsdServiceInfo>() {
        @Override public NsdServiceInfo createFromParcel(android.os.Parcel in) {
            NsdServiceInfo info = new NsdServiceInfo();
            info.mServiceName = in.readString();
            info.mServiceType = in.readString();
            byte[] address = in.createByteArray();
            if (address != null) {
                try {
                    info.mHost = java.net.InetAddress.getByAddress(address);
                } catch (java.net.UnknownHostException e) {
                    // Not 4 or 16 bytes: no host, as for an unresolved service.
                }
            }
            info.mPort = in.readInt();
            int size = in.readInt();
            for (int i = 0; i < size; i++) {
                String key = in.readString();
                info.mTxtRecord.put(key, in.createByteArray());
            }
            return info;
        }
        @Override public NsdServiceInfo[] newArray(int size) { return new NsdServiceInfo[size]; }
    };
}
