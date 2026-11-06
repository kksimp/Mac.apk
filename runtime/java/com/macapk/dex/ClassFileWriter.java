package com.macapk.dex;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles a complete JVM class file (JVMS SE21 section 4.1).
 *
 *   ClassFile {
 *       u4             magic;                 // 0xCAFEBABE
 *       u2             minor_version;
 *       u2             major_version;
 *       u2             constant_pool_count;
 *       cp_info        constant_pool[constant_pool_count-1];
 *       u2             access_flags;
 *       u2             this_class;
 *       u2             super_class;
 *       u2             interfaces_count;
 *       u2             interfaces[interfaces_count];
 *       u2             fields_count;
 *       field_info     fields[fields_count];
 *       u2             methods_count;
 *       method_info    methods[methods_count];
 *       u2             attributes_count;
 *       attribute_info attributes[attributes_count];
 *   }
 *
 * Everything from access_flags onward is serialized FIRST, into a scratch
 * buffer, and the constant pool is written only afterwards. That ordering is
 * forced by the pool itself: serializing a method body is what discovers most
 * of the constants it needs, so the pool is not final until the last method has
 * been written. The finished file is then magic + versions + pool + scratch.
 *
 * CLASS FILE VERSION: 52 (Java SE 8) by default. The reasoning, including why
 * not 49 and why not 50, and what that implies for StackMapTable, is written up
 * at the top of StackMapWriter.
 *
 * Not thread safe. One instance per class file.
 */
public final class ClassFileWriter {

    public static final int V1_5 = 49;
    public static final int V1_6 = 50;
    public static final int V1_7 = 51;
    public static final int V1_8 = 52;
    public static final int V9   = 53;
    public static final int V11  = 55;
    public static final int V17  = 61;
    public static final int V21  = 65;

    /**
     * Default major version.
     *
     * 52 is the floor for the constructs an R8-built APK routinely contains:
     * invokedynamic needs 51 (JVMS 4.1: invokedynamic "must not appear in a
     * class file whose version number is less than 51.0") and static plus
     * default interface methods need 52. Staying at 52 keeps the output
     * loadable by anything from JDK 8 up.
     *
     * It is not an unconditional ceiling. An attribute is only RECOGNISED at or
     * above the version that introduced it, and an unrecognised attribute is
     * skipped in silence (JVMS 4.7: "Java Virtual Machine implementations are
     * required to silently ignore attributes they do not recognize"), so a
     * class that needs one has to declare the matching version. Today that is
     * NestHost / NestMembers, introduced at 55.0 in Java SE 11 (JVMS Table
     * 4.7-C), which HotSpot gates literally: classFileParser.cpp only tests for
     * tag_nest_host / tag_nest_members inside `else if (_major_version >=
     * JAVA_11_VERSION)`. Emitting them at 52 costs bytes and changes nothing.
     * See versionFloor().
     */
    public static final int DEFAULT_VERSION = V1_8;

    // ---- access flags (JVMS 4.1 Table 4.1-B, 4.5-A, 4.6-A) ---------------
    public static final int ACC_PUBLIC       = 0x0001;
    public static final int ACC_PRIVATE      = 0x0002;
    public static final int ACC_PROTECTED    = 0x0004;
    public static final int ACC_STATIC       = 0x0008;
    public static final int ACC_FINAL        = 0x0010;
    public static final int ACC_SUPER        = 0x0020;   // classes
    public static final int ACC_SYNCHRONIZED = 0x0020;   // methods
    public static final int ACC_VOLATILE     = 0x0040;   // fields
    public static final int ACC_BRIDGE       = 0x0040;   // methods
    public static final int ACC_TRANSIENT    = 0x0080;   // fields
    public static final int ACC_VARARGS      = 0x0080;   // methods
    public static final int ACC_NATIVE       = 0x0100;
    public static final int ACC_INTERFACE    = 0x0200;
    public static final int ACC_ABSTRACT     = 0x0400;
    public static final int ACC_STRICT       = 0x0800;
    public static final int ACC_SYNTHETIC    = 0x1000;
    public static final int ACC_ANNOTATION   = 0x2000;
    public static final int ACC_ENUM         = 0x4000;

    /**
     * Every count in a class file is a u2 (JVMS 4.1, 4.5, 4.6, 4.7). Silently
     * truncating one is the worst available failure: the file still parses, but
     * the reader stops at the wrong element and interprets the remaining bytes
     * as something else entirely. LimitExceededException is the same type the
     * constant pool raises, so a front end that already retries an oversized
     * class handles this the same way.
     */
    static void checkCount(int n, String what) {
        if (n > 65535) {
            throw new ConstantPool.LimitExceededException(
                    n + " " + what + " exceeds the u2 limit of 65535 (JVMS 4.1)");
        }
    }

    /** attribute_info wrapper: a name plus an already-serialized body. */
    private static final class Attr {
        final String name;
        final byte[] body;
        Attr(String name, byte[] body) { this.name = name; this.body = body; }
    }

    /** Shared field_info / method_info builder (JVMS 4.5, 4.6). */
    public static class Member {
        final ClassFileWriter owner;
        /** Not final only so {@link ClassFileWriter#unfinalFieldsWrittenByInitializerHelpers}
         *  and {@link ClassFileWriter#unfinalFieldsWrittenOutsideInitializers}
         *  can clear ACC_FINAL once the class's version is known. */
        int access;
        final String name;
        final String desc;
        final List<Attr> attrs = new ArrayList<>();

        Member(ClassFileWriter owner, int access, String name, String desc) {
            this.owner = owner;
            this.access = access;
            this.name = name;
            this.desc = desc;
        }

        public String name() { return name; }
        public String descriptor() { return desc; }
        public int access() { return access; }

        /** Raw attribute escape hatch: RuntimeVisibleAnnotations,
         *  RuntimeVisibleParameterAnnotations, AnnotationDefault, MethodParameters,
         *  and anything else a front end already knows how to serialize. */
        public Member addAttribute(String attrName, byte[] body) {
            attrs.add(new Attr(attrName, body));
            return this;
        }

        /** Signature attribute (JVMS 4.7.9): the generic type. DEX carries this
         *  as the dalvik.annotation.Signature system annotation. */
        public Member setSignature(String signature) {
            ConstantPool.ByteVector b = new ConstantPool.ByteVector(2);
            b.putU2(owner.pool.utf8(signature));
            return addAttribute("Signature", b.toByteArray());
        }

        public Member setSynthetic() { return addAttribute("Synthetic", new byte[0]); }
        public Member setDeprecated() { return addAttribute("Deprecated", new byte[0]); }

        void write(ConstantPool pool, ConstantPool.ByteVector out) {
            out.putU2(access);
            out.putU2(pool.utf8(name));
            out.putU2(pool.utf8(desc));
            checkCount(attrs.size(), "attributes of " + name + desc);
            out.putU2(attrs.size());
            for (Attr a : attrs) {
                out.putU2(pool.utf8(a.name));
                out.putU4(a.body.length);
                out.putBytes(a.body);
            }
        }
    }

    /** field_info (JVMS 4.5). */
    public static final class FieldWriter extends Member {
        FieldWriter(ClassFileWriter owner, int access, String name, String desc) {
            super(owner, access, name, desc);
        }

        /**
         * ConstantValue attribute (JVMS 4.7.2). The constant's pool TAG must
         * match the FIELD's declared type, not whatever type the source
         * constant had: a `boolean` or `char` field takes a CONSTANT_Integer.
         * This method picks from the descriptor so callers cannot get it wrong.
         */
        public FieldWriter setConstantValue(Object value) {
            ConstantPool p = owner.pool;
            int index;
            char c = desc.charAt(0);
            switch (c) {
                case 'Z': case 'B': case 'C': case 'S': case 'I':
                    index = p.integer(((Number) value).intValue());
                    break;
                case 'J':
                    index = p.longConst(((Number) value).longValue());
                    break;
                case 'F':
                    index = p.floatBits(value instanceof Integer
                            ? (Integer) value
                            : Float.floatToRawIntBits(((Number) value).floatValue()));
                    break;
                case 'D':
                    index = p.doubleBits(value instanceof Long
                            ? (Long) value
                            : Double.doubleToRawLongBits(((Number) value).doubleValue()));
                    break;
                default:
                    if (!"Ljava/lang/String;".equals(desc)) {
                        throw new IllegalArgumentException(
                                "ConstantValue is only legal on primitive and String fields, not " + desc);
                    }
                    index = p.stringRef((String) value);
                    break;
            }
            ConstantPool.ByteVector b = new ConstantPool.ByteVector(2);
            b.putU2(index);
            return (FieldWriter) addAttribute("ConstantValue", b.toByteArray());
        }

        /** ConstantValue from a pool index the caller allocated itself. */
        public FieldWriter setConstantValueIndex(int cpIndex) {
            ConstantPool.ByteVector b = new ConstantPool.ByteVector(2);
            b.putU2(cpIndex);
            return (FieldWriter) addAttribute("ConstantValue", b.toByteArray());
        }
    }

    /** method_info (JVMS 4.6). */
    public static final class MethodWriter extends Member {
        private CodeWriter code;
        /** "&lt;clinit&gt;" or "&lt;init&gt;" when this is a synthetic helper holding
         *  code split or outlined out of that initializer, else null. */
        private String initializerOf;

        MethodWriter(ClassFileWriter owner, int access, String name, String desc) {
            super(owner, access, name, desc);
        }

        /**
         * Marks this synthetic method as holding code that the converter moved
         * out of {@code initializer} ("&lt;clinit&gt;" or "&lt;init&gt;"). The
         * writes it makes to its class's own fields of that initializer's kind
         * (putstatic for &lt;clinit&gt;, putfield for &lt;init&gt;) were initializer
         * writes in the DEX, so they are left to
         * {@link ClassFileWriter#unfinalFieldsWrittenByInitializerHelpers}
         * (DEX2JVM_INITIALIZER_HELPER_FINALS) and not counted as foreign writes
         * by {@link ClassFileWriter#unfinalFieldsWrittenOutsideInitializers}.
         */
        public MethodWriter markInitializerHelper(String initializer) {
            this.initializerOf = initializer;
            return this;
        }

        /**
         * Creates the CodeWriter for this method, pre-wired with the implicit
         * frame at offset 0 that a StackMapTable's first delta is measured
         * against (JVMS 4.10.1.6). Deriving it here rather than asking the
         * caller removes the two ways to get it wrong: forgetting `this`, and
         * using the class type instead of uninitializedThis in a constructor.
         */
        public CodeWriter newCode() {
            if ((access & (ACC_ABSTRACT | ACC_NATIVE)) != 0) {
                throw new IllegalStateException("abstract/native methods must not have a Code attribute");
            }
            code = new CodeWriter(owner.pool);
            code.setInitialFrame(StackMapWriter.initialFrame(
                    owner.thisName, name, desc, (access & ACC_STATIC) != 0));
            // JVMS 4.7.3: max_locals must cover the argument slots even for a
            // method that never reads them.
            code.setMaxLocals(CodeWriter.argSlots(desc) + ((access & ACC_STATIC) != 0 ? 0 : 1));
            return code;
        }

        public CodeWriter code() { return code; }

        /** Drop a partially-built body so a fresh one can replace it. Used by
         *  the oversize-method stub path in DexConverter: a method whose
         *  translation threw CodeLengthExceeded may have left a CodeWriter
         *  attached, and the stub must not be appended to it. */
        public void resetCode() { code = null; }

        /** Attaches a CodeWriter built elsewhere. */
        public MethodWriter setCode(CodeWriter cw) {
            this.code = cw;
            return this;
        }

        /** Exceptions attribute (JVMS 4.7.5): the `throws` clause. Reflection
         *  reads it, so Retrofit-style libraries that inspect it need it. */
        public MethodWriter setExceptions(String[] internalNames) {
            ConstantPool.ByteVector b = new ConstantPool.ByteVector(2 + internalNames.length * 2);
            b.putU2(internalNames.length);
            for (String n : internalNames) b.putU2(owner.pool.classRef(n));
            return (MethodWriter) addAttribute("Exceptions", b.toByteArray());
        }

        @Override void write(ConstantPool pool, ConstantPool.ByteVector out) {
            if (code != null) {
                // Serialized here, at the last possible moment, so any constant
                // the body needs still lands in the pool before it is written.
                attrs.add(0, new Attr("Code", code.toCodeAttributeBody()));
                code = null;
            }
            super.write(pool, out);
        }
    }

    private final ConstantPool pool;
    private int majorVersion = DEFAULT_VERSION;
    private int minorVersion = 0;

    private final int access;
    private final String thisName;
    private final String superName;
    private final String[] interfaces;
    /**
     * Direct superinterfaces added AFTER construction, appended after the DEX's
     * own in the order they were added. See {@link #addDirectInterface}.
     */
    private final List<String> addedInterfaces = new ArrayList<>();
    /** The class's own Signature, kept so an added interface can extend it. */
    private String classSignature;

    private final List<FieldWriter> fields = new ArrayList<>();
    private final List<MethodWriter> methods = new ArrayList<>();
    private final List<Attr> attrs = new ArrayList<>();

    // BootstrapMethods entries, each {method handle cp index, argument cp indexes}.
    private final List<int[]> bootstrapMethods = new ArrayList<>();

    public ClassFileWriter(int access, String thisName, String superName, String[] interfaces) {
        this(new ConstantPool(), access, thisName, superName, interfaces);
    }

    public ClassFileWriter(ConstantPool pool, int access, String thisName,
                           String superName, String[] interfaces) {
        this.pool = pool;
        this.thisName = thisName;
        this.superName = superName;
        this.interfaces = (interfaces == null) ? new String[0] : interfaces;
        // ACC_SUPER on every non-interface, which is what javac emits and what
        // enjarify sets for the same reason. JVMS 4.1 notes it is ignored from
        // version 52 onward (invokespecial always uses the ACC_SUPER
        // semantics), but older tooling reads class files too and a missing
        // ACC_SUPER changes how they interpret invokespecial.
        this.access = ((access & ACC_INTERFACE) != 0) ? access : (access | ACC_SUPER);
    }

    public ConstantPool pool() { return pool; }
    public String thisName() { return thisName; }
    public int majorVersion() { return majorVersion; }

    /** The major_version toByteArray will actually write. */
    public int effectiveMajorVersion() { return Math.max(majorVersion, versionFloor()); }

    /** "name:descriptor" of this class's own fields that a synthetic helper
     *  split or outlined out of an initializer writes: statics (putstatic)
     *  from a helper of &lt;clinit&gt;, instance fields (putfield) from a helper of
     *  &lt;init&gt;. One key per field, since JVMS 4.5 forbids two fields of a
     *  class with the same name and descriptor. */
    private final java.util.Set<String> initializerHelperPuts = new java.util.LinkedHashSet<>();

    public void noteInitializerHelperPut(String name, String desc) {
        initializerHelperPuts.add(name + ":" + desc);
    }

    /**
     * Clear ACC_FINAL on every own field an initializer's synthetic helper
     * writes, when the class is emitted at major_version 53 or above. Returns
     * how many.
     *
     * JVMS SE21 6.5 putstatic: "if the field is final, it must be declared in
     * the current class or interface, and the instruction must occur in the
     * class or interface initialization method of the current class or
     * interface"; 6.5 putfield: "If the field is final, it must be declared in
     * the current class, and the instruction must occur in an instance
     * initialization method of the current class". HotSpot enforces the SECOND half only from class-file version 53
     * on, for both opcodes (jdk21u linkResolver.cpp, LinkResolver::resolve_field:
     * `if (fd.constants()->pool_holder()->major_version() >= 53)` guards
     * `is_initialized_static_final_update` (putstatic, not in &lt;clinit&gt;) and
     * `is_initialized_instance_final_update` (putfield, not in &lt;init&gt;), both
     * "Update to %s final field %s.%s attempted from a different method (%s)
     * than the initializer method %s"), and it does so at RESOLUTION, so
     * disabling bytecode verification does not skip it. Below 53 a helper of
     * the same class may write the field, which is what every split or
     * outlined initializer relies
     * on at our default version 52. A NestHost/NestMembers attribute raises the
     * class to 55 (versionFloor), and then the helper's first put is an
     * IllegalAccessError: a &lt;clinit&gt; never initialises the class, an &lt;init&gt;
     * never constructs an instance.
     *
     * ART has no such rule for its own code (the DEX was one method), and it
     * lets an accessible Field.set write a static final, so dropping the flag
     * on exactly these fields is the smallest difference that keeps both the
     * nest (private access, getNestHost) and the class alive. Nothing else
     * about the fields changes.
     *
     * Not for an INTERFACE: JVMS 4.5 requires every interface field to be
     * public static final, so clearing the flag would trade the
     * IllegalAccessError for a ClassFormatError on a JVM that verifies. An
     * interface keeps its fields as they are (and has no instance fields).
     */
    public int unfinalFieldsWrittenByInitializerHelpers() {
        if (initializerHelperPuts.isEmpty() || effectiveMajorVersion() < 53) return 0;
        if ((access & ACC_INTERFACE) != 0) return 0;
        int n = 0;
        for (FieldWriter f : fields) {
            if ((f.access & ACC_FINAL) == 0) continue;
            if (!initializerHelperPuts.contains(f.name + ":" + f.desc)) continue;
            f.access &= ~ACC_FINAL;
            n++;
        }
        return n;
    }

    /** See {@link #setUnfinalForeignWrites}. */
    private boolean unfinalForeignWrites;
    /** "name:descriptor" of each field toByteArray cleared ACC_FINAL on under
     *  that rule, in field order, for the converter's log. */
    private List<String> foreignWriteUnfinals = java.util.Collections.emptyList();

    /**
     * Have toByteArray apply {@link #unfinalFieldsWrittenOutsideInitializers}
     * once the class is complete. It runs there, rather than when the caller
     * thinks the class is done, so it sees every method whoever added it: the
     * forwarding constructors, a precomputed initializer's helpers, any rescue
     * that adds a method late.
     */
    public void setUnfinalForeignWrites(boolean on) { this.unfinalForeignWrites = on; }

    /** The fields (as "name:descriptor") that rule cleared ACC_FINAL on in the
     *  last toByteArray; empty when it did not fire. */
    public List<String> foreignWriteUnfinals() { return foreignWriteUnfinals; }

    /**
     * Clear ACC_FINAL on every own field the bytecode of this class writes from
     * a method that may not write it, when the class is emitted at
     * major_version 53 or above. Returns how many fields.
     *
     * A static final may then be written only by a putstatic in &lt;clinit&gt;, an
     * instance final only by a putfield in &lt;init&gt; (JVMS SE21 6.5; HotSpot
     * checks it at resolution, so turning bytecode verification off does not skip it; see
     * {@link #unfinalFieldsWrittenByInitializerHelpers} for the source). ART has
     * no such rule. Its verifier refuses a put to a final field only from
     * ANOTHER class, so DEX code may write its own static final from any of its
     * methods, and obfuscators do: a static {@code init$0} that &lt;clinit&gt;
     * calls to fill a {@code private static final} table, say. On the JVM that
     * write is an IllegalAccessError ("Update to static final field ...
     * attempted from a different method (init$0) than the initializer method
     * &lt;clinit&gt;"), and the class never initializes. An instance final
     * written from outside a constructor (or from &lt;clinit&gt;) fails the same
     * way.
     *
     * The rule reads the emitted instructions (CodeWriter.fieldPuts), not
     * the DEX: a put counts when its Fieldref names this class, a field
     * declared here and final, of the kind the opcode writes (static for
     * putstatic, instance for putfield), and the method is not the initializer
     * of that kind. A synthetic method marked with
     * {@link MethodWriter#markInitializerHelper} counts as the initializer it
     * came from, because those writes were initializer writes in the DEX and
     * DEX2JVM_INITIALIZER_HELPER_FINALS governs them. Other synthetic methods
     * are counted like any other method. Below 53 HotSpot lets any method of
     * the class write the field, so nothing changes there.
     *
     * A ConstantValue attribute stays. JVMS 4.7.2 assigns it to every field
     * with ACC_STATIC set, final or not, before &lt;clinit&gt; runs, and HotSpot
     * parses it for any static field. The converter already emits ConstantValue
     * on non-final statics, because DEX static_values cover them as well.
     *
     * Not for an INTERFACE: JVMS 4.5 requires its fields to be public static
     * final (see unfinalFieldsWrittenByInitializerHelpers).
     *
     * Only access_flags change, and the constant pool is only read, so a class
     * this does not touch is byte for byte what it was.
     */
    public int unfinalFieldsWrittenOutsideInitializers() {
        if (effectiveMajorVersion() < 53 || (access & ACC_INTERFACE) != 0) return 0;
        java.util.Map<String, FieldWriter> finals = null;
        for (FieldWriter f : fields) {
            if ((f.access & ACC_FINAL) == 0) continue;
            if (finals == null) finals = new java.util.HashMap<>();
            finals.put(ConstantPool.memberKey(thisName, f.name, f.desc), f);
        }
        if (finals == null) return 0;
        java.util.Set<FieldWriter> hit = null;
        for (MethodWriter m : methods) {
            if (m.code == null) continue;
            String as = (m.initializerOf != null) ? m.initializerOf : m.name;
            int[] puts = m.code.fieldPuts();
            for (int i = 0; i < puts.length; i += 2) {
                boolean isStatic = puts[i] == CodeWriter.PUTSTATIC;
                if (as.equals(isStatic ? "<clinit>" : "<init>")) continue;
                FieldWriter f = finals.get(m.code.pool().fieldRefKey(puts[i + 1]));
                if (f == null || (f.access & ACC_FINAL) == 0) continue;
                // A putstatic of an instance field (or the reverse) fails
                // resolution with IncompatibleClassChangeError whatever the flag.
                if (((f.access & ACC_STATIC) != 0) != isStatic) continue;
                f.access &= ~ACC_FINAL;
                if (hit == null) hit = new java.util.HashSet<>();
                hit.add(f);
            }
        }
        if (hit == null) return 0;
        // Declaration order, so the log line is stable.
        List<String> cleared = new ArrayList<>(hit.size());
        for (FieldWriter f : fields) {
            if (hit.contains(f)) cleared.add(f.name + ":" + f.desc);
        }
        foreignWriteUnfinals = cleared;
        return cleared.size();
    }

    public void setVersion(int major, int minor) {
        this.majorVersion = major;
        this.minorVersion = minor;
    }

    /** SourceFile attribute (JVMS 4.7.10). Half of what makes a converted stack
     *  trace readable; the other half is CodeWriter.lineNumber(). */
    public void setSourceFile(String fileName) {
        ConstantPool.ByteVector b = new ConstantPool.ByteVector(2);
        b.putU2(pool.utf8(fileName));
        addAttribute("SourceFile", b.toByteArray());
    }

    /** Signature attribute (JVMS 4.7.9) for the class itself. */
    public void setSignature(String signature) {
        classSignature = signature;
        ConstantPool.ByteVector b = new ConstantPool.ByteVector(2);
        b.putU2(pool.utf8(signature));
        addAttribute("Signature", b.toByteArray());
    }

    /**
     * Add a DIRECT superinterface the DEX class only inherits, so that an
     * invokespecial naming it becomes legal (JVMS 4.9.2). Returns false when it
     * is already a direct superinterface. The type relation is unchanged -- the
     * class was already a subtype of the interface -- so instanceof, casts and
     * JVMS 5.4.6 selection (maximally-specific methods over the SAME
     * superinterface set) cannot change; see SuperInterfacePlan for the one
     * site that calls this and what it costs (Class.getInterfaces() gains an
     * entry; a class Signature, if any, gains the matching raw entry so
     * getGenericInterfaces() stays index-aligned with getInterfaces()).
     */
    public boolean addDirectInterface(String internalName) {
        if (internalName == null || internalName.equals(thisName)) return false;
        for (String i : interfaces) if (i.equals(internalName)) return false;
        if (addedInterfaces.contains(internalName)) return false;
        addedInterfaces.add(internalName);
        return true;
    }

    /** The Signature attribute body to publish, reflecting added interfaces. */
    private void refreshClassSignatureForAddedInterfaces() {
        if (addedInterfaces.isEmpty() || classSignature == null) return;
        StringBuilder sig = new StringBuilder(classSignature);
        // JVMS 4.7.9.1: ClassSignature ends with {SuperinterfaceSignature}, each a
        // ClassTypeSignature; a raw `L...;` is a legal one.
        for (String i : addedInterfaces) sig.append('L').append(i).append(';');
        for (int k = 0; k < attrs.size(); k++) {
            if (!"Signature".equals(attrs.get(k).name)) continue;
            ConstantPool.ByteVector b = new ConstantPool.ByteVector(2);
            b.putU2(pool.utf8(sig.toString()));
            attrs.set(k, new Attr("Signature", b.toByteArray()));
            return;
        }
    }

    /** EnclosingMethod attribute (JVMS 4.7.7) for a local or anonymous class.
     *  Pass null name/desc when the class is enclosed by a class rather than by
     *  a method, which the attribute encodes as method_index 0. */
    public void setEnclosingMethod(String ownerInternalName, String methodName, String methodDesc) {
        ConstantPool.ByteVector b = new ConstantPool.ByteVector(4);
        b.putU2(pool.classRef(ownerInternalName));
        b.putU2(methodName == null ? 0 : pool.nameAndType(methodName, methodDesc));
        addAttribute("EnclosingMethod", b.toByteArray());
        hasEnclosingMethod = true;
    }

    private boolean hasEnclosingMethod;
    /** Enclosing class of THIS class when it is anonymous, captured from the
     *  InnerClasses entry so the EnclosingMethod attribute can be reconstructed.
     *  See addInnerClass. */
    private String anonymousEnclosingClass;

    private final List<int[]> innerClasses = new ArrayList<>();
    private final List<String[]> innerClassNames = new ArrayList<>();

    // ---- nest membership (JVMS 4.7.28 / 4.7.29) --------------------------
    //
    // A nest is what makes private members mutually accessible between a class
    // and the classes nested inside it. Before nestmates (Java 11) javac
    // generated package-private synthetic accessor methods for that; from 11 on
    // it emits NestHost on each nested class and NestMembers on the outermost
    // one, and the private members stay private.
    //
    // DEX has no nest attributes at all, so without reconstruction every such
    // access becomes IllegalAccessError at first execution. NestPlan derives
    // both halves from the same InnerClasses / EnclosingMethod information this
    // class already receives; see the header there for why the host has to be
    // the OUTERMOST enclosing class and not the immediate one.

    private String nestHost;
    private final List<String> nestMembers = new ArrayList<>();

    /**
     * NestHost attribute (JVMS 4.7.28): the class hosting the nest this class
     * belongs to.
     *
     * JVMS 4.7.28: "A class may not have both a NestHost attribute and a
     * NestMembers attribute." Enforced here rather than left to the reader,
     * because HotSpot's response to the contradiction is to silently make the
     * class its own nest host (JVMS 5.4.4), i.e. the attribute would be written,
     * accepted, and then ignored.
     */
    public void setNestHost(String hostInternalName) {
        if (hostInternalName == null) return;
        if (!nestMembers.isEmpty()) {
            throw new IllegalStateException(
                    "JVMS 4.7.28: a class may not have both NestHost and NestMembers ("
                            + thisName + ")");
        }
        this.nestHost = hostInternalName;
    }

    /**
     * NestMembers attribute (JVMS 4.7.29): every class that names this one as
     * its nest host.
     *
     * The list must be COMPLETE in the direction that matters. JVMS 5.4.4 makes
     * a nested class fall back to being its own nest host unless the resolved
     * host "has a NestMembers attribute, [and] there is an entry in its classes
     * array that refers to a class or interface with the name N", so a member
     * missing from this list silently loses nestmate access rather than failing
     * loudly. Entries are compared BY NAME by HotSpot
     * (instanceKlass.cpp has_nest_member reads klass_name_at and compares
     * Symbols, never resolving), so an entry naming a class that is not present
     * at run time costs nothing.
     */
    public void setNestMembers(List<String> memberInternalNames) {
        if (memberInternalNames == null || memberInternalNames.isEmpty()) return;
        if (nestHost != null) {
            throw new IllegalStateException(
                    "JVMS 4.7.28: a class may not have both NestHost and NestMembers ("
                            + thisName + ")");
        }
        for (String m : memberInternalNames) {
            // A host is implicitly in its own nest (JVMS 5.4.4: the nestmate
            // test succeeds outright when C and D are the same class), so
            // listing it would be redundant, and javac does not.
            if (m == null || m.equals(thisName) || nestMembers.contains(m)) continue;
            nestMembers.add(m);
        }
    }

    /** The nest host set by setNestHost, or null. */
    public String nestHost() { return nestHost; }

    /** The nest members set by setNestMembers, never null. */
    public List<String> nestMembers() { return nestMembers; }

    /**
     * One InnerClasses entry (JVMS 4.7.6). Reflection needs it:
     * Class.getSimpleName(), isAnonymousClass() and getEnclosingClass() are all
     * answered from this attribute, and DEX carries the same information in the
     * dalvik.annotation.InnerClass / MemberClasses system annotations.
     *
     * outerName and innerSimpleName may be null (anonymous / local classes).
     *
     * A null innerSimpleName FORCES outerName to null, because JVMS 4.7.6 makes
     * the two fields dependent: "If C is anonymous (JLS 15.9.5), the value of
     * the inner_name_index item must be zero", and "the value of the
     * outer_class_info_index item must be zero if the value of the
     * inner_name_index item is zero". Normalizing here rather than at the call
     * site is deliberate -- DEX cannot express the difference directly. It marks
     * an anonymous class as enclosed by a CLASS (dalvik.annotation.EnclosingClass)
     * exactly as it marks a member class, and the only thing separating them is
     * the InnerClass annotation's null `name`. An enum with constant-specific
     * bodies is the common case, and getting it wrong made
     * Class.getDeclaringClass answer the enum instead of null and
     * isMemberClass answer true instead of false. HotSpot does not enforce the
     * rule ("Oracle's Java Virtual Machine implementation does not check the
     * consistency of an InnerClasses attribute", JVMS 4.7.6), so the only
     * symptom is reflection quietly disagreeing with a javac build.
     */
    public void addInnerClass(String innerName, String outerName, String innerSimpleName, int innerAccess) {
        if (innerSimpleName == null && outerName != null) {
            // Remember it for THIS class only: JVMS 4.7.7 says the enclosing
            // class of an anonymous class travels in EnclosingMethod instead,
            // and that attribute describes the class file it appears in.
            if (thisName.equals(innerName)) anonymousEnclosingClass = outerName;
            outerName = null;
        }
        innerClassNames.add(new String[] { innerName, outerName, innerSimpleName });
        innerClasses.add(new int[] { innerAccess });
    }

    /**
     * Appends a BootstrapMethods entry (JVMS 4.7.23) and returns its index,
     * which is what CONSTANT_InvokeDynamic_info's bootstrap_method_attr_index
     * and CodeWriter.invokeDynamic() take. Identical entries are deduplicated,
     * so a class with many lambdas sharing a metafactory does not accumulate
     * copies.
     */
    public int addBootstrapMethod(int methodHandleCpIndex, int[] argumentCpIndexes) {
        int[] args = (argumentCpIndexes == null) ? new int[0] : argumentCpIndexes;
        int[] entry = new int[1 + args.length];
        entry[0] = methodHandleCpIndex;
        System.arraycopy(args, 0, entry, 1, args.length);
        for (int i = 0; i < bootstrapMethods.size(); i++) {
            if (java.util.Arrays.equals(bootstrapMethods.get(i), entry)) return i;
        }
        bootstrapMethods.add(entry);
        return bootstrapMethods.size() - 1;
    }

    /** Raw class-level attribute (RuntimeVisibleAnnotations, NestMembers, and
     *  anything else the front end serializes itself). */
    public void addAttribute(String name, byte[] body) {
        attrs.add(new Attr(name, body));
    }

    public FieldWriter addField(int fieldAccess, String name, String desc) {
        FieldWriter f = new FieldWriter(this, fieldAccess, name, desc);
        fields.add(f);
        return f;
    }

    public MethodWriter addMethod(int methodAccess, String name, String desc) {
        MethodWriter m = new MethodWriter(this, methodAccess, name, desc);
        methods.add(m);
        return m;
    }

    // ---- serialization ---------------------------------------------------

    /**
     * The lowest class file version at which every attribute this class carries
     * is actually recognised.
     *
     * Raised per class rather than for the whole output, so the 99.9% of
     * classes that carry no nest attribute keep the 52 the rest of this file
     * argues for. That is the same rule DEFAULT_VERSION already applies, just
     * evaluated per class instead of once: pick the minimum version that makes
     * the constructs present mean what they say.
     */
    private int versionFloor() {
        if (nestHost != null || !nestMembers.isEmpty()) return V11;   // JVMS Table 4.7-C
        return 0;
    }

    public byte[] toByteArray() {
        // JVMS 4.7.7: "A class must have an EnclosingMethod attribute if and
        // only if it represents a local class or an anonymous class." DEX
        // carries dalvik.annotation.EnclosingMethod only when the enclosing
        // context really is a method, and uses EnclosingClass otherwise -- so an
        // anonymous class declared directly in a class body (the enum
        // constant-specific body, overwhelmingly) arrives with no method to name
        // and would end up with no EnclosingMethod attribute at all. javac emits
        // one with method_index 0, which JVMS 4.7.7 provides for: "If the
        // current class is not immediately enclosed by a method or constructor,
        // then the value of the method_index item must be zero." Without it
        // Class.isAnonymousClass answers false, because HotSpot decides
        // local-or-anonymous purely on the presence of this attribute.
        if (!hasEnclosingMethod && anonymousEnclosingClass != null) {
            setEnclosingMethod(anonymousEnclosingClass, null, null);
        }

        // Before any field is serialized, while every method still holds its
        // CodeWriter (MethodWriter.write drops it).
        foreignWriteUnfinals = java.util.Collections.emptyList();
        if (unfinalForeignWrites) unfinalFieldsWrittenOutsideInitializers();

        // Reserve this_class / super_class early so they land in the low pool
        // slots, purely for readability of javap output.
        int thisIndex = pool.classRef(thisName);
        int superIndex = (superName == null) ? 0 : pool.classRef(superName);

        ConstantPool.ByteVector body = new ConstantPool.ByteVector(1024);
        body.putU2(access);
        body.putU2(thisIndex);
        body.putU2(superIndex);

        checkCount(interfaces.length + addedInterfaces.size(), "interfaces");
        body.putU2(interfaces.length + addedInterfaces.size());
        for (String i : interfaces) body.putU2(pool.classRef(i));
        for (String i : addedInterfaces) body.putU2(pool.classRef(i));
        refreshClassSignatureForAddedInterfaces();

        checkCount(fields.size(), "fields");
        body.putU2(fields.size());
        for (FieldWriter f : fields) f.write(pool, body);

        // Methods are serialized before the class attributes because a method
        // body can still add pool entries, and because a Code attribute is by
        // far the most likely place to hit a pool limit.
        checkCount(methods.size(), "methods");
        body.putU2(methods.size());
        for (MethodWriter m : methods) m.write(pool, body);

        List<Attr> classAttrs = new ArrayList<>(attrs);
        // Before InnerClasses, which is where javac puts them. Attribute order
        // is not specified by JVMS 4.1 and no reader depends on it; matching
        // javac only keeps a javap diff against a reference build readable.
        if (nestHost != null) {
            // JVMS 4.7.28: attribute_length must be two, i.e. the body is
            // exactly the u2 host_class_index.
            ConstantPool.ByteVector b = new ConstantPool.ByteVector(2);
            b.putU2(pool.classRef(nestHost));
            classAttrs.add(new Attr("NestHost", b.toByteArray()));
        }
        if (!nestMembers.isEmpty()) {
            checkCount(nestMembers.size(), "NestMembers classes");
            ConstantPool.ByteVector b = new ConstantPool.ByteVector(2 + nestMembers.size() * 2);
            b.putU2(nestMembers.size());
            for (String m : nestMembers) b.putU2(pool.classRef(m));
            classAttrs.add(new Attr("NestMembers", b.toByteArray()));
        }
        if (!innerClasses.isEmpty()) {
            ConstantPool.ByteVector b = new ConstantPool.ByteVector(2 + innerClasses.size() * 8);
            b.putU2(innerClasses.size());
            for (int i = 0; i < innerClasses.size(); i++) {
                String[] names = innerClassNames.get(i);
                b.putU2(pool.classRef(names[0]));
                b.putU2(names[1] == null ? 0 : pool.classRef(names[1]));
                b.putU2(names[2] == null ? 0 : pool.utf8(names[2]));
                b.putU2(innerClasses.get(i)[0]);
            }
            classAttrs.add(new Attr("InnerClasses", b.toByteArray()));
        }
        if (!bootstrapMethods.isEmpty()) {
            ConstantPool.ByteVector b = new ConstantPool.ByteVector(2 + bootstrapMethods.size() * 8);
            b.putU2(bootstrapMethods.size());
            for (int[] e : bootstrapMethods) {
                b.putU2(e[0]);
                b.putU2(e.length - 1);
                for (int i = 1; i < e.length; i++) b.putU2(e[i]);
            }
            classAttrs.add(new Attr("BootstrapMethods", b.toByteArray()));
        }

        checkCount(classAttrs.size(), "class attributes");
        body.putU2(classAttrs.size());
        for (Attr a : classAttrs) {
            body.putU2(pool.utf8(a.name));
            body.putU4(a.body.length);
            body.putBytes(a.body);
        }

        // The pool is complete only now, which is why it is written last even
        // though it comes third in the file.
        ConstantPool.ByteVector out = new ConstantPool.ByteVector(body.length() + 4096);
        out.putU4(0xCAFEBABE);
        out.putU2(minorVersion);
        out.putU2(Math.max(majorVersion, versionFloor()));
        pool.writeTo(out);
        out.putBytes(body.toByteArray());
        return out.toByteArray();
    }
}
