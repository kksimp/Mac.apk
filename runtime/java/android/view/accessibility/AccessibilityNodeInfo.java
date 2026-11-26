// Mac.apk AccessibilityNodeInfo: minimum-shape stub so AOSP
// accessibility callbacks compile. We do not surface accessibility
// to VoiceOver / NSAccessibility on the macOS host; methods are
// no-op or trivially-getter shaped for caller round-trip.
//
// Surfaced on demand from NavigationBarItemView.onInitializeAccessibilityNodeInfo,
// which sets the content description after super does its thing.
// v1.0.95.
package android.view.accessibility;

// AOSP declares `public class AccessibilityNodeInfo implements Parcelable`.
// The missing `implements` is invisible to a member-level API diff but loud at
// runtime: Bundle.putParcelable / Intent extras fail a TYPE check instead of
// throwing, and every `instanceof Parcelable` on a node goes the wrong way.
// AccessibilityNodeProvider.createAccessibilityNodeInfo results travel through
// exactly those paths on a device.
public class AccessibilityNodeInfo implements android.os.Parcelable {
    // AOSP pools nodes; obtain()/recycle() are referenced widely (RecyclerView,
    // Compose semantics, custom onInitializeAccessibilityNodeInfo). A fresh
    // instance is observably identical since a11y is disabled. Was absent.
    private static final long UNDEFINED_NODE_ID = -1L;
    private long mSourceNodeId = UNDEFINED_NODE_ID;
    private int mWindowId = -1;
    private long mParentNodeId = UNDEFINED_NODE_ID;
    private long mTraversalBefore = UNDEFINED_NODE_ID;
    private long mTraversalAfter = UNDEFINED_NODE_ID;
    private long mLabelForId = UNDEFINED_NODE_ID;
    private long mLabeledById = UNDEFINED_NODE_ID;

    public static AccessibilityNodeInfo obtain() { return new AccessibilityNodeInfo(); }
    public static AccessibilityNodeInfo obtain(android.view.View source) {
        return new AccessibilityNodeInfo(source, AccessibilityNodeProvider.HOST_VIEW_ID);
    }
    public static AccessibilityNodeInfo obtain(android.view.View root, int virtualDescendantId) {
        return new AccessibilityNodeInfo(root, virtualDescendantId);
    }
    public static AccessibilityNodeInfo obtain(AccessibilityNodeInfo info) {
        AccessibilityNodeInfo copy = new AccessibilityNodeInfo();
        if (info != null) {
            copy.mSourceNodeId = info.mSourceNodeId;
            copy.mWindowId = info.mWindowId;
            copy.mParentNodeId = info.mParentNodeId;
            copy.mTraversalBefore = info.mTraversalBefore;
            copy.mTraversalAfter = info.mTraversalAfter;
            copy.mLabelForId = info.mLabelForId;
            copy.mLabeledById = info.mLabeledById;
            copy.mLabeledByList.addAll(info.mLabeledByList);
        }
        return copy;
    }
    public void recycle() {}

    // AccessibilityAction -- the action objects added via addAction(action). The
    // standard actions are public static final fields in AOSP; widgets reference
    // e.g. AccessibilityAction.ACTION_CLICK. Was absent.
    public static final class AccessibilityAction implements android.os.Parcelable {
        public static final AccessibilityAction ACTION_FOCUS = new AccessibilityAction(0x01, null);
        public static final AccessibilityAction ACTION_CLEAR_FOCUS = new AccessibilityAction(0x02, null);
        public static final AccessibilityAction ACTION_SELECT = new AccessibilityAction(0x04, null);
        public static final AccessibilityAction ACTION_CLICK = new AccessibilityAction(0x10, null);
        public static final AccessibilityAction ACTION_LONG_CLICK = new AccessibilityAction(0x20, null);
        public static final AccessibilityAction ACTION_SCROLL_FORWARD = new AccessibilityAction(0x1000, null);
        public static final AccessibilityAction ACTION_SCROLL_BACKWARD = new AccessibilityAction(0x2000, null);
        public static final AccessibilityAction ACTION_SHOW_ON_SCREEN = new AccessibilityAction(0x01020036, null);
        public static final AccessibilityAction ACTION_SCROLL_TO_POSITION = new AccessibilityAction(0x01020037, null);
        public static final AccessibilityAction ACTION_SCROLL_UP = new AccessibilityAction(0x01020038, null);
        public static final AccessibilityAction ACTION_SCROLL_LEFT = new AccessibilityAction(0x01020039, null);
        // ⚠ SCROLL_DOWN / SCROLL_RIGHT were SWAPPED here until v1.0.873.
        // public-final.xml: 0x0102003a = accessibilityActionScrollDown,
        //                   0x0102003b = accessibilityActionScrollRight.
        // Cross-checked against AOSP AccessibilityNodeInfo's own
        // `case R.id.accessibilityActionScrollDown: return "ACTION_SCROLL_DOWN";`
        // switch, so two independent sources agree.
        public static final AccessibilityAction ACTION_SCROLL_DOWN = new AccessibilityAction(0x0102003a, null);
        public static final AccessibilityAction ACTION_SCROLL_RIGHT = new AccessibilityAction(0x0102003b, null);
        public static final AccessibilityAction ACTION_CONTEXT_CLICK = new AccessibilityAction(0x0102003c, null);
        public static final AccessibilityAction ACTION_SET_PROGRESS = new AccessibilityAction(0x0102003d, null);
        // NOT 0x0102003e -- that is `icon_frame`. Five NON-action ids
        // (icon_frame / list_container / switch_widget / textAssist / autofill,
        // 0x3e-0x43) are interleaved between SET_PROGRESS and the real
        // accessibilityActionMoveWindow, which is 0x01020042.
        public static final AccessibilityAction ACTION_MOVE_WINDOW = new AccessibilityAction(0x01020042, null);
        public static final AccessibilityAction ACTION_SHOW_TOOLTIP = new AccessibilityAction(0x01020044, null);
        public static final AccessibilityAction ACTION_HIDE_TOOLTIP = new AccessibilityAction(0x01020045, null);
        public static final AccessibilityAction ACTION_PAGE_UP = new AccessibilityAction(0x01020046, null);
        public static final AccessibilityAction ACTION_PAGE_DOWN = new AccessibilityAction(0x01020047, null);
        public static final AccessibilityAction ACTION_PAGE_LEFT = new AccessibilityAction(0x01020048, null);
        public static final AccessibilityAction ACTION_PAGE_RIGHT = new AccessibilityAction(0x01020049, null);
        public static final AccessibilityAction ACTION_PRESS_AND_HOLD = new AccessibilityAction(0x0102004a, null);
        // ⚠ Everything from IME_ENTER down was shifted by -9 until v1.0.873:
        // NINE accessibilitySystemAction* ids (Back / Home / Recents /
        // Notifications / QuickSettings / PowerDialog / ToggleSplitScreen /
        // LockScreen / TakeScreenshot, 0x4b-0x53) sit between PRESS_AND_HOLD
        // and the real accessibilityActionImeEnter at 0x01020054.
        public static final AccessibilityAction ACTION_IME_ENTER = new AccessibilityAction(0x01020054, null);
        public static final AccessibilityAction ACTION_DRAG_START = new AccessibilityAction(0x01020055, null);
        public static final AccessibilityAction ACTION_DRAG_DROP = new AccessibilityAction(0x01020056, null);
        public static final AccessibilityAction ACTION_DRAG_CANCEL = new AccessibilityAction(0x01020057, null);
        public static final AccessibilityAction ACTION_SHOW_TEXT_SUGGESTIONS = new AccessibilityAction(0x01020058, null);
        public static final AccessibilityAction ACTION_SCROLL_IN_DIRECTION = new AccessibilityAction(0x0102005e, null);
        // ⚠ ACTION_SET_EXTENDED_SELECTION does not exist in AOSP under this
        // name, and 0x0102001d is `candidatesArea` (an unrelated IME id). The
        // real constant is ACTION_SET_SELECTION = 1 << 17 = 0x00020000, a
        // pre-Lollipop BITMASK action rather than an R.id value -- which is why
        // it belongs with the bitmask block at the top of this class, not here.
        // Kept under its existing name so any caller still links; corrected to
        // the real value.
        public static final AccessibilityAction ACTION_SET_EXTENDED_SELECTION = new AccessibilityAction(0x00020000, null);
        // The PRE-LOLLIPOP BITMASK actions. AOSP exposes each legacy
        // AccessibilityNodeInfo.ACTION_* int as an AccessibilityAction object of
        // the same id, so `addAction(AccessibilityAction.ACTION_COPY)` and
        // `addAction(ACTION_COPY)` name the same action. Values are the bitmask
        // constants (1 << n), NOT R.id values -- the same distinction the
        // ACTION_SET_EXTENDED_SELECTION note above records.
        public static final AccessibilityAction ACTION_ACCESSIBILITY_FOCUS =
                new AccessibilityAction(0x00000040, null);
        public static final AccessibilityAction ACTION_CLEAR_ACCESSIBILITY_FOCUS =
                new AccessibilityAction(0x00000080, null);
        public static final AccessibilityAction ACTION_NEXT_AT_MOVEMENT_GRANULARITY =
                new AccessibilityAction(0x00000100, null);
        public static final AccessibilityAction ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY =
                new AccessibilityAction(0x00000200, null);
        public static final AccessibilityAction ACTION_NEXT_HTML_ELEMENT =
                new AccessibilityAction(0x00000400, null);
        public static final AccessibilityAction ACTION_PREVIOUS_HTML_ELEMENT =
                new AccessibilityAction(0x00000800, null);
        public static final AccessibilityAction ACTION_CUT =
                new AccessibilityAction(0x00004000, null);
        public static final AccessibilityAction ACTION_COPY =
                new AccessibilityAction(0x00004000 << 1, null);   // 0x00008000
        public static final AccessibilityAction ACTION_PASTE =
                new AccessibilityAction(0x00010000, null);
        public static final AccessibilityAction ACTION_SET_SELECTION =
                new AccessibilityAction(0x00020000, null);
        public static final AccessibilityAction ACTION_EXPAND =
                new AccessibilityAction(0x00040000, null);
        public static final AccessibilityAction ACTION_COLLAPSE =
                new AccessibilityAction(0x00080000, null);
        public static final AccessibilityAction ACTION_DISMISS =
                new AccessibilityAction(0x00100000, null);
        public static final AccessibilityAction ACTION_SET_TEXT =
                new AccessibilityAction(0x00200000, null);
        /** AOSP's ACTION_CLEAR_SELECTION is the bitmask 1 << 3. */
        public static final AccessibilityAction ACTION_CLEAR_SELECTION =
                new AccessibilityAction(0x00000008, null);

        private final int mId;
        private final CharSequence mLabel;
        public AccessibilityAction(int id, CharSequence label) { mId = id; mLabel = label; }
        public int getId() { return mId; }
        public CharSequence getLabel() { return mLabel; }

        // AOSP field order: actionId, label (AccessibilityNodeInfo.java
        // L6921-6924). mSerializationFlag is a @hide bookkeeping field for
        // the standard-action bitset, not part of this action's identity,
        // and AOSP itself does not write it either.
        @Override public int describeContents() { return 0; }

        @Override public void writeToParcel(android.os.Parcel out, int flags) {
            out.writeInt(mId);
            out.writeCharSequence(mLabel);
        }

        public static final android.os.Parcelable.Creator<AccessibilityAction> CREATOR =
                new android.os.Parcelable.Creator<AccessibilityAction>() {
            @Override public AccessibilityAction createFromParcel(android.os.Parcel in) {
                return new AccessibilityAction(in.readInt(), in.readCharSequence());
            }
            @Override public AccessibilityAction[] newArray(int size) {
                return new AccessibilityAction[size];
            }
        };
    }

    // CollectionInfo / CollectionItemInfo -- describe lists/grids to a11y
    // services (RecyclerView/LazyColumn semantics). obtain() factories mirror AOSP.
    public static final class CollectionInfo {
        // AOSP UNDEFINED sentinel for itemCount/importantForAccessibilityItemCount
        // when the caller doesn't know the count (frameworks_base main,
        // AccessibilityNodeInfo.java:6707).
        public static final int UNDEFINED = -1;
        private int mRowCount, mColumnCount;
        private boolean mHierarchical;
        private int mSelectionMode;
        private int mItemCount = UNDEFINED;
        private int mImportantForAccessibilityItemCount = UNDEFINED;
        public CollectionInfo(int rowCount, int columnCount, boolean hierarchical) {
            this(rowCount, columnCount, hierarchical, 0);
        }
        public CollectionInfo(int rowCount, int columnCount, boolean hierarchical, int selectionMode) {
            mRowCount = rowCount; mColumnCount = columnCount;
            mHierarchical = hierarchical; mSelectionMode = selectionMode;
        }
        public static CollectionInfo obtain(int rowCount, int columnCount, boolean hierarchical) {
            return new CollectionInfo(rowCount, columnCount, hierarchical);
        }
        public static CollectionInfo obtain(int rowCount, int columnCount, boolean hierarchical, int selectionMode) {
            return new CollectionInfo(rowCount, columnCount, hierarchical, selectionMode);
        }
        public int getRowCount() { return mRowCount; }
        public int getColumnCount() { return mColumnCount; }
        public boolean isHierarchical() { return mHierarchical; }
        public int getSelectionMode() { return mSelectionMode; }
        public int getItemCount() { return mItemCount; }
        public int getImportantForAccessibilityItemCount() { return mImportantForAccessibilityItemCount; }

        // Fluent builder alongside the legacy obtain(...) factories. Modern
        // androidx.recyclerview / Compose semantics code targets this API
        // exclusively (verified AOSP frameworks_base main,
        // AccessibilityNodeInfo.java:6914-7019). Field defaults mirror AOSP's
        // Builder exactly (row/column count 0, itemCount/important... UNDEFINED).
        public static final class Builder {
            private int mRowCount = 0;
            private int mColumnCount = 0;
            private boolean mHierarchical = false;
            private int mSelectionMode;
            private int mItemCount = UNDEFINED;
            private int mImportantForAccessibilityItemCount = UNDEFINED;
            public Builder() {}
            public Builder setRowCount(int rowCount) { mRowCount = rowCount; return this; }
            public Builder setColumnCount(int columnCount) { mColumnCount = columnCount; return this; }
            public Builder setHierarchical(boolean hierarchical) { mHierarchical = hierarchical; return this; }
            public Builder setSelectionMode(int selectionMode) { mSelectionMode = selectionMode; return this; }
            public Builder setItemCount(int itemCount) { mItemCount = itemCount; return this; }
            public Builder setImportantForAccessibilityItemCount(int importantForAccessibilityItemCount) {
                mImportantForAccessibilityItemCount = importantForAccessibilityItemCount;
                return this;
            }
            public CollectionInfo build() {
                CollectionInfo info = new CollectionInfo(mRowCount, mColumnCount, mHierarchical);
                info.mSelectionMode = mSelectionMode;
                info.mItemCount = mItemCount;
                info.mImportantForAccessibilityItemCount = mImportantForAccessibilityItemCount;
                return info;
            }
        }
    }

    public static final class CollectionItemInfo {
        public int getSortDirection() { return 0; }
        private int mRowIndex, mRowSpan, mColumnIndex, mColumnSpan;
        private boolean mHeading, mSelected;
        // AOSP added row/column titles alongside indices (frameworks_base main,
        // AccessibilityNodeInfo.java:7128-7129); only the Builder below sets
        // them (matching AOSP: no public constructor overload with titles is
        // exposed outside @hide).
        private String mRowTitle, mColumnTitle;
        // Package-private no-arg ctor used only by Builder.build(), matching
        // AOSP's private CollectionItemInfo() (line 7131).
        CollectionItemInfo() {}
        /** AOSP CollectionItemInfo(int, int, int, int, boolean), the API-19 form
         *  that predates `selected`; it defaults to false, exactly as the
         *  matching obtain() overload below already does. */
        public CollectionItemInfo(int rowIndex, int rowSpan, int columnIndex, int columnSpan,
                boolean heading) {
            this(rowIndex, rowSpan, columnIndex, columnSpan, heading, false);
        }
        public CollectionItemInfo(int rowIndex, int rowSpan, int columnIndex, int columnSpan,
                boolean heading, boolean selected) {
            mRowIndex = rowIndex; mRowSpan = rowSpan;
            mColumnIndex = columnIndex; mColumnSpan = columnSpan;
            mHeading = heading; mSelected = selected;
        }
        public static CollectionItemInfo obtain(int rowIndex, int rowSpan, int columnIndex,
                int columnSpan, boolean heading, boolean selected) {
            return new CollectionItemInfo(rowIndex, rowSpan, columnIndex, columnSpan, heading, selected);
        }
        /** AOSP API-19 overload (before `selected` was added): defaults selected
         *  to false. */
        public static CollectionItemInfo obtain(int rowIndex, int rowSpan, int columnIndex,
                int columnSpan, boolean heading) {
            return new CollectionItemInfo(rowIndex, rowSpan, columnIndex, columnSpan, heading, false);
        }
        public int getRowIndex() { return mRowIndex; }
        public int getRowSpan() { return mRowSpan; }
        public int getColumnIndex() { return mColumnIndex; }
        public int getColumnSpan() { return mColumnSpan; }
        public boolean isHeading() { return mHeading; }
        public boolean isSelected() { return mSelected; }
        public String getRowTitle() { return mRowTitle; }
        public String getColumnTitle() { return mColumnTitle; }

        // Fluent builder alongside the legacy obtain(...) factories (verified
        // AOSP frameworks_base main, AccessibilityNodeInfo.java:7290-7419).
        // NOTE: AOSP main has NO setSortDirection on this Builder (verified by
        // grep of the fetched source, zero hits for SortDirection anywhere in
        // the file) -- only the 8 setters below plus build() exist. Not adding
        // a fictional method.
        public static final class Builder {
            private boolean mHeading;
            private int mColumnIndex;
            private int mRowIndex;
            private int mColumnSpan;
            private int mRowSpan;
            private boolean mSelected;
            private String mRowTitle;
            private String mColumnTitle;
            public Builder() {}
            public Builder setHeading(boolean heading) { mHeading = heading; return this; }
            public Builder setColumnIndex(int columnIndex) { mColumnIndex = columnIndex; return this; }
            public Builder setRowIndex(int rowIndex) { mRowIndex = rowIndex; return this; }
            public Builder setColumnSpan(int columnSpan) { mColumnSpan = columnSpan; return this; }
            public Builder setRowSpan(int rowSpan) { mRowSpan = rowSpan; return this; }
            public Builder setSelected(boolean selected) { mSelected = selected; return this; }
            public Builder setRowTitle(String rowTitle) { mRowTitle = rowTitle; return this; }
            public Builder setColumnTitle(String columnTitle) { mColumnTitle = columnTitle; return this; }
            public Builder setSortDirection(int sortDirection) { return this; }
            public CollectionItemInfo build() {
                CollectionItemInfo info = new CollectionItemInfo();
                info.mHeading = mHeading;
                info.mColumnIndex = mColumnIndex;
                info.mRowIndex = mRowIndex;
                info.mColumnSpan = mColumnSpan;
                info.mRowSpan = mRowSpan;
                info.mSelected = mSelected;
                info.mRowTitle = mRowTitle;
                info.mColumnTitle = mColumnTitle;
                return info;
            }
        }
    }

    // RangeInfo -- for sliders/progress (SeekBar, Compose Slider semantics).
    public static final class RangeInfo {
        public static final int RANGE_TYPE_INT = 0;
        public static final int RANGE_TYPE_FLOAT = 1;
        public static final int RANGE_TYPE_PERCENT = 2;
        /** AOSP RangeInfo.RANGE_TYPE_INDETERMINATE (API 36). */
        public static final int RANGE_TYPE_INDETERMINATE = 3;
        private final int mType;
        private final float mMin, mMax, mCurrent;
        public RangeInfo(int type, float min, float max, float current) {
            mType = type; mMin = min; mMax = max; mCurrent = current;
        }
        public static RangeInfo obtain(int type, float min, float max, float current) {
            return new RangeInfo(type, min, max, current);
        }
        /** AOSP RangeInfo.INDETERMINATE (API 36): the shared instance describing
         *  a range whose position is unknown -- an indeterminate ProgressBar.
         *  AOSP builds it as RANGE_TYPE_INDETERMINATE with all three values 0. */
        public static final RangeInfo INDETERMINATE =
                new RangeInfo(RANGE_TYPE_INDETERMINATE, 0f, 0f, 0f);
        public int getType() { return mType; }
        public float getMin() { return mMin; }
        public float getMax() { return mMax; }
        public float getCurrent() { return mCurrent; }
    }

    // ==================================================================
    // v1.0.684: the full node property surface.
    //
    // AccessibilityNodeInfo is a PROPERTY BAG that both the framework and the
    // app write into (View.onInitializeAccessibilityNodeInfo, and every
    // androidx AccessibilityNodeInfoCompat call). It is the largest single gap
    // the v677 audit found: 123 missing methods, referenced by 30 of 37 corpus
    // apps -- because androidx ViewCompat / RecyclerView / Compose populate a
    // node on EVERY app, not only when a screen reader is running.
    //
    // Critically, several setters that DID exist were empty no-ops
    // (setPassword, setScrollable, setVisibleToUser, setBoundsInScreen...), so
    // a caller could set a property and read back nothing. Everything here
    // STORES, so every setter round-trips to its getter -- which is the whole
    // observable contract of this class when no a11y service is connected.
    //
    // Node RELATIONSHIPS (getParent/getChild/findFocus/refresh) still return
    // null/false: AOSP itself returns null for those without a live
    // AccessibilityInteractionConnection, which is exactly our state. The
    // child/action bookkeeping that the app itself populates is real.
    //
    // Source: frameworks/base/core/java/android/view/accessibility/AccessibilityNodeInfo.java
    // ==================================================================

    // ---- AOSP action id constants (apps pass these to addAction(int)) ----
    public static final int ACTION_FOCUS = 0x00000001;
    public static final int ACTION_CLEAR_FOCUS = 0x00000002;
    public static final int ACTION_SELECT = 0x00000004;
    public static final int ACTION_CLEAR_SELECTION = 0x00000008;
    public static final int ACTION_CLICK = 0x00000010;
    public static final int ACTION_LONG_CLICK = 0x00000020;
    public static final int ACTION_ACCESSIBILITY_FOCUS = 0x00000040;
    public static final int ACTION_CLEAR_ACCESSIBILITY_FOCUS = 0x00000080;
    public static final int ACTION_NEXT_AT_MOVEMENT_GRANULARITY = 0x00000100;
    public static final int ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY = 0x00000200;
    public static final int ACTION_NEXT_HTML_ELEMENT = 0x00000400;
    public static final int ACTION_PREVIOUS_HTML_ELEMENT = 0x00000800;
    public static final int ACTION_SCROLL_FORWARD = 0x00001000;
    public static final int ACTION_SCROLL_BACKWARD = 0x00002000;
    public static final int ACTION_COPY = 0x00004000;
    public static final int ACTION_PASTE = 0x00008000;
    public static final int ACTION_CUT = 0x00010000;
    public static final int ACTION_SET_SELECTION = 0x00020000;
    public static final int ACTION_EXPAND = 0x00040000;
    public static final int ACTION_COLLAPSE = 0x00080000;
    public static final int ACTION_DISMISS = 0x00100000;
    public static final int ACTION_SET_TEXT = 0x00200000;

    public static final int MOVEMENT_GRANULARITY_CHARACTER = 0x00000001;
    public static final int MOVEMENT_GRANULARITY_WORD = 0x00000002;
    public static final int MOVEMENT_GRANULARITY_LINE = 0x00000004;
    public static final int MOVEMENT_GRANULARITY_PARAGRAPH = 0x00000008;
    public static final int MOVEMENT_GRANULARITY_PAGE = 0x00000010;

    public static final int FOCUS_INPUT = 1;
    public static final int FOCUS_ACCESSIBILITY = 2;

    public static final String EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY =
            "android.view.accessibility.extra.DATA_TEXT_CHARACTER_LOCATION_KEY";
    public static final String EXTRA_DATA_RENDERING_INFO_KEY =
            "android.view.accessibility.extra.DATA_RENDERING_INFO_KEY";

    // ---- backing state -------------------------------------------------
    private CollectionInfo collectionInfo;
    private CollectionItemInfo collectionItemInfo;
    private RangeInfo rangeInfo;
    private TouchDelegateInfo mTouchDelegateInfo;
    private ExtraRenderingInfo mExtraRenderingInfo;

    private CharSequence contentDescription;
    private CharSequence text;
    private CharSequence mStateDescription;
    private CharSequence mHintText;
    private CharSequence mError;
    private CharSequence mTooltipText;
    private CharSequence mPackageName;
    private CharSequence mClassName;
    private CharSequence mPaneTitle;
    private String mViewIdResourceName;
    private String mUniqueId;

    private int mActions;
    private int mMovementGranularities;
    private int mInputType;
    private int mLiveRegion;
    private int mDrawingOrder;
    private int mMaxTextLength = -1;
    private int mTextSelectionStart = -1;
    private int mTextSelectionEnd = -1;
    // AOSP keeps a node's children as NODE IDS (mChildNodeIds, a LongArray of
    // makeNodeId(accessibilityViewId, virtualDescendantId); android14-release
    // AccessibilityNodeInfo.java :931, :1378-1396), not as a count. The ids are
    // what an accessibility client walks a provider's VIRTUAL tree by
    // (getChildId(int) + getVirtualDescendantId(long)), and the in-process
    // harness dump (com.macapk.loader.ViewTextDump) does exactly that for
    // Compose's semantics tree. With only a count, the walk stopped at the host.
    private long[] mChildNodeIds;
    private int mChildIdCount;

    private final android.graphics.Rect mBoundsInParent = new android.graphics.Rect();
    private final android.graphics.Rect mBoundsInScreen = new android.graphics.Rect();
    private final android.graphics.Rect mBoundsInWindow = new android.graphics.Rect();

    private android.os.Bundle mExtras;
    private java.util.List<String> mAvailableExtraData;
    private final java.util.ArrayList<AccessibilityAction> mActionList =
            new java.util.ArrayList<>();

    private boolean checkable, checked, clickable, focusable, focused, selected;
    private boolean longClickable, mPassword, mScrollable, mEditable, mHeadingFlag;
    private boolean enabled = true;
    private boolean mVisibleToUser, mAccessibilityFocused, mContentInvalid, mDismissable;
    private boolean mMultiLine, mCanOpenPopup, mContextClickable, mScreenReaderFocusable;
    private boolean mShowingHintText, mTextEntryKey, mTextSelectable;
    private boolean mImportantForAccessibility = true;
    private boolean mRequestInitialAccessibilityFocus;

    public AccessibilityNodeInfo() {}

    public AccessibilityNodeInfo(android.view.View source) { setSource(source); }

    // ---- text / description -------------------------------------------
    public void setContentDescription(CharSequence d) { this.contentDescription = d; }
    public CharSequence getContentDescription() { return contentDescription; }

    public void setText(CharSequence t) { this.text = t; }
    public CharSequence getText() { return text; }

    public void setStateDescription(CharSequence v) { mStateDescription = v; }
    public CharSequence getStateDescription() { return mStateDescription; }

    public void setHintText(CharSequence v) { mHintText = v; }
    public CharSequence getHintText() { return mHintText; }

    public void setError(CharSequence v) { mError = v; }
    public CharSequence getError() { return mError; }

    public void setTooltipText(CharSequence v) { mTooltipText = v; }
    public CharSequence getTooltipText() { return mTooltipText; }

    public void setPackageName(CharSequence v) { mPackageName = v; }
    public CharSequence getPackageName() { return mPackageName; }

    public void setClassName(CharSequence v) { mClassName = v; }
    public CharSequence getClassName() { return mClassName; }

    public void setPaneTitle(CharSequence v) { mPaneTitle = v; }
    public CharSequence getPaneTitle() { return mPaneTitle; }

    public void setViewIdResourceName(String v) { mViewIdResourceName = v; }
    public String getViewIdResourceName() { return mViewIdResourceName; }

    public void setUniqueId(String v) { mUniqueId = v; }
    public String getUniqueId() { return mUniqueId; }

    // ---- boolean properties -------------------------------------------
    public void setCheckable(boolean b) { checkable = b; }
    public boolean isCheckable() { return checkable; }
    public void setChecked(boolean b) { checked = b; }
    public boolean isChecked() { return checked; }
    public void setClickable(boolean b) { clickable = b; }
    public boolean isClickable() { return clickable; }
    public void setEnabled(boolean b) { enabled = b; }
    public boolean isEnabled() { return enabled; }
    public void setFocusable(boolean b) { focusable = b; }
    public boolean isFocusable() { return focusable; }
    public void setFocused(boolean b) { focused = b; }
    public boolean isFocused() { return focused; }
    public void setSelected(boolean b) { selected = b; }
    public boolean isSelected() { return selected; }
    public void setLongClickable(boolean b) { longClickable = b; }
    public boolean isLongClickable() { return longClickable; }
    public void setPassword(boolean b) { mPassword = b; }
    public boolean isPassword() { return mPassword; }
    public void setScrollable(boolean b) { mScrollable = b; }
    public boolean isScrollable() { return mScrollable; }
    public void setEditable(boolean b) { mEditable = b; }
    public boolean isEditable() { return mEditable; }
    public void setHeading(boolean b) { mHeadingFlag = b; }
    public boolean isHeading() { return mHeadingFlag; }
    public void setVisibleToUser(boolean b) { mVisibleToUser = b; }
    public boolean isVisibleToUser() { return mVisibleToUser; }
    public void setAccessibilityFocused(boolean b) { mAccessibilityFocused = b; }
    public boolean isAccessibilityFocused() { return mAccessibilityFocused; }
    public void setContentInvalid(boolean b) { mContentInvalid = b; }
    public boolean isContentInvalid() { return mContentInvalid; }
    public void setDismissable(boolean b) { mDismissable = b; }
    public boolean isDismissable() { return mDismissable; }
    public void setMultiLine(boolean b) { mMultiLine = b; }
    public boolean isMultiLine() { return mMultiLine; }
    public void setCanOpenPopup(boolean b) { mCanOpenPopup = b; }
    public boolean canOpenPopup() { return mCanOpenPopup; }
    public void setContextClickable(boolean b) { mContextClickable = b; }
    public boolean isContextClickable() { return mContextClickable; }
    public void setScreenReaderFocusable(boolean b) { mScreenReaderFocusable = b; }
    public boolean isScreenReaderFocusable() { return mScreenReaderFocusable; }
    public void setShowingHintText(boolean b) { mShowingHintText = b; }
    public boolean isShowingHintText() { return mShowingHintText; }
    public void setTextEntryKey(boolean b) { mTextEntryKey = b; }
    public boolean isTextEntryKey() { return mTextEntryKey; }
    public void setTextSelectable(boolean b) { mTextSelectable = b; }
    public boolean isTextSelectable() { return mTextSelectable; }
    public void setImportantForAccessibility(boolean b) { mImportantForAccessibility = b; }
    public boolean isImportantForAccessibility() { return mImportantForAccessibility; }
    public void setRequestInitialAccessibilityFocus(boolean b) { mRequestInitialAccessibilityFocus = b; }
    public boolean hasRequestInitialAccessibilityFocus() { return mRequestInitialAccessibilityFocus; }

    // ---- int properties -------------------------------------------------
    public void setMovementGranularities(int g) { mMovementGranularities = g; }
    public int getMovementGranularities() { return mMovementGranularities; }
    public void setInputType(int t) { mInputType = t; }
    public int getInputType() { return mInputType; }
    public void setLiveRegion(int m) { mLiveRegion = m; }
    public int getLiveRegion() { return mLiveRegion; }
    public void setDrawingOrder(int o) { mDrawingOrder = o; }
    public int getDrawingOrder() { return mDrawingOrder; }
    public void setMaxTextLength(int n) { mMaxTextLength = n; }
    public int getMaxTextLength() { return mMaxTextLength; }
    public void setTextSelection(int start, int end) {
        mTextSelectionStart = start; mTextSelectionEnd = end;
    }
    public int getTextSelectionStart() { return mTextSelectionStart; }
    public int getTextSelectionEnd() { return mTextSelectionEnd; }

    // ---- bounds ---------------------------------------------------------
    public void setBoundsInParent(android.graphics.Rect b) {
        if (b != null) mBoundsInParent.set(b);
    }
    public void getBoundsInParent(android.graphics.Rect out) {
        if (out != null) out.set(mBoundsInParent);
    }
    public void setBoundsInScreen(android.graphics.Rect b) {
        if (b != null) mBoundsInScreen.set(b);
    }
    public void getBoundsInScreen(android.graphics.Rect out) {
        if (out != null) out.set(mBoundsInScreen);
    }
    public void setBoundsInWindow(android.graphics.Rect b) {
        if (b != null) mBoundsInWindow.set(b);
    }
    public void getBoundsInWindow(android.graphics.Rect out) {
        if (out != null) out.set(mBoundsInWindow);
    }

    // ---- extras ---------------------------------------------------------
    /** AOSP lazily creates and returns a live Bundle, never null. */
    public android.os.Bundle getExtras() {
        if (mExtras == null) mExtras = new android.os.Bundle();
        return mExtras;
    }
    public boolean hasExtras() { return mExtras != null && !mExtras.isEmpty(); }

    public void setAvailableExtraData(java.util.List<String> extraDataKeys) {
        mAvailableExtraData = extraDataKeys;
    }
    public java.util.List<String> getAvailableExtraData() {
        return mAvailableExtraData != null ? mAvailableExtraData
                                           : java.util.Collections.<String>emptyList();
    }
    /** No a11y connection to fetch extra data through. */
    public boolean refreshWithExtraData(String extraDataKey, android.os.Bundle args) {
        return false;
    }

    // ---- actions ---------------------------------------------------------
    public void addAction(int action) { mActions |= action; }

    public void addAction(AccessibilityAction action) {
        if (action == null) return;
        mActionList.add(action);
        mActions |= action.getId();
    }

    /** Kept for callers that reach the erased overload. */
    public void addAction(Object action) {
        if (action instanceof AccessibilityAction) addAction((AccessibilityAction) action);
    }

    public boolean removeAction(AccessibilityAction action) {
        if (action == null) return false;
        boolean removed = mActionList.remove(action);
        if (removed) mActions &= ~action.getId();
        return removed;
    }

    // AOSP: the legacy int overload returns void (only the AccessibilityAction
    // overload reports a boolean). Returning boolean here is a different
    // descriptor, so an SDK-compiled removeAction(int) call cannot link.
    // frameworks/base/core/java/android/view/accessibility/AccessibilityNodeInfo.java
    public void removeAction(int action) {
        mActions &= ~action;
        for (int i = mActionList.size() - 1; i >= 0; i--) {
            if (mActionList.get(i).getId() == action) mActionList.remove(i);
        }
    }

    public int getActions() { return mActions; }

    public java.util.List<AccessibilityAction> getActionList() {
        return java.util.Collections.unmodifiableList(mActionList);
    }

    public void removeAllActions() { mActionList.clear(); mActions = 0; }

    /**
     * No a11y connection, so there is no remote node to act on. AOSP returns
     * false in exactly this state rather than throwing.
     */
    public boolean performAction(int action) { return false; }
    public boolean performAction(int action, android.os.Bundle arguments) { return false; }

    // ---- hierarchy -------------------------------------------------------
    // The child COUNT is real bookkeeping (the app adds children itself), but
    // resolving a child or parent to a node needs a live connection, which we
    // do not have; AOSP returns null there too.
    // AOSP addChild(View) = addChildInternal(child, AccessibilityNodeProvider.HOST_VIEW_ID, true), and
    // a duplicate id is ignored (the `checked` branch, :1392-1394).
    public void addChild(android.view.View child) {
        if (child != null) addChildId(makeNodeId(accessibilityViewIdOf(child), AccessibilityNodeProvider.HOST_VIEW_ID));
    }
    public void addChild(android.view.View root, int virtualDescendantId) {
        addChildId(makeNodeId(accessibilityViewIdOf(root), virtualDescendantId));
    }
    public boolean removeChild(android.view.View child) {
        return removeChildId(makeNodeId(accessibilityViewIdOf(child), AccessibilityNodeProvider.HOST_VIEW_ID));
    }
    public boolean removeChild(android.view.View root, int virtualDescendantId) {
        return removeChildId(makeNodeId(accessibilityViewIdOf(root), virtualDescendantId));
    }
    public int getChildCount() { return mChildIdCount; }

    /** @hide AOSP (:1183-1188): the node id of child `index`. */
    public long getChildId(int index) {
        if (index < 0 || index >= mChildIdCount) throw new IndexOutOfBoundsException();
        return mChildNodeIds[index];
    }
    /** @hide AOSP (:120). */
    public static final int UNDEFINED_ITEM_ID = Integer.MAX_VALUE;
    private static final long VIRTUAL_DESCENDANT_ID_MASK = 0xffffffff00000000L;
    private static final int VIRTUAL_DESCENDANT_ID_SHIFT = 32;
    /** @hide AOSP (:856-858). */
    public static int getAccessibilityViewId(long accessibilityNodeId) {
        return (int) accessibilityNodeId;
    }
    /** @hide AOSP (:871-874). */
    public static int getVirtualDescendantId(long accessibilityNodeId) {
        return (int) ((accessibilityNodeId & VIRTUAL_DESCENDANT_ID_MASK)
                >> VIRTUAL_DESCENDANT_ID_SHIFT);
    }
    /** @hide AOSP (:887-889). */
    public static long makeNodeId(int accessibilityViewId, int virtualDescendantId) {
        return (((long) virtualDescendantId) << VIRTUAL_DESCENDANT_ID_SHIFT) | accessibilityViewId;
    }
    // AOSP reads View.getAccessibilityViewId(), a per-view id from a static
    // counter; our View has no such id, so the identity hash stands in. It only
    // has to tell a node's children apart, which it does for live views.
    private static int accessibilityViewIdOf(android.view.View v) {
        return v == null ? UNDEFINED_ITEM_ID : System.identityHashCode(v);
    }
    private void addChildId(long id) {
        for (int i = 0; i < mChildIdCount; i++) if (mChildNodeIds[i] == id) return;
        if (mChildNodeIds == null) mChildNodeIds = new long[4];
        else if (mChildIdCount == mChildNodeIds.length)
            mChildNodeIds = java.util.Arrays.copyOf(mChildNodeIds, mChildIdCount * 2);
        mChildNodeIds[mChildIdCount++] = id;
    }
    private boolean removeChildId(long id) {
        for (int i = 0; i < mChildIdCount; i++) {
            if (mChildNodeIds[i] == id) {
                System.arraycopy(mChildNodeIds, i + 1, mChildNodeIds, i, mChildIdCount - i - 1);
                mChildIdCount--;
                return true;
            }
        }
        return false;
    }
    public AccessibilityNodeInfo getChild(int index) { return null; }
    public AccessibilityNodeInfo getChild(int index, int prefetchingStrategy) { return null; }
    public AccessibilityNodeInfo getParent() { return null; }
    public AccessibilityNodeInfo getParent(int prefetchingStrategy) { return null; }

    private static long nodeIdOf(android.view.View view, int virtualDescendantId) {
        return view == null ? UNDEFINED_NODE_ID : makeNodeId(accessibilityViewIdOf(view), virtualDescendantId);
    }
    public void setParent(android.view.View parent) { setParent(parent, AccessibilityNodeProvider.HOST_VIEW_ID); }
    public void setParent(android.view.View root, int virtualDescendantId) {
        mParentNodeId = nodeIdOf(root, virtualDescendantId);
    }
    public void setSource(android.view.View source) { setSource(source, AccessibilityNodeProvider.HOST_VIEW_ID); }
    public void setSource(android.view.View root, int virtualDescendantId) {
        mSourceNodeId = nodeIdOf(root, virtualDescendantId);
    }

    public void setTraversalBefore(android.view.View view) {
        setTraversalBefore(view, AccessibilityNodeProvider.HOST_VIEW_ID);
    }
    public void setTraversalBefore(android.view.View root, int virtualDescendantId) {
        mTraversalBefore = nodeIdOf(root, virtualDescendantId);
    }
    public AccessibilityNodeInfo getTraversalBefore() { return null; }
    public void setTraversalAfter(android.view.View view) {
        setTraversalAfter(view, AccessibilityNodeProvider.HOST_VIEW_ID);
    }
    public void setTraversalAfter(android.view.View root, int virtualDescendantId) {
        mTraversalAfter = nodeIdOf(root, virtualDescendantId);
    }
    public AccessibilityNodeInfo getTraversalAfter() { return null; }

    public void setLabelFor(android.view.View labeled) {
        setLabelFor(labeled, AccessibilityNodeProvider.HOST_VIEW_ID);
    }
    public void setLabelFor(android.view.View root, int virtualDescendantId) {
        mLabelForId = nodeIdOf(root, virtualDescendantId);
    }
    public AccessibilityNodeInfo getLabelFor() { return null; }
    public void setLabeledBy(android.view.View label) {
        setLabeledBy(label, AccessibilityNodeProvider.HOST_VIEW_ID);
    }
    public void setLabeledBy(android.view.View root, int virtualDescendantId) {
        mLabeledById = nodeIdOf(root, virtualDescendantId);
    }
    public AccessibilityNodeInfo getLabeledBy() { return null; }

    public java.util.List<AccessibilityNodeInfo> findAccessibilityNodeInfosByText(String text) {
        return java.util.Collections.emptyList();
    }
    public java.util.List<AccessibilityNodeInfo> findAccessibilityNodeInfosByViewId(String id) {
        return java.util.Collections.emptyList();
    }
    public AccessibilityNodeInfo findFocus(int focus) { return null; }
    public AccessibilityNodeInfo focusSearch(int direction) { return null; }

    /** No connection to re-read from; AOSP returns false when unrefreshable. */
    public boolean refresh() { return false; }

    public int getWindowId() { return mWindowId; }
    public AccessibilityWindowInfo getWindow() { return null; }

    // ---- structured sub-info --------------------------------------------
    public void setCollectionInfo(CollectionInfo v) { collectionInfo = v; }
    public CollectionInfo getCollectionInfo() { return collectionInfo; }
    public void setCollectionItemInfo(CollectionItemInfo v) { collectionItemInfo = v; }
    public CollectionItemInfo getCollectionItemInfo() { return collectionItemInfo; }
    public void setRangeInfo(RangeInfo v) { rangeInfo = v; }
    public RangeInfo getRangeInfo() { return rangeInfo; }
    public void setTouchDelegateInfo(TouchDelegateInfo v) { mTouchDelegateInfo = v; }
    public TouchDelegateInfo getTouchDelegateInfo() { return mTouchDelegateInfo; }
    public ExtraRenderingInfo getExtraRenderingInfo() { return mExtraRenderingInfo; }
    public void setExtraRenderingInfo(ExtraRenderingInfo v) { mExtraRenderingInfo = v; }

    @Override public String toString() {
        return "AccessibilityNodeInfo[class=" + mClassName + " text=" + text
                + " bounds=" + mBoundsInScreen + "]";
    }

    /**
     * The touch-delegate regions of a node: extra hit areas that forward to a
     * target node, so a small view can be reachable by a larger touch target.
     *
     * AOSP builds one from a Map<Region, View> at the ViewGroup level and hands
     * it to accessibility clients. We have no live accessibility client, so no
     * instance is normally produced, but the type and its accessors must exist
     * for the walking code to link.
     *
     * Source: frameworks/base/core/java/android/view/accessibility/AccessibilityNodeInfo.java
     * (nested class TouchDelegateInfo)
     */
    public static final class TouchDelegateInfo implements android.os.Parcelable {
        private final java.util.List<android.graphics.Region> mRegions =
                new java.util.ArrayList<>();
        private final java.util.List<AccessibilityNodeInfo> mTargets =
                new java.util.ArrayList<>();

        public TouchDelegateInfo(java.util.Map<android.graphics.Region,
                                               android.view.View> targetMap) {
            if (targetMap == null || targetMap.isEmpty()) {
                throw new IllegalArgumentException("empty or null map");
            }
            for (java.util.Map.Entry<android.graphics.Region, android.view.View> e
                    : targetMap.entrySet()) {
                mRegions.add(e.getKey());
                // A node cannot be resolved without a live a11y connection;
                // AOSP also returns null here when the target is unavailable.
                mTargets.add(null);
            }
        }

        /** CREATOR-only: rebuild directly from a region list, bypassing the
         *  public Map&lt;Region,View&gt; constructor. mTargets is always
         *  null-filled regardless of construction path (see above), so no
         *  state is lost by skipping the View side on the way back in. */
        private TouchDelegateInfo(java.util.List<android.graphics.Region> regions) {
            mRegions.addAll(regions);
            for (int i = 0; i < regions.size(); i++) mTargets.add(null);
        }

        public int getRegionCount() { return mRegions.size(); }

        public android.graphics.Region getRegionAt(int index) {
            return mRegions.get(index);
        }

        public AccessibilityNodeInfo getTargetForRegion(android.graphics.Region region) {
            int i = mRegions.indexOf(region);
            return i >= 0 ? mTargets.get(i) : null;
        }

        // AOSP stores a Region->accessibilityId(long) map, resolving the
        // real AccessibilityNodeInfo lazily through a live a11y connection
        // (AccessibilityNodeInfo.java L7943-7955). This class has no such
        // connection and mTargets is always a null placeholder (see the
        // constructor above), so the accessibility-id half carries no real
        // information here; only the region list is genuine state, and
        // that is exactly what this writes.
        @Override public int describeContents() { return 0; }

        @Override public void writeToParcel(android.os.Parcel dest, int flags) {
            dest.writeInt(mRegions.size());
            for (android.graphics.Region r : mRegions) r.writeToParcel(dest, flags);
        }

        public static final android.os.Parcelable.Creator<TouchDelegateInfo> CREATOR =
                new android.os.Parcelable.Creator<TouchDelegateInfo>() {
            @Override public TouchDelegateInfo createFromParcel(android.os.Parcel in) {
                int n = in.readInt();
                java.util.List<android.graphics.Region> regions = new java.util.ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    regions.add(android.graphics.Region.CREATOR.createFromParcel(in));
                }
                return new TouchDelegateInfo(regions);
            }
            @Override public TouchDelegateInfo[] newArray(int size) {
                return new TouchDelegateInfo[size];
            }
        };
    }

    /**
     * Extra rendering info (text size and its unit, plus layout params) that a
     * screen reader uses to describe how text is laid out.
     *
     * Source: frameworks/base/core/java/android/view/accessibility/AccessibilityNodeInfo.java
     * (nested class ExtraRenderingInfo)
     */
    public static final class ExtraRenderingInfo {
        private android.util.Size mLayoutSize;
        private float mTextSizeInPx = Float.NEGATIVE_INFINITY;
        private int mTextSizeUnit = android.util.TypedValue.COMPLEX_UNIT_PX;

        ExtraRenderingInfo() { }

        public android.util.Size getLayoutSize() { return mLayoutSize; }

        public void setLayoutSize(android.util.Size size) { mLayoutSize = size; }

        public float getTextSizeInPx() { return mTextSizeInPx; }

        public void setTextSizeInPx(float textSizeInPx) { mTextSizeInPx = textSizeInPx; }

        public int getTextSizeUnit() { return mTextSizeUnit; }

        public void setTextSizeUnit(int textSizeUnit) { mTextSizeUnit = textSizeUnit; }
    }

    // --- Selection (API 36) -------------------------------------------------
    private Selection mSelection;

    /** The current text/container selection reported by this node, or null. */
    public Selection getSelection() { return mSelection; }

    /** Sets the selection carried by this node. */
    public void setSelection(Selection selection) { mSelection = selection; }

    /** One endpoint of a selection: a node (or view) plus a character offset.
     *  Source: frameworks/base/core/java/android/view/accessibility/AccessibilityNodeInfo.java */
    public static final class SelectionPosition implements android.os.Parcelable {
        private final AccessibilityNodeInfo mNode;
        private final android.view.View mView;
        private final int mOffset;
        public SelectionPosition(android.view.View view, int offset) {
            mView = view; mNode = null; mOffset = offset;
        }
        public SelectionPosition(android.view.View view, int lineStart, int offset) {
            mView = view; mNode = null; mOffset = offset;
        }
        public SelectionPosition(AccessibilityNodeInfo node, int offset) {
            mNode = node; mView = null; mOffset = offset;
        }
        public AccessibilityNodeInfo getNode() { return mNode; }
        public int getOffset() { return mOffset; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SelectionPosition)) return false;
            SelectionPosition p = (SelectionPosition) o;
            return mOffset == p.mOffset && mNode == p.mNode && mView == p.mView;
        }
        @Override public int hashCode() {
            int h = mOffset;
            h = 31 * h + System.identityHashCode(mNode);
            h = 31 * h + System.identityHashCode(mView);
            return h;
        }

        // AOSP marshals the node's accessibility id and the offset. A
        // SelectionPosition built from a live View or a live node cannot be
        // rebuilt from bytes in this tree (there is no a11y id registry), and
        // android/os/Parcel keeps the LIVE object when a body writes nothing --
        // which for a single process is the identity-preserving answer. See the
        // same reasoning on android.view.Surface.
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(android.os.Parcel dest, int flags) { }
        public static final android.os.Parcelable.Creator<SelectionPosition> CREATOR =
                new android.os.Parcelable.Creator<SelectionPosition>() {
            @Override public SelectionPosition createFromParcel(android.os.Parcel in) {
                return new SelectionPosition((AccessibilityNodeInfo) null, 0);
            }
            @Override public SelectionPosition[] newArray(int size) {
                return new SelectionPosition[size];
            }
        };
    }

    /** A selection range: a start and end SelectionPosition. API 36.
     *  Source: frameworks/base/core/java/android/view/accessibility/AccessibilityNodeInfo.java */
    public static final class Selection implements android.os.Parcelable {
        private final SelectionPosition mStart;
        private final SelectionPosition mEnd;
        public Selection(SelectionPosition start, SelectionPosition end) {
            mStart = start; mEnd = end;
        }
        public SelectionPosition getStart() { return mStart; }
        public SelectionPosition getEnd() { return mEnd; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Selection)) return false;
            Selection s = (Selection) o;
            return java.util.Objects.equals(mStart, s.mStart)
                    && java.util.Objects.equals(mEnd, s.mEnd);
        }
        @Override public int hashCode() { return java.util.Objects.hash(mStart, mEnd); }

        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(android.os.Parcel dest, int flags) {
            dest.writeParcelable(mStart, flags);
            dest.writeParcelable(mEnd, flags);
        }
        public static final android.os.Parcelable.Creator<Selection> CREATOR =
                new android.os.Parcelable.Creator<Selection>() {
            @Override public Selection createFromParcel(android.os.Parcel in) {
                ClassLoader cl = SelectionPosition.class.getClassLoader();
                return new Selection((SelectionPosition) in.readParcelable(cl),
                        (SelectionPosition) in.readParcelable(cl));
            }
            @Override public Selection[] newArray(int size) { return new Selection[size]; }
        };
    }

    // API 33/34 additions.
    private CharSequence mContainerTitle;
    private boolean mAccessibilityDataSensitive;
    private java.time.Duration mMinDurationBetweenContentChanges;
    public CharSequence getContainerTitle() { return mContainerTitle; }
    public void setContainerTitle(CharSequence title) { mContainerTitle = title; }
    public boolean isAccessibilityDataSensitive() { return mAccessibilityDataSensitive; }
    public void setAccessibilityDataSensitive(boolean sensitive) { mAccessibilityDataSensitive = sensitive; }
    public java.time.Duration getMinDurationBetweenContentChanges() { return mMinDurationBetweenContentChanges; }
    public void setMinDurationBetweenContentChanges(java.time.Duration duration) { mMinDurationBetweenContentChanges = duration; }
    public void setQueryFromAppProcessEnabled(android.view.View view, boolean enabled) { }

    private CharSequence mSupplementalDescription;
    private boolean mFieldRequired;
    private int mExpandedState;
    private int mChecked;
    public CharSequence getSupplementalDescription() { return mSupplementalDescription; }
    public void setSupplementalDescription(CharSequence d) { mSupplementalDescription = d; }
    public boolean isFieldRequired() { return mFieldRequired; }
    public void setFieldRequired(boolean required) { mFieldRequired = required; }
    public int getExpandedState() { return mExpandedState; }
    public void setExpandedState(int state) { mExpandedState = state; }
    public int getChecked() { return mChecked; }
    public void setChecked(int checked) { mChecked = checked; }

    // Consolidated into mLabeledByList + addLabeledBy/removeLabeledBy/getLabeledByList below.

    // ======================================================================
    //  AOSP API-36 surface completion (Mac.apk API-gap lane, android.view).
    // ======================================================================

    /** AOSP AccessibilityNodeInfo(View, int virtualDescendantId): the public
     *  constructor for a VIRTUAL node hosted by a real view. Mirrors the
     *  obtain(View, int) factory already at the top of this class -- AOSP made
     *  the constructors public at API 30 and deprecated obtain(). */
    public AccessibilityNodeInfo(android.view.View root, int virtualDescendantId) {
        this();
        setSource(root, virtualDescendantId);
    }

    /** AOSP AccessibilityNodeInfo.setGranularScrollingSupported(boolean) /
     *  isGranularScrollingSupported() (API 34): whether the node can scroll by
     *  a granularity smaller than a page. A plain round-trip on AOSP too. */
    private boolean mGranularScrollingSupported;
    public void setGranularScrollingSupported(boolean granularScrollingSupported) {
        mGranularScrollingSupported = granularScrollingSupported;
    }
    public boolean isGranularScrollingSupported() { return mGranularScrollingSupported; }

    /** AOSP AccessibilityNodeInfo.addLabeledBy(View[, int]) /
     *  removeLabeledBy(View[, int]) / getLabeledByList() (API 36): the
     *  multi-label replacement for setLabeledBy. */
    private final java.util.ArrayList<AccessibilityNodeInfo> mLabeledByList =
            new java.util.ArrayList<AccessibilityNodeInfo>();
    public java.util.List<AccessibilityNodeInfo> getLabeledByList() {
        return java.util.Collections.unmodifiableList(
                new java.util.ArrayList<AccessibilityNodeInfo>(mLabeledByList));
    }
    public void addLabeledBy(android.view.View label) {
        addLabeledBy(label, AccessibilityNodeProvider.HOST_VIEW_ID);
    }
    public void addLabeledBy(android.view.View labeledBy, int virtualDescendantId) {
        // AOSP: Preconditions.checkNotNull(root, "%s must not be null", root), and
        // the id goes onto a LongArray with no duplicate check.
        if (labeledBy == null) throw new NullPointerException("null must not be null");
        mLabeledByList.add(new AccessibilityNodeInfo(labeledBy, virtualDescendantId));
    }
    public boolean removeLabeledBy(android.view.View label) {
        return removeLabeledBy(label, AccessibilityNodeProvider.HOST_VIEW_ID);
    }
    public boolean removeLabeledBy(android.view.View labeledBy, int virtualDescendantId) {
        if (labeledBy == null) return false;
        return mLabeledByList.remove(new AccessibilityNodeInfo(labeledBy, virtualDescendantId));
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AccessibilityNodeInfo other = (AccessibilityNodeInfo) o;
        return mSourceNodeId == other.mSourceNodeId && mWindowId == other.mWindowId;
    }
    @Override public int hashCode() {
        // AOSP: prime 31 over getAccessibilityViewId, getVirtualDescendantId, mWindowId.
        final int prime = 31;
        int result = 1;
        result = prime * result + getAccessibilityViewId(mSourceNodeId);
        result = prime * result + getVirtualDescendantId(mSourceNodeId);
        result = prime * result + mWindowId;
        return result;
    }

    // ---- Parcelable ---------------------------------------------------------
    // AOSP flattens ~60 fields plus the action list. A node's identity, though,
    // is its SOURCE (a live View plus a virtual id) and its parent/child links,
    // and neither survives a byte round trip in this tree -- there is no
    // accessibility-id registry to resolve them against, because no a11y service
    // is bridged. android/os/Parcel keeps the LIVE object when writeToParcel
    // writes nothing, so a node handed through a Bundle in this single process
    // comes back as the SAME node, which is the identity-preserving answer and
    // strictly better than a partially-rebuilt copy with dead links.
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(android.os.Parcel dest, int flags) { }

    public static final android.os.Parcelable.Creator<AccessibilityNodeInfo> CREATOR =
            new android.os.Parcelable.Creator<AccessibilityNodeInfo>() {
        @Override public AccessibilityNodeInfo createFromParcel(android.os.Parcel in) {
            return new AccessibilityNodeInfo();
        }
        @Override public AccessibilityNodeInfo[] newArray(int size) {
            return new AccessibilityNodeInfo[size];
        }
    };
}
