// The probes: layouts inflated, measured and laid out with nothing attached,
// and every view's place written as text. The same class runs inside Win.apk
// (ProbeActivity, in the probe APK) and on the real AOSP framework under
// Robolectric (oracle/ProbeOracleTest); compare.py lines the two up. A line
// that differs is a place where Win.apk's framework is not Android's.
package dev.winapk.probe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.text.Layout;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

public final class Probes {
    private Probes() {}

    /** The screen every case is laid out on, in dp. */
    static final int W_DP = 360, H_DP = 640;

    private static final int[] LAYOUTS = {
        R.layout.text_gravity, R.layout.linear_weights, R.layout.frame_gravity, R.layout.relative,
        R.layout.edit_compound, R.layout.custom_params, R.layout.icons,
        R.layout.lib_material, R.layout.lib_constraint, R.layout.lib_appbar,
        R.layout.lib_text_lines, R.layout.lib_images, R.layout.lib_list, R.layout.lib_dialog,
        R.layout.lib_material, R.layout.lib_appbar, R.layout.lib_list, R.layout.lib_dialog,
        R.layout.lib_material, R.layout.lib_appbar, R.layout.lib_list, R.layout.lib_dialog,
        R.layout.lib_material, R.layout.lib_dialog,
    };
    private static final String[] NAMES = {
        "text_gravity", "linear_weights", "frame_gravity", "relative", "edit_compound", "custom_params", "icons",
        "lib_material", "lib_constraint", "lib_appbar",
        "lib_text_lines", "lib_images", "lib_list", "lib_dialog",
        "dark_material", "dark_appbar", "dark_list", "dark_dialog",
        "m3_material", "m3_appbar", "m3_list", "m3_dialog",
        "m3dark_material", "m3dark_dialog",
    };

    /** The platform's theme for the framework's own widgets, Material Components for the libraries'. */
    private static int themeOf(String name) {
        if (name.startsWith("dark_")) return R.style.ProbeLibDark;
        if (name.startsWith("m3dark_")) return R.style.ProbeM3Dark;
        if (name.startsWith("m3_")) return R.style.ProbeM3;
        return name.startsWith("lib_") ? R.style.ProbeLibTheme : R.style.ProbeTheme;
    }

    /** A dialog's content is inflated with the theme's alertDialogTheme, as AlertDialog does. */
    private static Context dialogContext(Context c) {
        android.util.TypedValue v = new android.util.TypedValue();
        int attr = c.getResources().getIdentifier("alertDialogTheme", "attr", c.getPackageName());
        if (attr != 0 && c.getTheme().resolveAttribute(attr, v, true) && v.resourceId != 0) {
            return new ContextThemeWrapper(c, v.resourceId);
        }
        return c;
    }

    /** What code does after inflating: a list's rows, a dialog's words. */
    private static void fill(View root, Context c) {
        int[] lists = {R.id.recycler, R.id.recycler_grid, R.id.recycler_strip};
        for (int i = 0; i < lists.length; i++) {
            View v = root.findViewById(lists[i]);
            if (!(v instanceof androidx.recyclerview.widget.RecyclerView)) continue;
            androidx.recyclerview.widget.RecyclerView list = (androidx.recyclerview.widget.RecyclerView) v;
            final int layout = i == 0 ? R.layout.row_item : R.layout.row_cell;
            final int count = i == 0 ? 12 : i == 1 ? 6 : 8;
            final boolean strip = i == 2;
            final float d = c.getResources().getDisplayMetrics().density;
            if (i == 0) {
                list.addItemDecoration(new androidx.recyclerview.widget.DividerItemDecoration(
                    c, androidx.recyclerview.widget.DividerItemDecoration.VERTICAL));
            }
            list.setAdapter(new androidx.recyclerview.widget.RecyclerView.Adapter<Row>() {
                @Override public Row onCreateViewHolder(ViewGroup parent, int type) {
                    View row = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
                    if (strip) row.getLayoutParams().width = Math.round(96 * d);
                    return new Row(row);
                }
                @Override public void onBindViewHolder(Row row, int position) {
                    String[] titles = {"\u05e9\u05dc\u05d5\u05dd \u05e2\u05d5\u05dc\u05dd", "Station",
                                       "\u05d0\u05d1\u05d5 \u05d9\u05d0\u05d9\u05e8 Vs 2026"};
                    ((TextView) row.itemView.findViewById(R.id.row_title)).setText(titles[position % 3] + " " + position);
                    TextView detail = row.itemView.findViewById(R.id.row_detail);
                    if (detail != null) {
                        detail.setText(position % 2 == 0 ? R.string.he_long : R.string.en_short);
                    }
                    TextView end = row.itemView.findViewById(R.id.row_end);
                    if (end != null) end.setText(position % 4 == 0 ? "12:3" + position % 10 : "");
                }
                @Override public int getItemCount() { return count; }
            });
        }
        TextView title = root.findViewById(androidx.appcompat.R.id.alertTitle);
        if (title != null) {
            title.setText(R.string.dialog_title);
            TextView message = root.findViewById(android.R.id.message);
            if (message != null) message.setText(R.string.dialog_message);
            int[] buttons = {android.R.id.button1, android.R.id.button2, android.R.id.button3};
            int[] words = {R.string.dialog_yes, R.string.dialog_no, 0};
            for (int i = 0; i < buttons.length; i++) {
                View b = root.findViewById(buttons[i]);
                if (!(b instanceof TextView)) continue;
                if (words[i] == 0) { b.setVisibility(View.GONE); continue; }
                ((TextView) b).setText(words[i]);
                b.setVisibility(View.VISIBLE);
            }
            // AlertController hides what the dialog does not use.
            int[] unused = {androidx.appcompat.R.id.customPanel, android.R.id.icon,
                            androidx.appcompat.R.id.titleDividerNoCustom};
            for (int id : unused) {
                View u = root.findViewById(id);
                if (u != null) u.setVisibility(View.GONE);
            }
        }
    }

    private static final class Row extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
        Row(View v) { super(v); }
    }

    /** runAll for a caller that must not name an Android class: the oracle's test class is
     *  loaded outside Robolectric's sandbox first, where there is no android.content.Context. */
    public static List<String> runAllOn(Object app, File pictures) {
        return runAll((Context) app, pictures);
    }

    /** Every case, left to right and right to left, as lines of text. */
    public static List<String> runAll(Context app, File pictures) {
        List<String> out = new ArrayList<>();
        float density = app.getResources().getDisplayMetrics().density;
        out.add("density " + density);
        int w = Math.round(W_DP * density), h = Math.round(H_DP * density);
        themeValues(app, out);
        textLayouts(app, out);
        defaultStyles(app, out);
        drawables(app, out);
        for (int i = 0; i < LAYOUTS.length; i++) {
            for (int dir = 0; dir < 2; dir++) {
                String name = NAMES[i] + (dir == 0 ? ".ltr" : ".rtl");
                out.add("case " + name);
                try {
                    Context c = new ContextThemeWrapper(app, themeOf(NAMES[i]));
                    if (NAMES[i].endsWith("_dialog")) c = dialogContext(c);
                    FrameLayout root = new FrameLayout(c);
                    root.setLayoutDirection(dir == 0 ? View.LAYOUT_DIRECTION_LTR : View.LAYOUT_DIRECTION_RTL);
                    LayoutInflater.from(c).inflate(LAYOUTS[i], root, true);
                    fill(root, c);
                    root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                                 View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
                    root.layout(0, 0, w, h);
                    // What was drawn: the case on a white bitmap, each view's patch of it as a
                    // few average colours beside its place (a wrong tint, a missing icon, a
                    // black box show there), and the whole picture as a PNG for looking at.
                    Bitmap picture = null;
                    try {
                        picture = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        Canvas canvas = new Canvas(picture);
                        canvas.drawColor(0xFFFFFFFF);
                        root.draw(canvas);
                        if (pictures != null) {
                            pictures.mkdirs();
                            try (FileOutputStream f = new FileOutputStream(new File(pictures, name + ".png"))) {
                                picture.compress(Bitmap.CompressFormat.PNG, 100, f);
                            }
                        }
                    } catch (Throwable t) {
                        out.add("  NOT DRAWN " + t);
                        picture = null;
                    }
                    dump(root.getChildAt(0), 0, 0, 0, density, out, c, picture);
                } catch (Throwable t) {
                    out.add("  FAILED " + t);
                }
            }
        }
        return out;
    }

    /** What a widget's constructor reads from its default style: for each default-style
     *  attribute of the libraries' theme, the attributes widgets ask for, as
     *  obtainStyledAttributes(null, {attr}, defStyleAttr, 0) answers them. A value that is a
     *  reference to another theme attribute, to a drawable or to a colour list must come
     *  out as on Android, or the widget takes its fallback and looks wrong everywhere. */
    private static void defaultStyles(Context app, List<String> out) {
        Context c = new ContextThemeWrapper(app, R.style.ProbeLibTheme);
        String[][] reads = {
            {"checkboxStyle", "buttonCompat", "android:button", "buttonTint", "android:background", "android:minHeight"},
            {"radioButtonStyle", "buttonCompat", "android:button", "buttonTint", "android:background"},
            {"switchStyle", "android:thumb", "track", "thumbTint", "trackTint", "switchMinWidth", "switchPadding",
             "android:textOn", "showText", "thumbTextPadding"},
            {"buttonStyle", "android:background", "android:textAppearance", "android:minHeight", "android:minWidth",
             "android:textAllCaps", "backgroundTint"},
            {"materialButtonStyle", "iconTint", "backgroundTint", "android:textColor", "cornerRadius", "iconPadding",
             "rippleColor", "strokeColor", "android:insetTop", "android:paddingLeft", "android:textAppearance"},
            {"editTextStyle", "android:background", "android:textColor", "android:textColorHint",
             "android:textAppearance", "android:textCursorDrawable"},
            {"toolbarStyle", "titleTextAppearance", "subtitleTextAppearance", "android:minHeight", "contentInsetStart",
             "titleMargin", "maxButtonHeight", "buttonGravity", "navigationIcon", "android:paddingLeft"},
            {"appBarLayoutStyle", "android:background", "elevation", "liftOnScroll"},
            {"bottomNavigationStyle", "itemIconTint", "itemTextColor", "backgroundTint", "android:background",
             "itemTextAppearanceActive", "labelVisibilityMode", "elevation", "itemRippleColor", "android:minHeight"},
            {"tabStyle", "tabIndicatorColor", "tabTextColor", "tabIconTint", "tabTextAppearance", "tabIndicator",
             "tabRippleColor", "android:background", "tabIndicatorHeight", "tabPaddingStart", "tabMinWidth"},
            {"chipStyle", "chipBackgroundColor", "android:textColor", "chipMinHeight", "chipIconTint",
             "android:textAppearance", "chipStrokeColor", "closeIconTint", "rippleColor", "checkedIcon", "chipStartPadding"},
            {"textInputStyle", "boxBackgroundMode", "boxBackgroundColor", "hintTextColor", "boxStrokeColor",
             "hintTextAppearance", "android:textColorHint", "shapeAppearance", "endIconTint", "boxCollapsedPaddingTop"},
            {"floatingActionButtonStyle", "backgroundTint", "tint", "fabSize", "elevation", "rippleColor", "borderWidth",
             "maxImageSize"},
            {"extendedFloatingActionButtonStyle", "backgroundTint", "iconTint", "android:textColor", "iconSize",
             "android:minHeight", "android:paddingStart"},
            {"materialCardViewStyle", "cardBackgroundColor", "cardElevation", "strokeColor", "rippleColor",
             "shapeAppearance", "checkedIconTint"},
            {"cardViewStyle", "cardBackgroundColor", "cardCornerRadius", "cardElevation"},
            {"snackbarStyle", "android:background", "backgroundTint", "actionTextColorAlpha", "maxActionInlineWidth"},
            {"spinnerStyle", "android:background", "android:popupBackground", "android:dropDownSelector"},
            {"seekBarStyle", "android:thumb", "android:progressDrawable", "tickMark", "android:splitTrack"},
            {"android:progressBarStyleHorizontal", "android:progressDrawable", "android:indeterminateDrawable",
             "android:minHeight", "android:maxHeight", "android:mirrorForRtl"},
            {"android:progressBarStyle", "android:indeterminateDrawable", "android:minWidth", "android:indeterminateOnly"},
            {"alertDialogStyle", "android:layout", "listLayout", "listItemLayout", "buttonIconDimen"},
            {"actionBarStyle", "background", "titleTextStyle", "height", "elevation", "contentInsetStart", "displayOptions"},
            {"actionButtonStyle", "android:background", "android:minWidth", "android:paddingLeft"},
            {"actionOverflowButtonStyle", "srcCompat", "android:background", "android:minWidth"},
            {"popupMenuStyle", "android:popupBackground", "overlapAnchor", "android:dropDownVerticalOffset"},
            {"autoCompleteTextViewStyle", "android:background", "android:popupBackground", "android:dropDownSelector"},
            {"checkedTextViewStyle", "android:textAppearance", "checkMarkCompat", "android:checkMark"},
            {"navigationViewStyle", "itemIconTint", "itemTextColor", "android:background", "itemBackground",
             "itemHorizontalPadding", "elevation"},
            {"sliderStyle", "thumbColor", "trackColorActive", "trackColorInactive", "thumbRadius", "trackHeight",
             "haloColor", "tickColorActive", "labelStyle"},
            {"linearProgressIndicatorStyle", "indicatorColor", "trackColor", "trackThickness", "trackCornerRadius"},
            {"circularProgressIndicatorStyle", "indicatorColor", "trackColor", "indicatorSize", "trackThickness"},
            {"bottomSheetStyle", "backgroundTint", "shapeAppearance", "behavior_peekHeight", "behavior_hideable",
             "android:elevation"},
            {"badgeStyle", "backgroundColor", "badgeTextColor", "badgeRadius", "badgeWithTextRadius", "maxCharacterCount"},
            {"materialToolbarStyle", "android:background", "titleTextColor", "navigationIconTint", "titleCentered"},
            {"collapsingToolbarLayoutStyle", "collapsedTitleTextAppearance", "expandedTitleTextAppearance",
             "statusBarScrim", "scrimAnimationDuration", "expandedTitleMargin"},
            {"imageButtonStyle", "android:background", "android:scaleType"},
            {"ratingBarStyle", "android:progressDrawable", "android:minHeight", "android:numStars"},
            {"listViewStyle", "android:divider", "android:listSelector", "android:dividerHeight"},
            {"dropDownListViewStyle", "android:divider", "android:listSelector", "android:dividerHeight"},
            {"textAppearanceBody1", "android:textSize", "android:textColor", "fontFamily", "android:letterSpacing",
             "android:textStyle", "android:fontFamily"},
            {"textAppearanceButton", "android:textSize", "android:textColor", "android:textAllCaps", "android:letterSpacing"},
            {"textAppearanceHeadline6", "android:textSize", "android:textColor", "fontFamily", "android:letterSpacing"},
            {"textAppearanceCaption", "android:textSize", "android:textColor"},
            {"shapeAppearanceSmallComponent", "cornerFamily", "cornerSize"},
            {"shapeAppearanceMediumComponent", "cornerFamily", "cornerSize"},
        };
        out.add("case styles.lib");
        for (String[] read : reads) {
            int defStyle = attrId(c, read[0]);
            for (int i = 1; i < read.length; i++) {
                StringBuilder sb = new StringBuilder("  ").append(read[0]).append('/').append(read[i]);
                try {
                    int attr = attrId(c, read[i]);
                    if (defStyle == 0 || attr == 0) {
                        out.add(sb.append(" no such attribute").toString());
                        continue;
                    }
                    android.content.res.TypedArray a = c.obtainStyledAttributes(null, new int[] {attr}, defStyle, 0);
                    sb.append(" has=").append(a.hasValue(0));
                    android.util.TypedValue v = a.peekValue(0);
                    if (v == null) {
                        sb.append(" value=null");
                    } else {
                        sb.append(" type=").append(v.type);
                        int id = a.getResourceId(0, 0);
                        if (id != 0) {
                            sb.append(" res=").append(c.getResources().getResourceTypeName(id)).append('/')
                              .append(c.getResources().getResourceEntryName(id));
                        }
                        if (v.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT
                                && v.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                            sb.append(" colour=").append(hex(v.data));
                        } else if (v.type == android.util.TypedValue.TYPE_DIMENSION) {
                            sb.append(" dimen=").append(dp(a.getDimension(0, 0), c.getResources().getDisplayMetrics().density));
                        } else if (v.type == android.util.TypedValue.TYPE_FLOAT) {
                            sb.append(" float=").append(a.getFloat(0, 0));
                        } else if (v.type >= android.util.TypedValue.TYPE_FIRST_INT
                                && v.type <= android.util.TypedValue.TYPE_LAST_INT) {
                            sb.append(" int=").append(v.data);
                        }
                        if (v.type == android.util.TypedValue.TYPE_STRING && id != 0
                                && "color".equals(c.getResources().getResourceTypeName(id))) {
                            android.content.res.ColorStateList l = a.getColorStateList(0);
                            sb.append(" list=").append(l == null ? "null" : hex(l.getDefaultColor()));
                        }
                    }
                    a.recycle();
                } catch (Throwable t) {
                    sb.append(" threw ").append(t.getClass().getSimpleName());
                }
                out.add(sb.toString());
            }
        }
    }

    private static int attrId(Context c, String name) {
        return name.startsWith("android:")
            ? c.getResources().getIdentifier(name.substring(8), "attr", "android")
            : c.getResources().getIdentifier(name, "attr", c.getPackageName());
    }

    /** A line of text in a Layout wider than it, for each direction and alignment: where the
     *  Layout says the line is, and where its ink lands when drawn. Libraries that draw text
     *  themselves (Material's collapsing titles and field labels) place it by these. */
    private static void textLayouts(Context app, List<String> out) {
        float d = app.getResources().getDisplayMetrics().density;
        android.text.TextPaint paint = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(16 * d);
        paint.setColor(0xFF000000);
        int width = Math.round(200 * d), height = Math.round(30 * d);
        String[] texts = {"שלום עולם", "Station", "12345", "אב abc"};
        String[] textNames = {"he", "en", "digits", "mixed"};
        android.text.TextDirectionHeuristic[] heuristics = {
            android.text.TextDirectionHeuristics.LTR, android.text.TextDirectionHeuristics.RTL,
            android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR, android.text.TextDirectionHeuristics.FIRSTSTRONG_RTL};
        String[] heuristicNames = {"ltr", "rtl", "firststrong_ltr", "firststrong_rtl"};
        Layout.Alignment[] alignments = {Layout.Alignment.ALIGN_NORMAL, Layout.Alignment.ALIGN_OPPOSITE,
                                         Layout.Alignment.ALIGN_CENTER};
        out.add("case text.layout");
        for (int t = 0; t < texts.length; t++) {
            for (int h = 0; h < heuristics.length; h++) {
                for (Layout.Alignment a : alignments) {
                    StringBuilder sb = new StringBuilder("  ").append(textNames[t]).append(' ')
                        .append(heuristicNames[h]).append(' ').append(a);
                    try {
                        android.text.StaticLayout l = android.text.StaticLayout.Builder
                            .obtain(texts[t], 0, texts[t].length(), paint, width)
                            .setAlignment(a).setTextDirection(heuristics[h]).setIncludePad(false).build();
                        sb.append(" dir=").append(l.getParagraphDirection(0))
                          .append(" left=").append(dp(l.getLineLeft(0), d)).append(" right=").append(dp(l.getLineRight(0), d))
                          .append(" width=").append(dp(l.getLineWidth(0), d)).append(" max=").append(dp(l.getLineMax(0), d))
                          .append(" first=").append(dp(l.getPrimaryHorizontal(0), d))
                          .append(" last=").append(dp(l.getPrimaryHorizontal(texts[t].length()), d));
                        Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        Canvas c = new Canvas(b);
                        c.drawColor(0xFFFFFFFF);
                        l.draw(c);
                        int min = -1, max = -1;
                        for (int x = 0; x < width; x++) {
                            boolean ink = false;
                            for (int y = 0; y < height && !ink; y++) ink = (b.getPixel(x, y) & 0xFF) < 0x80;
                            if (ink) { if (min < 0) min = x; max = x; }
                        }
                        sb.append(" ink=").append(dp(min, d)).append("..").append(dp(max, d));
                    } catch (Throwable e) {
                        sb.append(" threw ").append(e.getClass().getSimpleName());
                    }
                    out.add(sb.toString());
                }
            }
        }
    }

    /** Every drawable of the common libraries and the platform's widget drawables, as a view
     *  would ask about one: what it is, its own size, its padding, whether it follows state. */
    private static void drawables(Context app, List<String> out) {
        Context c = new ContextThemeWrapper(app, R.style.ProbeLibTheme);
        float d = c.getResources().getDisplayMetrics().density;
        Class<?>[] tables = {androidx.appcompat.R.drawable.class, com.google.android.material.R.drawable.class,
                             R.drawable.class};
        String[] tableNames = {"appcompat", "material", "probe"};
        for (int t = 0; t < tables.length; t++) {
            out.add("case drawables." + tableNames[t]);
            java.util.TreeMap<String, Integer> ids = new java.util.TreeMap<>();
            for (java.lang.reflect.Field f : tables[t].getFields()) {
                try {
                    if (f.getType() == int.class) ids.put(f.getName(), f.getInt(null));
                } catch (Throwable ignored) { }
            }
            for (java.util.Map.Entry<String, Integer> e : ids.entrySet()) {
                out.add(drawableLine(c, e.getKey(), e.getValue(), d));
            }
        }
        out.add("case drawables.platform");
        String[] platform = {"btn_default_material", "btn_default_mtrl_shape", "btn_colored_material",
            "btn_borderless_material", "edit_text_material", "textfield_default_mtrl_alpha",
            "textfield_activated_mtrl_alpha", "btn_check_material_anim", "btn_radio_material_anim",
            "switch_thumb_material_anim", "switch_track_material", "switch_track_mtrl_alpha",
            "btn_switch_to_on_mtrl_00012", "btn_switch_to_on_mtrl_00001", "spinner_background_material",
            "spinner_textfield_background_material", "progress_horizontal_material", "seekbar_thumb_material_anim",
            "seekbar_track_material", "scrubber_control_material_anim", "ratingbar_material",
            "item_background_material", "item_background_borderless_material", "list_divider_material",
            "popup_background_material", "dialog_background_material", "toast_frame", "tooltip_frame",
            "ic_menu_moreoverflow_material", "ic_ab_back_material", "ic_search_api_material",
            "ic_clear_material", "ic_go_search_api_material", "ic_voice_search_api_material",
            "text_cursor_material", "text_select_handle_middle_material", "text_select_handle_left_material",
            "fastscroll_thumb_material", "fastscroll_track_material", "scrollbar_handle_material",
            "list_selector_background", "divider_horizontal_bright", "btn_default", "btn_default_small",
            "edit_text", "editbox_background", "ic_menu_search", "ic_dialog_alert", "ic_dialog_info",
            "ic_menu_close_clear_cancel", "ic_delete", "ic_input_add", "star_big_on", "star_big_off",
            "presence_online", "sym_def_app_icon", "stat_notify_error", "screen_background_light",
            "dark_header", "title_bar", "progress_indeterminate_horizontal", "progress_horizontal",
            "alert_light_frame", "dialog_frame", "picture_frame", "toast_frame", "menuitem_background",
            "spinner_background", "spinner_dropdown_background", "gallery_thumb", "zoom_plate"};
        for (String name : platform) {
            int id = c.getResources().getIdentifier(name, "drawable", "android");
            out.add(id == 0 ? "  " + name + " no such drawable" : drawableLine(c, name, id, d));
        }
    }

    private static String drawableLine(Context c, String name, int id, float d) {
        StringBuilder sb = new StringBuilder("  ").append(name);
        try {
            android.graphics.drawable.Drawable dr =
                androidx.appcompat.content.res.AppCompatResources.getDrawable(c, id);
            if (dr == null) return sb.append(" null").toString();
            String cls = dr.getClass().getSimpleName();
            sb.append(' ').append(cls.replace("Compat", ""));
            sb.append(" size ").append(dp(dr.getIntrinsicWidth(), d)).append('x').append(dp(dr.getIntrinsicHeight(), d));
            android.graphics.Rect r = new android.graphics.Rect();
            boolean pad = dr.getPadding(r);
            sb.append(" pad=").append(pad).append(' ').append(dp(r.left, d)).append(',').append(dp(r.top, d))
              .append(',').append(dp(r.right, d)).append(',').append(dp(r.bottom, d));
            sb.append(" stateful=").append(dr.isStateful());
            android.graphics.Insets in = dr.getOpticalInsets();
            sb.append(" optical=").append(dp(in.left, d)).append(',').append(dp(in.top, d)).append(',')
              .append(dp(in.right, d)).append(',').append(dp(in.bottom, d));
            sb.append(" min ").append(dp(dr.getMinimumWidth(), d)).append('x').append(dp(dr.getMinimumHeight(), d));
        } catch (Throwable t) {
            sb.append(" threw ").append(t.getClass().getSimpleName());
        }
        return sb.toString();
    }

    /** What the two themes answer for the attributes widgets colour themselves by, read the
     *  three ways code reads them: resolveAttribute, a styled attribute array, and a colour
     *  state list of the libraries that names a theme attribute. */
    private static void themeValues(Context app, List<String> out) {
        String[] names = {"colorPrimary", "colorSecondary", "colorAccent", "colorControlNormal",
                          "colorControlActivated", "colorControlHighlight", "colorSurface", "colorOnSurface",
                          "android:textColorPrimary", "android:colorBackground", "android:colorAccent"};
        for (int theme = 0; theme < 2; theme++) {
            out.add("case theme." + (theme == 0 ? "platform" : "lib"));
            Context c = new ContextThemeWrapper(app, theme == 0 ? R.style.ProbeTheme : R.style.ProbeLibTheme);
            for (String name : names) {
                StringBuilder sb = new StringBuilder("  ").append(name);
                try {
                    int attr = name.startsWith("android:")
                        ? c.getResources().getIdentifier(name.substring(8), "attr", "android")
                        : c.getResources().getIdentifier(name, "attr", c.getPackageName());
                    if (attr == 0) {
                        out.add(sb.append(" no such attribute").toString());
                        continue;
                    }
                    android.util.TypedValue tv = new android.util.TypedValue();
                    boolean found = c.getTheme().resolveAttribute(attr, tv, true);
                    sb.append(" resolve=").append(found);
                    if (found) {
                        sb.append(" type=").append(tv.type);
                        if (tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT
                                && tv.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                            sb.append(" colour=").append(hex(tv.data));
                        }
                    }
                    android.content.res.TypedArray a = c.obtainStyledAttributes(new int[] {attr});
                    sb.append(" styled=").append(hex(a.getColor(0, 0x12345678)));
                    android.content.res.ColorStateList list = a.getColorStateList(0);
                    sb.append(" list=").append(list == null ? "null" : hex(list.getDefaultColor()));
                    a.recycle();
                } catch (Throwable t) {
                    sb.append(" threw ").append(t.getClass().getSimpleName());
                }
                out.add(sb.toString());
            }
            if (theme == 1) {
                int[] lists = {
                    com.google.android.material.R.color.mtrl_btn_text_color_selector,
                    com.google.android.material.R.color.mtrl_tabs_icon_color_selector,
                    com.google.android.material.R.color.mtrl_navigation_bar_item_tint,
                    com.google.android.material.R.color.mtrl_chip_background_color,
                    com.google.android.material.R.color.mtrl_text_btn_text_color_selector,
                    androidx.appcompat.R.color.abc_tint_switch_track,
                    androidx.appcompat.R.color.abc_tint_btn_checkable,
                };
                int[] checked = {android.R.attr.state_enabled, android.R.attr.state_checked};
                int[] enabled = {android.R.attr.state_enabled};
                for (int id : lists) {
                    StringBuilder sb = new StringBuilder("  ").append(c.getResources().getResourceEntryName(id));
                    try {
                        android.content.res.ColorStateList l =
                            androidx.appcompat.content.res.AppCompatResources.getColorStateList(c, id);
                        sb.append(" default=").append(hex(l.getDefaultColor()))
                          .append(" enabled=").append(hex(l.getColorForState(enabled, 0)))
                          .append(" checked=").append(hex(l.getColorForState(checked, 0)));
                    } catch (Throwable t) {
                        sb.append(" threw ").append(t.getClass().getSimpleName());
                    }
                    out.add(sb.toString());
                }
            }
        }
    }

    private static String hex(int colour) {
        return String.format("%08x", colour);
    }

    private static String dp(float px, float density) {
        // Tenths of a dp: the two sides round a pixel differently now and then.
        return String.valueOf(Math.round(px / density * 10) / 10f);
    }

    /** The mean colour of a rectangle of the picture, each channel as 0..9. */
    private static String colour(Bitmap b, int l, int t, int r, int bottom) {
        l = Math.max(0, l); t = Math.max(0, t);
        r = Math.min(b.getWidth(), r); bottom = Math.min(b.getHeight(), bottom);
        if (r <= l || bottom <= t) return "-";
        int w = r - l, h = bottom - t;
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, l, t, w, h);
        long red = 0, green = 0, blue = 0;
        for (int p : px) {
            red += (p >> 16) & 0xff;
            green += (p >> 8) & 0xff;
            blue += p & 0xff;
        }
        long n = px.length;
        return (red / n * 10 / 256) + "," + (green / n * 10 / 256) + "," + (blue / n * 10 / 256);
    }

    private static void dump(View v, int depth, int ox, int oy, float d, List<String> out, Context c,
                             Bitmap picture) {
        StringBuilder sb = new StringBuilder("  ");
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v.getClass().getSimpleName());
        if (v.getId() != View.NO_ID) {
            try { sb.append('#').append(c.getResources().getResourceEntryName(v.getId())); }
            catch (Throwable t) { sb.append("#?"); }
        }
        sb.append(" at ").append(dp(v.getLeft(), d)).append(',').append(dp(v.getTop(), d))
          .append(" size ").append(dp(v.getWidth(), d)).append('x').append(dp(v.getHeight(), d));
        if (v.getVisibility() != View.VISIBLE) sb.append(" vis=").append(v.getVisibility());
        sb.append(" dir=").append(v.getLayoutDirection());
        sb.append(" pad=").append(dp(v.getPaddingLeft(), d)).append(',').append(dp(v.getPaddingTop(), d))
          .append(',').append(dp(v.getPaddingRight(), d)).append(',').append(dp(v.getPaddingBottom(), d));
        if (v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            sb.append(" margins=").append(dp(m.leftMargin, d)).append(',').append(dp(m.topMargin, d))
              .append(',').append(dp(m.rightMargin, d)).append(',').append(dp(m.bottomMargin, d))
              .append(" lpdir=").append(m.getLayoutDirection());
        }
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            try {
                sb.append(" baseline=").append(dp(tv.getBaseline(), d));
                Layout l = tv.getLayout();
                if (l != null) {
                    sb.append(" lines=").append(l.getLineCount());
                    int last = l.getLineCount() - 1;
                    sb.append(" texth=").append(dp(l.getHeight(), d))
                      .append(" lastw=").append(dp(l.getLineWidth(last), d))
                      .append(" ell=").append(l.getEllipsisCount(last));
                    // Where the first line's ink sits inside the view.
                    sb.append(" text=").append(dp(l.getLineLeft(0) + tv.getTotalPaddingLeft(), d))
                      .append("..").append(dp(l.getLineRight(0) + tv.getTotalPaddingLeft(), d))
                      .append(" top=").append(dp(l.getLineTop(0) + tv.getTotalPaddingTop(), d));
                } else {
                    sb.append(" layout=null");
                }
            } catch (Throwable t) {
                sb.append(" text?").append(t.getClass().getSimpleName());
            }
        }
        if (v instanceof android.widget.ImageView && ((android.widget.ImageView) v).getDrawable() != null) {
            android.widget.ImageView iv = (android.widget.ImageView) v;
            float[] m = new float[9];
            iv.getImageMatrix().getValues(m);
            android.graphics.Rect b = iv.getDrawable().getBounds();
            sb.append(" scale=").append(Math.round(m[0] * 100) / 100f).append('x').append(Math.round(m[4] * 100) / 100f)
              .append(" shift=").append(dp(m[2], d)).append(',').append(dp(m[5], d))
              .append(" pic=").append(dp(b.width(), d)).append('x').append(dp(b.height(), d));
        }
        if (v instanceof android.widget.ImageView) {
            android.content.res.ColorStateList tint = ((android.widget.ImageView) v).getImageTintList();
            sb.append(" tint=").append(tint == null ? "none" : hex(tint.getColorForState(v.getDrawableState(), 0)));
        }
        if (v instanceof android.widget.Checkable) {
            sb.append(" checked=").append(((android.widget.Checkable) v).isChecked());
        }
        // Only whether the text is masked: the method's class is AppCompat's emoji wrapper
        // wherever EmojiCompat is set up, which it is in a started app and not under test.
        if (v instanceof TextView && ((TextView) v).getTransformationMethod()
                instanceof android.text.method.PasswordTransformationMethod) {
            sb.append(" password");
        }
        if (v instanceof TextView) sb.append(" gravity=").append(Integer.toHexString(((TextView) v).getGravity()));
        if (v.getBackgroundTintList() != null) {
            sb.append(" bgtint=").append(hex(v.getBackgroundTintList().getColorForState(v.getDrawableState(), 0)));
        }
        int x = ox + v.getLeft(), y = oy + v.getTop();
        if (picture != null && !(v instanceof ViewGroup) && v.getWidth() > 0 && v.getHeight() > 0) {
            // The whole view, then its middle third each way.
            int tw = v.getWidth() / 3, th = v.getHeight() / 3;
            sb.append(" px=").append(colour(picture, x, y, x + v.getWidth(), y + v.getHeight()))
              .append(';').append(colour(picture, x + tw, y + th, x + 2 * tw, y + 2 * th));
        }
        out.add(sb.toString());
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dump(g.getChildAt(i), depth + 1, x, y, d, out, c, picture);
        }
    }
}
