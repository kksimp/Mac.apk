// Compile the actual producer in the same translation unit: CBUUID values and
// dictionaries only. No Bluetooth controller/manager is initialized.
#include "../runtime/src/bluetooth_bridge.mm"
#include <fstream>
int main(int argc,char** argv){
    if(argc!=2)return 2;
    @autoreleasepool{
        uint8_t manufacturer[]={0x34,0x12,5,6};uint8_t service[]={42};
        NSDictionary* fields=@{
            CBAdvertisementDataLocalNameKey:@"Fixture",
            CBAdvertisementDataServiceUUIDsKey:@[[CBUUID UUIDWithString:@"11223344-5566-7788-99aa-bbccddeeff00"]],
            CBAdvertisementDataManufacturerDataKey:[NSData dataWithBytes:manufacturer length:sizeof manufacturer],
            CBAdvertisementDataServiceDataKey:@{[CBUUID UUIDWithString:@"180f"]:[NSData dataWithBytes:service length:sizeof service]},
            CBAdvertisementDataTxPowerLevelKey:@(-4)
        };
        NSData* result=macapk::bluetooth::Advertisement(fields);
        std::ofstream file(argv[1],std::ios::binary);file.write((const char*)result.bytes,result.length);return file?0:1;
    }
}
