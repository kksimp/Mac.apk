// android.widget.Switch -- the slider-toggle widget. Subclass of
// CompoundButton. androidx.preference.SwitchPreferenceCompat falls
// back to this when androidx.appcompat.widget.SwitchCompat isn't
// available.
package android.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;

public class Switch extends CompoundButton {
    // AOSP stores these labels; they back getTextOn/Off so a host querying
    // the configured strings (e.g. for accessibility) reads them back.
    private CharSequence mTextOn = "";
    private CharSequence mTextOff = "";
    private boolean mShowText;
    private boolean mSplitTrack;
    private int mSwitchPadding;
    private int mThumbTextPadding;
    private android.graphics.drawable.Drawable mThumbDrawable;
    private android.graphics.drawable.Drawable mTrackDrawable;

    // AOSP Switch (android16-release core/java/android/widget/Switch.java:
    // 200-227) chains (Context)/(Context,AttributeSet) down to
    // this(context, attrs, com.android.internal.R.attr.switchStyle) --
    // 0x0101043f, verified against public-final.xml. Same CompoundButton gap
    // as CheckBox/RadioButton: a programmatic `new Switch(ctx)` used to get
    // defStyleAttr=0, i.e. no default style at all.
    private static final int SWITCH_STYLE = 0x0101043f;
    public Switch(Context context) { this(context, null); }
    public Switch(Context context, AttributeSet attrs) { this(context, attrs, SWITCH_STYLE); }
    public Switch(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        macapkReadSwitchStyle(context, attrs, defStyleAttr, 0);
    }
    public Switch(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr);
        macapkReadSwitchStyle(context, attrs, defStyleAttr, defStyleRes);
    }

    // ==================================================================
    // THE SWITCH ITSELF: measure, layout and draw (AOSP android14-release
    // core/java/android/widget/Switch.java onMeasure :1036-1082, onLayout
    // :1160-1205, draw :1207-1271, onDraw :1273-1330, getCompoundPadding*
    // :1350-1372). This class used to be accessors only -- no onMeasure, no
    // onDraw, no thumb or track read from the style -- so every framework
    // Switch measured to its (empty) text and painted NOTHING. MEASURED:
    // NoNonsense Notes' settings use androidx SwitchPreference, whose widget is
    // this class (@android:id/switch_widget, measured 0x19): "Clickable links",
    // "Bigger titles" and every other on/off row showed no toggle at all.
    //
    // Ported: the style read (thumb, track, switchMinWidth, switchPadding,
    // thumbTextPadding, showText, splitTrack, thumbTint, trackTint), the size
    // rule (thumb width = intrinsic minus its padding, switch width =
    // max(switchMinWidth, 2 * thumb + track padding)), the trailing-edge
    // placement under the vertical gravity, the compound padding that keeps
    // the label clear of the switch, and the track-then-thumb paint at the
    // checked position. NOT ported: the thumb drag gesture (a tap toggles
    // through CompoundButton.performClick, as before), the position animation,
    // the ON/OFF text on the thumb (Material sets showText=false), optical
    // insets and the splitTrack clip.
    //
    // When the style's thumb or track is missing or has no intrinsic size,
    // a Material-shaped stand-in is drawn instead (MacapkSwitchPart), the
    // precedent being Resources.MacapkCompoundButtonDrawable for the checkbox.
    // ==================================================================

    /** android.R.attr ids (public-final.xml), SORTED as a styleable is. */
    private static final int[] MACAPK_SWITCH_ATTRS = {
        0x01010124 /*textOn*/, 0x01010125 /*textOff*/, 0x01010142 /*thumb*/,
        0x0101036e /*switchTextAppearance*/, 0x0101036f /*track*/,
        0x01010370 /*switchMinWidth*/, 0x01010371 /*switchPadding*/,
        0x01010372 /*thumbTextPadding*/, 0x0101044c /*splitTrack*/,
        0x01010471 /*thumbTint*/, 0x01010472 /*thumbTintMode*/,
        0x010104ad /*showText*/, 0x010104d9 /*trackTint*/, 0x010104da /*trackTintMode*/ };

    private int mSwitchWidth, mSwitchHeight, mThumbWidth;
    private int mSwitchLeft, mSwitchTop, mSwitchRight, mSwitchBottom;
    private final android.graphics.Rect mTempRect = new android.graphics.Rect();

    private void macapkReadSwitchStyle(Context context, AttributeSet attrs,
                                       int defStyleAttr, int defStyleRes) {
        android.graphics.drawable.Drawable thumb = null, track = null;
        android.content.res.TypedArray a = null;
        try {
            a = context.obtainStyledAttributes(attrs, MACAPK_SWITCH_ATTRS, defStyleAttr, defStyleRes);
            try { thumb = a.getDrawable(2); } catch (Throwable ignored) {}
            try { track = a.getDrawable(4); } catch (Throwable ignored) {}
            CharSequence on = a.getText(0), off = a.getText(1);
            if (on != null) mTextOn = on;
            if (off != null) mTextOff = off;
            mShowText = a.getBoolean(11, true);
            mThumbTextPadding = a.getDimensionPixelSize(7, 0);
            mSwitchMinWidth = a.getDimensionPixelSize(5, 0);
            mSwitchPadding = a.getDimensionPixelSize(6, 0);
            mSplitTrack = a.getBoolean(8, false);
            try { if (a.hasValue(9)) mThumbTintList = a.getColorStateList(9); } catch (Throwable ignored) {}
            try { if (a.hasValue(12)) mTrackTintList = a.getColorStateList(12); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {
            // A style we cannot read must never break construction.
        } finally {
            if (a != null) try { a.recycle(); } catch (Throwable ignored) {}
        }
        final float d = macapkDensity();
        if (!macapkUsable(thumb)) thumb = new MacapkSwitchPart(context, d, true);
        // The Material thumb art is a WHITE disc that the platform's
        // switch_thumb_material_anim tints per state (?colorControlActivated
        // checked, ?colorSwitchThumbNormal otherwise, tintMode multiply). The
        // thumb that arrives here carries none of those tints (its items come
        // back untinted), so a checked switch showed a white thumb where a device
        // shows the accent. Re-apply that tint from the theme when neither the
        // style's thumbTint nor the drawable itself supplies one.
        if (mThumbTintList == null && !(thumb instanceof MacapkSwitchPart) && !macapkCarriesTint(thumb)) {
            mThumbTintList = macapkDefaultThumbTint(context);
            if (mThumbTintList != null) {
                thumb = thumb.mutate();
                thumb.setTintList(mThumbTintList);
                thumb.setTintMode(android.graphics.PorterDuff.Mode.MULTIPLY);
            }
        }
        if (!macapkUsable(track)) track = new MacapkSwitchPart(context, d, false);
        setThumbDrawable(thumb);
        setTrackDrawable(track);
        if (mThumbTintList != null) macapkTint(mThumbDrawable, mThumbTintList);
        if (mTrackTintList != null) macapkTint(mTrackDrawable, mTrackTintList);
    }

    private float macapkDensity() {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            if (dm != null && dm.density > 0f) return dm.density;
        } catch (Throwable ignored) {}
        return android.util.DisplayMetrics.macapkDeviceDensity();
    }

    /** A thumb/track we can lay out: present, with a real intrinsic size, and
     *  not the empty VectorDrawable stand-in getDrawable returns for a file it
     *  could not inflate. */
    private static boolean macapkUsable(android.graphics.drawable.Drawable dr) {
        if (dr == null) return false;
        if (dr instanceof android.graphics.drawable.VectorDrawable vd
                && (vd.macapkUnresolvedStub || vd.macapkPaintsNothing())) return false;
        return dr.getIntrinsicWidth() > 0 && dr.getIntrinsicHeight() > 0;
    }

    /** The drawable, or any state of a selector, already has a tint. */
    private static boolean macapkCarriesTint(android.graphics.drawable.Drawable dr) {
        if (dr == null) return false;
        if (dr.getTintList() != null) return true;
        if (dr instanceof android.graphics.drawable.StateListDrawable sl) {
            for (int i = 0, n = sl.getStateCount(); i < n; i++) {
                android.graphics.drawable.Drawable c = sl.getStateDrawable(i);
                if (c != null && c.getTintList() != null) return true;
            }
        }
        return false;
    }

    /** switch_thumb_material_anim's per-state tints, from the view's theme:
     *  disabled -> ?colorSwitchThumbNormal at ?disabledAlpha, checked ->
     *  ?colorControlActivated, otherwise ?colorSwitchThumbNormal. */
    private static android.content.res.ColorStateList macapkDefaultThumbTint(Context context) {
        try {
            android.content.res.Resources.Theme t = context.getTheme();
            if (t == null) return null;
            int accent = t.resolveAttrColor("android:colorControlActivated",
                    t.resolveAttrColor("colorControlActivated", 0));
            int normal = t.resolveAttrColor("android:colorSwitchThumbNormal",
                    t.resolveAttrColor("colorSwitchThumbNormal", 0xFFF1F1F1));
            if (accent == 0) return null;
            int disabled = (Math.round(((normal >>> 24) & 0xFF) * 0.3f) << 24) | (normal & 0x00FFFFFF);
            return new android.content.res.ColorStateList(
                new int[][] { { -0x0101009e /* -state_enabled */ }, { 0x010100a0 /* state_checked */ }, {} },
                new int[] { disabled, accent, normal });
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void macapkTint(android.graphics.drawable.Drawable dr,
                            android.content.res.ColorStateList tint) {
        if (dr == null || tint == null || dr instanceof MacapkSwitchPart) return;
        dr.setTintList(tint);
        if (dr.isStateful()) dr.setState(getDrawableState());
    }

    private void macapkAttach(android.graphics.drawable.Drawable dr) {
        if (dr == null) return;
        dr.setCallback(this);
        if (dr.isStateful()) dr.setState(getDrawableState());
    }

    @Override
    public void setChecked(boolean checked) {
        super.setChecked(checked);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final android.graphics.Rect padding = mTempRect;
        int thumbWidth = 0, thumbHeight = 0;
        if (mThumbDrawable != null) {
            padding.setEmpty();
            mThumbDrawable.getPadding(padding);
            thumbWidth = mThumbDrawable.getIntrinsicWidth() - padding.left - padding.right;
            thumbHeight = mThumbDrawable.getIntrinsicHeight();
        }
        mThumbWidth = Math.max(0, thumbWidth);
        int trackHeight = 0;
        padding.setEmpty();
        if (mTrackDrawable != null) {
            mTrackDrawable.getPadding(padding);
            trackHeight = mTrackDrawable.getIntrinsicHeight();
        }
        mSwitchWidth = Math.max(mSwitchMinWidth, 2 * mThumbWidth + padding.left + padding.right);
        mSwitchHeight = Math.max(trackHeight, thumbHeight);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (getMeasuredHeight() < mSwitchHeight) {
            setMeasuredDimension(getMeasuredWidthAndState(), mSwitchHeight);
        }
    }

    /** AOSP onLayout's switch rect: at the trailing padding edge, placed by the
     *  vertical gravity. Computed at draw as well, so a size change that skipped
     *  onLayout still paints in the right place. */
    private void macapkLayoutSwitch() {
        if (isLayoutRtl()) {
            mSwitchLeft = getPaddingLeft();
            mSwitchRight = mSwitchLeft + mSwitchWidth;
        } else {
            mSwitchRight = getWidth() - getPaddingRight();
            mSwitchLeft = mSwitchRight - mSwitchWidth;
        }
        switch (getGravity() & Gravity.VERTICAL_GRAVITY_MASK) {
            case Gravity.CENTER_VERTICAL:
                mSwitchTop = (getPaddingTop() + getHeight() - getPaddingBottom()) / 2 - mSwitchHeight / 2;
                mSwitchBottom = mSwitchTop + mSwitchHeight;
                break;
            case Gravity.BOTTOM:
                mSwitchBottom = getHeight() - getPaddingBottom();
                mSwitchTop = mSwitchBottom - mSwitchHeight;
                break;
            default:
                mSwitchTop = getPaddingTop();
                mSwitchBottom = mSwitchTop + mSwitchHeight;
                break;
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        macapkLayoutSwitch();
    }

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        super.onDraw(canvas);
        macapkLayoutSwitch();
        final android.graphics.Rect padding = mTempRect;
        padding.setEmpty();
        if (mTrackDrawable != null) mTrackDrawable.getPadding(padding);
        final int trackPadL = padding.left, trackPadR = padding.right;
        final float pos = isChecked() ? 1f : 0f;
        final float p = isLayoutRtl() ? 1f - pos : pos;
        final int range = Math.max(0, mSwitchWidth - mThumbWidth - trackPadL - trackPadR);
        final int thumbInitialLeft = mSwitchLeft + (int) (p * range + 0.5f) + trackPadL;
        if (mTrackDrawable != null) {
            mTrackDrawable.setBounds(mSwitchLeft, mSwitchTop, mSwitchRight, mSwitchBottom);
            mTrackDrawable.draw(canvas);
        }
        if (mThumbDrawable != null) {
            padding.setEmpty();
            mThumbDrawable.getPadding(padding);
            mThumbDrawable.setBounds(thumbInitialLeft - padding.left, mSwitchTop,
                    thumbInitialLeft + mThumbWidth + padding.right, mSwitchBottom);
            mThumbDrawable.draw(canvas);
        }
    }

    @Override
    public int getCompoundPaddingLeft() {
        if (!isLayoutRtl()) return super.getCompoundPaddingLeft();
        int padding = super.getCompoundPaddingLeft() + mSwitchWidth;
        if (!android.text.TextUtils.isEmpty(getText())) padding += mSwitchPadding;
        return padding;
    }

    @Override
    public int getCompoundPaddingRight() {
        if (isLayoutRtl()) return super.getCompoundPaddingRight();
        int padding = super.getCompoundPaddingRight() + mSwitchWidth;
        if (!android.text.TextUtils.isEmpty(getText())) padding += mSwitchPadding;
        return padding;
    }

    @Override
    public void drawableStateChanged() {
        super.drawableStateChanged();
        final int[] state = getDrawableState();
        boolean changed = false;
        if (mThumbDrawable != null && mThumbDrawable.isStateful()) changed |= mThumbDrawable.setState(state);
        if (mTrackDrawable != null && mTrackDrawable.isStateful()) changed |= mTrackDrawable.setState(state);
        if (changed) invalidate();
    }

    @Override
    protected boolean verifyDrawable(android.graphics.drawable.Drawable who) {
        return super.verifyDrawable(who) || who == mThumbDrawable || who == mTrackDrawable;
    }

    @Override
    public void jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState();
        if (mThumbDrawable != null) mThumbDrawable.jumpToCurrentState();
        if (mTrackDrawable != null) mTrackDrawable.jumpToCurrentState();
    }

    /** The Material switch's thumb or track, drawn from the theme: a 20dp thumb
     *  disc, ?colorControlActivated when checked and ?colorSwitchThumbNormal
     *  (material_grey_50 / grey_400) when not, over a 14dp rounded track tinted
     *  the same colours at 30% (AOSP switch_track_mtrl_alpha bakes that alpha
     *  in). The box is AOSP's 9-patch geometry: the thumb's 20dp disc sits in a
     *  24dp box (2dp margin for its edge), the track spans the switch with 3dp
     *  side insets. Colours resolve at DRAW, against the view's theme. */
    static final class MacapkSwitchPart extends android.graphics.drawable.Drawable {
        private static final int STATE_ENABLED = 0x0101009e;
        private static final int STATE_CHECKED = 0x010100a0;
        private final Context ctx;
        private final float d;
        private final boolean thumb;
        private final android.graphics.Paint paint = new android.graphics.Paint();
        private int alpha = 255;

        MacapkSwitchPart(Context ctx, float density, boolean thumb) {
            this.ctx = ctx;
            this.d = density;
            this.thumb = thumb;
            paint.setAntiAlias(true);
        }

        private boolean has(int s) {
            final int[] st = getState();
            if (st != null) for (int v : st) if (v == s) return true;
            return false;
        }
        private boolean enabled() {
            final int[] st = getState();
            return st == null || st.length == 0 || has(STATE_ENABLED);
        }

        private int themeColor(String attr, int fallback) {
            try {
                android.content.res.Resources.Theme t = ctx != null ? ctx.getTheme() : null;
                if (t != null) return t.resolveAttrColor(attr, fallback);
            } catch (Throwable ignored) {}
            return android.content.res.Resources.macapkThemeColor(attr, fallback);
        }

        private boolean dark() {
            try {
                int fg = themeColor("android:colorForeground", 0xFF000000);
                int r = (fg >> 16) & 0xFF, g = (fg >> 8) & 0xFF, b = fg & 0xFF;
                return (r + g + b) > 3 * 128;   // a light foreground = a dark theme
            } catch (Throwable t) { return false; }
        }

        @Override public void draw(android.graphics.Canvas canvas) {
            final android.graphics.Rect b = getBounds();
            if (b == null || b.isEmpty()) return;
            final boolean on = has(STATE_CHECKED);
            final int accent = themeColor("colorControlActivated",
                    themeColor("android:colorControlActivated", 0xFF009688));
            int c;
            if (thumb) {
                c = on ? accent : themeColor("colorSwitchThumbNormal",
                        dark() ? 0xFFBDBDBD : 0xFFF1F1F1);
            } else {
                int base = on ? accent : themeColor("android:colorForeground", 0xFF000000);
                c = (Math.round(((base >>> 24) & 0xFF) * 0.3f) << 24) | (base & 0x00FFFFFF);
            }
            int a = (c >>> 24) & 0xFF;
            if (!enabled()) a = a / 2;
            a = a * alpha / 255;
            final float cx = b.exactCenterX(), cy = b.exactCenterY();
            if (thumb) {
                final float r = Math.min(10f * d, Math.min(b.width(), b.height()) / 2f);
                // A soft edge, as the 9-patch's shadow gives the disc.
                paint.setStyle(android.graphics.Paint.Style.FILL);
                paint.setColor(0x24000000);
                paint.setAlpha(0x24 * alpha / 255);
                canvas.drawCircle(cx, cy + 0.75f * d, r + 0.75f * d, paint);
                paint.setColor(c);
                paint.setAlpha(a);
                canvas.drawCircle(cx, cy, r, paint);
            } else {
                final float h = 14f * d, inset = 3f * d;
                final android.graphics.RectF rr = new android.graphics.RectF(
                        b.left + inset, cy - h / 2f, b.right - inset, cy + h / 2f);
                paint.setStyle(android.graphics.Paint.Style.FILL);
                paint.setColor(c);
                paint.setAlpha(a);
                canvas.drawRoundRect(rr, h / 2f, h / 2f, paint);
            }
        }

        @Override public int getIntrinsicWidth()  { return Math.round((thumb ? 24f : 40f) * d); }
        @Override public int getIntrinsicHeight() { return Math.round((thumb ? 24f : 20f) * d); }
        @Override public boolean isStateful() { return true; }
        @Override public boolean onStateChange(int[] state) { invalidateSelf(); return true; }
        @Override public void setAlpha(int a) { this.alpha = a; invalidateSelf(); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    public void setTextOn(CharSequence textOn) { mTextOn = textOn; }
    public CharSequence getTextOn() { return mTextOn; }
    public void setTextOff(CharSequence textOff) { mTextOff = textOff; }
    public CharSequence getTextOff() { return mTextOff; }
    public void setShowText(boolean showText) { mShowText = showText; }
    public boolean getShowText() { return mShowText; }
    public void setSplitTrack(boolean splitTrack) { mSplitTrack = splitTrack; }
    public boolean getSplitTrack() { return mSplitTrack; }
    public void setSwitchPadding(int pixels) { mSwitchPadding = pixels; }
    public int  getSwitchPadding() { return mSwitchPadding; }
    public void setThumbTextPadding(int pixels) { mThumbTextPadding = pixels; }
    public int  getThumbTextPadding() { return mThumbTextPadding; }
    // AOSP Switch.setSwitchMinWidth/getSwitchMinWidth was `{}` / hardcoded 0
    // with no backing field -- half-implemented-accessor shape, found by the
    // same grep that caught the tint-list pair below.
    private int mSwitchMinWidth;
    public void setSwitchMinWidth(int pixels) { mSwitchMinWidth = pixels; }
    public int  getSwitchMinWidth() { return mSwitchMinWidth; }
    public void setThumbDrawable(android.graphics.drawable.Drawable thumb) {
        if (mThumbDrawable != null && mThumbDrawable != thumb) mThumbDrawable.setCallback(null);
        mThumbDrawable = thumb;
        macapkAttach(thumb);
        requestLayout();
    }
    // AOSP Switch.setThumbResource(int) resolves the resource through the
    // Context and delegates to setThumbDrawable, same as CompoundButton.
    // setButtonDrawable(int) elsewhere in this package. Was a pure no-op, so
    // getThumbDrawable() stayed stale/null after a resource-id call.
    public void setThumbResource(int resId) {
        setThumbDrawable(resId != 0 ? getContext().getDrawable(resId) : null);
    }
    public void setTrackDrawable(android.graphics.drawable.Drawable track) {
        if (mTrackDrawable != null && mTrackDrawable != track) mTrackDrawable.setCallback(null);
        mTrackDrawable = track;
        macapkAttach(track);
        requestLayout();
    }
    public void setTrackResource(int resId) {
        setTrackDrawable(resId != 0 ? getContext().getDrawable(resId) : null);
    }
    public android.graphics.drawable.Drawable getThumbDrawable() { return mThumbDrawable; }
    public android.graphics.drawable.Drawable getTrackDrawable() { return mTrackDrawable; }
    // AOSP Switch.setThumbTintList/getThumbTintList and setTrackTintList/
    // getTrackTintList (API 21+) were `{}` / hardcoded null with no backing
    // field -- the exact ProgressBar-drawable-accessor shape, sitting right
    // next to getThumbDrawable/getTrackDrawable above which already round-
    // trip correctly through real fields.
    private android.content.res.ColorStateList mThumbTintList;
    private android.content.res.ColorStateList mTrackTintList;
    public void setThumbTintList(android.content.res.ColorStateList tint) { mThumbTintList = tint; }
    public android.content.res.ColorStateList getThumbTintList() { return mThumbTintList; }
    public void setTrackTintList(android.content.res.ColorStateList tint) { mTrackTintList = tint; }
    public android.content.res.ColorStateList getTrackTintList() { return mTrackTintList; }
    public void setSwitchTypeface(android.graphics.Typeface tf) {}

    /** AOSP Switch.setSwitchTextAppearance (frameworks/base/.../widget/Switch.java):
     *  reads a TextAppearance style resource and applies its textColor / textSize /
     *  typeface / textAllCaps to the switch's OWN internally-painted "ON"/"OFF"
     *  track labels (mTextColors, mTextPaint, mSwitchTransformationMethod --
     *  fields that back Switch's custom onDraw, not any CompoundButton/TextView
     *  text). This Switch, like the rest of this file (setThumbDrawable/
     *  setTrackDrawable/the tint-list pairs above), is a drawable-and-tint compat
     *  shim: it has no onDraw override and no mTextPaint/mTextColors of its own,
     *  so there is no pixel path for a text appearance to affect -- the same
     *  reason setSwitchTypeface() just above is a no-op. Kept as a real (if
     *  inert) method so PreferenceCompat / Material callers that configure
     *  android:switchTextAppearance resolve at link time instead of crashing. */
    public void setSwitchTextAppearance(Context context, int resid) {}

    /** AOSP Switch.onCreateDrawableState (android11-release Switch.java:
     *  1445-1451) merges CHECKED_STATE_SET AGAIN, on top of the merge the
     *  CompoundButton superclass already performed -- so a checked Switch's
     *  drawable state carries state_checked TWICE on real Android (the
     *  fwprobe golden reads `checked,checked,enabled,multiline`). StateSet
     *  matching is unaffected by the duplicate; it is mirrored because the
     *  raw array is observable via getDrawableState(). */
    private static final int[] MACAPK_CHECKED_STATE_SET = { 0x010100a0 };

    @Override
    protected int[] onCreateDrawableState(int extraSpace) {
        final int[] drawableState = super.onCreateDrawableState(extraSpace + 1);
        if (isChecked()) {
            android.view.View.mergeDrawableStates(drawableState, MACAPK_CHECKED_STATE_SET);
        }
        return drawableState;
    }

    // ==================================================================
    // API-36 surface completion (apidiff: android.widget.Switch had 11 public
    // members missing -- the Icon setters, both tint MODE families, and the
    // styled setSwitchTypeface). AOSP:
    // frameworks/base/core/java/android/widget/Switch.java (android16-release).
    // ==================================================================

    /** AOSP :764 / :583 -- verbatim. */
    public void setThumbIcon(android.graphics.drawable.Icon icon) {
        setThumbDrawable(icon == null ? null : icon.loadDrawable(getContext()));
    }
    public void setTrackIcon(android.graphics.drawable.Icon icon) {
        setTrackDrawable(icon == null ? null : icon.loadDrawable(getContext()));
    }

    // Tint modes. Both families were missing entirely here (neither the
    // PorterDuff.Mode nor the BlendMode form existed), unlike the tint LISTS
    // above. AOSP keeps one BlendMode per tint and converts; we do the same,
    // and additionally keep the PorterDuff.Mode because that is the form this
    // tree's Drawable.setTintMode takes.
    private android.graphics.PorterDuff.Mode mThumbTintMode;
    private android.graphics.PorterDuff.Mode mTrackTintMode;
    private android.graphics.BlendMode mThumbBlendMode;
    private android.graphics.BlendMode mTrackBlendMode;

    public void setThumbTintMode(android.graphics.PorterDuff.Mode tintMode) {
        mThumbTintMode = tintMode;
        mThumbBlendMode = MacApkBlendModes.fromPorterDuff(tintMode);
        if (mThumbDrawable != null) mThumbDrawable.setTintMode(tintMode);
    }
    public android.graphics.PorterDuff.Mode getThumbTintMode() { return mThumbTintMode; }
    public void setThumbTintBlendMode(android.graphics.BlendMode blendMode) {
        mThumbBlendMode = blendMode;
        mThumbTintMode = MacApkBlendModes.toPorterDuff(blendMode);
        if (mThumbDrawable != null) mThumbDrawable.setTintMode(mThumbTintMode);
    }
    public android.graphics.BlendMode getThumbTintBlendMode() { return mThumbBlendMode; }

    public void setTrackTintMode(android.graphics.PorterDuff.Mode tintMode) {
        mTrackTintMode = tintMode;
        mTrackBlendMode = MacApkBlendModes.fromPorterDuff(tintMode);
        if (mTrackDrawable != null) mTrackDrawable.setTintMode(tintMode);
    }
    public android.graphics.PorterDuff.Mode getTrackTintMode() { return mTrackTintMode; }
    public void setTrackTintBlendMode(android.graphics.BlendMode blendMode) {
        mTrackBlendMode = blendMode;
        mTrackTintMode = MacApkBlendModes.toPorterDuff(blendMode);
        if (mTrackDrawable != null) mTrackDrawable.setTintMode(mTrackTintMode);
    }
    public android.graphics.BlendMode getTrackTintBlendMode() { return mTrackBlendMode; }

    /** AOSP :411 setSwitchTypeface(Typeface, int). The FIRST half -- resolving
     *  the requested style onto a concrete Typeface -- is reproduced exactly.
     *  The second half sets fake-bold / skew on Switch's own mTextPaint, the
     *  paint that draws the internal ON/OFF track labels; this port has no
     *  such paint and no Switch.onDraw (see setSwitchTextAppearance above for
     *  the same reason), so there is nothing for those two calls to reach. */
    public void setSwitchTypeface(android.graphics.Typeface tf, int style) {
        if (style > 0) {
            if (tf == null) {
                tf = android.graphics.Typeface.defaultFromStyle(style);
            } else {
                tf = android.graphics.Typeface.create(tf, style);
            }
        }
        setSwitchTypeface(tf);
    }
}
