// android.widget.AbsListView -- intermediate parent of ListView and
// GridView. Previously a no-render stub (choice-mode constants + getters,
// no recycler, no row layout). Now a real recycling list surface: it lays
// out adapter rows top-to-bottom within the viewport, recycles scrolled-off
// row Views into a scrap heap (keyed by item-view-type so heterogeneous
// adapters reuse correctly), supports drag-scroll (the macOS scroll wheel /
// trackpad arrives as a synthesized DOWN->MOVE->UP drag via cocoa_bridge,
// same path ScrollView uses), and dispatches item clicks through
// AdapterView.performItemClick.
//
// We do not run a velocity-fling animation loop (Mac.apk lays out content
// statically each frame and the in-tree OverScroller settles immediately),
// so smoothScroll/fling jump to their destination rather than animate. Drag
// scrolling is fully interactive. AOSP:
//   frameworks/base/core/java/android/widget/AbsListView.java
//   public abstract class AbsListView extends AdapterView<ListAdapter>
//     implements TextWatcher, ViewTreeObserver.OnGlobalLayoutListener, ...
package android.widget;

import android.content.Context;
import android.database.DataSetObserver;
import android.graphics.Rect;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.util.ArrayList;

// AOSP: public abstract class AbsListView extends AdapterView<ListAdapter>
//   implements TextWatcher, ViewTreeObserver.OnGlobalLayoutListener,
//   Filter.FilterListener, ViewTreeObserver.OnTouchModeChangeListener,
//   RemoteViewsAdapter.RemoteAdapterConnectionCallback
// Four of five are declared here (docs/type-identity-audit_2026-08-12.md
// ranked #3/#4 by corpus blast radius -- 37 and 35 of 62 apps -- for the
// TextWatcher/OnGlobalLayoutListener pair; Filter.FilterListener and
// OnTouchModeChangeListener were the two open rows the same audit left for
// "whoever owns list widgets"). RemoteViewsAdapter.RemoteAdapterConnection-
// Callback is the one still missing, and for a different reason than the
// other four: RemoteViewsAdapter does not exist anywhere in this tree (a
// MISSING CLASS, not a missing declaration -- framework_lint.py's
// assignability check does not even flag it here for that reason), so it
// is left for whoever lands that class. See the method-level comments
// below for exactly what real AOSP does with each of the four and why our
// bodies are honest no-ops.
public abstract class AbsListView extends AdapterView<ListAdapter>
        implements TextWatcher, ViewTreeObserver.OnGlobalLayoutListener,
        Filter.FilterListener, ViewTreeObserver.OnTouchModeChangeListener {
    public static final int CHOICE_MODE_NONE     = 0;
    public static final int CHOICE_MODE_SINGLE   = 1;
    public static final int CHOICE_MODE_MULTIPLE = 2;
    public static final int CHOICE_MODE_MULTIPLE_MODAL = 3;

    public static final int TRANSCRIPT_MODE_DISABLED         = 0;
    public static final int TRANSCRIPT_MODE_NORMAL           = 1;
    public static final int TRANSCRIPT_MODE_ALWAYS_SCROLL    = 2;

    protected ListAdapter mAdapter;

    private int mChoiceMode = CHOICE_MODE_NONE;
    private final java.util.HashMap<Integer, Boolean> mCheckStates =
            new java.util.HashMap<Integer, Boolean>();

    private OnScrollListener mOnScrollListener;
    private RecyclerListener mRecyclerListener;
    // AOSP AbsListView.mTextFilterEnabled: gates the type-to-filter feature
    // (a floating EditText that filters rows by keystroke against a
    // Filterable adapter). We don't implement that floating filter window,
    // so the flag has no render/behavior effect yet -- it is stored (rather
    // than a bare no-op) so a caller reading it back, or a future filter-
    // window implementation, observes the real value.
    private boolean mTextFilterEnabled;

    // Index of the adapter item shown at the very top of the viewport, and
    // the pixel offset (<=0) of that row's top relative to the list top. This
    // is the AOSP first-position + first-top pair that defines scroll state.
    // mFirstPosition itself now lives in AdapterView, where AOSP declares it,
    // so AdapterView.getPositionForView / getFirstVisiblePosition can compute
    // from it instead of returning constants. This class keeps writing the same
    // field; only the declaration moved.
    private int mFirstTop = 0;

    private boolean mDataChanged = true;
    private int mItemCount = 0;

    // The recycler: scrap views by view type so heterogeneous adapters reuse.
    private final RecycleBin mRecycler = new RecycleBin();

    private float mLastMotionY;
    private boolean mIsBeingDragged;
    private int mTouchSlop = 8;
    private int mMotionPosition = INVALID_POSITION;

    /** MACAPK_LIST_ITEMCLICK_AOSP, default ON (2026-09-29). AOSP AbsListView
     *  never touches a row's own click listeners: a TAP on a row that did not
     *  consume the touch is turned into performItemClick by the LIST's own
     *  onTouchEvent (android14 AbsListView.java onTouchDown / CheckForTap /
     *  onTouchUp :4188-4260 / PerformClick :3259-3282 / CheckForLongPress
     *  :3284-3320). This tree instead called row.setOnClickListener(ours) and
     *  row.setOnLongClickListener(ours) on every row AFTER adapter.getView had
     *  run, which OVERWROTE any listener the adapter had put on its own row --
     *  the common "adapter row is its own OnClickListener" idiom. Measured
     *  2026-09-29 on com.best.deskclock 2.31: its city picker adapter (fm) sets
     *  row.setOnClickListener(this) and toggles the row's checkbox in onClick;
     *  our listener replaced it (View: performClick ... listener=
     *  android.widget.AbsListView$1), the city was never selected, and
     *  number_of_cities stayed 0 ("Cannot add a new clock"). It also made every
     *  row long-clickable, which AOSP never does (only the LIST becomes
     *  long-clickable, in AdapterView.setOnItemLongClickListener). With the gate
     *  on, rows are left exactly as the adapter built them and the list runs the
     *  AOSP tap / long-press state machine itself. =0 restores the per-row
     *  listeners exactly. */
    static final boolean ITEMCLICK_AOSP =
        !"0".equals(System.getenv("MACAPK_LIST_ITEMCLICK_AOSP"));
    // AOSP mTouchMode values this path uses (AbsListView.TOUCH_MODE_*).
    private static final int TOUCH_MODE_REST = -1;
    private static final int TOUCH_MODE_DOWN = 0;
    private static final int TOUCH_MODE_TAP = 1;
    private static final int TOUCH_MODE_DONE_WAITING = 2;
    private int mItemTouchMode = TOUCH_MODE_REST;
    private float mMotionX, mMotionY;
    private Runnable mPendingCheckForTap, mPendingCheckForLongPress, mTouchModeReset;

    private final AdapterDataSetObserver mObserver = new AdapterDataSetObserver();

    public AbsListView(Context c) { super(c); }
    public AbsListView(Context c, android.util.AttributeSet a) { super(c, a); }
    /** AOSP API 21 four-argument constructor. AOSP's three-argument form is
     *  exactly this(context, attrs, defStyleAttr, 0), so delegating the other
     *  way is identical for defStyleRes == 0; a non-zero defStyleRes is
     *  DROPPED here, as in the existing FrameLayout/Button four-argument forms
     *  (TextView, ProgressBar and their subclasses honour it). Without it an
     *  app subclass calling the four-argument
     *  super() dies with NoSuchMethodError; see debug/ctorsdk_gate.sh. */
    public AbsListView(Context context, android.util.AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        this(context, attrs, defStyleAttr);
    }
    public AbsListView(Context c, android.util.AttributeSet a, int s) { super(c, a, s); }

    @Override public ListAdapter getAdapter() { return mAdapter; }

    @Override public void setAdapter(ListAdapter adapter) {
        if (mAdapter != null) {
            try { mAdapter.unregisterDataSetObserver(mObserver); } catch (Throwable ignored) {}
        }
        resetList();
        this.mAdapter = adapter;
        this.adapter = adapter; // keep AdapterView.adapter in sync
        if (adapter != null) {
            mItemCount = adapter.getCount();
            try { adapter.registerDataSetObserver(mObserver); } catch (Throwable ignored) {}
        } else {
            mItemCount = 0;
        }
        mDataChanged = true;
        requestLayout();
    }

    private void resetList() {
        removeAllViewsInLayout();
        mRecycler.clear();
        mFirstPosition = 0;
        mFirstTop = 0;
        mCheckStates.clear();
    }

    @Override public int getCount() {
        return mAdapter != null ? mAdapter.getCount() : 0;
    }

    // ---- choice mode ----
    public void setChoiceMode(int mode) { mChoiceMode = mode; if (mode == CHOICE_MODE_NONE) mCheckStates.clear(); }
    public int  getChoiceMode() { return mChoiceMode; }

    public void setItemChecked(int position, boolean value) {
        if (mChoiceMode == CHOICE_MODE_NONE) return;
        final boolean itemCheckChanged = isItemChecked(position) != value;
        if (mChoiceMode == CHOICE_MODE_SINGLE && value) mCheckStates.clear();
        mCheckStates.put(position, value);
        // AOSP AbsListView.setItemChecked: "Do not generate a data change while
        // we are in the layout phase or data has not changed" --
        // `if (!mInLayout && !mBlockLayoutRequests && itemCheckChanged)
        // requestLayout()`. The relayout is what re-binds the rows, and the
        // layout pass pushes the state into each Checkable row (layoutChildren).
        if (itemCheckChanged && !mMacapkInLayout) requestLayout();
    }

    /** AOSP AbsListView.performItemClick: in a choice mode the CHECK STATE moves
     *  here, before the item listener runs, for every caller -- a touch, a
     *  keyboard click, or an app calling performItemClick() itself. MULTIPLE
     *  toggles; SINGLE checks the row and a click on the already-checked row
     *  leaves it checked (`boolean checked = !mCheckStates.get(position, false);
     *  if (checked) { clear(); put(position, true); }`). Then
     *  updateOnScreenCheckedViews(). This lived in bindClick as a bare toggle,
     *  which UNCHECKED a single-choice row on a second tap and never reached the
     *  rows' Checkable state at all. */
    @Override
    public boolean performItemClick(View view, int position, long id) {
        if (mChoiceMode == CHOICE_MODE_MULTIPLE || mChoiceMode == CHOICE_MODE_MULTIPLE_MODAL) {
            mCheckStates.put(position, !isItemChecked(position));
            macapkUpdateOnScreenCheckedViews();
        } else if (mChoiceMode == CHOICE_MODE_SINGLE) {
            if (!isItemChecked(position)) {
                mCheckStates.clear();
                mCheckStates.put(position, true);
            }
            macapkUpdateOnScreenCheckedViews();
        }
        return super.performItemClick(view, position, id);
    }

    /** AOSP AbsListView.updateOnScreenCheckedViews, Checkable branch. (AOSP's
     *  `else if (useActivated) child.setActivated(...)` branch for non-Checkable
     *  rows is NOT ported here: it restyles every activated-selector row in
     *  every choice-mode list, and wants its own fleet measurement.) */
    private void macapkUpdateOnScreenCheckedViews() {
        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child instanceof Checkable) {
                ((Checkable) child).setChecked(isItemChecked(mFirstPosition + i));
            }
        }
    }

    /** AOSP's mInLayout, for setItemChecked's requestLayout gate above. */
    private boolean mMacapkInLayout;
    public boolean isItemChecked(int position) {
        Boolean b = mCheckStates.get(position);
        return b != null && b;
    }
    public int getCheckedItemCount() {
        int n = 0;
        for (Boolean b : mCheckStates.values()) if (b) n++;
        return n;
    }
    public int getCheckedItemPosition() {
        if (mChoiceMode == CHOICE_MODE_SINGLE) {
            for (java.util.Map.Entry<Integer, Boolean> e : mCheckStates.entrySet()) {
                if (e.getValue()) return e.getKey();
            }
        }
        return INVALID_POSITION;
    }
    public void clearChoices() { mCheckStates.clear(); }
    public android.util.SparseBooleanArray getCheckedItemPositions() {
        android.util.SparseBooleanArray a = new android.util.SparseBooleanArray();
        for (java.util.Map.Entry<Integer, Boolean> e : mCheckStates.entrySet()) {
            a.put(e.getKey(), e.getValue());
        }
        return a;
    }

    // These six setters used to DISCARD their argument. AOSP stores each in a
    // field that its matching getter reads back (AbsListView.java:1390,
    // :1470, :1560, :1709, :1782, :6620 -- android16-release), and until now
    // those getters did not exist at all, so an app that wrote a value and
    // read it back saw a NoSuchMethodError instead of its own value. We now
    // store the state. Nothing in our layout consults these fields (we paint
    // no fast scroller, no scrolling cache and no bottom-stacked fill), so
    // storing them changes no pixel -- it only makes the getters honest.
    public void setFastScrollEnabled(boolean enabled) { mFastScrollEnabled = enabled; }
    public void setFastScrollAlwaysVisible(boolean alwaysShow) {
        // AOSP: "This will enable fast scrolling if it is not already enabled."
        mFastScrollAlwaysVisible = alwaysShow;
        if (alwaysShow) mFastScrollEnabled = true;
    }
    public void setSmoothScrollbarEnabled(boolean enabled) { mSmoothScrollbarEnabled = enabled; }
    public void setScrollingCacheEnabled(boolean enabled) { mScrollingCacheEnabled = enabled; }
    public void setStackFromBottom(boolean stack) {
        if (mStackFromBottom != stack) { mStackFromBottom = stack; requestLayout(); }
    }
    public void setTranscriptMode(int mode) { mTranscriptMode = mode; }
    public void setTextFilterEnabled(boolean textFilterEnabled) { mTextFilterEnabled = textFilterEnabled; }
    public void setOnScrollListener(OnScrollListener l) { mOnScrollListener = l; }
    public void setRecyclerListener(RecyclerListener l) { mRecyclerListener = l; }

    // ---- TextWatcher / ViewTreeObserver.OnGlobalLayoutListener ----
    // AOSP wires both interfaces to the type-to-filter floating popup (see
    // mTextFilterEnabled above): the popup's EditText forwards its
    // TextWatcher callbacks here, and onGlobalLayout shows/hides that same
    // popup when the list's visibility changes. We do not implement the
    // popup, so:
    //   - beforeTextChanged/afterTextChanged are empty in AOSP itself
    //     ("For our text watcher that is associated with the text filter.
    //     Does nothing.") -- mirroring AOSP here is not an approximation,
    //     it is the literal AOSP body.
    //   - onTextChanged in AOSP creates/shows the popup and, when the
    //     adapter is Filterable, calls filter.filter(s, this) -- which
    //     requires `this` to also be a Filter.FilterListener. We now declare
    //     that interface too (see below), but the popup itself is still
    //     missing, so there is nothing honest to do here beyond a no-op --
    //     see the Filter.FilterListener block below for why onFilterComplete
    //     itself is also a no-op even though the interface is now declared.
    //   - onGlobalLayout in AOSP only ever touches mPopup, which we never
    //     create, so it is a no-op for the same reason.
    // Declaring the interfaces is still correct and useful on its own: it
    // fixes instanceof/assignability for callers that check
    // `view instanceof TextWatcher` or hand a ListView's observer a
    // OnGlobalLayoutListener-typed reference, independent of the popup
    // feature neither side of that check actually needs.
    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
    @Override public void afterTextChanged(Editable s) {}
    @Override public void onGlobalLayout() {}

    // ---- Filter.FilterListener / ViewTreeObserver.OnTouchModeChangeListener ----
    // Unlike beforeTextChanged/afterTextChanged above, neither AOSP body here
    // is empty -- checked against the real source (android11-release) before
    // writing these, per the audit's own warning not to assume. Both turn out
    // to depend entirely on machinery this simplified AbsListView does not
    // have, for two different reasons:
    //   - onFilterComplete in AOSP re-selects a valid item when filtering
    //     invalidates the current selection ("selection resurrection": it
    //     reads/writes mSelectedPosition and mResurrectToPosition and calls
    //     resurrectSelection()). None of that exists anywhere in our
    //     AdapterView/AbsListView -- grepped the tree, zero hits for all
    //     three names; we track a plain `selectedPosition` with no
    //     resurrection concept layered on top. The only in-framework caller
    //     that would invoke this on `this` is onTextChanged's
    //     `filter.filter(s, this)` above, which never runs because
    //     isTextFilterEnabled() gates on the same unimplemented popup --
    //     so today this is unreachable from our own code. It stays a real
    //     @Override (not just a declaration) rather than being dropped,
    //     because an app-owned Filterable adapter can call
    //     `getFilter().filter(text, someListView)` directly against its own
    //     search box, bypassing our popup stub entirely, and the interface
    //     contract has to resolve when it does.
    //   - onTouchModeChanged in AOSP does two independent things on entering
    //     vs leaving touch mode, and we have the machinery for neither: on
    //     entry it calls hideSelector()/updateSelectorState() to drop the
    //     selection highlight, but we never paint a selector at all
    //     (getSelector() always returns null, setSelector() is a no-op,
    //     both below); on exit it tears down TOUCH_MODE_OVERSCROLL/OVERFLING
    //     fling/overscroll state via mFlingRunnable/mPositionScroller/
    //     mScrollY/finishGlows(), and we run no fling animation loop at all
    //     (see the class-level comment at the top of this file). Separately
    //     -- and this would be true even if we had that state -- grepping
    //     the runtime shows ViewTreeObserver.dispatchOnTouchModeChanged has
    //     zero callers anywhere in this tree: Mac.apk has no keyboard/dpad-
    //     vs-touch focus-mode duality to track, so nothing ever fires this
    //     callback in practice yet either.
    // Declaring both interfaces is still correct and useful independent of
    // the above: it fixes instanceof/assignability for a Filterable adapter
    // or a ViewTreeObserver caller holding this view by listener type, the
    // same reasoning the TextWatcher/OnGlobalLayoutListener block above
    // documents.
    @Override public void onFilterComplete(int count) {}
    @Override public void onTouchModeChanged(boolean isInTouchMode) {}


    public void setSelectionFromTop(int position, int y) {
        mFirstPosition = Math.max(0, position);
        mFirstTop = y;
        mDataChanged = true;
        requestLayout();
    }
    @Override public void setSelection(int position) {
        setSelectionFromTop(position, 0);
        super.setSelection(position);
    }

    public void smoothScrollToPosition(int position) { setSelectionFromTop(position, 0); }
    public void smoothScrollToPositionFromTop(int position, int offset) {
        setSelectionFromTop(position, offset);
    }
    public void smoothScrollToPositionFromTop(int position, int offset, int duration) {
        setSelectionFromTop(position, offset);
    }
    public void smoothScrollBy(int distance, int duration) { trackMotionScroll(-distance); }

    // ---- measure / layout ----
    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        // A list fills the space it is given; if UNSPECIFIED, fall back to a
        // single-row estimate so we never collapse to 0.
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            int h = 0;
            if (mAdapter != null && mAdapter.getCount() > 0) {
                View v = obtainView(0);
                measureRow(v, widthSize);
                h = v.getMeasuredHeight();
                mRecycler.addScrap(0, v);
            }
            heightSize = h;
        }
        setMeasuredDimension(widthSize, heightSize);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        layoutChildren();
    }

    /** AOSP ListView.measureHeightOfChildren(widthMeasureSpec, 0, NO_POSITION,
     *  maxHeight, -1) (ListView.java:1392-1450, master): the list padding plus
     *  every row's measured height plus one divider between rows, returning
     *  maxHeight as soon as the running total reaches it. Rows are measured with
     *  the same spec the layout pass uses (measureRow) and parked in the
     *  recycler, as AOSP does with recycleOnMeasure(), so the next layout reuses
     *  them instead of inflating twice. Only ListView calls this: AOSP's GridView
     *  has its own rule, and ours inherits AbsListView.onMeasure unchanged. */
    protected int macapkMeasureHeightOfChildren(int widthSize, int maxHeight, int dividerHeight) {
        int returnedHeight = getPaddingTop() + getPaddingBottom();
        if (mAdapter == null) return returnedHeight;
        final int count = mAdapter.getCount();
        for (int i = 0; i < count; i++) {
            final View child = obtainView(i);
            measureRow(child, widthSize);
            if (i > 0) returnedHeight += dividerHeight;
            mRecycler.addScrap(safeType(i), child);
            returnedHeight += child.getMeasuredHeight();
            if (returnedHeight >= maxHeight) return maxHeight;
        }
        return returnedHeight;
    }

    private void measureRow(View child, int parentWidth) {
        ViewGroup.LayoutParams p = child.getLayoutParams();
        if (p == null) {
            p = new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            child.setLayoutParams(p);
        }
        int childWidthSpec = ViewGroup.getChildMeasureSpec(
                MeasureSpec.makeMeasureSpec(parentWidth, MeasureSpec.EXACTLY),
                getPaddingLeft() + getPaddingRight(),
                p.width == 0 ? ViewGroup.LayoutParams.MATCH_PARENT : p.width);
        int childHeightSpec = (p.height > 0)
                ? MeasureSpec.makeMeasureSpec(p.height, MeasureSpec.EXACTLY)
                : MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        child.measure(childWidthSpec, childHeightSpec);
    }

    // Core recycling layout: detach all children into scrap, then fill the
    // viewport from mFirstPosition/mFirstTop down, reusing scrap by type.
    protected void layoutChildren() {
        if (mAdapter == null) return;
        final int count = mAdapter.getCount();
        mItemCount = count;

        // Detach current children into the recycler for reuse this pass.
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            int pos = mFirstPosition + i;
            mRecycler.addScrap(safeType(pos), child);
            if (mRecyclerListener != null) {
                try { mRecyclerListener.onMovedToScrapHeap(child); } catch (Throwable ignored) {}
            }
        }
        removeAllViewsInLayout();

        if (count == 0) { mDataChanged = false; return; }

        if (mFirstPosition >= count) mFirstPosition = Math.max(0, count - 1);

        final int width = getWidth();
        final int top = getPaddingTop();
        final int bottomEdge = getHeight() - getPaddingBottom();

        int y = mFirstTop != 0 ? mFirstTop : top;
        int pos = mFirstPosition;

        while (pos < count && y < bottomEdge) {
            final View child;
            mMacapkInLayout = true;
            try {
                child = obtainView(pos);
                // AOSP ListView.setupChild: "if (mChoiceMode != CHOICE_MODE_NONE
                // && mCheckStates != null) { if (child instanceof Checkable)
                // ((Checkable) child).setChecked(mCheckStates.get(position));
                // ...}". Without it no choice-mode row ever showed its state.
                if (mChoiceMode != CHOICE_MODE_NONE && child instanceof Checkable) {
                    ((Checkable) child).setChecked(isItemChecked(pos));
                }
            } finally {
                mMacapkInLayout = false;
            }
            measureRow(child, width);
            int h = child.getMeasuredHeight();
            addViewInLayout(child, getChildCount(), child.getLayoutParams());
            child.layout(getPaddingLeft(), y, width - getPaddingRight(), y + h);
            bindClick(child, pos);
            // AOSP's ListView fill loop advances by the child's height PLUS the
            // divider height (ListView.fillDown: `nextTop = child.getBottom()
            // + mDividerHeight`). Painting a divider without reserving its space
            // makes it overlap the next row, so the two halves must land
            // together -- see ListView.dispatchDraw for the paint half.
            y += h + macapkDividerHeight();
            pos++;
        }
        // AOSP ListView.fillSpecific -> correctTooHigh (ListView.java): when a
        // selection (setSelection / setSelectionFromTop) placed the first row
        // so low that the LAST item ends above the list's bottom, slide the rows
        // back down and fill the space above with the items before it. Without
        // it a list whose content fits showed only its tail: AlertDialog's
        // setSingleChoiceItems calls setSelection(checkedItem), so NoNonsense
        // Notes' "Default sorting order" dialog (4 entries, the 4th checked)
        // showed just "Manual" over an empty box. Whole rows only (AOSP can
        // leave a partial row at the top); MACAPK_LIST_CORRECT_TOO_HIGH=0
        // restores the old fill.
        if (MACAPK_LIST_CORRECT_TOO_HIGH && !mMacapkCorrectingTooHigh
                && pos >= count && y < bottomEdge && mFirstPosition > 0) {
            int space = bottomEdge - y;
            int newFirst = mFirstPosition;
            while (newFirst > 0) {
                final View v;
                mMacapkInLayout = true;
                try { v = obtainView(newFirst - 1); } finally { mMacapkInLayout = false; }
                measureRow(v, width);
                final int rh = v.getMeasuredHeight() + macapkDividerHeight();
                mRecycler.addScrap(safeType(newFirst - 1), v);
                if (rh > space) break;
                space -= rh;
                newFirst--;
            }
            if (newFirst != mFirstPosition) {
                mFirstPosition = newFirst;
                mFirstTop = 0;
                mMacapkCorrectingTooHigh = true;
                try { layoutChildren(); } finally { mMacapkCorrectingTooHigh = false; }
                return;
            }
        }
        mDataChanged = false;
        fireScroll();
    }
    static final boolean MACAPK_LIST_CORRECT_TOO_HIGH =
        !"0".equals(System.getenv("MACAPK_LIST_CORRECT_TOO_HIGH"));
    private boolean mMacapkCorrectingTooHigh;

    private int safeType(int pos) {
        if (mAdapter == null || pos < 0 || pos >= mAdapter.getCount()) return 0;
        int t = mAdapter.getItemViewType(pos);
        return t < 0 ? 0 : t;
    }

    // Obtain a row view for an adapter position, reusing a scrap view of the
    // matching type as the convertView (AOSP RecycleBin contract).
    protected View obtainView(int position) {
        int type = safeType(position);
        View scrap = mRecycler.getScrap(type);
        View child = mAdapter.getView(position, scrap, this);
        if (child == null) child = new View(getContext());
        if (child.getLayoutParams() == null) {
            child.setLayoutParams(new AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return child;
    }

    private void bindClick(final View row, final int position) {
        if (ITEMCLICK_AOSP) return;  // the list's own onTouchEvent clicks rows (see ITEMCLICK_AOSP)
        final long id = mAdapter.getItemId(position);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // The choice-mode check state moves inside performItemClick,
                // where AOSP keeps it (see the override above).
                performItemClick(row, position, id);
            }
        });
        // AOSP AbsListView.performLongPress routes a row's long press into
        // AdapterView's OnItemLongClickListener. That listener was STORED
        // (AdapterView.java:21,105-106) and invoked from NOWHERE in the tree, so
        // long-press on a list row -- the single most common long-press idiom in
        // Android -- was dead in every app. Now that View.onTouchEvent actually
        // schedules a long press (see View.checkForLongClick), the row only needs
        // to be long-clickable and to have a listener.
        row.setLongClickable(true);
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                return performItemLongClick(row, position, id);
            }
        });
    }

    /** AOSP AbsListView.performLongPress (frameworks/base core/java/android/
     *  widget/AbsListView.java): fire OnItemLongClickListener and report whether
     *  it consumed the press. AOSP also shows a context menu when the listener
     *  declines; this tree has no ContextMenu presentation path, so declining is
     *  simply "not handled", which lets the following UP fire the normal click
     *  exactly as a device does when nothing handles the long press. */
    public boolean performItemLongClick(View child, int position, long id) {
        if (onItemLongClickListener != null) {
            try {
                return onItemLongClickListener.onItemLongClick(this, child, position, id);
            } catch (Throwable ignored) { }
        }
        return false;
    }

    // ---- touch / drag scroll ----
    @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mLastMotionY = ev.getY();
                mIsBeingDragged = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(ev.getY() - mLastMotionY) > mTouchSlop
                        && getScrollableRange() > 0) {
                    mIsBeingDragged = true;
                    mLastMotionY = ev.getY();
                    return true;
                }
                return false;
            default:
                mIsBeingDragged = false;
                return false;
        }
    }

    /** AOSP AbsListView.onGenericMotionEvent (android14-release
     *  AbsListView.java:4488-4535): a wheel step moves the list by AXIS_VSCROLL x
     *  the vertical scroll factor through trackMotionScroll. AOSP's
     *  trackMotionScroll returns true when the list was already at that edge;
     *  then a MOUSE step is not consumed (it falls to super and on to an
     *  enclosing scroller), a non-mouse one pulls the edge glow. Ours returns
     *  nothing, so "at the edge" is read as "the anchor did not move".
     *  WheelScroll.VIEWS=0: not handled. */
    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (com.macapk.loader.WheelScroll.VIEWS
                && event.getAction() == MotionEvent.ACTION_SCROLL) {
            final float axisValue;
            if (event.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER)) {
                axisValue = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            } else if (event.isFromSource(android.view.InputDevice.SOURCE_ROTARY_ENCODER)) {
                axisValue = event.getAxisValue(MotionEvent.AXIS_SCROLL);
            } else {
                axisValue = 0;
            }
            final float factor = android.view.ViewConfiguration.get(getContext())
                    .getScaledVerticalScrollFactor();
            final int delta = Math.round(axisValue * factor);
            if (delta != 0) {
                final int prevFirst = mFirstPosition, prevTop = mFirstTop;
                trackMotionScroll(delta);
                final boolean atEdge = mFirstPosition == prevFirst && mFirstTop == prevTop;
                if (!atEdge) {
                    fireScroll();
                    return true;
                }
                final int overscrollMode = getOverScrollMode();
                if (!event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)
                        && getChildCount() > 0
                        && overscrollMode != OVER_SCROLL_NEVER) {
                    float overscroll = delta / ((float) getHeight());
                    if (delta > 0) {
                        mEdgeGlowTop.onPullDistance(overscroll, 0.5f);
                        mEdgeGlowTop.onRelease();
                    } else {
                        mEdgeGlowBottom.onPullDistance(-overscroll, 0.5f);
                        mEdgeGlowBottom.onRelease();
                    }
                    invalidate();
                    return true;
                }
            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mLastMotionY = ev.getY();
                mIsBeingDragged = false;
                if (ITEMCLICK_AOSP) onItemTouchDown(ev);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float y = ev.getY();
                int dy = (int) (y - mLastMotionY);
                if (!mIsBeingDragged && Math.abs(dy) > mTouchSlop) {
                    mIsBeingDragged = true;
                    // AOSP startScrollIfNeeded: a scroll cancels the pending
                    // tap / long press and un-presses the row.
                    if (ITEMCLICK_AOSP) cancelItemTouch();
                }
                if (mIsBeingDragged) {
                    mLastMotionY = y;
                    trackMotionScroll(dy);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (ITEMCLICK_AOSP && !mIsBeingDragged) onItemTouchUp(ev);
                else if (ITEMCLICK_AOSP) cancelItemTouch();
                mIsBeingDragged = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (ITEMCLICK_AOSP) cancelItemTouch();
                mIsBeingDragged = false;
                return true;
        }
        return super.onTouchEvent(ev);
    }

    // ---- AOSP item tap / long press (ITEMCLICK_AOSP) --------------------------
    // android14 AbsListView: onTouchDown remembers pointToPosition and posts
    // CheckForTap after getTapTimeout(); CheckForTap presses the row and, when
    // the list isLongClickable(), posts CheckForLongPress; onTouchUp posts the
    // click (PerformClick -> performItemClick) after getPressedStateDuration().
    // Only a touch the ROW did not consume reaches here, so a row with its own
    // OnClickListener keeps it, exactly as on a device.

    private void onItemTouchDown(MotionEvent ev) {
        cancelItemTouch();
        mMotionX = ev.getX();
        mMotionY = ev.getY();
        final int motionPosition = pointToPosition((int) mMotionX, (int) mMotionY);
        mMotionPosition = motionPosition;
        if (!mDataChanged && mAdapter != null && motionPosition >= 0
                && motionPosition < mAdapter.getCount() && mAdapter.isEnabled(motionPosition)) {
            mItemTouchMode = TOUCH_MODE_DOWN;
            if (mPendingCheckForTap == null) mPendingCheckForTap = new Runnable() {
                @Override public void run() { checkForTap(); }
            };
            postDelayed(mPendingCheckForTap, android.view.ViewConfiguration.getTapTimeout());
        } else {
            mItemTouchMode = TOUCH_MODE_REST;
        }
    }

    /** AOSP CheckForTap.run. */
    private void checkForTap() {
        if (mItemTouchMode != TOUCH_MODE_DOWN) return;
        mItemTouchMode = TOUCH_MODE_TAP;
        final View child = getChildAt(mMotionPosition - mFirstPosition);
        if (child != null && !child.hasExplicitFocusable()) {
            if (!mDataChanged) {
                child.setPressed(true);
                setPressed(true);
                if (isLongClickable()) {
                    if (mPendingCheckForLongPress == null) mPendingCheckForLongPress = new Runnable() {
                        @Override public void run() { checkForLongPress(); }
                    };
                    postDelayed(mPendingCheckForLongPress,
                            android.view.ViewConfiguration.getLongPressTimeout());
                } else {
                    mItemTouchMode = TOUCH_MODE_DONE_WAITING;
                }
            } else {
                mItemTouchMode = TOUCH_MODE_DONE_WAITING;
            }
        }
    }

    /** AOSP CheckForLongPress.run. */
    private void checkForLongPress() {
        final int motionPosition = mMotionPosition;
        final View child = getChildAt(motionPosition - mFirstPosition);
        if (child == null || mAdapter == null) return;
        boolean handled = false;
        if (!mDataChanged && isAttachedToWindowOrDetachedRoot()) {
            handled = performItemLongClick(child, motionPosition, mAdapter.getItemId(motionPosition));
        }
        if (handled) {
            mItemTouchMode = TOUCH_MODE_REST;
            setPressed(false);
            child.setPressed(false);
        } else {
            mItemTouchMode = TOUCH_MODE_DONE_WAITING;
        }
    }

    /** AOSP onTouchUp for TOUCH_MODE_DOWN / TAP / DONE_WAITING. */
    private void onItemTouchUp(MotionEvent ev) {
        final int mode = mItemTouchMode;
        if (mode != TOUCH_MODE_DOWN && mode != TOUCH_MODE_TAP && mode != TOUCH_MODE_DONE_WAITING) {
            mItemTouchMode = TOUCH_MODE_REST;
            return;
        }
        final int motionPosition = mMotionPosition;
        final View child = getChildAt(motionPosition - mFirstPosition);
        if (child != null) {
            if (mode != TOUCH_MODE_DOWN) child.setPressed(false);
            final float x = ev.getX();
            final boolean inList = x > getListPaddingLeft() && x < getWidth() - getListPaddingRight();
            if (inList && !child.hasExplicitFocusable()) {
                final Runnable performClick = new Runnable() {
                    @Override public void run() { performClickAt(motionPosition); }
                };
                if (mode == TOUCH_MODE_DOWN || mode == TOUCH_MODE_TAP) {
                    removeCallbacks(mode == TOUCH_MODE_DOWN ? mPendingCheckForTap : mPendingCheckForLongPress);
                    if (!mDataChanged && mAdapter != null && motionPosition < mAdapter.getCount()
                            && mAdapter.isEnabled(motionPosition)) {
                        mItemTouchMode = TOUCH_MODE_TAP;
                        child.setPressed(true);
                        setPressed(true);
                        if (mTouchModeReset != null) removeCallbacks(mTouchModeReset);
                        mTouchModeReset = new Runnable() {
                            @Override public void run() {
                                mTouchModeReset = null;
                                mItemTouchMode = TOUCH_MODE_REST;
                                child.setPressed(false);
                                setPressed(false);
                                // android14 AbsListView.java:4242-4243: `!mDataChanged && !mIsDetaching
                                // && isAttachedToWindow()`
                                if (!mDataChanged && isAttachedToWindowOrDetachedRoot()) performClick.run();
                            }
                        };
                        postDelayed(mTouchModeReset, android.view.ViewConfiguration.getPressedStateDuration());
                    } else {
                        mItemTouchMode = TOUCH_MODE_REST;
                    }
                    return;
                } else if (!mDataChanged && mAdapter != null && motionPosition < mAdapter.getCount()
                        && mAdapter.isEnabled(motionPosition)) {
                    performClick.run();
                }
            }
        }
        mItemTouchMode = TOUCH_MODE_REST;
        removeCallbacks(mPendingCheckForLongPress);
        setPressed(false);
    }

    /** AOSP PerformClick.run. */
    private void performClickAt(int motionPosition) {
        if (mDataChanged) return;
        final ListAdapter adapter = mAdapter;
        if (adapter != null && mItemCount > 0 && motionPosition != INVALID_POSITION
                && motionPosition < adapter.getCount() && adapter.isEnabled(motionPosition)) {
            final View view = getChildAt(motionPosition - mFirstPosition);
            if (view != null) performItemClick(view, motionPosition, adapter.getItemId(motionPosition));
        }
    }

    /** A scroll / cancel ends the tap: AOSP startScrollIfNeeded + ACTION_CANCEL. */
    private void cancelItemTouch() {
        if (mPendingCheckForTap != null) removeCallbacks(mPendingCheckForTap);
        if (mPendingCheckForLongPress != null) removeCallbacks(mPendingCheckForLongPress);
        if (mItemTouchMode != TOUCH_MODE_REST) {
            final View child = getChildAt(mMotionPosition - mFirstPosition);
            if (child != null) child.setPressed(false);
            setPressed(false);
        }
        mItemTouchMode = TOUCH_MODE_REST;
    }

    /** AOSP guards the long press with sameWindow(); a list in a window that
     *  was torn down mid-press must not fire. A list with no parent at all (a
     *  headless harness) has no window to have left. */
    private boolean isAttachedToWindowOrDetachedRoot() {
        return isAttachedToWindow() || getParent() == null;
    }

    // Rough estimate of total scrollable overflow (content height beyond the
    // viewport). We do not measure every row up front (could be thousands), so
    // we treat "more items below than fit" as scrollable.
    /** The vertical gap this list leaves BETWEEN rows. Zero here; ListView
     *  overrides it with its `android:divider`/`android:dividerHeight`. AOSP
     *  keeps the divider entirely inside ListView because ListView owns its own
     *  fill loop; this tree's fill loop lives in AbsListView, so the one number
     *  the loop needs is exposed as a hook rather than duplicating the loop. */
    protected int macapkDividerHeight() { return 0; }

    private int getScrollableRange() {
        if (mAdapter == null) return 0;
        int count = mAdapter.getCount();
        if (count == 0) return 0;
        boolean moreBelow = getLastVisiblePosition() < count - 1;
        boolean scrolledDown = mFirstPosition > 0 || mFirstTop < 0;
        return (moreBelow || scrolledDown) ? 1 : 0;
    }

    // Shift content by dy (positive = finger moved down = content moves down =
    // reveal earlier rows). Re-fills via layoutChildren after adjusting the
    // first-position anchor. Clamped at both ends.
    void trackMotionScroll(int dy) {
        if (mAdapter == null || mAdapter.getCount() == 0) return;
        int newTop = mFirstTop + dy;
        // Walk the anchor when the offset crosses a row boundary.
        if (getChildCount() > 0) {
            // Row PITCH, not row height: with dividers reserved between rows the
            // anchor must step by height + divider or the list drifts one divider
            // per crossed boundary.
            int firstH = getChildAt(0).getHeight() + macapkDividerHeight();
            while (newTop > getPaddingTop() && mFirstPosition > 0) {
                mFirstPosition--;
                newTop -= (firstH > 0 ? firstH : 1);
            }
            int last = mAdapter.getCount() - 1;
            while (newTop < getPaddingTop() - firstH
                    && getLastVisiblePosition() < last) {
                mFirstPosition++;
                newTop += (firstH > 0 ? firstH : 1);
            }
        }
        // Clamp the top edge so we cannot scroll above the first item.
        if (mFirstPosition == 0 && newTop > getPaddingTop()) {
            newTop = getPaddingTop();
        }
        if (newTop == mFirstTop) {
            // even if anchor unchanged, relayout to reflect bottom clamp
        }
        mFirstTop = newTop;
        layoutChildren();
        invalidate();
    }

    private void fireScroll() {
        if (mOnScrollListener != null) {
            try {
                mOnScrollListener.onScroll(this, mFirstPosition,
                        getChildCount(), mItemCount);
            } catch (Throwable ignored) {}
        }
    }

    private class AdapterDataSetObserver extends DataSetObserver {
        @Override public void onChanged() {
            mDataChanged = true;
            mItemCount = mAdapter != null ? mAdapter.getCount() : 0;
            requestLayout();
            invalidate();
        }
        @Override public void onInvalidated() {
            mDataChanged = true;
            requestLayout();
            invalidate();
        }
    }

    // Scrap heap keyed by item-view-type, so heterogeneous adapters reuse the
    // right kind of convertView. Mirrors AbsListView.RecycleBin's per-type
    // scrap arrays.
    private class RecycleBin {
        private final java.util.HashMap<Integer, ArrayList<View>> mScrap =
                new java.util.HashMap<Integer, ArrayList<View>>();

        void addScrap(int type, View v) {
            if (v == null) return;
            ArrayList<View> list = mScrap.get(type);
            if (list == null) { list = new ArrayList<View>(); mScrap.put(type, list); }
            list.add(v);
        }
        View getScrap(int type) {
            ArrayList<View> list = mScrap.get(type);
            if (list != null && !list.isEmpty()) {
                return list.remove(list.size() - 1);
            }
            return null;
        }
        void clear() { mScrap.clear(); }

        /** AOSP RecycleBin.reclaimScrapViews -- move every scrapped row into
         *  the caller's list and empty the heap. Used by
         *  AbsListView.reclaimViews(List). */
        void reclaimScrapViews(java.util.List<View> views) {
            for (ArrayList<View> list : mScrap.values()) views.addAll(list);
            mScrap.clear();
        }
    }

    public interface OnScrollListener {
        int SCROLL_STATE_IDLE         = 0;
        int SCROLL_STATE_TOUCH_SCROLL = 1;
        int SCROLL_STATE_FLING        = 2;
        void onScrollStateChanged(AbsListView view, int scrollState);
        void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount);
    }
    public interface RecyclerListener { void onMovedToScrapHeap(View view); }

    /**
     * The top-level view of a list item can implement this interface to allow
     * itself to modify the bounds of the selection shown for that item. AOSP
     * (frameworks/base/core/java/android/widget/AbsListView.java) calls it from
     * positionSelector(), right after the default selector rect is computed:
     *   if (sel instanceof SelectionBoundsAdjuster) {
     *       ((SelectionBoundsAdjuster) sel).adjustListItemSelectionBounds(selectorRect);
     *   }
     *
     * This has to exist as a TYPE even though we do not paint a selector
     * highlight yet: an app's own row class declares `implements
     * AbsListView.SelectionBoundsAdjuster`, and interface resolution happens at
     * class-load/link time, so a missing interface is a NoClassDefFoundError
     * when the row class loads, not a lazy NoSuchMethodError when a selector is
     * finally drawn.
     */
    public interface SelectionBoundsAdjuster {
        /**
         * Called to allow the list item to adjust the bounds shown for
         * its selection.
         *
         * @param bounds On call, this contains the bounds the list has
         * selected for the item (that is the bounds of the entire view). The
         * values can be modified as desired.
         */
        public void adjustListItemSelectionBounds(Rect bounds);
    }
    public interface MultiChoiceModeListener
            extends android.view.ActionMode.Callback {
        void onItemCheckedStateChanged(android.view.ActionMode mode,
                int position, long id, boolean checked);
    }
    public void setMultiChoiceModeListener(MultiChoiceModeListener listener) {}

    /** v1.0.1643: AOSP checkLayoutParams, so addView converts a foreign
     *  LayoutParams into this container's own kind (ViewGroup.addView ->
     *  generateLayoutParams). Gate MACAPK_CHECK_LAYOUT_PARAMS=0 restores the old
     *  accept-anything answer for an A/B. */
    @Override
    protected boolean checkLayoutParams(android.view.ViewGroup.LayoutParams p) {
        if (!MACAPK_CHECK_LP) return true;
        return p instanceof AbsListView.LayoutParams;
    }
    private static final boolean MACAPK_CHECK_LP =
        !"0".equals(System.getenv("MACAPK_CHECK_LAYOUT_PARAMS"));
    /** AOSP AbsListView.generateLayoutParams(ViewGroup.LayoutParams). */
    @Override
    protected android.view.ViewGroup.LayoutParams generateLayoutParams(android.view.ViewGroup.LayoutParams p) {
        return new LayoutParams(p);
    }

    public static class LayoutParams extends android.view.ViewGroup.LayoutParams {
        public int viewType;
        public long itemId = -1;
        public LayoutParams(int w, int h) { super(w, h); }
        public LayoutParams(int w, int h, int viewType) { super(w, h); this.viewType = viewType; }
        public LayoutParams(android.view.ViewGroup.LayoutParams src) { super(src); }
        public LayoutParams(Context c, android.util.AttributeSet attrs) { super(c, attrs); }
    }

    private boolean mSelectedChildViewEnabled;
    public void setSelectedChildViewEnabled(boolean v) { mSelectedChildViewEnabled = v; }
    public boolean isSelectedChildViewEnabled() { return mSelectedChildViewEnabled; }
    public void setCacheColorHint(int color) { mCacheColorHint = color; }

    public void scrollListBy(int y) { }

    private android.graphics.Rect mTouchFrame;
    /** GridView.layoutGrid overrides onLayout, so AbsListView.layoutChildren (the
     *  only other place that clears the private mDataChanged, initialised true
     *  and set again by setAdapter) never runs for a grid. AOSP GridView has its
     *  own layoutChildren, which does `boolean dataChanged = mDataChanged; if
     *  (dataChanged) { handleDataChanged(); }` at :1234-1237 and
     *  `mLayoutMode = LAYOUT_NORMAL; mDataChanged = false;` at :1407 (android14
     *  GridView.java). Left set, the tap state machine (onItemTouchDown /
     *  performClickAt) sees a list "in flux" forever and never clicks a cell.
     *  Called from every exit of GridView.layoutGrid. */
    void macapkGridLayoutDone() {
        if (!ITEMCLICK_AOSP) return;  // =0: the pre-change grid never touched the flag
        if (mDataChanged) handleDataChanged();
        mDataChanged = false;
    }

    /** AOSP AbsListView.pointToPosition (frameworks/base core/java/android/widget/
     *  AbsListView.java): hit-test the laid-out children top-most first and
     *  return `mFirstPosition + i`, or INVALID_POSITION.
     *
     *  ⚠ This was a hardcoded `return -1` while mFirstPosition and the
     *  per-child `mFirstPosition + i` assignment sat in the same class. It is
     *  what context-menu and drag-reorder code uses to name the row under the
     *  finger, so all of it silently no-oped. */
    public int pointToPosition(int x, int y) {
        android.graphics.Rect frame = mTouchFrame;
        if (frame == null) { mTouchFrame = new android.graphics.Rect(); frame = mTouchFrame; }
        final int count = getChildCount();
        for (int i = count - 1; i >= 0; i--) {
            final View child = getChildAt(i);
            if (child != null && child.getVisibility() == View.VISIBLE) {
                child.getHitRect(frame);
                if (frame.contains(x, y)) return mFirstPosition + i;
            }
        }
        return INVALID_POSITION;
    }

    /** AOSP AbsListView.pointToRowId: the stable row id at a point, or
     *  INVALID_ROW_ID. Depends on pointToPosition, so it was equally dead. */
    public long pointToRowId(int x, int y) {
        int position = pointToPosition(x, y);
        if (position >= 0 && mAdapter != null) return mAdapter.getItemId(position);
        return INVALID_ROW_ID;
    }
    public android.graphics.drawable.Drawable getSelector() { return null; }
    // AOSP AbsListView.getListPaddingLeft/Right/Top/Bottom return mListPadding,
    // which AOSP computes as the max of the View's own padding and the
    // selector drawable's padding. We never paint a selector (getSelector()
    // is always null, setSelector() is a no-op below), so that max always
    // reduces to the plain View padding -- the same reduction
    // getListPaddingTop/Bottom already use just below.
    public int getListPaddingTop() { return getPaddingTop(); }
    public int getListPaddingBottom() { return getPaddingBottom(); }
    public int getListPaddingLeft() { return getPaddingLeft(); }
    public int getListPaddingRight() { return getPaddingRight(); }

    public void setSelector(int resID) { }
    public boolean canScrollList(int direction) { return false; }

    public void setSelector(android.graphics.drawable.Drawable sel) { }

    public void setDrawSelectorOnTop(boolean onTop) { mDrawSelectorOnTop = onTop; }
    public void setLayoutAnimation(android.view.animation.LayoutAnimationController controller) { }
    public void setLayoutAnimationListener(android.view.animation.Animation.AnimationListener listener) { }

    // ------------------------------------------------------------------
    // API-36 surface completion (apidiff: android.widget.AbsListView had 34
    // public members missing). Every body below mirrors
    // frameworks/base/core/java/android/widget/AbsListView.java
    // (android16-release); the line numbers cited are from that file.
    // ------------------------------------------------------------------

    // Backing state for the getters AOSP pairs with the setters above.
    private int     mCacheColorHint;
    private boolean mFastScrollEnabled;
    private boolean mFastScrollAlwaysVisible;
    private int     mFastScrollStyle;
    // AOSP initialises both of these to true (AbsListView.java field decls).
    private boolean mScrollingCacheEnabled = true;
    private boolean mSmoothScrollbarEnabled = true;
    private boolean mStackFromBottom;
    private int     mTranscriptMode = TRANSCRIPT_MODE_DISABLED;
    private boolean mDrawSelectorOnTop;
    private boolean mFiltered;
    private CharSequence mTextFilterText;
    private boolean mDeferNotifyDataSetChanged;
    private float   mVelocityScale = 1.0f;
    private float   mFriction = Float.NaN;
    private View    mScrollUp, mScrollDown;
    private int     mLastHandledItemCount;
    // AOSP keeps a real EdgeEffect per edge and setTopEdgeEffectColor calls
    // mEdgeGlowTop.setColor(). Our EdgeEffect paints nothing (see
    // EdgeEffect.java's header) but it does store a colour, so keeping two
    // real instances here is both cheaper than a bespoke field and keeps the
    // get/set round trip identical to AOSP's.
    private final EdgeEffect mEdgeGlowTop = new EdgeEffect();
    private final EdgeEffect mEdgeGlowBottom = new EdgeEffect();

    /** AOSP :1514 -- we have no FastScroller object, so this is the
     *  `mFastScroll == null` branch verbatim. */
    public boolean isFastScrollEnabled() { return mFastScrollEnabled; }
    /** AOSP :1490, `mFastScroll == null` branch. */
    public boolean isFastScrollAlwaysVisible() {
        return mFastScrollEnabled && mFastScrollAlwaysVisible;
    }
    /** AOSP :1428 -- with no FastScroller attached AOSP just remembers the
     *  style resource for the scroller it would build later. */
    public void setFastScrollStyle(int styleResId) { mFastScrollStyle = styleResId; }

    /** AOSP :1692 / :1579 / :1771 / :1740 / :6630 / :6669. */
    public boolean isScrollingCacheEnabled()  { return mScrollingCacheEnabled; }
    public boolean isSmoothScrollbarEnabled() { return mSmoothScrollbarEnabled; }
    public boolean isStackFromBottom()        { return mStackFromBottom; }
    public boolean isTextFilterEnabled()      { return mTextFilterEnabled; }
    public int     getTranscriptMode()        { return mTranscriptMode; }
    public int     getCacheColorHint()        { return mCacheColorHint; }
    /** AOSP :2921. */
    public boolean isDrawSelectorOnTop()      { return mDrawSelectorOnTop; }

    // ---- text filter -------------------------------------------------
    // AOSP drives these through a floating filter popup (mPopup/mTextFilter,
    // an EditText in a PopupWindow). We do not build that popup -- see the
    // TextWatcher block above for why -- but the FILTER ITSELF is real: AOSP
    // setFilterText calls Filter.filter() on a Filterable adapter, and that
    // half works here, so we do it. What we skip is only showing the popup.
    /** AOSP :2048. */
    public void setFilterText(String filterText) {
        if (mTextFilterEnabled && filterText != null && filterText.length() > 0) {
            mTextFilterText = filterText;
            if (mAdapter instanceof Filterable) {
                Filter f = ((Filterable) mAdapter).getFilter();
                if (f != null) f.filter(filterText);
                mFiltered = true;
            }
        }
    }
    /** AOSP :2074 -- null unless filtering is both enabled and active. */
    public CharSequence getTextFilter() {
        return (mTextFilterEnabled && mTextFilterText != null) ? mTextFilterText : null;
    }
    /** AOSP :6494. */
    public void clearTextFilter() {
        if (mFiltered) {
            mTextFilterText = "";
            mFiltered = false;
        }
    }
    /** AOSP :6507. */
    public boolean hasTextFilter() { return mFiltered; }
    /** AOSP :6168 -- protected, and ListView consults it while laying out. */
    protected boolean isInFilterMode() { return mFiltered; }

    // ---- checked ids -------------------------------------------------
    /** AOSP :1112. AOSP keeps a LongSparseArray of checked ids that is only
     *  populated when the adapter hasStableIds(); with no such adapter the
     *  array is null and AOSP returns an empty array. We rebuild the same
     *  answer from the checked positions, which is what AOSP's array holds. */
    public long[] getCheckedItemIds() {
        if (mChoiceMode == CHOICE_MODE_NONE || mAdapter == null
                || !mAdapter.hasStableIds()) {
            return new long[0];
        }
        final java.util.ArrayList<Long> ids = new java.util.ArrayList<Long>();
        for (java.util.Map.Entry<Integer, Boolean> e : mCheckStates.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) {
                ids.add(Long.valueOf(mAdapter.getItemId(e.getKey().intValue())));
            }
        }
        final long[] out = new long[ids.size()];
        for (int i = 0; i < out.length; i++) out[i] = ids.get(i).longValue();
        return out;
    }

    // ---- data-set plumbing -------------------------------------------
    /** AOSP :5735 -- force a full re-layout of every visible row. */
    public void invalidateViews() {
        mDataChanged = true;
        requestLayout();
        invalidate();
    }
    /** AOSP :5916. AOSP's body is ~90 lines of selection/sync bookkeeping for
     *  state we do not keep (mSyncPosition, mNeedSync, transient-state views).
     *  The part that IS ours: record the count we have now handled, drop the
     *  scrap heap so stale rows are not reused against a changed adapter, and
     *  clear the changed flag. */
    protected void handleDataChanged() {
        mLastHandledItemCount = mItemCount;
        mRecycler.clear();
        mDataChanged = false;
    }

    // ---- scrolling ---------------------------------------------------
    /** AOSP :4598. AOSP hands the velocity to a FlingRunnable that posts a
     *  frame at a time; this port runs no animation loop (see the file
     *  header), so we settle straight onto the position that fling would
     *  have coasted to. The distance comes from the same OverScroller spline
     *  AOSP's FlingRunnable uses, so the DESTINATION matches -- only the
     *  intervening frames are missing.
     *  Sign: AOSP's FlingRunnable feeds trackMotionScroll(mLastFlingY - currY),
     *  i.e. the negation of the distance travelled, and our trackMotionScroll
     *  uses the same convention (positive = content moves down). */
    public void fling(int velocityY) {
        if (mAdapter == null || mAdapter.getCount() == 0) return;
        final OverScroller scroller = new OverScroller(getContext());
        scroller.fling(0, 0, 0, velocityY,
                0, 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
        final int travelled = scroller.getFinalY();
        if (travelled != 0) trackMotionScroll(-travelled);
    }
    /** AOSP :5233 -- forwarded to the fling scroller's friction. We build the
     *  scroller per fling (above), so remember it and apply it there. */
    public void setFriction(float friction) { mFriction = friction; }
    /** AOSP :5246. */
    public void setVelocityScale(float scale) { mVelocityScale = scale; }
    /** AOSP :5316 -- boundPosition caps how far the scroll may travel. We
     *  land on the target immediately, so honour the bound by clamping. */
    public void smoothScrollToPosition(int position, int boundPosition) {
        if (boundPosition >= 0) {
            if (position > boundPosition) position = boundPosition;
        }
        smoothScrollToPosition(position);
    }
    /** AOSP :3005 -- the two Views a list may light up to say "more above /
     *  more below". AOSP only ever sets their visibility from
     *  updateScrollIndicators(), which we do not run, so this stores them. */
    public void setScrollIndicators(View up, View down) {
        mScrollUp = up;
        mScrollDown = down;
    }

    // ---- edge effect colours -----------------------------------------
    /** AOSP :6819 / :6833 / (top at :6849) / getters just below each. */
    public void setEdgeEffectColor(int color) {
        setTopEdgeEffectColor(color);
        setBottomEdgeEffectColor(color);
    }
    public void setTopEdgeEffectColor(int color)    { mEdgeGlowTop.setColor(color); }
    public void setBottomEdgeEffectColor(int color) { mEdgeGlowBottom.setColor(color); }
    public int  getTopEdgeEffectColor()    { return mEdgeGlowTop.getColor(); }
    public int  getBottomEdgeEffectColor() { return mEdgeGlowBottom.getColor(); }

    // ---- RemoteViewsAdapter connection -------------------------------
    // AOSP implements RemoteViewsAdapter.RemoteAdapterConnectionCallback here.
    // We have no RemoteViewsAdapter (it binds to a RemoteViewsService across
    // a Binder we do not have), so setRemoteViewsAdapter cannot produce one.
    // The three callbacks still have to exist and behave: a widget host calls
    // deferNotifyDataSetChanged() then expects onRemoteAdapterConnected() to
    // flush it.
    /** AOSP :6765. */
    public void deferNotifyDataSetChanged() { mDeferNotifyDataSetChanged = true; }
    /** AOSP :6773 -- returns false when the adapter was newly swapped in.
     *  With no remote adapter to swap, AOSP's `mRemoteAdapter == null` path
     *  falls through to `return false`. */
    public boolean onRemoteAdapterConnected() {
        if (mDeferNotifyDataSetChanged) {
            mDeferNotifyDataSetChanged = false;
            if (mAdapter instanceof BaseAdapter) ((BaseAdapter) mAdapter).notifyDataSetChanged();
        }
        return false;
    }
    /** AOSP :6792 -- AOSP's body is a comment and nothing else: the adapter is
     *  deliberately kept so the cached items stay displayed. */
    public void onRemoteAdapterDisconnected() { }
    /** AOSP :6715. Binding needs a RemoteViewsService over Binder, which this
     *  runtime has no equivalent for, so no adapter is produced. AOSP's own
     *  contract is that the list keeps its current adapter until the service
     *  connects, which is exactly the state we leave it in. */
    public void setRemoteViewsAdapter(android.content.Intent intent) { }

    // ---- accessibility -----------------------------------------------
    /** AOSP :2616 -- the per-row hook the list's AccessibilityDelegate calls
     *  so subclasses can decorate an item's node. AOSP's body only sets
     *  enabled/selected flags on the node from the row's own state; ours does
     *  the same, and does nothing for INVALID_POSITION as AOSP does. */
    public void onInitializeAccessibilityNodeInfoForItem(View view, int position,
            android.view.accessibility.AccessibilityNodeInfo info) {
        if (position == INVALID_POSITION || info == null) return;
        if (view != null) {
            info.setEnabled(view.isEnabled());
        }
        info.setSelected(position == getSelectedItemPosition());
    }

    // ---- view reclamation --------------------------------------------
    /** AOSP :6680 -- hand every row (on screen and in the scrap heap) to the
     *  caller and detach them all. */
    public void reclaimViews(java.util.List<View> views) {
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            views.add(child);
            if (mRecyclerListener != null) {
                // AOSP: "Pretend they went through the scrap heap".
                try { mRecyclerListener.onMovedToScrapHeap(child); } catch (Throwable ignored) {}
            }
        }
        mRecycler.reclaimScrapViews(views);
        removeAllViews();
    }

    /** AOSP AbsListView :5363 -- package-private; ListView and GridView expose
     *  public overrides of it. Scroll by `position` ITEMS, measuring from the
     *  first (scrolling up) or last (scrolling down) visible row and nudging
     *  the anchor by one when that row is less than 75% visible, so a
     *  partially-shown row does not silently eat one step. */
    void smoothScrollByOffset(int position) {
        int index = -1;
        if (position < 0) {
            index = getFirstVisiblePosition();
        } else if (position > 0) {
            index = getLastVisiblePosition();
        }
        if (index > -1) {
            View child = getChildAt(index - getFirstVisiblePosition());
            if (child != null) {
                Rect visibleRect = new Rect();
                if (child.getGlobalVisibleRect(visibleRect)) {
                    int childRectArea = child.getWidth() * child.getHeight();
                    int visibleRectArea = visibleRect.width() * visibleRect.height();
                    float visibleArea = childRectArea == 0 ? 1f
                            : (visibleRectArea / (float) childRectArea);
                    final float visibleThreshold = 0.75f;
                    if ((position < 0) && (visibleArea < visibleThreshold)) {
                        ++index;
                    } else if ((position > 0) && (visibleArea < visibleThreshold)) {
                        --index;
                    }
                }
                smoothScrollToPosition(Math.max(0, Math.min(getCount(), index + position)));
            }
        }
    }
}
