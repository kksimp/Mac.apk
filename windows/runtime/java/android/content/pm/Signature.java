package android.content.pm;
// A signing certificate (DER). Win.apk reports an APK's only after its
// signature has verified (com.macapk.loader.ApkVerification).
public class Signature implements android.os.Parcelable {
    private final byte[] bytes;
    public Signature(byte[] signatureBytes) {
        this.bytes = signatureBytes != null ? signatureBytes : new byte[0];
    }
    /** AOSP Signature(String text)
     *  (frameworks/base/core/java/android/content/pm/Signature.java, android16-release):
     *  the argument is the HEX TEXT of the certificate, i.e. the inverse of
     *  toCharsString(), NOT raw characters. Upstream parses two hex digits per
     *  byte and throws IllegalArgumentException on an odd length or a non-hex
     *  character.
     *
     *  This used to be `hexString.getBytes()`, which produced a byte array
     *  TWICE the correct length holding ASCII digits. Anything that round-trips
     *  a signature through text -- the very common
     *  `new Signature(BuildConfig.EXPECTED_SIG).equals(pkgInfo.signatures[0])`
     *  anti-tamper check, or a certificate pinned in a resource -- therefore
     *  compared unequal against the real certificate no matter what, and
     *  toByteArray() on such an object handed the caller hex text where it
     *  expected DER. */
    public Signature(String hexString) {
        if (hexString == null) { this.bytes = new byte[0]; return; }
        final byte[] input = hexString.getBytes();
        final int n = input.length;
        if (n % 2 != 0) {
            throw new IllegalArgumentException("text size " + n + " is not even");
        }
        final byte[] sig = new byte[n / 2];
        int sigIndex = 0;
        for (int i = 0; i < n;) {
            final int hi = parseHexDigit(input[i++]);
            final int lo = parseHexDigit(input[i++]);
            sig[sigIndex++] = (byte) ((hi << 4) | lo);
        }
        this.bytes = sig;
    }

    /** AOSP Signature.parseHexDigit -- rejects anything that is not [0-9a-fA-F]. */
    private static int parseHexDigit(int nibble) {
        if ('0' <= nibble && nibble <= '9') return nibble - '0';
        if ('a' <= nibble && nibble <= 'f') return nibble - 'a' + 10;
        if ('A' <= nibble && nibble <= 'F') return nibble - 'A' + 10;
        throw new IllegalArgumentException("Invalid character " + nibble + " in hex string");
    }

    public byte[] toByteArray() { return bytes; }

    /** AOSP Signature.mFlags / getFlags / setFlags (@hide): the capabilities a
     *  PAST signer in a v3 rotation lineage keeps (SigningDetails
     *  .CertCapabilities). equals() ignores them, as upstream's does. */
    private int flags;
    public int getFlags() { return flags; }
    public void setFlags(int flags) { this.flags = flags; }
    public char[] toChars() { return toChars(null, null); }

    /** AOSP Signature.toChars(char[] existingArray, int[] outLen): the
     *  allocation-reusing form of toChars(). Upstream reuses `existingArray`
     *  only when it is at least 2*len long, otherwise allocates.
     *
     *  ⚠ outLen[0] is set to the BYTE count (mSignature.length), not the
     *  character count -- that is what AOSP does (Signature.java:151, "if
     *  (outLen != null) outLen[0] = N;" where N is sig.length), and a caller
     *  written against a device depends on the real thing, not on the value
     *  that looks more sensible. */
    public char[] toChars(char[] existingArray, int[] outLen) {
        final int n = bytes.length;
        final int n2 = n * 2;
        char[] text = (existingArray == null || n2 > existingArray.length)
                ? new char[n2] : existingArray;
        for (int j = 0; j < n; j++) {
            byte v = bytes[j];
            int d = (v >> 4) & 0xf;
            text[j * 2] = (char) (d >= 10 ? ('a' + d - 10) : ('0' + d));
            d = v & 0xf;
            text[j * 2 + 1] = (char) (d >= 10 ? ('a' + d - 10) : ('0' + d));
        }
        if (outLen != null) outLen[0] = n;
        return text;
    }

    /** AOSP: `return new String(toChars())` -- note toChars() always returns an
     *  exactly-sized array, so no length trim is needed here. */
    public String toCharsString() { return new String(toChars()); }
    @Override public int hashCode()         { return java.util.Arrays.hashCode(bytes); }
    @Override public boolean equals(Object o) {
        return o instanceof Signature && java.util.Arrays.equals(bytes, ((Signature) o).bytes);
    }

    // ---- Parcelable --------------------------------------------------------
    // AOSP: `Signature implements Parcelable` (android11-release
    // Signature.java:41), writeToParcel at :276-278 and the (Parcel)
    // constructor at :291-293. PackageInfo.signatures is a Signature[] AOSP
    // flattens with writeTypedArray, so this pair is what makes that array
    // marshallable.
    @Override
    public int describeContents() { return 0; }

    @Override
    public void writeToParcel(android.os.Parcel dest, int parcelableFlags) {
        dest.writeByteArray(bytes);
    }

    /** AOSP Signature.java:291-293. `bytes` is final here (AOSP's mSignature is
     *  not), which is exactly why the read has to happen in a constructor
     *  rather than in the CREATOR body. A parcel that carried a null array
     *  becomes the empty array, matching what both public constructors do with
     *  a null argument -- toByteArray()'s callers never null-check. */
    private Signature(android.os.Parcel source) {
        byte[] read = source.createByteArray();
        this.bytes = read != null ? read : new byte[0];
    }

    public static final android.os.Parcelable.Creator<Signature> CREATOR =
        new android.os.Parcelable.Creator<Signature>() {
            @Override public Signature createFromParcel(android.os.Parcel source) {
                return new Signature(source);
            }
            @Override public Signature[] newArray(int size) {
                return new Signature[size];
            }
        };
}
