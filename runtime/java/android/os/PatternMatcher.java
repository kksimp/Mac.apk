// android.os.PatternMatcher -- string matcher used by IntentFilter, PathPermission,
// UriRelativeFilter, and WifiNetworkSpecifier.
//
// Implements PATTERN_LITERAL, PATTERN_PREFIX, PATTERN_SIMPLE_GLOB,
// PATTERN_ADVANCED_GLOB, and PATTERN_SUFFIX per AOSP contract:
//   frameworks/base/core/java/android/os/PatternMatcher.java (android-16.0.0_r2, Apache-2.0)
package android.os;

import java.util.ArrayList;
import java.util.List;

public class PatternMatcher implements Parcelable {
    public static final int PATTERN_LITERAL = 0;
    public static final int PATTERN_PREFIX = 1;
    public static final int PATTERN_SIMPLE_GLOB = 2;
    public static final int PATTERN_ADVANCED_GLOB = 3;
    public static final int PATTERN_SUFFIX = 4;

    private static final int TOKEN_END = 0;
    private static final int TOKEN_ANY = 1;
    private static final int TOKEN_CHAR = 2;
    private static final int TOKEN_SET = 3;
    private static final int TOKEN_NOT_SET = 4;

    private static final int OP_LITERAL = 0;
    private static final int OP_STAR = 1;
    private static final int OP_PLUS = 2;
    private static final int OP_RANGE = 3;

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
                if ((escaped || c != '.') && match.charAt(im) != c) return false;
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

    static int[] parseAndVerifyAdvancedPattern(String pattern) {
        if (pattern == null) {
            throw new IllegalArgumentException("Pattern cannot be null");
        }
        List<Integer> out = new ArrayList<>();
        int i = 0;
        final int n = pattern.length();
        while (i < n) {
            int kind;
            int singleChar = 0;
            List<Integer> ranges = null;
            char c = pattern.charAt(i);
            if (c == '*' || c == '+' || c == '{') {
                throw new IllegalArgumentException("Dangling quantifier '" + c + "' at index " + i);
            } else if (c == '.') {
                kind = TOKEN_ANY;
                i++;
            } else if (c == '[') {
                i++;
                boolean negate = false;
                if (i < n && pattern.charAt(i) == '^') {
                    negate = true;
                    i++;
                }
                kind = negate ? TOKEN_NOT_SET : TOKEN_SET;
                ranges = new ArrayList<>();
                boolean closed = false;
                while (i < n) {
                    char sc = pattern.charAt(i++);
                    if (sc == ']') {
                        closed = true;
                        break;
                    }
                    if (sc == '\\') {
                        if (i >= n) {
                            throw new IllegalArgumentException("Trailing escape inside character class");
                        }
                        sc = pattern.charAt(i++);
                    }
                    if (i + 1 < n && pattern.charAt(i) == '-' && pattern.charAt(i + 1) != ']') {
                        i++; // consume '-'
                        char ec = pattern.charAt(i++);
                        if (ec == '\\') {
                            if (i >= n) {
                                throw new IllegalArgumentException("Trailing escape in character range");
                            }
                            ec = pattern.charAt(i++);
                        }
                        if (ec < sc) {
                            throw new IllegalArgumentException("Invalid character range " + sc + "-" + ec);
                        }
                        ranges.add((int) sc);
                        ranges.add((int) ec);
                    } else {
                        ranges.add((int) sc);
                        ranges.add((int) sc);
                    }
                }
                if (!closed) {
                    throw new IllegalArgumentException("Unclosed character class '[' in pattern: " + pattern);
                }
                if (ranges.isEmpty()) {
                    throw new IllegalArgumentException("Empty character class '[]' in pattern: " + pattern);
                }
            } else {
                if (c == '\\') {
                    i++;
                    if (i >= n) {
                        throw new IllegalArgumentException("Trailing escape at end of pattern: " + pattern);
                    }
                    c = pattern.charAt(i);
                }
                kind = TOKEN_CHAR;
                singleChar = c;
                i++;
            }

            int op = OP_LITERAL;
            int min = 1;
            int max = 1;
            if (i < n) {
                char q = pattern.charAt(i);
                if (q == '*') {
                    op = OP_STAR;
                    min = 0;
                    max = Integer.MAX_VALUE;
                    i++;
                } else if (q == '+') {
                    op = OP_PLUS;
                    min = 1;
                    max = Integer.MAX_VALUE;
                    i++;
                } else if (q == '{') {
                    int close = pattern.indexOf('}', i + 1);
                    if (close < 0) {
                        throw new IllegalArgumentException("Unclosed quantifier '{' in pattern: " + pattern);
                    }
                    String body = pattern.substring(i + 1, close);
                    if (body.isEmpty()) {
                        throw new IllegalArgumentException("Empty quantifier '{}' in pattern: " + pattern);
                    }
                    int comma = body.indexOf(',');
                    try {
                        if (comma < 0) {
                            min = Integer.parseInt(body);
                            max = min;
                        } else {
                            min = Integer.parseInt(body.substring(0, comma));
                            String maxStr = body.substring(comma + 1);
                            max = maxStr.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(maxStr);
                        }
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Invalid quantifier '{" + body + "}'", e);
                    }
                    if (min < 0 || max < min) {
                        throw new IllegalArgumentException("Invalid quantifier bounds {" + min + "," + max + "}");
                    }
                    op = OP_RANGE;
                    i = close + 1;
                }
            }

            out.add(kind);
            out.add(op);
            out.add(min);
            out.add(max);
            if (kind == TOKEN_CHAR) {
                out.add(singleChar);
            } else if (kind == TOKEN_SET || kind == TOKEN_NOT_SET) {
                out.add(ranges.size());
                out.addAll(ranges);
            }
        }
        out.add(TOKEN_END);
        int[] arr = new int[out.size()];
        for (int k = 0; k < out.size(); k++) {
            arr[k] = out.get(k);
        }
        return arr;
    }

    private static boolean matchAdvancedPattern(int[] tok, String s) {
        if (s == null || tok == null) return false;
        return matchAdvancedAt(tok, 0, s, 0);
    }

    private static boolean charMatches(int[] tok, int dataPos, int kind, char ch) {
        if (kind == TOKEN_ANY) return true;
        if (kind == TOKEN_CHAR) return ch == (char) tok[dataPos];
        int rLen = tok[dataPos];
        boolean hit = false;
        for (int j = 0; j < rLen; j += 2) {
            char lo = (char) tok[dataPos + 1 + j];
            char hi = (char) tok[dataPos + 2 + j];
            if (ch >= lo && ch <= hi) {
                hit = true;
                break;
            }
        }
        return kind == TOKEN_SET ? hit : !hit;
    }

    private static int tokenSize(int[] tok, int ti) {
        int kind = tok[ti];
        if (kind == TOKEN_END) return 1;
        if (kind == TOKEN_ANY) return 4;
        if (kind == TOKEN_CHAR) return 5;
        return 5 + tok[ti + 4];
    }

    private static boolean matchAdvancedAt(int[] tok, int ti, String s, int si) {
        int kind = tok[ti];
        if (kind == TOKEN_END) return si == s.length();
        int min = tok[ti + 2];
        int max = tok[ti + 3];
        int nextTi = ti + tokenSize(tok, ti);

        int matched = 0;
        while (matched < max && si + matched < s.length()
                && charMatches(tok, ti + 4, kind, s.charAt(si + matched))) {
            matched++;
        }
        for (int count = matched; count >= min; count--) {
            if (matchAdvancedAt(tok, nextTi, s, si + count)) {
                return true;
            }
        }
        return false;
    }
}
