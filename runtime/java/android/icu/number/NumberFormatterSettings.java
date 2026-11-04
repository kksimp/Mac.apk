package android.icu.number;

import android.icu.util.MeasureUnit;

/**
 * Shared fluent-setting base for the localized/unlocalized formatters. Every
 * setter returns a NEW instance, matching ICU's immutable-builder contract --
 * apps hold a partially-configured formatter and derive several from it, so
 * mutating in place would corrupt the shared prefix.
 *
 * Source: https://developer.android.com/reference/android/icu/number/NumberFormatterSettings
 */
public abstract class NumberFormatterSettings<T extends NumberFormatterSettings<?>> {

    Precision mPrecision;
    MeasureUnit mUnit;
    NumberFormatter.UnitWidth mUnitWidth;
    NumberFormatter.SignDisplay mSignDisplay;
    NumberFormatter.GroupingStrategy mGrouping;
    Integer mMinIntegerDigits;
    java.math.RoundingMode mRoundingMode;

    NumberFormatterSettings() { }

    abstract T copy();

    @SuppressWarnings("unchecked")
    public T precision(Precision precision) {
        T c = copy(); ((NumberFormatterSettings<?>) c).mPrecision = precision; return c;
    }

    @SuppressWarnings("unchecked")
    public T unit(MeasureUnit unit) {
        T c = copy(); ((NumberFormatterSettings<?>) c).mUnit = unit; return c;
    }

    @SuppressWarnings("unchecked")
    public T unitWidth(NumberFormatter.UnitWidth width) {
        T c = copy(); ((NumberFormatterSettings<?>) c).mUnitWidth = width; return c;
    }

    @SuppressWarnings("unchecked")
    public T sign(NumberFormatter.SignDisplay style) {
        T c = copy(); ((NumberFormatterSettings<?>) c).mSignDisplay = style; return c;
    }

    @SuppressWarnings("unchecked")
    public T grouping(NumberFormatter.GroupingStrategy strategy) {
        T c = copy(); ((NumberFormatterSettings<?>) c).mGrouping = strategy; return c;
    }

    /** ICU NumberFormatterSettings.roundingMode(RoundingMode): the rounding
     *  direction applied when the precision truncates digits. Immutable-builder
     *  like every sibling. Honored by LocalizedNumberFormatter.format via
     *  java.text.DecimalFormat.setRoundingMode. Play Services' price/size
     *  formatters chain this and hit NoSuchMethodError without it. */
    @SuppressWarnings("unchecked")
    public T roundingMode(java.math.RoundingMode roundingMode) {
        T c = copy(); ((NumberFormatterSettings<?>) c).mRoundingMode = roundingMode; return c;
    }

    @SuppressWarnings("unchecked")
    public T integerWidth(IntegerWidth style) {
        T c = copy();
        ((NumberFormatterSettings<?>) c).mMinIntegerDigits =
                style != null ? style.mMinInt : null;
        return c;
    }

    void copyInto(NumberFormatterSettings<?> other) {
        other.mPrecision = mPrecision;
        other.mUnit = mUnit;
        other.mUnitWidth = mUnitWidth;
        other.mSignDisplay = mSignDisplay;
        other.mGrouping = mGrouping;
        other.mMinIntegerDigits = mMinIntegerDigits;
        other.mRoundingMode = mRoundingMode;
    }

    @SuppressWarnings("unchecked")
    public T notation(Notation notation) { return (T) this; }
}

