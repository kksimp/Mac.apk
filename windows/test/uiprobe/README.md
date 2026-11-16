# UI probe: WinARM.apk's framework against Android's

The same code (`src/dev/winapk/probe/Probes.java`) runs twice: once inside
WinARM.apk, as a small APK, and once on the real AOSP framework, under Robolectric
with native graphics. What it sees comes out as lines of text, and `compare.py`
prints the lines that differ. Each one is a place where WinARM.apk's framework
does not do what Android does, found without opening an app.

What it compares:

| Section | What |
|---|---|
| `theme.*` | What a theme answers for the attributes widgets colour themselves by, and the libraries' colour state lists |
| `text.layout` | One line of text in a wider `StaticLayout`, for every direction and alignment: the Layout's answers and where the ink lands |
| `styles.lib` | What each widget's default style answers for the attributes its constructor reads (AppCompat and Material) |
| `drawables.*` | Every drawable of AppCompat, Material and the platform's widget set: class, size, padding, optical insets, statefulness |
| the layouts | `res/layout/*`, inflated, measured, laid out and drawn left to right and right to left: every view's place, padding, margins, baseline, text extent, tints, checked state and the mean colour of what it drew. Both pictures are saved as PNGs |

```sh
windows/test/uiprobe/build.sh <out> [gradle]              # probe.apk, oracle.txt, oracle-pictures/
windows/test/uiprobe/run_ours.sh <out> <winapk-runtime.exe> [--lib <dir>]   # <out>/ours.txt
python windows/test/uiprobe/compare.py <out>/oracle.txt <out>/ours.txt
```

- `build.sh` is a Gradle build (AGP 8.5, `build.gradle` beside it): it copies
  the project to `<out>/project`, assembles the APK and runs the Robolectric
  test that writes the oracle. The libraries (AppCompat, Material,
  ConstraintLayout, RecyclerView, CardView) come from Google's Maven
  repository; the first run downloads them and the Android framework
  Robolectric runs.
- `<out>` must be a path without spaces or non-ASCII characters (aapt2 and
  Robolectric both fail on those).
- Needs an Android SDK (build-tools and a platform), a JDK 17 and Gradle 8.9.
- WinARM.apk's pictures are in `<out>/data/android/data/data/probe/files/probe`.

A new layout case is a file under `res/layout/` plus its name in
`Probes.LAYOUTS`. A widget or a LayoutParams class of the probe's own
(`ProbeLabel`, `ProbeLayout`) stands for what apps do that the framework's own
widgets do not: a default style, a constructor that reads its attributes.

Numbers are in dp, and two lines count as equal within 1 dp: the fonts are
hinted a little differently and each side rounds a pixel its own way.

What the oracle cannot tell: Robolectric draws no Hebrew in a Material chip or
a toolbar title, shows an animated selector in the middle of its transition,
and runs without EmojiCompat; `no such drawable` lines are platform drawables
Robolectric's framework does not carry. Those lines differ on its side.

`../apicheck/ApiCheck.java` is the static counterpart: it resolves every class,
method and field that converted apps name against the runtime's jars and lists
the platform members the runtime lacks, by how many apps name each.
