package android.speech.tts;

import android.content.Context;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// android.speech.tts.TextToSpeech -- the client-side synthesis-to-speech API
// (API 4+). AOSP frameworks/base core/java/android/speech/tts/TextToSpeech.java.
// It must exist even for apps that never speak (Minecraft's MainActivity
// constructs one in onCreate and aborts the whole activity -- never reaching
// setContentView or starting its native game surface -- if the class is absent).
//
// The engine is macOS's own speech synthesizer, driven through /usr/bin/say
// (MACAPK_TTS_SAY, default on). Each TextToSpeech owns one queue worker, as
// AOSP's TextToSpeechService owns one per caller: speak() enqueues (QUEUE_ADD)
// or flushes (QUEUE_FLUSH), and the worker reports every utterance through
// UtteranceProgressListener -- onStart, then onDone, onError or onStop -- on
// its own thread, the way AOSP reports from the service's binder thread.
// Apps chain readers, navigation prompts and quizzes on onDone, so an engine
// that answered SUCCESS and never called back (the previous no-op) left them
// waiting forever. The voice follows setLanguage (say -v '?' lists each
// voice's locale) and the rate follows setSpeechRate.
// MACAPK_TTS_SAY=0: nothing is spoken, but every utterance still starts and
// completes, so callers are never left waiting.
//
// The public surface (constructors, methods, constants, nested types) mirrors
// AOSP verbatim so any app -- not just Minecraft -- resolves against it.
public class TextToSpeech {

    // Result codes.
    public static final int SUCCESS = 0;
    public static final int ERROR = -1;
    public static final int STOPPED = -2;
    public static final int ERROR_SYNTHESIS = -3;
    public static final int ERROR_SERVICE = -4;
    public static final int ERROR_OUTPUT = -5;
    public static final int ERROR_NETWORK = -6;
    public static final int ERROR_NETWORK_TIMEOUT = -7;
    public static final int ERROR_INVALID_REQUEST = -8;
    public static final int ERROR_NOT_INSTALLED_YET = -9;

    // Queue modes.
    public static final int QUEUE_FLUSH = 0;
    public static final int QUEUE_ADD = 1;

    // Language-availability codes.
    public static final int LANG_COUNTRY_VAR_AVAILABLE = 2;
    public static final int LANG_COUNTRY_AVAILABLE = 1;
    public static final int LANG_AVAILABLE = 0;
    public static final int LANG_MISSING_DATA = -1;
    public static final int LANG_NOT_SUPPORTED = -2;

    public static final String ACTION_TTS_QUEUE_PROCESSING_COMPLETED =
        "android.speech.tts.TTS_QUEUE_PROCESSING_COMPLETED";

    // Listener invoked when the engine finishes (or fails) initialization. Real
    // Android binds to the TTS service asynchronously and delivers this on the
    // caller's thread; we post SUCCESS to the main Looper so it lands after the
    // constructor returns (the caller has usually just done a putfield with the
    // new instance, e.g. Minecraft's textToSpeechManager, and its onInit body
    // may read that field back).
    public interface OnInitListener {
        void onInit(int status);
    }

    // Deprecated per-utterance completion callback (superseded by
    // UtteranceProgressListener). Kept for API completeness.
    public interface OnUtteranceCompletedListener {
        void onUtteranceCompleted(String utteranceId);
    }

    private volatile Locale mLanguage = Locale.getDefault();
    private volatile float mSpeechRate = 1.0f;
    private float mPitch = 1.0f;

    // ---- the macOS engine ----------------------------------------------------

    private static final boolean USE_SAY = !"0".equals(System.getenv("MACAPK_TTS_SAY"));
    private static final String SAY = "/usr/bin/say";
    /** say's default rate in words per minute; Android's rate 1.0 is "normal". */
    private static final float SAY_NORMAL_WPM = 175f;

    /** One installed macOS voice: its name (may contain spaces) and locale. */
    private static final class HostVoice {
        final String name;
        final Locale locale;
        HostVoice(String name, Locale locale) { this.name = name; this.locale = locale; }
    }

    private static volatile List<HostVoice> sHostVoices;

    /** `say -v '?'` lines look like "Milena              ru_RU    # Здравствуйте!..."
     *  (two or more spaces separate the name from the locale). Empty when say is
     *  unavailable or MACAPK_TTS_SAY=0, which callers treat as "no voice data". */
    private static List<HostVoice> hostVoices() {
        List<HostVoice> v = sHostVoices;
        if (v != null) return v;
        List<HostVoice> out = new java.util.ArrayList<>();
        if (USE_SAY) {
            java.util.regex.Pattern p =
                java.util.regex.Pattern.compile("^(.+?)\\s{2,}([a-z]{2,3})_([A-Za-z0-9]+)\\s+#");
            try {
                Process pr = new ProcessBuilder(SAY, "-v", "?").redirectErrorStream(true).start();
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                        pr.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        java.util.regex.Matcher m = p.matcher(line);
                        if (m.find()) out.add(new HostVoice(m.group(1).trim(), new Locale(m.group(2), m.group(3))));
                    }
                }
                pr.waitFor();
            } catch (Throwable t) {
                android.util.Log.w("TextToSpeech", "listing macOS voices failed: " + t);
            }
        }
        sHostVoices = out = Collections.unmodifiableList(out);
        return out;
    }

    /** The voice for a locale: same language and country first, then same
     *  language; null means "let say use the system voice". */
    private static String voiceFor(Locale loc) {
        if (loc == null) return null;
        String sameLang = null;
        for (HostVoice hv : hostVoices()) {
            if (!hv.locale.getLanguage().equals(loc.getLanguage())) continue;
            if (hv.locale.getCountry().equalsIgnoreCase(loc.getCountry())) return hv.name;
            if (sameLang == null) sameLang = hv.name;
        }
        return sameLang;
    }

    /** One queued request. text == null is a silence of silenceMs. */
    private static final class Utterance {
        final String id;
        final String text;
        final long silenceMs;
        Utterance(String id, String text, long silenceMs) {
            this.id = id; this.text = text; this.silenceMs = silenceMs;
        }
    }

    private final Object mLock = new Object();
    private final java.util.ArrayDeque<Utterance> mQueue = new java.util.ArrayDeque<>();
    private Utterance mCurrent;
    private Process mCurrentProcess;
    private boolean mCurrentStopped;
    private Thread mWorker;
    private boolean mShutdown;
    private volatile UtteranceProgressListener mProgressListener;
    private volatile OnUtteranceCompletedListener mCompletedListener;

    private int enqueue(Utterance u, int queueMode) {
        synchronized (mLock) {
            if (mShutdown) return ERROR;
            if (queueMode == QUEUE_FLUSH) flushLocked();
            mQueue.addLast(u);
            if (mWorker == null) {
                mWorker = new Thread(this::workerLoop, "TextToSpeech");
                mWorker.setDaemon(true);
                mWorker.start();
            }
            mLock.notifyAll();
        }
        return SUCCESS;
    }

    /** QUEUE_FLUSH / stop(): drop the queue and interrupt the utterance in
     *  flight; each one is reported through onStop (AOSP
     *  AbstractSynthesisCallback.stop -> dispatchOnStop). */
    private void flushLocked() {
        java.util.List<Utterance> dropped = new java.util.ArrayList<>(mQueue);
        mQueue.clear();
        UtteranceProgressListener l = mProgressListener;
        if (l != null) for (Utterance u : dropped) safe(() -> l.onStop(u.id, false));
        if (mCurrent != null) {
            mCurrentStopped = true;
            if (mCurrentProcess != null) mCurrentProcess.destroy();
            mLock.notifyAll();   // wakes a silence in progress
        }
    }

    private void workerLoop() {
        while (true) {
            Utterance u;
            synchronized (mLock) {
                while (mQueue.isEmpty() && !mShutdown) {
                    try { mLock.wait(); } catch (InterruptedException e) { return; }
                }
                if (mShutdown) { mWorker = null; return; }
                u = mQueue.pollFirst();
                mCurrent = u;
                mCurrentStopped = false;
                mCurrentProcess = null;
            }
            UtteranceProgressListener startL = mProgressListener;
            if (startL != null) safe(() -> startL.onStart(u.id));
            boolean ok = u.text == null ? silence(u) : speakNow(u);
            boolean stopped;
            synchronized (mLock) {
                stopped = mCurrentStopped;
                mCurrent = null;
                mCurrentProcess = null;
            }
            UtteranceProgressListener l = mProgressListener;
            if (stopped) {
                if (l != null) safe(() -> l.onStop(u.id, true));
            } else if (ok) {
                if (l != null) safe(() -> l.onDone(u.id));
                OnUtteranceCompletedListener c = mCompletedListener;
                if (c != null && u.id != null) safe(() -> c.onUtteranceCompleted(u.id));
            } else if (l != null) {
                safe(() -> l.onError(u.id, ERROR_SYNTHESIS));
            }
        }
    }

    private boolean silence(Utterance u) {
        long end = System.currentTimeMillis() + Math.max(0, u.silenceMs);
        synchronized (mLock) {
            long left;
            while (!mCurrentStopped && !mShutdown && (left = end - System.currentTimeMillis()) > 0) {
                try { mLock.wait(left); } catch (InterruptedException e) { return false; }
            }
        }
        return true;
    }

    private boolean speakNow(Utterance u) {
        if (!USE_SAY) return true;
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(SAY);
        int wpm = Math.round(SAY_NORMAL_WPM * (mSpeechRate > 0 ? mSpeechRate : 1f));
        cmd.add("-r");
        cmd.add(Integer.toString(Math.max(50, Math.min(700, wpm))));
        String voice = voiceFor(mLanguage);
        if (voice != null) { cmd.add("-v"); cmd.add(voice); }
        // The text goes in on stdin, never argv: no length limit, and a text that
        // starts with '-' cannot be read as an option.
        cmd.add("-f");
        cmd.add("-");
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            synchronized (mLock) {
                if (mCurrentStopped) { p.destroy(); return false; }
                mCurrentProcess = p;
            }
            try (java.io.OutputStream os = p.getOutputStream()) {
                os.write(u.text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (java.io.IOException ignored) {
                // say already exited (stopped); the exit code below says why
            }
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            return p.waitFor() == 0;
        } catch (Throwable t) {
            android.util.Log.w("TextToSpeech", "say failed: " + t);
            return false;
        }
    }

    private static void safe(Runnable r) {
        try { r.run(); } catch (Throwable t) { android.util.Log.w("TextToSpeech", "listener threw: " + t); }
    }

    public TextToSpeech(Context context, OnInitListener listener) {
        this(context, listener, null);
    }

    public TextToSpeech(Context context, OnInitListener listener, String engine) {
        this(context, listener, engine, null, true);
    }

    public TextToSpeech(Context context, OnInitListener listener, String engine,
                        String packageName, boolean useFallback) {
        dispatchOnInit(listener);
    }

    private static void dispatchOnInit(final OnInitListener listener) {
        if (listener == null) {
            return;
        }
        Looper main = Looper.getMainLooper();
        Runnable r = new Runnable() {
            @Override public void run() {
                listener.onInit(SUCCESS);
            }
        };
        if (main != null) {
            new Handler(main).post(r);
        } else {
            // No looper available; deliver inline as a last resort.
            r.run();
        }
    }

    public int setLanguage(final Locale loc) {
        int avail = isLanguageAvailable(loc);
        // AOSP keeps the old language when the new one is not supported.
        if (loc != null && avail >= LANG_AVAILABLE) mLanguage = loc;
        return avail;
    }

    public Locale getLanguage() { return mLanguage; }

    public Locale getDefaultLanguage() { return Locale.getDefault(); }

    /** From the installed macOS voices. With no voice list at all (say
     *  missing, or MACAPK_TTS_SAY=0) every language reads as available, the
     *  previous answer, so callers stay on their happy path instead of
     *  prompting to install voice data that cannot be installed. */
    public int isLanguageAvailable(final Locale loc) {
        if (loc == null) return LANG_NOT_SUPPORTED;
        List<HostVoice> voices = hostVoices();
        if (voices.isEmpty()) return LANG_AVAILABLE;
        int best = LANG_NOT_SUPPORTED;
        for (HostVoice hv : voices) {
            if (!hv.locale.getLanguage().equals(loc.getLanguage())) continue;
            if (!loc.getCountry().isEmpty() && hv.locale.getCountry().equalsIgnoreCase(loc.getCountry()))
                return LANG_COUNTRY_AVAILABLE;
            best = LANG_AVAILABLE;
        }
        return best;
    }

    public Set<Locale> getAvailableLanguages() {
        Set<Locale> out = new java.util.HashSet<>();
        for (HostVoice hv : hostVoices()) out.add(hv.locale);
        return out;
    }

    public int setSpeechRate(float speechRate) { mSpeechRate = speechRate; return SUCCESS; }

    public int setPitch(float pitch) { mPitch = pitch; return SUCCESS; }

    public int setAudioAttributes(AudioAttributes audioAttributes) { return SUCCESS; }

    public int speak(final CharSequence text, final int queueMode,
                     final Bundle params, final String utteranceId) {
        if (text == null || text.length() > getMaxSpeechInputLength()) return ERROR;
        return enqueue(new Utterance(utteranceId, text.toString(), 0), queueMode);
    }

    public int speak(final String text, final int queueMode,
                     final HashMap<String, String> params) {
        String id = params == null ? null : params.get(Engine.KEY_PARAM_UTTERANCE_ID);
        return speak(text, queueMode, (Bundle) null, id);
    }

    public int playEarcon(final String earcon, final int queueMode,
                          final Bundle params, final String utteranceId) {
        return SUCCESS;
    }

    public int playEarcon(final String earcon, final int queueMode,
                          final HashMap<String, String> params) {
        return SUCCESS;
    }

    public int playSilentUtterance(final long durationInMs, final int queueMode,
                                   final String utteranceId) {
        return enqueue(new Utterance(utteranceId, null, durationInMs), queueMode);
    }

    public int playSilence(final long durationInMs, final int queueMode,
                           final HashMap<String, String> params) {
        String id = params == null ? null : params.get(Engine.KEY_PARAM_UTTERANCE_ID);
        return playSilentUtterance(durationInMs, queueMode, id);
    }

    public int addSpeech(String text, String packagename, int resourceId) { return SUCCESS; }
    public int addSpeech(CharSequence text, String packagename, int resourceId) { return SUCCESS; }
    public int addSpeech(String text, String filename) { return SUCCESS; }
    public int addSpeech(CharSequence text, File file) { return SUCCESS; }
    public int addSpeech(CharSequence text, Uri uri) { return SUCCESS; }

    public int addEarcon(String earcon, String packagename, int resourceId) { return SUCCESS; }
    public int addEarcon(String earcon, String filename) { return SUCCESS; }
    public int addEarcon(String earcon, File file) { return SUCCESS; }
    public int addEarcon(String earcon, Uri uri) { return SUCCESS; }

    public boolean isSpeaking() {
        synchronized (mLock) { return mCurrent != null || !mQueue.isEmpty(); }
    }

    public int stop() {
        synchronized (mLock) { flushLocked(); }
        return SUCCESS;
    }

    public void shutdown() {
        synchronized (mLock) {
            flushLocked();
            mShutdown = true;
            mLock.notifyAll();
        }
    }

    public Set<String> getFeatures(final Locale locale) { return Collections.emptySet(); }

    public Set<Voice> getVoices() { return Collections.emptySet(); }

    public int setVoice(final Voice voice) { return SUCCESS; }

    public Voice getVoice() { return null; }

    public Voice getDefaultVoice() { return null; }

    public int synthesizeToFile(final CharSequence text, final Bundle params,
                                final ParcelFileDescriptor fileDescriptor,
                                final String utteranceId) {
        return ERROR;
    }

    public int synthesizeToFile(final CharSequence text, final Bundle params,
                                final File file, final String utteranceId) {
        return ERROR;
    }

    public int synthesizeToFile(final String text, final HashMap<String, String> params,
                                final String filename) {
        return ERROR;
    }

    public int setOnUtteranceCompletedListener(final OnUtteranceCompletedListener listener) {
        mCompletedListener = listener;
        return SUCCESS;
    }

    public int setOnUtteranceProgressListener(UtteranceProgressListener listener) {
        mProgressListener = listener;
        return SUCCESS;
    }

    public int setEngineByPackageName(String enginePackageName) { return SUCCESS; }

    public String getDefaultEngine() { return Engine.DEFAULT_ENGINE; }

    /** AOSP getCurrentEngine(): the package name of the engine in use. We run the
     *  single default engine, so this equals getDefaultEngine(). */
    public String getCurrentEngine() { return Engine.DEFAULT_ENGINE; }

    public boolean areDefaultsEnforced() { return false; }

    public List<EngineInfo> getEngines() { return Collections.emptyList(); }

    /** AOSP getMaxSpeechInputLength(): the maximum String length accepted by
     *  speak(). Public SDK API apps use to chunk long text. AOSP returns 4000. */
    public static int getMaxSpeechInputLength() { return 4000; }

    // Engine-level constants and intent actions. AOSP declares these on the
    // nested Engine class; apps reference e.g. TextToSpeech.Engine.KEY_PARAM_*.
    public static class Engine {
        public static final int DEFAULT_RATE = 100;
        public static final int DEFAULT_PITCH = 100;
        public static final float DEFAULT_VOLUME = 1.0f;
        public static final float DEFAULT_PAN = 0.0f;
        public static final int USE_DEFAULTS = 0;
        public static final String DEFAULT_ENGINE = "com.svox.pico";
        public static final int DEFAULT_STREAM = 3; // AudioManager.STREAM_MUSIC
        public static final int CHECK_VOICE_DATA_PASS = 1;
        public static final int CHECK_VOICE_DATA_FAIL = 0;
        public static final int CHECK_VOICE_DATA_BAD_DATA = -1;
        public static final int CHECK_VOICE_DATA_MISSING_DATA = -2;
        public static final int CHECK_VOICE_DATA_MISSING_VOLUME = -3;
        public static final String INTENT_ACTION_TTS_SERVICE = "android.intent.action.TTS_SERVICE";
        public static final String SERVICE_META_DATA = "android.speech.tts";
        public static final String ACTION_INSTALL_TTS_DATA = "android.speech.tts.engine.INSTALL_TTS_DATA";
        public static final String ACTION_TTS_DATA_INSTALLED = "android.speech.tts.engine.TTS_DATA_INSTALLED";
        public static final String ACTION_CHECK_TTS_DATA = "android.speech.tts.engine.CHECK_TTS_DATA";
        public static final String ACTION_GET_SAMPLE_TEXT = "android.speech.tts.engine.GET_SAMPLE_TEXT";
        public static final String EXTRA_SAMPLE_TEXT = "sampleText";
        public static final String EXTRA_AVAILABLE_VOICES = "availableVoices";
        public static final String EXTRA_UNAVAILABLE_VOICES = "unavailableVoices";
        public static final String EXTRA_VOICE_DATA_ROOT_DIRECTORY = "dataRoot";
        public static final String EXTRA_VOICE_DATA_FILES = "dataFiles";
        public static final String EXTRA_VOICE_DATA_FILES_INFO = "dataFilesInfo";
        public static final String EXTRA_CHECK_VOICE_DATA_FOR = "checkVoiceDataFor";
        public static final String EXTRA_TTS_DATA_INSTALLED = "dataInstalled";
        public static final String KEY_PARAM_RATE = "rate";
        public static final String KEY_PARAM_VOICE_NAME = "voiceName";
        public static final String KEY_PARAM_LANGUAGE = "language";
        public static final String KEY_PARAM_COUNTRY = "country";
        public static final String KEY_PARAM_VARIANT = "variant";
        public static final String KEY_PARAM_ENGINE = "engine";
        public static final String KEY_PARAM_PITCH = "pitch";
        public static final String KEY_PARAM_STREAM = "streamType";
        public static final String KEY_PARAM_AUDIO_ATTRIBUTES = "audioAttributes";
        public static final String KEY_PARAM_UTTERANCE_ID = "utteranceId";
        public static final String KEY_PARAM_VOLUME = "volume";
        public static final String KEY_PARAM_PAN = "pan";
        public static final String KEY_FEATURE_NETWORK_SYNTHESIS = "networkTts";
        public static final String KEY_FEATURE_EMBEDDED_SYNTHESIS = "embeddedTts";
        public static final String KEY_PARAM_SESSION_ID = "sessionId";
        public static final String KEY_FEATURE_NOT_INSTALLED = "notInstalled";
        public static final String KEY_FEATURE_NETWORK_TIMEOUT_MS = "networkTimeoutMs";
        public static final String KEY_FEATURE_NETWORK_RETRIES_COUNT = "networkRetriesCount";
    }

    // Describes an installed engine. AOSP populates these from resolved TTS
    // services; Mac.apk has none, so getEngines() returns an empty list and this
    // exists only for the return element type to resolve.
    public static class EngineInfo {
        public String name;
        public String label;
        public int icon;
        public int priority;
        public boolean system;

        @Override public String toString() {
            return "EngineInfo{name=" + name + "}";
        }
    }
}
