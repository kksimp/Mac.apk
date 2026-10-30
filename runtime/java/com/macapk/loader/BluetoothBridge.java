package com.macapk.loader;

import android.bluetooth.BluetoothDevice;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Real CoreBluetooth transport. Identifiers are opaque CoreBluetooth peers;
 * the address-shaped key is only an Android API handle, never a radio MAC. */
public final class BluetoothBridge {
    private BluetoothBridge() {}
    private static final boolean ON = "1".equals(System.getenv("MACAPK_BLUETOOTH_HOST"));
    private static final ExecutorService EVENTS = Executors.newSingleThreadExecutor(r -> {
        Thread t=new Thread(r,"macapk-bluetooth-events");t.setDaemon(true);return t;
    });
    private static final Map<String,BluetoothDevice> DEVICES=new ConcurrentHashMap<>();
    private static final Map<String,String> IDENTIFIERS=new ConcurrentHashMap<>();
    private static final Map<String,String> NAMES=new ConcurrentHashMap<>();
    private static final AtomicLong IDS=new AtomicLong();
    private static final Map<Long,GattSink> GATTS=new ConcurrentHashMap<>();
    private static volatile ScanSink scanner;
    private static native boolean nativeAvailable();
    private static native int nativeState();
    private static native int nativeAuthorization();
    private static native boolean nativeTransportReady();
    private static native boolean nativeRequestAuthorization();
    private static native boolean nativeScan(String[] uuids);
    private static native void nativeStopScan();
    private static native boolean nativeConnect(String identifier,long token);
    private static native void nativeDisconnect(long token,boolean close);
    private static native boolean nativeOperation(long token,int operation,String service,String characteristic,String descriptor,byte[] data,int value);
    public static boolean available() { try{return ON && nativeAvailable();}catch(UnsatisfiedLinkError e){return false;} }
    public static int state() {
        if(!available())return 10;
        try {
            // An already-authorized app can query state before requesting any
            // permission this run. Initialize CoreBluetooth in that case too;
            // its own state is authoritative for BLE on modern controllers.
            // No request is made when authorization is undetermined/denied.
            if(nativeAuthorization()==3 && !nativeTransportReady() && nativeRequestAuthorization()) {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(!nativeTransportReady() && System.nanoTime()<deadline) {
                    try { Thread.sleep(10); }
                    catch(InterruptedException interrupted) { Thread.currentThread().interrupt();break; }
                }
            }
            return nativeState();
        } catch(UnsatisfiedLinkError e) { return 10; }
    }
    public static boolean isEnabled(){return state()==12;}
    public static int authorization(){if(!available())return 2;try{return nativeAuthorization();}catch(UnsatisfiedLinkError e){return 2;} }
    /** Keep the Android per-operation permission model in addition to TCC.
     * macOS grants one bundle-wide Bluetooth entitlement; that must not grant
     * an APK a different Android operation it never declared/requested. */
    public static boolean hasPermission(String permission){
        if(permission==null||!available())return false;
        android.app.Application app=android.app.ActivityThread.currentApplication();
        if(app==null)return false;
        android.content.pm.ApplicationInfo info=app.getApplicationInfo();
        if(info==null)return false;
        return permissionGranted(app,permission,android.os.Build.VERSION.SDK_INT,info.targetSdkVersion);
    }
    private static boolean granted(android.app.Application app,String permission){
        return app.checkSelfPermission(permission)==android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
    // A pure policy seam: the permission answers still come from the actual
    // app Context/PackageManager, never from a second Bluetooth grant store.
    private static boolean permissionGranted(android.app.Application app,String permission,int sdk,int target){
        if(app==null)return false;
        boolean scan="android.permission.BLUETOOTH_SCAN".equals(permission);
        boolean connect="android.permission.BLUETOOTH_CONNECT".equals(permission);
        boolean advertise="android.permission.BLUETOOTH_ADVERTISE".equals(permission);
        if(!scan&&!connect&&!advertise)return granted(app,permission);
        if(sdk>=31&&target>=31)return granted(app,permission);
        if(!granted(app,"android.permission.BLUETOOTH"))return false;
        if(connect)return true;
        if(!granted(app,"android.permission.BLUETOOTH_ADMIN"))return false;
        if(advertise||sdk<23||target<23)return true;
        // Old-target BLE scans keep Android's existing location permission
        // prerequisite. This only checks it; it does not introduce a new UI.
        if(sdk>=29&&target>=29)return granted(app,"android.permission.ACCESS_FINE_LOCATION");
        return granted(app,"android.permission.ACCESS_FINE_LOCATION")||granted(app,"android.permission.ACCESS_COARSE_LOCATION");
    }
    /** Called from the permission worker, never the AppKit main queue. */
    public static int requestAuthorizationAndWait(long timeoutMs) {
        if(!available())return 2;
        if(!nativeRequestAuthorization())return 2;
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.max(0,timeoutMs));
        int auth=authorization();
        while(auth==0 && System.nanoTime()<deadline){
            try{Thread.sleep(40);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            auth=authorization();
        }
        while(auth==3 && !nativeTransportReady() && System.nanoTime()<deadline){
            try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
        }
        return auth;
    }
    public static void dispatch(Runnable action){EVENTS.execute(action);}
    public static BluetoothDevice device(String identifier,String name){
        if(identifier==null)return null;
        String key;
        try{
            byte[] bytes=MessageDigest.getInstance("SHA-256", "SUN").digest(identifier.getBytes(StandardCharsets.UTF_8));
            bytes[0]=(byte)((bytes[0]&0xfc)|2);
            key=String.format(java.util.Locale.ROOT,"%02X:%02X:%02X:%02X:%02X:%02X",bytes[0]&255,bytes[1]&255,bytes[2]&255,bytes[3]&255,bytes[4]&255,bytes[5]&255);
        }catch(java.security.GeneralSecurityException e){throw new IllegalStateException("Host SHA-256 unavailable",e);}
        String previous=IDENTIFIERS.putIfAbsent(key,identifier);
        if(previous!=null && !previous.equals(identifier))throw new IllegalStateException("CoreBluetooth handle collision");
        if(name!=null)NAMES.put(key,name);
        return DEVICES.computeIfAbsent(key,BluetoothDevice::new);
    }
    public static String identifier(BluetoothDevice device){return device==null?null:IDENTIFIERS.get(device.getAddress());}
    public static String name(BluetoothDevice device){return device==null?null:NAMES.get(device.getAddress());}
    public interface ScanSink {void found(BluetoothDevice device,byte[] advertisement,int rssi,boolean connectable);void failed(int error);}
    public static synchronized boolean startScan(String[] uuids,ScanSink sink){
        if(!hasPermission("android.permission.BLUETOOTH_SCAN")||!isEnabled()||authorization()!=3)return false;
        scanner=sink;
        if(!nativeScan(uuids)){scanner=null;return false;}return true;
    }
    public static synchronized void stopScan(){scanner=null;if(available())nativeStopScan();}
    private static void onScan(String identifier,String name,byte[] advertisement,int rssi,boolean connectable){
        ScanSink sink=scanner;if(sink==null)return;
        BluetoothDevice d=device(identifier,name);
        dispatch(()->{if(scanner==sink)sink.found(d,advertisement,rssi,connectable);});
    }
    private static void onScanFailure(int error){ScanSink s=scanner;if(s!=null)dispatch(()->{if(scanner==s)s.failed(error);});}
    public interface GattSink {void event(int event,String service,String characteristic,String descriptor,int value,int status,byte[] data);}
    public static long connect(BluetoothDevice device,GattSink sink){
        String id=identifier(device);if(id==null||!hasPermission("android.permission.BLUETOOTH_CONNECT")||!isEnabled()||authorization()!=3)return 0;
        long token=IDS.incrementAndGet();GATTS.put(token,sink);
        if(!nativeConnect(id,token)){GATTS.remove(token);return 0;}return token;
    }
    public static void disconnect(long token,boolean close){
        if(token==0)return;
        if(close)GATTS.remove(token);
        if(available())nativeDisconnect(token,close);
    }
    public static boolean operation(long token,int operation,String service,String characteristic,String descriptor,byte[] data,int value){
        return token!=0&&hasPermission("android.permission.BLUETOOTH_CONNECT")&&authorization()==3&&nativeOperation(token,operation,service,characteristic,descriptor,data,value);
    }
    private static void onGatt(long token,int event,String service,String characteristic,String descriptor,int value,int status,byte[] data){
        GattSink sink=GATTS.get(token);if(sink==null)return;
        dispatch(()->{if(GATTS.get(token)==sink)sink.event(event,service,characteristic,descriptor,value,status,data);});
    }
}
