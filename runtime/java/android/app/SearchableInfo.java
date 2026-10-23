package android.app;
import android.content.ComponentName;
/** Metadata describing a searchable activity, parsed from the app's
 *  `android.app.searchable` XML (<searchable android:label android:hint
 *  android:searchSuggestAuthority ...>). A value holder read by SearchView and
 *  the search dialog. Source: frameworks/base/core/java/android/app/
 *  SearchableInfo.java (android14-release), whose private XML constructor
 *  (:666-722) and getActivityMetaData (:780-830) are mirrored by macapkParse.
 *
 *  This class used to hold no state and SearchManager.getSearchableInfo
 *  always answered null, so an app's SearchView had no search activity to
 *  launch: AppCompat's SearchView.onSubmitQuery only launches the query intent
 *  when mSearchable != null, and NoNonsense Notes' search box (the toolbar
 *  search on its main screen) accepted text and did nothing on Enter. A
 *  default-constructed instance still answers AOSP's defaults for absent
 *  attributes. */
public class SearchableInfo implements android.os.Parcelable {
    // AOSP SearchableInfo constants (searchMode / voiceSearchMode bits).
    private static final int SEARCH_MODE_BADGE_LABEL = 0x04;
    private static final int SEARCH_MODE_BADGE_ICON = 0x08;
    private static final int SEARCH_MODE_QUERY_REWRITE_FROM_DATA = 0x10;
    private static final int SEARCH_MODE_QUERY_REWRITE_FROM_TEXT = 0x20;
    private static final int VOICE_SEARCH_SHOW_BUTTON = 1;
    private static final int VOICE_SEARCH_LAUNCH_WEB_SEARCH = 2;
    private static final int VOICE_SEARCH_LAUNCH_RECOGNIZER = 4;
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    private ComponentName mSearchActivity;
    private int mLabelId, mHintId, mIconId, mSearchButtonText, mSettingsDescriptionId;
    private int mSearchMode;
    private int mSearchInputType = android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_NORMAL;
    private int mSearchImeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO;
    private boolean mIncludeInGlobalSearch, mQueryAfterZeroResults, mAutoUrlDetect;
    private String mSuggestAuthority, mSuggestPath, mSuggestSelection,
            mSuggestIntentAction, mSuggestIntentData, mSuggestProviderPackage;
    private int mSuggestThreshold;
    private int mVoiceSearchMode, mVoiceLanguageModeId, mVoicePromptTextId,
            mVoiceLanguageId, mVoiceMaxResults;

    public SearchableInfo() {}

    /** AOSP getActivityMetaData(Context, XmlPullParser, ComponentName): the
     *  first <searchable> element of `xml`, read with the activity's own
     *  Context; null when the file has none or declares no label (AOSP throws
     *  "Search label must be a resource reference." there and the caller
     *  logs and drops the activity). */
    static SearchableInfo macapkParse(android.content.Context ctx,
                                      android.content.res.XmlResourceParser xml,
                                      ComponentName cName) {
        if (ctx == null || xml == null) return null;
        try {
            int type;
            while ((type = xml.next()) != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (type == org.xmlpull.v1.XmlPullParser.START_TAG
                        && "searchable".equals(xml.getName())) {
                    SearchableInfo s = new SearchableInfo();
                    s.mSearchActivity = cName;
                    s.macapkRead(ctx, xml);
                    return s.mLabelId != 0 ? s : null;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try { xml.close(); } catch (Throwable ignored) {}
        }
        return null;
    }

    private void macapkRead(android.content.Context ctx, android.util.AttributeSet a) {
        mSearchMode = intAttr(a, "searchMode", 0);
        mLabelId = a.getAttributeResourceValue(ANDROID_NS, "label", 0);
        mHintId = a.getAttributeResourceValue(ANDROID_NS, "hint", 0);
        mIconId = a.getAttributeResourceValue(ANDROID_NS, "icon", 0);
        mSearchButtonText = a.getAttributeResourceValue(ANDROID_NS, "searchButtonText", 0);
        mSearchInputType = intAttr(a, "inputType", mSearchInputType);
        mSearchImeOptions = intAttr(a, "imeOptions", mSearchImeOptions);
        mIncludeInGlobalSearch = a.getAttributeBooleanValue(ANDROID_NS, "includeInGlobalSearch", false);
        mQueryAfterZeroResults = a.getAttributeBooleanValue(ANDROID_NS, "queryAfterZeroResults", false);
        mAutoUrlDetect = a.getAttributeBooleanValue(ANDROID_NS, "autoUrlDetect", false);
        mSettingsDescriptionId = a.getAttributeResourceValue(ANDROID_NS, "searchSettingsDescription", 0);
        mSuggestAuthority = strAttr(ctx, a, "searchSuggestAuthority");
        mSuggestPath = strAttr(ctx, a, "searchSuggestPath");
        mSuggestSelection = strAttr(ctx, a, "searchSuggestSelection");
        mSuggestIntentAction = strAttr(ctx, a, "searchSuggestIntentAction");
        mSuggestIntentData = strAttr(ctx, a, "searchSuggestIntentData");
        mSuggestThreshold = intAttr(a, "searchSuggestThreshold", 0);
        mVoiceSearchMode = intAttr(a, "voiceSearchMode", 0);
        mVoiceLanguageModeId = a.getAttributeResourceValue(ANDROID_NS, "voiceLanguageModel", 0);
        mVoicePromptTextId = a.getAttributeResourceValue(ANDROID_NS, "voicePromptText", 0);
        mVoiceLanguageId = a.getAttributeResourceValue(ANDROID_NS, "voiceLanguage", 0);
        mVoiceMaxResults = intAttr(a, "voiceMaxResults", 0);
        // AOSP :709-716: the suggestion provider's package.
        if (mSuggestAuthority != null) {
            try {
                android.content.pm.ProviderInfo pi = ctx.getPackageManager()
                        .resolveContentProvider(mSuggestAuthority, 0);
                if (pi != null) mSuggestProviderPackage = pi.packageName;
            } catch (Throwable ignored) {}
        }
    }

    /** An int / enum / flag attribute: the compiled integer (decimal or hex). */
    private static int intAttr(android.util.AttributeSet a, String name, int def) {
        String v = a.getAttributeValue(ANDROID_NS, name);
        if (v == null || v.trim().isEmpty()) return def;
        v = v.trim();
        try {
            if (v.startsWith("0x") || v.startsWith("0X")) return (int) Long.parseLong(v.substring(2), 16);
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return a.getAttributeIntValue(ANDROID_NS, name, def);
        }
    }

    /** A string attribute, following a string resource reference. */
    private static String strAttr(android.content.Context ctx, android.util.AttributeSet a, String name) {
        String v = a.getAttributeValue(ANDROID_NS, name);
        if (v == null) return null;
        if (v.startsWith("@")) {
            int id = a.getAttributeResourceValue(ANDROID_NS, name, 0);
            if (id != 0) {
                try { return ctx.getResources().getString(id); } catch (Throwable ignored) {}
            }
        }
        return v;
    }

    public ComponentName getSearchActivity() { return mSearchActivity; }
    /** AOSP getLabelId / getHintId / getIconId / getSearchButtonText. */
    public int getLabelId() { return mLabelId; }
    public int getHintId() { return mHintId; }
    public int getIconId() { return mIconId; }
    public int getSearchButtonText() { return mSearchButtonText; }
    public boolean useBadgeLabel() { return (mSearchMode & SEARCH_MODE_BADGE_LABEL) != 0; }
    public boolean useBadgeIcon() { return (mSearchMode & SEARCH_MODE_BADGE_ICON) != 0 && mIconId != 0; }
    public int getInputType() { return mSearchInputType; }
    public int getImeOptions() { return mSearchImeOptions; }
    public boolean shouldRewriteQueryFromData() { return (mSearchMode & SEARCH_MODE_QUERY_REWRITE_FROM_DATA) != 0; }
    public boolean shouldRewriteQueryFromText() { return (mSearchMode & SEARCH_MODE_QUERY_REWRITE_FROM_TEXT) != 0; }
    public String getSuggestAuthority() { return mSuggestAuthority; }
    public String getSuggestPath() { return mSuggestPath; }
    public String getSuggestSelection() { return mSuggestSelection; }
    public String getSuggestIntentAction() { return mSuggestIntentAction; }
    public String getSuggestIntentData() { return mSuggestIntentData; }
    public int getSuggestThreshold() { return mSuggestThreshold; }
    public boolean getVoiceSearchEnabled() { return (mVoiceSearchMode & VOICE_SEARCH_SHOW_BUTTON) != 0; }
    public boolean getVoiceSearchLaunchWebSearch() { return (mVoiceSearchMode & VOICE_SEARCH_LAUNCH_WEB_SEARCH) != 0; }
    public boolean getVoiceSearchLaunchRecognizer() { return (mVoiceSearchMode & VOICE_SEARCH_LAUNCH_RECOGNIZER) != 0; }
    public int getVoiceLanguageModeId() { return mVoiceLanguageModeId; }
    public int getVoicePromptTextId() { return mVoicePromptTextId; }
    public int getVoiceLanguageId() { return mVoiceLanguageId; }
    public int getVoiceMaxResults() { return mVoiceMaxResults; }
    public boolean getVoiceSearchLaunchWebSearchEnabled() { return getVoiceSearchLaunchWebSearch(); }
    public boolean autoUrlDetect() { return mAutoUrlDetect; }

    /** AOSP getSuggestPackage(): "the name of the package where the suggestion
     *  provider lives, or null." */
    public String getSuggestPackage() { return mSuggestProviderPackage; }

    /** AOSP getSettingsDescriptionId() (android:searchSettingsDescription). */
    public int getSettingsDescriptionId() { return mSettingsDescriptionId; }

    /** AOSP shouldIncludeInGlobalSearch(): android:includeInGlobalSearch. */
    public boolean shouldIncludeInGlobalSearch() { return mIncludeInGlobalSearch; }

    /** AOSP queryAfterZeroResults(): android:queryAfterZeroResults. */
    public boolean queryAfterZeroResults() { return mQueryAfterZeroResults; }

    @Override public int describeContents() { return 0; }

    /** AOSP writeToParcel (:874-900) order, minus the action keys (not parsed). */
    @Override public void writeToParcel(android.os.Parcel dest, int flags) {
        dest.writeString(mSearchActivity != null ? mSearchActivity.flattenToString() : null);
        dest.writeInt(mLabelId); dest.writeInt(mHintId); dest.writeInt(mIconId);
        dest.writeInt(mSearchButtonText); dest.writeInt(mSearchMode);
        dest.writeInt(mSearchInputType); dest.writeInt(mSearchImeOptions);
        dest.writeInt(mIncludeInGlobalSearch ? 1 : 0);
        dest.writeInt(mQueryAfterZeroResults ? 1 : 0);
        dest.writeInt(mAutoUrlDetect ? 1 : 0);
        dest.writeInt(mSettingsDescriptionId);
        dest.writeString(mSuggestAuthority); dest.writeString(mSuggestPath);
        dest.writeString(mSuggestSelection); dest.writeString(mSuggestIntentAction);
        dest.writeString(mSuggestIntentData); dest.writeInt(mSuggestThreshold);
        dest.writeString(mSuggestProviderPackage);
        dest.writeInt(mVoiceSearchMode); dest.writeInt(mVoiceLanguageModeId);
        dest.writeInt(mVoicePromptTextId); dest.writeInt(mVoiceLanguageId);
        dest.writeInt(mVoiceMaxResults);
    }

    public static final android.os.Parcelable.Creator<SearchableInfo> CREATOR =
            new android.os.Parcelable.Creator<SearchableInfo>() {
        @Override public SearchableInfo createFromParcel(android.os.Parcel in) {
            SearchableInfo s = new SearchableInfo();
            String c = in.readString();
            s.mSearchActivity = c != null ? ComponentName.unflattenFromString(c) : null;
            s.mLabelId = in.readInt(); s.mHintId = in.readInt(); s.mIconId = in.readInt();
            s.mSearchButtonText = in.readInt(); s.mSearchMode = in.readInt();
            s.mSearchInputType = in.readInt(); s.mSearchImeOptions = in.readInt();
            s.mIncludeInGlobalSearch = in.readInt() != 0;
            s.mQueryAfterZeroResults = in.readInt() != 0;
            s.mAutoUrlDetect = in.readInt() != 0;
            s.mSettingsDescriptionId = in.readInt();
            s.mSuggestAuthority = in.readString(); s.mSuggestPath = in.readString();
            s.mSuggestSelection = in.readString(); s.mSuggestIntentAction = in.readString();
            s.mSuggestIntentData = in.readString(); s.mSuggestThreshold = in.readInt();
            s.mSuggestProviderPackage = in.readString();
            s.mVoiceSearchMode = in.readInt(); s.mVoiceLanguageModeId = in.readInt();
            s.mVoicePromptTextId = in.readInt(); s.mVoiceLanguageId = in.readInt();
            s.mVoiceMaxResults = in.readInt();
            return s;
        }
        @Override public SearchableInfo[] newArray(int size) { return new SearchableInfo[size]; }
    };
}
