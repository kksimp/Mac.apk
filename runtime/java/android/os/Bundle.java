// android.os.Bundle -- typed key/value container.
//
// Real Android backs this with Parcel for marshalling across IPC; Mac.apk
// has no IPC, so we just back it with a HashMap inherited from BaseBundle.
// Most apps treat Bundle as opaque state shuttled between Activity
// lifecycle calls. This impl covers the common get/put surface without
// serialization.
//
// Inheritance: Bundle extends BaseBundle (since API 21 on AOSP). The
// scalar / primitive-array / String accessors live on BaseBundle so
// that bytecode emitting `invokevirtual android/os/BaseBundle.<m>`
// resolves -- see BaseBundle.java for the rationale. Bundle adds the
// methods AOSP keeps here: byte/char/short/float scalars (and arrays),
// CharSequence, Serializable, recursive Bundle, plus ClassLoader hooks
// and Parcelable-shaped helpers we no-op.
package android.os;

public final class Bundle extends BaseBundle implements Parcelable, Cloneable {
    /** AOSP's shared IMMUTABLE empty Bundle (android.os.Bundle.EMPTY). Many
     *  apps reference it as a no-args default -- e.g. Reddit's
     *  IntroductionActivity (welcome/login) reads Bundle.EMPTY in onCreate;
     *  without the field its onCreate throws NoSuchFieldError and the Compose
     *  login UI never sets up.
     *
     *  The immutability is load-bearing, not decoration. AOSP swaps the backing
     *  store for ArrayMap.EMPTY (Bundle.java:57-58), whose first allocation
     *  attempt throws UnsupportedOperationException("ArrayMap is immutable")
     *  (ArrayMap.java:216-218). A mutable EMPTY is a process-wide singleton that
     *  any app or library can permanently poison with one stray put -- every
     *  later reader of the shared empty Bundle then sees that key. Being a
     *  no-op-shaped bug that only shows up in unrelated code much later, it is
     *  precisely the kind that never gets traced back. Collections.emptyMap()
     *  reproduces ArrayMap.EMPTY's observable pair exactly: put throws
     *  UnsupportedOperationException, remove of an absent key does not. */
    public static final Bundle EMPTY = new Bundle();
    static {
        EMPTY.map = java.util.Collections.<String, Object>emptyMap();
    }

    public Bundle() { super(); }
    public Bundle(int capacity) { super(capacity); }
    public Bundle(Bundle src) { super(src); }
    public Bundle(ClassLoader loader) { super(loader); }
    /** AOSP's package-private Bundle(Parcel, int): take the slice, defer the
     *  unparcel. Parcel.readBundle is the only caller. */
    Bundle(Parcel parcelledData, int length) { super(parcelledData, length); }

    /** Bundle-scoped putAll: merges another Bundle's entries. The
     *  AOSP BaseBundle.putAll takes PersistableBundle; Bundle adds an
     *  overload taking another Bundle. Both forms route through the
     *  same map field inherited from BaseBundle. */
    public void putAll(Bundle b) {
        unparcel();
        if (b != null) { b.unparcel(); map.putAll(b.map); }
    }

    /** AOSP Bundle.clone(): a shallow copy via the copy constructor. Fragment
     *  argument handling and NavController copy Bundles via clone() before
     *  stashing them in the back stack; a missing clone() threw NoSuchMethodError
     *  at that call site. v1.0.425. */
    @Override public Object clone() { return new Bundle(this); }

    /** AOSP Bundle.readFromParcel(Parcel): re-read this Bundle's contents from a
     *  Parcel. Custom Parcelable SavedState classes (Preference.SavedState, app
     *  SavedState inner classes) call super-state.readFromParcel during their
     *  CREATOR.createFromParcel; a missing method = link-time failure on those
     *  classes. This REPLACES the contents -- a key that was here before is gone
     *  afterwards -- which falls out of initializeFromParcelLocked's erase(). The
     *  old body MERGED instead, because under the alias model the parcel handed
     *  back the very bundle it had been given and there was nothing to copy. */
    public void readFromParcel(Parcel parcel) {
        if (parcel == null) return;
        readFromParcelInner(parcel);
    }

    // Scalars AOSP keeps on Bundle (not BaseBundle): byte/char/short/float.
    public void putByte(String k, byte v)         { unparcel(); map.put(k, v); }
    public byte getByte(String k)                 { return getByte(k, (byte) 0); }
    // AOSP quirk (frameworks/base/core/java/android/os/Bundle.java): unlike every
    // other 2-arg scalar getter (getChar/getShort/getFloat all stay primitive),
    // getByte(String, byte) returns a BOXED Byte, not a primitive byte. A guest
    // compiled against real AOSP emits invokevirtual expecting descriptor
    // (Ljava/lang/String;B)Ljava/lang/Byte; -- a primitive-return `getByte` here
    // is a different descriptor and never binds (NoSuchMethodError), not a wrong
    // value returned. Confirmed via debug/framework_lint.py's return-type-diff
    // finding, cross-checked against the AOSP source above.
    public Byte getByte(String k, byte def) {
        unparcel();
        Object v = map.get(k); return (v instanceof Byte) ? (Byte) v : def;
    }
    public void putChar(String k, char v)         { unparcel(); map.put(k, v); }
    public char getChar(String k)                 { return getChar(k, '\0'); }
    public char getChar(String k, char def) {
        unparcel();
        Object v = map.get(k); return (v instanceof Character) ? (Character) v : def;
    }
    public void putShort(String k, short v)       { unparcel(); map.put(k, v); }
    public short getShort(String k)               { return getShort(k, (short) 0); }
    public short getShort(String k, short def) {
        unparcel();
        Object v = map.get(k); return (v instanceof Short) ? (Short) v : def;
    }
    public void putFloat(String k, float v)       { unparcel(); map.put(k, v); }
    public float getFloat(String k)               { return getFloat(k, 0f); }
    public float getFloat(String k, float def) {
        unparcel();
        Object v = map.get(k); return (v instanceof Float) ? (Float) v : def;
    }

    public void putCharSequence(String k, CharSequence v)   { unparcel(); map.put(k, v); }
    public CharSequence getCharSequence(String k)           {
        unparcel();
        Object v = map.get(k); return (v instanceof CharSequence) ? (CharSequence) v : null;
    }
    /** AOSP core/java/android/os/BaseBundle.java: getCharSequence(key, defaultValue)
     *  returns the default only when the typed lookup yields null. */
    public CharSequence getCharSequence(String k, CharSequence def) {
        CharSequence cs = getCharSequence(k);
        return (cs == null) ? def : cs;
    }
    public void putSerializable(String k, java.io.Serializable v) { unparcel(); map.put(k, v); }
    public java.io.Serializable getSerializable(String k) {
        unparcel();
        Object v = map.get(k);
        return (v instanceof java.io.Serializable) ? (java.io.Serializable) v : null;
    }
    public void putBundle(String k, Bundle v)   { unparcel(); map.put(k, v); }
    public Bundle getBundle(String k)           {
        unparcel();
        Object v = map.get(k); return (v instanceof Bundle) ? (Bundle) v : null;
    }

    /** Parcelable storage. F-Droid + many SDKs use Bundle as a
     *  Parcelable carrier for cross-Activity state passing.
     *  putParcelable accepts the object; getParcelable returns it.
     *  No actual parcel serialization happens (it's a single-VM
     *  in-memory pass) -- AOSP behaves identically when the source
     *  and sink are in the same process. */
    public void putParcelable(String k, android.os.Parcelable v) { unparcel(); map.put(k, v); }
    // IBinder is carried as a live object reference in our single-VM model
    // (no real binder transport). putIBinder/getIBinder (API 18+) let AIDL /
    // Messenger-bundle code stash a callback binder in a Bundle and read it back.
    public void putBinder(String k, android.os.IBinder v) { unparcel(); map.put(k, v); }
    public android.os.IBinder getBinder(String k) {
        unparcel();
        Object v = map.get(k);
        return (v instanceof android.os.IBinder) ? (android.os.IBinder) v : null;
    }
    @SuppressWarnings("unchecked")
    public <T extends android.os.Parcelable> T getParcelable(String k) {
        unparcel();
        Object v = map.get(k);
        return (v instanceof android.os.Parcelable) ? (T) v : null;
    }
    /** API-33+ typed variant: getParcelable(String, Class<T>). Same
     *  semantics, additional class-cast safety. */
    @SuppressWarnings("unchecked")
    public <T> T getParcelable(String k, Class<T> clazz) {
        unparcel();
        Object v = map.get(k);
        return (v != null && clazz.isInstance(v)) ? (T) v : null;
    }
    public void putParcelableArrayList(String k, java.util.ArrayList<? extends android.os.Parcelable> v) { unparcel(); map.put(k, v); }
    @SuppressWarnings("unchecked")
    public <T extends android.os.Parcelable> java.util.ArrayList<T> getParcelableArrayList(String k) {
        unparcel();
        Object v = map.get(k);
        return (v instanceof java.util.ArrayList) ? (java.util.ArrayList<T>) v : null;
    }
    public void putParcelableArray(String k, android.os.Parcelable[] v) { unparcel(); map.put(k, v); }
    public android.os.Parcelable[] getParcelableArray(String k) {
        unparcel();
        Object v = map.get(k);
        return (v instanceof android.os.Parcelable[]) ? (android.os.Parcelable[]) v : null;
    }
    /** SparseArray<Parcelable> storage (AOSP Bundle, API 21+). Saved-state and
     *  RecyclerView/AdapterView state restoration carry view state as a
     *  SparseArray<Parcelable>; a missing method NoSuchMethodErrors at the
     *  invokevirtual site. v1.0.418. */
    public void putSparseParcelableArray(String k, android.util.SparseArray<? extends android.os.Parcelable> v) { unparcel(); map.put(k, v); }
    @SuppressWarnings("unchecked")
    public <T extends android.os.Parcelable> android.util.SparseArray<T> getSparseParcelableArray(String k) {
        unparcel();
        Object v = map.get(k);
        return (v instanceof android.util.SparseArray) ? (android.util.SparseArray<T>) v : null;
    }

    /** AOSP Bundle.putSize/getSize, putSizeF/getSizeF (API 21+). CameraX and
     *  other camera-adjacent code stash a preview/capture resolution in the
     *  savedInstanceState Bundle across config changes; a missing method
     *  NoSuchMethodErrors at the invokevirtual site. */
    public void putSize(String k, android.util.Size v)  { unparcel(); map.put(k, v); }
    public android.util.Size getSize(String k) {
        unparcel();
        Object v = map.get(k); return (v instanceof android.util.Size) ? (android.util.Size) v : null;
    }
    public void putSizeF(String k, android.util.SizeF v) { unparcel(); map.put(k, v); }
    public android.util.SizeF getSizeF(String k) {
        unparcel();
        Object v = map.get(k); return (v instanceof android.util.SizeF) ? (android.util.SizeF) v : null;
    }

    // float[] / byte[] arrays not covered by BaseBundle.
    public void putByteArray(String k, byte[] v)       { unparcel(); map.put(k, v); }
    public byte[] getByteArray(String k)               {
        unparcel();
        Object v = map.get(k); return (v instanceof byte[]) ? (byte[]) v : null;
    }
    public void putFloatArray(String k, float[] v)     { unparcel(); map.put(k, v); }
    public float[] getFloatArray(String k)             {
        unparcel();
        Object v = map.get(k); return (v instanceof float[]) ? (float[]) v : null;
    }
    public void putCharArray(String k, char[] v)       { unparcel(); map.put(k, v); }
    public char[] getCharArray(String k)               {
        unparcel();
        Object v = map.get(k); return (v instanceof char[]) ? (char[]) v : null;
    }
    public void putShortArray(String k, short[] v)     { unparcel(); map.put(k, v); }
    public short[] getShortArray(String k)             {
        unparcel();
        Object v = map.get(k); return (v instanceof short[]) ? (short[]) v : null;
    }
    public void putCharSequenceArray(String k, CharSequence[] v) { unparcel(); map.put(k, v); }
    public CharSequence[] getCharSequenceArray(String k) {
        unparcel();
        Object v = map.get(k); return (v instanceof CharSequence[]) ? (CharSequence[]) v : null;
    }
    public void putStringArrayList(String k, java.util.ArrayList<String> v) { unparcel(); map.put(k, v); }
    @SuppressWarnings("unchecked")
    public java.util.ArrayList<String> getStringArrayList(String k) {
        unparcel();
        Object v = map.get(k);
        return (v instanceof java.util.ArrayList) ? (java.util.ArrayList<String>) v : null;
    }
    public void putIntegerArrayList(String k, java.util.ArrayList<Integer> v) { unparcel(); map.put(k, v); }
    @SuppressWarnings("unchecked")
    public java.util.ArrayList<Integer> getIntegerArrayList(String k) {
        unparcel();
        Object v = map.get(k);
        return (v instanceof java.util.ArrayList) ? (java.util.ArrayList<Integer>) v : null;
    }
    public void putCharSequenceArrayList(String k, java.util.ArrayList<CharSequence> v) { unparcel(); map.put(k, v); }
    @SuppressWarnings("unchecked")
    public java.util.ArrayList<CharSequence> getCharSequenceArrayList(String k) {
        unparcel();
        Object v = map.get(k);
        return (v instanceof java.util.ArrayList) ? (java.util.ArrayList<CharSequence>) v : null;
    }

    // AOSP Bundle.java:191-201 re-declares both as public and delegates to
    // BaseBundle, which actually stores the loader. setClassLoader used to be a
    // no-op here, so an app that installed its own loader (the standard fix for
    // "BadParcelableException: ClassNotFoundException when unmarshalling") read
    // back a loader it never set.
    @Override public ClassLoader getClassLoader()       { return super.getClassLoader(); }
    @Override public void setClassLoader(ClassLoader l) { super.setClassLoader(l); }

    public boolean hasFileDescriptors()         { return false; }
    public Bundle deepCopy()                    { return new Bundle(this); }

    // Parcelable. Real android.os.Bundle implements Parcelable; BaseBundle
    // does not -- so this interface MUST live on Bundle to match AOSP. It is
    // load-bearing for Compose: androidx's canBeSavedToBundle()
    // (DisposableSaveableStateRegistry.android) accepts a rememberSaveable
    // value only if it isInstance of one of its AcceptableClasses (Parcelable,
    // Serializable, String, SparseArray, ...). rememberNavController()/NavHost
    // store the nav back stack as a Bundle, so a Bundle that is NOT Parcelable
    // makes RememberSaveableKt.requireCanBeSaved() throw "... cannot be saved
    // using the current SaveableStateRegistry" during the INITIAL composition,
    // which aborts the whole compose tree -> any rememberSaveable /
    // Navigation-Compose screen renders blank (found via debug/composetest
    // MACAPK_CT_MODE=navhost). We have no IPC, so the marshalling is a no-op
    // (single-VM in-memory pass, identical to AOSP when source and sink share a
    // process); declaring the interface is what makes the type check pass.
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {
        // NOT dest.writeBundle(this): writeBundle's whole job is to call THIS,
        // so delegating back would recurse forever. AOSP wraps writeToParcelInner
        // in pushAllowFds/restoreAllowFds; we carry no fds (hasFileDescriptors is
        // always false here), so the inner call is all there is.
        writeToParcelInner(dest, flags);
    }
    public static final Parcelable.Creator<Bundle> CREATOR =
        new Parcelable.Creator<Bundle>() {
            @Override public Bundle createFromParcel(Parcel source) { return source.readBundle(); }
            @Override public Bundle[] newArray(int size) { return new Bundle[size]; }
        };

    // API 33 type-safe getters. AOSP's in-memory path is getValue(key, X.class,
    // itemType) -> clazz.cast(value) with clazz = ArrayList / Parcelable[] /
    // SparseArray / the requested class: only the CONTAINER is checked, and the
    // javadoc says the item type "is only verified when unparceling". So a list
    // or sparse array whose items are of another type is still returned, never
    // null (BaseBundle.getValueAt). The array getter below additionally hands
    // back a clazz-typed copy when every item is an instance, because our Parcel
    // may rebuild arrays as Parcelable[] where AOSP rebuilds them as clazz[].
    @SuppressWarnings("unchecked")
    public <T> java.util.ArrayList<T> getParcelableArrayList(String key, Class<? extends T> clazz) {
        java.util.Objects.requireNonNull(clazz);
        return (java.util.ArrayList<T>) getParcelableArrayList(key);
    }
    @SuppressWarnings("unchecked")
    public <T> T[] getParcelableArray(String key, Class<T> clazz) {
        java.util.Objects.requireNonNull(clazz);
        Object[] a = getParcelableArray(key);
        if (a == null) return null;
        for (Object o : a) {
            if (o != null && !clazz.isInstance(o)) return (T[]) a;
        }
        T[] out = (T[]) java.lang.reflect.Array.newInstance(clazz, a.length);
        for (int i = 0; i < a.length; i++) out[i] = (T) a[i];
        return out;
    }
    @SuppressWarnings("unchecked")
    public <T extends java.io.Serializable> T getSerializable(String key, Class<T> clazz) {
        java.util.Objects.requireNonNull(clazz);
        unparcel();
        Object v = map.get(key);
        return (v != null && clazz.isInstance(v)) ? (T) v : null;
    }
    @SuppressWarnings("unchecked")
    public <T> android.util.SparseArray<T> getSparseParcelableArray(String key, Class<? extends T> clazz) {
        java.util.Objects.requireNonNull(clazz);
        return (android.util.SparseArray<T>) getSparseParcelableArray(key);
    }

    public Bundle(android.os.PersistableBundle b) { this(); }
}


