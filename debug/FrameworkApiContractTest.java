import android.content.ComponentName;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.PatternMatcher;
import android.os.ServiceManager;
import java.util.ArrayList;

public class FrameworkApiContractTest {
    public static void main(String[] args) throws Exception {
        try {
            IBinder b = ServiceManager.getServiceOrThrow("missing_contract_svc");
            throw new AssertionError("getServiceOrThrow returned " + b);
        } catch (ServiceManager.ServiceNotFoundException expected) {}

        Bundle bundle = new Bundle();
        ArrayList raw = new ArrayList();
        raw.add("wrong_element_type");
        bundle.putParcelableArrayList("arr", raw);
        if (bundle.getParcelableArrayList("arr", ComponentName.class) != null) {
            throw new AssertionError("getParcelableArrayList(k,Class) leaked mismatched element");
        }
        bundle.putSerializable("ser", "wrong_serializable");
        if (bundle.getSerializable("ser", Integer.class) != null) {
            throw new AssertionError("getSerializable(k,Integer) leaked String");
        }
        bundle.putSerializable("ser_ok", Integer.valueOf(77));
        if (!Integer.valueOf(77).equals(bundle.getSerializable("ser_ok", Integer.class))) {
            throw new AssertionError("getSerializable(k,Integer) rejected valid Integer");
        }

        Intent i = Intent.parseUri("android-app://com.example/https/example.org/item", Intent.URI_ANDROID_APP_SCHEME);
        if (!"com.example".equals(i.getPackage()) || !"https://example.org/item".equals(i.getDataString())) {
            throw new AssertionError("android-app URI mismatch: " + i);
        }
        Intent mainOnly = Intent.parseUri("android-app://com.example", Intent.URI_ANDROID_APP_SCHEME);
        if (!"com.example".equals(mainOnly.getPackage()) || !Intent.ACTION_MAIN.equals(mainOnly.getAction()) || mainOnly.getData() != null) {
            throw new AssertionError("android-app main URI mismatch: " + mainOnly);
        }

        PatternMatcher adv = new PatternMatcher("[0-9]+", PatternMatcher.PATTERN_ADVANCED_GLOB);
        if (!adv.match("42") || adv.match("") || adv.match("42a")) {
            throw new AssertionError("PATTERN_ADVANCED_GLOB '[0-9]+' failed");
        }
        PatternMatcher neg = new PatternMatcher("/items/[^/]+/edit", PatternMatcher.PATTERN_ADVANCED_GLOB);
        if (!neg.match("/items/123/edit") || neg.match("/items/a/b/edit")) {
            throw new AssertionError("PATTERN_ADVANCED_GLOB negated set failed");
        }
        PatternMatcher range = new PatternMatcher("[a-f]{2,4}", PatternMatcher.PATTERN_ADVANCED_GLOB);
        if (!range.match("cafe") || range.match("a") || range.match("cafebabe")) {
            throw new AssertionError("PATTERN_ADVANCED_GLOB range failed");
        }
        PatternMatcher esc = new PatternMatcher("v1\\.0", PatternMatcher.PATTERN_SIMPLE_GLOB);
        if (!esc.match("v1.0") || esc.match("v1X0")) {
            throw new AssertionError("escaped dot in SIMPLE_GLOB matched non-dot");
        }
        Parcel p = Parcel.obtain();
        adv.writeToParcel(p, 0);
        p.setDataPosition(0);
        PatternMatcher fromParcel = PatternMatcher.CREATOR.createFromParcel(p);
        p.recycle();
        if (!fromParcel.match("99") || !"[0-9]+".equals(fromParcel.getPattern())) {
            throw new AssertionError("PatternMatcher Parcel round-trip failed");
        }
        try {
            new PatternMatcher("[0-9", PatternMatcher.PATTERN_ADVANCED_GLOB);
            throw new AssertionError("malformed ADVANCED_GLOB accepted");
        } catch (IllegalArgumentException expected) {}

        int badSr = AudioTrack.getMinBufferSize(-1, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int badFmt = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, 999);
        int badCh = AudioTrack.getMinBufferSize(48000, -1, AudioFormat.ENCODING_PCM_16BIT);
        if (badSr != AudioTrack.ERROR_BAD_VALUE || badFmt != AudioTrack.ERROR_BAD_VALUE || badCh != AudioTrack.ERROR_BAD_VALUE) {
            throw new AssertionError("invalid AudioTrack params did not return ERROR_BAD_VALUE");
        }
        System.out.println("PASS: FrameworkApiContractTest");
    }
}
