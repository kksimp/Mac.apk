import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.PorterDuff;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.transition.Fade;
import android.transition.Transition;
import android.view.View;
import android.view.Window;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

/** Regression tests for HOTAPI-003 runtime fixes and preserved AOSP contracts. */
public class HotApiBatch3Test {
    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    public static void main(String[] args) {
        Activity act = new Activity();
        Context ctx = act;

        // 1. Window transition setters/getters + elevation + navBarDividerColor on ActivityWindow & DialogWindow
        Window aw = act.getWindow();
        Dialog dlg = new Dialog(ctx);
        Window dw = dlg.getWindow();
        check(aw != null && dw != null, "Activity/Dialog window must be non-null");

        for (Window w : new Window[] {aw, dw}) {
            check(w.getSharedElementsUseOverlay(), "default SharedElementsUseOverlay must be true");
            check(w.getAllowEnterTransitionOverlap(), "default AllowEnterTransitionOverlap must be true");
            check(w.getAllowReturnTransitionOverlap(), "default AllowReturnTransitionOverlap must be true");
            check(w.getTransitionBackgroundFadeDuration() == 0L,
                    "default TransitionBackgroundFadeDuration must be 0L");
            check(w.getEnterTransition() == null && w.getExitTransition() == null,
                    "default transitions must be null");

            Transition enter = new Fade();
            Transition ret = new Fade();
            Transition exit = new Fade();
            Transition reenter = new Fade();
            Transition seEnter = new Fade();
            Transition seRet = new Fade();
            Transition seExit = new Fade();
            Transition seReenter = new Fade();

            w.setEnterTransition(enter);
            w.setReturnTransition(ret);
            w.setExitTransition(exit);
            w.setReenterTransition(reenter);
            w.setSharedElementEnterTransition(seEnter);
            w.setSharedElementReturnTransition(seRet);
            w.setSharedElementExitTransition(seExit);
            w.setSharedElementReenterTransition(seReenter);
            w.setSharedElementsUseOverlay(false);
            w.setAllowEnterTransitionOverlap(false);
            w.setAllowReturnTransitionOverlap(false);
            w.setTransitionBackgroundFadeDuration(275L);

            check(w.getEnterTransition() == enter, "EnterTransition mismatch");
            check(w.getReturnTransition() == ret, "ReturnTransition mismatch");
            check(w.getExitTransition() == exit, "ExitTransition mismatch");
            check(w.getReenterTransition() == reenter, "ReenterTransition mismatch");
            check(w.getSharedElementEnterTransition() == seEnter, "SharedElementEnterTransition mismatch");
            check(w.getSharedElementReturnTransition() == seRet, "SharedElementReturnTransition mismatch");
            check(w.getSharedElementExitTransition() == seExit, "SharedElementExitTransition mismatch");
            check(w.getSharedElementReenterTransition() == seReenter, "SharedElementReenterTransition mismatch");
            check(!w.getSharedElementsUseOverlay(), "SharedElementsUseOverlay did not stick");
            check(!w.getAllowEnterTransitionOverlap(), "AllowEnterTransitionOverlap did not stick");
            check(!w.getAllowReturnTransitionOverlap(), "AllowReturnTransitionOverlap did not stick");
            check(w.getTransitionBackgroundFadeDuration() == 275L,
                    "TransitionBackgroundFadeDuration did not stick");

            w.setEnterTransition(null);
            check(w.getEnterTransition() == null, "clearing EnterTransition failed");

            check(w.getNavigationBarDividerColor() == 0, "default NavigationBarDividerColor must be 0");
            w.setNavigationBarDividerColor(0xFF334455);
            check(w.getNavigationBarDividerColor() == 0xFF334455,
                    "NavigationBarDividerColor round-trip failed");
        }

        aw.setElevation(12.5f);
        check(aw.getElevation() == 12.5f, "ActivityWindow elevation mismatch: " + aw.getElevation());
        dw.setElevation(18.0f);
        check(dw.getElevation() == 18.0f, "DialogWindow elevation mismatch: " + dw.getElevation());

        // 2. AccessibilityNodeInfo multi-label bookkeeping, source identity & AOSP disconnected contracts
        View label1 = new View(ctx);
        View label2 = new View(ctx);
        View label3 = new View(ctx);
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        check(node.getLabeledByList().isEmpty(), "fresh node getLabeledByList must be empty");

        node.addLabeledBy(label1);
        node.addLabeledBy(label2);
        node.addLabeledBy(label1); // duplicate ignored
        node.addLabeledBy(label3, 7);
        List<AccessibilityNodeInfo> labels = node.getLabeledByList();
        check(labels.size() == 3, "expected 3 labels in getLabeledByList(), got " + labels.size());

        check(!node.removeLabeledBy(label3, 99),
                "removing nonexistent virtualDescendantId must return false");
        check(node.removeLabeledBy(label1), "removeLabeledBy(View) must return true");
        check(node.removeLabeledBy(label3, 7), "removeLabeledBy(View, int) must return true");
        check(node.getLabeledByList().size() == 1,
                "expected 1 remaining label, got " + node.getLabeledByList().size());

        AccessibilityNodeInfo srcA = AccessibilityNodeInfo.obtain(label1);
        AccessibilityNodeInfo srcB = AccessibilityNodeInfo.obtain(label1);
        AccessibilityNodeInfo srcV = AccessibilityNodeInfo.obtain(label1, 42);
        check(srcA.equals(srcB) && srcA.hashCode() == srcB.hashCode(),
                "nodes obtained from same View must be equal");
        check(!srcA.equals(srcV), "virtual descendant node must differ from host view node");
        srcB.setSource(label1, 42);
        check(srcB.equals(srcV), "setSource(View, int) did not update node identity");

        node.setParent(label1);
        node.setTraversalBefore(label2);
        node.setTraversalAfter(label2, 3);
        node.setLabelFor(label1);
        node.setLabeledBy(label2);
        check(node.getParent() == null && node.getTraversalBefore() == null
                && node.getTraversalAfter() == null && node.getLabelFor() == null
                && node.getLabeledBy() == null,
                "disconnected AccessibilityNodeInfo relationship getters must remain null per AOSP");

        // 3. View backgroundTintMode round-trip without perturbing BlendMode or default null
        View v = new View(ctx);
        check(v.getBackgroundTintMode() == null, "default backgroundTintMode must be null");
        v.setBackgroundTintMode(PorterDuff.Mode.SRC_ATOP);
        check(v.getBackgroundTintMode() == PorterDuff.Mode.SRC_ATOP,
                "View.setBackgroundTintMode round-trip failed");
        check(v.getBackgroundTintBlendMode() == null,
                "View.getBackgroundTintBlendMode must stay decoupled from PorterDuff setter");

        // 4. Verified AOSP inert deprecated pairs remain inert
        RingtoneManager rm = new RingtoneManager(act);
        rm.setIncludeDrm(true);
        check(!rm.getIncludeDrm(), "RingtoneManager.getIncludeDrm must remain false per API 16+ AOSP");

        AudioManager am = new AudioManager();
        am.setWiredHeadsetOn(true);
        check(!am.isWiredHeadsetOn(), "AudioManager.isWiredHeadsetOn must remain hardware state (false)");

        v.setFrameContentVelocity(120f);
        check(v.getFrameContentVelocity() == 0f,
                "View.getFrameContentVelocity must remain 0f when AttachInfo velocity API is off");

        System.out.println("PASS: HotApiBatch3Test (HOTAPI-003 fixes + AOSP inert invariants)");
    }
}
