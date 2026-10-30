// CoreBluetooth central transport. All mutable radio state lives on one serial
// queue; Java callbacks only enqueue onto the Java event executor.
#import <CoreBluetooth/CoreBluetooth.h>
#import <IOBluetooth/IOBluetooth.h>
#import <Foundation/Foundation.h>
#include <jni.h>
#include <atomic>
#include <string>
#include <cstdio>

@interface MacApkBLEWrite : NSObject
@property(nonatomic,strong) CBCharacteristic* characteristic;
@property(nonatomic,strong) NSData* value;
@end
@implementation MacApkBLEWrite
@end
@interface MacApkBLEConnection : NSObject
@property(nonatomic,strong) CBPeripheral* peripheral;
@property(nonatomic,assign) jlong token;
@property(nonatomic,assign) NSInteger pending;
@property(nonatomic,assign) int discoveryStatus;
@property(nonatomic,strong) NSMutableSet<CBCharacteristic*>* reads;
@property(nonatomic,strong) NSMapTable<CBCharacteristic*,NSData*>* notificationWrites;
@property(nonatomic,strong) NSMutableArray<MacApkBLEWrite*>* writes;
@property(nonatomic,strong) NSMapTable<CBDescriptor*,NSData*>* descriptorWrites;
@end
@implementation MacApkBLEConnection
- (instancetype)init { if((self=[super init])){_reads=[NSMutableSet new];_notificationWrites=[NSMapTable strongToStrongObjectsMapTable];_writes=[NSMutableArray new];_descriptorWrites=[NSMapTable strongToStrongObjectsMapTable];}return self; }
@end
@interface MacApkBLECentral : NSObject<CBCentralManagerDelegate,CBPeripheralDelegate>
@end
namespace macapk::bluetooth {
namespace {
JavaVM* vm=nullptr;
jclass bridge=nullptr;
jmethodID onScan=nullptr,onScanFailure=nullptr,onGatt=nullptr;
dispatch_queue_t queue;
CBCentralManager* central;
MacApkBLECentral* delegate;
NSMutableDictionary<NSString*,CBPeripheral*>* peers;
NSMutableDictionary<NSNumber*,MacApkBLEConnection*>* connections;
std::atomic<int> managerState{-1};
bool scanRequested=false;
NSArray<CBUUID*>* scanServices;
JNIEnv* Env(){JNIEnv* e=nullptr;if(!vm)return nullptr;auto r=vm->GetEnv((void**)&e,JNI_VERSION_21);if(r==JNI_EDETACHED&&vm->AttachCurrentThreadAsDaemon((void**)&e,nullptr)!=JNI_OK)return nullptr;return e;}
NSString* String(JNIEnv* e,jstring s){if(!s)return nil;const char* v=e->GetStringUTFChars(s,nullptr);NSString* result=v?[NSString stringWithUTF8String:v]:nil;if(v)e->ReleaseStringUTFChars(s,v);return result;}
jstring JavaString(JNIEnv* e,NSString* s){return s?e->NewStringUTF(s.UTF8String):nullptr;}
NSData* Bytes(JNIEnv* e,jbyteArray b){if(!b)return nil;jsize n=e->GetArrayLength(b);NSMutableData* d=[NSMutableData dataWithLength:n];if(n)e->GetByteArrayRegion(b,0,n,(jbyte*)d.mutableBytes);return d;}
jbyteArray JavaBytes(JNIEnv* e,NSData* d){if(!d)return nullptr;jbyteArray b=e->NewByteArray((jsize)d.length);if(b&&d.length)e->SetByteArrayRegion(b,0,(jsize)d.length,(const jbyte*)d.bytes);return b;}
void Finish(JNIEnv* e){if(e->ExceptionCheck()){std::fprintf(stderr,"[W] Bluetooth callback dispatch threw\n");e->ExceptionDescribe();e->ExceptionClear();}e->PopLocalFrame(nullptr);}
NSString* FullUUID(CBUUID* uuid){NSString* s=uuid.UUIDString;if(s.length==4)return [NSString stringWithFormat:@"0000%@-0000-1000-8000-00805f9b34fb",s.lowercaseString];if(s.length==8)return [NSString stringWithFormat:@"%@-0000-1000-8000-00805f9b34fb",s.lowercaseString];return s.lowercaseString;}
int Status(NSError* error){if(!error)return 0;if([error.domain isEqualToString:CBATTErrorDomain])return (int)error.code;return 0x101;}
void Event(MacApkBLEConnection* c,int event,CBService* service,CBCharacteristic* characteristic,CBDescriptor* descriptor,int value,int status,NSData* data){
    JNIEnv* e=Env();if(!e||!bridge||!c)return;if(e->PushLocalFrame(16)<0)return;
    e->CallStaticVoidMethod(bridge,onGatt,c.token,event,JavaString(e,service?FullUUID(service.UUID):nil),JavaString(e,characteristic?FullUUID(characteristic.UUID):nil),JavaString(e,descriptor?FullUUID(descriptor.UUID):nil),value,status,JavaBytes(e,data));Finish(e);
}
void DescriptorEvent(MacApkBLEConnection* c,int event,CBCharacteristic* characteristic,NSString* descriptor,int status,NSData* data){
    JNIEnv* e=Env();if(!e||!bridge)return;if(e->PushLocalFrame(16)<0)return;
    e->CallStaticVoidMethod(bridge,onGatt,c.token,event,JavaString(e,FullUUID(characteristic.service.UUID)),JavaString(e,FullUUID(characteristic.UUID)),JavaString(e,descriptor),0,status,JavaBytes(e,data));Finish(e);
}
void ScanFailure(int error){JNIEnv* e=Env();if(!e||!bridge||e->PushLocalFrame(2)<0)return;e->CallStaticVoidMethod(bridge,onScanFailure,error);Finish(e);}
bool EnsureManager(){
    if(central)return true;
    // Allocating a manager is an authorization request. Never do it for a
    // feature probe or with a bundle missing its required privacy purpose.
    if(![[NSBundle mainBundle] objectForInfoDictionaryKey:@"NSBluetoothAlwaysUsageDescription"]){std::fprintf(stderr,"[W] Bluetooth: responsible bundle has no usage description\n");return false;}
    managerState.store(CBManagerStateUnknown);delegate=[MacApkBLECentral new];peers=[NSMutableDictionary new];connections=[NSMutableDictionary new];
    central=[[CBCentralManager alloc]initWithDelegate:delegate queue:queue options:@{CBCentralManagerOptionShowPowerAlertKey:@NO}];return central!=nil;
}
MacApkBLEConnection* Connection(CBPeripheral* p){for(MacApkBLEConnection* c in connections.allValues)if(c.peripheral==p)return c;return nil;}
CBCharacteristic* Characteristic(MacApkBLEConnection* c,NSString* service,NSString* characteristic){
    CBCharacteristic* found=nil;
    for(CBService* s in c.peripheral.services)if([FullUUID(s.UUID) isEqualToString:service])for(CBCharacteristic* ch in s.characteristics)if([FullUUID(ch.UUID) isEqualToString:characteristic]){if(found)return nil;found=ch;}
    return found;
}
CBDescriptor* Descriptor(CBCharacteristic* c,NSString* id){for(CBDescriptor* d in c.descriptors)if([FullUUID(d.UUID)isEqualToString:id])return d;return nil;}
void DiscoveryStep(MacApkBLEConnection* c,NSError* error){if(error)c.discoveryStatus=Status(error);if(--c.pending==0)Event(c,5,nil,nil,nil,0,c.discoveryStatus,nil);}
NSData* DescriptorData(id value){
    if([value isKindOfClass:NSData.class])return value;
    if([value isKindOfClass:NSString.class])return [(NSString*)value dataUsingEncoding:NSUTF8StringEncoding];
    if([value isKindOfClass:NSNumber.class]){uint16_t x=[value unsignedShortValue];uint8_t b[2]={(uint8_t)x,(uint8_t)(x>>8)};return [NSData dataWithBytes:b length:2];}return nil;
}
void DrainWrites(MacApkBLEConnection* c){while(c.writes.count && c.peripheral.canSendWriteWithoutResponse && c.peripheral.state==CBPeripheralStateConnected){MacApkBLEWrite* w=c.writes.firstObject;[c.writes removeObjectAtIndex:0];[c.peripheral writeValue:w.value forCharacteristic:w.characteristic type:CBCharacteristicWriteWithoutResponse];Event(c,7,w.characteristic.service,w.characteristic,nil,0,0,nil);}}
void AddAD(NSMutableData* out,uint8_t type,NSData* bytes){if(!bytes||bytes.length>254)return;uint8_t header[2]={(uint8_t)(bytes.length+1),type};[out appendBytes:header length:2];[out appendData:bytes];}
NSData* UUIDBytes(CBUUID* uuid){NSData* d=uuid.data;NSMutableData* reversed=[NSMutableData dataWithLength:d.length];const uint8_t* src=(const uint8_t*)d.bytes;uint8_t* dst=(uint8_t*)reversed.mutableBytes;for(NSUInteger i=0;i<d.length;i++)dst[i]=src[d.length-1-i];return reversed;}
NSData* Advertisement(NSDictionary<NSString*,id>* fields){
    // CoreBluetooth supplies decoded fields, not raw controller packets. Build
    // equivalent AD structures solely from the fields the host observed.
    NSMutableData* result=[NSMutableData new];
    NSString* name=fields[CBAdvertisementDataLocalNameKey];if(name)AddAD(result,9,[name dataUsingEncoding:NSUTF8StringEncoding]);
    for(CBUUID* uuid in fields[CBAdvertisementDataServiceUUIDsKey]){NSData* bytes=UUIDBytes(uuid);AddAD(result,bytes.length==2?3:bytes.length==4?5:7,bytes);}
    for(CBUUID* uuid in fields[CBAdvertisementDataOverflowServiceUUIDsKey]){NSData* bytes=UUIDBytes(uuid);AddAD(result,bytes.length==2?3:bytes.length==4?5:7,bytes);}
    NSDictionary<CBUUID*,NSData*>* service=fields[CBAdvertisementDataServiceDataKey];
    for(CBUUID* uuid in service){NSMutableData* d=[UUIDBytes(uuid) mutableCopy];[d appendData:service[uuid]];AddAD(result,uuid.data.length==2?0x16:uuid.data.length==4?0x20:0x21,d);}
    NSData* manufacturer=fields[CBAdvertisementDataManufacturerDataKey];if(manufacturer)AddAD(result,0xff,manufacturer);
    NSNumber* tx=fields[CBAdvertisementDataTxPowerLevelKey];if(tx){int8_t power=tx.charValue;AddAD(result,10,[NSData dataWithBytes:&power length:1]);}
    return result;
}
static jboolean Available(JNIEnv*,jclass){@autoreleasepool{return [IOBluetoothHostController defaultController]!=nil&&managerState.load()!=CBManagerStateUnsupported;}}
static jint State(JNIEnv*,jclass){@autoreleasepool{
    int s=managerState.load();
    // Once initialized, CoreBluetooth owns the BLE state. In particular, do
    // not overwrite its PoweredOn answer with the legacy Classic controller's
    // power query (which may be unavailable for this process/controller).
    if(s==CBManagerStatePoweredOn)return 12;
    if(s==CBManagerStateUnsupported||s==CBManagerStatePoweredOff||s==CBManagerStateUnauthorized)return 10;
    if(s==CBManagerStateResetting)return 13;
    IOBluetoothHostController* h=[IOBluetoothHostController defaultController];
    return h&&h.powerState==kBluetoothHCIPowerStateON?12:10;
}}
static jint Authorization(JNIEnv*,jclass){return (jint)CBManager.authorization;}
static jboolean TransportReady(JNIEnv*,jclass){int s=managerState.load();return s>=0&&s!=CBManagerStateUnknown&&s!=CBManagerStateResetting;}
static jboolean RequestAuthorization(JNIEnv*,jclass){__block bool ok=false;dispatch_sync(queue,^{ok=EnsureManager();});return ok;}
static jboolean Scan(JNIEnv* e,jclass,jobjectArray filters){
    NSMutableArray<CBUUID*>* uuids=nil;if(filters){uuids=[NSMutableArray new];jsize n=e->GetArrayLength(filters);for(jsize i=0;i<n;i++){jstring v=(jstring)e->GetObjectArrayElement(filters,i);NSString* s=String(e,v);e->DeleteLocalRef(v);if(s)[uuids addObject:[CBUUID UUIDWithString:s]];}}
    __block bool ok=false;dispatch_sync(queue,^{
        if(!EnsureManager())return;
        if(central.state==CBManagerStatePoweredOn){
            [central stopScan];scanServices=[uuids copy];scanRequested=true;
            [central scanForPeripheralsWithServices:scanServices options:@{CBCentralManagerScanOptionAllowDuplicatesKey:@YES}];ok=central.isScanning;
        }else if(central.state==CBManagerStateUnknown || central.state==CBManagerStateResetting){
            // A just-authorized manager may not have delivered its first state
            // callback yet. Keep the scan registration; start only on PoweredOn.
            scanServices=[uuids copy];scanRequested=true;ok=true;
        }
    });return ok;
}
static void StopScan(JNIEnv*,jclass){dispatch_async(queue,^{scanRequested=false;scanServices=nil;[central stopScan];});}
static jboolean Connect(JNIEnv* e,jclass,jstring id,jlong token){NSString* identifier=String(e,id);__block bool ok=false;dispatch_sync(queue,^{
    CBPeripheral* p=peers[identifier];if(!p||central.state!=CBManagerStatePoweredOn||Connection(p))return;
    MacApkBLEConnection* c=[MacApkBLEConnection new];c.token=token;c.peripheral=p;p.delegate=delegate;connections[@(token)]=c;[central connectPeripheral:p options:nil];ok=true;
});return ok;}
static void Disconnect(JNIEnv*,jclass,jlong token,jboolean close){dispatch_async(queue,^{MacApkBLEConnection* c=connections[@(token)];if(!c)return;[central cancelPeripheralConnection:c.peripheral];if(close){c.peripheral.delegate=nil;[connections removeObjectForKey:@(token)];}});}
static jboolean Operation(JNIEnv* e,jclass,jlong token,jint operation,jstring s,jstring ch,jstring d,jbyteArray bytes,jint value){
    NSString* service=String(e,s),*characteristic=String(e,ch),*descriptor=String(e,d);NSData* data=Bytes(e,bytes);__block bool ok=false;
    dispatch_sync(queue,^{MacApkBLEConnection* c=connections[@(token)];if(!c||c.peripheral.state!=CBPeripheralStateConnected)return;
        CBCharacteristic* target=characteristic?Characteristic(c,service,characteristic):nil;
        CBDescriptor* desc=descriptor?Descriptor(target,descriptor):nil;
        switch(operation){
            case 1:if(c.pending!=0)return;c.pending=1;c.discoveryStatus=0;[c.peripheral discoverServices:nil];ok=true;break;
            case 2:if(!target||!(target.properties&CBCharacteristicPropertyRead))return;[c.reads addObject:target];[c.peripheral readValueForCharacteristic:target];ok=true;break;
            case 3:{
                if(!target||!data||c.writes.count>=128)return;
                CBCharacteristicWriteType type=value==1?CBCharacteristicWriteWithoutResponse:CBCharacteristicWriteWithResponse;
                if(value!=1&&value!=2)return;
                if(!(target.properties&(value==1?CBCharacteristicPropertyWriteWithoutResponse:CBCharacteristicPropertyWrite)))return;
                if(data.length>[c.peripheral maximumWriteValueLengthForType:type])return;
                if(type==CBCharacteristicWriteWithoutResponse){MacApkBLEWrite* write=[MacApkBLEWrite new];write.characteristic=target;write.value=data;[c.writes addObject:write];DrainWrites(c);}
                else [c.peripheral writeValue:data forCharacteristic:target type:type];ok=true;break;
            }
            case 4:
                if([descriptor isEqualToString:@"00002902-0000-1000-8000-00805f9b34fb"] && target){
                    uint8_t b[2]={(uint8_t)(target.isNotifying?((target.properties&CBCharacteristicPropertyNotify)?1:2):0),0};
                    DescriptorEvent(c,9,target,descriptor,0,[NSData dataWithBytes:b length:2]);ok=true;
                }else if(desc){[c.peripheral readValueForDescriptor:desc];ok=true;}break;
            case 5:{
                if(!target||!data)return;
                if([descriptor isEqualToString:@"00002902-0000-1000-8000-00805f9b34fb"]){
                    if(data.length!=2||[c.notificationWrites objectForKey:target])return;const uint8_t* p=(const uint8_t*)data.bytes;
                    if(p[1]!=0||p[0]>2)return;
                    if((p[0]==1&&!(target.properties&CBCharacteristicPropertyNotify))||(p[0]==2&&(!(target.properties&CBCharacteristicPropertyIndicate)||(target.properties&CBCharacteristicPropertyNotify))))return;
                    [c.notificationWrites setObject:data forKey:target];[c.peripheral setNotifyValue:p[0]!=0 forCharacteristic:target];ok=true;
                }else if(desc && ![c.descriptorWrites objectForKey:desc]){[c.descriptorWrites setObject:data forKey:desc];[c.peripheral writeValue:data forDescriptor:desc];ok=true;}break;
            }
            case 6:[c.peripheral readRSSI];ok=true;break;
            case 7:{NSUInteger limit=[c.peripheral maximumWriteValueLengthForType:CBCharacteristicWriteWithoutResponse];if(limit<20)return;Event(c,12,nil,nil,nil,(int)MIN((NSUInteger)517,limit+3),0,nil);ok=true;break;}
            default:break;
        }
    });return ok;
}
}
void RegisterBluetoothBridgeMethods(JNIEnv* e){
    e->GetJavaVM(&vm);jclass c=e->FindClass("com/macapk/loader/BluetoothBridge");bridge=(jclass)e->NewGlobalRef(c);
    onScan=e->GetStaticMethodID(c,"onScan","(Ljava/lang/String;Ljava/lang/String;[BIZ)V");onScanFailure=e->GetStaticMethodID(c,"onScanFailure","(I)V");
    onGatt=e->GetStaticMethodID(c,"onGatt","(JILjava/lang/String;Ljava/lang/String;Ljava/lang/String;II[B)V");
    const JNINativeMethod methods[]={
        {(char*)"nativeAvailable",(char*)"()Z",(void*)Available},{(char*)"nativeState",(char*)"()I",(void*)State},
        {(char*)"nativeAuthorization",(char*)"()I",(void*)Authorization},{(char*)"nativeTransportReady",(char*)"()Z",(void*)TransportReady},{(char*)"nativeRequestAuthorization",(char*)"()Z",(void*)RequestAuthorization},
        {(char*)"nativeScan",(char*)"([Ljava/lang/String;)Z",(void*)Scan},{(char*)"nativeStopScan",(char*)"()V",(void*)StopScan},
        {(char*)"nativeConnect",(char*)"(Ljava/lang/String;J)Z",(void*)Connect},{(char*)"nativeDisconnect",(char*)"(JZ)V",(void*)Disconnect},
        {(char*)"nativeOperation",(char*)"(JILjava/lang/String;Ljava/lang/String;Ljava/lang/String;[BI)Z",(void*)Operation}};
    if(!c||!onScan||!onGatt||e->RegisterNatives(c,methods,sizeof(methods)/sizeof(methods[0]))!=JNI_OK){if(e->ExceptionCheck())e->ExceptionDescribe();std::abort();}
    e->DeleteLocalRef(c);queue=dispatch_queue_create("org.macapk.bluetooth.central",DISPATCH_QUEUE_SERIAL);
}
} // namespace macapk::bluetooth

@implementation MacApkBLECentral
- (void)centralManagerDidUpdateState:(CBCentralManager*)manager{
    using namespace macapk::bluetooth;managerState.store((int)manager.state);
    if(manager.state==CBManagerStatePoweredOn && scanRequested && !manager.isScanning){
        [manager scanForPeripheralsWithServices:scanServices options:@{CBCentralManagerScanOptionAllowDuplicatesKey:@YES}];
    }
    if(manager.state==CBManagerStatePoweredOff || manager.state==CBManagerStateUnauthorized || manager.state==CBManagerStateUnsupported){
        if(scanRequested){scanRequested=false;[manager stopScan];ScanFailure(3);}
        for(MacApkBLEConnection* c in connections.allValues){[c.writes removeAllObjects];Event(c,1,nil,nil,nil,0,0x101,nil);}
    }
}
- (void)centralManager:(CBCentralManager*)manager didDiscoverPeripheral:(CBPeripheral*)p advertisementData:(NSDictionary<NSString*,id>*)advertisement RSSI:(NSNumber*)rssi{
    using namespace macapk::bluetooth;peers[p.identifier.UUIDString]=p;
    JNIEnv* e=Env();if(!e||e->PushLocalFrame(8)<0)return;
    e->CallStaticVoidMethod(bridge,onScan,JavaString(e,p.identifier.UUIDString),JavaString(e,advertisement[CBAdvertisementDataLocalNameKey]?:p.name),JavaBytes(e,Advertisement(advertisement)),(jint)rssi.intValue,(jboolean)[advertisement[CBAdvertisementDataIsConnectable] boolValue]);Finish(e);
}
- (void)centralManager:(CBCentralManager*)manager didConnectPeripheral:(CBPeripheral*)p{
    using namespace macapk::bluetooth;MacApkBLEConnection* c=Connection(p);if(c)Event(c,1,nil,nil,nil,2,0,nil);
}
- (void)centralManager:(CBCentralManager*)manager didFailToConnectPeripheral:(CBPeripheral*)p error:(NSError*)error{
    using namespace macapk::bluetooth;MacApkBLEConnection* c=Connection(p);if(c)Event(c,1,nil,nil,nil,0,Status(error?:[NSError errorWithDomain:CBErrorDomain code:0 userInfo:nil]),nil);
}
- (void)centralManager:(CBCentralManager*)manager didDisconnectPeripheral:(CBPeripheral*)p error:(NSError*)error{
    using namespace macapk::bluetooth;MacApkBLEConnection* c=Connection(p);if(c){[c.writes removeAllObjects];[c.notificationWrites removeAllObjects];[c.reads removeAllObjects];c.pending=0;Event(c,1,nil,nil,nil,0,Status(error),nil);}
}
- (void)peripheral:(CBPeripheral*)p didDiscoverServices:(NSError*)error{
    using namespace macapk::bluetooth;MacApkBLEConnection* c=Connection(p);if(!c||c.pending<=0||p.state!=CBPeripheralStateConnected)return;
    if(!error)for(CBService* service in p.services){Event(c,2,service,nil,nil,service.isPrimary?0:1,0,nil);c.pending++;[p discoverCharacteristics:nil forService:service];}
    DiscoveryStep(c,error);
}
- (void)peripheral:(CBPeripheral*)p didDiscoverCharacteristicsForService:(CBService*)service error:(NSError*)error{
    using namespace macapk::bluetooth;MacApkBLEConnection* c=Connection(p);if(!c||c.pending<=0||p.state!=CBPeripheralStateConnected)return;
    if(!error)for(CBCharacteristic* characteristic in service.characteristics){Event(c,3,service,characteristic,nil,(int)characteristic.properties,0,nil);c.pending++;[p discoverDescriptorsForCharacteristic:characteristic];}
    DiscoveryStep(c,error);
}
- (void)peripheral:(CBPeripheral*)p didDiscoverDescriptorsForCharacteristic:(CBCharacteristic*)characteristic error:(NSError*)error{
    using namespace macapk::bluetooth;MacApkBLEConnection* c=Connection(p);if(!c||c.pending<=0||p.state!=CBPeripheralStateConnected)return;
    if(!error){bool cccd=false;for(CBDescriptor* descriptor in characteristic.descriptors){Event(c,4,characteristic.service,characteristic,descriptor,0,0,nil);if([FullUUID(descriptor.UUID)isEqualToString:@"00002902-0000-1000-8000-00805f9b34fb"])cccd=true;}
        // CoreBluetooth manages the CCCD itself and may omit it from the
        // descriptor inventory. Expose the corresponding real notification
        // operation whenever the characteristic supports notify/indicate.
        if(!cccd&&(characteristic.properties&(CBCharacteristicPropertyNotify|CBCharacteristicPropertyIndicate)))DescriptorEvent(c,4,characteristic,@"00002902-0000-1000-8000-00805f9b34fb",0,nil);
    }DiscoveryStep(c,error);
}
- (void)peripheral:(CBPeripheral*)p didUpdateValueForCharacteristic:(CBCharacteristic*)characteristic error:(NSError*)error{
    using namespace macapk::bluetooth;MacApkBLEConnection* c=Connection(p);if(!c)return;bool read=[c.reads containsObject:characteristic];if(error&&!read)return;if(read)[c.reads removeObject:characteristic];Event(c,read?6:8,characteristic.service,characteristic,nil,0,Status(error),characteristic.value);
}
- (void)peripheral:(CBPeripheral*)p didWriteValueForCharacteristic:(CBCharacteristic*)characteristic error:(NSError*)error{
    using namespace macapk::bluetooth;MacApkBLEConnection* c=Connection(p);if(c)Event(c,7,characteristic.service,characteristic,nil,0,Status(error),nil);
}
- (void)peripheral:(CBPeripheral*)p didUpdateNotificationStateForCharacteristic:(CBCharacteristic*)characteristic error:(NSError*)error{
    using namespace macapk::bluetooth;MacApkBLEConnection* c=Connection(p);if(!c)return;NSData* data=[c.notificationWrites objectForKey:characteristic];[c.notificationWrites removeObjectForKey:characteristic];if(data)DescriptorEvent(c,10,characteristic,@"00002902-0000-1000-8000-00805f9b34fb",Status(error),data);
}
- (void)peripheral:(CBPeripheral*)p didUpdateValueForDescriptor:(CBDescriptor*)descriptor error:(NSError*)error{
    using namespace macapk::bluetooth;MacApkBLEConnection* c=Connection(p);if(c)Event(c,9,descriptor.characteristic.service,descriptor.characteristic,descriptor,0,Status(error),DescriptorData(descriptor.value));
}
- (void)peripheral:(CBPeripheral*)p didWriteValueForDescriptor:(CBDescriptor*)descriptor error:(NSError*)error{
    using namespace macapk::bluetooth;MacApkBLEConnection* c=Connection(p);if(c){NSData* value=[c.descriptorWrites objectForKey:descriptor];[c.descriptorWrites removeObjectForKey:descriptor];Event(c,10,descriptor.characteristic.service,descriptor.characteristic,descriptor,0,Status(error),value);}
}
- (void)peripheral:(CBPeripheral*)p didReadRSSI:(NSNumber*)rssi error:(NSError*)error{
    using namespace macapk::bluetooth;MacApkBLEConnection* c=Connection(p);if(c)Event(c,11,nil,nil,nil,rssi.intValue,Status(error),nil);
}
- (void)peripheralIsReadyToSendWriteWithoutResponse:(CBPeripheral*)p{using namespace macapk::bluetooth;MacApkBLEConnection* c=Connection(p);if(c)DrainWrites(c);}
@end
