// ViewTextDump -- the HARNESS text dump: one uiautomator-shaped node per
// visible View, with ABSOLUTE surface-pixel bounds computed in-process.
//
// WHY THIS EXISTS (2026-09-26, docs/harness-text-dump_2026-09-26.md).
// debug/drive_app.sh taps "by text": it reads a view-tree dump out of the log
// and taps the centre of the view whose label matches. The emulator side of the
// fleet A/B (debug/emu_tap.py) does the same thing through `uiautomator dump`,
// and the A/B is only a comparison when both sides can SEE the same labels. The
// old source, ViewCanvasDriver.dumpTree, is a layout-debugging dump, not a
// label dump, and it hid targets four ways, each measured on the tablet A/B:
//   1. it prints getClass().getSimpleName(), which is "" for an ANONYMOUS
//      class, and debug/fdroidtest/treeparse.py requires a non-empty name, so
//      the line (and the coordinate chain of every descendant) was dropped.
//      Telegram 12.9.1's "Start Messaging" is `new TextView(context) {...}`;
//      the dump HAD the text, the parser threw it away, and drive_app's
//      tapany fell back to the first clickable view (the theme toggle), so
//      step 1 compared two different actions;
//   2. it prints raw text, so a multi-line label breaks the one-line record;
//   3. it carries no contentDescription, no hint, and nothing a custom view
//      or an AccessibilityNodeProvider publishes, which is exactly what
//      uiautomator reads (AccessibilityNodeInfoDumper.dumpNodeRec, android14:
//      node.getText() and node.getContentDescription());
//   4. it cannot see page text inside a WebView at all (the WKWebView draws in
//      its own Cocoa layer, so neither the dump nor the composite capture has
//      it) -- Candy Crush's first-run Terms of Use ACCEPT button.
// This dump answers all four the way uiautomator does, and it walks the
// floating windows (Dialog / PopupWindow) too, which the tree dump never did.
//
// WHAT A NODE'S LABEL IS (AOSP, cited):
//   * TextView: getTextForAccessibility() -- the text, or the HINT when the
//     text is empty (TextView.java android14-release :14439-14447, set into the
//     node at :13662). A password field shows its transformed text (dots).
//   * any View: createAccessibilityNodeInfo() -> onInitializeAccessibility-
//     NodeInfo(), so a custom view that publishes info.setText(...) is read
//     the way TalkBack and uiautomator read it; its text wins over the TextView
//     text, as AOSP's override chain does.
//   * content-desc: View.getContentDescription() (View.java :10745).
//   * a provider host (getAccessibilityNodeProvider() != null): the virtual
//     tree, provider.createAccessibilityNodeInfo(HOST_VIEW_ID = -1) and then
//     each child id, exactly AOSP's createAccessibilityNodeInfoInternal
//     (View.java :8861-8869). Walking children needs the node to RECORD child
//     ids (AOSP mChildNodeIds, read back through the hidden getChildId(int));
//     this class asks for that method reflectively, so a framework
//     AccessibilityNodeInfo without it degrades to "host node only".
// VISIBLE-TO-USER is AOSP View.isVisibleToUser(Rect) (:10985-11018): VISIBLE
// all the way up, alpha and transitionAlpha > 0, and a non-empty global
// visible rect -- each ancestor offsets by (left - scrollX, top - scrollY) and
// clips to its own bounds when it clips its children (ViewGroup
// getChildVisibleRect). The dump computes that rect itself, so bounds are the
// surface pixels MACAPK_INJECT_FILE's `tap <x> <y>` expects, scrolled
// containers and translated pages included (treeparse's legacy path cannot
// see scrollX, and put Telegram's off-screen intro page on screen).
//
// FORMAT (tag Mac.apk.TextDump; one line per record, text JSON-escaped):
//   BEGIN seq=N root=l,t,r,b
//   WIN seq=N id=a|oK b=l,t,r,b modal=0|1 touchable=0|1 cls=Name
//   NODE seq=N win=a d=D b=l,t,r,b clk=0|1 lclk=0|1 edit=0|1 web=0|1 prov=0|1
//        src=text|hint|a11y|desc|vnode|none cls=Name@hex wcls=Name
//        desc="json" text="json"
//   END seq=N nodes=K
//   WEBDOM seq=N wv=hex b=l,t,r,b vis=l,t,r,b vw=W vh=H json=<evaluateJavascript result>
// WEBDOM is asynchronous: evaluateJavascript answers on the WebView's thread
// after END. debug/fdroidtest/treeparse.py maps its page rects (CSS px) into
// surface pixels with the view/page size ratio.
// Both NODE and WEBDOM text carry every UTF-16 surrogate as a \\uXXXX escape
// (json(), escapeSurrogates()): NativeLog prints MODIFIED UTF-8, which would
// otherwise split an emoji into two undecodable halves.
//
// GATES. Runs only when MACAPK_DUMP_TREE_EVERY is set (a harness run: normal
// launches never reach it, so they are byte-identical); MACAPK_TEXT_DUMP=0
// restores the exact pre-change path (the hook returns at once);
// MACAPK_TEXT_DUMP_WEB=0 skips only the WebView page query. Offline gate:
// debug/harness_gate.sh.
package com.macapk.loader;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import java.util.function.Consumer;

public final class ViewTextDump {
    private ViewTextDump() {}

    public static final String TAG = "Mac.apk.TextDump";

    /** Read once: the dump is a harness facility, and the harness sets both
     *  variables before launch. MACAPK_DUMP_TREE_EVERY is what drive_app.sh,
     *  ime_gate.sh and fdroid_drive.sh already set; requiring it keeps every
     *  non-harness launch off this path entirely. */
    public static final boolean ENABLED =
            System.getenv("MACAPK_DUMP_TREE_EVERY") != null
            && !"0".equals(System.getenv("MACAPK_TEXT_DUMP"));
    private static final boolean WEB = !"0".equals(System.getenv("MACAPK_TEXT_DUMP_WEB"));

    private static final int MAX_NODES = 5000;      // per dump, all windows
    private static final int MAX_VNODES = 2000;     // per provider
    private static final int MAX_TEXT = 400;        // chars per label, like a log line should be
    private static final long WEB_RETRY_MS = 10_000L;

    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger();
    /** WebView -> when its page query went out. A page that never answers must
     *  not get a new query every dump forever, but must not be ignored forever
     *  either: re-ask after WEB_RETRY_MS. Weak, so a dump never pins a view. */
    private static final java.util.Map<Object, Long> WEB_INFLIGHT =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** The ViewCanvasDriver hook: dump to the runtime log. */
    public static void dumpToLog(View root, int statusBarH) {
        if (!ENABLED || root == null) return;
        dump(root, statusBarH, line -> NativeLog.i(TAG, line));
    }

    /** Dump the content root plus every floating window; returns the node
     *  count. `out` receives one record per call. Never throws. */
    public static int dump(View root, int statusBarH, Consumer<String> out) {
        if (root == null || out == null) return 0;
        final int seq = SEQ.incrementAndGet();
        final int[] count = {0};
        try {
            Rect rootRect = new Rect(root.getLeft(), root.getTop(), root.getRight(), root.getBottom());
            out.accept("BEGIN seq=" + seq + " root=" + box(rootRect));
            out.accept("WIN seq=" + seq + " id=a b=" + box(rootRect)
                    + " modal=0 touchable=1 cls=" + className(root));
            walk(root, 0, 0, rootRect, 0, "a", seq, out, count);
            java.util.List<WindowOverlayStack.Layer> layers = java.util.Collections.emptyList();
            try { layers = WindowOverlayStack.snapshot(); } catch (Throwable ignored) { }
            int k = 0;
            for (WindowOverlayStack.Layer layer : layers) {
                View v = layer.view;
                if (v == null || v.getVisibility() != View.VISIBLE) continue;
                // ViewCanvasDriver draws a layer at (bounds.left, statusBarH +
                // bounds.top) and paints the view in its LOCAL space, so the
                // view's own left/top are not added again: origin = that point
                // minus them, which walk() adds back.
                Rect lb = layer.bounds;
                int al = lb.left, at = statusBarH + lb.top;
                Rect wr = new Rect(al, at, al + lb.width(), at + lb.height());
                int flags = layer.params != null ? layer.params.flags : 0;
                // AOSP: FLAG_NOT_FOCUSABLE "will also enable FLAG_NOT_TOUCH_MODAL"
                // (WindowManager.LayoutParams javadoc), and LayoutParams.isModal()
                // is (flags & (FLAG_NOT_TOUCH_MODAL | FLAG_NOT_FOCUSABLE)) == 0
                // (android14-release WindowManager.java:5627-5629; WindowState
                // .getEffectiveTouchableRegion uses it). A touch-modal window takes
                // every touch, inside or out, so what is under it is not tappable.
                // ⚠ OUR dispatcher disagrees for NOT_FOCUSABLE-only layers:
                // WindowOverlayStack.deliverTouch tests FLAG_NOT_TOUCH_MODAL alone,
                // so a default non-focusable PopupWindow still swallows an outside
                // tap there. The dump follows AOSP; the dispatcher fix is filed
                // (docs/harness-text-dump_2026-09-26.md, "Known disagreements").
                boolean touchable = (flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0;
                boolean modal = touchable
                        && (flags & WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) == 0
                        && (flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0;
                String id = "o" + (k++);
                out.accept("WIN seq=" + seq + " id=" + id + " b=" + box(wr)
                        + " modal=" + (modal ? 1 : 0) + " touchable=" + (touchable ? 1 : 0)
                        + " cls=" + className(v));
                walk(v, al - v.getLeft(), at - v.getTop(), wr, 0, id, seq, out, count);
            }
        } catch (Throwable t) {
            out.accept("ERROR seq=" + seq + " " + oneLine(String.valueOf(t)));
        }
        out.accept("END seq=" + seq + " nodes=" + count[0]);
        return count[0];
    }

    /** ox/oy: the absolute point v.getLeft()/getTop() are relative to (the
     *  parent's content origin: its absolute position minus its scroll). */
    private static void walk(View v, int ox, int oy, Rect clip, int depth, String win,
                             int seq, Consumer<String> out, int[] count) {
        if (v == null || count[0] >= MAX_NODES) return;
        if (v.getVisibility() != View.VISIBLE) return;
        try {
            if (v.getAlpha() <= 0f || v.getTransitionAlpha() <= 0f) return;
        } catch (Throwable ignored) { }
        int l = ox + v.getLeft() + Math.round(safeTx(v));
        int t = oy + v.getTop() + Math.round(safeTy(v));
        Rect abs = new Rect(l, t, l + v.getWidth(), t + v.getHeight());
        Rect vis = new Rect(abs);
        boolean shown = vis.intersect(clip);
        if (shown && !vis.isEmpty()) {
            emitView(v, abs, vis, depth, win, seq, out, count);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            boolean clips = true;
            try { clips = g.getClipChildren(); } catch (Throwable ignored) { }
            Rect childClip;
            if (clips) {
                if (!shown || vis.isEmpty()) return;   // nothing of this group is on screen
                childClip = vis;
            } else {
                childClip = clip;
            }
            int cox = l - v.getScrollX(), coy = t - v.getScrollY();
            int n = g.getChildCount();
            for (int i = 0; i < n; i++) {
                walk(g.getChildAt(i), cox, coy, childClip, depth + 1, win, seq, out, count);
            }
        }
    }

    private static void emitView(View v, Rect abs, Rect vis, int depth, String win,
                                 int seq, Consumer<String> out, int[] count) {
        String text = null, src = "none";
        if (v instanceof android.widget.TextView) {
            android.widget.TextView tv = (android.widget.TextView) v;
            CharSequence t = null;
            try { t = tv.getText(); } catch (Throwable ignored) { }
            if (t != null && t.length() > 0) {
                text = t.toString();
                src = "text";
                try {
                    if (tv.getTransformationMethod()
                            instanceof android.text.method.PasswordTransformationMethod) {
                        // AOSP's accessibility text is the TRANSFORMED text.
                        text = "\u2022".repeat(text.length());
                    }
                } catch (Throwable ignored) { }
            } else {
                CharSequence h = null;
                try { h = tv.getHint(); } catch (Throwable ignored) { }
                if (h != null && h.length() > 0) { text = h.toString(); src = "hint"; }
            }
        }
        CharSequence desc = null;
        try { desc = v.getContentDescription(); } catch (Throwable ignored) { }
        AccessibilityNodeProvider prov = null;
        try { prov = v.getAccessibilityNodeProvider(); } catch (Throwable ignored) { }
        if (prov == null) {
            // What TalkBack / uiautomator read. An override that sets text is
            // the custom view's own label and wins, as in AOSP's chain.
            try {
                AccessibilityNodeInfo info = v.createAccessibilityNodeInfo();
                if (info != null) {
                    CharSequence it = info.getText();
                    if (it != null && it.length() > 0 && !it.toString().equals(text)) {
                        text = it.toString();
                        src = "a11y";
                    }
                    CharSequence id = info.getContentDescription();
                    if ((desc == null || desc.length() == 0) && id != null && id.length() > 0) desc = id;
                }
            } catch (Throwable ignored) { }
        }
        if (text == null && desc != null && desc.length() > 0) src = "desc";
        boolean clk = false, lclk = false, edit = false;
        try { clk = v.isClickable() || v.hasOnClickListeners(); } catch (Throwable ignored) { }
        try { lclk = v.isLongClickable(); } catch (Throwable ignored) { }
        edit = v instanceof android.widget.EditText;
        boolean web = v instanceof android.webkit.WebView;
        // tch: the view takes raw touch itself -- an OnTouchListener, or an APP
        // class overriding onTouchEvent (a custom canvas board: SolitaireCG);
        // drg: a drop target -- an OnDragListener, or an app override of
        // onDragEvent (View.dispatchDragEvent: listener first, then onDragEvent).
        // What debug/drive_app.sh `dragprobe` picks its drag source/target from.
        boolean tch = false, drg = false;
        try { tch = v.macapkHasOnTouchListener() || overrides(v.getClass(), "onTouchEvent", android.view.MotionEvent.class); }
        catch (Throwable ignored) { }
        try { drg = v.macapkHasOnDragListener() || overrides(v.getClass(), "onDragEvent", android.view.DragEvent.class); }
        catch (Throwable ignored) { }
        count[0]++;
        out.accept("NODE seq=" + seq + " win=" + win + " d=" + depth + " b=" + box(vis)
                + " clk=" + (clk ? 1 : 0) + " lclk=" + (lclk ? 1 : 0) + " edit=" + (edit ? 1 : 0)
                + " web=" + (web ? 1 : 0) + " prov=" + (prov != null ? 1 : 0)
                + " tch=" + (tch ? 1 : 0) + " drg=" + (drg ? 1 : 0)
                + " src=" + src + " cls=" + className(v) + "@" + hex(v) + " wcls=" + widgetClass(v)
                + " desc=" + json(desc) + " text=" + json(text));
        if (prov != null) {
            try { walkProvider(prov, v, abs, vis, depth + 1, win, seq, out, count); }
            catch (Throwable t) { out.accept("VERROR seq=" + seq + " host=" + hex(v) + " " + oneLine(String.valueOf(t))); }
        }
        if (web && WEB) requestWeb((android.webkit.WebView) v, abs, vis, seq, out);
    }

    // ---- AccessibilityNodeProvider (virtual views: Compose, ExploreByTouchHelper, custom canvases)

    private static void walkProvider(AccessibilityNodeProvider p, View host, Rect hostAbs, Rect clip,
                                     int depth, String win, int seq, Consumer<String> out, int[] count) {
        // The provider's boundsInScreen are in whatever frame the host's
        // getLocationOnScreen reports; subtract that and add the host's surface
        // position, so the answer does not depend on which frame that is.
        int[] loc = new int[2];
        try { host.getLocationOnScreen(loc); } catch (Throwable ignored) { }
        AccessibilityNodeInfo rootNode = p.createAccessibilityNodeInfo(-1 /* AOSP HOST_VIEW_ID */);
        if (rootNode == null) return;
        java.util.ArrayDeque<Object[]> work = new java.util.ArrayDeque<>();
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        seen.add(-1);
        work.add(new Object[] { rootNode, depth, Boolean.TRUE });
        int emitted = 0;
        while (!work.isEmpty() && emitted < MAX_VNODES && count[0] < MAX_NODES) {
            Object[] w = work.pollFirst();
            AccessibilityNodeInfo n = (AccessibilityNodeInfo) w[0];
            int d = (Integer) w[1];
            boolean isHost = (Boolean) w[2];
            if (!isHost) {
                // uiautomator skips a child that is not visible to the user.
                if (!n.isVisibleToUser()) continue;
                Rect sb = new Rect();
                n.getBoundsInScreen(sb);
                Rect r = new Rect(sb.left - loc[0] + hostAbs.left, sb.top - loc[1] + hostAbs.top,
                                  sb.right - loc[0] + hostAbs.left, sb.bottom - loc[1] + hostAbs.top);
                if (r.intersect(clip) && !r.isEmpty()) {
                    CharSequence t = n.getText(), ds = n.getContentDescription();
                    if (t == null || t.length() == 0) t = null;
                    String src = t != null ? "vnode" : (ds != null && ds.length() > 0 ? "desc" : "none");
                    count[0]++; emitted++;
                    out.accept("NODE seq=" + seq + " win=" + win + " d=" + d + " b=" + box(r)
                            + " clk=" + (n.isClickable() ? 1 : 0) + " lclk=" + (n.isLongClickable() ? 1 : 0)
                            + " edit=" + (n.isEditable() ? 1 : 0) + " web=0 prov=0 src=" + src
                            + " cls=" + sanitizeCls(n.getClassName()) + "@" + hex(host)
                            + " wcls=" + sanitizeCls(n.getClassName())
                            + " desc=" + json(ds) + " text=" + json(t));
                }
            }
            int cc = n.getChildCount();
            for (int i = 0; i < cc; i++) {
                Integer vid = childVirtualId(n, i);
                if (vid == null) {
                    if (isHost && i == 0) {
                        out.accept("VNOTE seq=" + seq + " host=" + hex(host) + " children=" + cc
                                + " unreadable (AccessibilityNodeInfo records no child ids)");
                    }
                    break;
                }
                // HOST_VIEW_ID (-1): AOSP's addChild(View) records a REAL child
                // view that way (makeNodeId(viewId, AccessibilityNodeProvider
                // .HOST_VIEW_ID)) -- e.g. Compose's AndroidViewHolder interop
                // children. It is not a provider node, so asking the provider
                // for it is not an AOSP client pattern (uiautomator resolves it
                // as a view, and createAccessibilityNodeInfo(HOST_VIEW_ID) would
                // just return the host again); walk() already visits it as a
                // view. UNDEFINED_ITEM_ID (Integer.MAX_VALUE) is skipped too: it
                // is never a provider node either.
                if (vid == -1 || vid == Integer.MAX_VALUE) continue;
                if (!seen.add(vid)) continue;
                AccessibilityNodeInfo c = null;
                try { c = p.createAccessibilityNodeInfo(vid); } catch (Throwable ignored) { }
                if (c != null) work.add(new Object[] { c, d + 1, Boolean.FALSE });
            }
        }
    }

    /** The virtual id of child `i`, through AOSP's hidden getChildId(int)
     *  (a node id = virtualId << 32 | accessibilityViewId,
     *  AccessibilityNodeInfo.makeNodeId), or null when the node class does not
     *  record child ids. */
    private static Integer childVirtualId(AccessibilityNodeInfo n, int i) {
        try {
            java.lang.reflect.Method m = n.getClass().getMethod("getChildId", int.class);
            Object r = m.invoke(n, i);
            if (r instanceof Long) return (int) (((Long) r) >> 32);
        } catch (Throwable ignored) { }
        return null;
    }

    // ---- WebView page text ----------------------------------------------

    /** The page walk, run in the page. Returns ONE JSON string (so the
     *  evaluateJavascript result is a JSON string literal, which never holds a
     *  raw newline): {w,h,n:[[l,t,r,b,clk,tag,label],...],body}. Coordinates
     *  are the viewport's CSS pixels (getBoundingClientRect). A node is an
     *  element that is on screen, not hidden, and either a CONTROL (a, button,
     *  input, select, textarea, label, summary, option, an ARIA widget role,
     *  an onclick attribute or cursor:pointer -- the last is how a div wired
     *  with addEventListener shows itself) labelled by its innerText / value /
     *  placeholder / aria-label / title, or an element with its own text,
     *  labelled by its whole innerText (so a paragraph with an inline link
     *  reads as one sentence, as Chrome's accessibility tree reads it, and the
     *  link is a node of its own). */
    public static final String WEB_JS =
        "(function(){try{"
      + "var W=window.innerWidth||document.documentElement.clientWidth,"
      + "H=window.innerHeight||document.documentElement.clientHeight,out=[],"
      + "C={A:1,BUTTON:1,INPUT:1,SELECT:1,TEXTAREA:1,LABEL:1,SUMMARY:1,OPTION:1},"
      + "R={button:1,link:1,checkbox:1,radio:1,tab:1,menuitem:1,option:1,switch:1},"
      + "S={SCRIPT:1,STYLE:1,NOSCRIPT:1,TEMPLATE:1,HEAD:1,META:1,LINK:1,TITLE:1},"
      + "all=document.body?document.body.getElementsByTagName('*'):[];"
      + "function sp(s){return (s||'').replace(/\\s+/g,' ').trim();}"
      + "for(var i=0;i<all.length&&out.length<400;i++){"
      + "var e=all[i],tn=e.tagName;if(S[tn])continue;"
      + "var r=e.getBoundingClientRect();"
      + "if(r.width<1||r.height<1||r.right<=0||r.bottom<=0||r.left>=W||r.top>=H)continue;"
      + "var cs=getComputedStyle(e);"
      + "if(cs.visibility==='hidden'||cs.display==='none'||parseFloat(cs.opacity)===0)continue;"
      + "var role=(e.getAttribute('role')||'').toLowerCase(),"
      + "clk=!!(C[tn]||R[role]||e.hasAttribute('onclick')||cs.cursor==='pointer'),t='';"
      + "if(tn==='INPUT'||tn==='TEXTAREA'||tn==='SELECT'){"
      + "t=(e.type==='password'?'':e.value)||e.placeholder||e.getAttribute('aria-label')||'';}"
      + "else if(clk){t=e.innerText||e.textContent||'';}"
      + "else{var own='';for(var c=e.firstChild;c;c=c.nextSibling)if(c.nodeType===3)own+=c.nodeValue;"
      + "if(sp(own))t=e.innerText||own;}"
      + "t=sp(t);"
      + "if(!t)t=sp(e.getAttribute('aria-label')||e.getAttribute('title')||(tn==='IMG'?e.getAttribute('alt'):''));"
      + "if(!t)continue;"
      + "out.push([Math.round(r.left),Math.round(r.top),Math.round(r.right),Math.round(r.bottom),"
      + "clk?1:0,tn.toLowerCase(),t.substring(0,300)]);}"
      + "return JSON.stringify({w:W,h:H,n:out,"
      + "body:sp(document.body?document.body.innerText:'').substring(0,4000)});"
      + "}catch(x){return JSON.stringify({err:String(x)});}})()";

    private static void requestWeb(android.webkit.WebView wv, Rect abs, Rect vis, int seq,
                                   Consumer<String> out) {
        long now = System.currentTimeMillis();
        Long prev = WEB_INFLIGHT.get(wv);
        final String head = "WEBDOM seq=" + seq + " wv=" + hex(wv) + " b=" + box(abs)
                + " vis=" + box(vis) + " vw=" + wv.getWidth() + " vh=" + wv.getHeight() + " json=";
        if (prev != null && now - prev < WEB_RETRY_MS) {
            out.accept(head + "null pending=1");
            return;
        }
        WEB_INFLIGHT.put(wv, now);
        try {
            wv.evaluateJavascript(WEB_JS, value -> {
                WEB_INFLIGHT.remove(wv);
                out.accept(head + oneLine(value == null ? "null" : escapeSurrogates(value)));
            });
        } catch (Throwable t) {
            WEB_INFLIGHT.remove(wv);
            out.accept(head + "null err=" + oneLine(String.valueOf(t)));
        }
    }

    // ---- formatting -----------------------------------------------------

    private static float safeTx(View v) { try { return v.getTranslationX(); } catch (Throwable t) { return 0f; } }
    private static float safeTy(View v) { try { return v.getTranslationY(); } catch (Throwable t) { return 0f; } }

    private static String box(Rect r) {
        return r.left + "," + r.top + "," + r.right + "," + r.bottom;
    }

    private static String hex(Object o) {
        return Integer.toHexString(System.identityHashCode(o));
    }

    /** Per (class, method): does an APP class (anything outside the framework
     *  packages) declare the override? A framework widget's own onTouchEvent
     *  (TextView, AbsListView, ...) is not the app handling touch. */
    private static final java.util.Map<String, Boolean> OVERRIDES =
            java.util.Collections.synchronizedMap(new java.util.HashMap<>());
    static boolean overrides(Class<?> c, String name, Class<?> arg) {
        String key = c.getName() + "#" + name;
        Boolean hit = OVERRIDES.get(key);
        if (hit != null) return hit;
        boolean r = false;
        for (Class<?> k = c; k != null && k != View.class; k = k.getSuperclass()) {
            String n = k.getName();
            if (n.startsWith("android.") || n.startsWith("androidx.") || n.startsWith("com.android.")
                    || n.startsWith("com.google.android.material.") || n.startsWith("java.")) continue;
            try { k.getDeclaredMethod(name, arg); r = true; break; }
            catch (NoSuchMethodException ignored) { }
            catch (Throwable t) { break; }   // a missing dependency of the app class: say no
        }
        OVERRIDES.put(key, r);
        return r;
    }

    /** Simple name, or for an anonymous / local class (whose simple name is
     *  "", the defect that hid Telegram's button) the binary name after the
     *  package: "IntroActivity$5". Never empty. */
    static String className(Object o) {
        Class<?> c = o.getClass();
        String s = c.getSimpleName();
        if (s == null || s.isEmpty()) {
            String n = c.getName();
            int dot = n.lastIndexOf('.');
            s = dot >= 0 ? n.substring(dot + 1) : n;
        }
        return sanitizeCls(s);
    }

    /** The nearest android.* / androidx.* ancestor's simple name -- what an
     *  app subclass IS as a widget ("TextView" for an anonymous TextView, much
     *  like AOSP's getAccessibilityClassName). */
    static String widgetClass(Object o) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            String n = c.getName();
            if (n.startsWith("android.") || n.startsWith("androidx.")) {
                String s = c.getSimpleName();
                if (s != null && !s.isEmpty()) return sanitizeCls(s);
            }
        }
        return "View";
    }

    private static String sanitizeCls(CharSequence s) {
        if (s == null || s.length() == 0) return "View";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            b.append(Character.isWhitespace(ch) || ch == '@' ? '_' : ch);
        }
        return b.toString();
    }

    private static String oneLine(String s) {
        return s.replace("\r", "\\r").replace("\n", "\\n");
    }

    /** A JSON string literal (RFC 8259 escaping), or null. Truncated to
     *  MAX_TEXT so one huge label cannot flood the log (never between the two
     *  halves of a surrogate pair).
     *
     *  ⚠ EVERY UTF-16 SURROGATE IS ESCAPED as \\uXXXX (fix pass, 2026-09-26).
     *  NativeLog's native print takes the string through GetStringUTFChars,
     *  which is MODIFIED UTF-8: a non-BMP character (an emoji, a CJK extension
     *  ideograph) goes out as two 3-byte CESU-8 halves that no UTF-8 reader
     *  decodes, so treeparse read "Let's go " + U+1F680 back as six U+FFFD
     *  and a recorded uiautomator label (real UTF-8) could never match it --
     *  a chat app's emoji button was a permanent taplabel MISS. Python's
     *  json.loads re-pairs the two escapes into one code point (RFC 8259 s7
     *  allows a pair as two escapes). */
    static String json(CharSequence cs) {
        if (cs == null) return "null";
        String s = cs.toString();
        if (s.length() > MAX_TEXT) {
            int cut = MAX_TEXT;
            if (Character.isHighSurrogate(s.charAt(cut - 1))) cut--;
            s = s.substring(0, cut);
        }
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (ch < 0x20 || ch == 0x2028 || ch == 0x2029 || Character.isSurrogate(ch)) {
                        b.append(String.format("\\u%04x", (int) ch));
                    } else {
                        b.append(ch);
                    }
            }
        }
        return b.append('"').toString();
    }

    /** `s` with every UTF-16 surrogate written as a \\uXXXX escape -- for an
     *  evaluateJavascript result, which is already JSON: in valid JSON a
     *  surrogate can occur only inside a string literal, where the escape
     *  means the same character, so the text stays valid JSON and survives
     *  NativeLog's modified UTF-8 (see json()). */
    static String escapeSurrogates(String s) {
        if (s == null) return null;
        StringBuilder b = null;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isSurrogate(ch)) {
                if (b == null) b = new StringBuilder(s.length() + 16).append(s, 0, i);
                b.append(String.format("\\u%04x", (int) ch));
            } else if (b != null) {
                b.append(ch);
            }
        }
        return b == null ? s : b.toString();
    }
}
