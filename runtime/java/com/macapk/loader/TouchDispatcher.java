// TouchDispatcher -- the unified input bridge between Cocoa events and
// Android input dispatch (touch AND hardware keyboard). The native
// MTKView subclass calls dispatch(action, x, y, viewW, viewH) for every
// mouseDown/Dragged/Up (mapped to a synthetic MotionEvent -> active
// Activity's dispatchTouchEvent) and dispatchKey(action, keyCode,
// unicode, meta) for every keyDown/keyUp (-> a synthetic KeyEvent ->
// active Activity's dispatchKeyEvent). Class name kept for ABI/registration
// stability; it is the general input driver now, not touch-only.
//
// Thread model: Cocoa events arrive on the AppKit main thread, which is
// also the JVM main thread, so dispatching is direct (no posting).
//
// Renderer-direct fallback: cocos2d-style engines deliver touches from
// onTouchEvent through queueEvent(runnable) onto their GL thread. After
// the regular view-tree dispatch we walk the content view for a renderer
// with handleActionDown/Up/Move and invoke them directly -- but ONLY
// where the queued copy will never run. v1.0.1639: our own
// android.opengl.GLSurfaceView now services queueEvent every frame
// (GLSurfaceView.macapkRunQueuedGlEvents, AOSP GLThread semantics), so
// for a renderer hosted by it (GD Lite, OpenGD) direct delivery would
// deliver every touch TWICE (a phantom second tap when DOWN and UP land
// in one frame) and is suppressed. It remains for vendored GLSurfaceView
// forks with their own GLThread (Hill Climb's FSGLSurfaceView), hosts
// that override queueEvent, and MACAPK_GLQ_DRAIN=0.
// 2026-09-27 (MACAPK_TOUCH_APP_QUEUE_ONLY, default ON): a fork's queue is now
// drained to empty before every draw by SurfaceView.macapkDrainForkedGlQueue
// (MACAPK_GLTHREAD_DRAIN_EMPTY), so for a fork host whose CURRENT GL thread is
// orphaned (dead, not a survivor) the app's own onTouchEvent -> queueEvent
// already delivers the touch, as on Android, and direct delivery is
// suppressed there too. It still runs where nothing would drain the queue
// (a live or survivor GL thread, a re-attach not yet re-bound, the drain off).
// docs/cocos2dx-touch-renderer-direct-retired_2026-09-27.md.
package com.macapk.loader;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

public final class TouchDispatcher {

    /** The registered dispatcher.
     *
     *  Boot hands the instance to the NATIVE side
     *  (NativeCocoa.registerTouchDispatcher), which is the only reference that
     *  existed -- so Java-side static helpers had no way to reach the instance
     *  methods. macapkInjectText needs dispatchKey(), which is an instance
     *  method because it consults per-dispatcher routing state. */
    private static volatile TouchDispatcher s_instance;

    public TouchDispatcher() { s_instance = this; }

    static TouchDispatcher macapkInstance() { return s_instance; }
    private static final String TAG = "Mac.apk.Touch";

    /** Called from native cocoa_bridge.mm for each mouse event.
     *  action: 0=ACTION_DOWN, 1=ACTION_UP, 2=ACTION_MOVE
     *  x, y:   point coords inside the MTKView (Cocoa, y=0 BOTTOM)
     *  viewW/H: MTKView bounds in points
     *  We convert to Android pixel coords (y=0 TOP, retina-scaled)
     *  matching what onSurfaceChanged fed the engine. */
    private static final boolean s_traceIme =
        "1".equals(System.getenv("MACAPK_TRACE_IME"));
    private static int s_logged = 0;
    /** Bump this when running the autoclick test harness so every
     *  injected click is visible in the log. */
    private static final int LOG_CAP = 64;

    /** Build the single-finger event a real touchscreen produces.
     *
     *  MotionEvent.obtain(downTime, eventTime, action, x, y, metaState) is the
     *  subset factory, and AOSP's subset factory leaves source
     *  SOURCE_UNKNOWN and toolType TOOL_TYPE_UNKNOWN (MotionEvent.java:1953 ->
     *  :1870, pp[0].clear()). Mac.apk used to paper over that by defaulting
     *  the factory itself to SOURCE_TOUCHSCREEN/TOOL_TYPE_FINGER, which made
     *  every event we synthesized answer differently from the platform. The
     *  defaults are now AOSP-exact, so the host-mouse path has to NAME the
     *  source -- an engine that routes input on (source & SOURCE_CLASS_POINTER)
     *  drops a SOURCE_UNKNOWN event outright.
     *
     *  Everything else here reproduces what the old short-form call produced:
     *  one pointer with id 0, pressure 1, size 1, edgeFlags 0, x/y precision 1. */
    /** Build a KeyEvent the way the platform's InputReader would.
     *
     *  KeyEvent's own constructors leave source at SOURCE_UNKNOWN, which is
     *  AOSP-exact (only the 10-arg ctor takes a source). On a real device an
     *  app never observes that, because InputReader stamps the originating
     *  device's source onto every key it delivers -- and it stamps the DEVICE's
     *  whole mask, not a per-keycode guess: InputDevice.cpp:161-179 builds one
     *  combined keyboardSource and KeyboardInputMapper.cpp:351 applies that same
     *  mSource to every key regardless of keycode.
     *
     *  We are the InputReader here, so we do the same, with the mask of the
     *  virtual keyboard InputManager registers at id -1 (KEYBOARD|DPAD). The
     *  arrow keys really do arrive as KEYCODE_DPAD_* from cocoa_bridge, so the
     *  DPAD half is honest. deviceId is already VIRTUAL_KEYBOARD (-1) from the
     *  constructor, which is what InputEvent.getDevice() resolves through. */
    static KeyEvent keyEvent(long downTime, long eventTime, int action,
                             int keyCode, int repeat, int metaState) {
        KeyEvent ev = new KeyEvent(downTime, eventTime, action, keyCode, repeat, metaState);
        ev.macapkSetSource(android.view.InputDevice.SOURCE_KEYBOARD
                         | android.view.InputDevice.SOURCE_DPAD);
        return ev;
    }

    /** A HOST keystroke (the Mac's keyboard), built the way InputReader +
     *  InputDispatcher build a hardware key (amongus-key-tap-move lane,
     *  2026-10-01; docs/host-keyboard-device_2026-10-01.md):
     *
     *  - DEVICE + SOURCE + FLAG_FROM_SYSTEM: the host keyboard's id and
     *    SOURCE_KEYBOARD (InputManager.macapkHostKeyDeviceId/-Source;
     *    MACAPK_HOST_KEYBOARD), and AKEY_EVENT_FLAG_FROM_SYSTEM, which
     *    KeyboardInputMapper sets on every hardware key (android14
     *    KeyboardInputMapper.cpp:285). keyEvent() above stays the builder for
     *    SYNTHESIZED keys -- the edge-swipe BACK and the harness `key` verb --
     *    which on Android come from the virtual keyboard (-1) too.
     *  - REPEAT COUNT + DOWN TIME (MACAPK_KEY_REPEAT, default ON). AppKit
     *    auto-repeats a held key as a fresh keyDown: and the bridge forwarded
     *    each one as a new press: repeatCount 0 and a new downTime every time,
     *    and the UP's downTime was its own time. AOSP: KeyboardInputMapper
     *    keeps the FIRST press's downTime for every repeat and for the UP
     *    (android14 KeyboardInputMapper.cpp:216-251), and InputDispatcher
     *    numbers "two identical key downs in a row" -- the driver repeating a
     *    held key itself, which is exactly AppKit's case -- as
     *    lastKeyEntry->repeatCount + 1, resets on any other key, and sets
     *    FLAG_LONG_PRESS on repeat 1 only (InputDispatcher.cpp:1652-1690).
     *
     *    {@code autoRepeat} is NSEvent.isARepeat, forwarded from AppKit: the host
     *    DOWN is a driver repeat of a still-held key ONLY when AppKit says so. A
     *    non-repeat DOWN is always a FRESH press (repeat 0, downTime = now, the
     *    held-key entry overwritten), because on our layer a key's UP can fail to
     *    reach this method -- AppKit sends no keyUp: while Command is held, the
     *    Shift-sensitive keycode of '1' vs '!' differs between the DOWN and the
     *    UP, and doDispatchKey's soft-input routing can return before the UP --
     *    whereas on Android InputReader always sees the UP and a focus loss cancels
     *    held keys (InputDispatcher.cpp:6599-6605). Without that rule a stale
     *    entry would hand the NEXT press of that key a stale downTime and a
     *    repeatCount > 0, and the repeat-gated AOSP paths (KeyEvent.dispatch
     *    starts tracking at repeat 0, so BACK -> onBackPressed; the Cmd+V shortcut
     *    stage; PopupWindow / ListPopupWindow / AutoCompleteTextView) would drop
     *    it. =0 restores repeat 0 / downTime == eventTime on every event. An UP for
     *    a key we never saw go down is still delivered (AOSP's mapper drops it):
     *    several host paths hand the guest a DOWN+UP pair whose DOWN may have
     *    been consumed upstream, and dropping their UP is a behaviour change
     *    this lane did not measure.
     *
     *    NOT done (known edge, notated): on window resign-key / app deactivate
     *    AOSP cancels held keys (ACTION_UP|FLAG_CANCELED); we do not, so hold W,
     *    Cmd+Tab away, release W leaves the guest believing W is held (this is
     *    the pre-change behaviour too) until W is pressed again. */
    static KeyEvent hostKeyEvent(long now, int action, int keyCode, int metaState, boolean autoRepeat) {
        long downTime = now;
        int repeat = 0;
        int flags = 0;
        if (KEY_REPEAT_AOSP) {
            synchronized (sHostKeyLock) {
                if (action == KeyEvent.ACTION_DOWN) {
                    // AppKit's "this is the OS repeating a held key" marks a driver repeat;
                    // anything else starts a fresh press (see above).
                    Long held = autoRepeat ? sHostKeyDownTime.get(keyCode) : null;
                    if (held != null) downTime = held;
                    else sHostKeyDownTime.put(keyCode, now);
                    repeat = (held != null && sHostLastDownKey == keyCode) ? sHostLastRepeat + 1 : 0;
                    sHostLastDownKey = keyCode;
                    sHostLastRepeat = repeat;
                    if (repeat == 1) flags |= KeyEvent.FLAG_LONG_PRESS;
                } else {
                    Long held = sHostKeyDownTime.remove(keyCode);
                    if (held != null) downTime = held;
                    sHostLastDownKey = Integer.MIN_VALUE;
                    sHostLastRepeat = 0;
                }
            }
        }
        // KeyboardInputMapper::processKey ORs AKEY_EVENT_FLAG_FROM_SYSTEM into every hardware
        // key (android14 :285); =0 (MACAPK_HOST_KEYBOARD) keeps the pre-change flags.
        if (android.hardware.input.InputManager.MACAPK_HOST_KEYBOARD) flags |= KeyEvent.FLAG_FROM_SYSTEM;
        final int src = android.hardware.input.InputManager.macapkHostKeySource();
        KeyEvent ev = new KeyEvent(downTime, now, action, keyCode, repeat, metaState,
                android.hardware.input.InputManager.macapkHostKeyDeviceId(), 0, flags, src);
        return ev;
    }
    /** MACAPK_KEY_REPEAT (default ON): see hostKeyEvent. */
    static final boolean KEY_REPEAT_AOSP = !"0".equals(System.getenv("MACAPK_KEY_REPEAT"));
    private static final Object sHostKeyLock = new Object();
    /** keyCode -> the downTime of a host key that is held right now. */
    private static final java.util.HashMap<Integer, Long> sHostKeyDownTime = new java.util.HashMap<>();
    private static int sHostLastDownKey = Integer.MIN_VALUE;
    private static int sHostLastRepeat;

    static MotionEvent touchEvent(long downTime, long eventTime, int action,
                                          float x, float y, int metaState) {
        // A host MOUSE stream (dispatchHostMouse) is being delivered: every event
        // built for it -- the activity's, an overlay window's, a drag source's
        // cancel -- is what CursorInputMapper emits, not a finger.
        final HostMouseState m = s_deliveringMouse;
        if (m != null) return mouseEvent(downTime, eventTime, action, x, y, m.buttonState, m.metaState);
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
        p.id = 0;
        p.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
        c.x = x; c.y = y;
        c.pressure = 1f; c.size = 1f;
        return MotionEvent.obtain(downTime, eventTime, action, 1,
                new MotionEvent.PointerProperties[]{ p },
                new MotionEvent.PointerCoords[]{ c },
                metaState, 0 /* buttonState */, 1f, 1f,
                TOUCHSCREEN_DEVICE_ID, 0 /* edgeFlags */,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0 /* flags */);
    }

    /** A TWO-POINTER MotionEvent, for the synthesized pinch stream. AOSP packs the pointer index of
     *  a POINTER_DOWN/UP into bits 8-15 of the action, which is what `getActionIndex()` reads and
     *  what every ScaleGestureDetector depends on. */
    static MotionEvent pinchEvent(long downTime, long eventTime, int action, int actionIndex,
                                  float x0, float y0, float x1, float y1, int pointerCount) {
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[pointerCount];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[pointerCount];
        for (int i = 0; i < pointerCount; i++) {
            MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
            p.id = i;
            p.toolType = MotionEvent.TOOL_TYPE_FINGER;
            props[i] = p;
            MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
            c.x = (i == 0) ? x0 : x1;
            c.y = (i == 0) ? y0 : y1;
            c.pressure = 1f; c.size = 1f;
            coords[i] = c;
        }
        int packed = action | (actionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        return MotionEvent.obtain(downTime, eventTime, packed, pointerCount, props, coords,
                0 /* metaState */, 0 /* buttonState */, 1f, 1f,
                TOUCHSCREEN_DEVICE_ID, 0 /* edgeFlags */,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0 /* flags */);
    }

    /** MULTI-TOUCH PINCH, synthesized from the host trackpad.
     *
     *  Mac.apk delivered exactly ONE pointer, so `ACTION_POINTER_DOWN`/`ACTION_POINTER_UP` were
     *  never generated and `ScaleGestureDetector` -- which is fully implemented here and was simply
     *  never fed -- could not fire. Consequence: **pinch-to-zoom was unreachable in every map,
     *  image, PDF and browser app**, with no error anywhere; the app just never saw a second finger.
     *
     *  macOS reports pinch as a single `NSEvent` with a scalar `magnification`, not as two touches,
     *  so the two fingers have to be synthesized. We straddle the cursor horizontally with a span
     *  that starts at a fixed base and scales by the accumulated magnification, which is the same
     *  convention the Android emulator and the iOS simulator use for a trackpad pinch. The gesture
     *  is delivered as AOSP orders it:
     *      DOWN(p0) -> POINTER_DOWN(p1) -> MOVE* -> POINTER_UP(p1) -> UP(p0)
     *
     *  `phase`: 0 begin, 1 change, 2 end. `cx`/`cy` are in the same view coordinates the ordinary
     *  `dispatch` path takes (Cocoa origin, bottom-left); the scaling/Y-flip below mirrors
     *  `doDispatch` exactly so both paths land in the same pixel space. */
    public void dispatchPinch(int phase, float cx, float cy, float span, float viewW, float viewH) {
        if (TRACKPAD_MULTITOUCH) {
            trackpadPinch(phase, cx, cy, span, 0f, viewW, viewH);
            return;
        }
        if (InputRecorder.ON) InputRecorder.unrecorded("legacy pinch (MACAPK_TRACKPAD_MULTITOUCH=0)");
        try {
            loopHandler().post(() -> doDispatchPinch(phase, cx, cy, span, viewW, viewH));
        } catch (Throwable t) {
            doDispatchPinch(phase, cx, cy, span, viewW, viewH);
        }
    }

    /** The synthesized two-finger stream cocoa_bridge.mm sends for a trackpad
     *  pinch AND a trackpad twist (rotateWithEvent:, GitHub #22): `span` is the
     *  finger separation and `angle` the axis between them (radians, counter-
     *  clockwise, view points with y up; 0 = side by side, the pinch). With
     *  MACAPK_TRACKPAD_MULTITOUCH=0 the old pinch body runs and a twist only
     *  moves nothing (that body has no angle). */
    public void dispatchTwoFinger(int phase, float cx, float cy, float span, float angle,
                                  float viewW, float viewH) {
        if (!TRACKPAD_MULTITOUCH) { dispatchPinch(phase, cx, cy, span, viewW, viewH); return; }
        trackpadPinch(phase, cx, cy, span, angle, viewW, viewH);
    }

    private void doDispatchPinch(int phase, float cx, float cy, float span,
                                 float viewW, float viewH) {
        Activity active = ActivityHost.peek();
        if (active == null) return;
        View croot = active.macapkContentView();
        if (croot == null) return;
        final float scale = macapkBackingScale();
        float px = cx * scale;
        float py = (viewH - cy) * scale;          // same Y-flip as doDispatch
        float half = Math.max(8f, span * scale) / 2f;
        float x0 = px - half, x1 = px + half;
        long now = android.os.SystemClock.uptimeMillis();
        try {
            if (phase == 0) {
                s_gestureDownTime = now;
                croot.dispatchTouchEvent(pinchEvent(s_gestureDownTime, now,
                        MotionEvent.ACTION_DOWN, 0, x0, py, x1, py, 1));
                croot.dispatchTouchEvent(pinchEvent(s_gestureDownTime, now,
                        MotionEvent.ACTION_POINTER_DOWN, 1, x0, py, x1, py, 2));
            } else if (phase == 1) {
                croot.dispatchTouchEvent(pinchEvent(s_gestureDownTime, now,
                        MotionEvent.ACTION_MOVE, 0, x0, py, x1, py, 2));
            } else {
                croot.dispatchTouchEvent(pinchEvent(s_gestureDownTime, now,
                        MotionEvent.ACTION_POINTER_UP, 1, x0, py, x1, py, 2));
                croot.dispatchTouchEvent(pinchEvent(s_gestureDownTime, now,
                        MotionEvent.ACTION_UP, 0, x0, py, x1, py, 1));
            }
        } catch (Throwable t) {
            NativeLog.w(TAG, "pinch dispatch threw: " + t);
        }
    }

    // ---- host trackpad multi-touch (MACAPK_TRACKPAD_MULTITOUCH, default ON) ----
    //
    // cocoa_bridge.mm opts MacApkMetalView into INDIRECT NSTouches and forwards
    // every touch frame here; TrackpadGestureMapper turns a settled two-finger
    // contact into a real multi-pointer stream (DOWN, POINTER_DOWN, MOVE,
    // POINTER_UP, UP / CANCEL) around the cursor, AOSP pointer-mode FREEFORM
    // style. The synthesized pinch rides the SAME delivery, so both streams
    // reach the app the way the host mouse does: on the window's input thread
    // (routeToLoop), through Activity.dispatchTouchEvent (which offsets into
    // content space -- the old pinch called the content root directly and so
    // landed one status-bar height low) and the renderer-direct fallback.
    //
    // =0 restores the old behaviour exactly: the view never opts into touches
    // (so no frame ever arrives here) and dispatchPinch takes its old body.
    static final boolean TRACKPAD_MULTITOUCH =
        !"0".equals(System.getenv("MACAPK_TRACKPAD_MULTITOUCH"));

    private static final TrackpadGestureMapper sTrackpad = new TrackpadGestureMapper();
    /** A host mouse button (or the synthesized scroll drag, which rides the
     *  same entry) is down: written synchronously in dispatch(), on the AppKit
     *  thread every trackpad frame also arrives on. */
    private static volatile boolean s_hostPointerDown;
    // DETECT lines. MOVE-only batches (a resting hold jitters: one MOVE per frame)
    // used to share the 64-line cap with everything else and starved the
    // `trackpad settle:` line from the owner's second attempt on; they now have a
    // small cap of their own and everything that decides a stream (engage, pointer
    // up/down, cancel, claim) has a generous one.
    private static final int TP_LOG_CAP = 256, TP_MOVE_LOG_CAP = 8;
    private static int s_tpLogged = 0, s_tpMoveLogged = 0;

    /** One trackpad frame from cocoa_bridge.mm (AppKit thread). Cursor and
     *  view size are view POINTS (Cocoa, y-up), the contacts are normalized pad
     *  positions with y already flipped to top-down. Returns ms until
     *  trackpadTick must be called (a settle is pending), or -1. */
    public long dispatchTrackpad(int n, int[] ids, int[] phases, float[] nx, float[] ny,
                                 float padWmm, float padHmm, float curX, float curY,
                                 float viewW, float viewH) {
        if (!TRACKPAD_MULTITOUCH) return -1L;
        final float scale = macapkBackingScale();
        final java.util.ArrayList<TrackpadGestureMapper.Motion> out = new java.util.ArrayList<>(4);
        long tick;
        synchronized (sTrackpad) {
            tick = sTrackpad.onFrame(android.os.SystemClock.uptimeMillis(), n, ids, phases, nx, ny,
                    padWmm, padHmm, surfaceX(curX, scale), surfaceY(curY, viewH, scale),
                    Math.round(viewW * scale), Math.round(viewH * scale),
                    trackpadMayEngage(), out::add);
        }
        deliverTrackpad(out, "frame");
        return tick;
    }

    /** The settle timer cocoa_bridge.mm scheduled from the last return value. */
    public long trackpadTick() {
        if (!TRACKPAD_MULTITOUCH) return -1L;
        final java.util.ArrayList<TrackpadGestureMapper.Motion> out = new java.util.ArrayList<>(4);
        long tick;
        synchronized (sTrackpad) {
            tick = sTrackpad.onTick(android.os.SystemClock.uptimeMillis(), trackpadMayEngage(), out::add);
        }
        deliverTrackpad(out, "settle");
        return tick;
    }

    /** macOS recognized a scroll (cocoa_bridge.mm scrollWheel: began) or a
     *  pinch on the fingers (`systemGesture`), or a host mouse BUTTON went down
     *  (dispatch() for the left button; the view's rightMouseDown: /
     *  otherMouseDown: for the others -- a physical two-finger click is macOS's
     *  default secondary click and must not become a tap). */
    public void trackpadClaim(boolean systemGesture) {
        if (!TRACKPAD_MULTITOUCH) return;
        final java.util.ArrayList<TrackpadGestureMapper.Motion> out = new java.util.ArrayList<>(1);
        synchronized (sTrackpad) { sTrackpad.claim(out::add, systemGesture); }
        deliverTrackpad(out, systemGesture ? "claim:system" : "claim:mouse");
    }

    /** A hold may start only where a tap from the host mouse would go to the
     *  Activity itself: an activity exists, no mouse/scroll stream is down, no
     *  drag-and-drop owns the pointer, and no floating window (Dialog,
     *  PopupWindow) is up -- the overlay stack's router is single-pointer, so a
     *  multi-pointer stream would fall THROUGH the dialog onto the Activity. */
    private static boolean trackpadMayEngage() {
        if (s_hostPointerDown) return false;
        if (ActivityHost.peek() == null) return false;
        if (android.view.MacApkDragSession.isActive()) return false;
        return WindowOverlayStack.isEmpty() && !WindowOverlayStack.gesturePinnedToOverlay();
    }

    /** Cocoa view POINTS (y up) -> surface pixels (y down), the transform the
     *  host-mouse path (doDispatch) applies: x scaled, y flipped against the view
     *  height and scaled. One definition for the hold's cursor and the pinch's
     *  focal point; debug/trackpad_gate.sh W5 asserts a value. */
    static float surfaceX(float xPts, float scale) {
        return xPts * scale;
    }
    static float surfaceY(float yPts, float viewH, float scale) {
        float y = yPts;
        // GATE-MUTANT(trackpad:yflip) begin -- debug/trackpad_gate.sh C5 deletes this
        y = viewH - yPts;
        // GATE-MUTANT(trackpad:yflip) end
        return y * scale;
    }

    /** The synthesized pinch as Motions: DOWN + POINTER_DOWN@1 around the focal
     *  point, MOVE with both pointers, POINTER_UP@1 + UP (the same shape AOSP's
     *  GestureConverter::handlePinch emits). Pure, so the gate can assert it. */
    static java.util.ArrayList<TrackpadGestureMapper.Motion> pinchMotions(
            int phase, float cx, float cy, float span, float viewH, float scale, long down) {
        return pinchMotions(phase, cx, cy, span, 0f, viewH, scale, down);
    }

    /** The same, with the two fingers' axis turned by `angle` (radians, CCW in
     *  the host's y-up view space; surface y runs down, so a CCW turn moves
     *  finger 1 UP the surface). angle 0 is the plain pinch. */
    static java.util.ArrayList<TrackpadGestureMapper.Motion> pinchMotions(
            int phase, float cx, float cy, float span, float angle, float viewH, float scale, long down) {
        final float px = surfaceX(cx, scale), py = surfaceY(cy, viewH, scale);
        final float half = Math.max(8f, span * scale) / 2f;
        float ox = half, oy = 0f;
        // GATE-MUTANT(trackpad:rotate) begin -- debug/trackpad_gate.sh C10 deletes this
        ox = (float) (half * Math.cos(angle));
        oy = (float) (-half * Math.sin(angle));
        // GATE-MUTANT(trackpad:rotate) end
        final int[] one = {0}, two = {0, 1};
        final float[] xs2 = {px - ox, px + ox}, ys2 = {py - oy, py + oy};
        final float[] xs1 = {px - ox}, ys1 = {py - oy};
        final java.util.ArrayList<TrackpadGestureMapper.Motion> out = new java.util.ArrayList<>(2);
        if (phase == 0) {
            out.add(new TrackpadGestureMapper.Motion(MotionEvent.ACTION_DOWN, 0, down, one, xs1, ys1));
            out.add(new TrackpadGestureMapper.Motion(MotionEvent.ACTION_POINTER_DOWN, 1, down, two, xs2, ys2));
        } else if (phase == 1) {
            out.add(new TrackpadGestureMapper.Motion(MotionEvent.ACTION_MOVE, 0, down, two, xs2, ys2));
        } else {
            out.add(new TrackpadGestureMapper.Motion(MotionEvent.ACTION_POINTER_UP, 1, down, two, xs2, ys2));
            out.add(new TrackpadGestureMapper.Motion(MotionEvent.ACTION_UP, 0, down, one, xs1, ys1));
        }
        return out;
    }

    private void trackpadPinch(int phase, float cx, float cy, float span, float angle,
                               float viewW, float viewH) {
        // A pinch the system recognized owns these fingers: end any hold first
        // (AOSP GestureConverter::handlePinch likewise starts from a clean
        // classification), so the app never sees two streams interleave.
        if (phase == 0) trackpadClaim(true);
        final float scale = macapkBackingScale();
        final long now = android.os.SystemClock.uptimeMillis();
        if (phase == 0) s_pinchDownTime = now;
        final long down = s_pinchDownTime != 0L ? s_pinchDownTime : now;
        final java.util.ArrayList<TrackpadGestureMapper.Motion> out =
            pinchMotions(phase, cx, cy, span, angle, viewH, scale, down);
        if (phase != 0 && phase != 1) s_pinchDownTime = 0L;
        deliverTrackpad(out, "pinch");
    }
    private static volatile long s_pinchDownTime = 0L;

    /** Deliver on the thread the host mouse would use, in order. */
    private void deliverTrackpad(java.util.List<TrackpadGestureMapper.Motion> evs, String why) {
        if (evs.isEmpty()) return;
        boolean moveOnly = true, engages = false;
        for (TrackpadGestureMapper.Motion m : evs) {
            if (m.action != MotionEvent.ACTION_MOVE) moveOnly = false;
            if (m.action == MotionEvent.ACTION_DOWN) engages = true;
        }
        // Whatever path delivered the DOWN (the settle tick, or a frame that
        // arrived after the settle elapsed) is logged as the settle: that is the
        // line the DETECT recipe greps for.
        final String label = engages && why.equals("frame") ? "settle" : why;
        if (moveOnly ? s_tpMoveLogged < TP_MOVE_LOG_CAP : s_tpLogged < TP_LOG_CAP) {
            if (moveOnly) s_tpMoveLogged++; else s_tpLogged++;
            NativeLog.i(TAG, "trackpad " + label + ": " + evs);
        }
        noteRealInput();
        final Activity a = ActivityHost.peek();
        if (a == null) return;
        if (routeToLoop(a)) {
            loopHandler().post(() -> deliverTrackpadNow(a, evs));
        } else {
            deliverTrackpadNow(a, evs);
        }
    }

    private static void deliverTrackpadNow(Activity a, java.util.List<TrackpadGestureMapper.Motion> evs) {
        // Input recorder, at delivery like doDispatch (the loop's FIFO order).
        if (InputRecorder.ON) InputRecorder.motions(evs);
        for (TrackpadGestureMapper.Motion m : evs) {
            MotionEvent ev = multiPointerEvent(m);
            try {
                if (deliverToTakenInputQueue(a, ev)) continue;
                a.dispatchTouchEvent(ev);
                // The renderer-direct fallback is single-pointer per call; tell
                // it about the pointer this event concerns (every pointer, for a
                // MOVE). It declines on its own when the GL queue is serviced.
                if (m.action == MotionEvent.ACTION_MOVE || m.action == MotionEvent.ACTION_CANCEL) {
                    for (int i = 0; i < m.ids.length; i++)
                        dispatchToRenderer(a, m.action, m.xs[i], m.ys[i], m.ids[i]);
                } else {
                    int i = Math.min(m.index, m.ids.length - 1);
                    dispatchToRenderer(a, m.action, m.xs[i], m.ys[i], m.ids[i]);
                }
            } catch (Throwable t) {
                NativeLog.w(TAG, "trackpad dispatch threw: " + t);
            } finally {
                ev.recycle();
            }
        }
    }

    /** Same device contract as the host mouse (touchEvent): a FINGER on the
     *  registered touchscreen, because that is what Mac.apk presents the host
     *  pointer as to every app. */
    static MotionEvent multiPointerEvent(TrackpadGestureMapper.Motion m) {
        final int count = m.ids.length;
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
            p.id = m.ids[i];
            p.toolType = MotionEvent.TOOL_TYPE_FINGER;
            props[i] = p;
            MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
            c.x = m.xs[i]; c.y = m.ys[i];
            c.pressure = 1f; c.size = 1f;
            coords[i] = c;
        }
        int action = m.action;
        // GATE-MUTANT(trackpad:index) begin -- debug/trackpad_gate.sh C4 deletes this
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)
            action |= m.index << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
        // GATE-MUTANT(trackpad:index) end
        // eventTime: when it happened (the engage burst carries the landing time,
        // GitHub #23), else now.
        long t = android.os.SystemClock.uptimeMillis();
        // GATE-MUTANT(trackpad:evtime) begin -- debug/trackpad_gate.sh C9 deletes this
        if (m.eventTime != 0L) t = m.eventTime;
        // GATE-MUTANT(trackpad:evtime) end
        return MotionEvent.obtain(m.downTime != 0L ? m.downTime : t, t, action, count, props, coords,
                0 /* metaState */, 0 /* buttonState */, 1f, 1f,
                TOUCHSCREEN_DEVICE_ID, 0 /* edgeFlags */,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0 /* flags */);
    }

    /** The window's real backing scale. This was a hardcoded `2.0f`, which is correct only on a
     *  retina display -- on a 1x external monitor every touch would land at twice its true
     *  coordinate. Native already knows the true factor; `NativeCocoa.backingScale()` surfaces it,
     *  and we fall back to 2.0 if the native side is not up yet (which is the pre-existing
     *  behaviour, so this can only improve the 1x case). */
    static float macapkBackingScale() {
        try {
            float bs = NativeCocoa.backingScale();
            if (bs >= 0.5f && bs <= 8f) return bs;
        } catch (Throwable ignored) {}
        return 2.0f;
    }

    /** The id InputManager registers our touchscreen under. It must match
     *  android.hardware.input.InputManager's TOUCHSCREEN_ID (which is private
     *  there), because InputEvent.getDevice() resolves an event's deviceId
     *  through that registry: an event stamped with an id nothing is
     *  registered under answers null and the app concludes the touch came from
     *  nowhere. It must also not be 0 -- AOSP reserves 0 for "did not come
     *  from a physical device", which is exactly why MotionEvent.obtain's own
     *  default stays 0 and the id is applied HERE instead. */
    private static final int TOUCHSCREEN_DEVICE_ID = 1;

    /** When the CURRENT host-mouse gesture went down, in uptimeMillis.
     *
     *  AOSP fixes downTime at the gesture's initial ACTION_DOWN and holds it
     *  constant for every MOVE and the UP (InputDispatcher stamps the whole
     *  stream with it). doDispatch used to pass `uptimeMillis()` for BOTH
     *  downTime and eventTime on every event, so getEventTime() -
     *  getDownTime() was always 0 -- which is the input to long-press
     *  detection (GestureDetector's LONGPRESS_TIMEOUT), fling velocity, and
     *  every double-tap window. A gesture that never appears to age cannot be
     *  classified as a long press or a fling by anything that trusts the
     *  timestamps rather than measuring wall time itself. */
    private static volatile long s_gestureDownTime = 0L;

    // Tier 2 M2: a CANVAS app's input must run on the ONE main loop
    // (serialized with its canvas traversal -- a touch handler that mutates
    // the view tree would otherwise race doFrame on the loop). A GL app's
    // input stays on the OS-main/Cocoa thread, where its engine renders and
    // where the cocos renderer-direct path (handleActionDown) is driven --
    // cocos touch handling is not thread-safe vs its render thread. A canvas
    // app is one whose active content tree has NO SurfaceView descendant.
    private static android.os.Handler sLoopHandler;
    private static android.os.Handler loopHandler() {
        if (sLoopHandler == null)
            sLoopHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        return sLoopHandler;
    }
    // ---- v1.0.444: automated input injection (no window focus required) ----
    // macOS 14+ won't deliver synthesized OS clicks/keys to our non-foreground
    // NSApplication+Metal window, so for AUTOMATED driving we inject straight into
    // the active Activity's dispatchTouchEvent/dispatchKeyEvent on the loop thread,
    // bypassing the Cocoa transform + back-gesture logic. Coords are WINDOW/SURFACE
    // pixels (top-left origin) -- the same screen space a real click lands in, i.e.
    // where the UI is DRAWN. Activity.dispatchTouchEvent then offsets by the content
    // root's top (the status-bar inset) into content-local space, so an injected tap
    // hits the on-screen target = (view-tree-dump y + statusBarH). Driven by a file
    // the harness writes (MACAPK_INJECT_FILE): lines "tap <sx> <sy>", "key <keycode>",
    // "sleep <ms>". This is a test harness, not an app path (gated on the env).
    //
    // ⚠⚠ IT CANNOT REACH A FLOATING WINDOW, AND IT FAILS SILENTLY BY HITTING
    // SOMETHING ELSE. This posts straight to Activity.dispatchTouchEvent; the real
    // Cocoa path in dispatch() below first offers the touch to
    // WindowOverlayStack.deliverTouch (see the v1.0.691 block around line 578). So a
    // Dialog / PopupWindow / dropdown / Compose ModalBottomSheet -- anything added
    // through WindowManager.addView -- is INVISIBLE here, and the tap falls THROUGH
    // it onto the Activity content underneath. MEASURED 2026-08-13: a tap on the
    // "Settings" row of Aurora Store's account bottom sheet navigated the CATALOGUE
    // BEHIND IT to a stream-browse screen, which rendered perfectly and looked like
    // a successful drive. Use debug/click.swift / debug/drag.swift (real CGEvents,
    // content-view coords = surface px / 2) for anything inside an overlay.
    // Deliberately NOT "fixed" here by calling deliverTouch: that changes shared
    // input behaviour, and the two paths differ in more than the overlay step
    // (gesture-back, held-pointer state, the renderer-direct fallback). See
    // docs/rendernode-property-change-never-invalidated-ancestors_2026-08-13.md §1
    // and the TOOLING TRAP row in docs/refuted-approaches.md.
    //
    // v1.0.1645: the caveat above now holds for CANVAS apps only (no SurfaceView
    // in the content tree), whose path is unchanged. A SurfaceView-tree app (GL,
    // Unity, cocos, libGDX) takes injectTapViaHostPath instead, which IS the
    // host-mouse sequence, overlay stack included.
    // "Canvas" here means the whole content tree, so a MIXED tree takes the
    // host path too: a View UI hosting a video or map SurfaceView (VLC, NewPipe
    // and AntennaPod playback, bobball once its game view is attached). If that
    // SurfaceView is not the one the host thread frames, nothing drains the GL
    // queue, and each tap costs INJECT_HOST_WAIT_MS before falling back to the
    // loop, with an "OS main thread drained no GL queue" warning line per tap.
    // Grep for that line before reading such an app's tap timings.
    //
    // Called from the inject-driver thread only: the SurfaceView route blocks
    // until the OS main thread has run each half of the tap.
    public static void macapkInjectTap(final float sx, final float sy) {
        if (injectTapViaHostPath(sx, sy)) return;
        injectTapToActivity(sx, sy);
    }

    /** Injected HOST tap (`hosttappx <sx> <sy>` in MACAPK_INJECT_FILE, surface
     *  pixels, top-left origin): the SAME sequence a real host mouse click
     *  runs once doDispatch has turned Cocoa points into surface pixels --
     *  dispatchSurfaceTouch, i.e. the nav-strip BACK glyph, the floating-window
     *  stack (WindowOverlayStack.deliverTouch), then the Activity -- on the
     *  thread dispatch() would pick. The plain `tap` verb's CANVAS route posts
     *  straight to Activity.dispatchTouchEvent and so can never reach a Dialog /
     *  PopupWindow / DatePicker dialog (it lands on the activity BEHIND it);
     *  `tap` is left as it is because fleet_ab replays depend on it. Added
     *  2026-09-29 (lane text-input) to drive the Mastodon date-of-birth dialog
     *  and Open Sudoku's dialogs headlessly. A SurfaceView tree already takes
     *  the host path through macapkInjectTap, so it is reused there. Unlike
     *  dispatch(), it does not call noteRealInput(): a harness tap must not
     *  arm host-URL opening. */
    public static void macapkInjectHostTap(final float sx, final float sy) {
        macapkInjectHostTap(sx, sy, 60L);
    }

    /** `hosttappx <sx> <sy> <holdMs>`: the same, holding the press for holdMs
     *  between DOWN and UP -- a long press (>= ViewConfiguration's 400 ms), e.g.
     *  NumberPicker's press-and-hold on its value, which opens its text input. */
    public static void macapkInjectHostTap(final float sx, final float sy, final long holdMs) {
        final TouchDispatcher d = s_instance;
        final Activity a0 = ActivityHost.peek();
        if (d == null || a0 == null) {
            NativeLog.w(TAG, "injectHostTap (" + sx + "," + sy + "): no dispatcher/activity; dropped");
            return;
        }
        if (injectTargetsSurfaceTree(a0)) { macapkInjectTap(sx, sy); return; }
        for (int action = 0; action <= 1; action++) {
            final int act = action;
            loopHandler().post(() -> {
                Activity a = ActivityHost.peek();
                if (act == 0) {
                    NativeLog.i(TAG, "injectTap (" + sx + "," + sy + ") -> "
                        + (a == null ? "NO ACTIVITY (dropped)" : a.getClass().getName())
                        + " via host-mouse path (hosttappx)");
                    s_gestureDownTime = android.os.SystemClock.uptimeMillis();
                }
                if (a == null) return;
                try { d.dispatchSurfaceTouch(a, act, sx, sy, sSurfaceW, sSurfaceH, true); }
                catch (Throwable thr) { NativeLog.w(TAG, "injectHostTap: " + thr); }
            });
            if (action == 0) {
                try { Thread.sleep(Math.max(0L, holdMs)); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** Injected SWIPE (`swipe x0 y0 x1 y1 [ms]` in MACAPK_INJECT_FILE): DOWN,
     *  one MOVE per ~16 ms along the line, UP, each half on the host thread
     *  through the same injectedHostTouch a tap uses, so a harness can hold a
     *  game in the state a finger drag produces (a runner changing lanes)
     *  instead of only tapping. The host path only (SurfaceView trees, the
     *  apps a drag harness targets); anything else is logged and dropped
     *  rather than half-delivered on another thread. Added 2026-09-28 for the
     *  frame-hitch harness (debug/hitch_run.sh --swipe-every). */
    public static void macapkInjectSwipe(final float x0, final float y0,
                                         final float x1, final float y1, long durMs) {
        final TouchDispatcher d = s_instance;
        final Activity a = ActivityHost.peek();
        if (d == null || a == null || android.os.Looper.macapkIsActivityThread()
                || !injectTargetsSurfaceTree(a)) {
            NativeLog.w(TAG, "injectSwipe: no SurfaceView-tree activity on the host path; dropped");
            return;
        }
        NativeLog.i(TAG, "injectSwipe (" + x0 + "," + y0 + ")->(" + x1 + "," + y1 + ") "
            + durMs + "ms -> " + a.getClass().getName());
        durMs = Math.max(32L, Math.min(durMs, 2000L));
        final int steps = (int) Math.max(2L, durMs / 16L);
        if (runOnHostThread(() -> d.injectedHostTouch(MotionEvent.ACTION_DOWN, x0, y0),
                INJECT_HOST_WAIT_MS) == HOST_NOT_RUN) return;
        for (int i = 1; i <= steps; i++) {
            try { Thread.sleep(durMs / steps); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            final float fx = x0 + (x1 - x0) * i / steps, fy = y0 + (y1 - y0) * i / steps;
            runOnHostThread(() -> d.injectedHostTouch(MotionEvent.ACTION_MOVE, fx, fy),
                INJECT_HOST_FOLLOWUP_WAIT_MS);
        }
        runOnHostThread(() -> d.injectedHostTouch(MotionEvent.ACTION_UP, x1, y1),
            INJECT_HOST_FOLLOWUP_WAIT_MS);
    }

    /** HARNESS verbs `hosttap fx fy [holdMs]` / `hostswipe fx0 fy0 fx1 fy1 [ms]`
     *  (MACAPK_INJECT_FILE). Unlike `tap`/`swipe`, which enter HERE in surface
     *  pixels, these post real left-mouse NSEvents into our own AppKit queue
     *  (NativeCocoa.postHostMouse -> CocoaPostHostMouse) at FRACTIONS of the
     *  visible window content, so the event crosses every stage a user's click
     *  crosses: window hit-testing (an NSView stacked over the render view),
     *  acceptsFirstMouse, the view's convertPoint, the points -> surface-pixel
     *  map in doDispatch, and AppKit's drag routing. A `tap` that works while
     *  the same `hosttap` does not localises the bug to exactly those stages.
     *  A hold with the same start and end is a long press. The cursor never
     *  moves and nothing reaches another app. The events DO pass through
     *  dispatch() and therefore noteRealInput(), which is the gate
     *  ActivityHost.macapkLaunchAllowed reads to allow a host URL / cross-app
     *  launch (the pre-existing injectedHostTouch path avoids it on purpose: "a
     *  harness tap must not unlock host-URL opening"). So these verbs FAIL
     *  CLOSED: they refuse to post anything unless the run has
     *  MACAPK_EXTERNAL_URLS=0 (debug/drive_app.sh sets it), and a harness that
     *  forgot cannot open the owner's browser. */
    static void macapkInjectHostGesture(float fx0, float fy0, float fx1, float fy1, long durMs) {
        macapkInjectHostGesture(fx0, fy0, fx1, fy1, durMs, 0L);
    }

    /** holdMs > 0: stay pressed at the start point for holdMs BEFORE moving --
     *  `hostswipe fx0 fy0 fx1 fy1 ms holdMs`, what drive_app.sh `drag:` writes.
     *  It is AOSP's `input draganddrop` shape (InputShellCommand.sendSwipe,
     *  android14: ACTION_DOWN, then "long press until drag start":
     *  sleep(ViewConfiguration.getLongPressTimeout()), then the MOVEs, then UP),
     *  so the gesture has the emulator harness's shape (that this hold reaches a
     *  source that starts its drag from onLongClick is UNPROVEN: Anuto starts its
     *  drag from onTouch DOWN, which needs no hold). holdMs == 0 is the old
     *  gesture exactly. */
    static void macapkInjectHostGesture(float fx0, float fy0, float fx1, float fy1, long durMs,
                                        long holdMs) {
        // GATE-MUTANT(touchinput:hosttap-guard) begin -- debug/touchinput_gate.sh C8
        if (!"0".equals(System.getenv("MACAPK_EXTERNAL_URLS"))) {
            NativeLog.w(TAG, "hosttap refused: set MACAPK_EXTERNAL_URLS=0 (a real-path click "
                + "counts as user input and would let the app open the host browser)");
            return;
        }
        // GATE-MUTANT(touchinput:hosttap-guard) end
        durMs = Math.max(16L, Math.min(durMs, 5000L));
        NativeLog.i(TAG, "injectHost (" + fx0 + "," + fy0 + ")->(" + fx1 + "," + fy1 + ") "
            + durMs + "ms -> " + (ActivityHost.peek() == null ? "NO ACTIVITY"
                : ActivityHost.peek().getClass().getName()));
        if (!NativeCocoa.postHostMouse(0, fx0, fy0)) {
            NativeLog.w(TAG, "injectHost: no window; dropped");
            return;
        }
        if (holdMs > 0) {
            try { Thread.sleep(Math.min(holdMs, 5000L)); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        final boolean moves = fx0 != fx1 || fy0 != fy1;
        final int steps = moves ? (int) Math.max(2L, durMs / 16L) : 1;
        for (int i = 1; i <= steps; i++) {
            try { Thread.sleep(durMs / steps); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            if (moves) NativeCocoa.postHostMouse(2, fx0 + (fx1 - fx0) * i / steps,
                                                    fy0 + (fy1 - fy0) * i / steps);
        }
        NativeCocoa.postHostMouse(1, fx1, fy1);
    }

    /** The pre-v1.0.1645 injected tap, kept byte-for-byte for canvas apps:
     *  both halves posted to the main loop, straight to
     *  Activity.dispatchTouchEvent. */
    private static void injectTapToActivity(final float sx, final float sy) {
        loopHandler().post(() -> {
            Activity a = ActivityHost.peek();
            // Say WHICH activity received this tap, and say it even when there
            // is none. Until v1.0.1057 this method logged NOTHING, so a tap
            // that landed and a tap that was swallowed were indistinguishable
            // in the log -- and the obvious grep does not help, because
            // "dispatch action=" comes from doDispatch, the COCOA mouse path,
            // which an injected tap never enters.
            //
            // That cost a real diagnosis: a Reddit login probe fired its tap
            // while the splash Activity was still on top, ~2,800 log lines
            // before the screen under test existed, and the run was read as
            // "the tap did nothing" rather than "the tap hit the wrong
            // screen". The activity name is the whole fix: it makes the
            // mistake visible in one line.
            NativeLog.i(TAG, "injectTap (" + sx + "," + sy + ") -> " +
                    (a == null ? "NO ACTIVITY (dropped)"
                               : a.getClass().getName()));
            if (a == null) return;
            long t = android.os.SystemClock.uptimeMillis();
            // downTime is the DOWN's timestamp for both halves of the tap, so
            // the pair reads as one 40ms gesture rather than two instants.
            try { a.dispatchTouchEvent(touchEvent(t, t, 0 /*DOWN*/, sx, sy, 0)); }
            catch (Throwable thr) { NativeLog.w(TAG, "injectTap down: " + thr); }
            try { a.dispatchTouchEvent(touchEvent(t, t + 40, 1 /*UP*/, sx, sy, 0)); }
            catch (Throwable thr) { NativeLog.w(TAG, "injectTap up: " + thr); }
        });
    }

    /** How long one half of an injected tap may wait for the OS main thread to
     *  drain Handler's GL queue before we conclude this surface is not being
     *  framed. A drawing surface drains it every frame, so even a 10 fps app
     *  answers in ~100ms; a second means the frame callback is not running. */
    private static final long INJECT_HOST_WAIT_MS = 1000L;
    /** How long the UP (and every later half of an injected gesture) waits
     *  for the host thread. Longer than INJECT_HOST_WAIT_MS on purpose: once
     *  the DOWN has run there, this surface is PROVEN to drain the queue, so a
     *  slow frame (a level load or shader compile the touch itself started) is
     *  a hitch, not a dead surface. Falling back after 1s put the UP on the
     *  loop thread while the host thread renders, which is the cocos
     *  thread-safety race this path exists to avoid. Matches the in-flight
     *  bound runOnHostThread already allows a claimed runnable. */
    private static final long INJECT_HOST_FOLLOWUP_WAIT_MS = 10_000L;
    /** Gap between the injected DOWN finishing and the UP being queued. The
     *  pre-v1.0.1645 injector encoded the same 40ms as the UP's eventTime. It
     *  has to be real time on this path, not just a timestamp: an engine that
     *  POLLS touch state once per frame (libGDX's isTouched/justTouched) cannot
     *  see a finger that went down and up inside one frame, and a real finger
     *  never produces that.
     *
     *  ⚠ NOT what the emulator side of the fleet A/B does. `adb shell input
     *  tap` (android14-release InputShellCommand.sendTap, :381-386) gives the
     *  DOWN and the UP the SAME downTime and eventTime and does not sleep; the
     *  only gap between them is the WAIT_FOR_FINISH round trip. So a per-frame
     *  poller can react to our tap and miss the emulator's. That asymmetry is
     *  in the A/B instrument, not in the app: read a "reacts on Mac.apk, not on
     *  the emulator" touch row with it in mind. (Also older and separate: the
     *  UP built by touchEvent() carries pressure 1f, where input tap's is 0f.) */
    private static final long INJECT_TAP_HOLD_MS = 40L;
    private static final int HOST_RAN = 0, HOST_NOT_RUN = 1, HOST_PENDING = 2;

    /** True when `a`'s host input runs on the AppKit thread: the same
     *  predicate routeToLoop uses (hostInputTree), read off the loop thread
     *  exactly as dispatch() already does. Such an app takes its host input on
     *  the AppKit thread, so injected input should too. */
    private static boolean injectTargetsSurfaceTree(Activity a) {
        if (a == null) return false;
        try {
            View root = a.macapkContentView();
            return root != null && hostInputTree(root);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Deliver an injected tap to a SurfaceView-tree app through the SAME code
     *  a host mouse click runs, on the SAME thread. Returns false (nothing
     *  delivered) when the app is not a SurfaceView tree or the OS main thread
     *  never picked the DOWN up; the caller then uses injectTapToActivity.
     *
     *  Why: a host click on such an app is dispatch() -> routeToLoop() false ->
     *  doDispatch() synchronously on the AppKit thread, which is also where the
     *  engine renders (cocos' renderer-direct path is not thread-safe against
     *  its render). The canvas injector instead posted to the main LOOP and
     *  skipped the overlay stack and the renderer-direct fallback, so an
     *  injected tap on a GL app was a different event on a different thread
     *  from the click it stands in for.
     *
     *  AOSP's own injector makes the same promise. `adb shell input tap`
     *  (android14-release InputShellCommand.sendTap, :381-386) injects DOWN
     *  then UP through InputManagerGlobal.injectInputEvent with
     *  INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH (:182-183) -- the InputDispatcher
     *  pipeline a hardware touch uses, and the UP is not injected until the
     *  DOWN has been delivered. The latch in runOnHostThread is that
     *  WAIT_FOR_FINISH.
     *
     *  Java has no direct handle on the AppKit thread; Handler's GL queue
     *  (enqueueGlThread) is the one queue drained there, every frame, by
     *  SurfaceView.macapkDrawFrame and GLSurfaceView's renderer branch. That
     *  runs the touch between two renders, which is also where a real
     *  mouseDown lands. If nothing drains it (a stalled or non-framing surface,
     *  or MACAPK_GLQ_DRAIN=0 with a setRenderer GLSurfaceView), the tap falls
     *  back rather than being lost. */
    private static boolean injectTapViaHostPath(final float sx, final float sy) {
        // Never block the main loop waiting on work it might itself have to
        // drain; only the inject driver's own thread should get here.
        if (android.os.Looper.macapkIsActivityThread()) return false;
        final TouchDispatcher d = s_instance;
        if (d == null) return false;
        Activity a = ActivityHost.peek();
        if (a == null) return false;   // the canvas path logs the drop
        if (!injectTargetsSurfaceTree(a)) return false;

        // Set ONLY when the DOWN ran and found no activity. Inverted from a
        // "delivered" flag on purpose: a DOWN still running on the host thread
        // (HOST_PENDING) has not written anything yet, and must still get its
        // UP, or the engine sees a press that never lifts.
        final java.util.concurrent.atomic.AtomicBoolean downDropped =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        int down = runOnHostThread(() -> {
            if (!d.injectedHostTouch(MotionEvent.ACTION_DOWN, sx, sy)) downDropped.set(true);
        }, INJECT_HOST_WAIT_MS);
        if (down == HOST_NOT_RUN) {
            // Nothing was delivered, so the whole tap can still take the
            // canvas route intact. Say so for EVERY such tap: the canvas route's
            // own line does not name its route, and a tap that silently used
            // the loop would be read as "the host path was exercised".
            NativeLog.w(TAG, "injectTap (" + sx + "," + sy + "): OS main thread drained no"
                + " GL queue in " + INJECT_HOST_WAIT_MS + "ms (surface not framing?) --"
                + " falling back to Activity.dispatchTouchEvent on the loop");
            return false;
        }
        if (down == HOST_PENDING) {
            // Still inside guest code after 11s. The UP is queued behind it on
            // the same FIFO, single-thread drain, so it cannot overtake it.
            NativeLog.w(TAG, "injectTap (" + sx + "," + sy + "): DOWN still running on"
                + " the host thread; queueing the UP behind it");
        }
        // The DOWN ran but found no activity (logged there): an UP now would
        // be half a gesture delivered to whatever appears next.
        if (downDropped.get()) return true;
        try { Thread.sleep(INJECT_TAP_HOLD_MS); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (runOnHostThread(() -> d.injectedHostTouch(MotionEvent.ACTION_UP, sx, sy),
                INJECT_HOST_FOLLOWUP_WAIT_MS) == HOST_NOT_RUN) {
            // The DOWN landed, so the UP must too: a finger that never lifts
            // leaves every press-and-hold engine stuck. Wrong thread beats no
            // UP, but only after the long bound: this surface already drained
            // the DOWN, so a short silence is a render hitch.
            NativeLog.w(TAG, "injectTap UP: host thread drained nothing for "
                + INJECT_HOST_FOLLOWUP_WAIT_MS + "ms after the DOWN;"
                + " delivering the UP on the loop");
            loopHandler().post(() -> { d.injectedHostTouch(MotionEvent.ACTION_UP, sx, sy); });
        }
        return true;
    }

    /** Run `r` on the OS main thread via Handler's GL queue and wait for it.
     *  Exactly one of {the drain, this caller} claims the runnable, so a
     *  timeout can never race a late drain into running it twice.
     *
     *  Three outcomes, not two. HOST_PENDING means the drain CLAIMED `r` but
     *  it has not finished inside our bound (guest code running long on the
     *  host thread): it will still run, so the caller must treat it as
     *  delivered. Reporting that as HOST_RAN let the tap read a result `r` had
     *  not written yet, and a DOWN that finished late then never got its UP. */
    private static int runOnHostThread(final Runnable r, final long waitMs) {
        final java.util.concurrent.atomic.AtomicBoolean claimed =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.CountDownLatch done =
            new java.util.concurrent.CountDownLatch(1);
        android.os.Handler.enqueueGlThread(() -> {
            if (!claimed.compareAndSet(false, true)) return;   // caller gave up
            try { r.run(); }
            catch (Throwable t) { NativeLog.w(TAG, "inject host dispatch: " + t); }
            finally { done.countDown(); }
        });
        try {
            if (done.await(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS))
                return HOST_RAN;
            if (claimed.compareAndSet(false, true)) return HOST_NOT_RUN;
            // Claimed by the drain and still inside guest code: it WILL run,
            // so wait for it rather than reporting a tap that is in flight.
            return done.await(10, java.util.concurrent.TimeUnit.SECONDS)
                ? HOST_RAN : HOST_PENDING;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            if (claimed.compareAndSet(false, true)) return HOST_NOT_RUN;
            return done.getCount() == 0 ? HOST_RAN : HOST_PENDING;
        }
    }

    /** One half of an injected tap, on the host thread: what doDispatch does
     *  after its Cocoa-point transform, minus noteRealInput (a harness tap must
     *  not unlock host-URL opening; see s_userInputSeen). The coordinates are
     *  already surface pixels, top-left origin, which is the space doDispatch
     *  transforms into. The trace lines are uncapped because a harness run has
     *  only a handful of taps and each one is the evidence for a verdict. */
    private boolean injectedHostTouch(int action, float sx, float sy) {
        Activity active = ActivityHost.peek();
        if (action == MotionEvent.ACTION_DOWN) {
            NativeLog.i(TAG, "injectTap (" + sx + "," + sy + ") -> " +
                    (active == null ? "NO ACTIVITY (dropped)"
                                    : active.getClass().getName())
                    + " via host-mouse path");
        }
        if (active == null) return false;
        if (action == MotionEvent.ACTION_DOWN)
            s_gestureDownTime = android.os.SystemClock.uptimeMillis();
        dispatchSurfaceTouch(active, action, sx, sy, sSurfaceW, sSurfaceH, true);
        return true;
    }
    /** Inject literal TEXT, character by character, through the SAME path a
     *  host keystroke takes.
     *
     *  ⚠ `key <keycode>` CANNOT TYPE, which is not obvious. macapkInjectKey
     *  calls Activity.dispatchKeyEvent directly, and every text-entry route --
     *  TextView.macapkHandleSoftKey and the bound InputConnection's commitText
     *  -- lives in doDispatchKey, the Cocoa entry point. So an injected key
     *  reaches a game's input system but never a text field, and a harness
     *  driving a search box sees its typing silently vanish. (Measured by the
     *  F-Droid drive harness, which could tap but not search.)
     *
     *  So this goes through doDispatchKey() with a real unicodeChar, exactly
     *  as a keyboard does, rather than adding a third text path that would
     *  then have to be kept in step with the other two.
     *
     *  But NOT through dispatchKey(), the host entry point, for two reasons
     *  (v1.0.1645, once the driver started reaching pure-GL apps):
     *  - dispatchKey() calls noteRealInput(), and a harness keystroke must not
     *    arm host-URL opening (see s_userInputSeen; drive_app.sh does not set
     *    MACAPK_EXTERNAL_URLS=0).
     *  - dispatchKey() runs doDispatchKey on the CALLER's thread when
     *    routeToLoop() is false, which for a SurfaceView tree meant the inject
     *    driver's own thread: neither the AppKit thread a real key uses there
     *    nor the loop. So route like dispatchKey does, but explicitly: a
     *    SurfaceView tree on the host thread (runOnHostThread, the same hand-off
     *    injected taps use), everything else on the loop.
     *
     *  Deliberately keeps the per-character gap: an app's TextWatcher and its
     *  recomposition run between keystrokes on a real keyboard too, and typing
     *  a whole string instantly has produced dropped characters in search
     *  fields that debounce. */
    public static void macapkInjectText(final String text) {
        if (text == null || text.isEmpty()) return;
        final TouchDispatcher d = macapkInstance();
        if (d == null) { NativeLog.w(TAG, "injectText: no dispatcher"); return; }
        boolean host = !android.os.Looper.macapkIsActivityThread()
                && injectTargetsSurfaceTree(ActivityHost.peek());
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            // keyCode 0: this is character input, not a physical key. The
            // soft-key path keys off unicodeChar, and a wrong keyCode would
            // make a game's dispatchKeyEvent see a bogus button press.
            final Runnable down = () -> d.doDispatchKey(KeyEvent.ACTION_DOWN, 0, c, 0);
            final Runnable up   = () -> d.doDispatchKey(KeyEvent.ACTION_UP, 0, c, 0);
            if (host) {
                if (runOnHostThread(down, INJECT_HOST_WAIT_MS) == HOST_NOT_RUN) {
                    // Nothing drains the GL queue: the rest of this string goes
                    // to the loop, so it is not charged 1s per character.
                    host = false;
                    NativeLog.w(TAG, "injectText: OS main thread drained no GL queue in "
                        + INJECT_HOST_WAIT_MS + "ms -- typing the rest on the loop");
                    loopHandler().post(down);
                    loopHandler().post(up);
                } else if (runOnHostThread(up, INJECT_HOST_FOLLOWUP_WAIT_MS) == HOST_NOT_RUN) {
                    // The DOWN landed on the host thread; the UP must land too.
                    host = false;
                    NativeLog.w(TAG, "injectText: host thread went quiet after a key DOWN"
                        + " -- delivering the UP and the rest on the loop");
                    loopHandler().post(up);
                }
            } else {
                loopHandler().post(down);
                loopHandler().post(up);
            }
            try { Thread.sleep(60); } catch (InterruptedException ig) { return; }
        }
        NativeLog.i(TAG, "injectText: typed " + text.length() + " char(s)");
    }

    /** Injected HOST key (`hostkey <keyCode>` in MACAPK_INJECT_FILE): a
     *  physical key with no character, DOWN then UP, through doDispatchKey --
     *  the entry a real Cocoa keyDown takes after host_keymap.h (Return arrives
     *  as keyCode 66 with no unicode), so ENTER reaches the focused field's
     *  soft-input route exactly as a typed Return does. The `key` verb stays
     *  the HARDWARE path (dispatchKeyToFocusedWindow), which a Mac keyboard
     *  never takes while a field holds input. Thread choice as injectText.
     *  Added 2026-09-29 (lane text-input) to commit a typed NumberPicker value
     *  headlessly. */
    public static void macapkInjectHostKey(final int keyCode) {
        final TouchDispatcher d = macapkInstance();
        if (d == null) { NativeLog.w(TAG, "injectHostKey: no dispatcher"); return; }
        final boolean host = !android.os.Looper.macapkIsActivityThread()
                && injectTargetsSurfaceTree(ActivityHost.peek());
        final Runnable down = () -> d.doDispatchKey(KeyEvent.ACTION_DOWN, keyCode, 0, 0);
        final Runnable up   = () -> d.doDispatchKey(KeyEvent.ACTION_UP, keyCode, 0, 0);
        if (host && runOnHostThread(down, INJECT_HOST_WAIT_MS) != HOST_NOT_RUN) {
            if (runOnHostThread(up, INJECT_HOST_FOLLOWUP_WAIT_MS) == HOST_NOT_RUN) {
                loopHandler().post(up);
            }
            return;
        }
        loopHandler().post(down);
        loopHandler().post(up);
    }

    /** One half of a host key (`hostkeydown` / `hostkeyup <keyCode>`): the
     *  same entry and thread choice as macapkInjectHostKey, so a harness can
     *  HOLD a key for as long as it sleeps between the two -- what moving a
     *  game character with the arrows needs (2026-10-02, Arrow Keys as D-pad). */
    public static void macapkInjectHostKeyAction(final int action, final int keyCode) {
        final TouchDispatcher d = macapkInstance();
        if (d == null) { NativeLog.w(TAG, "injectHostKey: no dispatcher"); return; }
        final boolean host = !android.os.Looper.macapkIsActivityThread()
                && injectTargetsSurfaceTree(ActivityHost.peek());
        final Runnable r = () -> d.doDispatchKey(action, keyCode, 0, 0);
        if (host && runOnHostThread(r, INJECT_HOST_WAIT_MS) != HOST_NOT_RUN) return;
        loopHandler().post(r);
    }

    public static void macapkInjectKey(final int keyCode) {
        loopHandler().post(() -> {
            Activity a = ActivityHost.peek();
            if (a == null) return;
            long t = android.os.SystemClock.uptimeMillis();
            try { dispatchKeyToFocusedWindow(a, keyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0, 0)); }
            catch (Throwable thr) { NativeLog.w(TAG, "injectKey down: " + thr); }
            try { dispatchKeyToFocusedWindow(a, keyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0, 0)); }
            catch (Throwable thr) { NativeLog.w(TAG, "injectKey up: " + thr); }
        });
    }
    // ---- key-binding injection (the Controls menu) -------------------------
    //
    // Press-and-HOLD, not tap. A gas pedal has to stay pressed for as long as
    // the key is held, so keyDown and keyUp inject SEPARATE down and up events
    // rather than macapkInjectTap's immediate down-then-up pair (which is
    // useless for driving). Several keys can be held at once -- gas plus steer
    // is the normal case -- so each held binding owns a pointer id and the
    // events are real multi-pointer MotionEvents: ACTION_POINTER_DOWN/UP with
    // the pointer index packed into the action, the same shape AOSP's
    // InputDispatcher delivers to a multi-touch app.
    //
    // Coordinates are SURFACE pixels, already denormalised by the caller
    // against the current content rect (see KeyBindProfiles for why bindings
    // are stored normalised).
    // The live surface size in PIXELS, published by SurfaceView.macapkSurfaceChanged.
    // That is the authoritative source (the native side's actual drawable size);
    // deriving it from NativeCocoa.phoneWidth()/phoneHeight() instead means
    // points-vs-pixels drift, which has already caused a real viewport bug once
    // (v1.0.742, HCR's "renders in the bottom-left corner").
    private static volatile int sSurfaceW = 0, sSurfaceH = 0;
    public static void macapkNoteSurfaceSize(int wPx, int hPx) {
        if (wPx > 0 && hPx > 0) { sSurfaceW = wPx; sSurfaceH = hPx; }
    }

    private static final int     MAX_HELD  = 8;
    private static final int[]   sHeldKey  = new int[MAX_HELD];
    // A pointer id is STABLE for the lifetime of that pointer (AOSP contract:
    // getPointerId(i) identifies the finger, the slot index does not). Slots
    // compact on release, so the id cannot be the slot index -- otherwise
    // releasing brake would silently renumber a still-held gas pointer and the
    // engine would see the held finger vanish and a new one appear.
    private static final int[]   sHeldId   = new int[MAX_HELD];
    private static final float[] sHeldX    = new float[MAX_HELD];
    private static final float[] sHeldY    = new float[MAX_HELD];
    private static int           sHeldCount = 0;

    /** Lowest id not currently in use, so ids stay small and reused. */
    private static int allocPointerId() {
        for (int id = 0; id < MAX_HELD; id++) {
            boolean taken = false;
            for (int i = 0; i < sHeldCount; i++) if (sHeldId[i] == id) { taken = true; break; }
            if (!taken) return id;
        }
        return 0;
    }

    private static int heldIndexOf(int keyCode) {
        for (int i = 0; i < sHeldCount; i++) if (sHeldKey[i] == keyCode) return i;
        return -1;
    }

    /** When the first bound key of the current held set went down. Same
     *  contract as s_gestureDownTime, for the keyboard-driven touch stream. */
    private static volatile long sHeldDownTime = 0L;

    /** Build an N-pointer event over the currently-held slots. `action` should
     *  already carry its pointer index for the POINTER_DOWN/UP forms.
     *
     *  The source argument used to be 0. That worked only because this file's
     *  obtain() silently rewrote a 0 source to SOURCE_TOUCHSCREEN; AOSP stores
     *  what the caller passed, so the source has to be named here or every
     *  keybind touch becomes SOURCE_UNKNOWN and a pointer-class-filtering
     *  engine ignores it. size is 1f for the same reason PointerCoords no
     *  longer defaults it: a real finger contact reports a size. */
    private static MotionEvent heldEvent(int action, int count) {
        long t = android.os.SystemClock.uptimeMillis();
        long down = sHeldDownTime != 0L ? sHeldDownTime : t;
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[]     crds  = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
            p.id = sHeldId[i];
            p.toolType = MotionEvent.TOOL_TYPE_FINGER;
            props[i] = p;
            MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
            c.x = sHeldX[i];
            c.y = sHeldY[i];
            c.pressure = 1f;
            c.size = 1f;
            crds[i] = c;
        }
        return MotionEvent.obtain(down, t, action, count, props, crds,
                                  0, 0, 1f, 1f, TOUCHSCREEN_DEVICE_ID, 0,
                                  android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    /** `cx`/`cy` are the coordinates of the pointer this event is ABOUT, which
     *  for a POINTER_DOWN/UP is not pointer 0 -- the renderer-direct fallback is
     *  single-touch, so it must be told the point that actually changed. */
    private static void deliverHeld(MotionEvent ev, float cx, float cy, int cid) {
        Activity a = ActivityHost.peek();
        if (a == null) { ev.recycle(); return; }
        if (deliverToTakenInputQueue(a, ev)) return;
        try { a.dispatchTouchEvent(ev); }
        catch (Throwable t) { NativeLog.w(TAG, "keybind dispatch: " + t); }
        // Engines whose onTouchEvent queues to a GLThread that never drains
        // need the renderer-direct path too -- same reasoning as doDispatch
        // (and the same v1.0.1639 suppression when the queue IS drained).
        // v1.0.833: `cid` is the id of the pointer this event is about. Passing
        // it is what makes simultaneous bound keys work on cocos2d-x: without
        // it every held pointer collapsed onto id 0 and the POINTER_ forms were
        // dropped entirely.
        try { dispatchToRenderer(a, ev.getActionMasked(), cx, cy, cid); }
        catch (Throwable ignored) { }
        finally { ev.recycle(); }
    }

    /** A bound key went DOWN: start holding a pointer at (px,py) in surface
     *  pixels. Idempotent -- key auto-repeat must not add a second pointer. */
    public static void macapkKeyBindDown(int keyCode, float px, float py) {
        MotionEvent ev;
        int downId;
        // Build under the lock, DISPATCH outside it: dispatchTouchEvent runs
        // guest code, which can re-enter this class, and holding a static lock
        // across a guest call is how a deadlock gets built by accident.
        synchronized (TouchDispatcher.class) {
            if (heldIndexOf(keyCode) >= 0) return;      // already held (auto-repeat)
            if (sHeldCount >= MAX_HELD) return;
            noteRealInput();                      // a REAL user action
            int id  = allocPointerId();   // BEFORE sHeldCount++ so it scans the live set
            int idx = sHeldCount++;
            // The gesture starts at the FIRST finger; later POINTER_DOWNs keep
            // the same downTime, exactly as AOSP's InputDispatcher does.
            if (idx == 0) sHeldDownTime = android.os.SystemClock.uptimeMillis();
            sHeldKey[idx] = keyCode; sHeldId[idx] = id;
            sHeldX[idx] = px; sHeldY[idx] = py;
            int action = (idx == 0)
                ? MotionEvent.ACTION_DOWN
                : (MotionEvent.ACTION_POINTER_DOWN
                   | (idx << MotionEvent.ACTION_POINTER_INDEX_SHIFT));
            ev = heldEvent(action, sHeldCount);
            downId = id;
        }
        deliverHeld(ev, px, py, downId);
    }

    /** A bound key went UP: release its pointer. */
    public static void macapkKeyBindUp(int keyCode) {
        MotionEvent ev;
        float cx, cy;
        int cid;
        synchronized (TouchDispatcher.class) {
            int idx = heldIndexOf(keyCode);
            if (idx < 0) return;
            int action = (sHeldCount == 1)
                ? MotionEvent.ACTION_UP
                : (MotionEvent.ACTION_POINTER_UP
                   | (idx << MotionEvent.ACTION_POINTER_INDEX_SHIFT));
            // The UP event must still DESCRIBE the pointer being lifted, so
            // build it before compacting the slot array.
            cx = sHeldX[idx]; cy = sHeldY[idx]; cid = sHeldId[idx];
            ev = heldEvent(action, sHeldCount);
            for (int i = idx; i < sHeldCount - 1; i++) {
                sHeldKey[i] = sHeldKey[i + 1];
                sHeldId[i]  = sHeldId[i + 1];
                sHeldX[i]   = sHeldX[i + 1];
                sHeldY[i]   = sHeldY[i + 1];
            }
            sHeldCount--;
            if (sHeldCount == 0) sHeldDownTime = 0L;   // gesture over
        }
        deliverHeld(ev, cx, cy, cid);
    }

    /** Drop every held pointer without delivering UPs. For teardown paths
     *  (activity finished) where the guest is going away anyway. */
    public static void macapkKeyBindReset() {
        synchronized (TouchDispatcher.class) { sHeldCount = 0; sHeldDownTime = 0L; }
    }

    // ---- the inject driver (MACAPK_INJECT_FILE) ----
    //
    // v1.0.1645: STARTED FROM Boot, right after this dispatcher is registered,
    // instead of from ViewCanvasDriver.doFrame. doFrame only runs for an app
    // with a Canvas-drawn tree, so every pure-GL app (libGDX, Arc) never
    // started the driver at all: the fleet A/B of 2026-09-24 found inject.txt
    // still holding every replayed tap and zero "inject-driver watching" lines
    // for ShatteredPD, Unciv, Mindustry, Retrowars, Feudal Tactics and more
    // (docs/fleet-emulator-ab_2026-09-25.md). The ViewCanvasDriver call stays
    // as a harmless no-op. The driver still does not TOUCH the file until an
    // activity exists, so a tap written early waits for a screen to land on,
    // as it effectively did when the first canvas frame started the driver.
    // A CAS, not a volatile check-then-set: Boot and ViewCanvasDriver.doFrame
    // both call this, on different threads, and two drivers in one process
    // would share one claim file (see macapkConsumeInjectFile).
    private static final java.util.concurrent.atomic.AtomicBoolean sInjectDriverStarted =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    public static void macapkStartInjectDriver() {
        final String path = System.getenv("MACAPK_INJECT_FILE");
        if (path == null) return;
        if (!sInjectDriverStarted.compareAndSet(false, true)) return;
        Thread th = new Thread(() -> {
            try {
                while (ActivityHost.peek() == null) Thread.sleep(150);
            } catch (InterruptedException ie) { return; }
            // Logged only once an activity exists: debug/fdroidtest/fdroid_drive.sh
            // gates its first tap on this line, and that gate meant "taps will
            // land" when the first canvas frame was what started the driver.
            java.nio.file.Path f = java.nio.file.Paths.get(path);
            // A RECORDING (first line `inputrec ...`, InputRecorder) named directly by
            // MACAPK_INJECT_FILE is replayed ONCE and never consumed: the claim below
            // renames and deletes, which would destroy the owner's file. Prepend any
            // line (a `#` comment) to make it an ordinary consumable inject file, as
            // debug/drive_app.sh replay does.
            if (InputRecorder.isRecordingFile(f)) {
                NativeLog.i(TAG, "inject-driver replaying recording " + path
                    + " read-only (not consumed; the driver stops after it)");
                final InputRecorder.Replay[] once = new InputRecorder.Replay[1];
                try {
                    macapkRunInjectLines(java.nio.file.Files.readAllLines(f), once, ReplaySink.HOST);
                } catch (Throwable t) { NativeLog.w(TAG, "inject-driver: recording replay: " + t); }
                macapkEndInjectSession(once, "end of input");
                return;
            }
            NativeLog.i(TAG, "inject-driver watching " + path);
            boolean warnedFallback = false;
            // A recording's session spans batches (a claim can split one): it ends at
            // `inputrec-end`, a new header, an ordinary (un-timed) line, or when
            // nothing has been appended for IDLE_END_MS.
            final InputRecorder.Replay[] session = new InputRecorder.Replay[1];
            long idleSince = System.nanoTime();
            while (true) {
                try {
                    java.util.List<String> lines = macapkConsumeInjectFile(f);
                    if (lines == null) {
                        // Could not claim the file by rename (read-only dir, a
                        // filesystem without rename). The old read-then-truncate
                        // is lossy but beats dropping the harness entirely.
                        if (!warnedFallback) {
                            warnedFallback = true;
                            NativeLog.w(TAG, "inject-driver: cannot rename " + f
                                + "; using read-then-truncate (lines appended during"
                                + " the read can be lost)");
                        }
                        lines = java.nio.file.Files.readAllLines(f);
                        new java.io.FileWriter(f.toFile(), false).close();
                    }
                    macapkRunInjectLines(lines, session, ReplaySink.HOST);
                    if (!lines.isEmpty()) idleSince = System.nanoTime();
                    else if (session[0] != null
                            && System.nanoTime() - idleSince > INJECT_SESSION_IDLE_END_MS * 1_000_000L)
                        macapkEndInjectSession(session, "end of input (nothing appended for "
                            + INJECT_SESSION_IDLE_END_MS + " ms)");
                    Thread.sleep(150);
                } catch (Throwable t) { NativeLog.w(TAG, "inject-driver: " + t); try { Thread.sleep(300); } catch (Throwable ig) {} }
            }
        }, "macapk-inject-driver");
        th.setDaemon(true);
        th.start();
    }

    /** The verb -> route table of the inject file, in ONE place, so an offline gate
     *  can assert it without running a callee (debug/touchinput_gate.sh D14).
     *  Returns the route name, "reject" for a line that names a real route with
     *  arguments it can never honour, or null for an unknown verb / too few args.
     *
     *  `hosttap fx fy [ms]` and `hosttappx sx sy [ms]` are TWO routes, and they
     *  have DIFFERENT verbs on purpose. `hosttap` = FRACTIONS of the window
     *  content posted as a real NSEvent (macapkInjectHostGesture, the path a
     *  user's click takes); `hosttappx` = surface PIXELS driven through
     *  dispatchSurfaceTouch (macapkInjectHostTap, the only way to reach a
     *  Dialog / PopupWindow headlessly). Until v1.0.1659 the pixel form was also
     *  spelled `hosttap` and was matched first, so drive_app's `hosttap:<fx>,<fy>`
     *  landed as pixels at (0,0) and the NSEvent branch was unreachable for
     *  three commits, with no symptom but a tap in the wrong place. A fraction
     *  outside [0,1] is therefore REJECTED (it is a pixel coordinate written with
     *  the wrong verb), never posted: a mis-spelt verb must fail loudly. */
    static String macapkInjectRoute(String[] p) {
        final String v = p[0];
        final int n = p.length;
        if (v.equals("tap") && n >= 3) return "tap";
        if (v.equals("hosttappx" /*GATE-SUB(touchinput:hosttap-route)*/) && n >= 3) return "hosttappx";
        if (v.equals("swipe") && n >= 5) return "swipe";
        if (v.equals("hosttap") && n >= 3) return fractionsInRange(p, 2) ? "hosttap" : "reject";
        if (v.equals("hostswipe") && n >= 5) return fractionsInRange(p, 4) ? "hostswipe" : "reject";
        if (v.equals("key") && n >= 2) return "key";
        if (v.equals("hostkey") && n >= 2) return "hostkey";
        if (v.equals("hostkeydown") && n >= 2) return "hostkeydown";
        if (v.equals("hostkeyup") && n >= 2) return "hostkeyup";
        if (v.equals("text") && n >= 2) return "text";
        if (v.equals("sleep") && n >= 2) return "sleep";
        // Host trackpad (cocoa_bridge.mm CocoaDebugTrackpad): the NSTouch frame /
        // magnify body a real trackpad runs, entered below the NSEvent decode.
        if (v.equals("trackpad") && n >= 3) return fractionsInRange(p, 2) ? "trackpad" : "reject";
        if (v.equals("magnify") && n >= 5) return fractionsInRange(p, 2) ? "magnify" : "reject";
        if (v.equals("rotate") && n >= 5) return fractionsInRange(p, 2) ? "rotate" : "reject";
        if (v.equals("hostscroll") && n >= 5) return fractionsInRange(p, 2) ? "hostscroll" : "reject";
        if (v.equals("hostwheel") && n >= 4) return fractionsInRange(p, 2) ? "hostwheel" : "reject";
        if (v.equals("close") || v.equals("quit") || v.equals("terminate")) return "quit";
        // Input-recorder events (InputRecorder; docs/input-recorder_2026-10-01.md):
        // the host entry points themselves, replayed with the recorded arguments.
        if (v.equals("ptr") && n >= 4) return "ptr";
        if (v.equals("keyev") && n >= 5) return "keyev";
        if (v.equals("mptr") && n >= 4) return "mptr";
        return null;
    }

    private static boolean fractionsInRange(String[] p, int count) {
        try {
            for (int i = 1; i <= count; i++) {
                float f = Float.parseFloat(p[i]);
                if (!(f >= 0f && f <= 1f)) return false;
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Run ONE inject-file line (already trimmed, non-empty, not a comment). The
     *  settle pause between lines stays in the driver loop. Parse errors throw,
     *  as they always did, to the driver's catch-all. */
    static void macapkDispatchInjectLine(String ln) throws InterruptedException {
        macapkDispatchInjectLine(ln, ReplaySink.HOST);
    }
    static void macapkDispatchInjectLine(String ln, InputRecorder.Sink sink) throws InterruptedException {
        final String[] p = ln.split("\\s+");
        final String r = macapkInjectRoute(p);
        if (r == null) {
            NativeLog.w(TAG, "inject-driver: ignored '" + KeyLogPolicy.injectLine(ln) + "' (unknown verb or too few arguments)");
            return;
        }
        switch (r) {
            case "tap":
                macapkInjectTap(Float.parseFloat(p[1]), Float.parseFloat(p[2]));
                break;
            case "hosttappx":
                if (p.length >= 4) {
                    macapkInjectHostTap(Float.parseFloat(p[1]), Float.parseFloat(p[2]),
                                        Long.parseLong(p[3]));
                } else {
                    macapkInjectHostTap(Float.parseFloat(p[1]), Float.parseFloat(p[2]));
                }
                break;
            case "swipe":
                macapkInjectSwipe(Float.parseFloat(p[1]), Float.parseFloat(p[2]),
                    Float.parseFloat(p[3]), Float.parseFloat(p[4]),
                    p.length >= 6 ? Long.parseLong(p[5]) : 150L);
                break;
            case "hosttap":
                macapkInjectHostGesture(Float.parseFloat(p[1]), Float.parseFloat(p[2]),
                    Float.parseFloat(p[1]), Float.parseFloat(p[2]),
                    p.length >= 4 ? Long.parseLong(p[3]) : INJECT_TAP_HOLD_MS);
                break;
            case "hostswipe":
                macapkInjectHostGesture(Float.parseFloat(p[1]), Float.parseFloat(p[2]),
                    Float.parseFloat(p[3]), Float.parseFloat(p[4]),
                    p.length >= 6 ? Long.parseLong(p[5]) : 300L,
                    p.length >= 7 ? Long.parseLong(p[6]) : 0L);
                break;
            case "key":
                macapkInjectKey(Integer.parseInt(p[1]));
                break;
            case "hostkey":
                macapkInjectHostKey(Integer.parseInt(p[1]));
                break;
            case "hostkeydown":
                macapkInjectHostKeyAction(KeyEvent.ACTION_DOWN, Integer.parseInt(p[1]));
                break;
            case "hostkeyup":
                macapkInjectHostKeyAction(KeyEvent.ACTION_UP, Integer.parseInt(p[1]));
                break;
            case "text":
                // Everything after "text " is the literal string, spaces
                // included -- so split on the FIRST space only, not on every space.
                macapkInjectText(ln.substring(ln.indexOf(' ') + 1));
                break;
            case "sleep":
                Thread.sleep(Long.parseLong(p[1]));
                break;
            case "trackpad": {
                // trackpad <cursor fx> <cursor fy> [<id>:<phase>:<nx>:<ny> ...]
                // phase as cocoa_bridge.mm flattens NSTouchPhase (0 began, 1 moved,
                // 2 stationary, 3 ended, 4 cancelled); nx/ny are pad fractions,
                // top-left origin. No contact = an empty frame.
                float[] a = new float[2 + 4 * (p.length - 3)];
                a[0] = Float.parseFloat(p[1]); a[1] = Float.parseFloat(p[2]);
                for (int i = 3; i < p.length; i++) {
                    String[] c = p[i].split(":");
                    for (int j = 0; j < 4; j++) a[2 + 4 * (i - 3) + j] = Float.parseFloat(c[j]);
                }
                if (!NativeCocoa.debugTrackpad(0, a))
                    NativeLog.w(TAG, "inject-driver: trackpad frame found no input view");
                break;
            }
            case "hostwheel": {
                // hostwheel <fx> <fy> <dyLines> [dxLines]: one classic mouse-wheel
                // notch through the host scrollWheel: body (CocoaDebugTrackpad kind 2).
                float[] a = { Float.parseFloat(p[1]), Float.parseFloat(p[2]),
                              Float.parseFloat(p[3]), p.length >= 5 ? Float.parseFloat(p[4]) : 0f };
                if (!NativeCocoa.debugTrackpad(2, a))
                    NativeLog.w(TAG, "inject-driver: hostwheel found no input view");
                break;
            }
            case "magnify":
                // magnify <cursor fx> <cursor fy> <phase 0|1|2> <magnification>
                if (!NativeCocoa.debugTrackpad(1, new float[]{ Float.parseFloat(p[1]),
                        Float.parseFloat(p[2]), Float.parseFloat(p[3]), Float.parseFloat(p[4]) }))
                    NativeLog.w(TAG, "inject-driver: magnify found no input view");
                break;
            case "rotate":
                // rotate <cursor fx> <cursor fy> <phase 0|1|2> <degrees, CCW +>
                if (!NativeCocoa.debugTrackpad(3, new float[]{ Float.parseFloat(p[1]),
                        Float.parseFloat(p[2]), Float.parseFloat(p[3]), Float.parseFloat(p[4]) }))
                    NativeLog.w(TAG, "inject-driver: rotate found no input view");
                break;
            case "hostscroll": {
                // hostscroll <fx> <fy> <phase 0|1|2> <dyPoints> [dxPoints]: one TRACKPAD
                // scroll event through the host scrollWheel: body (kind 4).
                float[] a = { Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3]),
                              Float.parseFloat(p[4]), p.length >= 6 ? Float.parseFloat(p[5]) : 0f };
                if (!NativeCocoa.debugTrackpad(4, a))
                    NativeLog.w(TAG, "inject-driver: hostscroll found no input view");
                break;
            }
            case "quit":
                // The three ways a user leaves a Mac app, each on its real
                // AppKit path (debug/closelat.sh).
                NativeCocoa.debugQuitGesture(p[0]);
                break;
            case "ptr":
                sink.pointer(Integer.parseInt(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3]));
                break;
            case "keyev":
                sink.key(Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                         Integer.parseInt(p[3]), Integer.parseInt(p[4]));
                break;
            case "mptr": {
                // mptr <action> <index> <id>:<x>:<y> ...   (surface px)
                final int k = p.length - 3;
                final int[] ids = new int[k];
                final float[] xs = new float[k], ys = new float[k];
                for (int i = 0; i < k; i++) {
                    String[] c = p[3 + i].split(":");
                    ids[i] = Integer.parseInt(c[0]);
                    xs[i] = Float.parseFloat(c[1]);
                    ys[i] = Float.parseFloat(c[2]);
                }
                final int act = Integer.parseInt(p[1]);
                // A recorded downTime belongs to the recording's clock; the stream
                // gets a fresh one at its DOWN, held for every later motion of it,
                // which is what the mapper does live (Motion.downTime).
                if (act == MotionEvent.ACTION_DOWN) s_replayTpDown = android.os.SystemClock.uptimeMillis();
                final java.util.List<TrackpadGestureMapper.Motion> one = new java.util.ArrayList<>(1);
                one.add(new TrackpadGestureMapper.Motion(act, Integer.parseInt(p[2]),
                        s_replayTpDown, ids, xs, ys));
                sink.motions(one);
                break;
            }
            default: // "reject"
                NativeLog.w(TAG, "inject-driver: refused '" + ln + "': " + p[0]
                    + " takes FRACTIONS in [0,1] of the window content; surface pixels use"
                    + " hosttappx (a pixel coordinate written with the fraction verb)");
                break;
        }
    }

    /** Run one batch of inject-file lines: the driver loop body, shared by the
     *  watched file and a read-only recording.
     *
     *  Ordinary lines run as they always did, each followed by the 250 ms settle.
     *  A RECORDING (InputRecorder: an `inputrec` header, then `+<ms> <line>`
     *  events) is replayed on its own clock instead: each event waits until <ms>
     *  after the header was read and gets NO settle, because the recorded gaps ARE
     *  the timing (a drag's moves are ~8-16 ms apart; a 250 ms settle between them
     *  would turn the drag into a long press). A refused recording (wrong window,
     *  wrong app, no MACAPK_EXTERNAL_URLS=0) skips every remaining event of it,
     *  loudly. The session ends at `inputrec-end`, a new header, or an ordinary
     *  (un-timed) line -- NOT at the end of the batch, because one claim can split a
     *  recording and its second half must stay on the recording's clock (the
     *  caller ends it: macapkEndInjectSession). */
    static void macapkRunInjectLines(java.util.List<String> lines, InputRecorder.Replay[] session,
                                     InputRecorder.Sink sink) throws InterruptedException {
        for (String raw : lines) {
            String ln = raw.trim();
            if (ln.isEmpty() || ln.startsWith("#")) continue;
            long at = -1L;
            if (ln.charAt(0) == '+') {
                final int sp = ln.indexOf(' ');
                try { at = Long.parseLong(sp < 0 ? ln.substring(1) : ln.substring(1, sp)); }
                catch (NumberFormatException e) {
                    NativeLog.w(TAG, "inject-driver: ignored '" + KeyLogPolicy.injectLine(ln) + "' (bad +<ms> timing)");
                    continue;
                }
                if (sp < 0) continue;
                ln = ln.substring(sp + 1).trim();
                if (ln.isEmpty()) continue;
            }
            if (ln.equals("inputrec") || ln.startsWith("inputrec ")) {
                if (session[0] != null) session[0].done("superseded by a new header");
                session[0] = InputRecorder.Replay.begin(ln, sink);
                continue;
            }
            InputRecorder.Replay rs = session[0];
            if (rs != null && at < 0) {
                // An ordinary line (a harness step appended after a recording) is not
                // part of it: end the session, and run the line as ordinary lines run.
                macapkEndInjectSession(session, "ordinary line '" + KeyLogPolicy.injectLine(ln) + "' follows");
                rs = null;
            }
            if (rs == null) {
                if (at >= 0) NativeLog.w(TAG, "inject-driver: '+" + at + "' timing with no"
                    + " inputrec header; running '" + KeyLogPolicy.injectLine(ln) + "' now");
                macapkDispatchInjectLine(ln, sink);
                Thread.sleep(250); // let each event settle + recompose
                continue;
            }
            if (rs.refused) { rs.skipped++; continue; }
            if (at >= 0) rs.waitUntil(at);
            final int h = rs.handle(ln);
            if (h == 2) { session[0] = null; continue; }
            if (h == 1) continue;
            try { macapkDispatchInjectLine(ln, sink); rs.events++; }
            catch (RuntimeException e) {
                rs.skipped++;
                NativeLog.w(TAG, "inputrec: bad line '" + KeyLogPolicy.injectLine(ln) + "': " + e);
            }
        }
    }

    /** Ends the recording session in progress, if any, and says how it went (the
     *  `inputrec: replay done` line debug/drive_app.sh replaywait waits for). */
    static void macapkEndInjectSession(InputRecorder.Replay[] session, String why) {
        if (session[0] == null) return;
        session[0].done(why);
        session[0] = null;
    }

    /** A recording whose claim stops growing is over (a crashed or force-quit
     *  session has no `inputrec-end`). */
    private static final long INJECT_SESSION_IDLE_END_MS = 1500L;

    /** downTime of the replayed trackpad stream (see the `mptr` case). */
    private static volatile long s_replayTpDown = 0L;

    /** Where a replayed recording's events enter: the SAME entry points the host
     *  calls (dispatch / dispatchKey / deliverTrackpad), with the recorded
     *  arguments, on the thread the host would have called them from. A
     *  SurfaceView-tree app takes its host input synchronously on the AppKit
     *  thread (routeToLoop false), so its events go there through the GL queue
     *  exactly as injectTapViaHostPath does; every other tree is called from the
     *  driver thread, and dispatch() itself posts it to the loop as it does for a
     *  real click. Because the real entry points run, a replay also calls
     *  noteRealInput(), which is why Replay.begin refuses without
     *  MACAPK_EXTERNAL_URLS=0. */
    static class ReplaySink implements InputRecorder.Sink {
        static final ReplaySink HOST = new ReplaySink();

        void run(Runnable r) {
            final Activity a = ActivityHost.peek();
            if (!android.os.Looper.macapkIsActivityThread() && injectTargetsSurfaceTree(a)) {
                if (runOnHostThread(r, INJECT_HOST_FOLLOWUP_WAIT_MS) != HOST_NOT_RUN) return;
                NativeLog.w(TAG, "inputrec: the host thread drained nothing for "
                    + INJECT_HOST_FOLLOWUP_WAIT_MS + "ms; delivering from the driver thread");
            }
            r.run();
        }

        @Override public void pointer(int action, float sx, float sy) {
            final TouchDispatcher d = s_instance;
            final float[] w = windowPts();
            if (d == null || w == null) {
                NativeLog.w(TAG, "inputrec: ptr dropped (no dispatcher or window)");
                return;
            }
            // dispatch() maps Cocoa points to surface px as x*scale, (viewH-y)*scale;
            // invert it with the CURRENT scale so the app receives the recorded px.
            final float scale = macapkBackingScale();
            final float vw = w[0], vh = w[1];
            final float x = sx / scale, y = vh - sy / scale;
            run(() -> d.dispatch(action, x, y, vw, vh));
        }

        @Override public void key(int action, int keyCode, int unicode, int meta) {
            final TouchDispatcher d = s_instance;
            if (d == null) { NativeLog.w(TAG, "inputrec: keyev dropped (no dispatcher)"); return; }
            run(() -> d.dispatchKey(action, keyCode, unicode, meta));
        }

        @Override public void motions(java.util.List<TrackpadGestureMapper.Motion> m) {
            final TouchDispatcher d = s_instance;
            if (d == null) { NativeLog.w(TAG, "inputrec: mptr dropped (no dispatcher)"); return; }
            run(() -> d.deliverTrackpad(m, "replay"));
        }

        @Override public float[] windowPts() {
            try {
                int w = NativeCocoa.phoneWidth(), h = NativeCocoa.phoneHeight();
                return w > 0 && h > 0 ? new float[]{ w, h } : null;
            } catch (Throwable t) {
                return null;
            }
        }

        @Override public float scale() { return macapkBackingScale(); }

        @Override public String packageName() {
            try {
                Activity a = ActivityHost.peek();
                return a == null ? null : a.getPackageName();
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /** How long a claimed inject file must stay the same size before it is
     *  read for the last time and deleted. See macapkConsumeInjectFile. */
    private static final long INJECT_CLAIM_SETTLE_MS = 40L;

    /** Take every complete line out of the inject file, losing none, and leave
     *  an EMPTY file behind. Returns an empty list when there is nothing to
     *  read, or null when the file cannot be claimed (the caller falls back).
     *
     *  The v1.0.444 driver did readAllLines() and then truncated. A line a
     *  harness appended between those two calls was destroyed unread, and a
     *  harness appends with `echo ... >>` while the driver polls, so the window
     *  is real: debug/injectfile_gate.sh reproduces the loss on demand, with
     *  dense back-to-back appends. Whether it is what cost the 2026-09-24 fleet
     *  a tap on tic (tap 2) and pk2 (tap 1) is only SUSPECTED. Those taps were
     *  4s apart against a sub-millisecond window, and both apps are SDL
     *  (SDLSurface) apps. If either still logs fewer injectTap lines than taps
     *  written after this change, the file race is ruled out (this consumer
     *  cannot lose a line; gate [1]) and the SDL input path is the suspect.
     *
     *  Claim by RENAME instead. rename(2) is atomic, so every append either
     *  opened the path before the rename (its bytes land in the claimed file,
     *  which we read) or after it (they land in a fresh file at the path, which
     *  the next poll reads). The remaining gap is an appender that OPENED the
     *  old file before the rename and WRITES after our read; a shell `echo >>`
     *  does open-write-close in microseconds, so we wait for the claimed file
     *  to stop growing before the final read.
     *
     *  The claim is named per PROCESS (`<path>.consuming.<pid>`), and a claim
     *  left unread is RECOVERED, in order, before anything new is claimed. A
     *  fixed name lost lines two ways: a second driver on the same inject file
     *  (a restarted process; OpenTTD's run.log in the 2026-09-24 fleet has two
     *  "inject-driver watching" lines) renamed onto the first one's unread
     *  claim, and a read that threw after the rename left a claim the next
     *  poll's rename then overwrote. rename(2) replaces its target, so
     *  "never overwrite an unread claim" has to be arranged, not requested.
     *  Only this process's claim and claims of processes that no longer exist
     *  are recovered; a live sibling's claim is its own to read.
     *
     *  An empty file is recreated at the path because every harness treats
     *  "the file became empty" as "the driver consumed it" ([ -s ] in
     *  fdroid_drive.sh, the fleet's "inject.txt is 0 bytes"), and CREATE_NEW
     *  cannot clobber a file a harness already recreated in the meantime.
     *
     *  Depends on nothing but java.nio (plus one NativeLog.w, which
     *  MACAPK_LOG_LEVEL=7 silences before its native print) so an offline gate
     *  can drive it (debug/injectfile_gate.sh). */
    static java.util.List<String> macapkConsumeInjectFile(java.nio.file.Path f)
            throws java.io.IOException, InterruptedException {
        final long myPid = ProcessHandle.current().pid();
        final java.nio.file.Path claimed = injectClaimPath(f, myPid);
        java.util.List<String> stranded = recoverInjectClaims(f, myPid, claimed);
        if (stranded != null) return stranded;
        if (!java.nio.file.Files.isRegularFile(f) || java.nio.file.Files.size(f) == 0)
            return java.util.Collections.emptyList();
        try {
            java.nio.file.Files.move(f, claimed, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.NoSuchFileException gone) {
            return java.util.Collections.emptyList();   // replaced mid-check; next poll
        } catch (java.io.IOException | UnsupportedOperationException cannot) {
            return null;
        }
        try { java.nio.file.Files.createFile(f); }
        catch (java.nio.file.FileAlreadyExistsException raced) { /* a harness got there first */ }
        catch (java.io.IOException ignored) { /* harness recreates on its next write */ }
        long size = -1;
        for (int i = 0; i < 25; i++) {           // bounded: at most ~1s of settling
            Thread.sleep(INJECT_CLAIM_SETTLE_MS);
            long now = java.nio.file.Files.size(claimed);
            if (now == size) break;
            size = now;
        }
        return readInjectClaim(claimed);
    }

    static java.nio.file.Path injectClaimPath(java.nio.file.Path f, long pid) {
        return f.resolveSibling(f.getFileName() + ".consuming." + pid);
    }

    /** Read and delete a claim. Delete only after a successful read: if the
     *  read throws, the claim stays where recoverInjectClaims finds it. */
    private static java.util.List<String> readInjectClaim(java.nio.file.Path claimed)
            throws java.io.IOException {
        byte[] bytes = java.nio.file.Files.readAllBytes(claimed);
        java.nio.file.Files.deleteIfExists(claimed);
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String ln : text.split("\\r?\\n")) out.add(ln);
        return out;
    }

    /** Lines of a claim nobody read, or null when there is none. Our own claim
     *  comes first (it predates anything still at the path). A dead process's
     *  claim is first renamed onto ours, so two live drivers racing to recover
     *  it cannot both read it: the loser's rename finds no source. */
    private static java.util.List<String> recoverInjectClaims(java.nio.file.Path f,
            long myPid, java.nio.file.Path claimed) throws java.io.IOException {
        if (java.nio.file.Files.exists(claimed)) return readInjectClaim(claimed);
        java.nio.file.Path dir = f.toAbsolutePath().getParent();
        if (dir == null) return null;
        final String prefix = f.getFileName() + ".consuming.";
        java.util.List<java.nio.file.Path> dead = new java.util.ArrayList<>();
        try (java.nio.file.DirectoryStream<java.nio.file.Path> ds =
                 java.nio.file.Files.newDirectoryStream(dir,
                     p -> p.getFileName().toString().startsWith(prefix))) {
            for (java.nio.file.Path p : ds) {
                long pid;
                try { pid = Long.parseLong(p.getFileName().toString().substring(prefix.length())); }
                catch (NumberFormatException notOurs) { continue; }
                if (pid == myPid) continue;
                boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
                if (!alive) dead.add(p);
            }
        } catch (java.nio.file.NoSuchFileException noDir) {
            return null;
        }
        for (java.nio.file.Path p : dead) {
            try {
                java.nio.file.Files.move(p, claimed, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.NoSuchFileException taken) {
                continue;                        // another driver recovered it
            }
            NativeLog.w(TAG, "inject-driver: recovered unread claim " + p.getFileName()
                + " of an exited process");
            return readInjectClaim(claimed);
        }
        return null;
    }
    private static boolean routeToLoop(Activity active) {
        if (!android.os.Looper.MACAPK_REAL_LOOPER) return false;
        // v1.0.939: identity, not name -- see Looper.macapkIsActivityThread.
        if (android.os.Looper.macapkIsActivityThread()) return false; // already on loop
        if (active == null) return false;
        View root = active.macapkContentView();
        // An activity with NO content yet is one being created right now: the
        // launch pushed it (ActivityHost.performLaunch) and its onCreate is
        // running, or about to, on the loop. Its input belongs on the loop too
        // -- AOSP binds a window's input receiver to its own looper, and nothing
        // reaches a window before it exists -- so it must queue behind onCreate,
        // not run beside it. Delivering it directly ran the app's key handling on
        // the host thread INSIDE another thread's onCreate: the Enter release
        // that follows a search submit reached NoNonsense Notes' ActivitySearch
        // mid-create, AppCompat attached its delegate from the wrong thread, and
        // onCreate threw "AppCompat has already installed itself into the
        // Window" (or lost android.R.id.content), leaving the search screen
        // blank. The OS-main exception (hostInputTree) needs a tree to name.
        if (root == null) return true;
        return !hostInputTree(root);   // UI-thread input unless OS-main renders
    }

    /** MACAPK_INPUT_UI_THREAD (default ON, 2026-09-30): which content trees
     *  keep their host input on the AppKit thread.
     *
     *  AOSP delivers every input event on the thread that owns the window:
     *  ViewRootImpl.setView binds `new WindowInputEventReceiver(inputChannel,
     *  Looper.myLooper())` (android14-release ViewRootImpl.java:1454), and
     *  InputEventReceiver's looper is "the looper to use when invoking
     *  callbacks" (InputEventReceiver.java:67). A SurfaceView changes nothing
     *  about that: its javadoc exists so "a secondary thread can render into
     *  the screen" (SurfaceView.java:90), the RENDER moves, the input does not.
     *
     *  The old predicate, "the tree holds ANY SurfaceView", sent every such
     *  app's clicks to the AppKit thread. That is only right when the AppKit
     *  thread is the app's GL thread, i.e. when our macapkDrawFrame drives the
     *  renderer (a GLSurfaceView Renderer, a reflected renderer; the cocos
     *  renderer-direct fallback lives in exactly those trees). For a view whose
     *  pixels come from the app's own threads (a native EGL window surface, a
     *  video or camera producer) it ran the app's click handlers off its main
     *  thread: Organic Maps' search/menu buttons threw "Cannot invoke setValue
     *  on a background thread" and its map Download button ABORTED the process
     *  in Storage::GetNodeAttrs, CHECK(m_threadChecker.CalledOnOriginalThread()).
     *  It also raced the view traversal, which runs on the loop for every tree
     *  (ViewCanvasDriver.macapkDrawFrame posts doFrame there).
     *
     *  =0 restores "any SurfaceView" byte for byte. */
    static final boolean INPUT_UI_THREAD =
        !"0".equals(System.getenv("MACAPK_INPUT_UI_THREAD"));

    /** True when `root`'s host input belongs on the AppKit thread (see
     *  INPUT_UI_THREAD). The ONE predicate for real host input (routeToLoop)
     *  and for injected input (injectTargetsSurfaceTree), so a harness tap
     *  keeps travelling the same thread a real click does. */
    static boolean hostInputTree(View root) {
        return INPUT_UI_THREAD ? treeHasOsMainRenderedSurface(root)
                               : treeHasSurfaceView(root);
    }
    private static boolean treeHasOsMainRenderedSurface(View v) {
        if (v instanceof android.view.SurfaceView
                && ((android.view.SurfaceView) v).macapkRendersOnOsMain()) return true;
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0, n = vg.getChildCount(); i < n; i++)
                if (treeHasOsMainRenderedSurface(vg.getChildAt(i))) return true;
        }
        return false;
    }
    private static boolean treeHasSurfaceView(View v) {
        if (v instanceof android.view.SurfaceView) return true;
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0, n = vg.getChildCount(); i < n; i++)
                if (treeHasSurfaceView(vg.getChildAt(i))) return true;
        }
        return false;
    }

    // (v1.0.867: the left-edge gesture-back swipe state was deleted with the
    // gesture pill -- 3-button nav has no edge-back. StatusBarDriver
    // .edgeBackInsetPx is likewise unused now; it is kept only so a future
    // gesture-nav mode does not have to re-derive AOSP's 20dp inset.)
    // Tappable nav-strip BACK button (v1.0.406): armed on a DOWN inside the
    // back-chevron hit rect, fires on the matching UP if still inside.
    private boolean _navBackArmed;

    /** v1.0.1642: deliver a key the way AOSP's input dispatch does. The
     *  FOCUSED window gets it: an open Dialog or focusable PopupWindow before
     *  the activity (WindowOverlayStack.deliverKey). For the activity window,
     *  DecorView.dispatchKeyEvent (android14 :366-401) hands it to the window's
     *  Window.Callback, which is the Activity itself unless AppCompat wrapped
     *  it -- AppCompatWindowCallback is what closes an ActionMode or collapses
     *  an expanded toolbar SearchView on BACK before the activity sees it. Was
     *  `active.dispatchKeyEvent(ev)` at every site: BACK skipped open popups
     *  and AppCompat, and went straight to onBackPressed. */
    static boolean dispatchKeyToFocusedWindow(Activity active, KeyEvent ev) {
        // Predictive back (API 33+), AOSP ViewRootImpl.NativePreImeInputStage:
        // for an app that opted in (android:enableOnBackInvokedCallback, or the
        // API 36 default), BACK goes to the FOCUSED window's
        // OnBackInvokedDispatcher BEFORE the view tree or the IME. See
        // macapkPredictiveBack below. The stage stays DORMANT (legacy key) for a
        // window whose views cannot reach its dispatcher yet -- see
        // macapkViewBackWired. Gate MACAPK_BACK_INVOKED (default ON); =0 skips
        // this block entirely, which is the pre-change path.
        if (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_BACK
                && android.window.WindowOnBackInvokedDispatcher.MACAPK_BACK_INVOKED) {
            // The stage sees the key with its AOSP repeat count (see
            // macapkBackRepeat); LEGACY / NO_CALLBACK hand the view tree the
            // ORIGINAL event, so a legacy app's key is byte-for-byte unchanged.
            KeyEvent staged = macapkBackRepeat(ev);
            int r = macapkPredictiveBack(active, staged);
            if (r == android.window.WindowOnBackInvokedDispatcher.FINISH_HANDLED) return true;
            if (r == android.window.WindowOnBackInvokedDispatcher.FINISH_NOT_HANDLED) return false;
            if (r == android.window.WindowOnBackInvokedDispatcher.FORWARD) {
                ev = staged;
                if (ev.getAction() == KeyEvent.ACTION_UP && !ev.isCanceled()) {
                    // android16 VRI :7750-7753: "forward a cancelled event so that
                    // following stages cancel their back logic" -- Activity.onKeyUp,
                    // Dialog.onKeyUp and PopupDecorView all ignore a canceled UP. A
                    // copy, because AOSP mutates the queued event and ours may be
                    // shared by the caller.
                    ev = KeyEvent.changeFlags(ev, ev.getFlags() | KeyEvent.FLAG_CANCELED);
                }
            }
            // MACAPK_KEY_LEGACY / MACAPK_KEY_NO_CALLBACK / FORWARD: the view tree
            // gets the key exactly as before.
        }
        if (WindowOverlayStack.deliverKey(ev)) return true;
        if (active == null) return false;
        if (deliverToTakenInputQueue(active, ev)) return true;
        android.view.Window w = null;
        try { w = active.getWindow(); } catch (Throwable ignored) { }
        android.view.Window.Callback cb = w != null ? w.getCallback() : null;
        return cb != null ? cb.dispatchKeyEvent(ev) : active.dispatchKeyEvent(ev);
    }

    /** AOSP ViewRootImpl's NativePreImeInputStage / NativePostImeInputStage: a
     *  window whose input queue was taken (Window.takeInputQueue, which
     *  NativeActivity and so every Unreal GameActivity does) gets its events in
     *  that native queue FIRST, and only an event the native side finishes
     *  UNHANDLED continues to the view hierarchy (here: the activity's normal
     *  dispatch, run on the queue's thread when AInputQueue_finishEvent says
     *  so). Returns false, changing nothing, for every other window. Keys go
     *  without the pre-IME pass (predispatch=false): there is no IME stage to
     *  run between the two. */
    static boolean deliverToTakenInputQueue(final Activity a, android.view.InputEvent ev) {
        if (a == null || ev == null) return false;
        android.view.InputQueue q;
        try {
            android.view.Window w = a.getWindow();
            q = w != null ? w.macapkTakenInputQueue() : null;
        } catch (Throwable t) { return false; }
        if (q == null || q.getNativePtr() == 0) return false;
        final android.view.InputEvent copy =
            ev instanceof MotionEvent me ? MotionEvent.obtain(me) : ev;
        q.sendInputEvent(copy, a, false, (token, handled) -> {
            if (handled) return;
            try {
                if (copy instanceof KeyEvent ke) {
                    a.dispatchKeyEvent(ke);
                } else if (copy instanceof MotionEvent me) {
                    if (isTouchDispatch(me)) {
                        a.dispatchTouchEvent(me);
                    } else {
                        a.dispatchGenericMotionEvent(me);
                    }
                }
            } catch (Throwable t) {
                NativeLog.w(TAG, "unhandled native-queue event dispatch threw: " + t);
            }
        });
        return true;
    }

    /** MACAPK_KEY_SHORTCUTS (default ON; =0 skips the stage below, the
     *  pre-change key path). */
    static final boolean MACAPK_KEY_SHORTCUTS = !"0".equals(System.getenv("MACAPK_KEY_SHORTCUTS"));

    /** The eligibility predicate AOSP's ViewRootImpl.ViewPostImeInputStage
     *  .processKeyEvent applies before trying dispatchKeyShortcutEvent
     *  (android14-release ViewRootImpl.java:6972-6984):
     *  <pre>
     *  // If a modifier is held, try to interpret the key as a shortcut.
     *  if (event.getAction() == KeyEvent.ACTION_DOWN
     *          && !KeyEvent.metaStateHasNoModifiers(event.getMetaState())
     *          && event.getRepeatCount() == 0
     *          && !KeyEvent.isModifierKey(event.getKeyCode())
     *          && groupNavigationDirection == 0) {
     *      if (mView.dispatchKeyShortcutEvent(event)) return FINISH_HANDLED;
     *  </pre>
     *  groupNavigationDirection is non-zero exactly for Ctrl+TAB and
     *  Ctrl+Shift+TAB (:6960-6968). Factored out (v1.0.1658, review issue 1)
     *  so WindowOverlayStack.deliverKey can run the SAME check on a focused
     *  overlay's own DecorView, the way AOSP's per-window ViewRootImpl would --
     *  before this split, the predicate lived only inside
     *  macapkKeyShortcutStage, which only ever ran against the ACTIVITY
     *  window, so a focused Dialog/PopupWindow's shortcut stage was
     *  unreachable (deliverKey's own `return true` beat it to the punch every
     *  time an overlay was focused; see docs/clipboard-keyboard-shortcuts_2026-09-28.md). */
    public static boolean macapkShortcutEligible(KeyEvent ev) {
        if (!MACAPK_KEY_SHORTCUTS || ev == null) return false;
        if (ev.getAction() != KeyEvent.ACTION_DOWN
                || KeyEvent.metaStateHasNoModifiers(ev.getMetaState())
                || ev.getRepeatCount() != 0
                || KeyEvent.isModifierKey(ev.getKeyCode())) return false;
        if (ev.getKeyCode() == KeyEvent.KEYCODE_TAB
                && (KeyEvent.metaStateHasModifiers(ev.getMetaState(), KeyEvent.META_CTRL_ON)
                    || KeyEvent.metaStateHasModifiers(ev.getMetaState(),
                           KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON))) return false;
        return true;
    }

    /** mView is the FOCUSED window's DecorView, whose dispatchKeyShortcutEvent
     *  hands the event to the window's Window.Callback (DecorView.java:404-425):
     *  the Dialog or the Activity (or AppCompat's wrapper of it), which offers
     *  it to the focused view (TextView.onKeyShortcut: Ctrl+A/X/C/V) and then
     *  to its own onKeyShortcut. This stage did not exist here, so a Ctrl
     *  shortcut -- and, with MACAPK_CMD_AS_CTRL, every Cmd+C/V/X/A -- ended at
     *  a view tree that had no reason to handle it.
     *
     *  Activity-window only: a focused overlay's own shortcut stage runs
     *  in-place inside WindowOverlayStack.deliverKey (v1.0.1658), since that is
     *  the window AOSP's stage would run against; this method is reached only
     *  when dispatchKeyToFocusedWindow fell through to the activity (no
     *  focusable overlay). Public for debug/keyshortcut_gate.sh. */
    public static boolean macapkKeyShortcutStage(Activity active, KeyEvent ev) {
        if (!macapkShortcutEligible(ev)) return false;
        if (active == null) return false;
        android.view.Window w = null;
        try { w = active.getWindow(); } catch (Throwable ignored) { }
        android.view.Window.Callback cb = w != null ? w.getCallback() : null;
        return cb != null ? cb.dispatchKeyShortcutEvent(ev) : active.dispatchKeyShortcutEvent(ev);
    }

    /** Runs a predictive-back callback on the Android main thread, where AOSP
     *  runs it (the window's UI thread; the gesture wrapper posts to
     *  Handler.getMain()). A BACK on a SurfaceView/GL tree is routed on the
     *  AppKit thread (routeToLoop), and an androidx OnBackPressedCallback there
     *  pops fragments and touches views, which must not happen off the owner
     *  thread. Inline when already on it, or when no main thread exists yet
     *  (Looper.macapkHasMainThread: an offline harness), the same rule the
     *  framework's other internal posters follow. Posts keep DOWN/UP order. */
    private static final java.util.concurrent.Executor BACK_INVOKER = r -> {
        // A callback that throws is an APP bug, and AOSP crashes the app with it
        // (it runs straight out of the input stage). Mac.apk logs it with its
        // stack and carries on -- the same thing Looper.loop does for every
        // posted message, so the inline and the posted case now agree. What it
        // must NOT do is hand the same BACK to the legacy path afterwards: the
        // app's handler already ran (or half-ran), and a second delivery would
        // run it again through Activity.onKeyUp -> onBackPressed.
        Runnable guarded = () -> {
            try { r.run(); }
            catch (Throwable t) {
                android.util.Log.e(TAG, "predictive back: an OnBackInvokedCallback threw"
                        + " (AOSP would crash the app here); the BACK stays consumed", t);
            }
        };
        if (!android.os.Looper.macapkHasMainThread() || ActivityHost.macapkOnMainThread()) guarded.run();
        else loopHandler().post(guarded);
    };

    /** AOSP's InputReader gives a HELD key's repeated DOWNs a growing
     *  getRepeatCount() (KeyEvent: "> 0 if this is after the initial down"),
     *  and ViewRootImpl.doOnBackKeyEvent relies on it: onBackStarted only when
     *  getRepeatCount() == 0, "1 means the button continues to be pressed
     *  (long press)" (android14 VRI :6555-6563). Mac.apk's host key
     *  bridge sends every macOS autorepeat keyDown (holding Esc) as a fresh
     *  DOWN with repeat 0, so without this an OnBackAnimationCallback got
     *  onBackStarted once per autorepeat, and androidx.activity 1.8+ answers a
     *  second onBackStarted by cancelling and restarting the in-progress
     *  callback. Scoped to the predictive-back stage: a BACK DOWN that arrives
     *  while an earlier BACK DOWN has had no UP is a repeat. The 2 s bound only
     *  catches a LOST UP (macOS's initial-repeat and repeat delays are well
     *  under 2 s even at the slowest Keyboard settings), so a new press after
     *  one is not mistaken for a repeat forever. An event that already carries
     *  a repeat count keeps it. The general fix -- carrying NSEvent.isARepeat
     *  through the host key bridge for EVERY key -- is outside this path. */
    private static boolean sBackDownOutstanding;
    private static long sBackLastDownTime;
    private static int sBackRepeat;
    static synchronized KeyEvent macapkBackRepeat(KeyEvent ev) {
        if (ev.getAction() == KeyEvent.ACTION_UP) {
            sBackDownOutstanding = false;
            sBackRepeat = 0;
            return ev;
        }
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return ev;
        long t = ev.getEventTime();
        long dt = t - sBackLastDownTime;
        sBackLastDownTime = t;
        if (sBackDownOutstanding && dt >= 0 && dt <= 2000) {
            sBackRepeat = Math.max(sBackRepeat + 1, ev.getRepeatCount());
            return ev.getRepeatCount() == sBackRepeat ? ev
                    : KeyEvent.changeTimeRepeat(ev, t, sBackRepeat);
        }
        sBackDownOutstanding = true;
        sBackRepeat = ev.getRepeatCount();
        return ev;
    }

    /** Whether View.findOnBackInvokedDispatcher reaches a window's dispatcher
     *  (patch P3 in docs/predictive-back-onbackinvokeddispatcher_2026-09-26.md).
     *  The predictive-back stage stays DORMANT until it does, because routing
     *  BACK to a window's callbacks is only AOSP-correct when every component
     *  that registers on that window CAN register: DrawerLayout, AppCompat
     *  Toolbar's collapse (Toolbar$Api33Impl.findOnBackInvokedDispatcher,
     *  confirmed in AntennaPod's converted jar), Material sheets / SearchView and
     *  Compose Popup/Dialog all find the dispatcher through a VIEW. With the
     *  stub answering null they register nowhere, so an opted-in app whose
     *  fragment back stack holds an androidx callback would pop the fragment and
     *  leave the drawer / search view open -- wrong against both the old Mac.apk
     *  (the legacy key reached them via onKeyUp) and real Android. A behavioural
     *  probe, not a reflective one, asked of the FOCUSED window on every BACK:
     *  a view in it must actually get a dispatcher back, i.e. exactly what its
     *  widgets would get when they register. A window whose views cannot find
     *  it keeps the legacy key. sForceViewBackWiredForGate is the offline
     *  gate's hook to exercise the stage itself while P3 is pending
     *  (debug/backinvoked_gate.sh prints PENDING when it uses it); nothing in
     *  the runtime sets it. */
    static volatile boolean sForceViewBackWiredForGate;
    static boolean macapkViewBackWired(View root) {
        if (sForceViewBackWiredForGate) return true;
        if (root == null) return false;
        View probe = root;
        // A CHILD exercises both halves (View + ViewGroup.findOnBackInvokedDispatcherForChild).
        if (root instanceof android.view.ViewGroup && ((android.view.ViewGroup) root).getChildCount() > 0) {
            View c = ((android.view.ViewGroup) root).getChildAt(0);
            if (c != null) probe = c;
        }
        try { return probe.findOnBackInvokedDispatcher() != null; }
        catch (Throwable ignored) { return false; }
    }

    /** The predictive-back stage for one BACK event: find the FOCUSED window's
     *  dispatcher (the same window WindowOverlayStack.deliverKey / the activity
     *  fallback below would give the key to) and run AOSP's doOnBackKeyEvent on
     *  it. Returns one of WindowOnBackInvokedDispatcher's result codes. One
     *  uncapped log line per BACK event (rare, and an absent line has been
     *  mis-read before -- see the TAB witness in doDispatchKey). */
    static int macapkPredictiveBack(Activity active, KeyEvent ev) {
        String where = "none";
        android.window.WindowOnBackInvokedDispatcher d = null;
        View probeRoot = null;   // a root of the focused window, for macapkViewBackWired
        try {
            java.util.List<WindowOverlayStack.Layer> layers = WindowOverlayStack.snapshot();
            macapkPruneBackRoots(layers);
            for (int i = layers.size() - 1; i >= 0 && d == null; i--) {
                WindowOverlayStack.Layer layer = layers.get(i);
                View v = layer.view;
                if (v == null || v.getVisibility() != View.VISIBLE) continue;
                int flags = layer.params != null ? layer.params.flags : 0;
                if ((flags & android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) continue;
                probeRoot = v;
                android.app.Dialog dlg = layer.owner != null ? layer.owner.get() : null;
                if (dlg != null) {
                    android.view.Window w = dlg.getWindow();
                    android.window.OnBackInvokedDispatcher od = w != null ? w.getOnBackInvokedDispatcher() : null;
                    if (od instanceof android.window.WindowOnBackInvokedDispatcher)
                        d = (android.window.WindowOnBackInvokedDispatcher) od;
                    where = "dialog " + dlg.getClass().getName();
                } else {
                    d = android.window.WindowOnBackInvokedDispatcher.macapkForRoot(v);
                    where = "overlay " + v.getClass().getName();
                }
                // The top focusable overlay IS the focused window, whether or not
                // it has a dispatcher: never fall through to a window behind it.
                if (d == null) return android.window.WindowOnBackInvokedDispatcher.MACAPK_KEY_LEGACY;
            }
            if (d == null && active != null) {
                android.view.Window w = active.getWindow();
                android.window.OnBackInvokedDispatcher od = w != null ? w.getOnBackInvokedDispatcher() : null;
                if (od instanceof android.window.WindowOnBackInvokedDispatcher)
                    d = (android.window.WindowOnBackInvokedDispatcher) od;
                where = "activity " + active.getClass().getName();
                probeRoot = active.macapkContentView();
                if (probeRoot == null && w != null) probeRoot = w.peekDecorView();
            }
            if (d == null || !d.isOnBackInvokedCallbackEnabled())
                return android.window.WindowOnBackInvokedDispatcher.MACAPK_KEY_LEGACY;
            if (!macapkViewBackWired(probeRoot)) {
                NativeLog.i(TAG, "BACK " + (ev.getAction() == KeyEvent.ACTION_DOWN ? "down" : "up")
                    + " -> predictive back DORMANT on " + where
                    + ": View.findOnBackInvokedDispatcher does not reach this window (patch P3 in"
                    + " docs/predictive-back-onbackinvokeddispatcher_2026-09-26.md), so its"
                    + " widgets cannot register their callbacks -> legacy key");
                return android.window.WindowOnBackInvokedDispatcher.MACAPK_KEY_LEGACY;
            }
        } catch (Throwable t) {
            NativeLog.w(TAG, "predictive back: focused-window lookup threw " + t);
            return android.window.WindowOnBackInvokedDispatcher.MACAPK_KEY_LEGACY;
        }
        android.window.OnBackInvokedCallback top = d.getTopCallback();
        // Did any APP code get scheduled for this event? (A throw from the app's
        // own callback is contained in BACK_INVOKER; this covers a failure in our
        // dispatch code after that point.)
        final boolean[] ranApp = {false};
        final java.util.concurrent.Executor invoker = x -> { ranApp[0] = true; BACK_INVOKER.execute(x); };
        int r;
        try {
            r = d.macapkOnBackKey(ev, invoker);
        } catch (Throwable t) {
            if (!ranApp[0]) {
                // Nothing of the app's has run: the legacy key is still a clean
                // answer.
                NativeLog.w(TAG, "predictive back: dispatch threw before any callback ran " + t);
                return android.window.WindowOnBackInvokedDispatcher.MACAPK_KEY_LEGACY;
            }
            // A callback already ran (or is posted): delivering the same BACK the
            // legacy way now would run the app's back handling a SECOND time.
            // Answer as the stage would have: consumed on 33-35, forwarded with
            // the UP canceled on 36.
            android.util.Log.e(TAG, "predictive back: dispatch threw after a callback ran;"
                    + " the BACK stays with the predictive path", t);
            r = android.os.Build.VERSION.SDK_INT >= 36
                    ? android.window.WindowOnBackInvokedDispatcher.FORWARD
                    : android.window.WindowOnBackInvokedDispatcher.FINISH_HANDLED;
        }
        if (r != android.window.WindowOnBackInvokedDispatcher.MACAPK_KEY_LEGACY) {
            NativeLog.i(TAG, "BACK " + (ev.getAction() == KeyEvent.ACTION_DOWN ? "down" : "up")
                + (ev.getRepeatCount() > 0 ? " (repeat " + ev.getRepeatCount() + ")" : "")
                + (ev.isCanceled() ? " (canceled)" : "") + " -> predictive back on " + where
                + " top=" + (top == null ? "none" : top.getClass().getName()
                    + (top instanceof android.window.OnBackAnimationCallback ? " [animation]" : "")
                    + " priority=" + d.macapkPriorityOf(top))
                + " -> " + backResultName(r));
        }
        return r;
    }

    private static String backResultName(int r) {
        switch (r) {
            case android.window.WindowOnBackInvokedDispatcher.FINISH_HANDLED: return "FINISH_HANDLED";
            case android.window.WindowOnBackInvokedDispatcher.FINISH_NOT_HANDLED: return "FINISH_NOT_HANDLED";
            case android.window.WindowOnBackInvokedDispatcher.FORWARD: return "FORWARD (API 36)";
            case android.window.WindowOnBackInvokedDispatcher.MACAPK_KEY_NO_CALLBACK:
                return "no callback registered -> legacy key (the Activity/Dialog default is not registered yet)";
            default: return "legacy";
        }
    }

    /** AOSP ViewRootImpl.dispatchDetachedFromWindow -> dispatcher.detachFromWindow
     *  (clear) for an overlay root whose window is gone: a per-root dispatcher
     *  whose root is no longer in the window stack is dropped before a BACK. */
    private static void macapkPruneBackRoots(java.util.List<WindowOverlayStack.Layer> layers) {
        java.util.List<View> roots = android.window.WindowOnBackInvokedDispatcher.macapkRoots();
        if (roots.isEmpty()) return;
        java.util.Set<View> live = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (WindowOverlayStack.Layer l : layers) if (l.view != null) live.add(l.view);
        for (View r : roots) if (!live.contains(r)) android.window.WindowOnBackInvokedDispatcher.macapkDetachRoot(r);
    }

    /** The dispatcher of the window whose ROOT view is `root`, as AOSP's
     *  View.findOnBackInvokedDispatcher resolves it through the ViewRootImpl:
     *  an Activity's decor -> that activity window's dispatcher; a Dialog's decor
     *  -> the dialog window's; any other window root in the stack -> its own
     *  per-root dispatcher; not a window root -> null (AOSP: a detached view has
     *  no ViewRootImpl). The hook the View.findOnBackInvokedDispatcher patch in
     *  docs/predictive-back-onbackinvokeddispatcher_2026-09-26.md calls. */
    public static android.window.OnBackInvokedDispatcher macapkBackDispatcherForRoot(View root) {
        // MACAPK_BACK_INVOKED=0: the pre-change answer (View.findOnBackInvokedDispatcher
        // returned null unconditionally), so widgets register nowhere, as before.
        if (root == null || !android.window.WindowOnBackInvokedDispatcher.MACAPK_BACK_INVOKED) return null;
        try {
            java.util.List<WindowOverlayStack.Layer> layers = WindowOverlayStack.snapshot();
            for (WindowOverlayStack.Layer layer : layers) {
                if (layer.view != root) continue;
                android.app.Dialog dlg = layer.owner != null ? layer.owner.get() : null;
                if (dlg != null && dlg.getWindow() != null) return dlg.getWindow().getOnBackInvokedDispatcher();
                // A NEW per-root dispatcher first drops those of windows already
                // gone, so the map stays bounded by the live windows even in a
                // session that never presses BACK (a dispatcher holding a
                // registered callback can pin its own root key in the WeakHashMap).
                if (android.window.WindowOnBackInvokedDispatcher.macapkPeekRoot(root) == null)
                    macapkPruneBackRoots(layers);
                return android.window.WindowOnBackInvokedDispatcher.macapkForRoot(root);
            }
            // A root whose layer already left the stack (detach runs after
            // WindowOverlayStack.remove) still answers with the dispatcher it
            // had, so an onDetachedFromWindow can unregister from it.
            android.window.WindowOnBackInvokedDispatcher had =
                    android.window.WindowOnBackInvokedDispatcher.macapkPeekRoot(root);
            if (had != null) return had;
            android.content.Context c = root.getContext();
            for (int i = 0; c != null && i < 16; i++) {
                if (c instanceof Activity) {
                    // The activity window's root is its decor; ours can also be
                    // the content root itself when an app's tree was never
                    // parented under the decor (macapkContentView).
                    Activity a = (Activity) c;
                    android.view.Window w = a.getWindow();
                    boolean isRoot = w != null
                            && (w.peekDecorView() == root || a.macapkContentView() == root);
                    return isRoot ? w.getOnBackInvokedDispatcher() : null;
                }
                if (!(c instanceof android.content.ContextWrapper)) break;
                c = ((android.content.ContextWrapper) c).getBaseContext();
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** Deliver a BACK press through the real key path: Activity.onKeyUp(
     *  KEYCODE_BACK) -> onBackPressed() -> androidx OnBackPressedDispatcher /
     *  fragment back stack, else finish() at the root. */
    private void dispatchBackKey(Activity active) {
        long bt = android.os.SystemClock.uptimeMillis();
        try {
            dispatchKeyToFocusedWindow(active, keyEvent(bt, bt,
                KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0, 0));
            dispatchKeyToFocusedWindow(active, keyEvent(bt, bt,
                KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0, 0));
            NativeLog.i(TAG, "edge-swipe back -> KEYCODE_BACK");
        } catch (Throwable thr) {
            NativeLog.w(TAG, "edge-swipe back dispatch threw: " + thr);
        }
    }

    // v1.0.822: has the guest received ANY real user input yet?
    //
    // Set by the two entry points the Cocoa bridge calls for a real mouse/key
    // event. ActivityHost consults it before handing an implicit ACTION_VIEW to
    // the HOST browser: opening Safari because the user tapped a link is
    // legitimate, opening it unprompted during startup is not -- the user never
    // asked, and it steals focus from the app that is still booting.
    //
    // Deliberately NOT set by macapkInjectTap/macapkInjectKey/macapkInjectText
    // (the last only since v1.0.1645; it went through dispatchKey): those are the
    // automation harness (debug/click.swift and friends), and letting a
    // synthetic tap unlock host-URL opening would defeat the guard exactly when
    // an unattended sweep is running.
    private static volatile boolean s_userInputSeen = false;
    public static boolean userInputSeen() { return s_userInputSeen; }
    // v1.0.849: WHEN the last real input arrived, for the host-URL RECENCY
    // gate (ActivityHost.openHost). The one-shot boolean above let a single
    // tap arm host-browser opens for the app's whole lifetime, so an ad SDK
    // firing ACTION_VIEW minutes later still hijacked the browser ("we still
    // have multiple apps opening browser links", 2026-08-01; subway opened an
    // ad tracker mid-campaign). Android's own model for background activity
    // starts is a RECENT-interaction grace window, not ever-interacted --
    // mirror that. Note the caveat above still applies: a CGEvent-synthesized
    // click (debug/click.swift) is indistinguishable from a human at this
    // layer, so unattended harnesses must ALSO set MACAPK_EXTERNAL_URLS=0.
    private static volatile long s_lastInputMillis = 0L;
    public static long lastUserInputMillis() { return s_lastInputMillis; }
    private static void noteRealInput() {
        s_userInputSeen   = true;
        s_lastInputMillis = System.currentTimeMillis();
    }

    /** A click or key the user delivered to a NATIVE view that bypasses this
     *  dispatcher: a WKWebView takes its mouse and keyboard events from AppKit
     *  directly (android.webkit.MacApkWebViewBridge.onRealInput), so without this
     *  a WebView-only app never registered a single real input and every
     *  browser hand-off it made looked programmatic. On a device the WebView is
     *  an ordinary View, and a tap on it is user interaction like any other. */
    public static void noteRealInputFromHostView() {
        noteRealInput();
    }

    /** MACAPK_WHEEL_SCROLL_EVENT (default ON; =0 = every wheel notch is the
     *  synthetic touch drag, the old path for every window). A host mouse-wheel
     *  notch over a window whose input queue was TAKEN (NativeActivity, Unreal's
     *  GameActivity) is delivered the way Android delivers a wheel: one
     *  MotionEvent ACTION_SCROLL, SOURCE_MOUSE, TOOL_TYPE_MOUSE, with
     *  AXIS_VSCROLL / AXIS_HSCROLL set (InputReader's CursorInputMapper: one
     *  ACTION_SCROLL per wheel step, +1 = up / left), at the pointer position.
     *  The synthetic drag cannot reach such an app: it arrives as DOWN, MOVE and
     *  UP inside one frame, and a native app that POLLS its pointer state once
     *  per frame (RetroArch's menu) never sees the finger down -- scrolling did
     *  nothing. RetroArch reads exactly this event (input/drivers/android_input.c:
     *  a SOURCE_MOUSE motion's AMotionEvent_getAxisValue(AXIS_VSCROLL) sets its
     *  wheel up / down). Since v1.0.2273 (WheelScroll.ALL) every other window
     *  gets the same event too, through deliverWheelToWindows, and the framework
     *  scrollers answer it (WheelScroll.VIEWS); =0 keeps the v1.0.2254 drag for
     *  them. A notch is never a touch, so it can never tap. Device id 0: Mac.apk
     *  registers no mouse InputDevice (see InputManager), and 0 is AOSP's "not
     *  from a physical device".
     *  @return true when the notch was delivered as ACTION_SCROLL. */
    public boolean dispatchWheel(float x, float y, float viewW, float viewH,
                                 float vscroll, float hscroll) {
        if (!WHEEL_SCROLL_EVENT) return false;
        final Activity a = ActivityHost.peek();
        if (a == null) return false;
        final boolean taken = hasTakenInputQueue(a);
        if (!taken && !WheelScroll.ALL) return false;
        noteRealInput();
        final float scale = macapkBackingScale();
        final float px = x * scale, py = (viewH - y) * scale;
        final MotionEvent ev = wheelEvent(px, py, vscroll, hscroll);
        Runnable r = taken ? () -> deliverToTakenInputQueue(a, ev)
                           : () -> deliverWheelToWindows(a, ev);
        if (routeToLoop(a)) loopHandler().post(r); else r.run();
        return true;
    }

    /** v1.0.2273 (WheelScroll.ALL): the window under the pointer gets the wheel
     *  step, as InputDispatcher sends a pointer event to the touched window --
     *  a floating window (dialog, popup) first, through WindowOverlayStack's
     *  touch rules, else the activity, whose view tree routes it to the view
     *  under the pointer (ViewGroup.dispatchGenericPointerEvent). An event no
     *  view handles is dropped, as on a device; it never becomes a touch. */
    /** MACAPK_WHEEL_TRACE=1: log where the first wheel steps went. */
    private static final boolean WHEEL_TRACE = "1".equals(System.getenv("MACAPK_WHEEL_TRACE"));
    private static int sWheelLogged;
    private static void deliverWheelToWindows(Activity a, MotionEvent ev) {
        try {
            if (!WindowOverlayStack.isEmpty()) {
                // Overlay bounds are content-space (screen minus the status bar).
                final float contentY = ev.getY() - StatusBarDriver.heightPx(null);
                if (WindowOverlayStack.deliverPointerGeneric(ev, ev.getX(), contentY)) {
                    if (WHEEL_TRACE && sWheelLogged++ < 6) NativeLog.i(TAG, "wheel -> overlay window at ("
                            + ev.getX() + "," + contentY + ")");
                    return;
                }
            }
            final boolean handled = a.dispatchGenericMotionEvent(ev);
            if (WHEEL_TRACE && sWheelLogged++ < 6) NativeLog.i(TAG, "wheel ACTION_SCROLL v="
                    + ev.getAxisValue(MotionEvent.AXIS_VSCROLL) + " at (" + ev.getX() + ","
                    + ev.getY() + ") -> " + a.getClass().getSimpleName() + " handled=" + handled);
        } catch (Throwable t) {
            NativeLog.w(TAG, "wheel delivery threw: " + t);
        }
    }

    static final boolean WHEEL_SCROLL_EVENT =
        !"0".equals(System.getenv("MACAPK_WHEEL_SCROLL_EVENT"));

    static boolean hasTakenInputQueue(Activity a) {
        if (a == null) return false;
        try {
            android.view.Window w = a.getWindow();
            android.view.InputQueue q = w != null ? w.macapkTakenInputQueue() : null;
            return q != null && q.getNativePtr() != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** ViewRootImpl.processPointerEvent sends only touch actions to
     *  dispatchTouchEvent; a pointer HOVER or SCROLL is a generic motion
     *  (processGenericMotionEvent / dispatchGenericMotionEvent), as is anything
     *  not from a pointer source. */
    static boolean isTouchDispatch(MotionEvent me) {
        final int act = me.getActionMasked();
        final boolean generic = act == MotionEvent.ACTION_SCROLL
                || act == MotionEvent.ACTION_HOVER_MOVE
                || act == MotionEvent.ACTION_HOVER_ENTER
                || act == MotionEvent.ACTION_HOVER_EXIT;
        return me.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER) && !generic;
    }

    /** One wheel step as InputReader's CursorInputMapper emits it. */
    static MotionEvent wheelEvent(float px, float py, float vscroll, float hscroll) {
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
        p.id = 0;
        p.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
        c.x = px; c.y = py;
        c.setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll);
        c.setAxisValue(MotionEvent.AXIS_HSCROLL, hscroll);
        return MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1,
                new MotionEvent.PointerProperties[]{ p },
                new MotionEvent.PointerCoords[]{ c },
                0 /* metaState */, 0 /* buttonState */, 1f, 1f,
                0 /* deviceId: no mouse InputDevice is registered */, 0 /* edgeFlags */,
                android.view.InputDevice.SOURCE_MOUSE, 0 /* flags */);
    }

    // ------------------------------------------------------------------
    // HOST MOUSE as an Android mouse (MACAPK_MOUSE_POINTER, default ON).
    //
    // A host mouse used to arrive as a touchscreen FINGER with buttonState 0 and
    // metaState 0, and only for the primary button, so an app could not tell a
    // mouse drag from a finger drag, a right-drag from a left-drag, or a
    // Ctrl/Shift drag from a plain one (GitHub #21: MapLibre Compose's
    // right-drag / Ctrl-drag rotate and Shift-drag box select). Real Android
    // (InputReader CursorInputMapper::sync, frameworks/native main) emits, per
    // change of the pressed-button set, all SOURCE_MOUSE / TOOL_TYPE_MOUSE with
    // metaState = the global meta state:
    //   BUTTON_RELEASE for each released button (actionButton = it,
    //   buttonState stepping down), then the main event -- DOWN when the first
    //   of PRIMARY/SECONDARY/TERTIARY goes down, UP when the last comes up, MOVE
    //   while one is held -- with buttonState = the new set, then
    //   BUTTON_PRESS for each newly pressed button (buttonState stepping up).
    // BUTTON_BACK / BUTTON_FORWARD (the side buttons) are not pointer-down
    // buttons; they also synthesize KEYCODE_BACK / KEYCODE_FORWARD, the key DOWN
    // before the motion events and the key UP after
    // (TouchCursorInputMapperCommon synthesizeButtonKeys). DOWN/MOVE/UP go
    // through the touch path (the window under the pointer, overlays first);
    // BUTTON_PRESS/RELEASE are generic motion (MotionEvent.isTouchEvent is false
    // for them), routed like a wheel step. A secondary click is NOT turned into
    // BACK: AOSP forwards it as a pointer stream (View shows the context menu,
    // performButtonActionOnTouchDown). Hover (no buttons) is #25.
    // ------------------------------------------------------------------
    static final boolean MOUSE_POINTER = !"0".equals(System.getenv("MACAPK_MOUSE_POINTER"));
    private static final int POINTER_DOWN_BUTTONS = MotionEvent.BUTTON_PRIMARY
            | MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_TERTIARY;
    /** The pressed-button set as of the last host mouse event (AppKit thread). */
    private static int s_hostMouseButtons;
    static final class HostMouseState {
        final int buttonState, metaState;
        HostMouseState(int buttonState, int metaState) { this.buttonState = buttonState; this.metaState = metaState; }
    }
    /** Non-null while a host-mouse DOWN/MOVE/UP is being delivered (delivery
     *  thread), so touchEvent builds mouse events for it. */
    private static volatile HostMouseState s_deliveringMouse;

    /** What CursorInputMapper builds for one main/hover event: one pointer,
     *  TOOL_TYPE_MOUSE, SOURCE_MOUSE, pressure 1 while a button is down. Device
     *  id 0, like the wheel step: no mouse InputDevice is registered. */
    static MotionEvent mouseEvent(long downTime, long eventTime, int action,
                                  float x, float y, int buttonState, int metaState) {
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
        p.id = 0;
        p.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
        c.x = x; c.y = y;
        c.pressure = (buttonState & POINTER_DOWN_BUTTONS) != 0 ? 1f : 0f;
        c.size = 0f;
        return MotionEvent.obtain(downTime, eventTime, action, 1,
                new MotionEvent.PointerProperties[]{ p },
                new MotionEvent.PointerCoords[]{ c },
                metaState, buttonState, 1f, 1f,
                0 /* deviceId */, 0 /* edgeFlags */,
                android.view.InputDevice.SOURCE_MOUSE, 0 /* flags */);
    }

    /** Called from cocoa_bridge.mm for every host mouse button event: the
     *  pressed set AFTER this event (AOSP BUTTON_* bits, which AppKit's button
     *  numbers map onto 1:1), the meta state, and the pointer in view points. */
    public void dispatchHostMouse(int buttonState, int metaState, float x, float y,
                                  float viewW, float viewH) {
        if (!MOUSE_POINTER) {
            // The pre-change path (the native side normally never calls in under
            // =0; this keeps the Java entry honest for the gate's control arm):
            // the primary button as a touchscreen finger, every other button dropped.
            final int lastP = s_hostMouseButtons & MotionEvent.BUTTON_PRIMARY;
            s_hostMouseButtons = buttonState;
            final int nowP = buttonState & MotionEvent.BUTTON_PRIMARY;
            if (lastP == 0 && nowP != 0) dispatch(MotionEvent.ACTION_DOWN, x, y, viewW, viewH);
            else if (lastP != 0 && nowP == 0) dispatch(MotionEvent.ACTION_UP, x, y, viewW, viewH);
            else if (nowP != 0) dispatch(MotionEvent.ACTION_MOVE, x, y, viewW, viewH);
            return;
        }
        final int last = s_hostMouseButtons;
        s_hostMouseButtons = buttonState;
        final boolean wasDown = (last & POINTER_DOWN_BUTTONS) != 0;
        final boolean down = (buttonState & POINTER_DOWN_BUTTONS) != 0;
        if (!wasDown && !down && last == buttonState) {
            // No button changed and none is held: a HOVER_MOVE (CursorInputMapper:
            // `down || source != MOUSE ? MOVE : HOVER_MOVE`), GitHub #25.
            if (!android.view.View.HOVER_VIEWS) return;
            Runnable h = () -> deliverHover(MotionEvent.ACTION_HOVER_MOVE, buttonState, metaState, x, y, viewH);
            if (routeToLoop(ActivityHost.peek())) loopHandler().post(h); else h.run();
            return;
        }
        noteRealInput();
        if (TRACKPAD_MULTITOUCH) {
            if (!wasDown && down) { s_hostPointerDown = true; trackpadClaim(false); }
            else if (wasDown && !down) s_hostPointerDown = false;
        }
        Runnable r = () -> deliverHostMouse(last, buttonState, metaState, x, y, viewW, viewH);
        if (routeToLoop(ActivityHost.peek())) loopHandler().post(r); else r.run();
    }

    private void deliverHostMouse(int last, int now, int meta, float x, float y,
                                  float viewW, float viewH) {
        final int pressed = now & ~last, released = last & ~now;
        final boolean wasDown = (last & POINTER_DOWN_BUTTONS) != 0;
        final boolean down = (now & POINTER_DOWN_BUTTONS) != 0;
        // Side buttons: key DOWN first (synthesizeButtonKeys AKEY_EVENT_ACTION_DOWN).
        sideButtonKeys(pressed, KeyEvent.ACTION_DOWN, meta);
        final float scale = macapkBackingScale();
        final float px = x * scale, py = (viewH - y) * scale;
        int step = last;
        for (int b = 1; b != 0 && (released & ~(b - 1)) != 0; b <<= 1) {
            if ((released & b) == 0) continue;
            step &= ~b;
            deliverMouseButton(MotionEvent.ACTION_BUTTON_RELEASE, b, step, meta, px, py);
        }
        // InputDispatcher: a DOWN clears the device's hovering pointer, so the
        // hovered window gets HOVER_EXIT before the DOWN ("Compatibility
        // behaviour: ACTION_DOWN causes HOVER_EXIT to get generated").
        if (!wasDown && down && android.view.View.HOVER_VIEWS) {
            deliverHover(MotionEvent.ACTION_HOVER_EXIT, last, meta, x, y, viewH);
        }
        if (!wasDown && !down && android.view.View.HOVER_VIEWS) {
            // Only a side button changed: the main event is a HOVER_MOVE.
            deliverHover(MotionEvent.ACTION_HOVER_MOVE, now, meta, x, y, viewH);
        }
        if (wasDown || down) {
            final int action = wasDown == down ? MotionEvent.ACTION_MOVE
                    : (down ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP);
            s_deliveringMouse = new HostMouseState(now, meta);
            try {
                doDispatch(action, x, y, viewW, viewH);
            } finally {
                s_deliveringMouse = null;
            }
        }
        for (int b = 1; b != 0 && (pressed & ~(b - 1)) != 0; b <<= 1) {
            if ((pressed & b) == 0) continue;
            step |= b;
            deliverMouseButton(MotionEvent.ACTION_BUTTON_PRESS, b, step, meta, px, py);
        }
        // CursorInputMapper: "Send hover move after UP to tell the application
        // that the mouse is hovering now." The window gets it as HOVER_ENTER.
        if (wasDown && !down && android.view.View.HOVER_VIEWS) {
            deliverHover(MotionEvent.ACTION_HOVER_MOVE, now, meta, x, y, viewH);
        }
        sideButtonKeys(released, KeyEvent.ACTION_UP, meta);
    }

    // ------------------------------------------------------------------
    // HOVER (GitHub #25, MACAPK_HOVER_EVENTS default ON). InputDispatcher sends
    // a hovering mouse's events to the window under it: HOVER_ENTER to a window
    // that newly has the pointer (instead of the raw HOVER_MOVE), HOVER_MOVE
    // while it stays, HOVER_EXIT to a window it left, and HOVER_EXIT before a
    // DOWN (getHoveringWindowsLocked / findTouchedWindowTargetsLocked,
    // frameworks/native main). Inside the window the view tree routes it
    // (ViewGroup.dispatchHoverEvent). s_hoverWindow is the window currently
    // hovered: the Activity, an overlay layer's root view, or null.
    // ------------------------------------------------------------------
    private static Object s_hoverWindow;

    /** Host pointer left our view (cocoa mouseExited:): the hovered window exits. */
    public void dispatchHostMouseExit(float x, float y, float viewW, float viewH) {
        if (!android.view.View.HOVER_VIEWS) return;
        final int meta = 0;
        Runnable h = () -> deliverHover(MotionEvent.ACTION_HOVER_EXIT, s_hostMouseButtons, meta, x, y, viewH);
        if (routeToLoop(ActivityHost.peek())) loopHandler().post(h); else h.run();
    }

    /** action is HOVER_MOVE (re-targeted to ENTER / EXIT per window) or
     *  HOVER_EXIT (the pointer left every window). x/y in view points. */
    private static void deliverHover(int action, int buttonState, int meta,
                                     float x, float y, float viewH) {
        final Activity a = ActivityHost.peek();
        if (a == null) { s_hoverWindow = null; return; }
        final float scale = macapkBackingScale();
        final float px = x * scale, py = (viewH - y) * scale;
        final float contentY = py - StatusBarDriver.heightPx(null);
        final Object target;
        if (action == MotionEvent.ACTION_HOVER_EXIT) {
            target = null;
        } else {
            Object w = WindowOverlayStack.isEmpty() ? null : WindowOverlayStack.hoverWindowAt(px, contentY);
            target = w == null ? a : (w == WindowOverlayStack.HOVER_SWALLOWED ? null : w);
        }
        final Object old = s_hoverWindow;
        if (old != null && old != target) {
            sendHover(old, a, MotionEvent.ACTION_HOVER_EXIT, buttonState, meta, px, py, contentY);
        }
        s_hoverWindow = target;
        if (target == null) return;
        sendHover(target, a, old == target ? MotionEvent.ACTION_HOVER_MOVE : MotionEvent.ACTION_HOVER_ENTER,
                buttonState, meta, px, py, contentY);
    }

    private static void sendHover(Object window, Activity a, int action, int buttonState, int meta,
                                  float px, float py, float contentY) {
        long t = android.os.SystemClock.uptimeMillis();
        MotionEvent ev = mouseEvent(t, t, action, px, py, buttonState, meta);
        try {
            if (window == a) {
                if (deliverToTakenInputQueue(a, ev)) return;
                a.dispatchGenericMotionEvent(ev);
            } else if (window instanceof View lv) {
                WindowOverlayStack.deliverGenericToLayer(lv, ev, px, contentY);
            }
        } catch (Throwable thr) {
            NativeLog.w(TAG, "hover delivery threw: " + thr);
        }
    }

    private void sideButtonKeys(int changed, int keyAction, int meta) {
        if ((changed & MotionEvent.BUTTON_BACK) != 0)
            deliverKey(keyAction, KeyEvent.KEYCODE_BACK, 0, meta, false);
        if ((changed & MotionEvent.BUTTON_FORWARD) != 0)
            deliverKey(keyAction, KeyEvent.KEYCODE_FORWARD, 0, meta, false);
    }

    /** One BUTTON_PRESS / BUTTON_RELEASE, routed like a wheel step: the taken
     *  native input queue if there is one, else the window under the pointer. */
    private static void deliverMouseButton(int action, int actionButton, int buttonState,
                                           int meta, float px, float py) {
        final Activity a = ActivityHost.peek();
        if (a == null) return;
        long t = android.os.SystemClock.uptimeMillis();
        long down = s_gestureDownTime != 0L ? s_gestureDownTime : t;
        MotionEvent ev = mouseEvent(down, t, action, px, py, buttonState, meta);
        ev.setActionButton(actionButton);
        if (deliverToTakenInputQueue(a, ev)) return;
        deliverWheelToWindows(a, ev);
    }

    public void dispatch(int action, float x, float y, float viewW, float viewH) {
        noteRealInput();
        if (TRACKPAD_MULTITOUCH) {
            // The host mouse (or the synthesized scroll drag) starts a stream:
            // a live trackpad hold must end first (CANCEL), and none may begin
            // while this one is down. Decided HERE, on the AppKit thread the
            // trackpad frames also arrive on, before this event is posted.
            if (action == MotionEvent.ACTION_DOWN) {
                s_hostPointerDown = true;
                trackpadClaim(false);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                s_hostPointerDown = false;
            }
        }
        if (routeToLoop(ActivityHost.peek())) {
            loopHandler().post(() -> doDispatch(action, x, y, viewW, viewH));
            return;
        }
        doDispatch(action, x, y, viewW, viewH);
    }
    private void doDispatch(int action, float x, float y, float viewW, float viewH) {
        // Input recorder (docs/input-recorder_2026-10-01.md), AT DELIVERY: on the
        // thread that hands the event to the app, in the loop's FIFO order -- which
        // is also where the trackpad claim's CANCEL (queued by dispatch() above,
        // before this event) is recorded, so a replay keeps that order.
        if (InputRecorder.ON) InputRecorder.pointer(action, x, y, viewW, viewH);
        Activity active = ActivityHost.peek();
        // v1.0.1047: decide ONCE per event whether this event is logged, so the
        // "dispatch" line and its "-> returned" line are always a matched pair.
        // Previously the second line re-tested `s_logged <= LOG_CAP` AFTER the
        // counter had saturated at LOG_CAP, which is permanently true -- so the
        // cap silently stopped applying to it. Measured on the F-Droid hand-test
        // log: 64 "dispatch" lines against 278 "-> returned" lines. That is
        // worse than a noisy log; an unpaired tail reads as "these taps arrived
        // and were never dispatched", which is a false diagnosis of exactly the
        // input bugs this trace exists to find.
        final boolean logThis = s_logged < LOG_CAP;
        if (logThis) {
            s_logged++;
            NativeLog.i(TAG, "dispatch action=" + action +
                " ptCoords=(" + x + "," + y + ") viewSize=" + viewW + "x" + viewH +
                " activity=" + (active == null ? "null" :
                    (active.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(active)))));
        }
        if (active == null) return;
        // AOSP processPointerEvent: a touch DOWN enters touch mode (focus that
        // cannot live in touch mode is dropped), before anything consumes it.
        if (action == MotionEvent.ACTION_DOWN) macapkEnterTouchModeForPointer(active, action);

        // Latch the gesture clock BEFORE any of the early returns below (the
        // nav-strip back button and the overlay stack both consume events), so
        // a DOWN that we swallow still starts the stream that a later MOVE/UP
        // belongs to. Every event of one press-drag-release then carries the
        // same downTime, which is what long-press and fling classification
        // read.
        if (action == 0 /* DOWN */) s_gestureDownTime = android.os.SystemClock.uptimeMillis();

        // The drawable's real backing scale (was hardcoded 2.0f -- correct on retina, but on a 1x
        // external display every touch landed at twice its true coordinate). See macapkBackingScale.
        float scale = macapkBackingScale();
        float px = x * scale;
        float py = (viewH - y) * scale;  // Y-flip: Cocoa origin bottom -> Android origin top
        dispatchSurfaceTouch(active, action, px, py,
                Math.round(viewW * scale), Math.round(viewH * scale), logThis);
    }

    /** Everything a host mouse event does once it is in SURFACE pixels
     *  (top-left origin): the nav-strip BACK glyph, the floating-window stack,
     *  Activity.dispatchTouchEvent and the renderer-direct fallback.
     *
     *  Split out of doDispatch (v1.0.1645) so the injected-tap driver can hand
     *  a SurfaceView-tree app EXACTLY this sequence rather than a lookalike --
     *  see injectTapViaHostPath. doDispatch keeps the Cocoa-point transform,
     *  the trace line and the gesture-clock latch, and its behaviour is
     *  unchanged: the two rounded sizes were computed inline at the one
     *  backButtonHitRect call before, from the same inputs. */
    /** Is a host pointer down right now, and where (SURFACE px)? Written at
     *  the top of every dispatchSurfaceTouch, so while an app's DOWN or MOVE
     *  handler runs the pointer is down and while its UP/CANCEL handler runs
     *  it is not -- the question AOSP's DragDropController asks through
     *  transferTouchFocus before a drag may start (View.startDragAndDrop ->
     *  MacApkDragSession.start). */
    private static volatile boolean s_ptrDown;
    private static volatile float s_ptrX, s_ptrY;
    public static float[] macapkPointerIfDown() {
        return s_ptrDown ? new float[]{ s_ptrX, s_ptrY } : null;
    }

    private void dispatchSurfaceTouch(Activity active, int action, float px, float py,
                                      int surfW, int surfH, boolean logThis) {
        // GATE-MUTANT(touchinput:drag-route) begin -- debug/touchinput_gate.sh
        // deletes this block (and the s_ptr* writes) to prove D8 sees the
        // production wiring: the gate must go RED without it.
        s_ptrDown = !(action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL);
        s_ptrX = px;
        s_ptrY = py;
        // A drag in progress owns the pointer (AOSP transfers touch focus to the
        // drag; the source window's stream is cancelled once). See
        // android.view.MacApkDragSession. Never true under MACAPK_DRAG_AND_DROP=0.
        if (android.view.MacApkDragSession.isActive()) {
            if (action == MotionEvent.ACTION_DOWN) {
                // A new stream while a drag still owns the pointer: its UP was
                // lost. End the dead drag (cancelled) and dispatch this DOWN
                // normally, so input can never stay routed to a stale session.
                NativeLog.w(TAG, "drag: DOWN while a drag is active (lost UP); aborting the drag");
                android.view.MacApkDragSession.abort();
            } else {
                if (android.view.MacApkDragSession.takeSourceCancel()) {
                    long ct = android.os.SystemClock.uptimeMillis();
                    MotionEvent cancel = touchEvent(s_gestureDownTime != 0L ? s_gestureDownTime : ct,
                            ct, MotionEvent.ACTION_CANCEL, px, py, 0);
                    try { active.dispatchTouchEvent(cancel); }
                    catch (Throwable thr) { NativeLog.w(TAG, "drag source cancel threw: " + thr); }
                    finally { cancel.recycle(); }
                }
                View droot = android.view.MacApkDragSession.activeRoot();
                if (droot != null) {
                    android.view.MacApkDragSession.onPointer(action,
                            px - droot.getLeft(), py - droot.getTop());
                }
                return;
            }
        }
        // GATE-MUTANT(touchinput:drag-route) end
        // Gesture-nav BACK = a left-edge swipe right (AOSP
        // EdgeBackGestureHandler), canvas apps only (same gate StatusBarDriver
        // uses to paint the pill, so a Unity/GL app's input is untouched). The
        // bottom pill is an OVERLAY now, not a reserved strip, so we do NOT
        // consume bottom touches -- the app's own bottom nav lives there.
        View croot = active.macapkContentView();
        if (android.os.Looper.MACAPK_REAL_LOOPER
                && croot != null && !treeHasSurfaceView(croot)) {
            // v1.0.406: tappable BACK button in the bottom nav strip. A tap
            // (DOWN then UP) inside the back-chevron rect dispatches
            // KEYCODE_BACK; a drag out disarms it (so it doesn't eat scrolls).
            int[] backRect = StatusBarDriver.backButtonHitRect(croot, surfW, surfH);
            if (backRect != null) {
                boolean inBack = px >= backRect[0] && px <= backRect[2]
                    && py >= backRect[1] && py <= backRect[3];
                if (action == 0 /* DOWN */) {
                    if (inBack) { _navBackArmed = true; return; }
                } else if (action == 2 /* MOVE */) {
                    if (_navBackArmed) { if (!inBack) _navBackArmed = false; else return; }
                } else if (action == 1 /* UP */) {
                    if (_navBackArmed) {
                        _navBackArmed = false;
                        if (inBack) dispatchBackKey(active);
                        return;
                    }
                }
            }
            // v1.0.867: the left-edge back SWIPE is GONE with the gesture pill.
            // It was the gesture-nav BACK trigger; under 3-button nav AOSP has
            // no edge-back at all -- BACK is the tappable glyph handled above.
            // Keeping both would be a Mac.apk invention AND a live hazard: the
            // swipe consumed any rightward drag starting within 20dp of the left
            // edge, so an app with a left-edge control (a drag handle, a game's
            // on-screen stick) silently lost it. Esc / Cmd+[ still dispatch BACK.
        }

        // v1.0.691: a floating window (Dialog/PopupWindow/dropdown) added via
        // WindowManager.addView is on TOP of the Activity content, so it gets
        // first crack at the touch. Overlay bounds are in CONTENT space (screen
        // minus status bar); px/py are screen space, so shift py up by the bar.
        // MACAPK_OVERLAY_GESTURE_PIN: a new gesture is the Activity's until an
        // overlay takes its DOWN, and a gesture pinned to an overlay that has
        // since been removed still has to be swallowed with the stack empty.
        if (action == 0 /* DOWN */) WindowOverlayStack.macapkGestureDown();
        if (!WindowOverlayStack.isEmpty() || WindowOverlayStack.gesturePinnedToOverlay()) {
            // Overlay bounds are content-space; shift the screen-space py up by
            // the status-bar height. heightPx(null) uses the default density,
            // which is correct here (the bar height is display-global).
            float contentY = py - StatusBarDriver.heightPx(null);
            if (WindowOverlayStack.deliverTouch(action, px, contentY)) {
                return;  // consumed by an overlay; do NOT also hit the Activity
            }
        }
        long t = android.os.SystemClock.uptimeMillis();
        long down = s_gestureDownTime != 0L ? s_gestureDownTime : t;
        MotionEvent ev = touchEvent(down, t, action, px, py, 0);
        try {
            if (deliverToTakenInputQueue(active, ev)) {
                if (logThis) NativeLog.i(TAG, "  -> the window's input queue (px=" + px + " py=" + py + ")");
                return;
            }
            boolean handled = active.dispatchTouchEvent(ev);
            if (logThis) {
                NativeLog.i(TAG, "  -> activity.dispatchTouchEvent returned " + handled +
                    " (px=" + px + " py=" + py + ")");
            }
            // Also try the renderer-direct path so engines whose
            // onTouchEvent queues to a dead GLThread still see the
            // touch. dispatchToRenderer itself declines when the host
            // view's queue is serviced (v1.0.1639; a fork's since
            // MACAPK_TOUCH_APP_QUEUE_ONLY) -- delivering then would be a
            // second copy of the same touch.
            dispatchToRenderer(active, action, px, py);
        }
        catch (Throwable thr) {
            NativeLog.w(TAG, "dispatchTouchEvent threw: " + thr);
        }
        finally { ev.recycle(); }
    }

    /** Called from native cocoa_bridge.mm for each host keyboard event,
     *  mirroring dispatch() for touch -- this class is the unified input
     *  bridge (touch + keys).
     *  action:      0=KeyEvent.ACTION_DOWN, 1=KeyEvent.ACTION_UP
     *  keyCode:     Android KEYCODE_* (the native side already translated
     *               the macOS virtual key code / character)
     *  unicodeChar: the literal character produced (0 for non-printing keys)
     *  metaState:   KeyEvent.META_* bits (shift/ctrl/alt)
     *  Routes to the active Activity's dispatchKeyEvent, exactly as a
     *  hardware keyboard does on Android. For Unity titles that reaches
     *  UnityPlayerActivity.dispatchKeyEvent -> UnityPlayer.injectEvent,
     *  feeding the engine input system (Input.GetKey, uGUI/TMP fields). */
    private static int s_keyLogged = 0;
    /** Every key DOWN that reached doDispatchKey, uncapped (see the witness there). */
    private static int s_keyDowns = 0;
    /** Set when the ENTER *down* was turned into performEditorAction, so the
     *  matching *up* is swallowed too. Single-threaded: every key lands on the
     *  loop thread via doDispatchKey. */
    private static boolean sImeEnterConsumed = false;
    public void dispatchKey(int action, int keyCode, int unicodeChar, int metaState) {
        dispatchHostKey(action, keyCode, unicodeChar, metaState, false);
    }
    /** The JNI door of the Cocoa key path (cocoa_bridge.mm dispatchKeyRawAction):
     *  dispatchKey plus NSEvent.isARepeat, so hostKeyEvent can number a driver
     *  repeat only when AppKit says one happened. Callers with no AppKit event
     *  (the BACK chevron, the harness, a recording replay) use dispatchKey. */
    public void dispatchHostKey(int action, int keyCode, int unicodeChar, int metaState, boolean autoRepeat) {
        noteRealInput();
        if (routeToLoop(ActivityHost.peek())) {
            loopHandler().post(() -> deliverKey(action, keyCode, unicodeChar, metaState, autoRepeat));
            return;
        }
        deliverKey(action, keyCode, unicodeChar, metaState, autoRepeat);
    }
    /** A HOST key, as it reaches the app: the recorder sees it here, on the
     *  delivering thread and after any queued focus change has run, so its
     *  password check reads the editor the key is delivered into (InputRecorder,
     *  "AT DELIVERY"). The harness injectors call doDispatchKey directly and are
     *  not host input, so they stay out of a recording. */
    private void deliverKey(int action, int keyCode, int unicodeChar, int metaState, boolean autoRepeat) {
        if (InputRecorder.ON) InputRecorder.key(action, keyCode, unicodeChar, metaState);
        doDispatchKey(action, keyCode, unicodeChar, metaState, autoRepeat);
    }
    private void doDispatchKey(int action, int keyCode, int unicodeChar, int metaState) {
        doDispatchKey(action, keyCode, unicodeChar, metaState, false);
    }
    private void doDispatchKey(int action, int keyCode, int unicodeChar, int metaState, boolean autoRepeat) {
        // Uncapped TAB witness. The trace below is gated on LOG_CAP (64), which
        // saturates within seconds of a boot -- and an ABSENT line was about to
        // be read as "TAB never reached Java", which would have sent the hunt to
        // the Cocoa side for nothing. A rare navigation key earns one line.
        if (keyCode == 61 && action == KeyEvent.ACTION_DOWN) {
            NativeLog.i(TAG, "TAB reached doDispatchKey (meta=" + metaState + ")");
        }
        // Uncapped DOWN counter (owner_issue_scan KEY-CONTROLLER-CHURN): the per-key trace below
        // stops at LOG_CAP (64) within seconds of a session that types first, so "how many keys
        // were pressed in this run" needs a witness that outlives it. One line at each power of
        // two from 4 (4, 8, 16, ...) is ~20 lines for a million keystrokes and carries a COUNT, never a key.
        if (action == KeyEvent.ACTION_DOWN) {
            final int n = ++s_keyDowns;
            if (n >= 4 && (n & (n - 1)) == 0) NativeLog.i(TAG, "key DOWNs so far=" + n);
        }
        Activity active = ActivityHost.peek();
        // v1.0.1047: same matched-pair fix as doDispatch above -- `s_keyLogged
        // <= LOG_CAP` stayed true forever once the counter saturated, so the
        // "-> returned" line outlived its cap.
        final boolean logThis = s_keyLogged < LOG_CAP;
        if (logThis) {
            s_keyLogged++;
            // KeyLogPolicy: a key that types a character is logged as
            // `key=<printable>` -- this line used to carry the first 64 typed
            // characters of every session (passwords included) into the user's
            // log file. Navigation / control keys and Ctrl/Meta chords keep
            // their keyCode; MACAPK_TRACE_KEYS=1 opts in to full values, never
            // while a password field is the target. docs/key-log-redaction_2026-10-01.md
            NativeLog.i(TAG, "dispatchKey action=" + action + " "
                + KeyLogPolicy.key(keyCode, unicodeChar, metaState) +
                " activity=" + (active == null ? "null" :
                    (active.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(active))))
                // AppKit's isARepeat, only when set: the live witness that the repeat word
                // reaches Java (hostKeyEvent numbers repeatCount from it).
                + (autoRepeat ? " autoRepeat=1" : ""));
        }
        // Soft-input routing: when an app has shown the soft keyboard for a
        // text field (Mac.apk records the target in InputMethodManager), the
        // host keystroke is text, not game input -- deliver it to that field
        // (which fires its TextWatcher -> Unity nativeSetInputString) and
        // consume it so the engine doesn't also see it as a gameplay key.
        android.widget.TextView input =
            android.view.inputmethod.InputMethodManager.macapkActiveInput();
        // MACAPK_TRACE_IME=1: which text target was live for this keystroke.
        // Added after three individually-correct fixes (TextView.onKeyDown,
        // EditText focusability, toggleSoftInput) each changed nothing -- the
        // signal that says the SITE is not being reached, not that the fix is
        // wrong. Costs one getenv-cached boolean per key.
        if (s_traceIme) {
            android.view.inputmethod.InputConnection tic =
                android.view.inputmethod.InputMethodManager.macapkActiveConnection();
            NativeLog.i(TAG, "  IME activeInput=" + (input == null ? "null"
                    : input.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(input)))
                + " activeConnection=" + (tic == null ? "null" : tic.getClass().getName())
                + " focused=" + android.view.inputmethod.InputMethodManager.macapkTraceFocused());
        }
        if (input != null) {
            if (action == KeyEvent.ACTION_DOWN) {
                boolean consumed = false;
                try { consumed = input.macapkHandleSoftKey(unicodeChar, keyCode); }
                catch (Throwable thr) { NativeLog.w(TAG, "softKey: " + thr); }
                if (consumed) return;
            } else if (action == KeyEvent.ACTION_UP) {
                boolean textKey = (unicodeChar >= 32 && unicodeChar != 127)
                    || keyCode == 67 || keyCode == 112 || keyCode == 66
                    || unicodeChar == 8 || unicodeChar == 10 || unicodeChar == 13;
                if (textKey) return;   // swallow the matching UP for text keys
            }
        }

        // Compose (and any view without a TextView) receives text through its
        // bound InputConnection, not macapkHandleSoftKey. Mac.apk IS the IME, so
        // feed host keystrokes the way a soft keyboard would: printable chars via
        // commitText, backspace via deleteSurroundingText. ENTER/arrows/escape
        // fall through to dispatchKeyEvent so Compose's focus key handling (submit,
        // caret movement) still runs. Only engaged when no TextView target is
        // active (Unity/cocos keep the macapkHandleSoftKey path). v1.0.425.
        if (input == null) {
            android.view.inputmethod.InputConnection ic =
                android.view.inputmethod.InputMethodManager.macapkActiveConnection();
            if (ic != null) {
                boolean printable = (unicodeChar >= 32 && unicodeChar != 127);
                boolean backspace = (keyCode == 67 /* KEYCODE_DEL */ || unicodeChar == 8);
                boolean enter     = (keyCode == 66 /* KEYCODE_ENTER */
                                     || keyCode == 160 /* KEYCODE_NUMPAD_ENTER */);
                if (action == KeyEvent.ACTION_DOWN) {
                    try {
                        if (printable) { ic.commitText(String.valueOf((char) unicodeChar), 1); return; }
                        if (backspace) { ic.deleteSurroundingText(1, 0); return; }
                        // ENTER on a field that declares an editor action is
                        // performEditorAction, not a key event -- that is what a
                        // real IME does, and Mac.apk IS the IME here. The rule and
                        // its citations live in
                        // InputMethodManager.macapkImeActionForEnter(); NONE means
                        // "plain newline", so we fall through to key dispatch and
                        // the editor inserts it itself.
                        //
                        // Why this is ADDITIVE rather than a replacement: Compose's
                        // own key handler already maps a raw KEYCODE_ENTER to
                        // KeyCommand.NEW_LINE -> the single-line ime action, and
                        // that path demonstrably works (measured on Aurora Store
                        // 4.8.4's search box, 2026-08-23). So we only consume the
                        // key when performEditorAction actually reports that it
                        // handled it; a `false` (AOSP's InputConnection default,
                        // InputConnection.java:106) falls straight through to the
                        // dispatch below and nothing that worked before changes.
                        if (enter) {
                            int act = android.view.inputmethod.InputMethodManager
                                        .macapkImeActionForEnter();
                            if (act != android.view.inputmethod.EditorInfo.IME_ACTION_NONE
                                    && ic.performEditorAction(act)) {
                                sImeEnterConsumed = true;
                                return;
                            }
                        }
                    } catch (Throwable thr) { NativeLog.w(TAG, "ic key: " + thr); }
                } else if (action == KeyEvent.ACTION_UP) {
                    if (printable || backspace) return;   // swallow the matching UP
                }
            }
        }
        // The ENTER *up* that matches a down we turned into performEditorAction,
        // swallowed OUTSIDE the `ic != null` block on purpose: the editor action
        // frequently tears the field down (Aurora's search navigates away and the
        // connection is gone by the time the up arrives, measured 2026-08-23), so
        // gating this on a still-live connection would leak a lone ACTION_UP to
        // the app. An app must never observe half a keystroke -- the same rule the
        // TextView soft-key path applies just above.
        if (action == KeyEvent.ACTION_UP && sImeEnterConsumed
                && (keyCode == 66 || keyCode == 160)) {
            sImeEnterConsumed = false;
            return;
        }

        // Tilt Control (the Controls menu): while it is on, the arrow keys tilt
        // the virtual device (TiltControl -> SensorManager's motion sensors)
        // instead of reaching the guest. AFTER the text-input paths -- an arrow
        // in a focused field still moves the caret, so a live TextView target or
        // InputConnection skips this -- and BEFORE key bindings and the guest.
        if (input == null
                && android.view.inputmethod.InputMethodManager.macapkActiveConnection() == null
                && TiltControl.handleKey(action, keyCode)) {
            return;
        }
        // Arrow Keys as D-pad (the Controls menu; mutually exclusive with Tilt
        // Control): the arrows become the hat of the virtual "Mac Arrow Keys"
        // controller (ArrowPad -> GamepadBridge) instead of keyboard keys, so an
        // engine that moves only from a controller sees one steady pad. Same
        // slot and same text-field exemption as Tilt Control.
        if (input == null
                && android.view.inputmethod.InputMethodManager.macapkActiveConnection() == null
                && ArrowPad.handleKey(action, keyCode)) {
            return;
        }
        // Space Bar Taps Center (the Controls menu): Space becomes a finger
        // held at the centre of the surface. Same slot and same text-field
        // exemption, so Space still types a space into a focused field.
        if (input == null
                && android.view.inputmethod.InputMethodManager.macapkActiveConnection() == null
                && SpaceTap.handleKey(action, keyCode, sSurfaceW, sSurfaceH)) {
            return;
        }

        // Key bindings (the Controls menu): a bound key becomes a HELD touch at
        // a screen point, so a touch-only game is playable from a keyboard.
        // Checked AFTER both text-input paths above -- typing into a field must
        // always beat a game binding -- and BEFORE the normal key dispatch, so
        // the guest sees a touch instead of the raw key, never both.
        KeyBindProfiles.Profile prof = KeyBindProfiles.active();
        if (prof != null) {
            KeyBindProfiles.Binding b = prof.forKey(keyCode);
            int w = sSurfaceW, h = sSurfaceH;
            if (b != null && w > 0 && h > 0) {
                // Normalised against the SURFACE, top-left origin -- exactly the
                // space doDispatch hands the guest (px = x*scale,
                // py = (viewH - y)*scale). Using the surface rather than a
                // separate content rect keeps one convention, so a binding
                // captured from a real click round-trips unchanged.
                if (action == KeyEvent.ACTION_DOWN)
                    macapkKeyBindDown(keyCode, b.nx * w, b.ny * h);
                else
                    macapkKeyBindUp(keyCode);
                return;
            }
        }

        if (active == null) return;
        long t = android.os.SystemClock.uptimeMillis();
        KeyEvent ev = hostKeyEvent(t, action, keyCode, metaState, autoRepeat);
        if (unicodeChar != 0) ev.macapkSetUnicodeChar(unicodeChar);
        try {
            // AOSP ViewPostImeInputStage.processKeyEvent's first step
            // (ViewRootImpl.java android14 :6692): a navigation key in touch
            // mode leaves it, and when that put focus somewhere the key IS the
            // focus request and is consumed. This is what makes the first
            // arrow press on a TV-style UI select its first item.
            if (macapkCheckLeavingTouchModeAndConsume(active, ev)) return;
            boolean handled = dispatchKeyToFocusedWindow(active, ev);
            if (logThis) {
                NativeLog.i(TAG, "  -> activity.dispatchKeyEvent returned " + handled);
            }
            // The key-shortcut stage, where AOSP runs it: after the view tree
            // declined the key and before focus navigation (below).
            if (!handled) handled = macapkKeyShortcutStage(active, ev);
            // TAB moves focus, exactly where AOSP does it: ViewRootImpl's
            // ViewPostImeInputStage.processKeyEvent runs performFocusNavigation
            // ONLY after mView.dispatchKeyEvent returned false
            // (ViewRootImpl.java:6045-6060). Running it on the unhandled path is
            // what keeps a field that wants its own Tab (a code editor, a game)
            // in charge -- it consumed the key, so we never look.
            if (s_traceFocus && action == KeyEvent.ACTION_DOWN) {
                NativeLog.i(TAG, "  focus-trace: key " + keyCode + " handled=" + handled
                    + " focus=" + macapkFocusName(active));
            }
            if (!handled && action == KeyEvent.ACTION_DOWN) {
                // NOT gated on logThis: LOG_CAP (64) saturates within the first
                // seconds of a boot, and an absent line was about to be read as
                // "TAB never reached the guest". A rare navigation key is worth
                // one uncapped line.
                macapkFocusNavigate(active, ev, keyCode == 61);
            }
        } catch (Throwable thr) {
            NativeLog.w(TAG, "dispatchKeyEvent threw: " + thr);
        }
    }

    /** MACAPK_TRACE_FOCUS=1: one line per key DOWN naming the focused view
     *  after the tree's dispatch, and one after focus navigation. Diagnostic. */
    private static final boolean s_traceFocus = "1".equals(System.getenv("MACAPK_TRACE_FOCUS"));

    /** AOSP ViewRootImpl.checkForLeavingTouchModeAndConsume (android14
     *  :7911-7945): only in touch mode, only on DOWN, not with
     *  FLAG_KEEP_TOUCH_MODE. A navigation key with no modifiers leaves touch
     *  mode and is consumed when that moved focus; a typing key leaves it and
     *  is still delivered. */
    private static boolean macapkCheckLeavingTouchModeAndConsume(Activity a, KeyEvent ev) {
        if (!android.view.ViewRootImpl.macapkInTouchMode()) return false;
        int action = ev.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_MULTIPLE) return false;
        if ((ev.getFlags() & KeyEvent.FLAG_KEEP_TOUCH_MODE) != 0) return false;
        android.view.View root = macapkDecor(a);
        if (ev.hasNoModifiers() && macapkIsNavigationKey(ev.getKeyCode())) {
            boolean took = android.view.ViewRootImpl.macapkEnsureTouchMode(root, false);
            // One uncapped line per touch-mode exit (rare: once per switch from
            // the mouse to the keyboard). A "nothing" with 0 focusables is a
            // window that offers no focus target, not a key-path failure.
            int offered = -1;
            try {
                if (!took && root instanceof android.view.ViewGroup vg) {
                    offered = vg.getFocusables(android.view.View.FOCUS_DOWN).size();
                }
            } catch (Throwable ignored) { }
            NativeLog.i(TAG, "touch mode left on key " + ev.getKeyCode() + ": focus "
                + (took ? "-> " + macapkFocusName(a) : "unchanged (" + macapkFocusName(a)
                    + ", window offers " + offered + " focusable(s))"));
            return took;
        }
        if (ev.getUnicodeChar() > 0) {
            android.view.ViewRootImpl.macapkEnsureTouchMode(root, false);
        }
        return false;
    }

    /** AOSP ViewRootImpl.isNavigationKey (android14 :7877-7894). */
    private static boolean macapkIsNavigationKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_PAGE_UP: case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MOVE_HOME: case KeyEvent.KEYCODE_MOVE_END:
            case KeyEvent.KEYCODE_TAB: case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_ENTER:
                return true;
            default:
                return false;
        }
    }

    /** AOSP processPointerEvent (android14 :6733-6737): a DOWN or a SCROLL
     *  from any pointer device enters touch mode. Called on the delivery path
     *  of every host pointer event. */
    static void macapkEnterTouchModeForPointer(Activity a, int action) {
        if (android.view.ViewRootImpl.macapkInTouchMode()) return;
        if (action != android.view.MotionEvent.ACTION_DOWN
                && action != android.view.MotionEvent.ACTION_SCROLL) return;
        android.view.ViewRootImpl.macapkEnsureTouchMode(macapkDecor(a), true);
    }

    /** The root of the tree that holds the window's keyboard focus: what
     *  AOSP calls ViewRootImpl.mView. Activity.dispatchKeyEvent walks the
     *  CONTENT view's tree (our content view is normally the render root and
     *  the decor a detached stub, Activity.macapkAttachDecor), so focus has to
     *  be searched and restored there; the decor only when it IS that tree's
     *  top (AppCompat's sub-decor, the screen_title decor) or there is no
     *  content yet. Using getDecorView() here searched an empty stub on every
     *  plain Activity (measured: SmartTube, "window offers 0 focusable(s)"). */
    private static android.view.View macapkDecor(Activity a) {
        if (a == null) return null;
        try {
            android.view.View cv = a.macapkContentView();
            if (cv != null) return cv.getRootView();
            android.view.Window w = a.getWindow();
            return (w == null) ? null : w.getDecorView();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String macapkFocusName(Activity a) {
        android.view.View root = macapkDecor(a);
        android.view.View f = (root == null) ? null : root.findFocus();
        return f == null ? "nothing" : f.getClass().getName() + "@"
            + Integer.toHexString(System.identityHashCode(f));
    }

    /** AOSP ViewRootImpl.performFocusNavigation (android14 :6836-6901): the
     *  arrows and Tab move focus once the view tree declined the key.
     *
     *  SCOPED DELTA from AOSP: the arrows are NOT taken from a focused TEXT
     *  EDITOR. AOSP's editor consumes them first (ArrowKeyMovementMethod moves
     *  the caret and only lets the key through at the text's edge), while ours
     *  is still a stub that consumes nothing, so taking the arrows here would
     *  yank focus out of a field mid-edit. Tab has no caret meaning and moves
     *  focus from anywhere. Remove the editor guard once ArrowKeyMovementMethod
     *  is real.
     *
     *  ⚠ The whole chain under this was already present and correct and simply
     *  unreachable: `View.focusSearch(int)` returned null unconditionally until
     *  v1.0.1608. Do not add navigation cases here without checking the entry
     *  point they depend on actually resolves. */
    private static void macapkFocusNavigate(Activity a, KeyEvent ev, boolean logThis) {
        final int keyCode = ev.getKeyCode();
        int direction = 0;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (ev.hasNoModifiers()) direction = android.view.View.FOCUS_LEFT;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (ev.hasNoModifiers()) direction = android.view.View.FOCUS_RIGHT;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (ev.hasNoModifiers()) direction = android.view.View.FOCUS_UP;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (ev.hasNoModifiers()) direction = android.view.View.FOCUS_DOWN;
                break;
            case KeyEvent.KEYCODE_TAB:
                // META_SHIFT_ON = 0x1: Shift+Tab walks backwards, as everywhere else.
                if (ev.hasNoModifiers()) direction = android.view.View.FOCUS_FORWARD;
                else if (ev.hasModifiers(KeyEvent.META_SHIFT_ON))
                    direction = android.view.View.FOCUS_BACKWARD;
                break;
            default:
                break;
        }
        if (direction == 0) return;
        try {
            android.view.View root = macapkDecor(a);
            if (root == null) return;
            android.view.View focused = root.findFocus();
            if (keyCode != KeyEvent.KEYCODE_TAB && focused instanceof android.widget.TextView tv
                    && tv.onCheckIsTextEditor()) {
                return;
            }
            if (focused == null) {
                // AOSP: nothing focused -> restoreDefaultFocus.
                boolean ok = root.restoreDefaultFocus();
                if (logThis) NativeLog.i(TAG, "  -> focus nav: nothing focused, default focus "
                    + (ok ? "-> " + macapkFocusName(a) : "found nothing"));
                return;
            }
            android.view.View next = focused.focusSearch(direction);
            if (next != null && next != focused) {
                android.graphics.Rect r = new android.graphics.Rect();
                focused.getFocusedRect(r);
                if (root instanceof android.view.ViewGroup vg) {
                    try {
                        vg.offsetDescendantRectToMyCoords(focused, r);
                        vg.offsetRectIntoDescendantCoords(next, r);
                    } catch (Throwable ignored) {
                        r = null;
                    }
                }
                boolean ok = next.requestFocus(direction, r);
                // Both witnesses are UNCAPPED for Tab (rare, and the v1.0.1608
                // capped trace nearly produced a wrong verdict).
                if (logThis) NativeLog.i(TAG, "  -> TAB focus " + (ok ? "moved to " : "DECLINED by ")
                    + next.getClass().getName() + "@"
                    + Integer.toHexString(System.identityHashCode(next)));
                if (s_traceFocus) NativeLog.i(TAG, "  focus-trace: nav dir=" + direction
                    + " -> " + next.getClass().getName() + (ok ? " took" : " DECLINED")
                    + " focus=" + macapkFocusName(a));
                if (ok) return;
            } else if (logThis) {
                int offered = -1;
                try {
                    if (root instanceof android.view.ViewGroup vg) {
                        offered = vg.getFocusables(direction).size();
                    }
                } catch (Throwable ignored) { }
                NativeLog.i(TAG, "  -> TAB: no next focusable (focused="
                    + focused.getClass().getName() + ", window offered " + offered
                    + " focusable(s))");
            }
            // AOSP: give the focused view a last chance to handle the key.
            boolean moved = root.dispatchUnhandledMove(focused, direction);
            if (s_traceFocus) NativeLog.i(TAG, "  focus-trace: nav dir=" + direction
                + " next=" + (next == null ? "null" : next.getClass().getName())
                + " unhandledMove=" + moved + " focus=" + macapkFocusName(a));
        } catch (Throwable thr) {
            NativeLog.w(TAG, "focusNavigate: " + thr);
        }
    }

    // Renderer-direct dispatch state. We cache the discovered renderer
    // + method handles so the per-event reflection cost is amortized.
    private static Object  s_renderer;
    /** The Activity the binding was made for. v1.0.1639: the binding used to
     *  be process-lifetime, so after a relaunch or in a second activity every
     *  click still went to the FIRST renderer (from a canvas activity, on the
     *  loop thread). Now re-resolved whenever the active Activity changes. */
    private static Activity s_boundActivity;
    /** True when the renderer's host view queues onto a queue that is drained
     *  every frame, so its onTouchEvent already delivers the touch. */
    private static boolean s_queueServiced;
    /** The view that holds s_renderer. Read per event by the fork check. */
    private static View    s_owner;
    /** MACAPK_TOUCH_APP_QUEUE_ONLY (default ON, 2026-09-27): no direct
     *  delivery while the bound host is a vendored-GLSurfaceView fork whose
     *  queue SurfaceView drains to empty every frame
     *  (SurfaceView.macapkForkGlQueueServiced): the app's own onTouchEvent has
     *  already queued the same touch there, so a direct call is a second copy
     *  (measured: every HCR touch arrived twice, hcr_run.log 2026-09-25
     *  :46444-46445). Asked per EVENT, not at bind time, because the answer
     *  changes: the fork's thread dies seconds into boot, and a re-attach can
     *  put a live successor in its place. =0 restores the v1.0.1639 rule. */
    private static final boolean s_appQueueOnly =
        !"0".equals(System.getenv("MACAPK_TOUCH_APP_QUEUE_ONLY"));
    /** MACAPK_GLTHREAD_DRAIN_TRACE=1 (the SurfaceView fork-drain trace, which
     *  logs EVERY drained runnable): lift LOG_CAP on the renderer-direct lines
     *  too, so a touch A/B can count direct vs drained deliveries one to one
     *  past the first 64 events. Diagnostic; changes no delivery. */
    private static final boolean s_directTrace =
        "1".equals(System.getenv("MACAPK_GLTHREAD_DRAIN_TRACE"));
    /** Last logged fork-suppression state (-1 none yet); log transitions only. */
    private static int s_forkSuppressState = -1;
    private static final AtomicInteger s_forkSuppressLogN = new AtomicInteger(0);
    private static Method  s_handleDown;
    private static Method  s_handleUp;
    private static Method  s_handleMove;
    private static final AtomicInteger s_directLogN = new AtomicInteger(0);
    /** Indexed touch ID: cocos2d-x's nativeTouchesBegin takes an int id
     *  (we use 0 since macOS mouse is a single pointer). */
    private static final int FAKE_POINTER_ID = 0;

    private static void dispatchToRenderer(Activity active, int action, float px, float py) {
        dispatchToRenderer(active, action, px, py, FAKE_POINTER_ID);
    }

    /** `pointerId` is the id of the pointer this event is ABOUT.
     *
     *  v1.0.833: this used to be hard-wired to FAKE_POINTER_ID and to handle
     *  only ACTION_DOWN/UP/MOVE, so ACTION_POINTER_DOWN (5) and
     *  ACTION_POINTER_UP (6) fell into `default: return` and were **silently
     *  dropped**. The keybind layer above already builds correct multi-pointer
     *  MotionEvents, but this direct path (then the ONLY delivery for a
     *  fork host, whose queued copy was believed never to run) dropped them,
     *  so on that engine every touch after the first was invisible.
     *  (CORRECTED 2026-09-27: cocos2d-x v2 DOES read onTouchEvent -- all three
     *  HCR builds' Cocos2dxGLSurfaceView.onTouchEvent queueEvent the renderer's
     *  handleAction* six ways, $6 POINTER_DOWN ... $11 CANCEL, with the real
     *  pointer id; javap of the converted classes.jar.)
     *
     *  Concrete symptom (Hill Climb Racing, arrow keys bound to gas/brake):
     *  hold gas, then press brake -> the brake's POINTER_DOWN is dropped; then
     *  release gas -> that POINTER_UP is dropped too. Nothing further is ever
     *  sent, so the engine still believes only gas is held and the brake never
     *  applies. Releasing both and pressing brake alone worked, which is why it
     *  looked intermittent rather than structural.
     *
     *  cocos2d-x's handleActionDown/Up already take an int id, so the fix is to
     *  pass the REAL pointer id and treat the POINTER_ forms as down/up for
     *  that id -- which is exactly what nativeTouchesBegin/End expect. Generic:
     *  any engine reached through this path gains multi-touch. */
    private static void dispatchToRenderer(Activity active, int action,
                                           float px, float py, int pointerId) {
        ensureRendererResolved(active);
        if (s_renderer == null || s_queueServiced) return;
        if (s_appQueueOnly && forkQueueDelivers(s_owner)) return;
        // v1.0.996: px/py arrive in DISPLAY space (doDispatch's Y-flip is against
        // the whole view; the keybind path denormalises against the published
        // display surface). The renderer's surface is the app WINDOW, whose
        // origin sits at the TOP content inset -- so a window-relative y is
        // display y minus that inset, exactly the shift WindowOverlayStack's
        // deliverTouch already applies for content-space overlay bounds.
        //
        // Zero for every fullscreen game (heightPx returns 0 under
        // FLAG_FULLSCREEN / a hidden status bar), so hillclimb and every
        // immersive title are byte-identical; it only corrects a GL app that
        // keeps its status bar, which before v1.0.996 was fed a y that was one
        // bar-height too large against a surface it believed was full-display.
        // Keep it in PIXELS -- v1.0.742 was a points-vs-pixels drift in this
        // exact seam.
        py -= StatusBarDriver.windowBoxInsetTopPx(null);
        // v1.0.997: the same correction on X. The window's origin gains an x
        // term the moment a LEFT inset can be non-zero. It cannot today --
        // StatusBarDriver.navBarPosition never returns NAV_BAR_LEFT because we
        // cannot tell ROTATION_270 from ROTATION_90 -- so this is a literal
        // no-op right now, and it is here so the seam is correct BY
        // CONSTRUCTION rather than by that accident. A right-edge bar needs no
        // shift: it moves the window's far edge, not its origin.
        px -= StatusBarDriver.windowBoxInsetLeftPx(null);
        Method m;
        Object[] args;
        switch (action) {
            case 0:                              // ACTION_DOWN
            case MotionEvent.ACTION_POINTER_DOWN: // 5: an additional finger
                m = s_handleDown;
                args = new Object[]{pointerId, px, py};
                break;
            case 1:                            // ACTION_UP
            case MotionEvent.ACTION_POINTER_UP: // 6: one finger of several lifted
            case 3:                            // ACTION_CANCEL -> release it
                m = s_handleUp;
                args = new Object[]{pointerId, px, py};
                break;
            case 2: // ACTION_MOVE
                m = s_handleMove;
                args = new Object[]{new int[]{pointerId},
                                     new float[]{px}, new float[]{py}};
                break;
            default: return;
        }
        if (m == null) return;
        try {
            m.invoke(s_renderer, args);
            int n = s_directLogN.getAndIncrement();
            if (n < LOG_CAP || s_directTrace) {
                NativeLog.i(TAG, "renderer-direct " + m.getName() + " ok (px="
                    + px + " py=" + py + ")");
            }
        } catch (Throwable t) {
            int n = s_directLogN.getAndIncrement();
            if (n < LOG_CAP || s_directTrace) {
                NativeLog.w(TAG, "renderer-direct " + m.getName() + " threw " + t);
            }
        }
    }

    /** Walk the active activity's view tree (depth-first) looking for a
     *  SurfaceView (or subclass) with a mRenderer / renderer field whose
     *  value declares handleActionDown(int,float,float). Caches results
     *  for fast repeat dispatch. */
    private static void ensureRendererResolved(Activity active) {
        if (s_renderer != null && s_boundActivity == active) return;
        s_renderer = null;
        s_handleDown = s_handleUp = s_handleMove = null;
        s_boundActivity = null;
        s_queueServiced = false;
        s_owner = null;
        View root = active.macapkContentView();
        if (root == null) return;
        Object[] ownerAndRenderer = findRendererInTree(root);
        if (ownerAndRenderer == null) return;
        View owner = (View) ownerAndRenderer[0];
        Object found = ownerAndRenderer[1];
        Method down = findMethod(found.getClass(), "handleActionDown",
                                  new Class<?>[]{int.class, float.class, float.class});
        Method up   = findMethod(found.getClass(), "handleActionUp",
                                  new Class<?>[]{int.class, float.class, float.class});
        Method mv   = findMethod(found.getClass(), "handleActionMove",
                                  new Class<?>[]{int[].class, float[].class, float[].class});
        if (down == null && up == null && mv == null) return;
        // s_renderer last: it is the "bound" flag another input thread reads.
        s_queueServiced = hostQueueServiced(owner);
        s_owner = owner;
        s_handleDown = down;
        s_handleUp   = up;
        s_handleMove = mv;
        s_boundActivity = active;
        s_renderer   = found;
        NativeLog.i(TAG, "renderer-direct: bound to " + found.getClass().getName()
            + " (down=" + (down != null) + " up=" + (up != null)
            + " move=" + (mv != null) + ")"
            + (s_queueServiced ? " -- SUPPRESSED: " + owner.getClass().getName()
                + ".queueEvent is serviced every GL frame, onTouchEvent delivers"
                : " host=" + owner.getClass().getName()));
    }

    /** Structural, not timed: the host is OUR android.opengl.GLSurfaceView,
     *  no class between it and GLSurfaceView overrides queueEvent(Runnable) (so
     *  work lands in the per-frame-drained GLOBAL_QUEUE), and the drain is on.
     *  A vendored fork (extends SurfaceView, own GLThread) fails the first test;
     *  whether ITS queue delivers is asked per event by forkQueueDelivers
     *  (MACAPK_TOUCH_APP_QUEUE_ONLY), because that answer changes over the
     *  fork's life. */
    private static boolean hostQueueServiced(View owner) {
        if (!android.opengl.GLSurfaceView.macapkGlQueueDrainEnabled()) return false;
        if (!(owner instanceof android.opengl.GLSurfaceView)) return false;
        for (Class<?> c = owner.getClass();
             c != null && c != android.opengl.GLSurfaceView.class;
             c = c.getSuperclass()) {
            try {
                c.getDeclaredMethod("queueEvent", Runnable.class);
                return false;
            } catch (NoSuchMethodException ignore) {
            } catch (Throwable t) {
                return false;
            }
        }
        return true;
    }

    /** MACAPK_TOUCH_APP_QUEUE_ONLY: does the bound host's OWN queue deliver
     *  this touch? True only for a SurfaceView fork whose current GL thread
     *  SurfaceView drains for (see SurfaceView.macapkForkGlQueueServiced). Logs
     *  each change of answer (bounded), so a run shows when delivery moved
     *  from the direct call to the app's queue and back. */
    private static boolean forkQueueDelivers(View owner) {
        if (!(owner instanceof android.view.SurfaceView)) return false;
        boolean yes;
        try {
            yes = ((android.view.SurfaceView) owner).macapkForkGlQueueServiced();
        } catch (Throwable t) {
            yes = false;
        }
        int st = yes ? 1 : 0;
        if (st != s_forkSuppressState) {
            s_forkSuppressState = st;
            if (s_forkSuppressLogN.getAndIncrement() < LOG_CAP || s_directTrace) {
                NativeLog.i(TAG, "renderer-direct: " + (yes
                    ? "SUPPRESSED -- " + owner.getClass().getName()
                        + "'s own GL-thread queue is drained every frame, onTouchEvent delivers"
                    : "delivering -- " + owner.getClass().getName()
                        + "'s GL-thread queue is not drained by us right now"));
            }
        }
        return yes;
    }

    /** {owner View, renderer}, or null. */
    private static Object[] findRendererInTree(View root) {
        if (root == null) return null;
        Object hit = extractRenderer(root);
        if (hit != null) return new Object[]{root, hit};
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            int n = vg.getChildCount();
            for (int i = 0; i < n; ++i) {
                Object[] r = findRendererInTree(vg.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    private static Object extractRenderer(View v) {
        Class<?> cls = v.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                String name = f.getName();
                // Heuristic: cocos2d-x stores its renderer in mRenderer.
                // Other engines might use `renderer` / `mGameRenderer`.
                if (!name.equals("mRenderer") && !name.equals("renderer")
                    && !name.equals("mGameRenderer")) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(v);
                    if (val == null) continue;
                    // Validate: does it have handleActionDown?
                    if (findMethod(val.getClass(), "handleActionDown",
                                    new Class<?>[]{int.class, float.class, float.class}) != null) {
                        return val;
                    }
                } catch (Throwable ignore) {}
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>[] params) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                if (m != null) { m.setAccessible(true); return m; }
            } catch (NoSuchMethodException ignore) {}
            c = c.getSuperclass();
        }
        return null;
    }
}
