package android.bluetooth;

import android.bluetooth.le.*;
import android.os.ParcelUuid;
import com.macapk.loader.BluetoothBridge;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Offline producer/consumer test. Advertisement bytes come from the actual
 * native CoreBluetooth dictionary adapter; no scan or connection is opened. */
public final class BluetoothClientSmoke {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static final class PermissionApp extends android.app.Application {
        final Set<String> granted=new HashSet<>();
        @Override public int checkSelfPermission(String permission){return granted.contains(permission)?0:-1;}
    }
    static void permissionPolicy()throws Exception{
        PermissionApp app=new PermissionApp();
        var policy=BluetoothBridge.class.getDeclaredMethod("permissionGranted",android.app.Application.class,String.class,int.class,int.class);
        policy.setAccessible(true);
        app.granted.add("android.permission.BLUETOOTH_CONNECT");
        check((Boolean)policy.invoke(null,app,"android.permission.BLUETOOTH_CONNECT",35,35),"modern CONNECT grant works");
        check(!(Boolean)policy.invoke(null,app,"android.permission.BLUETOOTH_SCAN",35,35),"CONNECT grant does not grant SCAN");
        check(!(Boolean)policy.invoke(null,app,"android.permission.BLUETOOTH_ADVERTISE",35,35),"CONNECT grant does not grant ADVERTISE");
        app.granted.clear();app.granted.add("android.permission.BLUETOOTH");app.granted.add("android.permission.BLUETOOTH_ADMIN");
        check((Boolean)policy.invoke(null,app,"android.permission.BLUETOOTH_CONNECT",35,30),"old target uses declared BLUETOOTH");
        check(!(Boolean)policy.invoke(null,app,"android.permission.BLUETOOTH_SCAN",35,30),"old target scan retains location prerequisite");
        app.granted.add("android.permission.ACCESS_FINE_LOCATION");
        check((Boolean)policy.invoke(null,app,"android.permission.BLUETOOTH_SCAN",35,30),"old target scan with existing fine location grant");
        check(!(Boolean)policy.invoke(null,null,"android.permission.BLUETOOTH_SCAN",35,35),"missing app has no operation permission");
    }
    static void rejectedDiscovery()throws Exception{
        var constructor=BluetoothGatt.class.getDeclaredConstructor(BluetoothDevice.class,BluetoothGattCallback.class,android.os.Handler.class);
        constructor.setAccessible(true);
        BluetoothGatt gatt=constructor.newInstance(new BluetoothDevice("02:00:00:00:00:01"),new BluetoothGattCallback(){},null);
        var handle=BluetoothGatt.class.getDeclaredMethod("handle",int.class,String.class,String.class,String.class,int.class,int.class,byte[].class);
        handle.setAccessible(true);
        String service="11223344-5566-7788-99aa-bbccddeeff00";
        handle.invoke(gatt,2,service,null,null,0,0,null);
        check(!gatt.discoverServices(),"unbound/disconnected discovery rejected");
        check(gatt.getServices().size()==1&&gatt.getService(UUID.fromString(service))!=null,"rejected discovery preserves existing service tree");
        gatt.close();
    }
    public static void main(String[] args)throws Exception{
        byte[] bytes=Files.readAllBytes(Path.of(args[0]));
        ScanRecord record=ScanRecord.parseFromBytes(bytes);
        ParcelUuid service=ParcelUuid.fromString("11223344-5566-7788-99aa-bbccddeeff00");
        check(record.getServiceUuids().contains(service),"CoreBluetooth UUID -> BLE endian -> Android UUID");
        check("Fixture".equals(record.getDeviceName()),"real host name field");
        check(record.getTxPowerLevel()==-4,"signed TX power");
        check(Arrays.equals(record.getManufacturerSpecificData(0x1234),new byte[]{5,6}),"manufacturer identifier endian");
        check(Arrays.equals(record.getServiceData(ParcelUuid.fromString("0000180f-0000-1000-8000-00805f9b34fb")),new byte[]{42}),"service data endian");
        BluetoothDevice peer=BluetoothBridge.device("00112233-4455-6677-8899-AABBCCDDEEFF","Fixture");
        check(peer==BluetoothBridge.device("00112233-4455-6677-8899-AABBCCDDEEFF",null),"stable device identity");
        check((Integer.parseInt(peer.getAddress().substring(0,2),16)&3)==2,"local unicast handle");
        check(BluetoothBridge.identifier(peer).equals("00112233-4455-6677-8899-AABBCCDDEEFF"),"opaque identifier preserved for transport");
        ScanResult result=new ScanResult(peer,record,-52,123);
        check(new ScanFilter.Builder().setServiceUuid(service).build().matches(result),"service UUID filter matches host record");
        check(!new ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString("21223344-5566-7788-99aa-bbccddeeff00")).build().matches(result),"other service rejected");
        check(new ScanFilter.Builder().setManufacturerData(0x1234,new byte[]{5},new byte[]{(byte)255}).build().matches(result),"manufacturer mask matches");
        check(ScanRecord.parseFromBytes(new byte[]{20,7,1}).getServiceUuids()==null,"truncated AD safely ignored");
        check(!BluetoothBridge.available()&&!BluetoothBridge.isEnabled(),"missing native transport stays unavailable");
        CountDownLatch failed=new CountDownLatch(1);
        BluetoothGatt client=peer.connectGatt(null,false,new BluetoothGattCallback(){
            @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state){check(status==BluetoothGatt.GATT_FAILURE&&state==BluetoothProfile.STATE_DISCONNECTED,"unbound transport fails honestly");failed.countDown();}
        });
        check(failed.await(3,TimeUnit.SECONDS),"unbound connect has async terminal callback");client.close();
        check(BluetoothGatt.macapkConnectedDevices().isEmpty(),"failed client does not claim a link");
        int[] oldCalls={0};
        BluetoothGattCallback callback=new BluetoothGattCallback(){@Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){oldCalls[0]++;}};
        callback.onCharacteristicChanged(null,null,new byte[]{1});
        check(oldCalls[0]==1,"new callback overload forwards exactly once to legacy override");
        int[] descriptorCalls={0};
        BluetoothGattCallback descriptorCallback=new BluetoothGattCallback(){@Override public void onDescriptorRead(BluetoothGatt g,BluetoothGattDescriptor d,int status){descriptorCalls[0]++;}};
        descriptorCallback.onDescriptorRead(null,null,0,new byte[]{1});
        check(descriptorCalls[0]==1,"descriptor API33 overload forwards in Android parameter order");
        permissionPolicy();rejectedDiscovery();
        System.out.println("bluetooth-client: PASS (real host AD producer, UUID filters, opaque identity, missing-native failure, callback compatibility, permission separation, rejected-discovery preservation; no radio)");
    }
}
