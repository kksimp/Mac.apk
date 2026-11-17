// Mac.apk v1.0.92 -- auto-generated stub, extended below with AOSP's real
// no-op defaults (thunderbird bring-up).
// Class referenced by F-Droid's classes.jar but not previously
// implemented. Empty body so JVM-side bytecode verification of
// callers passes. NoSuchMethodError will fire lazily for any method
// callers invoke; add signatures on demand.
//
// AOSP: frameworks/base/core/java/android/view/accessibility/
// AccessibilityNodeProvider.java (android11-release). Real AOSP declares
// this class abstract with five virtual-accessibility-tree callbacks that
// each already ship a harmless no-op default (subclasses override only the
// ones they need); we mirror the three that a concrete subclass in the
// wild actually overrides today, verified by disassembling thunderbird's
// converted classes.jar: com.takisoft.datetimepicker.widget.NumberPicker
// $AccessibilityNodeProviderImpl extends this class directly and overrides
// createAccessibilityNodeInfo / findAccessibilityNodeInfosByText /
// performAction. Any invokevirtual against a variable statically typed as
// AccessibilityNodeProvider (rather than the concrete subclass) resolves
// against THIS class's method table, so the base defaults have to exist
// here even though the subclass always overrides them. The remaining two
// AOSP methods (addExtraDataToAccessibilityNodeInfo, findFocus) are not
// yet exercised by any known caller; add them the same way (bare AOSP
// default body) the day something needs them.
package android.view.accessibility;

public class AccessibilityNodeProvider {
    /** AOSP: the virtual view id of the host view itself. */
    public static final int HOST_VIEW_ID = -1;

    public AccessibilityNodeProvider() {}

    /** AOSP default (AccessibilityNodeProvider.java:198-200): no virtual
     *  hierarchy implemented means no node to hand back. */
    public android.view.accessibility.AccessibilityNodeInfo createAccessibilityNodeInfo(
            int virtualViewId) {
        return null;
    }

    /** AOSP default (AccessibilityNodeProvider.java:255-258): no matches
     *  without a real virtual-view search implementation. */
    public java.util.List<android.view.accessibility.AccessibilityNodeInfo>
            findAccessibilityNodeInfosByText(String text, int virtualViewId) {
        return null;
    }

    /** AOSP default (AccessibilityNodeProvider.java:237-239): unhandled,
     *  same as any accessibility action nobody implemented. */
    public boolean performAction(int virtualViewId, int action, android.os.Bundle arguments) {
        return false;
    }

    /** AOSP AccessibilityNodeProvider.findFocus(int focus): the virtual node
     *  that currently holds FOCUS_INPUT / FOCUS_ACCESSIBILITY within this
     *  provider's tree. AOSP's default body returns null -- the provider must
     *  override it -- and that is reproduced verbatim. (The class header's note
     *  saying this method is "not yet exercised" is now stale: it is part of the
     *  published API surface and is added here on that basis.) */
    public android.view.accessibility.AccessibilityNodeInfo findFocus(int focus) {
        return null;
    }

    /** AOSP AccessibilityNodeProvider.addExtraDataToAccessibilityNodeInfo(int,
     *  AccessibilityNodeInfo, String, Bundle): fill in an expensive extra
     *  (character bounds, for instance) that the provider omitted from the node
     *  by default. AOSP's default body is empty. */
    public void addExtraDataToAccessibilityNodeInfo(int virtualViewId,
            android.view.accessibility.AccessibilityNodeInfo info, String extraDataKey,
            android.os.Bundle arguments) {
    }
}
