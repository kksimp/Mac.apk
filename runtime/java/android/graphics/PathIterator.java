package android.graphics;

import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * AOSP-faithful android.graphics.PathIterator (API 34). Mirrors
 * frameworks/base/graphics/java/android/graphics/PathIterator.java and its JNI
 * (libs/hwui/jni/PathIterator.cpp), which drives an SkPath::RawIter. We back it
 * by java.awt.geom.PathIterator over the Path's AWT shape (Path.macapkShape())
 * and reproduce RawIter's point layout, because callers index into it:
 *
 *   VERB_MOVE   points[0..1]            the move-to point
 *   VERB_LINE   points[0..3]            start point, end point
 *   VERB_QUAD   points[0..5]            start, control, end
 *   VERB_CONIC  points[0..5], [6]       start, control, end; the weight (never produced: AWT has no conics)
 *   VERB_CUBIC  points[0..7]            start, control 1, control 2, end
 *   VERB_CLOSE  nothing written
 *
 * RawIter hands back every segment's START point (the previous end point) as its
 * first point, which AWT's currentSegment() does not carry; androidx.graphics.path
 * and Compose's PathEasing read the start points and measured garbage without them
 * ("The easing path must start at 0.0f and end at 1.0f" in Play Store). Like Skia,
 * a drawing verb that follows a close (with no move-to between) is preceded by an
 * injected VERB_MOVE at the contour's start (SkPath::injectMoveToIfNeeded).
 *
 * The 8-float buffer is kept between calls, as AOSP keeps mPointsArray: next()
 * copies all eight floats, and entries a verb does not write keep their previous value.
 *
 * Not reproduced: the ConcurrentModificationException AOSP throws when the Path is
 * modified after the iterator is created (Path.getGenerationId() here is an O(n) value
 * hash, so checking it on every hasNext() would make a walk quadratic).
 */
public class PathIterator implements Iterator<PathIterator.Segment> {
    public static final int VERB_MOVE  = 0;
    public static final int VERB_LINE  = 1;
    public static final int VERB_QUAD  = 2;
    public static final int VERB_CONIC = 3;
    public static final int VERB_CUBIC = 4;
    public static final int VERB_CLOSE = 5;
    public static final int VERB_DONE  = 6;

    private static final int POINT_ARRAY_SIZE = 8;

    private final java.awt.geom.PathIterator mAwt;
    private final float[] mPointsArray = new float[POINT_ARRAY_SIZE];
    private final double[] mCoords = new double[6];
    private int mCachedVerb = -1;
    private boolean mDone = false;

    // RawIter state: the pen position, and the start of the current contour.
    private float mLastX, mLastY, mMoveX, mMoveY;
    private boolean mNeedsMove = false;

    public PathIterator(Path path) {
        if (path == null) throw new NullPointerException("path");
        mAwt = path.macapkShape().getPathIterator(null);
    }

    /** Advance the AWT iterator by one RawIter step, filling mPointsArray. */
    private int nNext() {
        if (mNeedsMove) {
            // A contour continues after a close: Skia emits the move-to first.
            mNeedsMove = false;
            mPointsArray[0] = mMoveX;
            mPointsArray[1] = mMoveY;
            mLastX = mMoveX;
            mLastY = mMoveY;
            return VERB_MOVE;
        }
        if (mAwt.isDone()) return VERB_DONE;
        final double[] c = mCoords;
        final float[] p = mPointsArray;
        final int type = mAwt.currentSegment(c);
        if (type != java.awt.geom.PathIterator.SEG_MOVETO
                && type != java.awt.geom.PathIterator.SEG_CLOSE
                && mAfterClose) {
            mAfterClose = false;
            mNeedsMove = true;
            return nNext();
        }
        mAwt.next();
        switch (type) {
            case java.awt.geom.PathIterator.SEG_MOVETO:
                mAfterClose = false;
                mMoveX = mLastX = p[0] = (float) c[0];
                mMoveY = mLastY = p[1] = (float) c[1];
                return VERB_MOVE;
            case java.awt.geom.PathIterator.SEG_LINETO:
                p[0] = mLastX; p[1] = mLastY;
                mLastX = p[2] = (float) c[0];
                mLastY = p[3] = (float) c[1];
                return VERB_LINE;
            case java.awt.geom.PathIterator.SEG_QUADTO:
                p[0] = mLastX; p[1] = mLastY;
                p[2] = (float) c[0]; p[3] = (float) c[1];
                mLastX = p[4] = (float) c[2];
                mLastY = p[5] = (float) c[3];
                return VERB_QUAD;
            case java.awt.geom.PathIterator.SEG_CUBICTO:
                p[0] = mLastX; p[1] = mLastY;
                p[2] = (float) c[0]; p[3] = (float) c[1];
                p[4] = (float) c[2]; p[5] = (float) c[3];
                mLastX = p[6] = (float) c[4];
                mLastY = p[7] = (float) c[5];
                return VERB_CUBIC;
            case java.awt.geom.PathIterator.SEG_CLOSE:
                mAfterClose = true;
                mLastX = mMoveX;
                mLastY = mMoveY;
                return VERB_CLOSE;
            default:
                return VERB_DONE;
        }
    }

    private boolean mAfterClose = false;

    private int nextInternal() {
        if (mDone) return VERB_DONE;
        int verb = nNext();
        if (verb == VERB_DONE) mDone = true;
        return verb;
    }

    private int getReturnVerb(int cachedVerb) {
        switch (cachedVerb) {
            case VERB_MOVE: return VERB_MOVE;
            case VERB_LINE: return VERB_LINE;
            case VERB_QUAD: return VERB_QUAD;
            case VERB_CONIC: return VERB_CONIC;
            case VERB_CUBIC: return VERB_CUBIC;
            case VERB_CLOSE: return VERB_CLOSE;
            case VERB_DONE: return VERB_DONE;
        }
        return nextInternal();
    }

    /** AOSP PathIterator.peek(): the next verb, without consuming it. */
    public int peek() {
        if (mCachedVerb != -1) return mCachedVerb;
        if (mDone) return VERB_DONE;
        if (mNeedsMove) return VERB_MOVE;
        if (mAwt.isDone()) return VERB_DONE;
        int type = mAwt.currentSegment(mCoords);
        if (mAfterClose && type != java.awt.geom.PathIterator.SEG_MOVETO
                && type != java.awt.geom.PathIterator.SEG_CLOSE) {
            return VERB_MOVE;
        }
        switch (type) {
            case java.awt.geom.PathIterator.SEG_MOVETO:  return VERB_MOVE;
            case java.awt.geom.PathIterator.SEG_LINETO:  return VERB_LINE;
            case java.awt.geom.PathIterator.SEG_QUADTO:  return VERB_QUAD;
            case java.awt.geom.PathIterator.SEG_CUBICTO: return VERB_CUBIC;
            case java.awt.geom.PathIterator.SEG_CLOSE:   return VERB_CLOSE;
            default:                                     return VERB_DONE;
        }
    }

    @Override
    public boolean hasNext() {
        if (mCachedVerb == -1) {
            mCachedVerb = nextInternal();
        }
        return mCachedVerb != VERB_DONE;
    }

    /**
     * Writes the next segment's points into {@code points} starting at {@code offset}
     * (all eight floats are copied, see the class comment) and returns its verb.
     */
    public int next(float[] points, int offset) {
        if (points == null) throw new NullPointerException("points");
        if (points.length < offset + POINT_ARRAY_SIZE) {
            throw new ArrayIndexOutOfBoundsException("points array must be able to "
                    + "hold at least 8 entries");
        }
        int returnVerb = getReturnVerb(mCachedVerb);
        mCachedVerb = -1;
        System.arraycopy(mPointsArray, 0, points, offset, POINT_ARRAY_SIZE);
        return returnVerb;
    }

    @Override
    public Segment next() {
        int returnVerb = getReturnVerb(mCachedVerb);
        mCachedVerb = -1;
        float conicWeight = 0f;
        if (returnVerb == VERB_CONIC) {
            conicWeight = mPointsArray[6];
        }
        float[] returnPoints = new float[POINT_ARRAY_SIZE];
        System.arraycopy(mPointsArray, 0, returnPoints, 0, POINT_ARRAY_SIZE);
        return new Segment(returnVerb, returnPoints, conicWeight);
    }

    public static class Segment {
        private final int mVerb;
        private final float[] mPoints;
        private final float mConicWeight;

        public Segment(int verb, float[] points, float conicWeight) {
            mVerb = verb;
            mPoints = points;
            mConicWeight = conicWeight;
        }

        public int getVerb() { return mVerb; }
        public float[] getPoints() { return mPoints; }
        public float getConicWeight() { return mConicWeight; }
    }
}
