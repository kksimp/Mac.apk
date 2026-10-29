// android.app.DialogFragment -- a Fragment that hosts a Dialog.
// Deprecated in API-28 in favor of androidx.fragment.app.DialogFragment,
// but legacy apps subclass the platform version. AOSP API-11+.
//
// AOSP ref: frameworks/base/core/java/android/app/DialogFragment.java
// (extends Fragment implements DialogInterface.OnCancelListener,
// DialogInterface.OnDismissListener). We mirror the public surface:
// onCreateDialog, show/dismiss, the STYLE_* constants, and the dialog
// accessors, and AOSP's lifecycle: the dialog is created in
// onGetLayoutInflater, filled with the fragment's view in onActivityCreated,
// shown in onStart, hidden in onStop and dismissed in onDestroyView.
package android.app;

import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

public class DialogFragment extends Fragment
        implements DialogInterface.OnCancelListener, DialogInterface.OnDismissListener {

    public static final int STYLE_NORMAL       = 0;
    public static final int STYLE_NO_TITLE     = 1;
    public static final int STYLE_NO_FRAME     = 2;
    public static final int STYLE_NO_INPUT     = 3;

    private int mStyle = STYLE_NORMAL;
    private int mTheme = 0;
    private boolean mCancelable = true;
    private boolean mShowsDialog = true;
    private Dialog mDialog;
    private boolean mViewDestroyed;
    private boolean mDismissed;
    private boolean mShownByMe;

    public DialogFragment() {}

    public void setStyle(int style, int theme) { mStyle = style; if (theme != 0) mTheme = theme; }

    /** AOSP show(FragmentManager,String): only "adds the fragment to the
     *  given FragmentManager" (`ft.add(this, tag); ft.commit();`). The
     *  Dialog itself is created, filled and shown by the fragment lifecycle
     *  (onGetLayoutInflater / onActivityCreated / onStart below), exactly as
     *  in AOSP -- which is what makes apps that bypass show() and commit
     *  their own add(fragment, tag) transaction (ejoysdk's
     *  BrowserDialogFragment.show does this) get a visible dialog too. */
    public void show(FragmentManager manager, String tag) {
        mDismissed = false;
        mShownByMe = true;
        FragmentTransaction ft = manager.beginTransaction();
        ft.add(this, tag);
        ft.commit();
    }
    public int show(FragmentTransaction transaction, String tag) {
        mDismissed = false;
        mShownByMe = true;
        transaction.add(this, tag);
        mViewDestroyed = false;
        return transaction.commit();
    }
    public void showNow(FragmentManager manager, String tag) {
        mDismissed = false;
        mShownByMe = true;
        FragmentTransaction ft = manager.beginTransaction();
        ft.add(this, tag);
        ft.commitNow();
    }

    // ---- AOSP DialogFragment lifecycle (frameworks/base/core/java/android/
    //      app/DialogFragment.java, API 28) ----

    @Override public void onAttach(Context context) {
        super.onAttach(context);
        if (!mShownByMe) {
            // Not shown through show(): the app added us with its own
            // transaction, so we are no longer dismissed.
            mDismissed = false;
        }
    }

    @Override public void onDetach() {
        super.onDetach();
        if (!mShownByMe && !mDismissed) {
            mDismissed = true;
        }
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // A fragment added into a container view is embedded, not a dialog.
        mShowsDialog = mContainerId == 0;
    }

    /** AOSP: when showing as a dialog, the dialog is created here, and the
     *  fragment's view is inflated with the dialog's context. */
    @Override public LayoutInflater onGetLayoutInflater(Bundle savedInstanceState) {
        if (!mShowsDialog) {
            return super.onGetLayoutInflater(savedInstanceState);
        }
        mDialog = onCreateDialog(savedInstanceState);
        if (mDialog != null) {
            setupDialog(mDialog, mStyle);
            LayoutInflater li = (LayoutInflater) mDialog.getContext()
                    .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            if (li != null) return li;
        }
        return super.onGetLayoutInflater(savedInstanceState);
    }

    /** AOSP setupDialog: applies the STYLE_* window features. */
    public void setupDialog(Dialog dialog, int style) {
        switch (style) {
            case STYLE_NO_INPUT:
                dialog.getWindow().addFlags(
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                // fall through
            case STYLE_NO_FRAME:
            case STYLE_NO_TITLE:
                dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        }
    }

    /** AOSP: the view made by onCreateView becomes the dialog's content. */
    @Override public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        if (!mShowsDialog) {
            return;
        }
        View view = getView();
        if (view != null) {
            if (view.getParent() != null) {
                throw new IllegalStateException(
                        "DialogFragment can not be attached to a container view");
            }
            mDialog.setContentView(view);
        }
        final Activity activity = getActivity();
        if (activity != null) {
            mDialog.setOwnerActivity(activity);
        }
        mDialog.setCancelable(mCancelable);
        mDialog.setOnCancelListener(this);
        mDialog.setOnDismissListener(this);
    }

    @Override public void onStart() {
        super.onStart();
        if (mDialog != null) {
            mViewDestroyed = false;
            mDialog.show();
        }
    }

    @Override public void onStop() {
        super.onStop();
        if (mDialog != null) {
            mDialog.hide();
        }
    }

    /** AOSP: remove the dialog along with the fragment's view. mViewDestroyed
     *  stops our onDismiss from removing the fragment a second time. */
    @Override public void onDestroyView() {
        super.onDestroyView();
        if (mDialog != null) {
            mViewDestroyed = true;
            mDialog.dismiss();
            mDialog = null;
        }
    }

    /** Override point: returns the Dialog this fragment displays. AOSP
     *  default returns a plain Dialog with the chosen theme. */
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        return new Dialog(getContext(), mTheme);
    }

    public void dismiss() { dismissInternal(false); }
    public void dismissAllowingStateLoss() { dismissInternal(true); }
    private void dismissInternal(boolean allowStateLoss) {
        if (mDismissed) return;
        mDismissed = true;
        mShownByMe = false;
        if (mDialog != null) {
            try { mDialog.dismiss(); } catch (Throwable ignored) {}
        }
        mViewDestroyed = true;
        // AOSP dismissInternal: pop the back-stack entry if show() pushed
        // one, else commit a remove() transaction so isAdded()/
        // getFragmentManager() clear correctly. showNow()/show() here
        // never call addToBackStack (our FragmentManagerImpl's back stack
        // is an already-disclosed no-op -- see that file's header), so
        // remove() is always the right branch: without this, a fragment
        // added by show() would stay isAdded()==true
        // forever after being dismissed.
        final FragmentManager fm = getFragmentManager();
        if (fm != null) {
            try {
                final FragmentTransaction ft = fm.beginTransaction().remove(this);
                if (allowStateLoss) ft.commitAllowingStateLoss(); else ft.commit();
            } catch (Throwable ignored) {}
        }
    }

    public Dialog getDialog() { return mDialog; }
    public boolean getShowsDialog() { return mShowsDialog; }
    public void setShowsDialog(boolean showsDialog) { mShowsDialog = showsDialog; }
    public boolean isCancelable() { return mCancelable; }
    public void setCancelable(boolean cancelable) {
        mCancelable = cancelable;
        if (mDialog != null) mDialog.setCancelable(cancelable);
    }
    public int getTheme() { return mTheme; }

    @Override public void onCancel(DialogInterface dialog) {}
    @Override public void onDismiss(DialogInterface dialog) {
        if (!mViewDestroyed) dismissInternal(true);
    }

    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return null;
    }
}
