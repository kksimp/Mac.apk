// android.os.WorkSource -- attributes power/work to one or more uids (used by
// PowerManager.WakeLock.setWorkSource and AlarmManager). Mac.apk does no power
// attribution, but the value object is fully functional (real uid list) so apps
// constructing and passing one behave correctly.
//
// AOSP ref: frameworks/base/core/java/android/os/WorkSource.java
package android.os;

public class WorkSource implements Parcelable {
    private final java.util.ArrayList<Integer> mUids = new java.util.ArrayList<>();
    private final java.util.ArrayList<String> mNames = new java.util.ArrayList<>();

    public WorkSource() {}

    public WorkSource(int uid) { mUids.add(uid); mNames.add(null); }

    public WorkSource(int uid, String name) { mUids.add(uid); mNames.add(name); }

    public WorkSource(WorkSource orig) {
        if (orig != null) { mUids.addAll(orig.mUids); mNames.addAll(orig.mNames); }
    }

    public int size() { return mUids.size(); }

    public int get(int index) { return mUids.get(index); }

    public String getName(int index) { return mNames.get(index); }

    public boolean add(int uid) {
        mUids.add(uid); mNames.add(null);
        return true;
    }

    public boolean add(int uid, String name) {
        mUids.add(uid); mNames.add(name);
        return true;
    }

    public boolean add(WorkSource other) {
        if (other == null) return false;
        mUids.addAll(other.mUids); mNames.addAll(other.mNames);
        return true;
    }

    public boolean remove(WorkSource other) {
        if (other == null) return false;
        boolean changed = false;
        for (int i = 0; i < other.mUids.size(); i++) {
            int idx = mUids.indexOf(other.mUids.get(i));
            if (idx >= 0) { mUids.remove(idx); mNames.remove(idx); changed = true; }
        }
        return changed;
    }

    public void set(WorkSource other) {
        clear();
        if (other != null) { mUids.addAll(other.mUids); mNames.addAll(other.mNames); }
    }

    public void clear() { mUids.clear(); mNames.clear(); }

    public boolean isEmpty() { return mUids.isEmpty(); }

    /** AOSP core/java/android/os/WorkSource.java:300 -- "Compare this
     *  WorkSource with another. @return If there is a DIFFERENCE, true is
     *  returned." Note the inverted sense (true means unequal), and that AOSP
     *  compares names only when BOTH name arrays are non-null. Ported with the
     *  same shape over our list-backed storage. */
    public boolean diff(WorkSource other) {
        int n = mUids.size();
        if (other == null || n != other.mUids.size()) {
            return true;
        }
        for (int i = 0; i < n; i++) {
            if (!mUids.get(i).equals(other.mUids.get(i))) {
                return true;
            }
            String a = mNames.get(i);
            String b = other.mNames.get(i);
            if (a != null && b != null && !a.equals(b)) {
                return true;
            }
        }
        return false;
    }

    // ---- WorkChains (AOSP android14 WorkSource.java:554-591, 949-1070) --------
    // A chain of (uid, tag) attribution nodes; Google Play services builds them
    // for location / alarm attribution. Verbatim API and semantics.
    private java.util.ArrayList<WorkChain> mChains;

    /** @SystemApi -- a new, empty chain added to this WorkSource. */
    public WorkChain createWorkChain() {
        if (mChains == null) mChains = new java.util.ArrayList<>(4);
        final WorkChain wc = new WorkChain();
        mChains.add(wc);
        return wc;
    }

    /** @SystemApi -- the chains, or null when none were ever created. */
    public java.util.List<WorkChain> getWorkChains() { return mChains; }

    public static final class WorkChain implements Parcelable {
        private int mSize;
        private int[] mUids;
        private String[] mTags;

        public WorkChain() {
            mSize = 0;
            mUids = new int[4];
            mTags = new String[4];
        }
        public WorkChain(WorkChain other) {
            mSize = other.mSize;
            mUids = other.mUids.clone();
            mTags = other.mTags.clone();
        }
        private WorkChain(Parcel in) {
            mSize = in.readInt();
            mUids = in.createIntArray();
            mTags = in.createStringArray();
        }
        public WorkChain addNode(int uid, String tag) {
            if (mSize == mUids.length) resizeArrays();
            mUids[mSize] = uid;
            mTags[mSize] = tag;
            mSize++;
            return this;
        }
        public int getAttributionUid() { return mSize > 0 ? mUids[0] : -1; }
        public String getAttributionTag() { return mTags.length > 0 ? mTags[0] : null; }
        public int[] getUids() {
            int[] uids = new int[mSize];
            System.arraycopy(mUids, 0, uids, 0, mSize);
            return uids;
        }
        public String[] getTags() {
            String[] tags = new String[mSize];
            System.arraycopy(mTags, 0, tags, 0, mSize);
            return tags;
        }
        public int getSize() { return mSize; }
        private void resizeArrays() {
            final int newSize = mSize * 2;
            int[] uids = new int[newSize];
            String[] tags = new String[newSize];
            System.arraycopy(mUids, 0, uids, 0, mSize);
            System.arraycopy(mTags, 0, tags, 0, mSize);
            mUids = uids;
            mTags = tags;
        }
        @Override public String toString() {
            StringBuilder result = new StringBuilder("WorkChain{");
            for (int i = 0; i < mSize; ++i) {
                if (i != 0) result.append(", ");
                result.append("(").append(mUids[i]);
                if (mTags[i] != null) result.append(", ").append(mTags[i]);
                result.append(")");
            }
            return result.append("}").toString();
        }
        @Override public int hashCode() {
            return (mSize + 31 * java.util.Arrays.hashCode(mUids)) * 31 + java.util.Arrays.hashCode(mTags);
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof WorkChain)) return false;
            WorkChain other = (WorkChain) o;
            return mSize == other.mSize && java.util.Arrays.equals(mUids, other.mUids)
                    && java.util.Arrays.equals(mTags, other.mTags);
        }
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mSize);
            dest.writeIntArray(mUids);
            dest.writeStringArray(mTags);
        }
        public static final Parcelable.Creator<WorkChain> CREATOR = new Parcelable.Creator<WorkChain>() {
            @Override public WorkChain createFromParcel(Parcel in) { return new WorkChain(in); }
            @Override public WorkChain[] newArray(int size) { return new WorkChain[size]; }
        };
    }

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mUids.size());
        for (int i = 0; i < mUids.size(); i++) dest.writeInt(mUids.get(i));
        dest.writeInt(mNames.size());
        for (int i = 0; i < mNames.size(); i++) dest.writeString(mNames.get(i));
        // AOSP carries the chains too (-1 = none ever created).
        if (mChains == null) {
            dest.writeInt(-1);
        } else {
            dest.writeInt(mChains.size());
            for (WorkChain wc : mChains) wc.writeToParcel(dest, flags);
        }
    }

    public static final Parcelable.Creator<WorkSource> CREATOR =
        new Parcelable.Creator<WorkSource>() {
            @Override public WorkSource createFromParcel(Parcel in) {
                WorkSource ws = new WorkSource();
                int n = in.readInt();
                for (int i = 0; i < n; i++) ws.mUids.add(in.readInt());
                int m = in.readInt();
                for (int i = 0; i < m; i++) ws.mNames.add(in.readString());
                int c = in.readInt();
                if (c >= 0) {
                    ws.mChains = new java.util.ArrayList<>(c);
                    for (int i = 0; i < c; i++) ws.mChains.add(WorkChain.CREATOR.createFromParcel(in));
                }
                return ws;
            }
            @Override public WorkSource[] newArray(int size) { return new WorkSource[size]; }
        };
}
