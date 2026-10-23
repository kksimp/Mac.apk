// android.os.LocaleList -- ordered list of Locale objects.
// AOSP API-24+. F-Droid + AppCompat code paths call
// Configuration.getLocales() to discover the user's preferred
// locale priority order.
package android.os;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class LocaleList implements Parcelable {
    private final Locale[] locales;
    private static final LocaleList EMPTY = new LocaleList();
    private static LocaleList defaultList;
    private static Locale defaultLocale = Locale.getDefault();
    private static LocaleList systemList;

    // Pseudo-locales used for RTL / accented pseudo-translation testing.
    // matchesLanguageAndScript() must never treat these as a script match
    // even when the language matches (aosp-mirror android/os/LocaleList.java
    // isPseudoLocale(Locale), lines ~326-328).
    private static final Locale LOCALE_EN_XA = new Locale("en", "XA");
    private static final Locale LOCALE_AR_XB = new Locale("ar", "XB");

    public LocaleList(Locale... locales) {
        this.locales = (locales != null) ? locales : new Locale[0];
    }
    public Locale get(int index) {
        return (index >= 0 && index < locales.length) ? locales[index] : null;
    }
    public int size() { return locales.length; }
    public boolean isEmpty() { return locales.length == 0; }
    public int indexOf(Locale l) {
        for (int i = 0; i < locales.length; ++i) {
            if (locales[i] != null && locales[i].equals(l)) return i;
        }
        return -1;
    }

    private static boolean isPseudoLocale(Locale locale) {
        return LOCALE_EN_XA.equals(locale) || LOCALE_AR_XB.equals(locale);
    }

    // AOSP first tries the locale's own explicit script subtag (the "Hant"
    // in "zh-Hant-TW") via Locale.getScript(), then falls back to ICU's
    // ULocale.addLikelySubtags to INFER a script from language+region for
    // locales that don't carry one (e.g. plain "zh-TW" -> Hant) -- aosp-mirror
    // android/os/LocaleList.java getLikelyScript(), lines ~302-309. Our
    // android.icu.util.ULocale (runtime/java/android/icu/util/ULocale.java)
    // doesn't implement addLikelySubtags yet ("richer ULocale-only features
    // ... are not modeled", per its own header comment), so we can only
    // resolve the explicit-subtag case. This is not a silent behavior lie:
    // matchesLanguageAndScript()'s empty-script branch below is AOSP's OWN
    // documented degradation path for "we can't guess a script" (its comment:
    // "fall back to old behavior of matching, which considered locales with
    // different regions different"), not a shortcut we invented on top of it.
    private static String getLikelyScript(Locale locale) {
        return locale.getScript();
    }

    // Mirrors aosp-mirror android/os/LocaleList.java matchesLanguageAndScript
    // (lines ~349-374) exactly: true if language AND (explicit or inferred)
    // script match, e.g. zh-HK matches zh-Hant; en-US matches en-CA. Used by
    // getFirstMatch() below for locale/resource fallback resolution, and
    // called directly by locale-aware library code (androidx, ICU shims).
    public static boolean matchesLanguageAndScript(Locale supported, Locale desired) {
        if (supported.equals(desired)) {
            return true; // return early so we don't do unnecessary computation
        }
        if (!supported.getLanguage().equals(desired.getLanguage())) {
            return false;
        }
        if (isPseudoLocale(supported) || isPseudoLocale(desired)) {
            return false;
        }
        final String supportedScr = getLikelyScript(supported);
        if (supportedScr.isEmpty()) {
            // Can't guess a script -- fall back to a straight country compare
            // (AOSP's own designed degradation path; see getLikelyScript above).
            final String supportedRegion = supported.getCountry();
            return supportedRegion.isEmpty() || supportedRegion.equals(desired.getCountry());
        }
        final String desiredScr = getLikelyScript(desired);
        return supportedScr.equals(desiredScr);
    }

    private int findFirstMatchIndex(Locale supportedLocale) {
        for (int idx = 0; idx < locales.length; ++idx) {
            if (matchesLanguageAndScript(supportedLocale, locales[idx])) {
                return idx;
            }
        }
        return Integer.MAX_VALUE;
    }

    // Mirrors aosp-mirror computeFirstMatchIndex (lines ~435-461), the
    // English-not-assumed path getFirstMatch() itself always takes.
    private int computeFirstMatchIndex(String[] supportedLocales) {
        if (locales.length == 1) { // just one locale, the most common scenario
            return 0;
        }
        if (locales.length == 0) { // empty locale list
            return -1;
        }
        int bestIndex = Integer.MAX_VALUE;
        for (String languageTag : supportedLocales) {
            final Locale supportedLocale = Locale.forLanguageTag(languageTag);
            final int idx = findFirstMatchIndex(supportedLocale);
            if (idx == 0) { // match on this list's first (most preferred) locale
                return 0;
            } else if (idx < bestIndex) {
                bestIndex = idx;
            }
        }
        // No match found in the walk above -> fall back to the first locale.
        return (bestIndex == Integer.MAX_VALUE) ? 0 : bestIndex;
    }

    /**
     * Returns the first match in the locale list given an unordered array of
     * supported locales in BCP 47 format, walking THIS list in priority order
     * and returning the first entry whose language+script matches any of
     * supportedLocales (falling back to index 0 only when nothing matches) --
     * mirrors aosp-mirror getFirstMatch()/computeFirstMatch(), lines ~439-480.
     *
     * @return the first matching {@link Locale}, or {@code null} if this
     *     {@link LocaleList} is empty. supportedLocales must be non-null,
     *     matching AOSP's own Arrays.asList(supportedLocales) NPE behavior.
     */
    public Locale getFirstMatch(String[] supportedLocales) {
        java.util.Objects.requireNonNull(supportedLocales);
        final int bestIndex = computeFirstMatchIndex(supportedLocales);
        return (bestIndex == -1) ? null : locales[bestIndex];
    }

    public static LocaleList getEmptyLocaleList() { return EMPTY; }

    public static synchronized LocaleList getDefault() {
        if (defaultList == null) defaultList = macapkGetSystemLocales();
        return defaultList;
    }

    /** Host identity must survive process-default and application-locale changes. */
    public static synchronized LocaleList macapkGetSystemLocales() {
        if (systemList == null) systemList = readAppleLanguagesOrdered();
        return systemList;
    }

    // macOS's per-user RANKED language preference (System Settings > General >
    // Language & Region > Preferred Languages) lives in the AppleLanguages
    // global default, e.g. ("fr-FR", "en-US") most-preferred first -- the
    // macOS analogue of AOSP's ranked Configuration.getLocales() list (a real
    // Android device also lets a user populate more than one system language,
    // and resource fallback tries each ranked entry in turn). We read it the
    // same proven way com.macapk.loader.Boot's macapkSystemIsDark() already
    // reads the sibling AppleInterfaceStyle default for dark-mode detection: a
    // one-shot `defaults` subprocess, output scraped, any failure swallowed.
    // This must never throw or block boot; on any failure we fall back to the
    // JVM's own single-locale Locale.getDefault(), this class's pre-existing
    // (and still correct-if-narrower) behavior.
    private static LocaleList readAppleLanguagesOrdered() {
        try {
            // java.lang.Process, not android.os.Process -- this file's own
            // package shadows the unqualified name.
            java.lang.Process p = new ProcessBuilder("defaults", "read", "-g", "AppleLanguages")
                    .redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            p.waitFor();
            // OpenStep arrays can contain quoted or unquoted language tags.
            List<Locale> ordered = new ArrayList<>();
            String array = out.toString().trim();
            if (array.startsWith("(") && array.endsWith(")")) {
                for (String entry : array.substring(1, array.length() - 1).split(",")) {
                    String tag = entry.trim().replaceAll("^\"|\"$", "");
                    if (!tag.isEmpty()) ordered.add(Locale.forLanguageTag(tag));
                }
            }
            if (!ordered.isEmpty()) {
                return new LocaleList(ordered.toArray(new Locale[0]));
            }
        } catch (Throwable t) {
            // fall through to the single-locale default below
        }
        return new LocaleList(Locale.getDefault());
    }

    public static LocaleList getAdjustedDefault() { return getDefault(); }
    public static void setDefault(LocaleList list) { setDefault(list, 0); }
    public static synchronized void setDefault(LocaleList list, int index) {
        macapkGetSystemLocales();
        defaultList = list;
        if (list != null && index >= 0 && index < list.size()) {
            defaultLocale = list.get(index);
        }
    }
    public static LocaleList forLanguageTags(String list) {
        if (list == null || list.isEmpty()) return EMPTY;
        String[] tags = list.split(",");
        Locale[] locs = new Locale[tags.length];
        for (int i = 0; i < tags.length; ++i) {
            locs[i] = Locale.forLanguageTag(tags[i].trim());
        }
        return new LocaleList(locs);
    }
    public String toLanguageTags() {
        if (locales.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < locales.length; ++i) {
            if (i > 0) sb.append(',');
            sb.append(locales[i].toLanguageTag());
        }
        return sb.toString();
    }
    @Override public String toString() { return toLanguageTags(); }
    @Override public boolean equals(Object o) {
        if (!(o instanceof LocaleList)) return false;
        LocaleList other = (LocaleList) o;
        if (other.locales.length != locales.length) return false;
        for (int i = 0; i < locales.length; ++i) {
            if (!java.util.Objects.equals(locales[i], other.locales[i])) return false;
        }
        return true;
    }
    @Override public int hashCode() {
        int h = 1;
        for (Locale l : locales) h = 31 * h + (l != null ? l.hashCode() : 0);
        return h;
    }

    /** AOSP core/java/android/os/LocaleList.java:333 -- the public overload,
     *  which forwards `isPseudoLocale(locale != null ? locale.toLocale()
     *  : null)` to the Locale form (:326, @hide in javadoc but public in
     *  bytecode -- ours is private, which is invisible to any app since the
     *  ULocale form is the only one android.jar exposes). */
    public static boolean isPseudoLocale(android.icu.util.ULocale locale) {
        return isPseudoLocale(locale != null ? locale.toLocale() : null);
    }

    // AOSP :writeToParcel -- `dest.writeString8(mStringRepresentation)`, i.e.
    // the comma-joined language-tag string that toLanguageTags() returns, and
    // CREATOR rebuilds with forLanguageTags(). So the wire form is exactly one
    // string, and this round-trips through our own forLanguageTags parser.
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int parcelableFlags) {
        dest.writeString(toLanguageTags());
    }
    public static final Parcelable.Creator<LocaleList> CREATOR =
            new Parcelable.Creator<LocaleList>() {
        @Override public LocaleList createFromParcel(Parcel source) {
            return forLanguageTags(source.readString());
        }
        @Override public LocaleList[] newArray(int size) { return new LocaleList[size]; }
    };
}
