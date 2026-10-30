# Opt-in CoreBluetooth bridge

`MACAPK_BLUETOOTH_HOST=1` enables a real BLE backend; the default is off. Android feature queries, adapter availability, runtime permission flags and operation checks agree with actual host hardware and TCC authorization. Capability queries do not grant permission. Permission requests run on a worker so the Cocoa and Android event loops remain responsive.

The central path implements service-UUID scanning/filtering, opaque peer identifiers, connection, service discovery, characteristic/descriptor I/O, CCCD subscriptions, notifications, RSSI and MTU reporting. Write-without-response queues respect native backpressure. A rejected discovery does not discard the previous service tree. API-33 callback overloads preserve legacy callbacks without duplicate delivery.

The peripheral path publishes services, advertises supported service UUIDs, handles incoming reads/writes and subscriptions, and sends notifications with backpressure and bounded close/timeout behavior. Apple advertising supports local name and service UUIDs; unsupported manufacturer/service-data payloads, Classic Bluetooth, pairing, controller power changes and indication confirmation fail explicitly. Address-shaped peer handles are not physical MAC addresses.

Use normal LaunchServices/Finder app launching with an appropriate NSBluetoothAlwaysUsageDescription. A purpose description does not authorize access. Do not bypass the system permission prompt.

`debug/check_bluetooth_compatibility.sh` exercises production AD encoding through the Android parser/filter, identifier handling, per-operation permissions, callback compatibility, rejected-discovery state and read-only production JNI capability queries. It does not request permission, scan, connect or advertise. Real two-device radio transfer remains an integration-validation requirement.
