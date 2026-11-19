package com.macapk.dex;

/** Regression for DEX member-class flags that disagree with InnerClasses. */
public final class InnerClassAccessTest {
    private static void expect(int actual, int expected, String what) {
        if (actual != expected) {
            throw new AssertionError(what + ": expected 0x"
                    + Integer.toHexString(expected) + ", got 0x"
                    + Integer.toHexString(actual));
        }
    }

    public static void main(String[] args) {
        int iface = ClassFileWriter.ACC_INTERFACE | ClassFileWriter.ACC_ABSTRACT;
        int clazz = ClassFileWriter.ACC_SUPER;

        expect(DexConverter.normalizeInnerClassAccess(iface,
                        ClassFileWriter.ACC_PUBLIC | ClassFileWriter.ACC_INTERFACE),
                iface | ClassFileWriter.ACC_PUBLIC,
                "public nested interface must have a public classfile flag");
        expect(DexConverter.normalizeInnerClassAccess(iface,
                        ClassFileWriter.ACC_PROTECTED | ClassFileWriter.ACC_INTERFACE),
                iface | ClassFileWriter.ACC_PUBLIC,
                "protected nested interface must also be classfile-public");
        expect(DexConverter.normalizeInnerClassAccess(iface,
                        ClassFileWriter.ACC_PRIVATE | ClassFileWriter.ACC_INTERFACE),
                iface,
                "private nested interfaces stay classfile-private by package access");
        expect(DexConverter.normalizeInnerClassAccess(iface, iface),
                iface,
                "package-private nested interfaces stay package-private");
        expect(DexConverter.normalizeInnerClassAccess(
                        ClassFileWriter.ACC_PUBLIC | iface,
                        ClassFileWriter.ACC_PUBLIC | ClassFileWriter.ACC_INTERFACE),
                ClassFileWriter.ACC_PUBLIC | iface,
                "already-public classes remain unchanged");
        expect(DexConverter.normalizeInnerClassAccess(clazz,
                        ClassFileWriter.ACC_PUBLIC),
                clazz | ClassFileWriter.ACC_PUBLIC,
                "public nested classes must have a public classfile flag");
        expect(DexConverter.normalizeInnerClassAccess(clazz,
                        ClassFileWriter.ACC_PROTECTED),
                clazz | ClassFileWriter.ACC_PUBLIC,
                "protected nested classes must also be classfile-public");
        expect(DexConverter.normalizeInnerClassAccess(clazz,
                        ClassFileWriter.ACC_PRIVATE),
                clazz,
                "private nested classes stay classfile-private by package access");
        expect(DexConverter.normalizeInnerClassAccess(clazz, 0),
                clazz,
                "package-private nested classes stay package-private");

        System.out.println("PASS: DEX inner-class access flags match Proxy visibility");
    }
}
