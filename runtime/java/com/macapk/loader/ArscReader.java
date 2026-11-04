// Native resources.arsc (ResTable) reader -- the "better apktool" for the
// resource-table path, the companion to BinaryXmlParser (the manifest path).
//
// WHY: at install time we currently shell out to apktool to decode
// resources.arsc into a res/values*/*.xml text tree that ResourceIndex
// then reads. apktool is a ~10MB bundled Java tool that forks its own
// JVM and writes up to ~45MB of decoded text per app. Parsing the BINARY
// table directly in-process removes that tool, that fork, and that disk,
// and gives us aapt's real typed values (a string "true" vs a boolean
// true, a color's exact ARGB flavor, a dimension's unit) that the text
// form flattens.
//
// FORMAT: verified verbatim against AOSP libs/androidfw/include/androidfw/
// ResourceTypes.h (fetched 2026-07-24); the full layout with byte offsets
// is in docs/arsc-format-contract_2026-07-24.md. Struct sizes used below:
//   ResChunk_header 8, Res_value 8, ResStringPool_header 28,
//   ResTable_header 12, ResTable_package 288, ResTable_typeSpec 16,
//   ResTable_type (variable, config is last), ResTable_entry Full 8 /
//   Compact 8, ResTable_map_entry 16, ResTable_map 12.
//
// SCOPE (this file): parse the whole table -- all packages, types,
// entries, every config -- into a flat list of rows, plus
//   * an id -> (type,name) index (== apktool's res/values/public.xml),
//   * the per-config apktool DIRECTORY QUALIFIER string ("-fr-rCA-v21"),
//     byte-compatible with apktool's ResConfig.computeQualifiers so a row
//     lands in the same values-<quals> bucket ResourceIndex already scores,
//   * full COMPLEX (bag) entries -- style/array/plurals: parent + the
//     name/value pairs,
//   * @type/name REFERENCE resolution against the table's own ids (plus an
//     optional framework id->name map for the android: package), and
//   * string-pool STYLE SPANS, so styled strings keep their markup.
//
// It is validated against apktool's own output by debug/arsc_verify.sh
// across the whole APK fleet before it is wired into the default path.
package com.macapk.loader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ArscReader {

    // ---- chunk types (docs/arsc-format-contract) --------------------
    private static final int RES_STRING_POOL_TYPE   = 0x0001;
    private static final int RES_TABLE_TYPE         = 0x0002;
    private static final int RES_TABLE_PACKAGE_TYPE = 0x0200;
    private static final int RES_TABLE_TYPE_TYPE    = 0x0201;
    private static final int RES_TABLE_TYPE_SPEC    = 0x0202;

    // ---- Res_value dataType -----------------------------------------
    public static final int TYPE_NULL        = 0x00;
    public static final int TYPE_REFERENCE   = 0x01;
    public static final int TYPE_ATTRIBUTE   = 0x02;
    public static final int TYPE_STRING      = 0x03;
    public static final int TYPE_FLOAT       = 0x04;
    public static final int TYPE_DIMENSION   = 0x05;
    public static final int TYPE_FRACTION    = 0x06;
    public static final int TYPE_DYNAMIC_REFERENCE = 0x07;
    public static final int TYPE_DYNAMIC_ATTRIBUTE = 0x08;
    public static final int TYPE_INT_DEC     = 0x10;
    public static final int TYPE_INT_HEX     = 0x11;
    public static final int TYPE_INT_BOOLEAN = 0x12;
    public static final int TYPE_COLOR_ARGB8 = 0x1c;
    public static final int TYPE_COLOR_RGB8  = 0x1d;
    public static final int TYPE_COLOR_ARGB4 = 0x1e;
    public static final int TYPE_COLOR_RGB4  = 0x1f;

    // ---- ResTable_type flags ----------------------------------------
    private static final int FLAG_SPARSE   = 0x01;
    private static final int FLAG_OFFSET16 = 0x02;

    // ---- ResTable_entry flags ---------------------------------------
    private static final int ENTRY_FLAG_COMPLEX = 0x0001;
    private static final int ENTRY_FLAG_COMPACT = 0x0008;

    private static final long NO_ENTRY = 0xffffffffL;

    /** A style span over a pooled string: ResStringPool_span {name, first, last}.
     *  `tag` is the raw pool string, either "b" or "a;href=http://x" (the
     *  attributes follow the tag name, ';'-separated, each "key=value"). */
    public static final class Span {
        public final String tag;
        public final int firstChar;
        public final int lastChar;
        Span(String tag, int firstChar, int lastChar) {
            this.tag = tag; this.firstChar = firstChar; this.lastChar = lastChar;
        }
    }

    /** One name/value pair inside a complex (bag) entry: a ResTable_map. */
    public static final class MapItem {
        /** ResTable_ref: an attr resource id, or a ResTable_map internal
         *  pseudo-id -- Res_MAKEARRAY(i) for an array item, ATTR_ONE /
         *  ATTR_OTHER / ... for a plurals quantity. */
        public final int nameRef;
        /** Resolved name for `nameRef`: "attr/textColor", "android:attr/x",
         *  a plurals quantity ("one"/"other"/...), or "" for array items. */
        public final String name;
        public final int dataType;
        public final int data;
        public final String value;   // formatted, references resolved
        MapItem(int nameRef, String name, int dataType, int data, String value) {
            this.nameRef = nameRef; this.name = name;
            this.dataType = dataType; this.data = data; this.value = value;
        }
    }

    /** One decoded resource row (a single entry in a single config). */
    public static final class Row {
        public final int resId;          // 0xPPTTEEEE
        public final int pkgId;
        public final String pkg;         // owning package name ("com.x", "android")
        public final String type;        // "string","color","attr","style",...
        public final String name;        // entry key, e.g. "app_name"
        /** apktool's values-directory qualifier suffix for this row's config:
         *  "" for the default config, else e.g. "-fr-rCA-xhdpi-v21". */
        public final String config;
        public final boolean defaultConfig;
        public final boolean complex;    // true => a style/array/plurals bag
        // --- complex only ---
        public final int parentRef;      // parent style id, 0 if none
        public final String parent;      // "@style/Foo" / "" if none
        public final List<MapItem> items;
        // --- simple only ---
        public final int dataType;       // Res_value.dataType
        public final int data;           // Res_value.data
        public final String value;       // formatted value ("" for complex)
        public final Span[] spans;       // style spans, null when unstyled

        Row(int resId, int pkgId, String pkg, String type, String name,
            String config, boolean complex, int parentRef, String parent,
            List<MapItem> items, int dataType, int data, String value, Span[] spans) {
            this.resId = resId; this.pkgId = pkgId; this.pkg = pkg;
            this.type = type; this.name = name;
            this.config = config; this.defaultConfig = config.isEmpty();
            this.complex = complex; this.parentRef = parentRef; this.parent = parent;
            this.items = items; this.dataType = dataType; this.data = data;
            this.value = value; this.spans = spans;
        }
    }

    // A row as first walked: values are still RAW (dataType/data) because
    // reference formatting needs the COMPLETE id->name table, which only
    // exists after the whole walk. finish() turns these into Rows.
    private static final class RawRow {
        int resId, pkgId; String pkg, type, name, config;
        boolean complex; int parentRef; int dataType, data;
        int[] mapNames; int[] mapTypes; int[] mapData;
    }

    /** The table bytes. Read only while parsing (run()); parse() and
     *  parseFrameworkIndex() drop it once the reader is built, because the
     *  framework reader is cached for the life of the process and the
     *  platform table is ~52 MB: holding it kept 52 MB of dead bytes in every
     *  app's Java heap (measured on Metrolist, 2026-10-19). A device maps
     *  resources.arsc read-only and shares it; nothing here reads it again. */
    private byte[] d;
    private static final byte[] RELEASED = new byte[0];
    /** MACAPK_ARSC_KEEP_BYTES=1 keeps the bytes after parsing (pre-v1.0.2254). */
    private static final boolean KEEP_BYTES = "1".equals(System.getenv("MACAPK_ARSC_KEEP_BYTES"));
    private void releaseBytes() { if (!KEEP_BYTES) d = RELEASED; }
    /** True once the table bytes were dropped (gate hook). */
    boolean bytesReleased() { return d == RELEASED; }
    private String[] globalStrings = new String[0];
    private Span[][] globalStyles = new Span[0][];
    private final List<RawRow> raw = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    // resId -> "type/name" (config-independent == public.xml)
    private final Map<Integer, String> publicMap = new LinkedHashMap<>();
    // pkgId -> package name, for the "@<pkg>:type/name" cross-package prefix
    private final Map<Integer, String> pkgNames = new LinkedHashMap<>();
    // Optional id -> "type/name" for resources this table does NOT contain
    // (in practice package 0x01, the android framework). apktool resolves
    // those against its bundled framework 1.apk; we take an injected map so
    // this class stays a pure parser with no I/O.
    private Map<Integer, String> frameworkMap = Collections.emptyMap();

    private ArscReader(byte[] data) { this.d = data; }

    /** Parse a resources.arsc blob. Throws on anything that doesn't look
     *  like a resource table -- callers keep apktool as the fallback. */
    public static ArscReader parse(byte[] arsc) throws Exception {
        return parse(arsc, null);
    }

    /** Parse with an id -> "type/name" map for out-of-table (framework)
     *  references, so `@android:string/ok` resolves by name instead of
     *  degrading to a raw id. Pass null for none. */
    public static ArscReader parse(byte[] arsc, Map<Integer, String> framework)
            throws Exception {
        return parse(arsc, framework, null);
    }

    /** Parse with a framework id -> name map AND a framework-table reader
     *  (from parseFrameworkIndex) whose attr enum/flag bags render framework
     *  enum ints back to their symbol names ("bold", "vertical"). apktool
     *  does exactly this against its bundled framework table, and the
     *  string-matching consumers above ResourceIndex were trained on that
     *  text form, so an arsc-fed index must render the same names. Without
     *  it a framework enum stays an int, which is what the v757 harness
     *  measured as the only bag-item divergence class (929 of 82,126). */
    public static ArscReader parse(byte[] arsc, Map<Integer, String> framework,
                                   ArscReader frameworkAttrSource)
            throws Exception {
        ArscReader r = new ArscReader(arsc);
        if (framework != null) r.frameworkMap = framework;
        // Pre-seed the attr symbol table with the framework's own attr bags.
        // collectAttrDefs skips ids already present; framework ids (0x01)
        // can never collide with this table's own packages, and an app that
        // (illegally) shadowed one would lose to the framework def exactly
        // as it would on a real device.
        if (frameworkAttrSource != null) r.attrDefs.putAll(frameworkAttrSource.attrDefs);
        r.run();
        r.finish();
        r.releaseBytes();
        return r;
    }

    /** Parse a FRAMEWORK table (the ~18.6MB android package arsc) for use
     *  as reference data only: the id -> "type/name" publicMap plus the
     *  attr enum/flag symbol bags. Skips everything else -- the global
     *  value string pool, style spans, and per-entry Row construction --
     *  because building formatted rows for a couple hundred thousand
     *  framework entries would cost real memory and time in the boot path
     *  for data no caller reads. rows() is empty on the returned reader. */
    public static ArscReader parseFrameworkIndex(byte[] arsc) throws Exception {
        ArscReader r = new ArscReader(arsc);
        r.indexOnly = true;
        r.run();
        r.collectAttrDefs();
        r.raw.clear();      // attr raw rows served their purpose
        r.releaseBytes();
        return r;
    }

    /** Index-only mode (parseFrameworkIndex): keep publicMap + attr bags,
     *  skip value formatting inputs. Never set by parse(). */
    private boolean indexOnly = false;

    public List<Row> rows() { return rows; }
    /** resId (0xPPTTEEEE) -> "type/name". Equivalent to res/values/public.xml. */
    public Map<Integer, String> publicMap() { return publicMap; }
    public Map<Integer, String> packageNames() { return pkgNames; }
    public String[] globalStrings() { return globalStrings; }

    // ---- little-endian readers --------------------------------------
    private int u8(int o)  { return d[o] & 0xff; }
    private int u16(int o) { return (d[o] & 0xff) | ((d[o + 1] & 0xff) << 8); }
    private int i32(int o) {
        return (d[o] & 0xff) | ((d[o + 1] & 0xff) << 8)
             | ((d[o + 2] & 0xff) << 16) | ((d[o + 3] & 0xff) << 24);
    }
    private long u32(int o) { return i32(o) & 0xffffffffL; }

    private void run() throws Exception {
        if (d.length < 12 || u16(0) != RES_TABLE_TYPE) {
            throw new IllegalArgumentException(
                "not a resource table (type=0x" + Integer.toHexString(
                    d.length >= 2 ? u16(0) : -1) + ")");
        }
        int headerSize = u16(2);
        long total = u32(4);
        if (total > d.length) total = d.length;

        // Walk the table's child chunks: one global string pool + N packages.
        int off = headerSize;
        while (off + 8 <= total) {
            int type = u16(off);
            int chHeader = u16(off + 2);
            long size = u32(off + 4);
            if (size < 8 || off + size > total) break;
            if (type == RES_STRING_POOL_TYPE) {
                // Index-only mode never formats values, and the global pool
                // is the value strings (the bulk of a framework table), so
                // skipping it is most of that mode's saving.
                if (!indexOnly) {
                    globalStrings = readStringPool(off);
                    globalStyles = readStylePool(off, globalStrings);
                }
            } else if (type == RES_TABLE_PACKAGE_TYPE) {
                parsePackage(off, chHeader, (int) size);
            }
            off += size;
        }
    }

    private void parsePackage(int pkgOff, int pkgHeaderSize, int pkgSize) {
        int pkgId = i32(pkgOff + 8);                       // +8
        String pkgName = readPackageName(pkgOff + 12);     // +12, u16[128]
        int typeStrings = i32(pkgOff + 268);               // +268
        int keyStrings  = i32(pkgOff + 276);               // +276
        String[] typeNames = typeStrings != 0
            ? readStringPool(pkgOff + typeStrings) : new String[0];
        String[] keyNames = keyStrings != 0
            ? readStringPool(pkgOff + keyStrings) : new String[0];
        if (!pkgName.isEmpty()) pkgNames.putIfAbsent(pkgId, pkgName);

        // Walk the package's child chunks (typeSpec + type). The two string
        // pools live inside the header region; child data starts at
        // pkgOff + pkgHeaderSize.
        int off = pkgOff + pkgHeaderSize;
        int end = pkgOff + pkgSize;
        while (off + 8 <= end) {
            int type = u16(off);
            int chHeader = u16(off + 2);
            long size = u32(off + 4);
            if (size < 8 || off + size > end) break;
            if (type == RES_TABLE_TYPE_TYPE) {
                parseType(off, chHeader, (int) size, pkgId, pkgName,
                          typeNames, keyNames);
            }
            // RES_TABLE_TYPE_SPEC carries only per-entry spec-flag masks;
            // not needed to extract values, so it is skipped.
            off += size;
        }
    }

    /** ResTable_package.name: 128 UTF-16 code units at +12, NUL-terminated. */
    private String readPackageName(int off) {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 128; ++i) {
            int p = off + i * 2;
            if (p + 1 >= d.length) break;
            int c = u16(p);
            if (c == 0) break;
            sb.append((char) c);
        }
        return sb.toString();
    }

    private void parseType(int tOff, int tHeaderSize, int tSize, int pkgId,
                           String pkgName, String[] typeNames, String[] keyNames) {
        int typeId = u8(tOff + 8);                         // +8
        int flags  = u8(tOff + 9);                         // +9
        int entryCount = i32(tOff + 12);                   // +12
        int entriesStart = i32(tOff + 16);                 // +16
        // config starts at +20; its first u32 is its size.
        int configSize = i32(tOff + 20);
        String config = configQualifiers(tOff + 20, configSize);

        String typeName = (typeId - 1) >= 0 && (typeId - 1) < typeNames.length
            ? typeNames[typeId - 1] : ("type" + typeId);

        int offArray = tOff + tHeaderSize;   // start of the entry-offset array
        int entriesBase = tOff + entriesStart;

        for (int i = 0; i < entryCount; ++i) {
            int entryIndex;
            long entryOffRel;
            if ((flags & FLAG_SPARSE) != 0) {
                // sparse: (u16 idx, u16 offset/4) packed in a u32
                int packed = i32(offArray + i * 4);
                entryIndex = packed & 0xffff;
                entryOffRel = ((long) ((packed >>> 16) & 0xffff)) * 4L;
            } else if ((flags & FLAG_OFFSET16) != 0) {
                int off16 = u16(offArray + i * 2);
                entryIndex = i;
                entryOffRel = (off16 == 0xffff) ? NO_ENTRY : ((long) off16) * 4L;
            } else {
                entryOffRel = u32(offArray + i * 4);
                entryIndex = i;
            }
            if (entryOffRel == NO_ENTRY) continue;
            int entryOff = entriesBase + (int) entryOffRel;
            if (entryOff + 4 > d.length) continue;
            decodeEntry(entryOff, pkgId, pkgName, typeId, entryIndex, typeName,
                        keyNames, config);
        }
    }

    private void decodeEntry(int entryOff, int pkgId, String pkgName, int typeId,
                             int entryIndex, String typeName, String[] keyNames,
                             String config) {
        RawRow r = new RawRow();
        r.resId = ((pkgId & 0xff) << 24) | ((typeId & 0xff) << 16)
                | (entryIndex & 0xffff);
        r.pkgId = pkgId; r.pkg = pkgName; r.type = typeName; r.config = config;
        int eflags = u16(entryOff + 2);      // flags live at +2 in both forms

        if ((eflags & ENTRY_FLAG_COMPACT) != 0) {
            // Compact (platform >= U): u16 key; u16 flags; u32 data.
            // dataType = flags>>8; there is no separate Res_value.
            r.name = keyName(keyNames, u16(entryOff));
            r.complex = false;
            r.dataType = (eflags >>> 8) & 0xff;
            r.data = i32(entryOff + 4);
        } else {
            int size = u16(entryOff);        // Full.size
            r.name = keyName(keyNames, i32(entryOff + 4));   // Full.key
            if ((eflags & ENTRY_FLAG_COMPLEX) != 0) {
                // ResTable_map_entry = Full(8) + u32 parent + u32 count, then
                // `count` ResTable_map (u32 name + Res_value(8) = 12 bytes).
                // AOSP starts the map array at entry + entry->size (normally
                // 16 == sizeof(ResTable_map_entry)); use the declared size
                // rather than a hardcoded 16 so an extended header still walks.
                r.complex = true;
                r.parentRef = i32(entryOff + 8);
                int count = i32(entryOff + 12);
                int mapOff = entryOff + size;
                // Cap by what the buffer can actually hold: a corrupt count
                // must not throw out of the whole parse (the caller's fallback
                // is apktool, and we would rather degrade one entry).
                int maxCount = Math.max(0, (d.length - mapOff) / 12);
                if (count < 0 || count > maxCount) count = maxCount;
                r.mapNames = new int[count];
                r.mapTypes = new int[count];
                r.mapData  = new int[count];
                for (int i = 0; i < count; ++i) {
                    int m = mapOff + i * 12;
                    r.mapNames[i] = i32(m);
                    r.mapTypes[i] = u8(m + 4 + 3);   // Res_value.dataType at +3
                    r.mapData[i]  = i32(m + 4 + 4);  // Res_value.data     at +4
                }
            } else {
                r.complex = false;
                int vOff = entryOff + size;  // Res_value follows the Full struct
                if (vOff + 8 <= d.length) {
                    r.dataType = u8(vOff + 3);
                    r.data = i32(vOff + 4);
                }
            }
        }

        // aapt2 --collapse-resource-names keys EVERY collapsed entry with the
        // one pool string "(name removed)". A name is a map key throughout
        // ResourceIndex (and the text form of every reference that formatRef
        // renders), so each such id gets apktool's own synthetic spelling,
        // APKTOOL_RENAMED_0x<id>, HERE -- before the rows, the public map and
        // the rendered references are built -- so the three agree. (An ingest
        // that renamed only its key side left `@string/(name removed)` in
        // every referencing value; 2026-09-20 review.)
        if ("(name removed)".equals(r.name)) {
            r.name = String.format("APKTOOL_RENAMED_0x%08x", r.resId);
        }
        // Index-only mode keeps raw rows solely for the attr bags that
        // collectAttrDefs consumes; every other entry contributes only its
        // publicMap line below.
        if (!indexOnly || (r.complex && "attr".equals(r.type))) {
            raw.add(r);
        }
        // public.xml is config-independent: first (type,name) per id wins.
        publicMap.putIfAbsent(r.resId, r.type + "/" + r.name);
    }

    /** An <attr> whose bag carries enum or flag symbols: the int values a
     *  style may assign to it, and the source-level name of each. */
    private static final class AttrDef {
        boolean flags;
        int[] vals;
        String[] names;
    }
    private final Map<Integer, AttrDef> attrDefs = new LinkedHashMap<>();

    /** Second pass: format every value now that the full id->name table
     *  (and therefore reference resolution) is available. */
    private void finish() {
        pkgNames.putIfAbsent(0x01, "android");
        collectAttrDefs();
        for (RawRow r : raw) {
            if (r.complex) {
                int n = r.mapNames.length;
                List<MapItem> items = new ArrayList<>(n);
                for (int i = 0; i < n; ++i) {
                    items.add(new MapItem(
                        r.mapNames[i], mapItemName(r.mapNames[i], r.pkgId, r.type),
                        r.mapTypes[i], r.mapData[i],
                        formatBagItemValue(r.mapNames[i], r.mapTypes[i],
                                           r.mapData[i], r.pkgId)));
                }
                String parent = r.parentRef != 0
                    ? formatRef(r.parentRef, r.pkgId, false) : "";
                rows.add(new Row(r.resId, r.pkgId, r.pkg, r.type, r.name, r.config,
                                 true, r.parentRef, parent,
                                 Collections.unmodifiableList(items),
                                 TYPE_NULL, 0, "", null));
            } else {
                Span[] spans = (r.dataType == TYPE_STRING
                                && r.data >= 0 && r.data < globalStyles.length)
                    ? globalStyles[r.data] : null;
                rows.add(new Row(r.resId, r.pkgId, r.pkg, r.type, r.name, r.config,
                                 false, 0, "", Collections.emptyList(),
                                 r.dataType, r.data,
                                 formatValue(r.dataType, r.data, r.pkgId), spans));
            }
        }
    }

    private String keyName(String[] keyNames, int key) {
        return (key >= 0 && key < keyNames.length && keyNames[key] != null)
            ? keyNames[key] : "";
    }

    // ---- enum / flag symbols ----------------------------------------
    //
    // An <attr> is stored as a bag: an internal ATTR_TYPE slot holding the
    // format bitmask (ATTR_TYPE_ENUM = 1<<16, ATTR_TYPE_FLAGS = 1<<17 --
    // ResourceTypes.h ResTable_map), followed by one slot per symbol whose
    // "name" is a generated @id resource and whose value is the int that
    // symbol stands for. A style assigning that attr stores the INT, so
    // rendering the source-level name back ("end", not "3") requires the
    // attr's own bag. We can do that for every attr THIS table defines;
    // framework attrs (package 0x01) would need the framework's attr bags,
    // which the injected id->name map does not carry -- those keep their
    // int, which is exactly the value Android's own TypedArray would hand
    // the app, so nothing is lost at runtime.
    private static final int ATTR_TYPE_ENUM  = 1 << 16;
    private static final int ATTR_TYPE_FLAGS = 1 << 17;

    private void collectAttrDefs() {
        for (RawRow r : raw) {
            if (!r.complex || !"attr".equals(r.type)) continue;
            if (attrDefs.containsKey(r.resId)) continue;   // first config wins
            int format = 0;
            int nsym = 0;
            for (int i = 0; i < r.mapNames.length; ++i) {
                if (r.mapNames[i] == ATTR_TYPE) format = r.mapData[i];
                else if (!isInternalId(r.mapNames[i])) nsym++;
            }
            boolean isEnum = (format & ATTR_TYPE_ENUM) != 0;
            boolean isFlags = (format & ATTR_TYPE_FLAGS) != 0;
            if (nsym == 0 || !(isEnum || isFlags)) continue;
            AttrDef a = new AttrDef();
            a.flags = isFlags;
            a.vals = new int[nsym];
            a.names = new String[nsym];
            int j = 0;
            for (int i = 0; i < r.mapNames.length && j < nsym; ++i) {
                if (isInternalId(r.mapNames[i])) continue;
                String n = lookupName(r.mapNames[i]);
                if (n == null) n = "";
                int slash = n.indexOf('/');          // "id/always" -> "always"
                a.names[j] = slash >= 0 ? n.substring(slash + 1) : n;
                a.vals[j] = r.mapData[i];
                j++;
            }
            // Rendering an int as its symbol is only safe when the symbol
            // spells exactly one value, because consumers turn the text back
            // into an int by NAME. Under resource-name obfuscation every
            // symbol of an attr is "0_resource_name_obfuscated" (Play Store
            // 53.7: ConstraintLayout's barrierDirection, 6 symbols, one name),
            // and the name lookup returned whichever value it met last: an
            // "end" Barrier (6) came back as "top" (2), so every OneGoogle
            // account-menu label was laid out on top of its icon. Android's
            // TypedArray hands the app the compiled int and never consults the
            // names, so keeping the int for such an attr is the faithful answer.
            if (!macapkSymbolsUnique(a.names)) continue;
            attrDefs.put(r.resId, a);
        }
    }

    /** True when no two symbols share a (non-empty) name. */
    private static boolean macapkSymbolsUnique(String[] names) {
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (String n : names) {
            if (n == null || n.isEmpty() || !seen.add(n)) return false;
        }
        return true;
    }

    /** Res_INTERNALID: a "resource id" with a zero type byte is an internal
     *  slot number (ATTR_TYPE/MIN/MAX/L10N, Res_MAKEARRAY), not a reference. */
    private static boolean isInternalId(int id) {
        return (id & 0xffff0000) != 0 && (id & 0x00ff0000) == 0;
    }

    /** Format a bag item's value, rendering an enum/flag int as its symbol
     *  name when the named attr is one we have the symbols for. */
    private String formatBagItemValue(int nameRef, int dataType, int data, int fromPkgId) {
        if (dataType == TYPE_INT_DEC || dataType == TYPE_INT_HEX) {
            AttrDef a = attrDefs.get(nameRef);
            if (a != null) {
                String sym = a.flags ? flagsToNames(a, data) : enumToName(a, data);
                if (sym != null) return sym;
            }
        }
        return formatValue(dataType, data, fromPkgId);
    }

    /** The symbol for an enum value: the LAST declared symbol with that
     *  value, which is apktool's ResEnumAttr.decodeValue (its loop keeps
     *  overwriting the match and never breaks). The framework declares
     *  fill_parent then match_parent for -1, so "match_parent" is what the
     *  decoded text tree says and what every string-matching consumer above
     *  ResourceIndex was trained on (v1.0.1590; "first wins" produced
     *  fill_parent, a value-identical spelling that still diffed). */
    private static String enumToName(AttrDef a, int data) {
        String last = null;
        for (int i = 0; i < a.vals.length; ++i) {
            if (a.vals[i] == data) last = a.names[i];
        }
        return last;
    }

    /** apktool 3.0.2's ResFlags.getSymbols(int), read from its bytecode
     *  (brut.androlib.res.table.value.ResFlags): symbols sorted by bit count
     *  DESCENDING then value ASCENDING; a symbol is selected when the value
     *  contains it and the symbols selected so far do not already cover it;
     *  then any selected symbol whose bits the OTHER selected symbols cover
     *  is dropped as redundant. 0x800013 renders "start|center"
     *  (start=0x800003, center=0x11) and scrollIndicators 3 renders
     *  "top|bottom" -- the decoded tree's spellings, which the string-
     *  matching consumers above ResourceIndex were trained on. A value with
     *  no symbol (0 with no zero-symbol, or no containable symbol) yields
     *  null and the caller keeps the int. */
    private static String flagsToNames(AttrDef a, int data) {
        if (data == 0) {
            for (int i = 0; i < a.vals.length; ++i) {
                if (a.vals[i] == 0) return a.names[i];
            }
            return null;
        }
        Integer[] order = new Integer[a.vals.length];
        for (int i = 0; i < order.length; ++i) order[i] = i;
        Arrays.sort(order, (x, y) -> {
            int bx = Integer.bitCount(a.vals[x]), by = Integer.bitCount(a.vals[y]);
            if (bx != by) return by - bx;
            return Integer.compare(a.vals[x], a.vals[y]);
        });
        java.util.List<Integer> sel = new java.util.ArrayList<>();
        int covered = 0;
        for (int idx : order) {
            int v = a.vals[idx];
            if (v == 0 || (data & v) != v) continue;
            if ((covered & v) == v) continue;
            sel.add(idx);
            covered |= v;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sel.size(); ++i) {
            int others = 0;
            for (int j = 0; j < sel.size(); ++j) if (j != i) others |= a.vals[sel.get(j)];
            int v = a.vals[sel.get(i)];
            if ((v & ~others) == 0) continue;           // redundant: covered by the rest
            if (sb.length() > 0) sb.append('|');
            sb.append(a.names[sel.get(i)]);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /** The source-level spelling of an int assigned to attr `attrResId`
     *  (an enum symbol, an "a|b" flag set), or null when the attr is not one
     *  this reader (or the framework reader it was seeded from) knows the
     *  symbols of. Used by BinaryXmlParser's text-form rendering so a compiled
     *  layout's `android:gravity="0x11"` reads "center" the way the decoded
     *  tree spelled it. */
    public String renderAttrInt(int attrResId, int dataType, int data) {
        if (dataType != TYPE_INT_DEC && dataType != TYPE_INT_HEX) return null;
        AttrDef a = attrDefs.get(attrResId);
        if (a == null) return null;
        return a.flags ? flagsToNames(a, data) : enumToName(a, data);
    }

    /** Does this reader know the symbol table of attr `attrResId`? */
    public boolean hasAttrSymbols(int attrResId) { return attrDefs.containsKey(attrResId); }

    // ---- value formatting -------------------------------------------

    /** ResTable_map pseudo-ids (ResourceTypes.h, Res_MAKEINTERNAL): the
     *  bag "name" slot is an attr id EXCEPT for these, which encode an
     *  array item or a plurals quantity. */
    private static final int ATTR_TYPE  = 0x01000000;
    private static final int ATTR_MIN   = 0x01000001;
    private static final int ATTR_MAX   = 0x01000002;
    private static final int ATTR_L10N  = 0x01000003;
    private static final int ATTR_OTHER = 0x01000004;
    private static final int ATTR_ZERO  = 0x01000005;
    private static final int ATTR_ONE   = 0x01000006;
    private static final int ATTR_TWO   = 0x01000007;
    private static final int ATTR_FEW   = 0x01000008;
    private static final int ATTR_MANY  = 0x01000009;

    /** Name for a bag item's `nameRef`.
     *
     *  The internal (Res_INTERNALID) id space is REUSED per bag type, so the
     *  meaning of 0x01000001 depends on what kind of bag it sits in:
     *    * <attr>    -- ATTR_TYPE/MIN/MAX/L10N carry the format bitmask and
     *                   bounds; the remaining items are @id references naming
     *                   the enum/flag symbols.
     *    * <plurals> -- ATTR_OTHER..ATTR_MANY are the quantity keywords.
     *    * <array>   -- aapt2 numbers items `ATTR_MIN + i`
     *                   (ResEntryWriter.cpp Visit(const Array*)), i.e. the
     *                   SAME ids ATTR_MIN/ATTR_MAX use in an attr bag. They
     *                   are positional indexes with no name.
     *    * <style>   -- items name real attr resources; nothing internal.
     *  Interpreting ATTR_MIN as "^min" everywhere silently swallowed the
     *  first two items of every 2-element <array>, which is what caught this. */
    private String mapItemName(int nameRef, int fromPkgId, String bagType) {
        if (isInternalId(nameRef)) {
            if ("attr".equals(bagType)) {
                switch (nameRef) {
                    case ATTR_TYPE: return "^type";
                    case ATTR_MIN:  return "^min";
                    case ATTR_MAX:  return "^max";
                    case ATTR_L10N: return "^l10n";
                    default: return "";
                }
            }
            if ("plurals".equals(bagType)) {
                switch (nameRef) {
                    case ATTR_OTHER: return "other";
                    case ATTR_ZERO:  return "zero";
                    case ATTR_ONE:   return "one";
                    case ATTR_TWO:   return "two";
                    case ATTR_FEW:   return "few";
                    case ATTR_MANY:  return "many";
                    default: return "";
                }
            }
            return "";      // array item index, or an internal slot we can't name
        }
        String n = lookupName(nameRef);
        if (n == null) return "";
        int tp = (nameRef >>> 24) & 0xff;
        String prefix = (tp != fromPkgId) ? (pkgName(tp) + ":") : "";
        return prefix + n;
    }

    private String pkgName(int pkgId) {
        String s = pkgNames.get(pkgId);
        return s != null ? s : ("pkg" + pkgId);
    }

    private String lookupName(int resId) {
        String n = publicMap.get(resId);
        if (n != null) return n;
        n = frameworkMap.get(resId);
        return n;
    }

    /** apktool's ResReference.toXmlTextValue: "@" (or "?" for an attribute
     *  ref) + a "<pkg>:" prefix when the target lives in a DIFFERENT package
     *  than the referrer + "<type>/" (omitted for `?attr/...`) + name.
     *  An id we cannot resolve keeps its raw hex (apktool degrades it to
     *  "@null"; we keep the information -- the verify harness normalizes). */
    private String formatRef(int resId, int fromPkgId, boolean asAttr) {
        if (resId == 0) return "@null";
        String tn = lookupName(resId);
        char sigil = asAttr ? '?' : '@';
        if (tn == null) return sigil + String.format("0x%08x", resId);
        int tp = (resId >>> 24) & 0xff;
        String prefix = (tp != fromPkgId) ? (pkgName(tp) + ":") : "";
        if (asAttr && tn.startsWith("attr/")) tn = tn.substring(5);
        return sigil + prefix + tn;
    }

    /** Format a simple Res_value the way a text decoder would. */
    private String formatValue(int dataType, int data, int fromPkgId) {
        switch (dataType) {
            case TYPE_STRING:
                return (data >= 0 && data < globalStrings.length
                        && globalStrings[data] != null) ? globalStrings[data] : "";
            case TYPE_INT_BOOLEAN:
                return (data != 0) ? "true" : "false";
            case TYPE_INT_DEC:
                return Integer.toString(data);
            case TYPE_INT_HEX:
                return String.format("0x%x", data);
            case TYPE_FLOAT:
                return formatFloat(Float.intBitsToFloat(data));
            case TYPE_REFERENCE:
            case TYPE_DYNAMIC_REFERENCE:
                return formatRef(data, fromPkgId, false);
            case TYPE_ATTRIBUTE:
            case TYPE_DYNAMIC_ATTRIBUTE:
                return formatRef(data, fromPkgId, true);
            // ARGB4/RGB4 pack each 4-bit channel into the HIGH nibble of its
            // 8-bit slot, so the text form takes one nibble per channel --
            // masking the low 16/12 bits would print the wrong colour.
            case TYPE_COLOR_ARGB8:
                return String.format("#%08x", data);
            case TYPE_COLOR_RGB8:
                return String.format("#%06x", data & 0xffffff);
            case TYPE_COLOR_ARGB4:
                return String.format("#%x%x%x%x", (data >>> 28) & 0xf,
                    (data >>> 20) & 0xf, (data >>> 12) & 0xf, (data >>> 4) & 0xf);
            case TYPE_COLOR_RGB4:
                return String.format("#%x%x%x", (data >>> 20) & 0xf,
                    (data >>> 12) & 0xf, (data >>> 4) & 0xf);
            case TYPE_DIMENSION:
                return formatFloat(complexToFloat(data))
                     + DIM_UNITS[data & COMPLEX_UNIT_MASK];
            case TYPE_FRACTION:
                return formatFloat(complexToFloat(data) * 100)
                     + ((data & COMPLEX_UNIT_MASK) == 0 ? "%" : "%p");
            case TYPE_NULL:
                // DATA_NULL_EMPTY (1) means "explicitly empty", distinct from
                // "undefined" (0). Both are values, not the absence of one.
                return data == 1 ? "@empty" : "@null";
            default:
                return Integer.toString(data);
        }
    }

    // TypedValue's complex (dimension/fraction) encoding. Verified against
    // AOSP ResourceTypes.h + android.util.TypedValue.complexToFloat: the
    // mantissa is NOT shifted down before scaling -- the radix multiplier
    // carries the 1/2^8 mantissa shift itself, so
    //   value = (data & (MANTISSA_MASK << 8)) * RADIX_MULTS[radix].
    // Masking in place is also what keeps a negative mantissa negative (the
    // sign bit survives at bit 31); shifting first and using a 1/2^8-scaled
    // multiplier is a 256x error, which is exactly what it looked like.
    private static final int COMPLEX_UNIT_MASK      = 0xf;
    private static final int COMPLEX_RADIX_SHIFT    = 4;
    private static final int COMPLEX_RADIX_MASK     = 0x3;
    private static final int COMPLEX_MANTISSA_SHIFT = 8;
    private static final int COMPLEX_MANTISSA_MASK  = 0xffffff;
    private static final float MANTISSA_MULT = 1.0f / (1 << COMPLEX_MANTISSA_SHIFT);
    private static final float[] RADIX_MULTS = {
        MANTISSA_MULT,
        1.0f / (1 << 7)  * MANTISSA_MULT,
        1.0f / (1 << 15) * MANTISSA_MULT,
        1.0f / (1 << 23) * MANTISSA_MULT,
    };
    // Index by (data & COMPLEX_UNIT_MASK); the tail entries are the
    // out-of-range "??" apktool prints rather than throwing.
    private static final String[] DIM_UNITS = {
        "px", "dp", "sp", "pt", "in", "mm",
        "??", "??", "??", "??", "??", "??", "??", "??", "??", "??",
    };

    private static float complexToFloat(int data) {
        return (data & (COMPLEX_MANTISSA_MASK << COMPLEX_MANTISSA_SHIFT))
             * RADIX_MULTS[(data >> COMPLEX_RADIX_SHIFT) & COMPLEX_RADIX_MASK];
    }

    /** Float text form: one decimal place for an exact integer (so "16.0dp"
     *  reads as a float), otherwise Java's minimal representation. Matches
     *  apktool's ResPrimitive.floatToString, which is what ResourceIndex's
     *  existing dimension/fraction parsers already consume. */
    private static String formatFloat(float f) {
        if (f == (long) f) return String.format(Locale.ROOT, "%.1f", f);
        return Float.toString(f);
    }

    /** Re-apply a styled string's spans as markup, the way apktool's
     *  ResStringEncoder does: a span tag is either a bare element name
     *  ("b") or "name;key=value;key2=value2". Spans are stored
     *  outermost-first and firstChar/lastChar are INCLUSIVE character
     *  indexes into the plain text. */
    public static String applySpans(String text, Span[] spans) {
        if (spans == null || spans.length == 0 || text == null) return text;
        // Build per-character open/close lists, then emit. Closing happens in
        // reverse open order at each position so nesting stays well-formed.
        int n = text.length();
        StringBuilder[] open = new StringBuilder[n + 1];
        StringBuilder[] close = new StringBuilder[n + 1];
        for (Span s : spans) {
            if (s == null) continue;
            int a = Math.max(0, s.firstChar);
            int b = Math.min(n - 1, s.lastChar);
            if (a > b) continue;
            String[] parts = s.tag.split(";");
            String el = parts[0];
            StringBuilder o = new StringBuilder("<").append(el);
            for (int i = 1; i < parts.length; ++i) {
                int eq = parts[i].indexOf('=');
                if (eq <= 0) continue;
                o.append(' ').append(parts[i], 0, eq).append("=\"")
                 .append(parts[i].substring(eq + 1)).append('"');
            }
            o.append('>');
            if (open[a] == null) open[a] = new StringBuilder();
            open[a].append(o);
            if (close[b + 1] == null) close[b + 1] = new StringBuilder();
            close[b + 1].insert(0, "</" + el + ">");
        }
        StringBuilder out = new StringBuilder(n + 32);
        for (int i = 0; i <= n; ++i) {
            if (close[i] != null) out.append(close[i]);
            if (open[i] != null) out.append(open[i]);
            if (i < n) out.append(text.charAt(i));
        }
        return out.toString();
    }

    // ---- string pool (mirrors BinaryXmlParser; kept self-contained so the
    //      working manifest parser is untouched) -----------------------
    private String[] readStringPool(int off) {
        int headerSize   = u16(off + 2);
        int stringCount  = i32(off + 8);
        int flags        = i32(off + 16);
        int stringsStart = i32(off + 20);
        boolean utf8 = (flags & 0x100) != 0;
        String[] pool = new String[Math.max(0, stringCount)];
        int idxBase = off + headerSize;
        int strBase = off + stringsStart;
        for (int i = 0; i < stringCount; ++i) {
            int rel = i32(idxBase + i * 4);
            int sOff = strBase + rel;
            try {
                pool[i] = utf8 ? readUtf8(sOff) : readUtf16(sOff);
            } catch (Throwable t) {
                pool[i] = "";
            }
        }
        return pool;
    }

    /** The pool's STYLE section: `styleCount` u32 offsets (immediately after
     *  the string offsets), each pointing into `stylesStart` at a run of
     *  ResStringPool_span {u32 name; u32 firstChar; u32 lastChar} terminated
     *  by name == END (0xffffffff). Style i decorates string i. */
    private Span[][] readStylePool(int off, String[] strings) {
        try {
            int headerSize  = u16(off + 2);
            int stringCount = i32(off + 8);
            int styleCount  = i32(off + 12);
            int stylesStart = i32(off + 24);
            if (styleCount <= 0 || stylesStart <= 0) return new Span[0][];
            Span[][] out = new Span[Math.max(styleCount, stringCount)][];
            int idxBase = off + headerSize + stringCount * 4;
            int base = off + stylesStart;
            for (int i = 0; i < styleCount; ++i) {
                int rel = i32(idxBase + i * 4);
                int p = base + rel;
                List<Span> spans = new ArrayList<>(2);
                while (p + 12 <= d.length) {
                    long nameRef = u32(p);
                    if (nameRef == NO_ENTRY) break;             // END
                    int first = i32(p + 4), last = i32(p + 8);
                    int ni = (int) nameRef;
                    String tag = (ni >= 0 && ni < strings.length && strings[ni] != null)
                        ? strings[ni] : "";
                    if (!tag.isEmpty() && first >= 0 && last >= first) {
                        spans.add(new Span(tag, first, last));
                    }
                    p += 12;
                }
                if (!spans.isEmpty()) out[i] = spans.toArray(new Span[0]);
            }
            return out;
        } catch (Throwable t) {
            // Styles are decoration; a malformed style section must never
            // fail the table walk (the strings themselves are already read).
            return new Span[0][];
        }
    }

    private String readUtf16(int off) {
        int len = u16(off);
        off += 2;
        if ((len & 0x8000) != 0) {
            len = ((len & 0x7fff) << 16) | u16(off);
            off += 2;
        }
        char[] c = new char[len];
        for (int i = 0; i < len; ++i) c[i] = (char) u16(off + i * 2);
        return new String(c);
    }

    private String readUtf8(int off) {
        int charLen = u8(off);                    // UTF-16 length (unused)
        off += 1;
        if ((charLen & 0x80) != 0) off += 1;      // 2-byte extension
        int byteLen = u8(off);
        off += 1;
        if ((byteLen & 0x80) != 0) {
            byteLen = ((byteLen & 0x7f) << 8) | u8(off);
            off += 1;
        }
        // aapt's "UTF-8" string pool is CESU-8: a supplementary character is
        // stored as a UTF-16 surrogate pair, each half as its own 3-byte
        // sequence -- which Java's strict UTF-8 decoder rejects (yielding two
        // U+FFFD). Decode manually so a 3-byte sequence in the surrogate range
        // is kept as a UTF-16 char (adjacent high+low halves then form the
        // supplementary char), while still accepting a standard 4-byte
        // sequence. BMP chars decode identically to a plain UTF-8 decode.
        StringBuilder sb = new StringBuilder(byteLen);
        int end = off + byteLen;
        int i = off;
        while (i < end) {
            int b0 = u8(i);
            if (b0 < 0x80) {
                sb.append((char) b0); i += 1;
            } else if ((b0 & 0xe0) == 0xc0 && i + 1 < end) {
                sb.append((char) (((b0 & 0x1f) << 6) | (u8(i + 1) & 0x3f)));
                i += 2;
            } else if ((b0 & 0xf0) == 0xe0 && i + 2 < end) {
                sb.append((char) (((b0 & 0x0f) << 12)
                    | ((u8(i + 1) & 0x3f) << 6) | (u8(i + 2) & 0x3f)));
                i += 3;
            } else if ((b0 & 0xf8) == 0xf0 && i + 3 < end) {
                sb.appendCodePoint(((b0 & 0x07) << 18) | ((u8(i + 1) & 0x3f) << 12)
                    | ((u8(i + 2) & 0x3f) << 6) | (u8(i + 3) & 0x3f));
                i += 4;
            } else {
                i += 1;   // skip a malformed lead byte rather than abort
            }
        }
        return sb.toString();
    }

    // ================================================================
    // ResTable_config -> apktool values-directory qualifier suffix
    // ================================================================
    //
    // Byte-compatible with apktool v3.0.2's
    // brut.androlib.res.decoder.BinaryResourceParser.parseConfig (which
    // fields exist at which config `size`, and the packed 3-letter locale
    // decode) + brut.androlib.res.table.ResConfig.computeQualifiers (which
    // literal each value renders to, and in what ORDER). Matching apktool
    // rather than aapt is deliberate: ResourceIndex already scores apktool's
    // directory names, so an ArscReader-fed index must bucket rows into the
    // exact same values-<quals> names apktool would have written.
    //
    // The field layout itself is AOSP ResourceTypes.h's ResTable_config; see
    // docs/arsc-format-contract_2026-07-24.md.

    private static final int MNC_ZERO = 0xffff;

    private static final int MASK_KEYSHIDDEN = 0x03;
    private static final int MASK_NAVHIDDEN  = 0x0c;
    private static final int MASK_GRAMMATICAL_GENDER = 0x03;
    private static final int MASK_SCREENSIZE = 0x0f;
    private static final int MASK_SCREENLONG = 0x30;
    private static final int MASK_LAYOUTDIR  = 0xc0;
    private static final int MASK_UI_MODE_TYPE  = 0x0f;
    private static final int MASK_UI_MODE_NIGHT = 0x30;
    private static final int MASK_SCREENROUND      = 0x03;
    private static final int MASK_COLOR_MODE_WIDECG = 0x03;
    private static final int MASK_COLOR_MODE_HDR    = 0x0c;

    /** True when this row's config is the DEFAULT (no qualifiers at all). */
    private String configQualifiers(int o, int size) {
        if (size < 8) return "";
        StringBuilder sb = new StringBuilder();

        int mcc = (size >= 8) ? u16(o + 4) : 0;
        int mnc = (size >= 8) ? u16(o + 6) : 0;

        String language = "", region = "";
        if (size >= 12) {
            language = unpackLanguageOrRegion(u8(o + 8), u8(o + 9), 'a');
            region   = unpackLanguageOrRegion(u8(o + 10), u8(o + 11), '0');
        }
        int orientation = 0, touchscreen = 0;
        if (size >= 14) { orientation = u8(o + 12); touchscreen = u8(o + 13); }
        int density = (size >= 16) ? u16(o + 14) : 0;
        int keyboard = 0, navigation = 0, inputFlags = 0, grammatical = 0;
        if (size >= 20) {
            keyboard = u8(o + 16); navigation = u8(o + 17);
            inputFlags = u8(o + 18); grammatical = u8(o + 19);
        }
        int screenWidth = 0, screenHeight = 0, sdkVersion = 0;
        if (size >= 28) {
            screenWidth = u16(o + 20); screenHeight = u16(o + 22);
            sdkVersion = u16(o + 24);   // minorVersion at +26, unused by apktool
        }
        int screenLayout = 0, uiMode = 0, smallestScreenWidthDp = 0;
        if (size >= 32) {
            screenLayout = u8(o + 28); uiMode = u8(o + 29);
            smallestScreenWidthDp = u16(o + 30);
        }
        int screenWidthDp = 0, screenHeightDp = 0;
        if (size >= 36) { screenWidthDp = u16(o + 32); screenHeightDp = u16(o + 34); }
        String localeScript = "", localeVariant = "";
        if (size >= 48) {
            localeScript = readAscii(o + 36, 4);
            localeVariant = readAscii(o + 40, 8);
        }
        int screenLayout2 = 0, colorMode = 0;
        if (size >= 52) { screenLayout2 = u8(o + 48); colorMode = u8(o + 49); }

        // Anything past the last field apktool knows is "non-standard"; it
        // only matters when NON-ZERO, in which case apktool appends a
        // hash-derived "-unkXXXXXXXX" so two otherwise-identical configs do
        // not collide. Mirrors readExceedingBytes (all-zero tail => null).
        int known = size >= 52 ? 52 : size >= 48 ? 48 : size >= 36 ? 36
                  : size >= 32 ? 32 : size >= 28 ? 28 : size >= 20 ? 20
                  : size >= 16 ? 16 : size >= 14 ? 14 : size >= 12 ? 12 : 8;
        byte[] unknown = null;
        if (size > known) {
            int n = Math.min(size - known, Math.max(0, d.length - (o + known)));
            for (int i = 0; i < n; ++i) {
                if (d[o + known + i] != 0) {
                    unknown = Arrays.copyOfRange(d, o + known, o + known + n);
                    break;
                }
            }
        }

        if (mcc != 0) sb.append("-mcc").append(String.format(Locale.ROOT, "%03d", mcc));
        if (mnc != 0) sb.append("-mnc").append(String.format(
            Locale.ROOT, "%02d", mnc == MNC_ZERO ? 0 : mnc));
        if (!language.isEmpty()) {
            if (localeScript.isEmpty() && (region.isEmpty() || region.length() == 2)
                    && localeVariant.isEmpty()) {
                sb.append('-').append(language);                 // legacy form
                if (!region.isEmpty()) sb.append("-r").append(region);
            } else {
                sb.append("-b+").append(language);               // BCP 47 form
                if (!localeScript.isEmpty()) sb.append('+').append(localeScript);
                if (!region.isEmpty()) sb.append('+').append(region);
                if (!localeVariant.isEmpty()) sb.append('+').append(localeVariant);
            }
        }
        switch (grammatical & MASK_GRAMMATICAL_GENDER) {
            case 0: break;
            case 1: sb.append("-neuter"); break;
            case 2: sb.append("-feminine"); break;
            case 3: sb.append("-masculine"); break;
            default: break;
        }
        switch (screenLayout & MASK_LAYOUTDIR) {
            case 0x00: break;
            case 0x40: sb.append("-ldltr"); break;
            case 0x80: sb.append("-ldrtl"); break;
            default: sb.append("-layoutDir=").append(screenLayout & MASK_LAYOUTDIR); break;
        }
        if (smallestScreenWidthDp != 0) sb.append("-sw").append(smallestScreenWidthDp).append("dp");
        if (screenWidthDp != 0) sb.append("-w").append(screenWidthDp).append("dp");
        if (screenHeightDp != 0) sb.append("-h").append(screenHeightDp).append("dp");
        switch (screenLayout & MASK_SCREENSIZE) {
            case 0x00: break;
            case 0x01: sb.append("-small"); break;
            case 0x02: sb.append("-normal"); break;
            case 0x03: sb.append("-large"); break;
            case 0x04: sb.append("-xlarge"); break;
            default: sb.append("-screenSize=").append(screenLayout & MASK_SCREENSIZE); break;
        }
        switch (screenLayout & MASK_SCREENLONG) {
            case 0x00: break;
            case 0x10: sb.append("-notlong"); break;
            case 0x20: sb.append("-long"); break;
            default: sb.append("-screenLong=").append(screenLayout & MASK_SCREENLONG); break;
        }
        switch (screenLayout2 & MASK_SCREENROUND) {
            case 0x00: break;
            case 0x01: sb.append("-notround"); break;
            case 0x02: sb.append("-round"); break;
            default: sb.append("-screenRound=").append(screenLayout2 & MASK_SCREENROUND); break;
        }
        switch (colorMode & MASK_COLOR_MODE_WIDECG) {
            case 0x00: break;
            case 0x01: sb.append("-nowidecg"); break;
            case 0x02: sb.append("-widecg"); break;
            default: sb.append("-colorModeWideCG=").append(colorMode & MASK_COLOR_MODE_WIDECG); break;
        }
        switch (colorMode & MASK_COLOR_MODE_HDR) {
            case 0x00: break;
            case 0x04: sb.append("-lowdr"); break;
            case 0x08: sb.append("-highdr"); break;
            default: sb.append("-colorModeHdr=").append(colorMode & MASK_COLOR_MODE_HDR); break;
        }
        switch (orientation) {
            case 0: break;
            case 1: sb.append("-port"); break;
            case 2: sb.append("-land"); break;
            case 3: sb.append("-square"); break;
            default: sb.append("-orientation=").append(orientation); break;
        }
        switch (uiMode & MASK_UI_MODE_TYPE) {
            case 0x00: case 0x01: break;                 // ANY / NORMAL
            case 0x02: sb.append("-desk"); break;
            case 0x03: sb.append("-car"); break;
            case 0x04: sb.append("-television"); break;
            case 0x05: sb.append("-appliance"); break;
            case 0x06: sb.append("-watch"); break;
            case 0x07: sb.append("-vrheadset"); break;
            case 0x0b: sb.append("-godzillaui"); break;  // MIUI
            case 0x0c: sb.append("-smallui"); break;     // MIUI
            case 0x0d: sb.append("-mediumui"); break;    // MIUI
            case 0x0e: sb.append("-largeui"); break;     // MIUI
            case 0x0f: sb.append("-hugeui"); break;      // MIUI
            default: sb.append("-uiModeType=").append(uiMode & MASK_UI_MODE_TYPE); break;
        }
        switch (uiMode & MASK_UI_MODE_NIGHT) {
            case 0x00: break;
            case 0x10: sb.append("-notnight"); break;
            case 0x20: sb.append("-night"); break;
            default: sb.append("-uiModeNight=").append(uiMode & MASK_UI_MODE_NIGHT); break;
        }
        switch (density) {
            case 0: break;                               // DEFAULT
            case 120: sb.append("-ldpi"); break;
            case 160: sb.append("-mdpi"); break;
            case 213: sb.append("-tvdpi"); break;
            case 240: sb.append("-hdpi"); break;
            case 320: sb.append("-xhdpi"); break;
            case 480: sb.append("-xxhdpi"); break;
            case 640: sb.append("-xxxhdpi"); break;
            case 0xfffe: sb.append("-anydpi"); break;
            case 0xffff: sb.append("-nodpi"); break;
            default: sb.append('-').append(density).append("dpi"); break;
        }
        switch (touchscreen) {
            case 0: break;
            case 1: sb.append("-notouch"); break;
            case 2: sb.append("-stylus"); break;
            case 3: sb.append("-finger"); break;
            default: sb.append("-touchscreen=").append(touchscreen); break;
        }
        switch (inputFlags & MASK_KEYSHIDDEN) {
            case 0: break;
            case 1: sb.append("-keysexposed"); break;
            case 2: sb.append("-keyshidden"); break;
            case 3: sb.append("-keyssoft"); break;
            default: break;
        }
        switch (keyboard) {
            case 0: break;
            case 1: sb.append("-nokeys"); break;
            case 2: sb.append("-qwerty"); break;
            case 3: sb.append("-12key"); break;
            default: sb.append("-keyboard=").append(keyboard); break;
        }
        switch (inputFlags & MASK_NAVHIDDEN) {
            case 0x00: break;
            case 0x04: sb.append("-navexposed"); break;
            case 0x08: sb.append("-navhidden"); break;
            default: sb.append("-navHidden=").append(inputFlags & MASK_NAVHIDDEN); break;
        }
        switch (navigation) {
            case 0: break;
            case 1: sb.append("-nonav"); break;
            case 2: sb.append("-dpad"); break;
            case 3: sb.append("-trackball"); break;
            case 4: sb.append("-wheel"); break;
            default: sb.append("-navigation=").append(navigation); break;
        }
        if (screenWidth != 0 && screenHeight != 0) {
            sb.append('-').append(screenWidth).append('x').append(screenHeight);
        }
        if (sdkVersion != 0) sb.append("-v").append(sdkVersion);
        if (unknown != null) {
            sb.append("-unk").append(String.format("%08X", Arrays.hashCode(unknown)));
        }
        return sb.toString();
    }

    /** ResTable_config language/country: 2 ASCII chars, or -- when the high
     *  bit of the first byte is set -- a packed 3-letter code (5 bits per
     *  letter, offset from `base`). Mirrors apktool's
     *  BinaryResourceParser.unpackLanguageOrRegion. */
    private static String unpackLanguageOrRegion(int b0, int b1, char base) {
        if (b0 == 0) return "";                       // "any"
        if ((b0 & 0x80) != 0) {
            char c0 = (char) (base + (b1 & 0x1f));
            char c1 = (char) (base + ((b1 & 0xe0) >>> 5) + ((b0 & 0x03) << 3));
            char c2 = (char) (base + ((b0 & 0x7c) >>> 2));
            return new String(new char[] { c0, c1, c2 });
        }
        return new String(new char[] { (char) b0, (char) b1 });
    }

    /** Fixed-width NUL-terminated ASCII field (localeScript/localeVariant). */
    private String readAscii(int off, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; ++i) {
            if (off + i >= d.length) break;
            int c = u8(off + i);
            if (c == 0) break;
            sb.append((char) c);
        }
        return sb.toString();
    }
}
