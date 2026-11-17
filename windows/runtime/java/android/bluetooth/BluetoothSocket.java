// android.bluetooth.BluetoothSocket -- an RFCOMM socket to a remote Bluetooth
// device.
//
// On a host that talks to devices (com.macapk.loader.HostBluetooth; Win.apk,
// on Winsock's AF_BTH) the socket is real: connect() runs an SDP query for
// the service UUID and connects, the streams read and write the link
// (reads block, as Bionic's do), and close() ends a read blocked on another
// thread with an IOException, as on Android. BluetoothServerSocket.accept
// hands out connected ones.
//
// Without such a host (the Mac) no socket can ever carry traffic, and
// connect() THROWS IOException rather than returning silently: AOSP's own
// contract is "@throws IOException on error, for example connection failure",
// and a silent success would leave an app reading from a stream that will
// never produce a byte while it believes the link is up.
//
// Mirrors frameworks/base/core/java/android/bluetooth/BluetoothSocket.java.
package android.bluetooth;

import com.macapk.loader.HostBluetooth;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public class BluetoothSocket implements Closeable {

    public static final int TYPE_RFCOMM = 1;
    public static final int TYPE_SCO = 2;
    public static final int TYPE_L2CAP = 3;

    private final BluetoothDevice mDevice;
    private final UUID mUuid;
    private final boolean mSecure;
    private long mHandle;          // the host's, 0 until connected
    private boolean mClosed;
    private final InputStream mIn = new BluetoothInputStream();
    private final OutputStream mOut = new BluetoothOutputStream();

    public BluetoothSocket() { this(null, null, true); }

    /** A client socket; BluetoothDevice.createRfcommSocketToServiceRecord. */
    BluetoothSocket(BluetoothDevice device, UUID uuid, boolean secure) {
        mDevice = device;
        mUuid = uuid;
        mSecure = secure;
    }

    /** A connected socket, from BluetoothServerSocket.accept. */
    BluetoothSocket(long handle, BluetoothDevice device) {
        mDevice = device;
        mUuid = null;
        mSecure = true;
        mHandle = handle;
    }

    /** AOSP: blocks until connected or failed; IOException on failure (no
     *  radio, device out of range, the service not offered, or the socket
     *  already closed), SecurityException without BLUETOOTH_CONNECT. */
    public void connect() throws IOException {
        synchronized (this) {
            if (mClosed) throw new IOException("socket closed");
            if (mHandle != 0) throw new IOException("already connected");
        }
        if (mDevice == null || mUuid == null) throw new IOException("no device");
        HostBluetooth.enforceConnect("connect");
        long h = HostBluetooth.rfcommConnect(mDevice.getAddress(), mUuid.toString(), mSecure);
        synchronized (this) {
            if (mClosed) {
                // Closed while connecting: the link is dropped, as AOSP's
                // abort does.
                HostBluetooth.rfcommClose(h);
                throw new IOException("socket closed");
            }
            mHandle = h;
        }
    }

    /** AOSP hands back the socket's stream whether or not it is connected,
     *  and the READ is what fails: before a connection with the IOException
     *  connect() gives, after close() with "socket closed". */
    public InputStream getInputStream() throws IOException { return mIn; }

    /** @see #getInputStream -- same on the write side. */
    public OutputStream getOutputStream() throws IOException { return mOut; }

    public synchronized boolean isConnected() { return mHandle != 0 && !mClosed; }

    public BluetoothDevice getRemoteDevice() { return mDevice; }

    /** AOSP: RFCOMM / SCO / L2CAP. RFCOMM is the type every socket factory
     *  on this class creates. */
    public int getConnectionType() { return TYPE_RFCOMM; }

    /** AOSP returns the link's MTU and throws IOException when the socket is
     *  not connected. */
    public int getMaxTransmitPacketSize() throws IOException { return mtu(); }

    /** @see #getMaxTransmitPacketSize */
    public int getMaxReceivePacketSize() throws IOException { return mtu(); }

    private int mtu() throws IOException {
        long h = handle();
        int mtu = HostBluetooth.rfcommMtu(h);
        return mtu > 0 ? mtu : 1011;  // RFCOMM's default frame size
    }

    /** The connected handle, or the IOException a read would get. */
    private synchronized long handle() throws IOException {
        if (mClosed) throw new IOException("socket closed");
        if (mHandle == 0) throw new IOException(HostBluetooth.rfcommAvailable() ? "socket not connected"
                                                                                 : "Mac.apk: no Bluetooth radio");
        return mHandle;
    }

    /** Idempotent; a read or write blocked on another thread ends with an
     *  IOException. */
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

    @Override public String toString() {
        return "BluetoothSocket{device=" + mDevice + ", uuid=" + mUuid + ", connected=" + isConnected() + "}";
    }

    private final class BluetoothInputStream extends InputStream {
        @Override public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n < 0 ? -1 : (b[0] & 0xff);
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (b == null) throw new NullPointerException();
            if (off < 0 || len < 0 || len > b.length - off) throw new IndexOutOfBoundsException();
            if (len == 0) return 0;
            return HostBluetooth.rfcommRead(handle(), b, off, len);
        }

        @Override public int available() throws IOException {
            return HostBluetooth.rfcommAvailableBytes(handle());
        }

        @Override public void close() throws IOException { BluetoothSocket.this.close(); }
    }

    private final class BluetoothOutputStream extends OutputStream {
        @Override public void write(int b) throws IOException {
            write(new byte[] { (byte) b }, 0, 1);
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            if (b == null) throw new NullPointerException();
            if (off < 0 || len < 0 || len > b.length - off) throw new IndexOutOfBoundsException();
            if (len == 0) return;
            HostBluetooth.rfcommWrite(handle(), b, off, len);
        }

        @Override public void close() throws IOException { BluetoothSocket.this.close(); }
    }
}
