// HostTouchTest -- the MotionEvents the Windows host's touchscreen and pen
// contacts become (com.macapk.loader.TouchDispatcher.hostTouchEvent, fed by
// windows/host/pointer_input.h's TouchTracker through dispatchHostTouch).
//
//   java -cp android-framework.jar;macapk-loader.jar;<classes> com.macapk.loader.HostTouchTest
//
// No window and no native code: it builds the events an app would be handed
// for two fingers and for a pen and reads them back the way apps do
// (getActionMasked / getActionIndex, getPointerCount, getPointerId,
// findPointerIndex, getX(i), getToolType, getPressure, the source and the
// button state), and checks that the single-pointer event the host mouse
// path builds is still a plain finger.
// Prints PASS/FAIL per check and "ALL PASSED" at the end; exits 1 on a failure.
package com.macapk.loader;

import android.view.InputDevice;
import android.view.MotionEvent;

public class HostTouchTest {
    static int failures;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }

    static final int FINGER = MotionEvent.TOOL_TYPE_FINGER;

    public static void main(String[] args) {
        // Two thumbs: the second lands while the first is down.
        MotionEvent down = TouchDispatcher.hostTouchEvent(1000L, 1000L, MotionEvent.ACTION_DOWN, 0,
                new int[]{0}, new float[]{100f}, new float[]{500f}, new float[]{1f}, new int[]{FINGER}, 0);
        check("DOWN: one pointer, id 0, a finger on the touchscreen",
                down.getActionMasked() == MotionEvent.ACTION_DOWN && down.getPointerCount() == 1
                        && down.getPointerId(0) == 0 && down.getToolType(0) == FINGER
                        && down.getSource() == InputDevice.SOURCE_TOUCHSCREEN
                        && down.getX() == 100f && down.getY() == 500f && down.getDeviceId() != 0);

        MotionEvent second = TouchDispatcher.hostTouchEvent(1000L, 1040L, MotionEvent.ACTION_POINTER_DOWN, 1,
                new int[]{0, 1}, new float[]{100f, 700f}, new float[]{500f, 520f}, new float[]{1f, 0.5f},
                new int[]{FINGER, FINGER}, 0);
        check("POINTER_DOWN: the masked action and the index of the pointer that landed",
                second.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN && second.getActionIndex() == 1
                        && second.getAction() == (MotionEvent.ACTION_POINTER_DOWN
                                | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT)));
        check("both pointers, each with its own id and place",
                second.getPointerCount() == 2 && second.getPointerId(0) == 0 && second.getPointerId(1) == 1
                        && second.getX(0) == 100f && second.getY(0) == 500f
                        && second.getX(1) == 700f && second.getY(1) == 520f
                        && second.getPressure(1) == 0.5f);
        check("the gesture keeps its down time", second.getDownTime() == 1000L && second.getEventTime() == 1040L);

        // The first thumb lifted; the one that stays is id 1 at index 0.
        MotionEvent move = TouchDispatcher.hostTouchEvent(1000L, 1100L, MotionEvent.ACTION_MOVE, 0,
                new int[]{1}, new float[]{690f}, new float[]{530f}, new float[]{1f}, new int[]{FINGER}, 0);
        check("a pointer keeps its id when another lifts: findPointerIndex finds it",
                move.getActionMasked() == MotionEvent.ACTION_MOVE && move.getPointerCount() == 1
                        && move.getPointerId(0) == 1 && move.findPointerIndex(1) == 0
                        && move.findPointerIndex(0) == -1 && move.getX(move.findPointerIndex(1)) == 690f);

        MotionEvent up = TouchDispatcher.hostTouchEvent(1000L, 1200L, MotionEvent.ACTION_POINTER_UP, 0,
                new int[]{0, 1, 2}, new float[]{1f, 2f, 3f}, new float[]{4f, 5f, 6f}, new float[]{1f, 1f, 1f},
                new int[]{FINGER, FINGER, FINGER}, 0);
        check("POINTER_UP at index 0 of three", up.getActionMasked() == MotionEvent.ACTION_POINTER_UP
                && up.getActionIndex() == 0 && up.getPointerCount() == 3 && up.getX(2) == 3f && up.getY(2) == 6f);

        // A pen with its barrel button held, then turned over.
        MotionEvent pen = TouchDispatcher.hostTouchEvent(2000L, 2000L, MotionEvent.ACTION_DOWN, 0,
                new int[]{0}, new float[]{10f}, new float[]{20f}, new float[]{0.25f},
                new int[]{MotionEvent.TOOL_TYPE_STYLUS}, MotionEvent.BUTTON_STYLUS_PRIMARY);
        check("a pen: TOOL_TYPE_STYLUS, its pressure, BUTTON_STYLUS_PRIMARY, a stylus on the touchscreen",
                pen.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS && pen.getPressure() == 0.25f
                        && pen.getButtonState() == MotionEvent.BUTTON_STYLUS_PRIMARY
                        && pen.isFromSource(InputDevice.SOURCE_STYLUS)
                        && pen.isFromSource(InputDevice.SOURCE_TOUCHSCREEN));
        MotionEvent eraser = TouchDispatcher.hostTouchEvent(2000L, 2000L, MotionEvent.ACTION_DOWN, 0,
                new int[]{0}, new float[]{10f}, new float[]{20f}, new float[]{1f},
                new int[]{MotionEvent.TOOL_TYPE_ERASER}, 0);
        check("the pen turned over: TOOL_TYPE_ERASER", eraser.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
                && eraser.isFromSource(InputDevice.SOURCE_STYLUS));

        // The host mouse's left button, as it was.
        MotionEvent finger = TouchDispatcher.touchEvent(3000L, 3000L, MotionEvent.ACTION_DOWN, 5f, 6f, 0);
        check("the mouse's left button is still one plain finger",
                finger.getPointerCount() == 1 && finger.getPointerId(0) == 0 && finger.getToolType(0) == FINGER
                        && finger.getPressure() == 1f && finger.getButtonState() == 0
                        && finger.getSource() == InputDevice.SOURCE_TOUCHSCREEN);

        // The wheel's scroll step.
        MotionEvent wheel = TouchDispatcher.wheelEvent(50f, 60f, 1f, -0.5f);
        check("a wheel step: ACTION_SCROLL from a mouse with both axes",
                wheel.getActionMasked() == MotionEvent.ACTION_SCROLL && wheel.isFromSource(InputDevice.SOURCE_MOUSE)
                        && wheel.getAxisValue(MotionEvent.AXIS_VSCROLL) == 1f
                        && wheel.getAxisValue(MotionEvent.AXIS_HSCROLL) == -0.5f
                        && !TouchDispatcher.isTouchDispatch(wheel) && TouchDispatcher.isTouchDispatch(second));

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
