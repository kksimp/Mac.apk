// android.os.PatternMatcher -- string matcher used by IntentFilter, PathPermission,
// UriRelativeFilter, and WifiNetworkSpecifier.
//
// Implements PATTERN_LITERAL, PATTERN_PREFIX, PATTERN_SIMPLE_GLOB,
// PATTERN_ADVANCED_GLOB, and PATTERN_SUFFIX per AOSP contract:
//   frameworks/base/core/java/android/os/PatternMatcher.java (aosp-mirror main, Apache-2.0)
package android.os;

import java.util.Arrays;

public class PatternMatcher implements Parcelable {
    public static final int PATTERN_LITERAL = 0;
    public static final int PATTERN_PREFIX = 1;
    public static final int PATTERN_SIMPLE_GLOB = 2;
    public static final int PATTERN_ADVANCED_GLOB = 3;
    public static final int PATTERN_SUFFIX = 4;

    // token types for advanced matching
    private static final int TOKEN_TYPE_LITERAL = 0;
    private static final int TOKEN_TYPE_ANY = 1;
    private static final int TOKEN_TYPE_SET = 2;
    private static final int TOKEN_TYPE_INVERSE_SET = 3;

    // Return for no match
    private static final int NO_MATCH = -1;

    // Parsed placeholders for advanced patterns
    private static final int PARSED_TOKEN_CHAR_SET_START = -1;
    private static final int PARSED_TOKEN_CHAR_SET_INVERSE_START = -2;
    private static final int PARSED_TOKEN_CHAR_SET_STOP = -3;
    private static final int PARSED_TOKEN_CHAR_ANY = -4;
    private static final int PARSED_MODIFIER_RANGE_START = -5;
    private static final int PARSED_MODIFIER_RANGE_STOP = -6;
    private static final int PARSED_MODIFIER_ZERO_OR_MORE = -7;
    private static final int PARSED_MODIFIER_ONE_OR_MORE = -8;

    private static final int MAX_PATTERN_STORAGE = 2048;
    // workspace to use for building a parsed advanced pattern;
    private static final int[] sParsedPatternScratch = new int[MAX_PATTERN_STORAGE];

    private final String mPattern;
    private final int mType;
    private final int[] mParsedPattern;

    public PatternMatcher(String pattern, int type) {
        mPattern = pattern;
        mType = type;
        if (mType == PATTERN_ADVANCED_GLOB) {
            mParsedPattern = parseAndVerifyAdvancedPattern(pattern);
        } else {
            mParsedPattern = null;
        }
    }

    public PatternMatcher(Parcel src) {
        mPattern = src.readString();
        mType = src.readInt();
        int[] parsed = src.createIntArray();
        if (mType == PATTERN_ADVANCED_GLOB && parsed == null && mPattern != null) {
            parsed = parseAndVerifyAdvancedPattern(mPattern);
        }
        mParsedPattern = parsed;
    }

    public final String getPath() { return mPattern; }
    /** Non-AOSP alias for getPath(). Kept because
     *  android/net/wifi/WifiNetworkSpecifier.java calls it. */
    public final String getPattern() { return mPattern; }
    public final int getType() { return mType; }

    public boolean match(String str) {
        return matchPattern(str, mPattern, mParsedPattern, mType);
    }

    @Override public String toString() {
        String type = "? ";
        switch (mType) {
            case PATTERN_LITERAL:       type = "LITERAL: "; break;
            case PATTERN_PREFIX:        type = "PREFIX: "; break;
            case PATTERN_SIMPLE_GLOB:   type = "GLOB: "; break;
            case PATTERN_ADVANCED_GLOB: type = "ADVANCED: "; break;
            case PATTERN_SUFFIX:        type = "SUFFIX: "; break;
        }
        return "PatternMatcher{" + type + mPattern + "}";
    }

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mPattern);
        dest.writeInt(mType);
        dest.writeIntArray(mParsedPattern);
    }

    public static final Parcelable.Creator<PatternMatcher> CREATOR =
        new Parcelable.Creator<PatternMatcher>() {
            @Override public PatternMatcher createFromParcel(Parcel source) {
                return new PatternMatcher(source);
            }
            @Override public PatternMatcher[] newArray(int size) {
                return new PatternMatcher[size];
            }
        };

    static boolean matchPattern(String match, String pattern, int[] parsedPattern, int type) {
        if (match == null) return false;
        if (type == PATTERN_LITERAL) {
            return pattern.equals(match);
        } else if (type == PATTERN_PREFIX) {
            return match.startsWith(pattern);
        } else if (type == PATTERN_SUFFIX) {
            return match.endsWith(pattern);
        } else if (type == PATTERN_SIMPLE_GLOB) {
            return matchGlobPattern(match, pattern);
        } else if (type == PATTERN_ADVANCED_GLOB) {
            int[] tokens = parsedPattern != null ? parsedPattern : parseAndVerifyAdvancedPattern(pattern);
            return matchAdvancedPattern(tokens, match);
        }
        return false;
    }

    private static boolean matchGlobPattern(String match, String pattern) {
        final int NP = pattern.length();
        final int NM = match.length();
        if (NP <= 0) {
            return NM <= 0;
        }
        int ip = 0, im = 0;
        char nextChar = pattern.charAt(0);
        while ((ip < NP) && (im < NM)) {
            char c = nextChar;
            ip++;
            nextChar = (ip < NP) ? pattern.charAt(ip) : 0;
            final boolean escaped = (c == '\\');
            if (escaped) {
                c = nextChar;
                ip++;
                nextChar = (ip < NP) ? pattern.charAt(ip) : 0;
            }
            if (nextChar == '*') {
                if (!escaped && c == '.') {
                    if (ip >= (NP - 1)) {
                        return true;
                    }
                    ip++;
                    nextChar = pattern.charAt(ip);
                    if (nextChar == '\\') {
                        ip++;
                        nextChar = (ip < NP) ? pattern.charAt(ip) : 0;
                    }
                    do {
                        if (match.charAt(im) == nextChar) break;
                        im++;
                    } while (im < NM);
                    if (im == NM) return false;
                    ip++;
                    nextChar = (ip < NP) ? pattern.charAt(ip) : 0;
                    im++;
                } else {
                    while ((im < NM) && (match.charAt(im) == c)) im++;
                    ip++;
                    nextChar = (ip < NP) ? pattern.charAt(ip) : 0;
                }
            } else {
                if (c != '.' && match.charAt(im) != c) return false;
                im++;
            }
        }
        if (ip >= NP && im >= NM) {
            return true;
        }
        if ((ip == NP - 2) && (pattern.charAt(ip) == '.') && (pattern.charAt(ip + 1) == '*')) {
            return true;
        }
        return false;
    }

    /**
     * Parses the advanced pattern and returns an integer array representation of it. The integer
     * array treats each field as a character if positive and a unique token placeholder if
     * negative. This method will throw on any pattern structure violations.
     */
    synchronized static int[] parseAndVerifyAdvancedPattern(String pattern) {
        int ip = 0;
        final int LP = pattern.length();

        int it = 0;

        boolean inSet = false;
        boolean inRange = false;
        boolean inCharClass = false;

        boolean addToParsedPattern;

        while (ip < LP) {
            if (it > MAX_PATTERN_STORAGE - 3) {
                throw new IllegalArgumentException("Pattern is too large!");
            }

            char c = pattern.charAt(ip);
            addToParsedPattern = false;

            switch (c) {
                case '[':
                    if (inSet) {
                        addToParsedPattern = true; // treat as literal or char class in set
                    } else {
                        if (pattern.charAt(ip + 1) == '^') {
                            sParsedPatternScratch[it++] = PARSED_TOKEN_CHAR_SET_INVERSE_START;
                            ip++; // skip over the '^'
                        } else {
                            sParsedPatternScratch[it++] = PARSED_TOKEN_CHAR_SET_START;
                        }
                        ip++; // move to the next pattern char
                        inSet = true;
                        continue;
                    }
                    break;
                case ']':
                    if (!inSet) {
                        addToParsedPattern = true; // treat as literal outside of set
                    } else {
                        int parsedToken = sParsedPatternScratch[it - 1];
                        if (parsedToken == PARSED_TOKEN_CHAR_SET_START ||
                            parsedToken == PARSED_TOKEN_CHAR_SET_INVERSE_START) {
                            throw new IllegalArgumentException(
                                    "You must define characters in a set.");
                        }
                        sParsedPatternScratch[it++] = PARSED_TOKEN_CHAR_SET_STOP;
                        inSet = false;
                        inCharClass = false;
                    }
                    break;
                case '{':
                    if (!inSet) {
                        if (it == 0 || isParsedModifier(sParsedPatternScratch[it - 1])) {
                            throw new IllegalArgumentException("Modifier must follow a token.");
                        }
                        sParsedPatternScratch[it++] = PARSED_MODIFIER_RANGE_START;
                        ip++;
                        inRange = true;
                    }
                    break;
                case '}':
                    if (inRange) { // only terminate the range if we're currently in one
                        sParsedPatternScratch[it++] = PARSED_MODIFIER_RANGE_STOP;
                        inRange = false;
                    }
                    break;
                case '*':
                    if (!inSet) {
                        if (it == 0 || isParsedModifier(sParsedPatternScratch[it - 1])) {
                            throw new IllegalArgumentException("Modifier must follow a token.");
                        }
                        sParsedPatternScratch[it++] = PARSED_MODIFIER_ZERO_OR_MORE;
                    }
                    break;
                case '+':
                    if (!inSet) {
                        if (it == 0 || isParsedModifier(sParsedPatternScratch[it - 1])) {
                            throw new IllegalArgumentException("Modifier must follow a token.");
                        }
                        sParsedPatternScratch[it++] = PARSED_MODIFIER_ONE_OR_MORE;
                    }
                    break;
                case '.':
                    if (!inSet) {
                        sParsedPatternScratch[it++] = PARSED_TOKEN_CHAR_ANY;
                    }
                    break;
                case '\\': // escape
                    if (ip + 1 >= LP) {
                        throw new IllegalArgumentException("Escape found at end of pattern!");
                    }
                    c = pattern.charAt(++ip);
                    addToParsedPattern = true;
                    break;
                default:
                    addToParsedPattern = true;
                    break;
            }
            if (inSet) {
                if (inCharClass) {
                    sParsedPatternScratch[it++] = c;
                    inCharClass = false;
                } else {
                    // look forward for character class
                    if (ip + 2 < LP
                            && pattern.charAt(ip + 1) == '-'
                            && pattern.charAt(ip + 2) != ']') {
                        inCharClass = true;
                        sParsedPatternScratch[it++] = c; // set first token as lower end of range
                        ip++; // advance past dash
                    } else { // literal
                        sParsedPatternScratch[it++] = c; // set first token as literal
                        sParsedPatternScratch[it++] = c; // set second set as literal
                    }
                }
            } else if (inRange) {
                int endOfSet = pattern.indexOf('}', ip);
                if (endOfSet < 0) {
                    throw new IllegalArgumentException("Range not ended with '}'");
                }
                String rangeString = pattern.substring(ip, endOfSet);
                int commaIndex = rangeString.indexOf(',');
                try {
                    final int rangeMin;
                    final int rangeMax;
                    if (commaIndex < 0) {
                        int parsedRange = Integer.parseInt(rangeString);
                        rangeMin = rangeMax = parsedRange;
                    } else {
                        rangeMin = Integer.parseInt(rangeString.substring(0, commaIndex));
                        if (commaIndex == rangeString.length() - 1) { // e.g. {n,} (n or more)
                            rangeMax = Integer.MAX_VALUE;
                        } else {
                            rangeMax = Integer.parseInt(rangeString.substring(commaIndex + 1));
                        }
                    }
                    if (rangeMin > rangeMax) {
                        throw new IllegalArgumentException(
                            "Range quantifier minimum is greater than maximum");
                    }
                    sParsedPatternScratch[it++] = rangeMin;
                    sParsedPatternScratch[it++] = rangeMax;
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Range number format incorrect", e);
                }
                ip = endOfSet;
                continue; // don't increment ip
            } else if (addToParsedPattern) {
                sParsedPatternScratch[it++] = c;
            }
            ip++;
        }
        if (inSet) {
            throw new IllegalArgumentException("Set was not terminated!");
        }
        return Arrays.copyOf(sParsedPatternScratch, it);
    }

    private static boolean isParsedModifier(int parsedChar) {
        return parsedChar == PARSED_MODIFIER_ONE_OR_MORE ||
                parsedChar == PARSED_MODIFIER_ZERO_OR_MORE ||
                parsedChar == PARSED_MODIFIER_RANGE_STOP ||
                parsedChar == PARSED_MODIFIER_RANGE_START;
    }

    static boolean matchAdvancedPattern(int[] parsedPattern, String match) {

        // create indexes
        int ip = 0, im = 0;

        // one-time length check
        final int LP = parsedPattern.length, LM = match.length();

        // The current character being analyzed in the pattern
        int patternChar;

        int tokenType;

        int charSetStart = 0, charSetEnd = 0;

        while (ip < LP) { // we still have content in the pattern

            patternChar = parsedPattern[ip];
            // get the match type of the next verb

            switch (patternChar) {
                case PARSED_TOKEN_CHAR_ANY:
                    tokenType = TOKEN_TYPE_ANY;
                    ip++;
                    break;
                case PARSED_TOKEN_CHAR_SET_START:
                case PARSED_TOKEN_CHAR_SET_INVERSE_START:
                    tokenType = patternChar == PARSED_TOKEN_CHAR_SET_START
                            ? TOKEN_TYPE_SET
                            : TOKEN_TYPE_INVERSE_SET;
                    charSetStart = ip + 1; // start from the char after the set start
                    while (++ip < LP && parsedPattern[ip] != PARSED_TOKEN_CHAR_SET_STOP);
                    charSetEnd = ip - 1; // we're on the set stop, end is the previous
                    ip++; // move the pointer to the next pattern entry
                    break;
                default:
                    charSetStart = ip;
                    tokenType = TOKEN_TYPE_LITERAL;
                    ip++;
                    break;
            }

            final int minRepetition;
            final int maxRepetition;

            // look for a match length modifier
            if (ip >= LP) {
                minRepetition = maxRepetition = 1;
            } else {
                patternChar = parsedPattern[ip];
                switch (patternChar) {
                    case PARSED_MODIFIER_ZERO_OR_MORE:
                        minRepetition = 0;
                        maxRepetition = Integer.MAX_VALUE;
                        ip++;
                        break;
                    case PARSED_MODIFIER_ONE_OR_MORE:
                        minRepetition = 1;
                        maxRepetition = Integer.MAX_VALUE;
                        ip++;
                        break;
                    case PARSED_MODIFIER_RANGE_START:
                        minRepetition = parsedPattern[++ip];
                        maxRepetition = parsedPattern[++ip];
                        ip += 2; // step over PARSED_MODIFIER_RANGE_STOP and on to the next token
                        break;
                    default:
                        minRepetition = maxRepetition = 1; // implied literal
                        break;
                }
            }
            if (minRepetition > maxRepetition) {
                return false;
            }

            // attempt to match as many characters as possible
            int matched = matchChars(match, im, LM, tokenType, minRepetition, maxRepetition,
                    parsedPattern, charSetStart, charSetEnd);

            // if we found a conflict, return false immediately
            if (matched == NO_MATCH) {
                return false;
            }

            // move the match pointer the number of characters matched
            im += matched;
        }
        return ip >= LP && im >= LM; // have parsed entire string and regex
    }

    private static int matchChars(String match, int im, final int lm, int tokenType,
            int minRepetition, int maxRepetition, int[] parsedPattern,
            int tokenStart, int tokenEnd) {
        int matched = 0;

        while(matched < maxRepetition
                && matchChar(match, im + matched, lm, tokenType, parsedPattern, tokenStart,
                    tokenEnd)) {
            matched++;
        }

        return matched < minRepetition ? NO_MATCH : matched;
    }

    private static boolean matchChar(String match, int im, final int lm, int tokenType,
            int[] parsedPattern, int tokenStart, int tokenEnd) {
        if (im >= lm) { // we've overrun the string, no match
            return false;
        }
        switch (tokenType) {
            case TOKEN_TYPE_ANY:
                return true;
            case TOKEN_TYPE_SET:
                for (int i = tokenStart; i < tokenEnd; i += 2) {
                    char matchChar = match.charAt(im);
                    if (matchChar >= parsedPattern[i] && matchChar <= parsedPattern[i + 1]) {
                        return true;
                    }
                }
                return false;
            case TOKEN_TYPE_INVERSE_SET:
                for (int i = tokenStart; i < tokenEnd; i += 2) {
                    char matchChar = match.charAt(im);
                    if (matchChar >= parsedPattern[i] && matchChar <= parsedPattern[i + 1]) {
                        return false;
                    }
                }
                return true;
            case TOKEN_TYPE_LITERAL:
                return match.charAt(im) == parsedPattern[tokenStart];
            default:
                return false;
        }
    }
}