# Resource and process-identity regressions

Build the Java framework and loader using `debug/build_java_only.sh`. The checks
run as ordinary Java programs; they do not launch an app or send network requests.

```sh
classes="$(mktemp -d)"
cp="runtime/build/lib/android-framework.jar:runtime/build/lib/macapk-loader.jar"
javac --release 21 -cp "$cp" -d "$classes" debug/resource_process/ResourceProcessSmoke.java
cp="$classes:$cp"
java -cp "$cp" ResourceProcessSmoke process
java -cp "$cp" ResourceProcessSmoke legacy
MACAPK_FW_RES_APK=/path/to/framework-res.apk java -cp "$cp" ResourceProcessSmoke integer
```

`integer` uses an empty temporary app ZIP and the separately supplied platform
resource table. With the API-36 table used in the local reproduction,
`config_cursorWindowSize` is 2048 KiB. Before the fix the framework ID resolves,
but `getInteger` returns zero. No APK or device data is needed for this check.
The empty ZIP intentionally has no app resource table; a missing-table warning
is expected. Use the framework-res.apk staged with the runtime being tested.

The other modes accept a locally owned optimized APK and explicit resource IDs;
no application name or ID is built into the regression code. This reproduction
was checked against Xiaohongshu 9.43.1 (9431801), original APK SHA-256
`842bb1ed3f2c7751fccd5a4fa87933e2f780f691b59d626b25f896ea588f47b4`:

```sh
apk=/path/to/original-9.43.1.apk
java -cp "$cp" ResourceProcessSmoke path "$apk" 7f0c1319 r/mht.xml com.xingin.xhstheme.view.swipeback.SwipeBackLayout
java -cp "$cp" ResourceProcessSmoke theme "$apk" 7f12024a 7f040817 false
java -cp "$cp" ResourceProcessSmoke xml "$apk" 7f0c0014 androidx.appcompat.widget.ContentFrameLayout match_parent
rm -rf "$classes"
```

The APK is not distributed here. These checks establish resource and constructor
behavior; they do not establish full application compatibility or successful login.

Local verification on 2026-10-25: the full Java build passed. All six cases above
fail against the unmodified 1.0.2303 jars and pass against newly built patched
jars. Failures are assertions about the targeted behavior, rather than missing
test dependencies. The broader interactive experiment and its remaining login
and WebView limitations are recorded in [issue #47](https://github.com/kksimp/Mac.apk/issues/47).
