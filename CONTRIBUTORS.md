# Contributors

Mac.apk is built with help from people outside the project. Thank you. This list names everyone whose code is in the project, with the pull requests and what they did. Their commits are kept under their own names wherever we could import them as they were sent. Where a pull request reached this repo through a release sync, the author is credited as a co-author on a commit and listed here.

## v1.0.2404

- **@ThePhilDiamond**: #160, #162 to #168, #170, #171, #174, #175, #177 to #179, #181, #182, #185 to #187 (and #196 via dex2jvm): Touch ID switch and prompt, screen camera, camera and microphone fixes, audio engine restarts, launcher polish, colour emoji, app stub start-up, native video surfaces, diagnostics.
- **@Rengwang** (Xu Rui): #191 to #195, #197: framework contracts that follow AOSP (ServiceManager, PatternMatcher, Bundle, Intent, AudioTrack, Binder, window and accessibility behaviour, MediaExtractor, ActivityManager tasks), and the dex2jvm dynamic-proxy fix.
- **@defia**: #143, #144, #158, #159: native wrapper loading, wrapped Application identity, secondary-process identity, video fallback and fullscreen routing.
- **@5566brs**: #149, #150, #151: ART's version string for MultiDex, Thread subclass start, class loading without a per-class lock.
- **@Ye-Yu-Mo**: #142: section-stripped exports, JNI_OnLoad and nested Looper fixes.
- **@Enderfga**: #184: Bionic's static mutex initializers on arm64.
- **@chuenlok**: #153: re-binding the ANGLE surface when a view returns to the window.
- **@joshiste**: #136: streaming large assets from AssetManager.open.

## v1.0.2377

- **@5566brs**: #57, #69, #70, #80, #84, #85 (merged) and #62 to #68, #71, #72, #88 to #95: right-to-left layout and text, tints, PopupWindow, Toolbar, shrunk-APK attributes, class loaders, ReplacementSpan, elevation, provider authorities, themes, compound drawables, Switch, Toasts, GridView, ListView and more.
- **@defia**: #121, #122, #124, #125, #126: native executable backups outside the ABI library folders, opt-in guest code writes with W^X, opt-in CoreBluetooth BLE, per-app HTTP and SOCKS5 proxies, KeyStore authentication policies.
- **@chuenlok**: #108, #109, #110, #114: animated GIF and WebP drawables, the DialogFragment lifecycle, URL-safe bundle folder names, UTF-8 manifest strings.
- **@Mistarille224**: #87, #96: application locale selection, feature-based package discovery.
- **@m9rco**: #102: resource lookup and process identity fixes.
- **@wishbone305**: #119: host clicks for Unreal games.
- **@kushu7**: #82, #83: the per-version app folder and Android TV mode work that we reworked in v1.0.2306.

## Earlier releases

- **@YuKongA**: #39, #40 (AGSL shaders and RenderEffect, color filters).
- **@hlwhl**: #50, #51 (native library loading order and import binding).
- **@lxy2500798479**: #37, #46 (Apache HTTP params, native frames in crash logs).
- **@blueagler**: #33 (Application base-context getters).
