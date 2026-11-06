package android.bluetooth.le;

import android.os.ParcelUuid;
import android.util.SparseArray;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Advertisement fields supplied by the host, or parsed from Android AD data. */
public final class ScanRecord {
    private int flags=-1,tx=Integer.MIN_VALUE;
    private String name;
    private byte[] bytes;
    private final List<ParcelUuid> services=new ArrayList<>(),solicitation=new ArrayList<>();
    private final Map<ParcelUuid,byte[]> serviceData=new LinkedHashMap<>();
    private final SparseArray<byte[]> manufacturer=new SparseArray<>();
    ScanRecord(){}
    public static ScanRecord parseFromBytes(byte[] bytes){
        if(bytes==null)return null;
        ScanRecord record=new ScanRecord();record.bytes=bytes.clone();
        for(int p=0;p<bytes.length;){
            int length=bytes[p++]&255;if(length==0)break;
            if(length>bytes.length-p)break;
            int type=bytes[p++]&255,end=p+length-1;
            try{
                if(type==1 && p<end)record.flags=bytes[p]&255;
                else if(type==8||type==9)record.name=new String(bytes,p,end-p,StandardCharsets.UTF_8);
                else if(type==10 && p<end)record.tx=bytes[p];
                else if(type==2||type==3||type==4||type==5||type==6||type==7||type==0x14||type==0x15||type==0x1f){
                    int size=type==2||type==3||type==0x14?2:type==4||type==5||type==0x1f?4:16;
                    List<ParcelUuid> list=type==0x14||type==0x15||type==0x1f?record.solicitation:record.services;
                    for(int q=p;q+size<=end;q+=size)list.add(uuid(bytes,q,size));
                }else if(type==0x16||type==0x20||type==0x21){
                    int size=type==0x16?2:type==0x20?4:16;
                    if(end-p>=size)record.serviceData.put(uuid(bytes,p,size),Arrays.copyOfRange(bytes,p+size,end));
                }else if(type==0xff&&end-p>=2){
                    int id=(bytes[p]&255)|((bytes[p+1]&255)<<8);record.manufacturer.put(id,Arrays.copyOfRange(bytes,p+2,end));
                }
            }catch(IllegalArgumentException ignored){/* malformed AD field is ignored */}
            p=end;
        }
        return record;
    }
    private static ParcelUuid uuid(byte[] bytes,int offset,int length){
        ByteBuffer in=ByteBuffer.wrap(bytes,offset,length).order(ByteOrder.LITTLE_ENDIAN);
        if(length==16){long least=in.getLong(),most=in.getLong();return new ParcelUuid(new UUID(most,least));}
        long shortId=length==2?in.getShort()&65535L:in.getInt()&0xffffffffL;
        return new ParcelUuid(new UUID((shortId<<32)|0x1000L,0x800000805f9b34fbL));
    }
    public int getAdvertiseFlags(){return flags;}
    public List<ParcelUuid> getServiceUuids(){return services.isEmpty()?null:Collections.unmodifiableList(services);}
    public Map<ParcelUuid,byte[]> getServiceData(){return Collections.unmodifiableMap(serviceData);}
    public byte[] getServiceData(ParcelUuid uuid){return serviceData.get(uuid);}
    public SparseArray<byte[]> getManufacturerSpecificData(){return manufacturer;}
    public byte[] getManufacturerSpecificData(int id){return manufacturer.get(id);}
    public List<ParcelUuid> getServiceSolicitationUuids(){return solicitation.isEmpty()?null:Collections.unmodifiableList(solicitation);}
    public String getDeviceName(){return name;}
    public int getTxPowerLevel(){return tx;}
    public byte[] getBytes(){return bytes==null?null:bytes.clone();}
    @Override public String toString(){return "ScanRecord{serviceUuids="+services+", name="+name+"}";}
}
