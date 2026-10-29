package com.macapk.loader;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;


/** ART Field_set bypasses final-field access checks only when override=true.
 * Host classes retain HotSpot's rules. Only app-owned static final fields are
 * written through typed JNI field IDs; no system/framework access is expanded. */
public final class ArtReflection {
    private ArtReflection() {}
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static native Object readStatic(Field field) throws IllegalAccessException;
    private static native void writeStatic(Field field, Object value) throws IllegalAccessException;
    // The bare-JVM regression harness has no runtime JNI bindings. Use the
    // host JDK's actual Unsafe there, without linking to Android's narrower
    // sun.misc.Unsafe surface (which intentionally has no staticFieldBase).
    private static Object hostAccess(Field field,Object value,boolean write) throws IllegalAccessException {
        try {
            Class<?> unsafe=Class.forName("sun.misc.Unsafe"); Field singleton=unsafe.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
            Object u=singleton.get(null);
            Object base=unsafe.getMethod("staticFieldBase",Field.class).invoke(u,field);
            long offset=(Long)unsafe.getMethod("staticFieldOffset",Field.class).invoke(u,field);
            Class<?> type=field.getType(); String kind=type.isPrimitive()?type.getName():"object";
            String cap=Character.toUpperCase(kind.charAt(0))+kind.substring(1);
            if(write) { unsafe.getMethod("put"+cap+"Volatile",Object.class,long.class,type.isPrimitive()?type:Object.class).invoke(u,base,offset,value); return null; }
            return unsafe.getMethod("get"+cap+"Volatile",Object.class,long.class).invoke(u,base,offset);
        } catch (ReflectiveOperationException ex) { throw new IllegalAccessException("Host static-field access unavailable: "+ex.getClass().getSimpleName()); }
    }
    @SuppressWarnings("deprecation")
    public static void set(Field field, Object receiver, Object value) throws IllegalAccessException {
        // Preserve the original caller's ordinary access rules. Moving a
        // reflective call into a helper must not grant the helper's access.
        if (!field.isAccessible()) {
            Class<?> caller = WALKER.getCallerClass();
            try {
                var setter = MethodHandles.privateLookupIn(caller, MethodHandles.lookup()).unreflectSetter(field);
                Object converted = convert(field.getType(), value);
                if (Modifier.isStatic(field.getModifiers())) setter.invokeWithArguments(converted);
                else setter.invokeWithArguments(receiver, converted);
                return;
            } catch (IllegalAccessException ex) { throw ex; }
            catch (ClassCastException | java.lang.invoke.WrongMethodTypeException ex) { throw new IllegalArgumentException(ex); }
            catch (RuntimeException | Error ex) { throw ex; }
            catch (Throwable ex) { throw new IllegalAccessException(ex.toString()); }
        }
        try { field.set(receiver,value); return; }
        catch (IllegalAccessException denied) {
            int mods=field.getModifiers();
            if (!Modifier.isStatic(mods) || !Modifier.isFinal(mods)
                    || !(field.getDeclaringClass().getClassLoader() instanceof dalvik.system.BaseDexClassLoader)
                    || field.getDeclaringClass().isRecord() || field.getDeclaringClass().isHidden()) throw denied;
        }
        Class<?> type=field.getType(); Object converted=convert(type,value);
        try { Class.forName(field.getDeclaringClass().getName(),true,field.getDeclaringClass().getClassLoader()); }
        catch (ClassNotFoundException impossible) { throw new IllegalAccessException(impossible.toString()); }
        try { writeStatic(field,converted); }
        catch (UnsatisfiedLinkError noRuntime) { hostAccess(field,converted,true); }
    }
    @SuppressWarnings("deprecation")
    public static Object get(Field field, Object receiver) throws IllegalAccessException {
        if (!field.isAccessible()) {
            Class<?> caller = WALKER.getCallerClass();
            try {
                var getter=MethodHandles.privateLookupIn(caller,MethodHandles.lookup()).unreflectGetter(field);
                return Modifier.isStatic(field.getModifiers()) ? getter.invokeWithArguments() : getter.invokeWithArguments(receiver);
            } catch (IllegalAccessException ex) { throw ex; }
            catch (ClassCastException | java.lang.invoke.WrongMethodTypeException ex) { throw new IllegalArgumentException(ex); }
            catch (RuntimeException | Error ex) { throw ex; }
            catch (Throwable ex) { throw new IllegalAccessException(ex.toString()); }
        }
        int mods=field.getModifiers();
        if (!Modifier.isStatic(mods) || !Modifier.isFinal(mods)
                || !(field.getDeclaringClass().getClassLoader() instanceof dalvik.system.BaseDexClassLoader)) return field.get(receiver);
        try { Class.forName(field.getDeclaringClass().getName(),true,field.getDeclaringClass().getClassLoader()); }
        catch (ClassNotFoundException impossible) { throw new IllegalAccessException(impossible.toString()); }
        try { return readStatic(field); }
        catch (UnsatisfiedLinkError noRuntime) { return hostAccess(field,null,false); }
    }
    private static Object convert(Class<?> type,Object value) {
        if(!type.isPrimitive()) { if(value==null || type.isInstance(value)) return value; throw new IllegalArgumentException("Field type mismatch"); }
        if(type==boolean.class && value instanceof Boolean || type==char.class && value instanceof Character
                || type==byte.class && value instanceof Byte) return value;
        int rank=value instanceof Byte?1:value instanceof Short?2:value instanceof Character?2:value instanceof Integer?3:
            value instanceof Long?4:value instanceof Float?5:value instanceof Double?6:99;
        int target=type==short.class?2:type==int.class?3:type==long.class?4:type==float.class?5:type==double.class?6:0;
        if(target==0 || rank>target || type==short.class && value instanceof Character) throw new IllegalArgumentException("Field type mismatch");
        Number n=value instanceof Character?Integer.valueOf((Character)value):(Number)value;
        if(type==short.class)return n.shortValue(); if(type==int.class)return n.intValue(); if(type==long.class)return n.longValue();
        if(type==float.class)return n.floatValue(); return n.doubleValue();
    }
}
