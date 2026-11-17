// com.macapk.gms.SafeParcel -- Google Play services' SafeParcelable wire format,
// both directions, on android.os.Parcel.
//
// WHY THIS EXISTS. Every object GMS hands an app across the process boundary
// (GoogleSignInAccount, SignInAccount, Scope, ...) travels as a SafeParcel: the
// app's OWN bundled play-services classes read it back through their CREATOR.
// Mac.apk's GMS stand-in runs in the app's process, but the app still owns those
// classes (the GMS family is child-first; docs/gms-device-oracle-parent-first_
// 2026-08-01.md) and still casts the result to its own class. So we do what the
// real GMS process does: WRITE the wire bytes, and let the app's CREATOR build
// its own object. The wire is the stable contract between a newer GMS and an
// older client library -- field ids never move -- so this is version-robust in a
// way that poking renamed fields reflectively could never be.
//
// THE FORMAT, read from the app's own SafeParcelWriter (Reddit 2026.21.0,
// class yo00 -- javap, 2026-09-30; method names are R8-renamed, the bytecode is
// the authority):
//   object   : writeInt(0xFFFF0000 | 20293); writeInt(<size, back-patched>)
//              (x0 begins with id 20293 = 0x4F45; y0 seeks to start-4 and writes
//              end-start)
//   field    : fixed-size  -> writeInt((size << 16) | id) then the value (w0)
//              variable    -> writeInt(0xFFFF0000 | id); writeInt(<size>); value
//   string   : variable field holding Parcel.writeString            (s0)
//   parcelable: variable field holding value.writeToParcel(p, flags)  (r0)
//   typed list: variable field: writeInt(n); per item writeInt(<item size>) then
//              item.writeToParcel, a 0 size meaning a null item       (v0)
// A field header whose top 16 bits are 0xFFFF carries its size in the next int.
package com.macapk.gms;

import android.os.Parcel;

import java.util.LinkedHashMap;
import java.util.Map;

public final class SafeParcel {

    /** SafeParcelWriter.beginObjectHeader's id (0x4F45). */
    public static final int OBJECT_HEADER = 20293;

    private SafeParcel() {}

    // ---------------------------------------------------------------- writing

    /** Begin an object; returns the position finishObject back-patches. */
    public static int beginObject(Parcel p) { return beginVariable(p, OBJECT_HEADER); }

    public static void finishObject(Parcel p, int start) { finishVariable(p, start); }

    static int beginVariable(Parcel p, int id) {
        p.writeInt(0xFFFF0000 | id);
        p.writeInt(0);
        return p.dataPosition();
    }

    static void finishVariable(Parcel p, int start) {
        int end = p.dataPosition();
        p.setDataPosition(start - 4);
        p.writeInt(end - start);
        p.setDataPosition(end);
    }

    private static void fixedHeader(Parcel p, int id, int size) {
        if (size >= 0xFFFF) { p.writeInt(0xFFFF0000 | id); p.writeInt(size); }
        else p.writeInt((size << 16) | id);
    }

    public static void writeInt(Parcel p, int id, int v) { fixedHeader(p, id, 4); p.writeInt(v); }

    public static void writeBoolean(Parcel p, int id, boolean v) { fixedHeader(p, id, 4); p.writeInt(v ? 1 : 0); }

    public static void writeLong(Parcel p, int id, long v) { fixedHeader(p, id, 8); p.writeLong(v); }

    /** A null string is OMITTED (writeNull=false, which is what every field we
     *  write uses in the app's own writer); the reader's default stands. */
    public static void writeString(Parcel p, int id, String v) {
        if (v == null) return;
        int s = beginVariable(p, id);
        p.writeString(v);
        finishVariable(p, s);
    }

    /** A nested SafeParcelable: the body writes one complete object. */
    public interface Body { void write(Parcel p); }

    public static void writeObject(Parcel p, int id, Body body) {
        if (body == null) return;
        int s = beginVariable(p, id);
        body.write(p);
        finishVariable(p, s);
    }

    /** A typed list of SafeParcelables (never null here: an empty list is
     *  written as n=0, which the reader turns into an empty list). */
    public static void writeTypedList(Parcel p, int id, java.util.List<Body> items) {
        int s = beginVariable(p, id);
        p.writeInt(items.size());
        for (Body b : items) {
            if (b == null) { p.writeInt(0); continue; }
            int sizePos = p.dataPosition();
            p.writeInt(1);
            int start = p.dataPosition();
            b.write(p);
            int end = p.dataPosition();
            p.setDataPosition(sizePos);
            p.writeInt(end - start);
            p.setDataPosition(end);
        }
        finishVariable(p, s);
    }

    // ---------------------------------------------------------------- reading

    /** One field: its id and the [start, start+size) of its payload. */
    public static final class Field {
        public final int id, start, size;
        Field(int id, int start, int size) { this.id = id; this.start = start; this.size = size; }
    }

    /**
     * Read one object starting at the parcel's current position. Returns its
     * fields by id (last occurrence wins, as the reader's switch does) and
     * leaves the position at the object's end.
     *
     * @throws IllegalArgumentException when the header is not 20293 -- the
     *         reader's "Expected object header" (SafeParcelReader.ParseException).
     */
    public static Map<Integer, Field> readObject(Parcel p) {
        int header = p.readInt();
        int size = sizeOf(p, header);
        int start = p.dataPosition();
        if ((header & 0xFFFF) != OBJECT_HEADER) {
            throw new IllegalArgumentException("Expected object header. Got 0x"
                    + Integer.toHexString(header));
        }
        int end = start + size;
        Map<Integer, Field> out = new LinkedHashMap<>();
        while (p.dataPosition() < end) {
            int fh = p.readInt();
            int fs = sizeOf(p, fh);
            int fstart = p.dataPosition();
            out.put(fh & 0xFFFF, new Field(fh & 0xFFFF, fstart, fs));
            p.setDataPosition(fstart + fs);
        }
        p.setDataPosition(end);
        return out;
    }

    private static int sizeOf(Parcel p, int header) {
        return ((header & 0xFFFF0000) != 0xFFFF0000) ? (header >>> 16) : p.readInt();
    }

    public static String readString(Parcel p, Map<Integer, Field> f, int id) {
        Field x = f.get(id);
        if (x == null || x.size == 0) return null;
        int save = p.dataPosition();
        p.setDataPosition(x.start);
        String s = p.readString();
        p.setDataPosition(save);
        return s;
    }

    public static boolean readBoolean(Parcel p, Map<Integer, Field> f, int id, boolean def) {
        Field x = f.get(id);
        if (x == null) return def;
        int save = p.dataPosition();
        p.setDataPosition(x.start);
        int v = p.readInt();
        p.setDataPosition(save);
        return v != 0;
    }

    public static int readInt(Parcel p, Map<Integer, Field> f, int id, int def) {
        Field x = f.get(id);
        if (x == null) return def;
        int save = p.dataPosition();
        p.setDataPosition(x.start);
        int v = p.readInt();
        p.setDataPosition(save);
        return v;
    }

    public static long readLong(Parcel p, Map<Integer, Field> f, int id, long def) {
        Field x = f.get(id);
        if (x == null) return def;
        int save = p.dataPosition();
        p.setDataPosition(x.start);
        long v = p.readLong();
        p.setDataPosition(save);
        return v;
    }

    /** A nested object field, parsed. Null when absent. */
    public static Map<Integer, Field> readNestedObject(Parcel p, Map<Integer, Field> f, int id) {
        Field x = f.get(id);
        if (x == null || x.size == 0) return null;
        int save = p.dataPosition();
        p.setDataPosition(x.start);
        Map<Integer, Field> o = readObject(p);
        p.setDataPosition(save);
        return o;
    }

    /** A typed list field, each non-null item parsed as an object. */
    public static java.util.List<Map<Integer, Field>> readTypedList(Parcel p,
            Map<Integer, Field> f, int id) {
        java.util.List<Map<Integer, Field>> out = new java.util.ArrayList<>();
        Field x = f.get(id);
        if (x == null || x.size == 0) return out;
        int save = p.dataPosition();
        p.setDataPosition(x.start);
        int n = p.readInt();
        for (int i = 0; i < n && p.dataPosition() < x.start + x.size; i++) {
            int itemSize = p.readInt();
            if (itemSize == 0) { out.add(null); continue; }
            int itemStart = p.dataPosition();
            out.add(readObject(p));
            p.setDataPosition(itemStart + itemSize);
        }
        p.setDataPosition(save);
        return out;
    }
}
