#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <jni.h>
#include <cstdint>

// Deliberately independent of AppKit/CAMetalLayer. AGSL surfaces are offscreen and
// may be rendered from the Android UI thread without dispatching to the host main thread.
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_macapk_graphics_NativeSkiaMetal_createDeviceQueue(JNIEnv* env, jclass) {
    @autoreleasepool {
        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        if (!device) {
            env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "No Metal device is available for Skia AGSL");
            return nullptr;
        }
        id<MTLCommandQueue> queue = [device newCommandQueue];
        if (!queue) {
            [device release];
            env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Cannot create Skia Metal command queue");
            return nullptr;
        }
        queue.label = @"Mac.apk Skia AGSL";
        jlongArray result = env->NewLongArray(2);
        if (!result) {
            [queue release];
            [device release];
            return nullptr;
        }
        jlong handles[] = {
            static_cast<jlong>(reinterpret_cast<uintptr_t>(device)),
            static_cast<jlong>(reinterpret_cast<uintptr_t>(queue))
        };
        env->SetLongArrayRegion(result, 0, 2, handles);
        if (env->ExceptionCheck()) {
            [queue release];
            [device release];
            return nullptr;
        }
        return result;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_macapk_graphics_NativeSkiaMetal_releaseDeviceQueue(JNIEnv*, jclass, jlong device, jlong queue) {
    @autoreleasepool {
        [(id<MTLCommandQueue>)(uintptr_t)queue release];
        [(id<MTLDevice>)(uintptr_t)device release];
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_macapk_graphics_NativeSkiaMetal_deviceName(JNIEnv* env, jclass, jlong device) {
    @autoreleasepool {
        NSString* name = [(id<MTLDevice>)(uintptr_t)device name];
        return env->NewStringUTF(name ? name.UTF8String : "unknown");
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_macapk_graphics_NativeSkiaMetal_openPool(JNIEnv*, jclass) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>([[NSAutoreleasePool alloc] init]));
}

extern "C" JNIEXPORT void JNICALL
Java_com_macapk_graphics_NativeSkiaMetal_closePool(JNIEnv*, jclass, jlong pool) {
    [(NSAutoreleasePool*)(uintptr_t)pool drain];
}
