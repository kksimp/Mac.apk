package com.macapk.loader;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Win.apk: which native methods ART calls as @CriticalNative.
 *
 * <p>ART calls a static native method annotated
 * {@code dalvik.annotation.optimization.CriticalNative} with its Java arguments
 * only: no JNIEnv*, no jclass. HotSpot knows nothing of the annotation and
 * passes both, so the guest function read the JNIEnv* as its first argument
 * (Google's Elements and map renderer libraries hand native handles that way,
 * and Maps' heap was corrupted from there). The host asks here when it binds a
 * guest native, and gives such a method a thunk that drops the two
 * (windows/src/jni_bind.cpp). The annotation has build retention, so it is read
 * from the class file the DEX converter wrote, not by reflection.
 */
public final class CriticalNatives {
    private static final String ANNOTATION = "Ldalvik/annotation/optimization/CriticalNative;";
    private static final Map<Class<?>, Set<String>> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private CriticalNatives() {}

    /** Called by the host (JNI) for a static native with primitive arguments only. */
    public static boolean isCritical(Class<?> c, String name, String sig) {
        if (c == null || name == null || sig == null) return false;
        Set<String> set = CACHE.get(c);
        if (set == null) {
            set = read(c);
            CACHE.put(c, set);
        }
        return set.contains(name + sig);
    }

    private static Set<String> read(Class<?> c) {
        Set<String> out = new HashSet<>();
        ClassLoader l = c.getClassLoader();
        if (l == null) return out;
        try (InputStream in = l.getResourceAsStream(c.getName().replace('.', '/') + ".class")) {
            if (in != null) parse(new DataInputStream(in), out);
        } catch (IOException | RuntimeException e) {
            // unreadable: no method is critical, as before
        }
        return out;
    }

    // The class file's methods whose annotations (visible or not) include ANNOTATION.
    private static void parse(DataInputStream d, Set<String> out) throws IOException {
        if (d.readInt() != 0xCAFEBABE) return;
        d.readUnsignedShort();
        d.readUnsignedShort();
        int n = d.readUnsignedShort();
        String[] utf8 = new String[n];
        for (int i = 1; i < n; i++) {
            int tag = d.readUnsignedByte();
            switch (tag) {
                case 1: utf8[i] = d.readUTF(); break;
                case 3: case 4: d.readInt(); break;
                case 5: case 6: d.readLong(); i++; break;
                case 7: case 8: case 16: case 19: case 20: d.readUnsignedShort(); break;
                case 9: case 10: case 11: case 12: case 17: case 18: d.readInt(); break;
                case 15: d.readUnsignedByte(); d.readUnsignedShort(); break;
                default: return;
            }
        }
        d.readUnsignedShort();
        d.readUnsignedShort();
        d.readUnsignedShort();
        int interfaces = d.readUnsignedShort();
        for (int i = 0; i < interfaces; i++) d.readUnsignedShort();
        int fields = d.readUnsignedShort();
        for (int i = 0; i < fields; i++) {
            d.readUnsignedShort();
            d.readUnsignedShort();
            d.readUnsignedShort();
            skipAttributes(d);
        }
        int methods = d.readUnsignedShort();
        for (int i = 0; i < methods; i++) {
            d.readUnsignedShort();
            String name = utf8[d.readUnsignedShort()];
            String desc = utf8[d.readUnsignedShort()];
            int attrs = d.readUnsignedShort();
            for (int a = 0; a < attrs; a++) {
                String an = utf8[d.readUnsignedShort()];
                int len = d.readInt();
                byte[] body = new byte[len];
                d.readFully(body);
                if (("RuntimeInvisibleAnnotations".equals(an) || "RuntimeVisibleAnnotations".equals(an))
                        && names(body, utf8)) {
                    out.add(name + desc);
                }
            }
        }
    }

    // Whether an annotations attribute names ANNOTATION (its top-level types only).
    private static boolean names(byte[] body, String[] utf8) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(body);
        int count = b.getShort() & 0xffff;
        for (int i = 0; i < count; i++) {
            int type = b.getShort() & 0xffff;
            if (type < utf8.length && ANNOTATION.equals(utf8[type])) return true;
            int pairs = b.getShort() & 0xffff;
            for (int p = 0; p < pairs; p++) {
                b.getShort();
                skipValue(b);
            }
        }
        return false;
    }

    private static void skipValue(java.nio.ByteBuffer b) {
        int tag = b.get() & 0xff;
        switch (tag) {
            case 'e': b.getShort(); b.getShort(); break;
            case '@': {
                b.getShort();
                int pairs = b.getShort() & 0xffff;
                for (int p = 0; p < pairs; p++) {
                    b.getShort();
                    skipValue(b);
                }
                break;
            }
            case '[': {
                int n = b.getShort() & 0xffff;
                for (int i = 0; i < n; i++) skipValue(b);
                break;
            }
            default: b.getShort(); break;
        }
    }

    private static void skipAttributes(DataInputStream d) throws IOException {
        int attrs = d.readUnsignedShort();
        for (int a = 0; a < attrs; a++) {
            d.readUnsignedShort();
            int len = d.readInt();
            d.skipNBytes(len);
        }
    }
}
