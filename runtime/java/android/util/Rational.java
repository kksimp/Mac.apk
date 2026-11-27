// android.util.Rational -- an immutable rational number (numerator /
// denominator). AOSP API-21+. Created here because
// android.app.PictureInPictureParams (05-app subsystem) takes Rational
// aspect ratios and the type was absent; it is a self-contained
// Number subclass with no Android dependencies.
//
// AOSP ref: frameworks/base/core/java/android/util/Rational.java. We
// mirror the reduce-on-construct behavior + the public surface
// (numerator/denominator getters, comparison, NaN/Infinity sentinels).
package android.util;

public final class Rational extends Number implements Comparable<Rational> {
    public static final Rational NaN = new Rational(0, 0);
    public static final Rational POSITIVE_INFINITY = new Rational(1, 0);
    public static final Rational NEGATIVE_INFINITY = new Rational(-1, 0);
    public static final Rational ZERO = new Rational(0, 1);

    private final int mNumerator;
    private final int mDenominator;

    public Rational(int numerator, int denominator) {
        // Normalize sign onto the numerator, then reduce by the gcd, so
        // equals/hashCode are canonical -- matching AOSP.
        if (denominator < 0) { numerator = -numerator; denominator = -denominator; }
        if (numerator == 0 && denominator == 0) {
            mNumerator = 0; mDenominator = 0;          // NaN
        } else if (numerator != 0 && denominator == 0) {
            mNumerator = numerator > 0 ? 1 : -1; mDenominator = 0;  // +/- Inf
        } else if (numerator == 0) {
            mNumerator = 0; mDenominator = 1;          // canonical zero
        } else {
            int g = gcd(numerator, denominator);
            mNumerator = numerator / g;
            mDenominator = denominator / g;
        }
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = b;
            b = a % b;
            a = t;
        }
        // Normalize the gcd at the end. Taking abs before Euclid's loop does
        // not make Integer.MIN_VALUE positive and can leave a negative gcd.
        return Math.abs(a);
    }

    public int getNumerator()   { return mNumerator; }
    public int getDenominator() { return mDenominator; }

    public boolean isNaN()      { return mDenominator == 0 && mNumerator == 0; }
    public boolean isInfinite() { return mNumerator != 0 && mDenominator == 0; }
    public boolean isFinite()   { return mDenominator != 0; }
    public boolean isZero()     { return isFinite() && mNumerator == 0; }

    @Override public float floatValue()   { return (float) mNumerator / (float) mDenominator; }
    @Override public double doubleValue()  {
        if (isNaN()) return Double.NaN;
        if (isInfinite()) return mNumerator > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        return (double) mNumerator / (double) mDenominator;
    }
    @Override public int intValue() {
        if (mDenominator != 0) return mNumerator / mDenominator;
        if (mNumerator > 0) return Integer.MAX_VALUE;
        if (mNumerator < 0) return Integer.MIN_VALUE;
        return 0;
    }
    @Override public long longValue() {
        if (mDenominator != 0) return (long) mNumerator / mDenominator;
        if (mNumerator > 0) return Long.MAX_VALUE;
        if (mNumerator < 0) return Long.MIN_VALUE;
        return 0L;
    }
    @Override public short shortValue() { return (short) intValue(); }
    @Override public byte byteValue()   { return (byte) intValue(); }

    @Override public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Rational)) return false;
        Rational r = (Rational) obj;
        return mNumerator == r.mNumerator && mDenominator == r.mDenominator;
    }
    @Override public int hashCode() {
        final int prime = 31;
        int numeratorFlipped = mNumerator << 16 | mNumerator >>> 16;
        return mDenominator ^ numeratorFlipped;
    }

    @Override public int compareTo(Rational another) {
        if (another == null) throw new NullPointerException("another must not be null");
        if (equals(another)) return 0;
        if (isNaN()) return 1;
        if (another.isNaN()) return -1;
        if (isInfinite()) return mNumerator > 0 ? 1 : -1;
        if (another.isInfinite()) return another.mNumerator > 0 ? -1 : 1;
        // Two int products fit in a long. Compare them directly rather than
        // rounding both rational values to doubles or subtracting the products.
        long left = (long) mNumerator * another.mDenominator;
        long right = (long) another.mNumerator * mDenominator;
        return Long.compare(left, right);
    }

    @Override public String toString() {
        if (isNaN()) return "NaN";
        if (isInfinite()) return mNumerator > 0 ? "Infinity" : "-Infinity";
        return mNumerator + "/" + mDenominator;
    }

    public static Rational parseRational(String string) throws NumberFormatException {
        if (string == null) throw new NullPointerException("string must not be null");
        if (string.equals("NaN")) return NaN;
        if (string.equals("Infinity")) return POSITIVE_INFINITY;
        if (string.equals("-Infinity")) return NEGATIVE_INFINITY;
        int sep = string.indexOf(':');
        if (sep < 0) sep = string.indexOf('/');
        if (sep < 0) throw new NumberFormatException("Invalid Rational: \"" + string + "\"");
        try {
            int n = Integer.parseInt(string.substring(0, sep));
            int d = Integer.parseInt(string.substring(sep + 1));
            return new Rational(n, d);
        } catch (NumberFormatException e) {
            throw new NumberFormatException("Invalid Rational: \"" + string + "\"");
        }
    }
}
