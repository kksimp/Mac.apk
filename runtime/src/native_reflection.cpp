#include "macapk/native_reflection.h"
#include <string>
#include <cstdio>
#include <cstdlib>
namespace macapk {
namespace {
// JNI static-field access for ART reflection compatibility. Never accepts host
// classes, instance/non-final fields or fields without explicit access override.
static jobject ArtStaticAccess(JNIEnv* env, jobject field, jobject value, bool write) {
    jclass fc=env->FindClass("java/lang/reflect/Field");
    if(!field || !env->IsInstanceOf(field,fc)) { env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),"Expected Field"); return nullptr; }
    jobject owner=env->CallObjectMethod(field,env->GetMethodID(fc,"getDeclaringClass","()Ljava/lang/Class;"));
    jint mods=env->CallIntMethod(field,env->GetMethodID(fc,"getModifiers","()I"));
    jboolean accessible=env->CallBooleanMethod(field,env->GetMethodID(fc,"isAccessible","()Z"));
    jclass cc=env->FindClass("java/lang/Class");
    jobject loader=env->CallObjectMethod(owner,env->GetMethodID(cc,"getClassLoader","()Ljava/lang/ClassLoader;"));
    jclass appLoader=env->FindClass("dalvik/system/BaseDexClassLoader");
    if(env->ExceptionCheck()) return nullptr;
    if((mods&24)!=24 || !accessible || !loader || !env->IsInstanceOf(loader,appLoader)) {
        env->ThrowNew(env->FindClass("java/lang/IllegalAccessException"),"Only accessible app-owned static final fields are supported"); return nullptr;
    }
    jobject type=env->CallObjectMethod(field,env->GetMethodID(fc,"getType","()Ljava/lang/Class;"));
    jboolean primitive=env->CallBooleanMethod(type,env->GetMethodID(cc,"isPrimitive","()Z"));
    jfieldID id=env->FromReflectedField(field); jclass declaring=(jclass)owner;
    if(!primitive) {
        if(write) {
            if(value && !env->IsInstanceOf(value,(jclass)type)) { env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),"Field type mismatch"); return nullptr; }
            env->SetStaticObjectField(declaring,id,value); return nullptr;
        }
        return env->GetStaticObjectField(declaring,id);
    }
    jstring name=(jstring)env->CallObjectMethod(type,env->GetMethodID(cc,"getName","()Ljava/lang/String;"));
    const char* utf=env->GetStringUTFChars(name,nullptr); std::string kind=utf?utf:""; if(utf)env->ReleaseStringUTFChars(name,utf);
#define ART_FIELD_CASE(NAME, PRIM, BOX, SIG, JT) \
    if(kind==PRIM) { \
        jclass box=env->FindClass("java/lang/" BOX); \
        if(write) { \
            if(!value || !env->IsInstanceOf(value,box)) { env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),"Field type mismatch");return nullptr;} \
            JT v=env->Call##NAME##Method(value,env->GetMethodID(box, PRIM "Value", "()" SIG)); \
            if(!env->ExceptionCheck())env->SetStatic##NAME##Field(declaring,id,v); return nullptr; \
        } \
        JT v=env->GetStatic##NAME##Field(declaring,id); \
        return env->CallStaticObjectMethod(box,env->GetStaticMethodID(box,"valueOf","(" SIG ")Ljava/lang/" BOX ";"),v); \
    }
    ART_FIELD_CASE(Boolean,"boolean","Boolean","Z",jboolean)
    ART_FIELD_CASE(Byte,"byte","Byte","B",jbyte)
    ART_FIELD_CASE(Char,"char","Character","C",jchar)
    ART_FIELD_CASE(Short,"short","Short","S",jshort)
    ART_FIELD_CASE(Int,"int","Integer","I",jint)
    ART_FIELD_CASE(Long,"long","Long","J",jlong)
    ART_FIELD_CASE(Float,"float","Float","F",jfloat)
    ART_FIELD_CASE(Double,"double","Double","D",jdouble)
#undef ART_FIELD_CASE
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),"Unknown field type");return nullptr;
}
static jobject ArtReadStatic(JNIEnv* env,jclass,jobject field) { return ArtStaticAccess(env,field,nullptr,false); }
static void ArtWriteStatic(JNIEnv* env,jclass,jobject field,jobject value) { ArtStaticAccess(env,field,value,true); }
const JNINativeMethod kArtReflectionMethods[] = {
    {(char*)"readStatic",(char*)"(Ljava/lang/reflect/Field;)Ljava/lang/Object;",(void*)ArtReadStatic},
    {(char*)"writeStatic",(char*)"(Ljava/lang/reflect/Field;Ljava/lang/Object;)V",(void*)ArtWriteStatic},
};


}
void RegisterReflectionNativeMethods(JNIEnv* env) {
    {
        jclass type=env->FindClass("com/macapk/loader/ArtReflection");
        if(!type || env->RegisterNatives(type,kArtReflectionMethods,2)!=JNI_OK) {
            if(env->ExceptionCheck())env->ExceptionDescribe();
            std::fprintf(stderr,"[macapk] cannot register native_reflection natives\n");
            std::abort();
        }
        env->DeleteLocalRef(type);
    }
}
} // namespace macapk
