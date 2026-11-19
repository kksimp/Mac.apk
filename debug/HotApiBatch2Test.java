import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageInstaller;
import android.content.res.ColorStateList;
import android.graphics.HardwareRenderer;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import com.macapk.loader.AppClassLoader;
import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Regression tests for HOTAPI-002 setter/getter state round-trips and non-lib*.so ELF detection. */
public class HotApiBatch2Test {
    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    public static void main(String[] args) throws Exception {
        // 1. Activity immersive
        Activity act = new Activity();
        Context ctx = act;
        check(!act.isImmersive(), "Activity.isImmersive default must be false");
        act.setImmersive(true);
        check(act.isImmersive(), "Activity.setImmersive(true) did not stick");
        act.setImmersive(false);
        check(!act.isImmersive(), "Activity.setImmersive(false) did not stick");

        // 2. ActivityManager processStateSummary (defensive copy across instances)
        ActivityManager am1 = new ActivityManager();
        ActivityManager am2 = new ActivityManager();
        am1.setProcessStateSummary(null);
        check(am1.getProcessStateSummary() == null, "default processStateSummary must be null");
        byte[] state = new byte[] {1, 2, 3, 4};
        am1.setProcessStateSummary(state);
        state[0] = 99;
        byte[] readBack = am2.getProcessStateSummary();
        check(Arrays.equals(readBack, new byte[] {1, 2, 3, 4}),
                "processStateSummary must copy defensively and share across instances");
        readBack[1] = 88;
        check(am1.getProcessStateSummary()[1] == 2,
                "getProcessStateSummary must return defensive copy");
        am1.setProcessStateSummary(null);
        check(am2.getProcessStateSummary() == null, "clear processStateSummary failed");

        // 3. AbsListView selector round-trip
        ListView list = new ListView(ctx);
        check(list.getSelector() == null, "ListView default selector must be null");
        ColorDrawable sel = new ColorDrawable(0xFF112233);
        list.setSelector(sel);
        check(list.getSelector() == sel, "ListView.getSelector did not return set drawable");
        list.setSelector(0);
        check(list.getSelector() == null, "ListView.setSelector(0) must clear selector");

        // 4. PackageInstaller.SessionParams referrerUri / originatingUri
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        Uri refUri = Uri.parse("https://example.com/ref");
        Uri origUri = Uri.parse("https://example.com/orig");
        sp.setReferrerUri(refUri);
        sp.setOriginatingUri(origUri);
        check(refUri.equals(sp.referrerUri), "SessionParams.setReferrerUri failed");
        check(origUri.equals(sp.originatingUri), "SessionParams.setOriginatingUri failed");
        PackageInstaller piInst = new PackageInstaller();
        int sid = piInst.createSession(sp);
        try {
            PackageInstaller.SessionInfo sinfo = piInst.getSessionInfo(sid);
            check(sinfo != null && refUri.equals(sinfo.getReferrerUri())
                    && origUri.equals(sinfo.getOriginatingUri()),
                    "SessionInfo did not echo referrerUri/originatingUri");
        } finally {
            piInst.abandonSession(sid);
        }

        // 5. View & ViewGroup state round-trips
        View v = new View(ctx);
        v.setScrollBarFadeDuration(350);
        v.setScrollBarDefaultDelayBeforeFade(450);
        v.setScrollBarSize(12);
        check(v.getScrollBarFadeDuration() == 350, "ScrollBarFadeDuration mismatch");
        check(v.getScrollBarDefaultDelayBeforeFade() == 450, "ScrollBarDelay mismatch");
        check(v.getScrollBarSize() == 12, "ScrollBarSize mismatch");

        ColorStateList csl = ColorStateList.valueOf(0xFFAA00FF);
        v.setForegroundTintList(csl);
        v.setForegroundTintMode(PorterDuff.Mode.SRC_IN);
        check(v.getForegroundTintList() == csl, "ForegroundTintList mismatch");
        check(v.getForegroundTintMode() == PorterDuff.Mode.SRC_IN, "ForegroundTintMode mismatch");

        PointerIcon pi = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND);
        v.setPointerIcon(pi);
        check(v.getPointerIcon() == pi, "PointerIcon mismatch");

        check(!v.isForceDarkAllowed(), "default isForceDarkAllowed invariant preserved");
        v.setForceDarkAllowed(true);
        check(v.isForceDarkAllowed(), "setForceDarkAllowed(true) did not stick");

        ViewGroup vg = new ViewGroup(ctx) {};
        check(!vg.isAnimationCacheEnabled() && !vg.isAlwaysDrawnWithCacheEnabled()
                && vg.getPersistentDrawingCache() == 0,
                "ViewGroup cache defaults must remain preserved");
        vg.setAnimationCacheEnabled(true);
        vg.setAlwaysDrawnWithCacheEnabled(true);
        vg.setPersistentDrawingCache(2);
        check(vg.isAnimationCacheEnabled(), "setAnimationCacheEnabled did not stick");
        check(vg.isAlwaysDrawnWithCacheEnabled(), "setAlwaysDrawnWithCacheEnabled did not stick");
        check(vg.getPersistentDrawingCache() == 2,
                "setPersistentDrawingCache did not stick");

        // 6. HardwareRenderer properties
        HardwareRenderer hr = new HardwareRenderer();
        check(hr.isOpaque(), "HardwareRenderer default isOpaque must be true");
        hr.setOpaque(false);
        hr.setSpotShadowColor(0xFF010203);
        hr.setAmbientShadowColor(0xFF040506);
        check(!hr.isOpaque(), "HardwareRenderer.setOpaque(false) failed");
        check(hr.getSpotShadowColor() == 0xFF010203, "SpotShadowColor failed");
        check(hr.getAmbientShadowColor() == 0xFF040506, "AmbientShadowColor failed");

        // 7. AudioManager & MediaPlayer
        AudioManager audio = new AudioManager();
        check(audio.isSpeakerphoneOn(), "AudioManager default speakerphone must be true");
        audio.setSpeakerphoneOn(false);
        audio.setBluetoothScoOn(true);
        audio.setBluetoothA2dpOn(true);
        audio.setMicrophoneMute(true);
        check(!audio.isSpeakerphoneOn() && audio.isBluetoothScoOn()
                && audio.isBluetoothA2dpOn() && audio.isMicrophoneMute(),
                "AudioManager boolean routing flags failed");
        audio.setParameters("foo=bar;sample_rate=48000");
        String got = audio.getParameters("sample_rate;missing;foo");
        check("sample_rate=48000;foo=bar".equals(got),
                "AudioManager.getParameters returned unexpected: " + got);

        MediaPlayer mp = new MediaPlayer();
        check(mp.getAudioSessionId() == 0, "MediaPlayer default audioSessionId mismatch");
        mp.setAudioSessionId(42);
        check(mp.getAudioSessionId() == 42, "MediaPlayer.setAudioSessionId failed");

        // 8. Non-lib*.so ELF detection in AppClassLoader.macapkLoadNative
        Path tmp = Files.createTempDirectory("hotapi_elf_test");
        try {
            File elfPlugin = tmp.resolve("amap_support").toFile();
            Files.write(elfPlugin.toPath(), new byte[] {0x7f, 'E', 'L', 'F', 2, 1, 1, 0});
            File badTranslator = tmp.resolve("missing_translator").toFile();
            try (AppClassLoader cl = new AppClassLoader(
                    new URL[0], HotApiBatch2Test.class.getClassLoader(),
                    null, tmp.toFile(), badTranslator, null)) {
                boolean sawTranslateAttempt = false;
                try {
                    cl.macapkLoadNative(elfPlugin.getAbsolutePath());
                } catch (UnsatisfiedLinkError e) {
                    sawTranslateAttempt = e.getMessage() != null
                            && e.getMessage().contains("Mac.apk could not translate");
                }
                check(sawTranslateAttempt,
                        "Non-lib*.so ELF file must route through translator before dlopen");
            }
        } finally {
            for (File f : tmp.toFile().listFiles()) {
                if (f.isDirectory()) {
                    for (File sub : f.listFiles()) sub.delete();
                }
                f.delete();
            }
            tmp.toFile().delete();
        }

        System.out.println("PASS: HotApiBatch2Test (HOTAPI-002 + ELF magic routing)");
    }
}
