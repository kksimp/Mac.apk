// android.widget.GridView -- AbsListView that lays its rows out in a grid of
// N columns. Was a bare `extends Object` stub (broke instanceof AbsListView /
// AdapterView and any grid inflate). Now a real AbsListView subclass with a
// column-aware fill: it measures the column width from numColumns (or from a
// requested columnWidth in AUTO_FIT mode), then lays adapter items left-to-
// right, top-to-bottom, reusing AbsListView.obtainView for row recycling.
// Vertical drag-scroll is inherited from AbsListView. AOSP:
//   frameworks/base/core/java/android/widget/GridView.java
//   public class GridView extends AbsListView
package android.widget;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;

public class GridView extends AbsListView {
    public static final int AUTO_FIT  = -1;
    public static final int NO_STRETCH            = 0;
    public static final int STRETCH_SPACING       = 1;
    public static final int STRETCH_COLUMN_WIDTH  = 2;
    public static final int STRETCH_SPACING_UNIFORM = 3;

    private int mNumColumns = AUTO_FIT;
    private int mRequestedColumnWidth = 0;
    private int mRequestedHorizontalSpacing = 0;
    private int mVerticalSpacing = 0;
    private int mStretchMode = STRETCH_COLUMN_WIDTH;
    private int mColumnWidth;
    private int mGravity = 0;

    // AOSP GridView: mRequestNumColumns / mRequestedHorizontalSpacing are what
    // the app asked for; mNumColumns / mHorizontalSpacing / mColumnWidth are
    // what determineColumns() resolved for the current width. Here
    // mNumColumns keeps the REQUESTED count (its old meaning) and the resolved
    // values live in the mResolved* fields.
    private int mResolvedNumColumns = 1;
    private int mResolvedHorizontalSpacing = 0;

    /** AOSP GridView(Context, AttributeSet, int, int) reads its styleable
     *  (android14 GridView.java:150-180):
     *      setHorizontalSpacing(a.getDimensionPixelOffset(GridView_horizontalSpacing, 0));
     *      setVerticalSpacing(a.getDimensionPixelOffset(GridView_verticalSpacing, 0));
     *      int index = a.getInt(GridView_stretchMode, STRETCH_COLUMN_WIDTH);
     *      if (index >= 0) setStretchMode(index);
     *      int columnWidth = a.getDimensionPixelOffset(GridView_columnWidth, -1);
     *      if (columnWidth > 0) setColumnWidth(columnWidth);
     *      int numColumns = a.getInt(GridView_numColumns, 1);
     *      setNumColumns(numColumns);
     *      index = a.getInt(GridView_gravity, -1);
     *      if (index >= 0) setGravity(index);
     *  None of it was read, so XML grids kept our defaults. */
    private static final int[] MACAPK_GRID_ATTRS = {
        0x010100af, // gravity
        0x01010114, // horizontalSpacing
        0x01010115, // verticalSpacing
        0x01010116, // stretchMode
        0x01010117, // columnWidth
        0x01010118, // numColumns
    };
    private void macapkReadGridAttrs(Context c, android.util.AttributeSet attrs, int defStyleAttr) {
        mNumColumns = 1;
        mGravity = Gravity.START;
        if (c == null) return;
        android.content.res.TypedArray a = null;
        try {
            a = c.obtainStyledAttributes(attrs, MACAPK_GRID_ATTRS, defStyleAttr, 0);
            mRequestedHorizontalSpacing = a.getDimensionPixelOffset(1, 0);
            mVerticalSpacing = a.getDimensionPixelOffset(2, 0);
            int stretch = a.getInt(3, STRETCH_COLUMN_WIDTH);
            if (stretch >= 0) mStretchMode = stretch;
            int cw = a.getDimensionPixelOffset(4, -1);
            if (cw > 0) mRequestedColumnWidth = cw;
            mNumColumns = a.getInt(5, 1);
            int g = a.getInt(0, -1);
            if (g >= 0) mGravity = g;
        } catch (Throwable ignored) {
        } finally {
            if (a != null) { try { a.recycle(); } catch (Throwable ignored) {} }
        }
    }

    public GridView(Context c) { super(c); macapkReadGridAttrs(c, null, 0x01010071); }
    public GridView(Context c, android.util.AttributeSet a) { super(c, a); macapkReadGridAttrs(c, a, 0x01010071); }
    /** AOSP API 21 four-argument constructor. AOSP's three-argument form is
     *  exactly this(context, attrs, defStyleAttr, 0), so delegating the other
     *  way is identical for defStyleRes == 0; a non-zero defStyleRes is
     *  DROPPED here, as in the existing FrameLayout/Button four-argument forms
     *  (TextView, ProgressBar and their subclasses honour it). Without it an
     *  app subclass calling the four-argument
     *  super() dies with NoSuchMethodError; see debug/ctorsdk_gate.sh. */
    public GridView(Context context, android.util.AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        this(context, attrs, defStyleAttr);
    }
    public GridView(Context c, android.util.AttributeSet a, int s) { super(c, a, s); macapkReadGridAttrs(c, a, s); }

    public void setNumColumns(int numColumns) { mNumColumns = numColumns; requestLayout(); }
    /** AOSP getNumColumns(): the column count determineColumns resolved. */
    public int  getNumColumns() {
        if (mNumColumns != AUTO_FIT) return Math.max(1, mNumColumns);
        return Math.max(1, mResolvedNumColumns > 0 && mColumnWidth > 0 ? mResolvedNumColumns : computeAutoColumns());
    }
    public void setColumnWidth(int columnWidth) { mRequestedColumnWidth = columnWidth; requestLayout(); }
    public int  getColumnWidth() { return mColumnWidth; }
    public int  getRequestedColumnWidth() { return mRequestedColumnWidth; }
    public void setHorizontalSpacing(int hs) { mRequestedHorizontalSpacing = hs; requestLayout(); }
    public int  getHorizontalSpacing() {
        return MACAPK_GRID_MEASURE_AOSP ? mResolvedHorizontalSpacing : mRequestedHorizontalSpacing;
    }
    public int  getRequestedHorizontalSpacing() { return mRequestedHorizontalSpacing; }
    public void setVerticalSpacing(int vs) { mVerticalSpacing = vs; requestLayout(); }
    public int  getVerticalSpacing() { return mVerticalSpacing; }
    public void setStretchMode(int stretchMode) { mStretchMode = stretchMode; requestLayout(); }
    public int  getStretchMode() { return mStretchMode; }
    public void setGravity(int gravity) {
        if (gravity != mGravity) { mGravity = gravity; requestLayout(); }
    }
    public int  getGravity() { return mGravity; }

    private int computeAutoColumns() {
        int avail = getWidth() - getPaddingLeft() - getPaddingRight();
        if (mRequestedColumnWidth > 0 && avail > 0) {
            return Math.max(1, (avail + mRequestedHorizontalSpacing)
                    / (mRequestedColumnWidth + mRequestedHorizontalSpacing));
        }
        return 2; // AOSP defaults numColumns to 2 (AUTO_FIT with no width hint)
    }

    /** AOSP GridView.determineColumns (android14 :1070-1131), verbatim in
     *  shape: resolves the column count, the column width and the horizontal
     *  spacing for availableSpace by the stretch mode. Returns true when the
     *  requested columns did not fit. */
    private boolean determineColumns(int availableSpace) {
        final int requestedHorizontalSpacing = mRequestedHorizontalSpacing;
        final int stretchMode = mStretchMode;
        final int requestedColumnWidth = mRequestedColumnWidth;
        boolean didNotInitiallyFit = false;
        int numColumns;
        if (mNumColumns == AUTO_FIT) {
            if (requestedColumnWidth > 0) {
                numColumns = (availableSpace + requestedHorizontalSpacing)
                        / (requestedColumnWidth + requestedHorizontalSpacing);
            } else {
                numColumns = 2;
            }
        } else {
            numColumns = mNumColumns;
        }
        if (numColumns <= 0) numColumns = 1;
        mResolvedNumColumns = numColumns;
        switch (stretchMode) {
            case NO_STRETCH:
                mColumnWidth = requestedColumnWidth;
                mResolvedHorizontalSpacing = requestedHorizontalSpacing;
                break;
            default:
                int spaceLeftOver = availableSpace - (numColumns * requestedColumnWidth)
                        - ((numColumns - 1) * requestedHorizontalSpacing);
                if (spaceLeftOver < 0) didNotInitiallyFit = true;
                switch (stretchMode) {
                    case STRETCH_COLUMN_WIDTH:
                        mColumnWidth = requestedColumnWidth + spaceLeftOver / numColumns;
                        mResolvedHorizontalSpacing = requestedHorizontalSpacing;
                        break;
                    case STRETCH_SPACING:
                        mColumnWidth = requestedColumnWidth;
                        mResolvedHorizontalSpacing = numColumns > 1
                                ? requestedHorizontalSpacing + spaceLeftOver / (numColumns - 1)
                                : requestedHorizontalSpacing + spaceLeftOver;
                        break;
                    case STRETCH_SPACING_UNIFORM:
                        mColumnWidth = requestedColumnWidth;
                        mResolvedHorizontalSpacing = numColumns > 1
                                ? requestedHorizontalSpacing + spaceLeftOver / (numColumns + 1)
                                : requestedHorizontalSpacing + spaceLeftOver;
                        break;
                }
                break;
        }
        if (mColumnWidth <= 0) mColumnWidth = 1;
        return didNotInitiallyFit;
    }

    /** AOSP GridView.onMeasure (android14 :1134-1214). A wrap_content grid
     *  measured AT_MOST is as tall as its rows (row height = the first item's
     *  height), capped at the offered size; UNSPECIFIED is one row. Ours
     *  inherited AbsListView.onMeasure, which fills the offered height, so a
     *  grid in a dialog stretched the dialog to the whole window (Privacy Friendly Notes'
     *  category colour picker: five rows of swatches over a screen of blank).
     *  MACAPK_GRID_MEASURE_AOSP=0 restores the old measure. */
    static final boolean MACAPK_GRID_MEASURE_AOSP =
        !"0".equals(System.getenv("MACAPK_GRID_MEASURE_AOSP"));
    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (!MACAPK_GRID_MEASURE_AOSP) { super.onMeasure(widthMeasureSpec, heightMeasureSpec); return; }
        final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        final int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        final int padL = getPaddingLeft(), padR = getPaddingRight();
        final int padT = getPaddingTop(), padB = getPaddingBottom();
        if (widthMode == MeasureSpec.UNSPECIFIED) {
            widthSize = (mColumnWidth > 0 ? mColumnWidth : 0) + padL + padR;
        }
        final int childWidth = widthSize - padL - padR;
        determineColumns(childWidth);
        int childHeight = 0;
        final ListAdapter adapter = getAdapter();
        final int count = adapter == null ? 0 : adapter.getCount();
        if (count > 0 && heightMode != MeasureSpec.EXACTLY) {
            try {
                final View child = obtainView(0);
                final ViewGroup.LayoutParams p = child.getLayoutParams();
                final int childHeightSpec = ViewGroup.getChildMeasureSpec(
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), 0,
                        p != null ? p.height : ViewGroup.LayoutParams.WRAP_CONTENT);
                final int childWidthSpec = ViewGroup.getChildMeasureSpec(
                        MeasureSpec.makeMeasureSpec(mColumnWidth, MeasureSpec.EXACTLY), 0,
                        p != null ? p.width : ViewGroup.LayoutParams.MATCH_PARENT);
                child.measure(childWidthSpec, childHeightSpec);
                childHeight = child.getMeasuredHeight();
            } catch (Throwable ignored) {}
        }
        if (heightMode == MeasureSpec.UNSPECIFIED) {
            heightSize = padT + padB + childHeight;
        } else if (heightMode == MeasureSpec.AT_MOST) {
            int ourSize = padT + padB;
            final int numColumns = mResolvedNumColumns;
            for (int i = 0; i < count; i += numColumns) {
                ourSize += childHeight;
                if (i + numColumns < count) ourSize += mVerticalSpacing;
                if (ourSize >= heightSize) { ourSize = heightSize; break; }
            }
            heightSize = ourSize;
        }
        setMeasuredDimension(widthSize, heightSize);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        layoutGrid();
    }

    private void layoutGrid() {
        if (getAdapter() == null) {
            if (AbsListView.ITEMCLICK_AOSP) removeAllViewsInLayout();  // AOSP resetList()
            macapkGridLayoutDone();
            return;
        }
        removeAllViewsInLayout();
        final int count = getAdapter().getCount();
        if (count == 0) { macapkGridLayoutDone(); return; }

        final int paddingLeft = getPaddingLeft();
        final int paddingRight = getPaddingRight();
        final int paddingTop = getPaddingTop();
        final int avail = getWidth() - paddingLeft - paddingRight;
        final int cols;
        final int hs;
        if (MACAPK_GRID_MEASURE_AOSP) {
            determineColumns(avail);
            cols = Math.max(1, mResolvedNumColumns);
            hs = mResolvedHorizontalSpacing;
        } else {
            cols = Math.max(1, getNumColumns());
            hs = mRequestedHorizontalSpacing;
            mColumnWidth = mRequestedColumnWidth > 0 && mStretchMode == NO_STRETCH
                    ? mRequestedColumnWidth
                    : Math.max(1, (avail - hs * (cols - 1)) / cols);
        }
        // AOSP GridView.makeRow (android14 :303-345): under RTL the first
        // column is the RIGHT-most one and the row grows leftwards; the cell's
        // child is placed in its column by mGravity resolved against the
        // layout direction (setupChild :1471-1486). Ours always ran
        // left-to-right with the child stretched over the column.
        final boolean rtl = MACAPK_GRID_MEASURE_AOSP && isLayoutRtl();
        final int uniform = (mStretchMode == STRETCH_SPACING_UNIFORM) ? hs : 0;
        final int absH = Gravity.getAbsoluteGravity(mGravity,
                rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR) & Gravity.HORIZONTAL_GRAVITY_MASK;

        final int bottomEdge = getHeight() - getPaddingBottom();
        int pos = Math.max(0, mFirstPosition);
        int rowTop = paddingTop;

        while (pos < count && rowTop < bottomEdge) {
            int rowHeight = 0;
            int nextLeft = rtl ? getWidth() - paddingRight - mColumnWidth - uniform
                               : paddingLeft + uniform;
            for (int c = 0; c < cols && pos < count; c++, pos++) {
                View child = obtainView(pos);
                final ViewGroup.LayoutParams p = child.getLayoutParams();
                int childWidthSpec;
                int childHeightSpec;
                if (MACAPK_GRID_MEASURE_AOSP) {
                    childWidthSpec = ViewGroup.getChildMeasureSpec(
                            MeasureSpec.makeMeasureSpec(mColumnWidth, MeasureSpec.EXACTLY), 0,
                            p != null ? p.width : ViewGroup.LayoutParams.MATCH_PARENT);
                    childHeightSpec = ViewGroup.getChildMeasureSpec(
                            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), 0,
                            p != null ? p.height : ViewGroup.LayoutParams.WRAP_CONTENT);
                } else {
                    childWidthSpec = MeasureSpec.makeMeasureSpec(mColumnWidth, MeasureSpec.EXACTLY);
                    childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
                }
                child.measure(childWidthSpec, childHeightSpec);
                int w = child.getMeasuredWidth();
                int h = child.getMeasuredHeight();
                int x;
                if (!MACAPK_GRID_MEASURE_AOSP) {
                    x = paddingLeft + c * (mColumnWidth + hs);
                    w = mColumnWidth;
                } else if (absH == Gravity.CENTER_HORIZONTAL) {
                    x = nextLeft + (mColumnWidth - w) / 2;
                } else if (absH == Gravity.RIGHT) {
                    x = nextLeft + mColumnWidth - w;
                } else {
                    x = nextLeft;
                }
                addViewInLayout(child, getChildCount(), child.getLayoutParams());
                child.layout(x, rowTop, x + w, rowTop + h);
                bindGridClick(child, pos);
                if (h > rowHeight) rowHeight = h;
                nextLeft += (rtl ? -1 : 1) * (mColumnWidth + hs);
            }
            rowTop += rowHeight + mVerticalSpacing;
        }
        macapkGridLayoutDone();
    }

    private void bindGridClick(final View cell, final int position) {
        if (AbsListView.ITEMCLICK_AOSP) return;  // AbsListView.onTouchEvent clicks cells, as in AOSP
        final long id = getAdapter().getItemId(position);
        cell.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { performItemClick(cell, position, id); }
        });
    }

    /** AOSP GridView :882 -- a public override of AbsListView's
     *  package-private smoothScrollByOffset(int), same as ListView's. */
    @Override
    public void smoothScrollByOffset(int offset) { super.smoothScrollByOffset(offset); }
}
