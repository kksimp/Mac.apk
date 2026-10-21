// android.app.PendingIntent -- a token wrapping an Intent + the
// operation to perform with it (launch an activity, start a service,
// or fire a broadcast) that another process (or, on Mac.apk, a
// deferred caller like AlarmManager / NotificationManager) can later
// trigger on the creating app's behalf. AOSP API-1+.
//
// AOSP ref: frameworks/base/core/java/android/app/PendingIntent.java.
// Real Android marshals the wrapped Intent + target type through the
// ActivityManager. Mac.apk is single-process, so we hold the Intent
// directly and, on send(), dispatch it through our own
// Context.startActivity / startService / sendBroadcast path -- exactly
// the operation type the factory recorded. This makes notification
// taps, alarm callbacks, and widget actions actually fire the wrapped
// Intent instead of silently dropping it.
package android.app;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
public final class PendingIntent implements Parcelable {
    public static final int FLAG_ONE_SHOT       = 0x40000000;
    public static final int FLAG_NO_CREATE      = 0x20000000;
    public static final int FLAG_CANCEL_CURRENT = 0x10000000;
    public static final int FLAG_UPDATE_CURRENT = 0x08000000;
    public static final int FLAG_IMMUTABLE      = 0x04000000;
    public static final int FLAG_MUTABLE        = 0x02000000;
    public static final int FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT = 0x01000000;
    public static final int FLAG_INCLUDE_OUT_OF_BAND_ACCESSORY_NETWORK_SLICES = 0x00800000;

    // Operation-type discriminator: which dispatch path send() takes.
    // Mirrors AOSP's internal ActivityManager.INTENT_SENDER_* kinds.
    private static final int TYPE_ACTIVITY   = 0;
    private static final int TYPE_ACTIVITIES = 1;
    private static final int TYPE_BROADCAST  = 2;
    private static final int TYPE_SERVICE    = 3;
    private static final int TYPE_FOREGROUND_SERVICE = 4;

    private int type = TYPE_ACTIVITY;
    private int requestCode;
    private int flags;
    private Context creatorContext;
    private Intent intent;          // single-intent forms
    private Intent[] intents;       // getActivities form
    private boolean canceled;

    private PendingIntent() {}

    /** The identity of a token: AOSP PendingIntentRecord.Key (android14
     *  services/core/java/com/android/server/am/PendingIntentRecord.java
     *  :77-160) -- operation type, request code, the creation flags minus the
     *  three lookup-only ones, and the Intent compared with filterEquals
     *  (action, data, type, identifier, package, component, categories; NOT
     *  extras). For getActivities AOSP keys on the last Intent. */
    private static final int LOOKUP_ONLY_FLAGS =
        FLAG_NO_CREATE | FLAG_CANCEL_CURRENT | FLAG_UPDATE_CURRENT;

    private static final class Key {
        final int type, requestCode, flags;
        final Intent intent;
        Key(int type, int requestCode, int flags, Intent intent) {
            this.type = type;
            this.requestCode = requestCode;
            this.flags = flags & ~LOOKUP_ONLY_FLAGS;
            this.intent = intent;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            if (type != k.type || requestCode != k.requestCode || flags != k.flags) return false;
            if (intent == null) return k.intent == null;
            return k.intent != null && intent.filterEquals(k.intent);
        }
        @Override public int hashCode() {
            int h = 31 * (31 * type + requestCode) + flags;
            return 31 * h + (intent == null ? 0 : intent.filterHashCode());
        }
    }

    private Key key() { return new Key(type, requestCode, flags, getIntent()); }

    /** Live tokens by Key: ActivityManagerService.mIntentSenderRecords, which
     *  is what makes FLAG_NO_CREATE / FLAG_UPDATE_CURRENT / FLAG_CANCEL_CURRENT
     *  mean anything. Weak like AOSP's, so a token nobody holds is dropped. */
    private static final java.util.HashMap<Key, java.lang.ref.WeakReference<PendingIntent>>
        sLive = new java.util.HashMap<>();

    private static PendingIntent liveLocked(Key k) {
        java.lang.ref.WeakReference<PendingIntent> ref = sLive.get(k);
        PendingIntent p = ref == null ? null : ref.get();
        if (p == null || p.canceled) {
            sLive.remove(k);
            return null;
        }
        return p;
    }

    /** AOSP ActivityManagerService.getIntentSenderLocked (android14
     *  PendingIntentController.getIntentSender :111-170): an existing token
     *  for the same Key is returned (extras replaced under UPDATE_CURRENT),
     *  or cancelled under CANCEL_CURRENT; NO_CREATE returns the existing
     *  token or null; otherwise a new token is registered. The Intents are
     *  copied, as AOSP's travel through a Parcel, so a caller mutating its
     *  Intent afterwards changes neither the token nor its Key. */
    private static PendingIntent make(int type, Context c, int requestCode,
                                      Intent intent, Intent[] intents, int flags) {
        if (intent != null) intent = new Intent(intent);
        if (intents != null) {
            Intent[] copy = new Intent[intents.length];
            for (int i = 0; i < intents.length; i++)
                copy[i] = intents[i] == null ? null : new Intent(intents[i]);
            intents = copy;
        }
        Intent keyIntent = intent != null ? intent
            : (intents != null && intents.length > 0 ? intents[intents.length - 1] : null);
        Key k = new Key(type, requestCode, flags, keyIntent);
        synchronized (sLive) {
            PendingIntent existing = liveLocked(k);
            if (existing != null) {
                if ((flags & FLAG_CANCEL_CURRENT) == 0) {
                    if ((flags & FLAG_UPDATE_CURRENT) != 0) {
                        if (existing.intent != null) existing.intent.replaceExtras(intent);
                        if (intents != null) existing.intents = intents;
                    }
                    return existing;
                }
                existing.cancel();
            }
            if ((flags & FLAG_NO_CREATE) != 0) return null;
            PendingIntent p = new PendingIntent();
            p.type = type;
            p.creatorContext = c;
            p.requestCode = requestCode;
            p.intent = intent;
            p.intents = intents;
            p.flags = flags & ~LOOKUP_ONLY_FLAGS;
            sLive.put(k, new java.lang.ref.WeakReference<>(p));
            return p;
        }
    }

    // AOSP: getActivity has both 4-arg and 5-arg (with Bundle options) overloads.
    // Per https://developer.android.com/reference/android/app/PendingIntent both
    // PendingIntent.getActivity and PendingIntent.getActivities have Bundle overloads.
    public static PendingIntent getActivity(Context c, int requestCode, Intent intent, int flags) {
        return make(TYPE_ACTIVITY, c, requestCode, intent, null, flags);
    }
    public static PendingIntent getActivity(Context c, int requestCode, Intent intent, int flags, Bundle options) {
        return make(TYPE_ACTIVITY, c, requestCode, intent, null, flags);
    }
    public static PendingIntent getActivities(Context c, int requestCode, Intent[] intents, int flags) {
        return make(TYPE_ACTIVITIES, c, requestCode, null, intents, flags);
    }
    public static PendingIntent getActivities(Context c, int requestCode, Intent[] intents, int flags, Bundle options) {
        return make(TYPE_ACTIVITIES, c, requestCode, null, intents, flags);
    }
    public static PendingIntent getBroadcast(Context c, int requestCode, Intent intent, int flags) {
        return make(TYPE_BROADCAST, c, requestCode, intent, null, flags);
    }
    public static PendingIntent getService(Context c, int requestCode, Intent intent, int flags) {
        return make(TYPE_SERVICE, c, requestCode, intent, null, flags);
    }
    public static PendingIntent getForegroundService(Context c, int requestCode, Intent intent, int flags) {
        return make(TYPE_FOREGROUND_SERVICE, c, requestCode, intent, null, flags);
    }

    /** MACAPK_PI_UNRESOLVED_CANCELED (default on, v1.0.1653): an ACTIVITY
     *  PendingIntent whose intent resolves to nothing makes send() throw
     *  CanceledException, as on a device. AOSP android14:
     *  PendingIntentRecord.sendInner (:553-577) returns
     *  startActivityInPackage's START_* result -- negative for
     *  START_INTENT_NOT_RESOLVED / START_CLASS_NOT_FOUND -- and
     *  PendingIntent.send (:1048-1056) throws when sendAndReturnResult < 0.
     *  A RuntimeException out of the start (a SecurityException) is caught
     *  and logged there with res left at START_SUCCESS, so it still does not
     *  throw; nor does anything a broadcast / service send does. Here the
     *  start's "not resolved" is ActivityNotFoundException (ActivityHost's
     *  stage 2b). =0: every failure is swallowed, the pre-change answer. */
    private static final boolean UNRESOLVED_CANCELED =
        !"0".equals(System.getenv("MACAPK_PI_UNRESOLVED_CANCELED"));
    /** sendInner's res: START_SUCCESS, or negative for a start that did not
     *  resolve (the value itself is private to this class). */
    private static final int RES_OK = 0, RES_NOT_RESOLVED = -1;
    /** ActivityManager.START_CANCELED's role: the sender itself is cancelled. */
    static final int RES_CANCELED = -2;
    static boolean macapkUnresolvedCanceled() { return UNRESOLVED_CANCELED; }

    /** Activity.startIntentSenderForResultInner's start (android14 :5969-5978):
     *  PendingIntentRecord.sendWithResult -> sendInner, whose result the
     *  Activity then turns into SendIntentException (cancelled) or
     *  ActivityNotFoundException (checkStartActivityResult). No OnFinished. */
    int macapkStartIntentSender(Context c, Intent fillIn) {
        if (canceled) return RES_CANCELED;
        return dispatch(c, fillIn);
    }

    /** Dispatch the wrapped Intent via the recorded operation type.
     *  dispatchContext lets a sender supply the Context whose
     *  startActivity/startService runs the launch; falls back to the creator
     *  context, then the Application. The fill-in Intent is MERGED into a
     *  copy of the base per AOSP, never substituted -- see fillInto().
     *  Returns sendInner's result: negative only for an activity start that
     *  resolved to nothing (see UNRESOLVED_CANCELED). */
    private int dispatch(Context dispatchContext, Intent overrideIntent) {
        if (canceled) return RES_OK;
        Context ctx = dispatchContext != null ? dispatchContext
                    : (creatorContext != null ? creatorContext : Application.getInstance());
        if (ctx == null) return RES_OK;
        try {
            switch (type) {
                case TYPE_ACTIVITIES:
                    if (intents != null) ctx.startActivities(intents);
                    break;
                case TYPE_BROADCAST: {
                    Intent i = fillInto(overrideIntent);
                    if (i != null) ctx.sendBroadcast(i);
                    break;
                }
                case TYPE_SERVICE: {
                    Intent i = fillInto(overrideIntent);
                    if (i != null) ctx.startService(i);
                    break;
                }
                case TYPE_FOREGROUND_SERVICE: {
                    Intent i = fillInto(overrideIntent);
                    if (i != null) ctx.startForegroundService(i);
                    break;
                }
                case TYPE_ACTIVITY:
                default: {
                    Intent i = fillInto(overrideIntent);
                    if (i != null) ctx.startActivity(i);
                    break;
                }
            }
        } catch (android.content.ActivityNotFoundException anf) {
            if (UNRESOLVED_CANCELED && (type == TYPE_ACTIVITY || type == TYPE_ACTIVITIES)) {
                android.util.Log.w("PendingIntent", "Unable to send startActivity intent: " + anf.getMessage());
                return RES_NOT_RESOLVED;
            }
        } catch (Throwable ignored) {
            // A failed deferred launch must not crash the sender (e.g.
            // AlarmManager's scheduled-executor thread): sendInner catches
            // RuntimeException from every start and leaves res at
            // START_SUCCESS, so nothing is thrown out of send().
        }
        return RES_OK;
    }

    /** AOSP send(Context, int, Intent) semantics, from
     *  PendingIntentRecord.sendInner (frameworks/base services/core/java/
     *  com/android/server/am/PendingIntentRecord.java): the caller's Intent
     *  is FILL-IN data merged into a COPY of the stored base --
     *  `finalIntent = new Intent(key.requestIntent);
     *   finalIntent.fillIn(intent, key.flags);` -- and an IMMUTABLE token
     *  ignores it entirely. It is never a substitute: the base's action,
     *  explicit component, and extras survive, with the fill-in's extras
     *  merged on top (Intent.fillIn always merges extras; identity fields
     *  copy only where the base left them undefined).
     *
     *  ⚠ Until v1.0.956 this PREFERRED the override wholesale, which
     *  dropped the base Intent's action + component + extras. That is
     *  invisible for no-arg send() callers, but it is fatal for
     *  PackageInstaller session status delivery: the system's status
     *  fill-in (EXTRA_STATUS etc.) would REPLACE the store's callback
     *  Intent instead of merging into it, so the store's receiver -- keyed
     *  on its own action (Aurora's `action == ACTION_INSTALL_STATUS` guard)
     *  or reached only via its explicit component -- never fired, and its
     *  own extras (Aurora reads its EXTRA_PACKAGE_NAME `?: return`) were
     *  gone even when it did. The same applied to
     *  Activity.startIntentSender's fillInIntent. */
    private Intent fillInto(Intent overrideIntent) {
        if (overrideIntent == null) return intent;
        if ((flags & FLAG_IMMUTABLE) != 0) return intent;
        if (intent == null) return overrideIntent;
        Intent merged = new Intent(intent);
        // AOSP passes the creation flags as the fillIn flags; the FILL_IN_*
        // bits live in the low byte and every PendingIntent.FLAG_* is a high
        // bit, so for real callers this is fillIn(other, 0) -- extras merge,
        // identity fields only fill where the base is undefined.
        merged.fillIn(overrideIntent, flags);
        return merged;
    }

    public void send() throws CanceledException {
        if (canceled) throw new CanceledException();
        if (dispatch(null, null) < 0) throw new CanceledException();
    }
    public void send(int code) throws CanceledException {
        if (canceled) throw new CanceledException();
        if (dispatch(null, null) < 0) throw new CanceledException();
    }
    // sendInner delivers OnFinished for any res other than START_CANCELED
    // (:623-629) and only then does send() see res < 0 and throw, so the
    // callback fires first even for a start that did not resolve.
    public void send(Context c, int code, Intent intent) throws CanceledException {
        if (canceled) throw new CanceledException();
        int res = dispatch(c, intent);
        fireFinished(c, intent, code, null, null, null, null);
        if (res < 0) throw new CanceledException();
    }
    public void send(Context c, int code, Intent intent, OnFinished onFinished, android.os.Handler handler) throws CanceledException {
        if (canceled) throw new CanceledException();
        int res = dispatch(c, intent);
        fireFinished(c, intent, code, null, null, onFinished, handler);
        if (res < 0) throw new CanceledException();
    }
    public void send(Context c, int code, Intent intent, OnFinished onFinished, android.os.Handler handler, String requiredPermission) throws CanceledException {
        if (canceled) throw new CanceledException();
        int res = dispatch(c, intent);
        fireFinished(c, intent, code, null, null, onFinished, handler);
        if (res < 0) throw new CanceledException();
    }
    public void send(Context c, int code, Intent intent, OnFinished onFinished, android.os.Handler handler, String requiredPermission, Bundle options) throws CanceledException {
        if (canceled) throw new CanceledException();
        int res = dispatch(c, intent);
        fireFinished(c, intent, code, null, null, onFinished, handler);
        if (res < 0) throw new CanceledException();
    }
    public void send(int code, OnFinished onFinished, android.os.Handler handler) throws CanceledException {
        if (canceled) throw new CanceledException();
        int res = dispatch(null, null);
        fireFinished(null, intent, code, null, null, onFinished, handler);
        if (res < 0) throw new CanceledException();
    }

    /** Notify the OnFinished callback after a send, on the supplied
     *  Handler when given (AOSP posts it there), else inline. */
    private void fireFinished(Context c, Intent fired, int resultCode, String resultData,
                              Bundle resultExtras, OnFinished cb, android.os.Handler handler) {
        if (cb == null) return;
        final Intent fi = fired != null ? fired : intent;
        Runnable r = () -> {
            try { cb.onSendFinished(this, fi, resultCode, resultData, resultExtras); }
            catch (Throwable ignored) {}
        };
        if (handler != null) handler.post(r); else r.run();
    }

    public void cancel() {
        synchronized (sLive) {
            canceled = true;
            Key k = key();
            java.lang.ref.WeakReference<PendingIntent> ref = sLive.get(k);
            if (ref != null && (ref.get() == this || ref.get() == null)) sLive.remove(k);
        }
    }

    /** AOSP API-31+ -- returns an IntentSender that wraps this token.
     *  IntentSender simply re-dispatches through this PendingIntent. */
    public android.content.IntentSender getIntentSender() {
        return new android.content.IntentSender(this);
    }

    public String getCreatorPackage() {
        return creatorContext != null ? creatorContext.getPackageName() : null;
    }
    public String getTargetPackage() { return getCreatorPackage(); }
    public int getCreatorUid() { return 0; }
    public Intent getIntent() {
        if (intent != null) return intent;
        if (intents != null && intents.length > 0) return intents[intents.length - 1];
        return null;
    }
    public boolean isImmutable() { return (flags & FLAG_IMMUTABLE) != 0; }
    public boolean isActivity()  { return type == TYPE_ACTIVITY || type == TYPE_ACTIVITIES; }
    public boolean isBroadcast() { return type == TYPE_BROADCAST; }
    public boolean isService()   { return type == TYPE_SERVICE || type == TYPE_FOREGROUND_SERVICE; }
    public boolean isForegroundService() { return type == TYPE_FOREGROUND_SERVICE; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PendingIntent)) return false;
        PendingIntent p = (PendingIntent) o;
        // AOSP equality is the IIntentSender token, and one token exists per
        // PendingIntentRecord.Key (see make()). Comparing Keys gives the same
        // answer here and also for a copy that came back through a Parcel.
        // Before, only component + action were compared, so two alarms whose
        // Intents differed only in data URI (or categories, type, package)
        // were "equal" and AlarmManager's cancel-before-schedule removed one.
        return key().equals(p.key());
    }
    @Override public int hashCode() { return key().hashCode(); }

    // ---- Parcelable ----
    //
    // ⚠ Until v1.0.940 this was an empty body plus a CREATOR that answered a
    // blank token. That was invisible while android.os.Parcel faked marshalling
    // with a side list of live objects; now that it marshals for real, the
    // single most common carrier of a PendingIntent -- a Notification, which is
    // built with contentIntent/actions and then travels through a Bundle --
    // would arrive with a token whose send() dispatches nothing. A tapped
    // notification that does nothing is exactly the kind of failure that shows
    // up as "the app is broken" with no log line.
    //
    // AOSP writes `out.writeStrongBinder(mTarget.asBinder())`
    // (frameworks/base/core/java/android/app/PendingIntent.java:1219-1226), a
    // handle to the ActivityManager-side IIntentSender that holds the real
    // Intent. Mac.apk is single-process with no binder and no ActivityManager,
    // so this class IS the token: the Intent and the operation type live here,
    // and flattening them is the faithful analogue. AOSP's OnMarshaledListener
    // hook has no counterpart -- it exists so the system can detect an
    // implicit-Intent PendingIntent leaving the process, and nothing leaves.
    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(Parcel dest, int parcelableFlags) {
        dest.writeInt(type);
        dest.writeInt(requestCode);
        dest.writeInt(this.flags);
        // creatorContext ABSENT-MARKER. A Context is a live process object and
        // is not Parcelable on AOSP either, so there is nothing to flatten; the
        // marker records that one EXISTED so the reader can restore the only
        // correct in-process answer instead of silently producing a token whose
        // getCreatorPackage() is null and whose dispatch() has no Context.
        // Mac.apk hosts exactly one app, so Application.getInstance() IS the
        // creator's context; on AOSP the creator may be another process, which
        // is why AOSP carries a binder instead.
        // REMOVAL CONDITION: if Mac.apk ever hosts more than one package in a
        // process, this has to carry the creator's identity (package + uid) and
        // resolve it on read, not assume the single Application.
        dest.writeInt(creatorContext != null ? 1 : 0);
        dest.writeTypedObject(intent, parcelableFlags);
        dest.writeTypedArray(intents, parcelableFlags);
        dest.writeInt(canceled ? 1 : 0);
    }

    public static final Creator<PendingIntent> CREATOR = new Creator<PendingIntent>() {
        @Override public PendingIntent createFromParcel(Parcel s) {
            PendingIntent p = new PendingIntent();
            p.type = s.readInt();
            p.requestCode = s.readInt();
            p.flags = s.readInt();
            if (s.readInt() != 0) p.creatorContext = Application.getInstance();
            p.intent = s.readTypedObject(Intent.CREATOR);
            p.intents = s.createTypedArray(Intent.CREATOR);
            p.canceled = s.readInt() != 0;
            // AOSP unparcels the same binder, i.e. the same token: hand back
            // the live one so cancel() / UPDATE_CURRENT on either side is seen
            // by both.
            if (!p.canceled) {
                synchronized (sLive) {
                    PendingIntent live = liveLocked(p.key());
                    if (live != null) return live;
                }
            }
            return p;
        }
        @Override public PendingIntent[] newArray(int size) { return new PendingIntent[size]; }
    };
    public interface OnFinished {
        void onSendFinished(PendingIntent p, Intent intent, int resultCode, String resultData, Bundle resultExtras);
    }

    /** Thrown by send() when the PendingIntent has been canceled.
     *  AOSP nests this in PendingIntent; callers catch it to detect a
     *  revoked token. */
    public static class CanceledException extends android.util.AndroidException {
        public CanceledException() {}
        public CanceledException(String name) { super(name); }
        public CanceledException(Exception cause) { super(cause); }
    }

    /** AOSP static helpers exposing the IntentSender's creator info
     *  without an instance (used by some readback code). */
    public static PendingIntent readPendingIntentOrNullFromParcel(Parcel in) { return null; }
    public static void writePendingIntentOrNullToParcel(PendingIntent sender, Parcel out) {}

    public void send(android.os.Bundle options) throws CanceledException { send(); }

    /** AOSP getCreatorUserHandle(): {@code UserHandle.getUserHandleForUid(
     *  getCachedInfo().getCreatorUid())} -- "the user handle of the app that
     *  created this PendingIntent", never null. Mac.apk runs one app as one
     *  process for one user, and every PendingIntent here is created by that
     *  app, so this is the process's own user -- the same one
     *  android.os.Process.myUserHandle() reports. */
    public android.os.UserHandle getCreatorUserHandle() {
        return android.os.Process.myUserHandle();
    }
}
