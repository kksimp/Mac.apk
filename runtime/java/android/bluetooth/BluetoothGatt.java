package android.bluetooth;

import com.macapk.loader.BluetoothBridge;
import android.os.Handler;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** GATT client backed by CoreBluetooth; unsupported controller requests fail. */
public final class BluetoothGatt implements BluetoothProfile {
    public static final int GATT_SUCCESS = 0;
    public static final int GATT_READ_NOT_PERMITTED = 0x2;
    public static final int GATT_WRITE_NOT_PERMITTED = 0x3;
    public static final int GATT_INSUFFICIENT_AUTHENTICATION = 0x5;
    public static final int GATT_REQUEST_NOT_SUPPORTED = 0x6;
    public static final int GATT_INSUFFICIENT_ENCRYPTION = 0xf;
    public static final int GATT_INVALID_OFFSET = 0x7;
    public static final int GATT_INVALID_ATTRIBUTE_LENGTH = 0xd;
    public static final int GATT_CONNECTION_CONGESTED = 0x8f;
    public static final int GATT_FAILURE = 0x101;

    public static final int CONNECTION_PRIORITY_BALANCED = 0;
    public static final int CONNECTION_PRIORITY_HIGH = 1;
    public static final int CONNECTION_PRIORITY_LOW_POWER = 2;


    private static final Set<BluetoothGatt> CLIENTS=ConcurrentHashMap.newKeySet();
    private final BluetoothDevice mDevice;
    private final BluetoothGattCallback mCallback;
    private final Handler mHandler;
    private volatile long token;
    private volatile int state=STATE_DISCONNECTED;
    private volatile boolean closed;
    private final List<BluetoothGattService> services=new ArrayList<>();
    private final Set<BluetoothGattCharacteristic> notifications=Collections.newSetFromMap(new IdentityHashMap<>());
    private BluetoothGatt(BluetoothDevice device,BluetoothGattCallback callback,Handler handler){mDevice=device;mCallback=callback;mHandler=handler;}
    static BluetoothGatt createFailed(BluetoothDevice device,BluetoothGattCallback callback){
        BluetoothGatt g=new BluetoothGatt(device,callback,null);
        BluetoothBridge.dispatch(()->{if(callback!=null&&!g.closed)callback.onConnectionStateChange(g,GATT_FAILURE,STATE_DISCONNECTED);});return g;
    }
    static BluetoothGatt create(BluetoothDevice device,BluetoothGattCallback callback,Handler handler){
        if(callback==null)throw new IllegalArgumentException("callback cannot be null");
        BluetoothGatt g=new BluetoothGatt(device,callback,handler);CLIENTS.add(g);g.connect();return g;
    }
    public static List<BluetoothDevice> macapkConnectedDevices(){
        Set<BluetoothDevice> out=new LinkedHashSet<>();for(BluetoothGatt g:CLIENTS)if(g.state==STATE_CONNECTED&&!g.closed)out.add(g.mDevice);return new ArrayList<>(out);
    }
    public static int macapkConnectionState(BluetoothDevice device){
        int state=STATE_DISCONNECTED;for(BluetoothGatt g:CLIENTS)if(!g.closed&&g.mDevice.equals(device))state=Math.max(state,g.state);return state;
    }
    public static List<BluetoothDevice> macapkDevicesMatchingConnectionStates(int[] states){
        Set<BluetoothDevice> out=new LinkedHashSet<>();
        if(states!=null)for(BluetoothGatt g:CLIENTS)if(!g.closed)for(int state:states)if(g.state==state)out.add(g.mDevice);
        return new ArrayList<>(out);
    }
    public BluetoothDevice getDevice(){return mDevice;}
    public synchronized boolean connect(){
        if(closed||state!=STATE_DISCONNECTED)return false;
        if(token!=0)BluetoothBridge.disconnect(token,true);
        state=STATE_CONNECTING;
        token=BluetoothBridge.connect(mDevice,this::receive);
        if(token==0){state=STATE_DISCONNECTED;BluetoothBridge.dispatch(()->receive(1,null,null,null,STATE_DISCONNECTED,GATT_FAILURE,null));return false;}
        return true;
    }
    public void disconnect(){if(!closed&&token!=0){state=STATE_DISCONNECTING;BluetoothBridge.disconnect(token,false);}}
    public synchronized void close(){if(closed)return;closed=true;BluetoothBridge.disconnect(token,true);token=0;state=STATE_DISCONNECTED;CLIENTS.remove(this);services.clear();notifications.clear();}
    private boolean operation(int op,BluetoothGattCharacteristic c,BluetoothGattDescriptor d,byte[] data,int value){
        if(closed||state!=STATE_CONNECTED)return false;
        if(d!=null)c=d.getCharacteristic();
        String service=null,characteristic=null,descriptor=null;
        if(c!=null){if(c.getService()==null)return false;service=c.getService().getUuid().toString();characteristic=c.getUuid().toString();}
        if(d!=null)descriptor=d.getUuid().toString();
        return BluetoothBridge.operation(token,op,service,characteristic,descriptor,data,value);
    }
    public synchronized boolean discoverServices(){
        // Native callbacks are queued, and handle() takes this same lock.
        // Preserve the old tree if a duplicate/busy/disconnected request fails.
        if(!operation(1,null,null,null,0))return false;
        services.clear();return true;
    }
    public synchronized List<BluetoothGattService> getServices(){return new ArrayList<>(services);}
    public synchronized BluetoothGattService getService(UUID uuid){for(BluetoothGattService s:services)if(s.getUuid().equals(uuid))return s;return null;}
    public boolean readCharacteristic(BluetoothGattCharacteristic c){return c!=null&&operation(2,c,null,null,0);}
    public boolean writeCharacteristic(BluetoothGattCharacteristic c){return c!=null&&c.getValue()!=null&&operation(3,c,null,c.getValue().clone(),c.getWriteType());}
    public int writeCharacteristic(BluetoothGattCharacteristic c,byte[] value,int type){if(c==null||value==null)throw new IllegalArgumentException("characteristic/value cannot be null");return operation(3,c,null,value.clone(),type)?GATT_SUCCESS:GATT_FAILURE;}
    public boolean readDescriptor(BluetoothGattDescriptor d){return d!=null&&operation(4,null,d,null,0);}
    public boolean writeDescriptor(BluetoothGattDescriptor d){return d!=null&&d.getValue()!=null&&operation(5,null,d,d.getValue().clone(),0);}
    public int writeDescriptor(BluetoothGattDescriptor d,byte[] value){if(d==null||value==null)throw new IllegalArgumentException("descriptor/value cannot be null");return operation(5,null,d,value.clone(),0)?GATT_SUCCESS:GATT_FAILURE;}
    public synchronized boolean setCharacteristicNotification(BluetoothGattCharacteristic c,boolean enable){
        if(closed||state!=STATE_CONNECTED||c==null||c.getService()==null)return false;
        if(enable)notifications.add(c);else notifications.remove(c);return true;
    }
    public boolean readRemoteRssi(){return operation(6,null,null,null,0);}
    /** CoreBluetooth negotiates ATT itself. The callback reports the measured
     * write-without-response payload limit + ATT header, not the requested MTU. */
    public boolean requestMtu(int mtu){return mtu>=23&&mtu<=517&&operation(7,null,null,null,mtu);}
    public boolean requestConnectionPriority(int priority){return false;}
    public boolean beginReliableWrite(){return false;}
    public boolean executeReliableWrite(){return false;}
    public void abortReliableWrite(){}
    @Deprecated public void abortReliableWrite(BluetoothDevice ignored){abortReliableWrite();}
    public void readPhy(){}
    public void setPreferredPhy(int txPhy,int rxPhy,int options){}
    private void receive(int event,String service,String characteristic,String descriptor,int value,int status,byte[] data){
        Runnable action=()->handle(event,service,characteristic,descriptor,value,status,data);
        if(mHandler!=null)mHandler.post(action);else action.run();
    }
    private synchronized void handle(int event,String service,String characteristic,String descriptor,int value,int status,byte[] data){
        if(closed)return;
        BluetoothGattService s=service==null?null:getService(UUID.fromString(service));
        BluetoothGattCharacteristic c=s==null||characteristic==null?null:s.getCharacteristic(UUID.fromString(characteristic));
        BluetoothGattDescriptor d=c==null||descriptor==null?null:c.getDescriptor(UUID.fromString(descriptor));
        switch(event){
            case 1:state=value;if(value==STATE_DISCONNECTED){notifications.clear();}mCallback.onConnectionStateChange(this,status,value);break;
            case 2:services.add(new BluetoothGattService(UUID.fromString(service),value));break;
            case 3:if(s!=null)s.addCharacteristic(new BluetoothGattCharacteristic(UUID.fromString(characteristic),value,0));break;
            case 4:if(c!=null)c.addDescriptor(new BluetoothGattDescriptor(UUID.fromString(descriptor),value));break;
            case 5:mCallback.onServicesDiscovered(this,status);break;
            case 6:if(c!=null){c.setValue(data);mCallback.onCharacteristicRead(this,c,data,status);}break;
            case 7:if(c!=null)mCallback.onCharacteristicWrite(this,c,status);break;
            case 8:if(c!=null&&notifications.contains(c)){c.setValue(data);mCallback.onCharacteristicChanged(this,c,data);}break;
            case 9:if(d!=null){d.setValue(data);mCallback.onDescriptorRead(this,d,status,data);}break;
            case 10:if(d!=null){if(status==GATT_SUCCESS)d.setValue(data);mCallback.onDescriptorWrite(this,d,status);}break;
            case 11:mCallback.onReadRemoteRssi(this,value,status);break;
            case 12:mCallback.onMtuChanged(this,value,status);break;
            default:break;
        }
    }
    @Override public int getConnectionState(BluetoothDevice device){return mDevice.equals(device)?state:STATE_DISCONNECTED;}
    @Override public List<BluetoothDevice> getConnectedDevices(){return !closed&&state==STATE_CONNECTED?List.of(mDevice):List.of();}
    @Override public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states){for(int s:states)if(s==state)return List.of(mDevice);return List.of();}
}
