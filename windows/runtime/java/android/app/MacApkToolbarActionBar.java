// The platform action bar of a window whose decor is screen_action_bar: the
// role AOSP gives WindowDecorActionBar over ToolbarWidgetWrapper (android14
// com/android/internal/app/WindowDecorActionBar.java, com/android/internal/
// widget/ToolbarWidgetWrapper.java). The decor holds a real android.widget.
// Toolbar with the id @android:id/action_bar inside @android:id/
// action_bar_container (Activity.ActivityWindow.macapkInstallScreenActionBar),
// so apps that find that Toolbar and style it directly (Bill Farmer's apps
// set its navigation icon) and apps that drive getActionBar() see the same bar.
//
// What is delegated: title and subtitle (an explicit title wins over the
// window's, as setTitle sets DISPLAY_TITLE_SET on AOSP), the display options
// (home-as-up shows ?android:homeAsUpIndicator as the navigation button; show
// title / use logo / show home), show and hide, the height, the background,
// a custom view, and the options menu, which ToolbarActionBar-style is the
// Toolbar's own menu filled through the window callback (onCreatePanelMenu +
// onPreparePanel) with item clicks and the Up button going to
// onMenuItemSelected(FEATURE_OPTIONS_PANEL, ...), android.R.id.home for Up.
// Not ported: list and tab navigation modes (the base class's state only),
// split action bars, hide-on-content-scroll, action modes in the bar.
package android.app;

final class MacApkToolbarActionBar extends ActionBar {
    private static final int DISPLAY_TITLE_SET = 0x1000;   // WindowDecorActionBar's private flag

    private final android.widget.Toolbar mToolbar;
    private final android.view.View mContainer;
    private final Activity mActivity;
    private android.graphics.drawable.Drawable mUpIndicator;
    private android.view.View mCustom;
    private boolean mMenuQueued;

    MacApkToolbarActionBar(Activity activity, android.widget.Toolbar toolbar, android.view.View container,
                           int displayOptions, android.graphics.drawable.Drawable upIndicator) {
        mActivity = activity;
        mToolbar = toolbar;
        mContainer = container;
        mUpIndicator = upIndicator;
        super.setDisplayOptions(displayOptions);
        mToolbar.setNavigationOnClickListener(v -> mActivity.onMenuItemSelected(
                android.view.Window.FEATURE_OPTIONS_PANEL, new HomeItem(mActivity)));
        mToolbar.setOnMenuItemClickListener(item -> mActivity.onMenuItemSelected(
                android.view.Window.FEATURE_OPTIONS_PANEL, item));
        applyOptions();
    }

    android.widget.Toolbar macapkToolbar() { return mToolbar; }

    /** The window's title (Activity.onTitleChanged -> Window.setTitle ->
     *  WindowDecorActionBar.setWindowTitle): shown unless the app set one. */
    void macapkSetWindowTitle(CharSequence title) {
        if ((getDisplayOptions() & DISPLAY_TITLE_SET) == 0) setTitleInternal(title);
    }

    // ---- title / subtitle ----
    @Override public void setTitle(CharSequence title) {
        super.setDisplayOptions(getDisplayOptions() | DISPLAY_TITLE_SET);
        setTitleInternal(title);
    }
    @Override public void setTitle(int resId) { setTitle(resId != 0 ? mActivity.getText(resId) : null); }
    private void setTitleInternal(CharSequence title) {
        super.setTitle(title);
        if ((getDisplayOptions() & DISPLAY_SHOW_TITLE) != 0) mToolbar.setTitle(title);
    }
    @Override public CharSequence getTitle() { return mToolbar.getTitle(); }
    @Override public void setSubtitle(CharSequence subtitle) {
        super.setSubtitle(subtitle);
        if ((getDisplayOptions() & DISPLAY_SHOW_TITLE) != 0) mToolbar.setSubtitle(subtitle);
    }
    @Override public void setSubtitle(int resId) { setSubtitle(resId != 0 ? mActivity.getText(resId) : null); }
    @Override public CharSequence getSubtitle() { return mToolbar.getSubtitle(); }

    // ---- display options (ToolbarWidgetWrapper.setDisplayOptions) ----
    @Override public void setDisplayOptions(int options) {
        super.setDisplayOptions((options & ~DISPLAY_TITLE_SET) | (getDisplayOptions() & DISPLAY_TITLE_SET));
        applyOptions();
    }
    @Override public void setDisplayOptions(int options, int mask) {
        setDisplayOptions((getDisplayOptions() & ~mask) | (options & mask));
    }
    @Override public void setDisplayUseLogoEnabled(boolean on) { setDisplayOptions(on ? DISPLAY_USE_LOGO : 0, DISPLAY_USE_LOGO); }
    @Override public void setDisplayShowHomeEnabled(boolean on) { setDisplayOptions(on ? DISPLAY_SHOW_HOME : 0, DISPLAY_SHOW_HOME); }
    @Override public void setDisplayHomeAsUpEnabled(boolean on) { setDisplayOptions(on ? DISPLAY_HOME_AS_UP : 0, DISPLAY_HOME_AS_UP); }
    @Override public void setDisplayShowTitleEnabled(boolean on) { setDisplayOptions(on ? DISPLAY_SHOW_TITLE : 0, DISPLAY_SHOW_TITLE); }
    @Override public void setDisplayShowCustomEnabled(boolean on) { setDisplayOptions(on ? DISPLAY_SHOW_CUSTOM : 0, DISPLAY_SHOW_CUSTOM); }

    private void applyOptions() {
        int o = getDisplayOptions();
        mToolbar.setNavigationIcon((o & DISPLAY_HOME_AS_UP) != 0 ? mUpIndicator : null);
        if ((o & DISPLAY_SHOW_TITLE) != 0) {
            mToolbar.setTitle(super.getTitle());
            mToolbar.setSubtitle(super.getSubtitle());
        } else {
            mToolbar.setTitle(null);
            mToolbar.setSubtitle(null);
        }
        if (mCustom != null) {
            boolean show = (o & DISPLAY_SHOW_CUSTOM) != 0;
            if (show && mCustom.getParent() == null) mToolbar.addView(mCustom);
            else if (!show && mCustom.getParent() == mToolbar) mToolbar.removeView(mCustom);
        }
    }

    @Override public void setHomeAsUpIndicator(android.graphics.drawable.Drawable indicator) {
        if (indicator != null) mUpIndicator = indicator;
        applyOptions();
    }
    @Override public void setHomeAsUpIndicator(int resId) {
        setHomeAsUpIndicator(resId != 0 ? mActivity.getDrawable(resId) : null);
    }
    @Override public void setIcon(int resId) { }
    @Override public void setIcon(android.graphics.drawable.Drawable icon) { }
    @Override public void setLogo(int resId) {
        if ((getDisplayOptions() & DISPLAY_USE_LOGO) != 0) mToolbar.setLogo(resId);
    }
    @Override public void setLogo(android.graphics.drawable.Drawable logo) {
        if ((getDisplayOptions() & DISPLAY_USE_LOGO) != 0) mToolbar.setLogo(logo);
    }
    @Override public void setBackgroundDrawable(android.graphics.drawable.Drawable d) { mContainer.setBackground(d); }

    @Override public void setCustomView(android.view.View view) {
        setCustomView(view, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }
    @Override public void setCustomView(android.view.View view, LayoutParams lp) {
        if (mCustom != null && mCustom.getParent() == mToolbar) mToolbar.removeView(mCustom);
        mCustom = view;
        super.setCustomView(view, lp);
        if (view != null && lp != null) {
            view.setLayoutParams(new android.widget.Toolbar.LayoutParams(lp.width, lp.height,
                    lp.gravity != -1 ? lp.gravity : android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL));
        }
        applyOptions();
    }
    @Override public void setCustomView(int resId) {
        setCustomView(android.view.LayoutInflater.from(getThemedContext()).inflate(resId, mToolbar, false));
    }
    @Override public android.view.View getCustomView() { return mCustom; }

    // ---- visibility and size ----
    @Override public void show() { super.show(); mContainer.setVisibility(android.view.View.VISIBLE); }
    @Override public void hide() { super.hide(); mContainer.setVisibility(android.view.View.GONE); }
    @Override public boolean isShowing() { return mContainer.getVisibility() == android.view.View.VISIBLE; }
    @Override public int getHeight() { return mContainer.getHeight(); }
    @Override public android.content.Context getThemedContext() { return mToolbar.getContext(); }
    @Override public void setElevation(float elevation) { super.setElevation(elevation); mContainer.setElevation(elevation); }

    // ---- the options menu (ToolbarActionBar.populateOptionsMenu) ----
    /** Fills the Toolbar's menu from the activity, after the current message,
     *  as AOSP's invalidateOptionsMenu posts its rebuild. Coalesced. */
    void macapkInvalidateMenu() {
        if (mMenuQueued) return;
        mMenuQueued = true;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            mMenuQueued = false;
            populateMenu();
        });
    }

    private void populateMenu() {
        android.view.Menu menu = mToolbar.getMenu();
        if (menu == null) return;
        menu.clear();
        try {
            if (!mActivity.onCreatePanelMenu(android.view.Window.FEATURE_OPTIONS_PANEL, menu)
                    || !mActivity.onPreparePanel(android.view.Window.FEATURE_OPTIONS_PANEL, null, menu)) {
                menu.clear();
            }
        } catch (RuntimeException e) {
            menu.clear();
            throw e;
        }
    }

    /** The Up button's item: AOSP ToolbarWidgetWrapper's mNavItem,
     *  an ActionMenuItem with the id android.R.id.home. */
    private static final class HomeItem implements android.view.MenuItem {
        private final android.content.Context mContext;
        HomeItem(android.content.Context c) { mContext = c; }
        @Override public int getItemId() { return android.R.id.home; }
        @Override public int getGroupId() { return 0; }
        @Override public int getOrder() { return 0; }
        @Override public android.view.MenuItem setTitle(CharSequence title) { return this; }
        @Override public android.view.MenuItem setTitle(int title) { return this; }
        @Override public CharSequence getTitle() { return null; }
        @Override public android.view.MenuItem setTitleCondensed(CharSequence title) { return this; }
        @Override public CharSequence getTitleCondensed() { return null; }
        @Override public android.view.MenuItem setIcon(android.graphics.drawable.Drawable icon) { return this; }
        @Override public android.view.MenuItem setIcon(int iconRes) { return this; }
        @Override public android.graphics.drawable.Drawable getIcon() { return null; }
        @Override public android.view.MenuItem setIntent(android.content.Intent intent) { return this; }
        @Override public android.content.Intent getIntent() { return null; }
        @Override public android.view.MenuItem setShortcut(char numericChar, char alphaChar) { return this; }
        @Override public android.view.MenuItem setNumericShortcut(char numericChar) { return this; }
        @Override public char getNumericShortcut() { return 0; }
        @Override public android.view.MenuItem setAlphabeticShortcut(char alphaChar) { return this; }
        @Override public char getAlphabeticShortcut() { return 0; }
        @Override public android.view.MenuItem setCheckable(boolean checkable) { return this; }
        @Override public boolean isCheckable() { return false; }
        @Override public android.view.MenuItem setChecked(boolean checked) { return this; }
        @Override public boolean isChecked() { return false; }
        @Override public android.view.MenuItem setVisible(boolean visible) { return this; }
        @Override public boolean isVisible() { return true; }
        @Override public android.view.MenuItem setEnabled(boolean enabled) { return this; }
        @Override public boolean isEnabled() { return true; }
        @Override public boolean hasSubMenu() { return false; }
        @Override public android.view.SubMenu getSubMenu() { return null; }
        @Override public android.view.MenuItem setOnMenuItemClickListener(OnMenuItemClickListener l) { return this; }
        @Override public android.view.ContextMenu.ContextMenuInfo getMenuInfo() { return null; }
        @Override public void setShowAsAction(int actionEnum) { }
        @Override public android.view.MenuItem setShowAsActionFlags(int actionEnum) { return this; }
        @Override public android.view.MenuItem setActionView(android.view.View view) { return this; }
        @Override public android.view.MenuItem setActionView(int resId) { return this; }
        @Override public android.view.View getActionView() { return null; }
        @Override public android.view.MenuItem setActionProvider(android.view.ActionProvider actionProvider) { return this; }
        @Override public android.view.ActionProvider getActionProvider() { return null; }
        @Override public boolean expandActionView() { return false; }
        @Override public boolean collapseActionView() { return false; }
        @Override public boolean isActionViewExpanded() { return false; }
        @Override public android.view.MenuItem setOnActionExpandListener(OnActionExpandListener listener) { return this; }
    }
}
