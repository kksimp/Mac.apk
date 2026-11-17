// android.bluetooth.BluetoothServerSocket -- a listening RFCOMM socket.
//
// On a host that talks to devices (com.macapk.loader.HostBluetooth; Win.apk)
// the socket listens on a free channel, published under the service UUID in
// SDP so a phone's createRfcommSocketToServiceRecord finds it, and accept()
// hands out connected BluetoothSockets. close() ends an accept blocked on
// another thread with an IOException, as on Android.
//
// Without such a host (the Mac) BluetoothAdapter.listenUsing* throws, so
// nothing ever constructs one that could accept.
//
// Mirrors frameworks/base/core/java/android/bluetooth/BluetoothServerSocket.java.
package android.bluetooth;

import com.macapk.loader.HostBluetooth;

import java.io.Closeable;
import java.io.IOException;

public class BluetoothServerSocket implements Closeable {

    private final String mServiceName;
    private long mHandle;  // the host's, 0 once closed
    private boolean mClosed;

    public BluetoothServerSocket() {
        mServiceName = null;
        mClosed = true;
    }

    BluetoothServerSocket(String serviceName, long handle) {
        mServiceName = serviceName;
        mHandle = handle;
    }

    public BluetoothSocket accept() throws IOException { return accept(-1); }

    /** AOSP: blocks for a connection, IOException on error (this socket
     *  closed) or when `timeout` ms (>= 0) pass without one. */
    public BluetoothSocket accept(int timeout) throws IOException {
        long h;
        synchronized (this) {
            if (mClosed) throw new IOException("Mac.apk: no Bluetooth radio");
            h = mHandle;
        }
        long s = HostBluetooth.rfcommAccept(h, timeout < 0 ? -1 : timeout);
        if (s == 0) throw new IOException("accept timed out");
        String address = HostBluetooth.rfcommRemoteAddress(s);
        return new BluetoothSocket(s, address != null ? new BluetoothDevice(address) : null);
    }

    /** AOSP returns the assigned L2CAP PSM, or throws when the socket is not
     *  an L2CAP listener. These are RFCOMM, so 0 -- which is not a valid PSM,
     *  so it cannot be mistaken for one. */
    public int getPsm() { return 0; }

    @Override public String toString() {
        return "ServiceName: " + mServiceName + " Type: RFCOMM ServerSocket: " + (mClosed ? "closed" : "listening");
    }

    @Override public void close() throws IOException {
        long h;
        synchronized (this) {
            if (mClosed) return;
            mClosed = true;
            h = mHandle;
            mHandle = 0;
        }
        if (h != 0) HostBluetooth.rfcommClose(h);
    }
}
