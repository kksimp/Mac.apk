// HostBluetooth -- talking to Bluetooth devices through the host, for
// BluetoothSocket / BluetoothServerSocket (classic RFCOMM), BluetoothLeScanner
// (LE advertisements) and BluetoothGatt (the LE GATT client). Win.apk binds
// the natives (windows/host/native_bluetooth.cpp: Winsock AF_BTH and WinRT's
// Windows.Devices.Bluetooth); the radio itself is HostRadios'.
//
// A host without the natives (the Mac, which binds none of these) throws
// UnsatisfiedLinkError; every call here then answers as the classes did
// before this bridge: the sockets' IOException "no Bluetooth radio", no LE.
// Not remembered: a call made before the host has bound its natives must not
// disable them for the run.
//
// The permission checks are here too, as the Bluetooth service's are on a
// device: BLUETOOTH_CONNECT and BLUETOOTH_SCAN (implied by the legacy
// BLUETOOTH / BLUETOOTH_ADMIN below API 31, see RuntimePermissionGroups), a
// SecurityException without them, and scan results only with the location
// permission the target level asks for, unless the scan is declared
// neverForLocation.
package com.macapk.loader;

import java.io.IOException;

public final class HostBluetooth {
    private HostBluetooth() {}

    private static final String NO_RADIO = "Mac.apk: no Bluetooth radio";

    public static final String PERM_CONNECT = "android.permission.BLUETOOTH_CONNECT";
    public static final String PERM_SCAN = "android.permission.BLUETOOTH_SCAN";

    // ---- permissions ---------------------------------------------------------

    private static boolean granted(String permission) {
        android.app.Application app = android.app.Application.getInstance();
        return app != null
                && app.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** True when the app may talk to devices (BLUETOOTH_CONNECT, or BLUETOOTH
     *  below API 31). */
    public static boolean canConnect() { return granted(PERM_CONNECT); }

    /** The Bluetooth service's answer to a call without BLUETOOTH_CONNECT:
     *  a SecurityException naming the permission. */
    public static void enforceConnect(String what) {
        if (!canConnect()) throw new SecurityException("Need " + PERM_CONNECT + " permission for " + what);
    }

    /** The same for BLUETOOTH_SCAN (BLUETOOTH_ADMIN below API 31). */
    public static void enforceScan(String what) {
        if (!granted(PERM_SCAN)) throw new SecurityException("Need " + PERM_SCAN + " permission for " + what);
    }

    /** Whether scan results may reach the app: location is needed for them
     *  (fine from API 29, coarse or fine before), unless the app targets API
     *  31+ and declares BLUETOOTH_SCAN with neverForLocation. Without it a
     *  scan runs and finds nothing, as on a device. */
    public static boolean scanResultsAllowed() {
        android.app.Application app = android.app.Application.getInstance();
        if (app == null) return false;
        int target = app.getApplicationInfo() != null ? app.getApplicationInfo().targetSdkVersion : 0;
        if (target >= 31 && neverForLocation(app)) return true;
        boolean fine = granted(SpecialAccessStore.PERM_ACCESS_FINE_LOCATION);
        if (target >= 29) return fine;
        return fine || granted(SpecialAccessStore.PERM_ACCESS_COARSE_LOCATION);
    }

    private static boolean neverForLocation(android.app.Application app) {
        try {
            android.content.pm.PackageInfo info = app.getPackageManager().getPackageInfo(
                    app.getPackageName(), android.content.pm.PackageManager.GET_PERMISSIONS);
            if (info == null || info.requestedPermissions == null || info.requestedPermissionsFlags == null) {
                return false;
            }
            for (int i = 0; i < info.requestedPermissions.length && i < info.requestedPermissionsFlags.length; i++) {
                if (PERM_SCAN.equals(info.requestedPermissions[i])
                        && (info.requestedPermissionsFlags[i]
                                & android.content.pm.PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION) != 0) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ---- RFCOMM ------------------------------------------------------------------

    /** True when the host can open RFCOMM sockets (a Bluetooth stack with a
     *  radio). */
    public static boolean rfcommAvailable() {
        try {
            return nativeRfcommAvailable();
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Connects to the service `uuid` on the device at `address` (an SDP
     *  query finds its channel) and returns the socket's handle. Blocks for
     *  the connection; IOException when it fails. */
    public static long rfcommConnect(String address, String uuid, boolean secure) throws IOException {
        try {
            return nativeRfcommConnect(address, uuid, secure);
        } catch (UnsatisfiedLinkError e) {
            throw new IOException(NO_RADIO);
        }
    }

    /** Listens on a free channel and publishes it under `uuid` (and `name`)
     *  in SDP; returns the server's handle. */
    public static long rfcommListen(String name, String uuid, boolean secure) throws IOException {
        try {
            return nativeRfcommListen(name, uuid, secure);
        } catch (UnsatisfiedLinkError e) {
            throw new IOException(NO_RADIO);
        }
    }

    /** A connection's handle, or 0 when `timeoutMs` (>= 0; -1 waits) passed
     *  without one. */
    public static long rfcommAccept(long server, int timeoutMs) throws IOException {
        try {
            return nativeRfcommAccept(server, timeoutMs);
        } catch (UnsatisfiedLinkError e) {
            throw new IOException(NO_RADIO);
        }
    }

    /** Blocks for data; the count read, -1 at the end of the stream. */
    public static int rfcommRead(long socket, byte[] b, int off, int len) throws IOException {
        try {
            return nativeRfcommRead(socket, b, off, len);
        } catch (UnsatisfiedLinkError e) {
            throw new IOException(NO_RADIO);
        }
    }

    public static void rfcommWrite(long socket, byte[] b, int off, int len) throws IOException {
        try {
            nativeRfcommWrite(socket, b, off, len);
        } catch (UnsatisfiedLinkError e) {
            throw new IOException(NO_RADIO);
        }
    }

    public static int rfcommAvailableBytes(long socket) throws IOException {
        try {
            return nativeRfcommAvailableBytes(socket);
        } catch (UnsatisfiedLinkError e) {
            throw new IOException(NO_RADIO);
        }
    }

    /** The link's MTU, 0 when not known. */
    public static int rfcommMtu(long socket) {
        try {
            return nativeRfcommMtu(socket);
        } catch (UnsatisfiedLinkError e) {
            return 0;
        }
    }

    /** The peer's address of a connected socket (an accepted one has no
     *  other record of it), null when not known. */
    public static String rfcommRemoteAddress(long socket) {
        try {
            return nativeRfcommRemoteAddress(socket);
        } catch (UnsatisfiedLinkError e) {
            return null;
        }
    }

    /** Closes a socket or a server: a read or accept blocked on another
     *  thread ends with an IOException. */
    public static void rfcommClose(long handle) {
        try {
            nativeRfcommClose(handle);
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    // ---- LE ---------------------------------------------------------------------

    /** True when the host scans for and connects to LE devices (Windows'
     *  Bluetooth LE API, with a radio). */
    public static boolean leAvailable() {
        try {
            return nativeLeAvailable() && HostRadios.bluetoothRadio() != null;
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Starts the host's one advertisement watcher: 0, or a
     *  ScanCallback.SCAN_FAILED_* code. */
    public static int scanStart() {
        try {
            return nativeScanStart();
        } catch (UnsatisfiedLinkError e) {
            return 3;  // SCAN_FAILED_INTERNAL_ERROR
        }
    }

    public static void scanStop() {
        try {
            nativeScanStop();
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    /** The next advertisement, {String address, int[] {rssi dBm, tx power
     *  (127 absent), connectable, legacy}, byte[] record (the advertising
     *  data and scan response in wire form), String name or null}; the error
     *  that ended the scan as {null, int[] {error}, null, null}; null when
     *  `timeoutMs` passed without one. */
    public static Object[] scanNext(int timeoutMs) {
        try {
            return nativeScanNext(timeoutMs);
        } catch (UnsatisfiedLinkError e) {
            return null;
        }
    }

    /** Begins a GATT connection; its events come from gattNext. 0 when the
     *  host cannot. */
    public static long gattConnect(String address, boolean autoConnect) {
        try {
            return nativeGattConnect(address, autoConnect);
        } catch (UnsatisfiedLinkError e) {
            return 0;
        }
    }

    public static boolean gattReconnect(long h) {
        try {
            return nativeGattReconnect(h);
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    public static void gattDisconnect(long h) {
        try {
            nativeGattDisconnect(h);
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    /** Ends the connection; GATT_CLOSED is its last event. */
    public static void gattClose(long h) {
        try {
            nativeGattClose(h);
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    public static boolean gattDiscover(long h) {
        try {
            return nativeGattDiscover(h);
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Reads a characteristic (`descriptor` 0) or one of its descriptors; the
     *  ids are the attribute handles discoverServices reported. */
    public static boolean gattRead(long h, int characteristic, int descriptor) {
        try {
            return nativeGattRead(h, characteristic, descriptor);
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    public static boolean gattWrite(long h, int characteristic, int descriptor, byte[] value, boolean withResponse) {
        try {
            return nativeGattWrite(h, characteristic, descriptor, value, withResponse);
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Listens for the characteristic's notifications (the subscription
     *  itself is the app's write of the CCCD, as on Android). */
    public static boolean gattNotify(long h, int characteristic, boolean enable) {
        try {
            return nativeGattNotify(h, characteristic, enable);
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    public static boolean gattMtu(long h, int mtu) {
        try {
            return nativeGattMtu(h, mtu);
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** The connection's next event, {int[] {kind, status, a, b}, byte[]
     *  value, String[] tree}, or null when `timeoutMs` passed. The kinds are
     *  GATT_*; status is Android's (0 success, an ATT error code, 133). */
    public static Object[] gattNext(long h, int timeoutMs) {
        try {
            return nativeGattNext(h, timeoutMs);
        } catch (UnsatisfiedLinkError e) {
            return null;
        }
    }

    /** gattNext's kinds. */
    public static final int GATT_CONNECTION = 1;   // status, new state (0 disconnected, 2 connected)
    public static final int GATT_SERVICES = 2;     // status; tree: "S" uuid handle, "C" uuid handle properties,
                                                   // "D" uuid handle
    public static final int GATT_CHAR_READ = 3;    // status, characteristic; value
    public static final int GATT_CHAR_WRITE = 4;   // status, characteristic
    public static final int GATT_CHAR_CHANGED = 5; // characteristic; value
    public static final int GATT_DESC_READ = 6;    // status, characteristic, descriptor; value
    public static final int GATT_DESC_WRITE = 7;   // status, characteristic, descriptor
    public static final int GATT_MTU = 8;          // status, mtu
    public static final int GATT_CLOSED = 9;       // the last event

    private static native boolean nativeRfcommAvailable();
    private static native long nativeRfcommConnect(String address, String uuid, boolean secure) throws IOException;
    private static native long nativeRfcommListen(String name, String uuid, boolean secure) throws IOException;
    private static native long nativeRfcommAccept(long server, int timeoutMs) throws IOException;
    private static native int nativeRfcommRead(long socket, byte[] b, int off, int len) throws IOException;
    private static native void nativeRfcommWrite(long socket, byte[] b, int off, int len) throws IOException;
    private static native int nativeRfcommAvailableBytes(long socket) throws IOException;
    private static native int nativeRfcommMtu(long socket);
    private static native String nativeRfcommRemoteAddress(long socket);
    private static native void nativeRfcommClose(long handle);
    private static native boolean nativeLeAvailable();
    private static native int nativeScanStart();
    private static native void nativeScanStop();
    private static native Object[] nativeScanNext(int timeoutMs);
    private static native long nativeGattConnect(String address, boolean autoConnect);
    private static native boolean nativeGattReconnect(long h);
    private static native void nativeGattDisconnect(long h);
    private static native void nativeGattClose(long h);
    private static native boolean nativeGattDiscover(long h);
    private static native boolean nativeGattRead(long h, int characteristic, int descriptor);
    private static native boolean nativeGattWrite(long h, int characteristic, int descriptor, byte[] value,
                                                  boolean withResponse);
    private static native boolean nativeGattNotify(long h, int characteristic, boolean enable);
    private static native boolean nativeGattMtu(long h, int mtu);
    private static native Object[] nativeGattNext(long h, int timeoutMs);
}
