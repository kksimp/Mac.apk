// A layout whose LayoutParams look at their size and margins in their own
// constructor, as ConstraintLayout 1.0's validate() does: the children are
// placed by what the constructor saw, so a framework that fills the fields in
// later shows up as a different layout.
package dev.winapk.probe;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

public class ProbeLayout extends ViewGroup {
    public ProbeLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public static class Params extends MarginLayoutParams {
        final int seenWidth, seenHeight, seenStart, seenTop;

        public Params(Context c, AttributeSet attrs) {
            super(c, attrs);
            seenWidth = width;
            seenHeight = height;
            seenStart = getMarginStart();
            seenTop = topMargin;
        }
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new Params(getContext(), attrs);
    }

    private static int sizeFor(int seen, int whenZero, int whenMatch, int whenWrap) {
        if (seen > 0) return seen;
        return seen == 0 ? whenZero : seen == LayoutParams.MATCH_PARENT ? whenMatch : whenWrap;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            Params p = (Params) child.getLayoutParams();
            child.measure(MeasureSpec.makeMeasureSpec(sizeFor(p.seenWidth, 70, w / 2, 30), MeasureSpec.EXACTLY),
                          MeasureSpec.makeMeasureSpec(sizeFor(p.seenHeight, 50, h, 20), MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int x = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            Params p = (Params) child.getLayoutParams();
            x += p.seenStart;
            child.layout(x, p.seenTop, x + child.getMeasuredWidth(), p.seenTop + child.getMeasuredHeight());
            x += child.getMeasuredWidth();
        }
    }
}
