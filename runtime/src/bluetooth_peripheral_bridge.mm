// Real BLE peripheral transport. Compile this translation unit with ARC.
#import <CoreBluetooth/CoreBluetooth.h>
#import <Foundation/Foundation.h>
#include <jni.h>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <mutex>
#include <string>

namespace {
JavaVM* vm = nullptr;
jmethodID sessionEvent = nullptr, serviceAdded = nullptr, advertisementResult = nullptr, advertisementStopped = nullptr;
dispatch_queue_t radioQueue, callbackQueue;
char queueKey;
std::atomic<int> state{CBManagerStateUnknown};
std::mutex stateMutex;
std::condition_variable stateChanged;
std::atomic<uint64_t> nextHandle{1};
std::atomic<int> nextRequest{1};
struct Environment {
    JNIEnv* env = nullptr; bool attached = false;
    Environment() {
        if (!vm) return;
        if (vm->GetEnv((void**)&env, JNI_VERSION_1_8) == JNI_EDETACHED)
            attached = vm->AttachCurrentThreadAsDaemon((void**)&env, nullptr) == JNI_OK;
    }
    ~Environment() { if (attached) vm->DetachCurrentThread(); }
};
void deleteReference(jobject reference) { Environment e; if (e.env && reference) e.env->DeleteGlobalRef(reference); }
void clearCallbackException(JNIEnv* env) {
    if (env->ExceptionCheck()) { env->ExceptionClear(); std::fprintf(stderr,"[bluetooth] peripheral Java callback dispatch failed\n"); }
}
NSString* string(JNIEnv* env, jstring value) {
    if (!value) return nil;
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (!chars) return nil;
    NSString* result = [[NSString alloc] initWithCharacters:(const unichar*)chars length:env->GetStringLength(value)];
    env->ReleaseStringChars(value, chars); return result;
}
NSData* data(JNIEnv* env, jbyteArray value) {
    if (!value) return nil;
    jsize length = env->GetArrayLength(value);
    NSMutableData* result = [NSMutableData dataWithLength:length];
    if (length) env->GetByteArrayRegion(value, 0, length, (jbyte*)result.mutableBytes);
    return result;
}
jbyteArray byteArray(JNIEnv* env, NSData* value) {
    if (!value) return nullptr;
    jbyteArray result = env->NewByteArray((jsize)value.length);
    if (result && value.length) env->SetByteArrayRegion(result, 0, (jsize)value.length, (const jbyte*)value.bytes);
    return result;
}
bool authorized() {
    const char* flag = std::getenv("MACAPK_BLUETOOTH_HOST");
    return flag && flag[0] == '1' && [CBManager authorization] == CBManagerAuthorizationAllowedAlways;
}
}

@interface MacApkPeripheralRequest : NSObject
@property(nonatomic, strong) CBATTRequest* request;
@property(nonatomic, copy) NSString* identifier;
@property(nonatomic) int characteristic;
@property(nonatomic) BOOL acknowledged;
@end
@implementation MacApkPeripheralRequest
@end

@interface MacApkPeripheralNotice : NSObject
@property(nonatomic, strong) CBCentral* central;
@property(nonatomic, strong) CBMutableCharacteristic* characteristic;
@property(nonatomic, strong) NSData* value;
@end
@implementation MacApkPeripheralNotice
@end

@interface MacApkPeripheralServer : NSObject {
@public
    jobject peer;
    uint64_t handle;
    BOOL closed;
    NSMutableDictionary<NSNumber*, CBMutableService*>* services;
    NSMutableDictionary<NSNumber*, CBMutableCharacteristic*>* characteristics;
    NSMapTable<CBCharacteristic*, NSNumber*>* characteristicIds;
    NSMutableSet<NSNumber*>* published;
    NSMutableDictionary<NSString*, CBCentral*>* centrals;
    NSMutableDictionary<NSString*, NSMutableSet<NSNumber*>*>* subscriptions;
    NSMutableDictionary<NSNumber*, MacApkPeripheralRequest*>* requests;
    NSMutableArray<MacApkPeripheralNotice*>* notices;
}
@end
@implementation MacApkPeripheralServer
- (instancetype)init {
    if ((self = [super init])) {
        services = [NSMutableDictionary dictionary]; characteristics = [NSMutableDictionary dictionary];
        characteristicIds = [NSMapTable mapTableWithKeyOptions:NSPointerFunctionsStrongMemory|NSPointerFunctionsObjectPointerPersonality valueOptions:NSPointerFunctionsStrongMemory];
        published = [NSMutableSet set]; centrals = [NSMutableDictionary dictionary];
        subscriptions = [NSMutableDictionary dictionary]; requests = [NSMutableDictionary dictionary]; notices = [NSMutableArray array];
    }
    return self;
}
- (void)dealloc { deleteReference(peer); }
@end

@class MacApkPeripheralAdvertisementDelegate;
@interface MacApkPeripheralAdvertisement : NSObject {
@public
    jobject peer;
    uint64_t handle;
    BOOL completed;
    BOOL submitted;
    int timeout;
    CBPeripheralManager* advertiser;
    MacApkPeripheralAdvertisementDelegate* advertisingDelegate;
    NSDictionary* payload;
}
@end
@implementation MacApkPeripheralAdvertisement
- (void)dealloc { deleteReference(peer); }
@end

@interface MacApkPeripheralDelegate : NSObject<CBPeripheralManagerDelegate>
@end
@interface MacApkPeripheralAdvertisementDelegate : NSObject<CBPeripheralManagerDelegate>
@property(nonatomic, weak) MacApkPeripheralAdvertisement* owner;
@end

namespace {
CBPeripheralManager* manager;
MacApkPeripheralDelegate* delegate;
NSMutableDictionary<NSNumber*, MacApkPeripheralServer*>* servers;
NSMapTable<CBService*, MacApkPeripheralServer*>* serviceOwners;
NSMapTable<CBCharacteristic*, MacApkPeripheralServer*>* characteristicOwners;
MacApkPeripheralAdvertisement* advertising;
void initializeQueues() {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        radioQueue = dispatch_queue_create("org.macapk.bluetooth.peripheral", DISPATCH_QUEUE_SERIAL);
        dispatch_queue_set_specific(radioQueue, &queueKey, &queueKey, nullptr);
        callbackQueue = dispatch_queue_create("org.macapk.bluetooth.peripheral.callbacks", DISPATCH_QUEUE_SERIAL);
        servers = [NSMutableDictionary dictionary];
        serviceOwners = [NSMapTable mapTableWithKeyOptions:NSPointerFunctionsStrongMemory|NSPointerFunctionsObjectPointerPersonality valueOptions:NSPointerFunctionsStrongMemory];
        characteristicOwners = [NSMapTable mapTableWithKeyOptions:NSPointerFunctionsStrongMemory|NSPointerFunctionsObjectPointerPersonality valueOptions:NSPointerFunctionsStrongMemory];
    });
}
void sync(void (^action)(void)) {
    initializeQueues();
    if (dispatch_get_specific(&queueKey)) action(); else dispatch_sync(radioQueue, action);
}
void ensureManager() {
    sync(^{
        if (!manager && authorized()) {
            delegate = [MacApkPeripheralDelegate new];
            manager = [[CBPeripheralManager alloc] initWithDelegate:delegate queue:radioQueue
                    options:@{CBPeripheralManagerOptionShowPowerAlertKey:@NO}];
        }
    });
}
void event(MacApkPeripheralServer* server, int kind, int id, int auxiliary,
           NSString* identifier, int status, int offset, NSData* value) {
    // Capturing server retains its global peer until this callback has finished,
    // even if close() removes the registry entry in the meantime.
    dispatch_async(callbackQueue, ^{
        @autoreleasepool {
            Environment e; if (!e.env || !server->peer) return;
            if (e.env->PushLocalFrame(8) != JNI_OK) return;
            jstring central = identifier ? e.env->NewStringUTF(identifier.UTF8String) : nullptr;
            e.env->CallVoidMethod(server->peer, sessionEvent, kind, id, auxiliary, central, status, offset, byteArray(e.env, value));
            clearCallbackException(e.env); e.env->PopLocalFrame(nullptr);
        }
    });
}
void added(MacApkPeripheralServer* server, int id, int status) {
    dispatch_async(callbackQueue, ^{
        Environment e; if (!e.env || !server->peer) return;
        e.env->CallVoidMethod(server->peer, serviceAdded, id, status); clearCallbackException(e.env);
    });
}
void adResult(MacApkPeripheralAdvertisement* advertisement, int result) {
    dispatch_async(callbackQueue, ^{
        Environment e; if (!e.env || !advertisement->peer) return;
        e.env->CallVoidMethod(advertisement->peer, advertisementResult, result); clearCallbackException(e.env);
    });
}
void adStopped(MacApkPeripheralAdvertisement* advertisement) {
    dispatch_async(callbackQueue, ^{
        Environment e; if (!e.env || !advertisement->peer) return;
        e.env->CallVoidMethod(advertisement->peer, advertisementStopped); clearCallbackException(e.env);
    });
}
MacApkPeripheralServer* serverFor(CBCharacteristic* characteristic) { return [characteristicOwners objectForKey:characteristic]; }
int rememberRequest(MacApkPeripheralServer* server, CBATTRequest* request, NSString* identifier,
                    int characteristic, bool acknowledged) {
    if (server->requests.count >= 128) return 0;
    int id = nextRequest.fetch_add(1);
    MacApkPeripheralRequest* pending = [MacApkPeripheralRequest new];
    pending.request = request; pending.identifier = identifier; pending.characteristic = characteristic;
    pending.acknowledged = acknowledged; server->requests[@(id)] = pending;
    __weak MacApkPeripheralServer* weakServer = server;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 30*NSEC_PER_SEC), radioQueue, ^{
        MacApkPeripheralServer* owner = weakServer; if (!owner || owner->closed) return;
        MacApkPeripheralRequest* remaining = owner->requests[@(id)];
        if (remaining == pending) {
            if (remaining.request && !remaining.acknowledged && manager.state == CBManagerStatePoweredOn)
                [manager respondToRequest:remaining.request withResult:CBATTErrorUnlikelyError];
            [owner->requests removeObjectForKey:@(id)];
        }
    });
    return id;
}
void connected(MacApkPeripheralServer* server, CBCentral* central) {
    NSString* identifier = central.identifier.UUIDString;
    if (!server->centrals[identifier]) {
        server->centrals[identifier] = central;
        event(server, 2, 0, 2, identifier, 0, (int)central.maximumUpdateValueLength+3, nil);
    }
}
void drain(MacApkPeripheralServer* server) {
    while (!server->closed && server->notices.count && manager.state == CBManagerStatePoweredOn) {
        MacApkPeripheralNotice* notice = server->notices.firstObject;
        NSString* identifier = notice.central.identifier.UUIDString;
        NSNumber* id = [server->characteristicIds objectForKey:notice.characteristic];
        if (!id || ![server->subscriptions[identifier] containsObject:id]) {
            [server->notices removeObjectAtIndex:0]; event(server, 6, 0, 0, identifier, 257, 0, nil); continue;
        }
        if (![manager updateValue:notice.value forCharacteristic:notice.characteristic onSubscribedCentrals:@[notice.central]]) break;
        [server->notices removeObjectAtIndex:0];
        // Unconfirmed notifications have no remote acknowledgement. CoreBluetooth
        // has accepted this real update; confirmed indications are rejected.
        event(server, 6, 0, 0, identifier, 0, 0, nil);
    }
}
void removeService(MacApkPeripheralServer* server, NSNumber* serviceId) {
    CBMutableService* service = server->services[serviceId]; if (!service) return;
    for (CBMutableCharacteristic* characteristic in service.characteristics) {
        NSNumber* id = [server->characteristicIds objectForKey:characteristic];
        [server->characteristics removeObjectForKey:id]; [server->characteristicIds removeObjectForKey:characteristic];
        [characteristicOwners removeObjectForKey:characteristic];
        for (NSMutableSet* subscribed in server->subscriptions.allValues) [subscribed removeObject:id];
        for (NSNumber* requestId in server->requests.allKeys) {
            MacApkPeripheralRequest* request = server->requests[requestId];
            if (request.characteristic == id.intValue) {
                if (request.request && !request.acknowledged && manager.state == CBManagerStatePoweredOn)
                    [manager respondToRequest:request.request withResult:CBATTErrorAttributeNotFound];
                [server->requests removeObjectForKey:requestId];
            }
        }
    }
    if (manager.state == CBManagerStatePoweredOn && [server->published containsObject:serviceId])
        [manager removeService:service];
    [serviceOwners removeObjectForKey:service]; [server->services removeObjectForKey:serviceId]; [server->published removeObject:serviceId];
    drain(server);
}
void closeServer(MacApkPeripheralServer* server) {
    if (!server || server->closed) return;
    server->closed = YES;
    for (NSNumber* service in server->services.allKeys) removeService(server, service);
    [server->requests removeAllObjects]; [server->notices removeAllObjects];
    [server->subscriptions removeAllObjects]; [server->centrals removeAllObjects];
    [servers removeObjectForKey:@(server->handle)];
}
}

@implementation MacApkPeripheralDelegate
- (void)peripheralManagerDidUpdateState:(CBPeripheralManager*)peripheral {
    state.store((int)peripheral.state); stateChanged.notify_all();
    if (peripheral.state != CBManagerStatePoweredOn) {
        if (advertising) {
            MacApkPeripheralAdvertisement* old = advertising; advertising = nil;
            old->advertiser.delegate = nil;
            if (!old->completed) adResult(old, 4); else adStopped(old);
        }
        for (MacApkPeripheralServer* server in servers.allValues) {
            for (NSString* identifier in server->centrals.allKeys) event(server, 2, 0, 0, identifier, 257, 0, nil);
            [server->centrals removeAllObjects]; [server->subscriptions removeAllObjects];
            for (MacApkPeripheralNotice* notice in server->notices) event(server, 6, 0, 0, notice.central.identifier.UUIDString, 257, 0, nil);
            [server->notices removeAllObjects]; [server->requests removeAllObjects];
            for (NSNumber* service in server->services.allKeys) { removeService(server, service); added(server, service.intValue, 257); }
        }
    }
}
- (void)peripheralManager:(CBPeripheralManager*)peripheral didAddService:(CBService*)service error:(NSError*)error {
    MacApkPeripheralServer* server = [serviceOwners objectForKey:service];
    if (!server || server->closed) {
        // close/remove can race the asynchronous add. A successful late add
        // must immediately be withdrawn, never left as an orphan host service.
        if (!error && peripheral.state == CBManagerStatePoweredOn) [peripheral removeService:(CBMutableService*)service];
        return;
    }
    NSNumber* serviceId = [server->services allKeysForObject:(CBMutableService*)service].firstObject;
    if (!serviceId) return;
    if (error) removeService(server, serviceId); else [server->published addObject:serviceId];
    added(server, serviceId.intValue, error ? 257 : 0);
}
- (void)peripheralManager:(CBPeripheralManager*)peripheral central:(CBCentral*)central didSubscribeToCharacteristic:(CBCharacteristic*)characteristic {
    MacApkPeripheralServer* server = serverFor(characteristic); if (!server || server->closed) return;
    connected(server, central);
    NSString* identifier = central.identifier.UUIDString; NSNumber* id = [server->characteristicIds objectForKey:characteristic];
    NSMutableSet* subscriptions = server->subscriptions[identifier];
    if (!subscriptions) server->subscriptions[identifier] = subscriptions = [NSMutableSet set];
    [subscriptions addObject:id];
    uint8_t bytes[2] = {(uint8_t)((characteristic.properties & CBCharacteristicPropertyNotify) ? 1 : 2), 0};
    int request = rememberRequest(server, nil, identifier, id.intValue, true);
    event(server, 5, id.intValue, request, identifier, 1, 0, [NSData dataWithBytes:bytes length:2]);
}
- (void)peripheralManager:(CBPeripheralManager*)peripheral central:(CBCentral*)central didUnsubscribeFromCharacteristic:(CBCharacteristic*)characteristic {
    MacApkPeripheralServer* server = serverFor(characteristic); if (!server || server->closed) return;
    NSString* identifier = central.identifier.UUIDString; NSNumber* id = [server->characteristicIds objectForKey:characteristic];
    [server->subscriptions[identifier] removeObject:id];
    uint8_t bytes[2] = {0,0}; int request = rememberRequest(server, nil, identifier, id.intValue, true);
    event(server, 5, id.intValue, request, identifier, 0, 0, [NSData dataWithBytes:bytes length:2]);
    // CoreBluetooth exposes subscription lifetime, not raw ACL disconnects.
    // Losing the last subscription ends this bidirectional application channel.
    // Read/write-only peers remain known until shutdown, rather than guessing.
    if (server->subscriptions[identifier].count == 0) {
        [server->subscriptions removeObjectForKey:identifier]; [server->centrals removeObjectForKey:identifier];
        event(server, 2, 0, 0, identifier, 0, 0, nil);
    }
    drain(server);
}
- (void)peripheralManager:(CBPeripheralManager*)peripheral didReceiveReadRequest:(CBATTRequest*)request {
    MacApkPeripheralServer* server = serverFor(request.characteristic);
    if (!server || server->closed) { [peripheral respondToRequest:request withResult:CBATTErrorAttributeNotFound]; return; }
    connected(server, request.central);
    NSNumber* id = [server->characteristicIds objectForKey:request.characteristic];
    int number = rememberRequest(server, request, request.central.identifier.UUIDString, id.intValue, false);
    if (!number) { [peripheral respondToRequest:request withResult:CBATTErrorInsufficientResources]; return; }
    event(server, 3, id.intValue, number, request.central.identifier.UUIDString, 0, (int)request.offset, nil);
}
- (void)peripheralManager:(CBPeripheralManager*)peripheral didReceiveWriteRequests:(NSArray<CBATTRequest*>*)writes {
    if (!writes.count) return;
    // A multi-request atomic/reliable write cannot be represented by ordinary
    // Android write callbacks without a prepare/execute transaction. Reject it
    // explicitly; ordinary offset-zero WRITE_NO_RESPONSE packets remain supported.
    for (CBATTRequest* request in writes) {
        MacApkPeripheralServer* server = serverFor(request.characteristic);
        if (!server || server->closed) { [peripheral respondToRequest:writes.firstObject withResult:CBATTErrorAttributeNotFound]; return; }
        if (request.offset != 0 || (writes.count > 1 && (request.characteristic.properties & CBCharacteristicPropertyWrite))) {
            [peripheral respondToRequest:writes.firstObject withResult:CBATTErrorRequestNotSupported]; return;
        }
        if (server->requests.count >= 128) { [peripheral respondToRequest:writes.firstObject withResult:CBATTErrorInsufficientResources]; return; }
    }
    bool needsResponse = (writes.firstObject.characteristic.properties & CBCharacteristicPropertyWrite) != 0;
    for (CBATTRequest* request in writes) {
        MacApkPeripheralServer* server = serverFor(request.characteristic); connected(server, request.central);
        NSNumber* id = [server->characteristicIds objectForKey:request.characteristic];
        int number = needsResponse ? rememberRequest(server, request, request.central.identifier.UUIDString, id.intValue, false) : nextRequest.fetch_add(1);
        event(server, 4, id.intValue, number, request.central.identifier.UUIDString, needsResponse ? 1 : 0, 0, request.value ?: [NSData data]);
    }
    if (!needsResponse) [peripheral respondToRequest:writes.firstObject withResult:CBATTErrorSuccess];
}
- (void)peripheralManagerIsReadyToUpdateSubscribers:(CBPeripheralManager*)peripheral {
    for (MacApkPeripheralServer* server in servers.allValues) drain(server);
}
@end

// Each advertising attempt owns its own manager/delegate. CoreBluetooth's
// didStartAdvertising callback carries no request id; reusing one manager could
// attribute a late callback from a stopped attempt to a newer advertisement.
@implementation MacApkPeripheralAdvertisementDelegate
- (void)peripheralManagerDidUpdateState:(CBPeripheralManager*)peripheral {
    MacApkPeripheralAdvertisement* advertisement = self.owner;
    if (!advertisement || advertising != advertisement) return;
    if (peripheral.state == CBManagerStatePoweredOn) {
        if (!advertisement->submitted) {
            advertisement->submitted = YES;
            [peripheral startAdvertising:advertisement->payload];
        }
    } else if (peripheral.state != CBManagerStateUnknown && peripheral.state != CBManagerStateResetting) {
        peripheral.delegate = nil; advertising = nil;
        if (!advertisement->completed) adResult(advertisement, 4); else adStopped(advertisement);
    }
}
- (void)peripheralManagerDidStartAdvertising:(CBPeripheralManager*)peripheral error:(NSError*)error {
    MacApkPeripheralAdvertisement* advertisement = self.owner;
    if (!advertisement || advertising != advertisement || advertisement->completed) return;
    advertisement->completed = YES;
    if (error) { peripheral.delegate = nil; advertising = nil; adResult(advertisement, 4); return; }
    adResult(advertisement, 0);
    if (advertisement->timeout > 0) {
        __weak MacApkPeripheralAdvertisement* weakAdvertisement = advertisement;
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)advertisement->timeout*NSEC_PER_MSEC), radioQueue, ^{
            MacApkPeripheralAdvertisement* active = weakAdvertisement;
            if (active && advertising == active) {
                [active->advertiser stopAdvertising]; active->advertiser.delegate = nil;
                advertising = nil; adStopped(active);
            }
        });
    }
}
@end

namespace macapk::bluetoothperipheral {
namespace {
jint nativeState(JNIEnv*, jclass) {
    if (!authorized()) return CBManagerStateUnauthorized;
    ensureManager();
    if (state.load() == CBManagerStateUnknown && !dispatch_get_specific(&queueKey)) {
        std::unique_lock<std::mutex> lock(stateMutex);
        stateChanged.wait_for(lock, std::chrono::seconds(2), []{ return state.load() != CBManagerStateUnknown; });
    }
    return state.load();
}
jlong openServer(JNIEnv* env, jclass, jobject session) {
    if (!session || nativeState(env, nullptr) != CBManagerStatePoweredOn) return 0;
    MacApkPeripheralServer* server = [MacApkPeripheralServer new]; server->peer = env->NewGlobalRef(session);
    if (!server->peer) return 0;
    server->handle = nextHandle.fetch_add(1);
    sync(^{ servers[@(server->handle)] = server; }); return (jlong)server->handle;
}
void close(JNIEnv*, jclass, jlong handle) { sync(^{ closeServer(servers[@(handle)]); }); }
jboolean addService(JNIEnv* env, jclass, jlong handle, jint serviceId, jstring uuid, jboolean primary,
        jintArray ids, jobjectArray uuids, jintArray properties, jintArray permissions, jobjectArray descriptions) {
    if (!ids || !uuids || !properties || !permissions || !descriptions || !uuid) return JNI_FALSE;
    jsize count = env->GetArrayLength(ids);
    if (count == 0 || count > 128 || env->GetArrayLength(uuids) != count || env->GetArrayLength(properties) != count
            || env->GetArrayLength(permissions) != count || env->GetArrayLength(descriptions) != count) return JNI_FALSE;
    jint identifiers[128], props[128], perms[128];
    env->GetIntArrayRegion(ids, 0, count, identifiers); env->GetIntArrayRegion(properties, 0, count, props); env->GetIntArrayRegion(permissions, 0, count, perms);
    NSString* serviceUuid = string(env, uuid); if (![[NSUUID alloc] initWithUUIDString:serviceUuid]) return JNI_FALSE;
    CBMutableService* service = [[CBMutableService alloc] initWithType:[CBUUID UUIDWithString:serviceUuid] primary:primary];
    NSMutableArray<CBMutableCharacteristic*>* chars = [NSMutableArray array]; NSMutableArray<NSNumber*>* numbers = [NSMutableArray array];
    for (int i = 0; i < count; i++) {
        if (identifiers[i] <= 0 || props[i] == 0 || (props[i]&~0x1e) || (perms[i]&~0x33) || (props[i]&0x0c)==0x0c) return JNI_FALSE;
        jstring juuid = (jstring)env->GetObjectArrayElement(uuids,i); NSString* characteristicUuid = string(env,juuid); env->DeleteLocalRef(juuid);
        if (![[NSUUID alloc] initWithUUIDString:characteristicUuid]) return JNI_FALSE;
        CBAttributePermissions hostPermissions = 0;
        if (perms[i]&0x03) hostPermissions |= CBAttributePermissionsReadable;
        if (perms[i]&0x02) hostPermissions |= CBAttributePermissionsReadEncryptionRequired;
        if (perms[i]&0x30) hostPermissions |= CBAttributePermissionsWriteable;
        if (perms[i]&0x20) hostPermissions |= CBAttributePermissionsWriteEncryptionRequired;
        CBMutableCharacteristic* characteristic = [[CBMutableCharacteristic alloc] initWithType:[CBUUID UUIDWithString:characteristicUuid]
                properties:(CBCharacteristicProperties)props[i] value:nil permissions:hostPermissions];
        jstring description = (jstring)env->GetObjectArrayElement(descriptions,i);
        if (description) { characteristic.descriptors = @[[[CBMutableDescriptor alloc] initWithType:[CBUUID UUIDWithString:CBUUIDCharacteristicUserDescriptionString] value:string(env,description) ?: @""]]; env->DeleteLocalRef(description); }
        [chars addObject:characteristic]; [numbers addObject:@(identifiers[i])];
    }
    service.characteristics = chars;
    __block bool accepted = false;
    sync(^{
        MacApkPeripheralServer* server = servers[@(handle)];
        if (!server || server->closed || !authorized() || manager.state != CBManagerStatePoweredOn || server->services[@(serviceId)]) return;
        for (NSNumber* id in numbers) if (server->characteristics[id]) return;
        server->services[@(serviceId)] = service; [serviceOwners setObject:server forKey:service];
        for (int i = 0; i < count; i++) {
            server->characteristics[numbers[i]] = chars[i]; [server->characteristicIds setObject:numbers[i] forKey:chars[i]];
            [characteristicOwners setObject:server forKey:chars[i]];
        }
        [manager addService:service]; accepted = true;
    });
    return accepted;
}
jboolean remove(JNIEnv*, jclass, jlong handle, jint serviceId) {
    __block bool result = false;
    sync(^{ MacApkPeripheralServer* server = servers[@(handle)];
        if (server && !server->closed && [server->published containsObject:@(serviceId)]) { removeService(server,@(serviceId)); result = true; }
    }); return result;
}
void clear(JNIEnv*, jclass, jlong handle) { sync(^{ MacApkPeripheralServer* server = servers[@(handle)]; if (server) for (NSNumber* id in server->services.allKeys) removeService(server,id); }); }
jboolean respond(JNIEnv* env, jclass, jlong handle, jstring central, jint requestId, jint status, jint offset, jbyteArray value) {
    NSString* identifier = string(env,central); NSData* response = data(env,value); __block bool result = false;
    sync(^{
        MacApkPeripheralServer* server = servers[@(handle)]; if (!server || server->closed) return;
        MacApkPeripheralRequest* request = server->requests[@(requestId)];
        if (!request || ![identifier isEqualToString:request.identifier]) return;
        if (request.acknowledged) { result = status == 0 && offset == 0; [server->requests removeObjectForKey:@(requestId)]; return; }
        if (!authorized() || manager.state != CBManagerStatePoweredOn || offset != (int)request.request.offset) return;
        request.request.value = response ?: [NSData data];
        CBATTError code = status >= 0 && status <= 0x11 ? (CBATTError)status : CBATTErrorUnlikelyError;
        [manager respondToRequest:request.request withResult:code]; [server->requests removeObjectForKey:@(requestId)]; result = true;
    }); return result;
}
jboolean notify(JNIEnv* env, jclass, jlong handle, jstring central, jint characteristicId, jbyteArray value) {
    NSString* identifier = string(env,central); NSData* bytes = data(env,value); if (!identifier || !bytes) return JNI_FALSE;
    __block bool accepted = false;
    sync(^{
        MacApkPeripheralServer* server = servers[@(handle)]; if (!server || server->closed || !authorized() || manager.state != CBManagerStatePoweredOn) return;
        CBCentral* peer = server->centrals[identifier]; CBMutableCharacteristic* characteristic = server->characteristics[@(characteristicId)];
        if (!peer || !characteristic || !(characteristic.properties & CBCharacteristicPropertyNotify)
                || ![server->subscriptions[identifier] containsObject:@(characteristicId)] || bytes.length > peer.maximumUpdateValueLength || server->notices.count >= 64) return;
        NSUInteger buffered = 0; for (MacApkPeripheralNotice* queued in server->notices) buffered += queued.value.length;
        if (buffered + bytes.length > 256*1024) return;
        MacApkPeripheralNotice* notice = [MacApkPeripheralNotice new]; notice.central = peer; notice.characteristic = characteristic; notice.value = bytes;
        [server->notices addObject:notice]; accepted = true; drain(server);
    }); return accepted;
}
jlong startAdvertising(JNIEnv* env, jclass, jobject callback, jobjectArray uuids, jboolean includeName, jint timeout) {
    if (!callback || !uuids || timeout < 0 || timeout > 180000 || !authorized()) return -4;
    NSMutableArray<CBUUID*>* serviceUuids = [NSMutableArray array]; NSUInteger size = 0;
    jsize count = env->GetArrayLength(uuids); if (count > 16) return -1;
    for (int i = 0; i < count; i++) {
        jstring uuid = (jstring)env->GetObjectArrayElement(uuids,i); NSString* name = string(env,uuid); env->DeleteLocalRef(uuid);
        if (![[NSUUID alloc] initWithUUIDString:name]) return -5;
        CBUUID* value = [CBUUID UUIDWithString:name]; [serviceUuids addObject:value]; size += value.data.length;
    }
    if (count) size += 2;
    NSString* name = includeName ? NSProcessInfo.processInfo.hostName : nil;
    if (name.length) size += [name lengthOfBytesUsingEncoding:NSUTF8StringEncoding] + 2;
    // Never rely on Apple's overflow area: Android scanners cannot see it.
    if (size > 28) return -1;
    MacApkPeripheralAdvertisement* advertisement = [MacApkPeripheralAdvertisement new];
    advertisement->peer = env->NewGlobalRef(callback); if (!advertisement->peer) return -4;
    advertisement->handle = nextHandle.fetch_add(1); advertisement->timeout = timeout;
    __block jlong result = -4;
    sync(^{
        if (!manager || manager.state != CBManagerStatePoweredOn) return;
        if (advertising) { result = -2; return; }
        NSMutableDictionary* payload = [NSMutableDictionary dictionary];
        if (serviceUuids.count) payload[CBAdvertisementDataServiceUUIDsKey] = serviceUuids;
        if (name) payload[CBAdvertisementDataLocalNameKey] = name;
        advertisement->payload = payload;
        advertisement->advertisingDelegate = [MacApkPeripheralAdvertisementDelegate new];
        advertisement->advertisingDelegate.owner = advertisement;
        advertising = advertisement;
        advertisement->advertiser = [[CBPeripheralManager alloc] initWithDelegate:advertisement->advertisingDelegate
                queue:radioQueue options:@{CBPeripheralManagerOptionShowPowerAlertKey:@NO}];
        __weak MacApkPeripheralAdvertisement* weakAdvertisement = advertisement;
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 10*NSEC_PER_SEC), radioQueue, ^{
            MacApkPeripheralAdvertisement* pending = weakAdvertisement;
            if (pending && advertising == pending && !pending->completed) {
                pending->advertiser.delegate = nil;
                if (pending->advertiser.state == CBManagerStatePoweredOn) [pending->advertiser stopAdvertising];
                advertising = nil; adResult(pending, 4);
            }
        });
        result = (jlong)advertisement->handle;
    }); return result;
}
void stopAdvertising(JNIEnv*, jclass, jlong handle) {
    sync(^{ if (advertising && advertising->handle == (uint64_t)handle) {
        MacApkPeripheralAdvertisement* previous = advertising;
        [previous->advertiser stopAdvertising]; previous->advertiser.delegate = nil;
        advertising = nil; adStopped(previous);
    } });
}
}
void RegisterBluetoothPeripheralBridgeMethods(JNIEnv* env) {
    env->GetJavaVM(&vm); initializeQueues();
    jclass bridge = env->FindClass("com/macapk/loader/BluetoothPeripheralBridge");
    jclass session = env->FindClass("com/macapk/loader/BluetoothPeripheralBridge$Session");
    jclass advertisement = env->FindClass("com/macapk/loader/BluetoothPeripheralBridge$Advertisement");
    if (session) { sessionEvent = env->GetMethodID(session,"onEvent","(IIILjava/lang/String;II[B)V"); serviceAdded = env->GetMethodID(session,"onServiceAdded","(II)V"); }
    if (advertisement) { advertisementResult = env->GetMethodID(advertisement,"onResult","(I)V"); advertisementStopped = env->GetMethodID(advertisement,"onStopped","()V"); }
    const JNINativeMethod methods[] = {
        {(char*)"nativeState",(char*)"()I",(void*)nativeState},
        {(char*)"nativeOpenServer",(char*)"(Lcom/macapk/loader/BluetoothPeripheralBridge$Session;)J",(void*)openServer},
        {(char*)"nativeCloseServer",(char*)"(J)V",(void*)close},
        {(char*)"nativeAddService",(char*)"(JILjava/lang/String;Z[I[Ljava/lang/String;[I[I[Ljava/lang/String;)Z",(void*)addService},
        {(char*)"nativeRemoveService",(char*)"(JI)Z",(void*)remove},
        {(char*)"nativeClearServices",(char*)"(J)V",(void*)clear},
        {(char*)"nativeRespond",(char*)"(JLjava/lang/String;III[B)Z",(void*)respond},
        {(char*)"nativeNotify",(char*)"(JLjava/lang/String;I[B)Z",(void*)notify},
        {(char*)"nativeStartAdvertising",(char*)"(Lcom/macapk/loader/BluetoothPeripheralBridge$Advertisement;[Ljava/lang/String;ZI)J",(void*)startAdvertising},
        {(char*)"nativeStopAdvertising",(char*)"(J)V",(void*)stopAdvertising},
    };
    if (!bridge || !sessionEvent || !serviceAdded || !advertisementResult || !advertisementStopped
            || env->RegisterNatives(bridge, methods, sizeof(methods)/sizeof(methods[0])) != JNI_OK) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        std::fprintf(stderr,"[bluetooth] cannot register peripheral JNI bridge\n"); std::abort();
    }
    env->DeleteLocalRef(bridge); env->DeleteLocalRef(session); env->DeleteLocalRef(advertisement);
}
}
