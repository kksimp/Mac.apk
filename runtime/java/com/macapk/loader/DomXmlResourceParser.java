// com.macapk.loader.DomXmlResourceParser -- concrete
// android.content.res.XmlResourceParser implementation. The parser
// loads the entire XML file into a DOM tree at construction time
// (apk-bundled preference XMLs are tiny -- a few KB at most) and
// then emits XmlPullParser-style events as the caller walks the
// tree via next() / nextTag().
//
// Why DOM rather than a real streaming parser:
// - JDK ships javax.xml.parsers.DocumentBuilder already, so we
//   don't pull in another XML library.
// - The XmlPullParser API surface is what callers expect; we just
//   adapt it on top of an in-memory tree.
// - androidx.preference / androidx.fragment use this for layouts /
//   menus / preferences that are tens of KB at most. DOM is fast
//   enough; no streaming budget required.
//
// Event ordering: classic depth-first pre-order over the document.
// We emit START_DOCUMENT once, then for each Element: START_TAG,
// (recursive walk of children: TEXT for non-blank text nodes,
// nested START_TAG/END_TAG for child elements), END_TAG. END_DOCUMENT
// at the end.
//
// Loaded via reflection by org.xmlpull.v1.XmlPullParserFactory and
// by android.content.res.Resources.getXml (also reflection-driven,
// to keep android-framework.jar from depending on
// com.macapk.loader).
package com.macapk.loader;

import android.util.AttributeSet;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

public final class DomXmlResourceParser
    implements android.content.res.XmlResourceParser {

    /** Flat sequence of XmlPullParser events emitted by walking the
     *  DOM. Each event carries the originating Node so attribute
     *  lookups for START_TAG events can be served lazily. */
    private static final class Event {
        final int type;
        final Node node;
        final int depth;
        final String text;
        Event(int type, Node node, int depth, String text) {
            this.type = type; this.node = node; this.depth = depth; this.text = text;
        }
    }

    private final List<Event> events = new ArrayList<>();
    private int cursor = -1;
    private boolean closed = false;
    private String inputEncoding;

    public DomXmlResourceParser() {}
    public DomXmlResourceParser(InputStream in) throws XmlPullParserException {
        setInput(in, null);
    }
    /** Wrap an ALREADY-PARSED DOM. The compiled-binary-XML path needs this: a framework resource
     *  served straight out of framework-res.apk is aapt-compiled AXML, which the text
     *  DocumentBuilder in setInput() cannot read (it fails "Content is not allowed in prolog").
     *  BinaryXmlParser produces the Document instead, and this hands it to the same event walk,
     *  so every consumer sees an identical XmlResourceParser either way. */
    public DomXmlResourceParser(Document doc) throws XmlPullParserException {
        if (doc == null) throw new XmlPullParserException("null Document");
        try {
            buildEvents(doc);
        } catch (Throwable t) {
            throw new XmlPullParserException("DOM walk failed: " + t);
        }
    }

    static final boolean MACAPK_DOM_BUILDER_REUSE =
        !"0".equals(System.getenv("MACAPK_DOM_BUILDER_REUSE"));

    private static DocumentBuilder newBuilder() throws javax.xml.parsers.ParserConfigurationException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        // Match AOSP: namespace-aware parsing is the default for
        // resource XML so `xmlns:android` prefixes resolve to the
        // android namespace URI, which lets app code look up attrs
        // by namespace.
        dbf.setNamespaceAware(true);
        // Defensive: disable external entity resolution (apps may
        // ship XMLs that reference DTDs that aren't on the host;
        // we want a hard-fail, not a network fetch).
        try { dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); }
        catch (Throwable ignore) {}
        return dbf.newDocumentBuilder();
    }

    private static final ThreadLocal<DocumentBuilder> REUSED = ThreadLocal.withInitial(() -> {
        try { return newBuilder(); }
        catch (javax.xml.parsers.ParserConfigurationException e) { throw new IllegalStateException(e); }
    });

    @Override
    public void setInput(InputStream in, String encoding) throws XmlPullParserException {
        if (in == null) throw new XmlPullParserException("null InputStream");
        this.inputEncoding = encoding;
        try {
            // One configured builder per thread, reset before each parse:
            // building the factory + builder per call was 22% of a TextView
            // constructor's appearance read (DocumentBuilderImpl.<init>,
            // XIncludeAwareParserConfiguration.<init>), measured by JFR on
            // TextCtorCheck cost, 2026-09-26. DocumentBuilder is not
            // thread-safe, hence ThreadLocal; reset() returns it to the
            // factory configuration.
            DocumentBuilder db = MACAPK_DOM_BUILDER_REUSE ? REUSED.get() : newBuilder();
            if (MACAPK_DOM_BUILDER_REUSE) db.reset();
            Document doc = db.parse(in);
            buildEvents(doc);
        } catch (Throwable t) {
            throw new XmlPullParserException("DOM parse failed: " + t);
        }
    }

    @Override
    public void setInput(Reader in) throws XmlPullParserException {
        // Slurp via UTF-8 round-trip. Adapter for callers that hand us
        // a Reader; AOSP's impl converts to bytes for the underlying
        // pull parser too.
        if (in == null) throw new XmlPullParserException("null Reader");
        try {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = in.read(buf)) > 0) sb.append(buf, 0, n);
            setInput(new ByteArrayInputStream(sb.toString().getBytes("UTF-8")), "UTF-8");
        } catch (IOException e) {
            throw new XmlPullParserException("Reader read failed: " + e);
        }
    }

    private void buildEvents(Document doc) {
        // XmlPullParser.setInput(): "resets the parser state" (a pull parser is
        // reused across documents); a no-op for the single-use resource parser.
        events.clear();
        cursor = -1;
        mergedText = null;
        events.add(new Event(START_DOCUMENT, doc, 0, null));
        Node root = doc.getDocumentElement();
        if (root != null) walk(root, 1);
        events.add(new Event(END_DOCUMENT, doc, 0, null));
    }

    private void walk(Node node, int depth) {
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            events.add(new Event(START_TAG, node, depth, null));
            Node child = node.getFirstChild();
            while (child != null) {
                switch (child.getNodeType()) {
                    case Node.ELEMENT_NODE:
                        walk(child, depth + 1);
                        break;
                    case Node.TEXT_NODE:
                    case Node.CDATA_SECTION_NODE: {
                        // AOSP collapses whitespace-only text between
                        // tags into IGNORABLE_WHITESPACE; non-blank
                        // text becomes TEXT. Approximate that here.
                        String t = child.getNodeValue();
                        if (t != null && !t.trim().isEmpty()) {
                            events.add(new Event(TEXT, child, depth + 1, t));
                        } else if (t != null && !t.isEmpty()) {
                            events.add(new Event(IGNORABLE_WHITESPACE, child, depth + 1, t));
                        }
                        break;
                    }
                    case Node.COMMENT_NODE:
                        events.add(new Event(COMMENT, child, depth + 1, child.getNodeValue()));
                        break;
                    default:
                        // PIs, doctypes, etc. -- skip silently. Real
                        // resource XML never has these.
                }
                child = child.getNextSibling();
            }
            events.add(new Event(END_TAG, node, depth, null));
        }
    }

    // ---- two event contracts: a RESOURCE parser vs a PULL parser ----
    //
    // This one class serves two AOSP classes with DIFFERENT first events:
    //  * resource XML (XmlResourceParser): AOSP XmlBlock.Parser.next() starts
    //    with `if (!mStarted) { mStarted = true; return START_DOCUMENT; }`, so
    //    the FIRST next() reports START_DOCUMENT. That is this class's default.
    //  * XmlPullParserFactory.newPullParser() / Xml.newPullParser(): AOSP hands
    //    out libcore's KXmlParser, whose event type is START_DOCUMENT after
    //    setInput and whose first next() is the first START_TAG; next() never
    //    reports COMMENT, PROCESSING_INSTRUCTION or DOCDECL (nextToken() does),
    //    and whitespace-only text is TEXT with isWhitespace() true (XmlPullParser
    //    javadoc, next(): "START_TAG, TEXT, END_TAG, END_DOCUMENT").
    // The factory used to hand out the RESOURCE contract, so a standard pull
    // loop saw START_DOCUMENT twice. MEASURED on NewPipe (lanes19
    // media-playback, 2026-09-29): ExoPlayer's DashManifestParser does
    //     int eventType = xpp.next();
    //     if (eventType != START_TAG || !"MPD".equals(xpp.getName())) throw
    //         ParserException.createForMalformedManifest("inputStream does not
    //         contain a valid media presentation description", null);
    // and every DASH stream failed with exactly that. macapkPullParserSemantics()
    // (called by XmlPullParserFactory) selects the KXmlParser contract.
    // MACAPK_XMLPULL_KXML_EVENTS=0: the factory keeps the resource contract, as before.
    private boolean pullSemantics;
    /** Pull mode only: the coalesced text of the current TEXT event when
     *  next() merged several DOM text/CDATA nodes (across skipped comments and
     *  PIs). Null otherwise; cleared whenever the cursor moves. */
    private String mergedText;

    /** Select the KXmlParser event contract (see above). Idempotent. */
    public void macapkPullParserSemantics() { pullSemantics = true; }

    private int rawEventType() {
        if (cursor < 0) return START_DOCUMENT;
        if (cursor >= events.size()) return END_DOCUMENT;
        return events.get(cursor).type;
    }

    // ---- XmlPullParser cursor ----
    @Override public int getEventType() {
        int t = rawEventType();
        return (pullSemantics && t == IGNORABLE_WHITESPACE) ? TEXT : t;
    }
    @Override public int next() {
        if (pullSemantics) {
            // Skip the DOM's own START_DOCUMENT event (the parser is already AT
            // start-document after setInput) and every token next() does not
            // report; report whitespace-only text as TEXT.
            int t;
            mergedText = null;
            do {
                cursor = Math.min(cursor + 1, events.size());
                t = rawEventType();
            } while (t == START_DOCUMENT || t == COMMENT
                    || t == PROCESSING_INSTRUCTION || t == DOCDECL);
            if (t == TEXT || t == IGNORABLE_WHITESPACE) macapkCoalesceText();
            if (System.getenv("MACAPK_TRACE_XMLPULL") != null) {
                NativeLog.i("Mac.apk.XmlPull", "next(pull) -> " + t + " name=" + getName());
            }
            return t == IGNORABLE_WHITESPACE ? TEXT : t;
        }
        cursor = Math.min(cursor + 1, events.size());
        int t = getEventType();
        // Diagnostic (env-gated): dump the pull-event stream so we can
        // see exactly what a consumer's parse loop saw.
        if (System.getenv("MACAPK_TRACE_XMLPULL") != null) {
            NativeLog.i("Mac.apk.XmlPull", "next -> " + t
                + " name=" + getName() + " depth=" + getDepth()
                + " p@" + Integer.toHexString(System.identityHashCode(this)));
        }
        return t;
    }
    @Override public int nextToken() {
        if (pullSemantics) {
            // nextToken() reports every token (COMMENT included) but, like
            // next(), never a second START_DOCUMENT.
            int t;
            mergedText = null;
            do {
                cursor = Math.min(cursor + 1, events.size());
                t = rawEventType();
            } while (t == START_DOCUMENT);
            // Same as getEventType(): inside an element KXmlParser reports
            // whitespace as TEXT (IGNORABLE_WHITESPACE only at depth 0, where
            // this DOM has none), so nextToken() and getEventType() agree.
            return t == IGNORABLE_WHITESPACE ? TEXT : t;
        }
        return next();
    }

    /** XmlPullParser.next() javadoc: "element content will be coalesced and only
     *  one TEXT event must be returned for whole element content (comments and
     *  processing instructions will be ignored and entity references must be
     *  expanded or exception must be thrown if entity reference can not be
     *  expanded)". libcore KXmlParser.next() does it with
     *  `while (minType > ENTITY_REF || (minType >= TEXT && peekType() >= TEXT))`:
     *  text, CDATA, entity refs, comments and PIs keep folding into one TEXT.
     *  Called with the cursor ON the first text event: absorb every following
     *  text/CDATA event, stepping over COMMENT/PI/DOCDECL between them, and leave
     *  the cursor on the LAST absorbed text event. A trailing comment is not
     *  absorbed (next() skips it anyway). */
    private void macapkCoalesceText() {
        StringBuilder sb = null;
        int last = cursor;
        for (int j = cursor + 1; j < events.size(); j++) {
            Event e = events.get(j);
            if (e.type == COMMENT || e.type == PROCESSING_INSTRUCTION || e.type == DOCDECL) continue;
            if (e.type != TEXT && e.type != IGNORABLE_WHITESPACE) break;
            if (sb == null) {
                String first = events.get(cursor).text;
                sb = new StringBuilder(first == null ? "" : first);
            }
            if (e.text != null) sb.append(e.text);
            last = j;
        }
        if (sb != null) { mergedText = sb.toString(); cursor = last; }
    }
    @Override public int nextTag() throws XmlPullParserException, IOException {
        int t = next();
        // Skip text/whitespace/comments between tags. AOSP semantics:
        // nextTag returns the next START_TAG or END_TAG, throwing if
        // anything else is found AND it isn't ignorable whitespace.
        while (t != START_TAG && t != END_TAG && t != END_DOCUMENT) {
            if (t == TEXT && getText() != null && !getText().trim().isEmpty()) {
                throw new XmlPullParserException("unexpected text in nextTag");
            }
            t = next();
        }
        return t;
    }
    @Override public String nextText() throws XmlPullParserException, IOException {
        if (getEventType() != START_TAG) {
            throw new XmlPullParserException("nextText not on START_TAG");
        }
        int t = next();
        if (t == TEXT) {
            String s = getText();
            next();
            return s == null ? "" : s;
        } else if (t == END_TAG) {
            return "";
        }
        throw new XmlPullParserException("nextText: unexpected event " + t);
    }
    @Override public void require(int type, String namespace, String name)
        throws XmlPullParserException, IOException {
        if (getEventType() != type
            || (namespace != null && !namespace.equals(getNamespace()))
            || (name != null      && !name.equals(getName()))) {
            throw new XmlPullParserException("require(" + type + ") failed at "
                + getPositionDescription());
        }
    }

    @Override public int getDepth() {
        if (cursor < 0 || cursor >= events.size()) return 0;
        return events.get(cursor).depth;
    }
    @Override public int getLineNumber()   { return -1; }
    @Override public int getColumnNumber() { return -1; }
    @Override public String getPositionDescription() {
        return "event " + cursor + " of " + events.size();
    }
    @Override public boolean isWhitespace() {
        if (cursor < 0 || cursor >= events.size()) return true;
        Event e = events.get(cursor);
        if (mergedText != null) return mergedText.trim().isEmpty();
        if (e.type == IGNORABLE_WHITESPACE) return true;
        if (e.type == TEXT && e.text != null && e.text.trim().isEmpty()) return true;
        return false;
    }
    @Override public String getText() {
        if (cursor < 0 || cursor >= events.size()) return null;
        Event e = events.get(cursor);
        if (mergedText != null) return mergedText;
        if (e.text != null) return e.text;
        if (e.node != null && e.node.getNodeType() == Node.TEXT_NODE) return e.node.getNodeValue();
        return null;
    }
    @Override public char[] getTextCharacters(int[] holderForStartAndLength) {
        String s = getText();
        if (s == null) { holderForStartAndLength[0] = 0; holderForStartAndLength[1] = 0; return new char[0]; }
        char[] arr = s.toCharArray();
        holderForStartAndLength[0] = 0;
        holderForStartAndLength[1] = arr.length;
        return arr;
    }

    @Override public String getName() {
        if (cursor < 0 || cursor >= events.size()) return null;
        Event e = events.get(cursor);
        if (e.node == null) return null;
        // localName when namespace-aware; nodeName fallback for bare
        // elements without a namespace declaration.
        String ln = e.node.getLocalName();
        return ln != null ? ln : e.node.getNodeName();
    }
    @Override public String getPrefix() {
        if (cursor < 0 || cursor >= events.size()) return null;
        return events.get(cursor).node == null ? null : events.get(cursor).node.getPrefix();
    }
    @Override public String getNamespace() {
        if (cursor < 0 || cursor >= events.size()) return XmlPullParser.NO_NAMESPACE;
        Node n = events.get(cursor).node;
        if (n == null) return XmlPullParser.NO_NAMESPACE;
        String uri = n.getNamespaceURI();
        return uri == null ? XmlPullParser.NO_NAMESPACE : uri;
    }
    @Override public String getNamespace(String prefix) {
        // Walk up the current element's chain looking for a declared
        // prefix. AOSP's implementation searches the namespace stack;
        // for our DOM-backed parser the DOM itself encodes the
        // resolved namespace URI on each node.
        Node n = cursor >= 0 && cursor < events.size() ? events.get(cursor).node : null;
        while (n != null) {
            if (n.hasAttributes()) {
                NamedNodeMap attrs = n.getAttributes();
                for (int i = 0; i < attrs.getLength(); ++i) {
                    Node a = attrs.item(i);
                    String nm = a.getNodeName();
                    if (("xmlns:" + prefix).equals(nm)) return a.getNodeValue();
                    if (prefix == null && "xmlns".equals(nm))   return a.getNodeValue();
                }
            }
            n = n.getParentNode();
        }
        return null;
    }
    @Override public int getNamespaceCount(int depth) { return 0; }
    @Override public String getNamespacePrefix(int pos) { return null; }
    @Override public String getNamespaceUri(int pos)    { return null; }
    @Override public boolean isEmptyElementTag() {
        if (cursor < 0 || cursor >= events.size()) return false;
        Event e = events.get(cursor);
        return e.type == START_TAG && (e.node == null || !e.node.hasChildNodes());
    }

    // ---- attributes (XmlPullParser + AttributeSet, served from the
    //      current START_TAG event's node) ----
    private NamedNodeMap currentAttrs() {
        if (cursor < 0 || cursor >= events.size()) return null;
        Event e = events.get(cursor);
        if (e.type != START_TAG || e.node == null) return null;
        return e.node.getAttributes();
    }
    /** BinaryXmlParser's alias / attr-id records: not document attributes. */
    private static boolean macapkInternalAttr(Node n) {
        String uri = n.getNamespaceURI();
        return BinaryXmlParser.MACAPK_ALIAS_NS.equals(uri) || BinaryXmlParser.MACAPK_ATTRID_NS.equals(uri);
    }
    @Override public int getAttributeCount() {
        NamedNodeMap a = currentAttrs();
        if (a == null) return -1;
        // Filter out xmlns:* declarations -- AOSP doesn't count them
        // among attributes.
        int n = 0;
        for (int i = 0; i < a.getLength(); ++i) {
            Node it = a.item(i);
            String nm = it.getNodeName();
            if (!nm.startsWith("xmlns") && !macapkInternalAttr(it)) ++n;
        }
        return n;
    }
    @Override public String getAttributeName(int index) {
        Node a = nthAttr(index);
        if (a == null) return null;
        String ln = a.getLocalName();
        return ln != null ? ln : a.getNodeName();
    }
    @Override public String getAttributeNamespace(int index) {
        Node a = nthAttr(index);
        if (a == null) return null;
        String uri = a.getNamespaceURI();
        return uri == null ? "" : uri;
    }
    @Override public String getAttributePrefix(int index) {
        Node a = nthAttr(index);
        return a == null ? null : a.getPrefix();
    }
    @Override public String getAttributeType(int index)    { return "CDATA"; }
    @Override public boolean isAttributeDefault(int index) { return false; }
    @Override public String getAttributeValue(int index) {
        Node a = nthAttr(index);
        return a == null ? null : a.getNodeValue();
    }
    @Override public String getAttributeValue(String namespace, String name) {
        NamedNodeMap a = currentAttrs();
        if (a == null || name == null) return null;
        // Pass 1: namespace-qualified lookup (the "android" URI is
        // encoded on the attribute when the doc declares
        // xmlns:android; PreferenceInflater queries with it).
        for (int i = 0; i < a.getLength(); ++i) {
            Node n = a.item(i);
            String nm = n.getNodeName();
            if (nm.startsWith("xmlns")) continue;
            String ln  = n.getLocalName();
            String uri = n.getNamespaceURI();
            boolean nameOk = name.equals(ln) || name.equals(nm);
            boolean nsOk;
            if (namespace == null || namespace.isEmpty()) {
                nsOk = (uri == null || uri.isEmpty());
            } else {
                nsOk = namespace.equals(uri);
            }
            if (nameOk && nsOk) return n.getNodeValue();
        }
        // Pass 2 (null/empty namespace only): any-namespace local-name
        // match. Our TypedArray emulates styleable lookups BY NAME and
        // passes null meaning "this attr, whatever namespace aapt put
        // it in" -- real TypedArray matches by attr resource id, which
        // is namespace-blind at this level. Without this, app-res-auto
        // attrs (`motion:constraintSetStart` on a MotionScene
        // Transition) were INVISIBLE: the Transition parsed as
        // abstract, MotionLayout had no current transition, its
        // ConstraintSets never derived from the layout, and every
        // child solved to zero size (Calculator's transposed keypad).
        // Mirrors LayoutInflater.DomBackedAttributeSet semantics.
        if (namespace == null || namespace.isEmpty()) {
            for (int i = 0; i < a.getLength(); ++i) {
                Node n = a.item(i);
                String nm = n.getNodeName();
                if (nm.startsWith("xmlns")) continue;
                String ln = n.getLocalName();
                if (name.equals(ln) || name.equals(nm)) {
                    return n.getNodeValue();
                }
            }
        }
        // Pass 3: the attr under its resource-TABLE name (BinaryXmlParser's
        // alias for a document that spells the attribute differently).
        for (int i = 0; i < a.getLength(); ++i) {
            Node n = a.item(i);
            if (BinaryXmlParser.MACAPK_ALIAS_NS.equals(n.getNamespaceURI()) && name.equals(n.getLocalName())) {
                return n.getNodeValue();
            }
        }
        return null;
    }
    private Node nthAttr(int index) {
        NamedNodeMap a = currentAttrs();
        if (a == null) return null;
        int n = 0;
        for (int i = 0; i < a.getLength(); ++i) {
            Node it = a.item(i);
            if (it.getNodeName().startsWith("xmlns")) continue;
            if (macapkInternalAttr(it)) continue;
            if (n == index) return it;
            ++n;
        }
        return null;
    }
    /** AOSP AttributeSet.getAttributeNameResource: "Return the resource ID
     *  associated with the given attribute name. ... Returns 0 if there is no
     *  resource associated with this attribute." XmlBlock.Parser passes the
     *  compiled id straight through.
     *
     *  This returned a hardcoded 0 until v1.0.889, and that ONE gap made every
     *  androidx-inflated ColorStateList in the fleet a CONSTANT FUNCTION. The
     *  app's own bundled androidx.core.content.res.ColorStateListInflaterCompat
     *  builds each <selector> row's state spec from exactly this call: it guards
     *  the id against android.R.attr.color / alpha / lStar and otherwise stores
     *  `+/-id` as a state entry. With the id 0 every guard fails -- so even
     *  `android:color` and `android:alpha` get counted as state entries -- and
     *  `+/-0 == 0`, so the spec comes out all zeros. StateSet.trimStateSet only
     *  truncates length, it does not strip zeros, and ColorStateList's matcher
     *  treats a leading 0 as the padding terminator (AOSP-faithful) and returns
     *  true. Net effect: every row matched everything and getColorForState
     *  returned row 0 for any state array you passed.
     *
     *  Namespace matters and must not be collapsed. An `android:`-namespaced
     *  attribute resolves ONLY through the frozen framework table; an
     *  app/res-auto one resolves ONLY through the app's index. Getting the
     *  app branch wrong is visible: `m3_checkbox_button_tint.xml` (present in
     *  20 of 44 corpus apps) carries an `app:state_error` row, and an
     *  unresolved id there collapses that row's spec to a wildcard that matches
     *  unconditionally -- painting the checkbox `?colorError` RED.
     *
     *  Returning 0 for a genuinely unknown name is the documented contract, and
     *  it is safe here only because resolution is empirically complete: the
     *  corpus has zero bare-namespace `state_*` attributes across 15,317
     *  occurrences, all 9 `android:` state names used are in the frozen table,
     *  and the 4 `app:` ones are in each app's own public.xml. Because 0 is
     *  ALSO the terminator, a future gap would be silent -- so warn once per
     *  name rather than letting it degrade quietly. */
    private static final java.util.Set<String> sUnresolvedAttrWarned =
        java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    /** ⚠ GATED OFF (MACAPK_ATTR_NAME_RESOURCE=1) -- MEASURED, ONE CLEAR WIN AND
     *  ONE UNEXPLAINED REGRESSION, v1.0.889.
     *  WIN: F-Droid's four unselected bottom-nav icons went from near-invisible
     *  WHITE ghosts to dark slate matching the emulator (dark px in the nav band:
     *  emulator 3524, before ~0, after 4338). That is androidx-inflated
     *  itemIconTint selectors resolving for the first time, exactly as intended.
     *  REGRESSION: F-Droid's search FAB LOST its green fill entirely (green px in
     *  the FAB box: emulator 11290, before green, after 0 -- it renders light
     *  grey). NOT caused by the ColorStateList default-colour rule: reverting
     *  that left the FAB grey, so this call is the cause.
     *  WHY IT IS UNEXPLAINED: the obvious mechanism does not fit. A Material FAB
     *  backgroundTint selector is [state_enabled=false -> dim, <wildcard> -> fill],
     *  and BOTH default rules pick the fill for that shape -- the old
     *  forward-scan because no all-zero spec is ever length-0 (so it falls to
     *  colors[last]), and the real-id case because the wildcard row IS length-0.
     *  So something OTHER than ColorStateList consumes this id on the FAB path.
     *  getAttributeNameResource is not androidx's alone: TintTypedArray,
     *  MaterialResources and MaterialShapeDrawable's fill plumbing all read
     *  AttributeSets, and any of them may branch on it.
     *  NEXT STEP: find which consumer. `debug/appclass.py fdroid --callers
     *  getAttributeNameResource` names every class in the app that calls it;
     *  read the FAB-adjacent hits rather than guessing. Do NOT flip this on
     *  until the FAB is green AND the nav icons stay dark. */
    @Override public int getAttributeNameResource(int index) {
        // v1.0.890 probe, paired with the one in ColorStateList.inflate. This
        // method is called by androidx's ColorStateListInflaterCompat and by
        // essentially nothing else in a colour-selector context, so a hit here
        // during a run PROVES androidx's inflater is doing the work rather than
        // ours. Logged BEFORE the gate so it reports on a normal (gated-off)
        // build -- the question is who runs, not what we return.
        if (System.getenv("MACAPK_TRACE_CSL_INFLATE") != null) {
            Node probe = nthAttr(index);
            String pn = (probe == null) ? "?" : probe.getNodeName();
            if (sUnresolvedAttrWarned.add("PROBE:" + pn)) {
                NativeLog.i("Mac.apk.CSL",
                    "ANDROIDX asked getAttributeNameResource for " + pn);
            }
        }
        // v1.0.892: ON by default (MACAPK_ATTR_NAME_RESOURCE=0 opts out). It was
        // gated at v1.0.889 because real ids fixed fdroid's invisible nav icons
        // but killed its FAB fill, with no mechanism that fit. The mechanism is
        // now known and fixed in the SAME version: the base Drawable state
        // machine was a stub, so MaterialShapeDrawable resolved the FAB's tint
        // against an empty state (see Drawable.setState). These are ONE change --
        // the state fix is a near-no-op alone, because with all-zero specs every
        // row matches anyway, and real ids alone are the regression.
        if ("0".equals(System.getenv("MACAPK_ATTR_NAME_RESOURCE"))) return 0;
        Node n = nthAttr(index);
        if (n == null) return 0;
        String local = n.getLocalName();
        if (local == null || local.isEmpty()) local = n.getNodeName();
        if (local == null || local.isEmpty()) return 0;
        // The compiled document's own resource-map id, when BinaryXmlParser kept it.
        NamedNodeMap all = currentAttrs();
        for (int i = 0; all != null && i < all.getLength(); ++i) {
            Node it = all.item(i);
            if (BinaryXmlParser.MACAPK_ATTRID_NS.equals(it.getNamespaceURI()) && local.equals(it.getLocalName())) {
                try { return Integer.parseInt(it.getNodeValue()); } catch (NumberFormatException ignored) { }
            }
        }
        final String uri = n.getNamespaceURI();
        final boolean isAndroidNs = uri != null
            && uri.startsWith("http://schemas.android.com/apk/res/android");
        if (isAndroidNs) {
            int id = android.content.res.ColorStateList.macapkFrameworkAttrId(local);
            if (id == 0) warnUnresolvedAttr("android", local);
            return id;
        }
        if (uri != null && !uri.isEmpty()) {
            // res-auto, or an explicit per-package res/<pkg> namespace.
            int id = lookupResource("attr", local);
            if (id == 0) warnUnresolvedAttr(uri, local);
            return id;
        }
        // No namespace declared: the app's own table first, framework second.
        int id = lookupResource("attr", local);
        if (id != 0) return id;
        return android.content.res.ColorStateList.macapkFrameworkAttrId(local);
    }
    private static void warnUnresolvedAttr(String ns, String local) {
        if (!sUnresolvedAttrWarned.add(ns + ":" + local)) return;
        // Loud once, because 0 doubles as the state-spec terminator: an
        // unresolved STATE attr silently turns its row into a match-everything
        // wildcard rather than failing.
        NativeLog.w("Mac.apk.Xml", "getAttributeNameResource: no id for "
            + ns + ":" + local + " (a state_* name here would silently"
            + " become a wildcard row)");
    }
    @Override public int getAttributeListValue(String ns, String a, String[] options, int def) {
        String v = getAttributeValue(ns, a);
        if (v == null) return def;
        for (int i = 0; i < options.length; ++i) if (v.equals(options[i])) return i;
        return def;
    }
    @Override public boolean getAttributeBooleanValue(String ns, String a, boolean def) {
        String v = getAttributeValue(ns, a);
        return v == null ? def : Boolean.parseBoolean(v);
    }
    @Override public int getAttributeResourceValue(String ns, String a, int def) {
        return parseIntOrDef(getAttributeValue(ns, a), def);
    }
    @Override public int getAttributeIntValue(String ns, String a, int def) {
        return parseIntOrDef(getAttributeValue(ns, a), def);
    }
    @Override public int getAttributeUnsignedIntValue(String ns, String a, int def) {
        return parseIntOrDef(getAttributeValue(ns, a), def);
    }
    @Override public float getAttributeFloatValue(String ns, String a, float def) {
        String v = getAttributeValue(ns, a);
        if (v == null) return def;
        try { return Float.parseFloat(v); } catch (NumberFormatException e) { return def; }
    }
    @Override public int getAttributeListValue(int index, String[] options, int def) {
        String v = getAttributeValue(index);
        if (v == null) return def;
        for (int i = 0; i < options.length; ++i) if (v.equals(options[i])) return i;
        return def;
    }
    @Override public boolean getAttributeBooleanValue(int index, boolean def) {
        String v = getAttributeValue(index);
        return v == null ? def : Boolean.parseBoolean(v);
    }
    @Override public int getAttributeResourceValue(int index, int def)   { return parseIntOrDef(getAttributeValue(index), def); }
    @Override public int getAttributeIntValue(int index, int def)        { return parseIntOrDef(getAttributeValue(index), def); }
    @Override public int getAttributeUnsignedIntValue(int index, int def){ return parseIntOrDef(getAttributeValue(index), def); }
    @Override public float getAttributeFloatValue(int index, float def) {
        String v = getAttributeValue(index);
        if (v == null) return def;
        try { return Float.parseFloat(v); } catch (NumberFormatException e) { return def; }
    }
    @Override public String getIdAttribute()                  { return getAttributeValue(null, "id"); }
    @Override public String getClassAttribute()               { return getAttributeValue(null, "class"); }
    @Override public int getIdAttributeResourceValue(int def) { return parseIntOrDef(getIdAttribute(), def); }
    @Override public int getStyleAttribute()                  { return parseIntOrDef(getAttributeValue(null, "style"), 0); }

    private static int parseIntOrDef(String v, int def) {
        if (v == null || v.isEmpty()) return def;
        // v1.0.95: resolve `@type/name` references via the
        // ResourceIndex (apktool-decoded public.xml). Previously this
        // returned `def` (= 0 for getAttributeResourceValue) whenever
        // the value started with @, which silently dropped EVERY
        // menu/layout/preference `@string/...` and `@drawable/...`
        // reference. F-Droid's BottomNav tabs were rendering with
        // empty labels because SupportMenuInflater asked for the
        // title resource ID, got 0, and gave up. Now we parse
        // @[pkg:]type/name and resolve via Resources.getIdentifier.
        // The `@0x...` numeric AOSP form (which we don't actually
        // generate in our apktool path) falls through to hex parse.
        try {
            if (v.startsWith("@")) {
                String body = v.substring(1);
                if (body.startsWith("+")) body = body.substring(1); // @+id/foo
                if (body.startsWith("0x") || body.startsWith("0X")) {
                    return (int) Long.parseLong(body.substring(2), 16);
                }
                // Strip optional package prefix ("android:string/foo").
                int colon = body.indexOf(':');
                if (colon >= 0) body = body.substring(colon + 1);
                int slash = body.indexOf('/');
                if (slash <= 0 || slash + 1 >= body.length()) return def;
                String type = body.substring(0, slash);
                String name = body.substring(slash + 1);
                int id = lookupResource(type, name);
                if (id != 0) return id;
                return def;
            }
            if (v.startsWith("0x") || v.startsWith("0X"))
                return (int) Long.parseLong(v.substring(2), 16);
            return Integer.parseInt(v);
        } catch (NumberFormatException e) { return def; }
    }

    /** Static lookup via the ResourceIndex. Resolves @<type>/<name>
     *  references using the apktool-decoded public.xml. ResourceIndex
     *  is in com.macapk.loader so we go through reflection to avoid
     *  introducing a cross-package dependency from android.content.res.
     *  Returns 0 if the resource is missing or the lookup chain hasn't
     *  initialized yet. */
    private static int lookupResource(String type, String name) {
        try {
            Object idx = android.content.res.Resources.macapkGetResourceIndex();
            if (idx == null) return 0;
            java.lang.reflect.Method m = idx.getClass()
                .getMethod("idOfName", String.class, String.class, String.class);
            Object v = m.invoke(idx, name, type, null);
            if (v instanceof Integer) return ((Integer) v).intValue();
        } catch (Throwable ignored) {}
        return 0;
    }

    // ---- features / properties / entity replacement (no-ops) ----
    @Override public void setFeature(String name, boolean state) {}
    @Override public boolean getFeature(String name) { return false; }
    @Override public void setProperty(String name, Object value) {}
    @Override public Object getProperty(String name) { return null; }
    @Override public String getInputEncoding() { return inputEncoding; }
    @Override public void defineEntityReplacementText(String entityName, String replacementText) {}

    @Override public void close() { closed = true; }
}
