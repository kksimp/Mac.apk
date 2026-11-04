package android.icu.number;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * A locale-bound formatter. format(...) produces a FormattedNumber.
 *
 * Backed by java.text.DecimalFormat for the locale, honoring the precision,
 * grouping, sign and integer-width settings. Measure units are carried through
 * to the FormattedNumber (so getOutputUnit works) and appended in their short
 * form; ICU's full unit-name spelling and compact notation are NOT reproduced,
 * which is noted here rather than silently emitting different text.
 *
 * Source: https://developer.android.com/reference/android/icu/number/LocalizedNumberFormatter
 */
public class LocalizedNumberFormatter
        extends NumberFormatterSettings<LocalizedNumberFormatter> {

    private final Locale mLocale;

    LocalizedNumberFormatter(Locale locale) {
        mLocale = locale != null ? locale : Locale.getDefault();
    }

    @Override LocalizedNumberFormatter copy() {
        LocalizedNumberFormatter c = new LocalizedNumberFormatter(mLocale);
        copyInto(c);
        return c;
    }

    public FormattedNumber format(long input) { return format(Long.valueOf(input)); }

    public FormattedNumber format(double input) { return format(Double.valueOf(input)); }

    public FormattedNumber format(Number input) {
        NumberFormat nf = NumberFormat.getInstance(mLocale);
        if (nf instanceof DecimalFormat) {
            DecimalFormat df = (DecimalFormat) nf;
            if (mGrouping == NumberFormatter.GroupingStrategy.OFF) {
                df.setGroupingUsed(false);
            }
            if (mMinIntegerDigits != null) {
                df.setMinimumIntegerDigits(mMinIntegerDigits);
            }
            if (mRoundingMode != null) {
                df.setRoundingMode(mRoundingMode);
            }
            if (mPrecision != null) {
                mPrecision.applyTo(df);
                if (mPrecision instanceof Precision.SignificantDigits) {
                    ((Precision.SignificantDigits) mPrecision).resolveFor(df, input);
                }
            }
            if (mSignDisplay == NumberFormatter.SignDisplay.ALWAYS
                    || mSignDisplay == NumberFormatter.SignDisplay.ACCOUNTING_ALWAYS) {
                // java.text.DecimalFormatSymbols has no getPlusSign() (that is
                // ICU4J); U+002B is what CLDR uses for every locale we serve.
                df.setPositivePrefix("+");
            }
        }
        String s = nf.format(input);
        if (mUnit != null && mUnitWidth != NumberFormatter.UnitWidth.HIDDEN) {
            s = s + " " + mUnit.getSubtype();
        }
        return new FormattedNumber(s, input, mUnit);
    }

    public FormattedNumber format(BigDecimal input) { return format((Number) input); }

    public Locale getLocale() { return mLocale; }
}
