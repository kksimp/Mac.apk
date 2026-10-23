// Binary Android XML (AXML) parser -- the "better apktool" for the
// manifest path.
//
// WHY: apktool's DECODED AndroidManifest.xml is plain text, which
// LOSES the typed attribute values the binary format carries: a
// TYPE_INT_BOOLEAN android:value and a TYPE_STRING android:value
// "true" both decode to the same text, so meta-data Bundle typing
// could never be Android-correct from the text (the 2026-06-11
// boolean-typing attempt broke Crossy exactly this way and had to be
// reverted). Parsing the BINARY manifest gives us aapt's real types,
// the real package/versionName without filename heuristics, and one
// less external-tool dependency on the boot path (apktool still
// decodes res/ for ResourceIndex).
//
// FORMAT (verified against AOSP libs/androidfw/include/androidfw/
// ResourceTypes.h, fetched 2026-06-11):
//   ResChunk_header { u16 type; u16 headerSize; u32 size; }
//   RES_STRING_POOL_TYPE=0x0001, RES_XML_TYPE=0x0003,
//   RES_XML_START_NAMESPACE=0x0100, END_NAMESPACE=0x0101,
//   START_ELEMENT=0x0102, END_ELEMENT=0x0103, CDATA=0x0104,
//   RESOURCE_MAP=0x0180.
//   ResStringPool_header { hdr; u32 stringCount, styleCount, flags
//     (UTF8_FLAG=1<<8), stringsStart, stylesStart; }
//   ResXMLTree_node { hdr; u32 lineNumber; StringPool_ref comment; }
//   ResXMLTree_attrExt { ref ns, name; u16 attributeStart,
//     attributeSize, attributeCount, idIndex, classIndex, styleIndex; }
//     (attributeStart is relative to the attrExt struct itself, per
//      ResXMLParser::getAttributePointer)
//   ResXMLTree_attribute { ref ns, name, rawValue;
//     Res_value { u16 size; u8 res0; u8 dataType; u32 data; } }
//   Res_value dataType: TYPE_NULL=0x00 REFERENCE=0x01 ATTRIBUTE=0x02
//     STRING=0x03 FLOAT=0x04 DIMENSION=0x05 FRACTION=0x06
//     INT_DEC=0x10 INT_HEX=0x11 INT_BOOLEAN=0x12 COLORS=0x1c..0x1f.
//
// OUTPUT: an org.w3c.dom.Document equivalent to the text decode (so
// Manifest.java's existing DOM walk works unchanged), with one
// addition: every attribute whose binary value was TYPED gets a
// sibling annotation attribute in the MACAPK_TYPE_NS namespace with
// the same local name and a value of "boolean" / "int" / "float" /
// "reference" / "attribute" / "string". Consumers that care about
// Android-true Bundle typing (MacApkPackageManager.putTypedMeta)
// read the annotation; everything else ignores it.
//
// Obfuscated manifests may carry EMPTY attribute-name strings and
// rely on the RESOURCE MAP (string index -> android attr resource
// id); the WELL_KNOWN_ATTRS table (ids verified against AOSP
// core/res public-final.xml) recovers the names we rely on.
package com.macapk.loader;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

public final class BinaryXmlParser {
    private static final String TAG = "Mac.apk.AXML";

    /** Namespace for the value-type annotations. */
    public static final String MACAPK_TYPE_NS = "http://macapk/axml-type";
    /** An attribute's NAME in the app's resource table, when the document's own
     *  name string differs from it (see setAttr), and the attr's resource id.
     *  Parser-internal: DomXmlResourceParser hides both from attribute
     *  iteration and consults them only for by-name and by-id lookups. */
    public static final String MACAPK_ALIAS_NS = "http://macapk/axml-alias";
    public static final String MACAPK_ATTRID_NS = "http://macapk/axml-attrid";
    private static final String ANDROID_NS =
        "http://schemas.android.com/apk/res/android";

    // Chunk types.
    private static final int RES_STRING_POOL_TYPE        = 0x0001;
    private static final int RES_XML_TYPE                = 0x0003;
    private static final int RES_XML_START_NAMESPACE     = 0x0100;
    private static final int RES_XML_END_NAMESPACE       = 0x0101;
    private static final int RES_XML_START_ELEMENT       = 0x0102;
    private static final int RES_XML_END_ELEMENT         = 0x0103;
    private static final int RES_XML_CDATA               = 0x0104;
    private static final int RES_XML_RESOURCE_MAP        = 0x0180;

    // Res_value dataType.
    private static final int TYPE_NULL        = 0x00;
    private static final int TYPE_REFERENCE   = 0x01;
    private static final int TYPE_ATTRIBUTE   = 0x02;
    private static final int TYPE_STRING      = 0x03;
    private static final int TYPE_FLOAT       = 0x04;
    private static final int TYPE_DIMENSION   = 0x05;
    private static final int TYPE_FRACTION    = 0x06;
    private static final int TYPE_INT_DEC     = 0x10;
    private static final int TYPE_INT_HEX     = 0x11;
    private static final int TYPE_INT_BOOLEAN = 0x12;
    private static final int TYPE_COLOR_FIRST = 0x1c;
    private static final int TYPE_COLOR_LAST  = 0x1f;

    /** android attr resource id -> local name, for manifests whose
     *  attribute-name strings are empty (resource-map only). IDs
     *  verified against AOSP core/res public-final.xml. */
    private static final Map<Integer, String> WELL_KNOWN_ATTRS = new HashMap<>();
    static {
        WELL_KNOWN_ATTRS.put(0x01010000, "theme");
        WELL_KNOWN_ATTRS.put(0x01010001, "label");
        WELL_KNOWN_ATTRS.put(0x01010003, "name");
        WELL_KNOWN_ATTRS.put(0x0101000e, "enabled");
        WELL_KNOWN_ATTRS.put(0x01010010, "exported");
        WELL_KNOWN_ATTRS.put(0x01010018, "authorities");
        WELL_KNOWN_ATTRS.put(0x0101001a, "initOrder");
        WELL_KNOWN_ATTRS.put(0x0101001b, "grantUriPermissions");
        WELL_KNOWN_ATTRS.put(0x01010024, "value");
        WELL_KNOWN_ATTRS.put(0x01010025, "resource");
        WELL_KNOWN_ATTRS.put(0x0101020c, "minSdkVersion");
        WELL_KNOWN_ATTRS.put(0x0101021b, "versionCode");
        WELL_KNOWN_ATTRS.put(0x0101021c, "versionName");
        WELL_KNOWN_ATTRS.put(0x01010270, "targetSdkVersion");
    }

    /** The names a compiled XML's typed values refer to, so the DOM can be
     *  rendered in the DECODED-TEXT spelling every consumer above
     *  ResourceIndex was verified on (v1.0.1590, Phase 5 of docs/disk-
     *  footprint-and-space-efficiency_2026-09-19.md: resources are read from
     *  the installed apks, never from an apktool tree). Without one, the raw
     *  forms ("@0x7f0a0000", "0x11") are emitted, which is what the manifest
     *  path has always consumed. */
    public interface Names {
        /** "type/name" for an id of the app or the platform ("android:" is
         *  prepended by the renderer for package 0x01), or null when unknown. */
        String typeName(int resId);
        /** The enum symbol / "a|b" flag spelling of an int assigned to attr
         *  `attrResId`, or null when the attr's symbols are unknown. */
        String enumText(int attrResId, int dataType, int data);
    }

    private final byte[] d;
    private final Names names;
    private String[] pool = new String[0];
    private int[] resourceMap = new int[0];

    private BinaryXmlParser(byte[] data, Names names) { this.d = data; this.names = names; }

    /** Parse an AXML blob into a DOM document. Throws on anything
     *  that doesn't look like binary XML -- callers fall back to the
     *  apktool text decode. */
    public static Document parse(byte[] axml) throws Exception {
        return new BinaryXmlParser(axml, null).run();
    }

    /** Parse with the app+platform names, rendering references, attribute
     *  refs, enums/flags and colours in apktool's text spelling. */
    public static Document parse(byte[] axml, Names names) throws Exception {
        return new BinaryXmlParser(axml, names).run();
    }

    /** True when `bytes` start with the RES_XML_TYPE chunk header, i.e. the
     *  entry is compiled XML rather than text (a raw/ file is stored as-is). */
    public static boolean isBinaryXml(byte[] bytes) {
        return bytes != null && bytes.length >= 8
            && bytes[0] == 0x03 && bytes[1] == 0x00 && bytes[2] == 0x08 && bytes[3] == 0x00;
    }

    /** Serialise a parsed document back to the XML TEXT the decoded tree held
     *  (`<?xml version="1.0" encoding="utf-8"?>`, one root with its xmlns
     *  declarations, attributes in document order, escaped). Every existing
     *  consumer parses text XML, so a compiled entry rendered this way needs
     *  no consumer change at all. The MACAPK_TYPE_NS annotations are dropped
     *  (the text tree never had them). */
    public static String toText(Document doc) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        Element root = doc.getDocumentElement();
        if (root != null) writeElement(sb, root);
        return sb.toString();
    }

    private static void writeElement(StringBuilder sb, Element el) {
        sb.append('<').append(el.getTagName());
        org.w3c.dom.NamedNodeMap attrs = el.getAttributes();
        boolean aliasNs = false, attrIdNs = false;
        for (int i = 0; i < attrs.getLength(); i++) {
            org.w3c.dom.Node a = attrs.item(i);
            String qn = a.getNodeName();
            if (MACAPK_TYPE_NS.equals(a.getNamespaceURI())) continue;
            // The alias / attr-id records travel with the text too, so they need
            // their prefixes declared (on the element itself), or the text is
            // not well-formed and every reader of it fails.
            if (!aliasNs && MACAPK_ALIAS_NS.equals(a.getNamespaceURI())) {
                sb.append(" xmlns:macapkAlias=\"").append(MACAPK_ALIAS_NS).append('"');
                aliasNs = true;
            } else if (!attrIdNs && MACAPK_ATTRID_NS.equals(a.getNamespaceURI())) {
                sb.append(" xmlns:macapkAttrId=\"").append(MACAPK_ATTRID_NS).append('"');
                attrIdNs = true;
            }
            sb.append(' ').append(qn).append("=\"");
            escape(sb, a.getNodeValue());
            sb.append('"');
        }
        org.w3c.dom.NodeList kids = el.getChildNodes();
        boolean any = false;
        for (int i = 0; i < kids.getLength(); i++) {
            org.w3c.dom.Node n = kids.item(i);
            if (n instanceof Element) {
                if (!any) { sb.append(">\n"); any = true; }
                writeElement(sb, (Element) n);
            } else if (n instanceof org.w3c.dom.Text) {
                String t = n.getNodeValue();
                if (t == null || t.trim().isEmpty()) continue;
                if (!any) { sb.append('>'); any = true; }
                escape(sb, t);
            }
        }
        if (any) sb.append("</").append(el.getTagName()).append(">\n");
        else sb.append(" />\n");
    }

    private static void escape(StringBuilder sb, String v) {
        if (v == null) return;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '&':  sb.append("&amp;");  break;
                case '<':  sb.append("&lt;");   break;
                case '>':  sb.append("&gt;");   break;
                case '"':  sb.append("&quot;"); break;
                default:   sb.append(c);
            }
        }
    }

    private int u8(int off)  { return d[off] & 0xff; }
    private int u16(int off) { return (d[off] & 0xff) | ((d[off + 1] & 0xff) << 8); }
    private int i32(int off) {
        return (d[off] & 0xff) | ((d[off + 1] & 0xff) << 8)
             | ((d[off + 2] & 0xff) << 16) | ((d[off + 3] & 0xff) << 24);
    }

    private Document run() throws Exception {
        if (d.length < 8 || u16(0) != RES_XML_TYPE) {
            throw new IllegalArgumentException(
                "not binary XML (type=0x" + Integer.toHexString(u16(0)) + ")");
        }
        int total = i32(4);
        if (total > d.length) total = d.length;

        Document doc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder().newDocument();
        Deque<Element> stack = new ArrayDeque<>();
        Map<String, String> nsPrefix = new HashMap<>();   // uri -> prefix
        java.util.List<String[]> nsDecls = new java.util.ArrayList<>();  // {prefix, uri} in order

        int off = u16(2);   // skip the outer RES_XML header
        while (off + 8 <= total) {
            int type = u16(off);
            int headerSize = u16(off + 2);
            int size = i32(off + 4);
            if (size < 8 || off + size > total) break;
            switch (type) {
                case RES_STRING_POOL_TYPE:
                    parseStringPool(off);
                    break;
                case RES_XML_RESOURCE_MAP: {
                    int n = (size - headerSize) / 4;
                    resourceMap = new int[n];
                    for (int i = 0; i < n; ++i)
                        resourceMap[i] = i32(off + headerSize + i * 4);
                    break;
                }
                case RES_XML_START_NAMESPACE: {
                    // node header (16) + prefix ref + uri ref
                    int prefix = i32(off + 16);
                    int uri    = i32(off + 20);
                    if (uri >= 0 && prefix >= 0) {
                        nsPrefix.put(poolStr(uri), poolStr(prefix));
                        nsDecls.add(new String[] { poolStr(prefix), poolStr(uri) });
                    }
                    break;
                }
                case RES_XML_END_NAMESPACE:
                    break;
                case RES_XML_START_ELEMENT: {
                    int ext = off + headerSize;      // attrExt start
                    int nameIdx = i32(ext + 4);
                    String tag = poolStr(nameIdx);
                    Element el = doc.createElement(
                        tag.isEmpty() ? "unnamed" : tag);
                    // The namespaces declared before this element go on it as
                    // xmlns attributes, the way the text form carries them
                    // (aapt emits every START_NAMESPACE right before the root).
                    if (!nsDecls.isEmpty()) {
                        for (String[] decl : nsDecls) {
                            if (decl[0].isEmpty() || decl[1].isEmpty()) continue;
                            try {
                                el.setAttributeNS("http://www.w3.org/2000/xmlns/",
                                                  "xmlns:" + decl[0], decl[1]);
                            } catch (Throwable ignored) {}
                        }
                        nsDecls.clear();
                    }
                    int attributeStart = u16(ext + 8);
                    int attributeSize  = u16(ext + 10);
                    int attributeCount = u16(ext + 12);
                    int attrs = ext + attributeStart;
                    for (int i = 0; i < attributeCount; ++i) {
                        int a = attrs + i * attributeSize;
                        if (a + 20 > total) break;
                        setAttr(el, a, nsPrefix);
                    }
                    if (stack.isEmpty()) doc.appendChild(el);
                    else stack.peek().appendChild(el);
                    stack.push(el);
                    break;
                }
                case RES_XML_END_ELEMENT:
                    if (!stack.isEmpty()) stack.pop();
                    break;
                case RES_XML_CDATA:
                default:
                    break;
            }
            off += size;
        }
        if (doc.getDocumentElement() == null) {
            throw new IllegalArgumentException("binary XML had no root element");
        }
        return doc;
    }

    /** Render a TYPE_DIMENSION / TYPE_FRACTION Res_value.data as the source
     *  literal it was compiled from. Layout per ResourceTypes.h
     *  (android11-release): COMPLEX_MANTISSA in bits 8..31 (signed),
     *  COMPLEX_RADIX in bits 4..5 selecting a fixed-point scale of
     *  {1, 1/128, 1/32768, 1/8388608} (23p0/16p7/8p15/0p23), COMPLEX_UNIT in
     *  bits 0..3. Fraction mantissas store the plain fraction (1.0 == 100%),
     *  and the "%"-suffixed text form carries the PERCENT number
     *  (TypedValue.macapkParseNumericLiteral parses "50%" with scale 1/100),
     *  so fractions are emitted *100. The emitted forms ("32.0dip",
     *  "50.0%") round-trip through macapkParseNumericLiteral back to the
     *  identical Res_value. */
    private static String complexLiteral(int data, boolean fraction) {
        int mantissa = data & 0xffffff00;              // keep sign, clear low byte
        int radix = (data >> 4) & 0x3;
        float f;
        switch (radix) {
            case 0:  f = (mantissa >> 8); break;       // 23p0: integral
            case 1:  f = mantissa * (1.0f / (1 << 15)); break;  // 16p7
            case 2:  f = mantissa * (1.0f / (1 << 23)); break;  // 8p15
            default: f = mantissa * (1.0f / (1L << 31)); break; // 0p23
        }
        int unit = data & 0xf;
        if (fraction) {
            return (f * 100f) + (unit == 1 ? "%p" : "%");
        }
        String suffix;
        switch (unit) {
            case 0:  suffix = "px";  break;
            case 1:  suffix = "dip"; break;
            case 2:  suffix = "sp";  break;
            case 3:  suffix = "pt";  break;
            case 4:  suffix = "in";  break;
            case 5:  suffix = "mm";  break;
            default: suffix = "px";  break;
        }
        return f + suffix;
    }

    /** apktool's spelling of a reference: "@type/name" (an app id),
     *  "@android:type/name" (a platform id), "@null" for id 0; an attribute
     *  ref drops the "attr/" ("?colorPrimary", "?android:textColorPrimary").
     *  Without names, or for an id no table knows, the raw hex form. */
    private String refText(int resId, boolean attr) {
        if (resId == 0) return attr ? "?0x00000000" : "@null";
        String tn = (names != null) ? names.typeName(resId) : null;
        if (tn == null) return String.format(attr ? "?0x%08x" : "@0x%08x", resId);
        String prefix = ((resId >>> 24) == 0x01) ? "android:" : "";
        if (attr && tn.startsWith("attr/")) tn = tn.substring(5);
        return (attr ? "?" : "@") + prefix + tn;
    }

    /** The source form of a colour by its compiled flavour: RGB8 "#rrggbb",
     *  ARGB8 "#aarrggbb", ARGB4/RGB4 one nibble per channel (aapt packs each
     *  4-bit channel into the HIGH nibble of its byte). */
    private static String colorText(int dataType, int data) {
        switch (dataType) {
            case 0x1c: return String.format("#%08x", data);                     // ARGB8
            case 0x1d: return String.format("#%06x", data & 0xffffff);          // RGB8
            case 0x1e: return String.format("#%x%x%x%x", (data >>> 28) & 0xf,   // ARGB4
                (data >>> 20) & 0xf, (data >>> 12) & 0xf, (data >>> 4) & 0xf);
            case 0x1f: return String.format("#%x%x%x", (data >>> 20) & 0xf,     // RGB4
                (data >>> 12) & 0xf, (data >>> 4) & 0xf);
            default:   return String.format("#%08x", data);
        }
    }

    private void setAttr(Element el, int a, Map<String, String> nsPrefix) {
        int nsIdx   = i32(a);
        int nameIdx = i32(a + 4);
        int rawIdx  = i32(a + 8);
        int dataType = u8(a + 15);
        int data     = i32(a + 16);

        String name = poolStr(nameIdx);
        final boolean namedInDocument = !name.isEmpty();
        if (name.isEmpty() && nameIdx >= 0 && nameIdx < resourceMap.length) {
            // A shrunk APK (Meta's: WhatsApp, Instagram) strips every attribute
            // name string from its compiled XML and keeps only the resource map,
            // so a layout's android:id / layout_width arrive nameless. Recover
            // the name from the attr id: the platform table for 0x0101xxxx, the
            // app's own table for its attrs.
            int rid = resourceMap[nameIdx];
            String known = WELL_KNOWN_ATTRS.get(rid);
            if (known == null) known = FrameworkAttrIds.name(rid);
            if (known == null && names != null && rid != 0) {
                String tn = names.typeName(rid);
                if (tn != null && tn.startsWith("attr/")) known = tn.substring(5);
            }
            if (known != null) name = known;
        }
        if (name.isEmpty()) return;   // unusable attribute

        // The attr this value is assigned to (the resource map pairs each
        // attribute-name string with the attr's resource id) -- what the
        // enum/flag symbol tables are keyed by.
        int attrId = (nameIdx >= 0 && nameIdx < resourceMap.length) ? resourceMap[nameIdx] : 0;
        String value;
        String typeTag;
        switch (dataType) {
            case TYPE_STRING:
                value = rawIdx >= 0 ? poolStr(rawIdx) : poolStr(data);
                typeTag = "string";
                break;
            case TYPE_INT_BOOLEAN:
                value = (data != 0) ? "true" : "false";
                typeTag = "boolean";
                break;
            case TYPE_INT_DEC:
            case TYPE_INT_HEX: {
                String sym = (names != null && attrId != 0)
                    ? names.enumText(attrId, dataType, data) : null;
                if (sym != null) value = sym;
                else value = (dataType == TYPE_INT_DEC)
                    ? Integer.toString(data) : "0x" + Integer.toHexString(data);
                typeTag = "int";
                break;
            }
            case TYPE_FLOAT:
                value = Float.toString(Float.intBitsToFloat(data));
                typeTag = "float";
                break;
            // Complex-encoded values. These used to fall to `default:` and
            // render the RAW BITS as a decimal int ("8193" for 32dp), which
            // poisoned every dimension-reading consumer of a compiled DOM --
            // a framework <vector android:width="32dp"> parsed as an
            // 8193 px intrinsic. Decode per ResourceTypes.h (android11-
            // release): mantissa in bits 8..31, radix in bits 4..5 scaling
            // by {1, 1/128, 1/32768, 1/8388608}, unit in bits 0..3. The
            // rendered literal round-trips through every text parser in the
            // tree (TypedValue.macapkParseNumericLiteral, VectorDrawable's
            // suffix-stripping parseFloat).
            case TYPE_DIMENSION: {
                value = complexLiteral(data, false);
                // apktool spells the dp unit "dp" in a decoded file; the
                // manifest path has always seen "dip" and both parse.
                if (names != null && value.endsWith("dip"))
                    value = value.substring(0, value.length() - 3) + "dp";
                typeTag = "dimension";
                break;
            }
            case TYPE_FRACTION: {
                value = complexLiteral(data, true);
                typeTag = "fraction";
                break;
            }
            case TYPE_REFERENCE:
                value = refText(data, false);
                typeTag = "reference";
                break;
            case TYPE_ATTRIBUTE:
                value = refText(data, true);
                typeTag = "attribute";
                break;
            case TYPE_NULL:
                // Res_value DATA_NULL_EMPTY (1) is the compiled "@empty";
                // DATA_NULL_UNDEFINED is an absent value.
                value = (names != null) ? (data == 1 ? "@empty" : "@null") : "";
                typeTag = "string";
                break;
            default:
                if (dataType >= TYPE_COLOR_FIRST && dataType <= TYPE_COLOR_LAST) {
                    value = (names != null) ? colorText(dataType, data)
                                            : String.format("#%08x", data);
                    typeTag = "int";
                } else {
                    value = Integer.toString(data);
                    typeTag = "int";
                }
                break;
        }

        String nsUri = nsIdx >= 0 ? poolStr(nsIdx) : "";
        try {
            if (!nsUri.isEmpty()) {
                String prefix = nsPrefix.get(nsUri);
                if (prefix == null || prefix.isEmpty()) {
                    prefix = ANDROID_NS.equals(nsUri) ? "android" : "ns";
                }
                el.setAttributeNS(nsUri, prefix + ":" + name, value);
            } else {
                el.setAttribute(name, value);
            }
            // Type annotation for typed (non-string) values, so the
            // Bundle-typing consumer can be Android-correct without
            // guessing from the text form.
            if (!"string".equals(typeTag)) {
                el.setAttributeNS(MACAPK_TYPE_NS, "macapkType:" + name, typeTag);
            }
            // A shrunk APK (WhatsApp) keeps attribute NAMES in some compiled XML
            // (res/xml MotionScenes) while its resource table has the attr's name
            // removed (APKTOOL_RENAMED_0x...). A device matches styleable slots
            // by the resource-map ID, so it never notices; our TypedArray matches
            // by the TABLE name and getAttributeNameResource by the document
            // name, so `constraintSetStart` was invisible to both -- MotionLayout
            // then had no start state, skipped its measure, and the Settings
            // header (back arrow, profile) laid out at 0x0. Record the table
            // spelling and the id beside the attribute.
            if (namedInDocument && attrId != 0 && (attrId >>> 24) == 0x7f && names != null) {
                String tn = names.typeName(attrId);
                if (tn != null && tn.startsWith("attr/")) {
                    String tableName = tn.substring(5);
                    if (!tableName.isEmpty() && !tableName.equals(name)) {
                        // Only where the names disagree, so an APK with intact
                        // names parses exactly as before.
                        el.setAttributeNS(MACAPK_ALIAS_NS, "macapkAlias:" + tableName, value);
                        el.setAttributeNS(MACAPK_ATTRID_NS, "macapkAttrId:" + name, Integer.toString(attrId));
                    }
                }
            }
        } catch (Throwable t) {
            // DOM name validation can reject odd attr names from
            // exotic manifests; skip the attribute rather than fail
            // the whole parse.
        }
    }

    // ---- string pool ------------------------------------------------
    private void parseStringPool(int off) {
        int headerSize   = u16(off + 2);
        int stringCount  = i32(off + 8);
        int flags        = i32(off + 16);
        int stringsStart = i32(off + 20);
        boolean utf8 = (flags & 0x100) != 0;
        pool = new String[stringCount];
        int idxBase = off + headerSize;
        int strBase = off + stringsStart;
        for (int i = 0; i < stringCount; ++i) {
            int sOff = strBase + i32(idxBase + i * 4);
            try {
                pool[i] = utf8 ? readUtf8(sOff) : readUtf16(sOff);
            } catch (Throwable t) {
                pool[i] = "";
            }
        }
    }

    private String readUtf16(int off) {
        int len = u16(off);
        off += 2;
        if ((len & 0x8000) != 0) {              // length extension
            len = ((len & 0x7fff) << 16) | u16(off);
            off += 2;
        }
        char[] c = new char[len];
        for (int i = 0; i < len; ++i) c[i] = (char) u16(off + i * 2);
        return new String(c);
    }

    private String readUtf8(int off) {
        int charLen = u8(off);                   // UTF-16 length (unused)
        off += 1;
        if ((charLen & 0x80) != 0) off += 1;     // extension byte
        int byteLen = u8(off);
        off += 1;
        if ((byteLen & 0x80) != 0) {
            byteLen = ((byteLen & 0x7f) << 8) | u8(off);
            off += 1;
        }
        return new String(d, off, byteLen, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String poolStr(int idx) {
        if (idx < 0 || idx >= pool.length) return "";
        String s = pool[idx];
        return s != null ? s : "";
    }
}
