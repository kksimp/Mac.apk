package android.bluetooth.le;

import com.macapk.loader.BluetoothBridge;
import android.os.SystemClock;
import java.util.*;

/** CoreBluetooth-backed scanning. Filters are rechecked on the Android side;
 * CoreBluetooth's service UUID filter is only an optimization. */
public final class BluetoothLeScanner {
    public static final String EXTRA_CALLBACK_TYPE="android.bluetooth.le.extra.CALLBACK_TYPE";
    public static final String EXTRA_ERROR_CODE="android.bluetooth.le.extra.ERROR_CODE";
    public static final String EXTRA_LIST_SCAN_RESULT="android.bluetooth.le.extra.LIST_SCAN_RESULT";
    private static final Map<ScanCallback,Registration> ACTIVE=new LinkedHashMap<>();
    private record Registration(List<ScanFilter> filters,ScanSettings settings,Set<String> seen) {}
    public BluetoothLeScanner(){}
    public void startScan(ScanCallback callback){startScan(null,new ScanSettings.Builder().build(),callback);}
    public void startScan(List<ScanFilter> filters,ScanSettings settings,ScanCallback callback){
        if(callback==null)throw new IllegalArgumentException("callback cannot be null");
        if(settings==null)settings=new ScanSettings.Builder().build();
        if((settings.getCallbackType()&ScanSettings.CALLBACK_TYPE_MATCH_LOST)!=0 || settings.getReportDelayMillis()!=0){
            fail(callback,ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED);return;
        }
        synchronized(ACTIVE){
            if(ACTIVE.containsKey(callback)){fail(callback,ScanCallback.SCAN_FAILED_ALREADY_STARTED);return;}
            ACTIVE.put(callback,new Registration(filters==null?List.of():List.copyOf(filters),settings,new HashSet<>()));
            if(!refresh()){ACTIVE.remove(callback);fail(callback,ScanCallback.SCAN_FAILED_INTERNAL_ERROR);}
        }
    }
    public void stopScan(ScanCallback callback){
        if(callback==null)throw new IllegalArgumentException("callback cannot be null");
        synchronized(ACTIVE){if(ACTIVE.remove(callback)!=null)refresh();}
    }
    public int startScan(List<ScanFilter> filters,ScanSettings settings,android.app.PendingIntent callbackIntent){return ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED;}
    public void stopScan(android.app.PendingIntent callbackIntent){}
    public void flushPendingScanResults(ScanCallback callback){}
    private static void fail(ScanCallback cb,int error){BluetoothBridge.dispatch(()->cb.onScanFailed(error));}
    private static boolean refresh(){
        if(ACTIVE.isEmpty()){BluetoothBridge.stopScan();return true;}
        Set<String> services=new LinkedHashSet<>();boolean all=false;
        for(Registration r:ACTIVE.values()){
            if(r.filters.isEmpty()){all=true;break;}
            for(ScanFilter f:r.filters){
                if(f.getServiceUuid()==null||f.getServiceUuidMask()!=null){all=true;break;}
                services.add(f.getServiceUuid().toString());
            }
        }
        return BluetoothBridge.startScan(all?null:services.toArray(new String[0]),new BluetoothBridge.ScanSink(){
            public void failed(int error){
                List<ScanCallback> callbacks;
                synchronized(ACTIVE){callbacks=new ArrayList<>(ACTIVE.keySet());ACTIVE.clear();}
                for(ScanCallback callback:callbacks)callback.onScanFailed(error);
            }
            public void found(android.bluetooth.BluetoothDevice device,byte[] advertisement,int rssi,boolean connectable){
                ScanResult result=new ScanResult(device,connectable?1:0,android.bluetooth.BluetoothDevice.PHY_LE_1M,ScanResult.PHY_UNUSED,ScanResult.SID_NOT_PRESENT,ScanResult.TX_POWER_NOT_PRESENT,rssi,0,ScanRecord.parseFromBytes(advertisement),SystemClock.elapsedRealtimeNanos());
                Map<ScanCallback,Registration> snapshot;
                synchronized(ACTIVE){snapshot=new LinkedHashMap<>(ACTIVE);}
                for(var e:snapshot.entrySet()){
                    Registration r=e.getValue();boolean matches=r.filters.isEmpty();
                    for(ScanFilter f:r.filters)if(f.matches(result)){matches=true;break;}
                    if(!matches)continue;
                    int type=r.settings.getCallbackType();
                    synchronized(ACTIVE){
                        if(ACTIVE.get(e.getKey())!=r)continue;
                        if(type==ScanSettings.CALLBACK_TYPE_FIRST_MATCH&&!r.seen.add(device.getAddress()))continue;
                    }
                    e.getKey().onScanResult(type,result);
                }
            }
        });
    }
}
