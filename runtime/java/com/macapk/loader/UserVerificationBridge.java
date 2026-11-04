package com.macapk.loader;

import android.os.CancellationSignal;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/** Host user verification, not an Android biometric-strength certification.
 * Only the native OS reply can create authorization; Java cannot mark success. */
public final class UserVerificationBridge {
    private UserVerificationBridge(){}
    public static final int STRONG=0x000f, WEAK=0x00ff, DEVICE_CREDENTIAL=0x8000;
    private static final boolean ON="1".equals(System.getenv("MACAPK_USER_VERIFICATION_HOST"));
    private static final AtomicLong IDS=new AtomicLong();
    private static final Map<Long,Session> SESSIONS=new ConcurrentHashMap<>();
    private static native boolean nativeBound();
    private static native int nativeCanAuthenticate(int mask);
    private static native boolean nativeBegin(long id,int mask,String reason,Object crypto,String negativeText);
    private static native void nativeCancel(long id);
    private static native int[] nativeTakeResult(long id);
    private static native long nativeLastAuthenticationTime(int mask);
    private static native void nativeBeginKeyOperation(Object spi);
    private static native boolean nativeConsumeAuthorization(Object spi,boolean perUse,int seconds,int mask);
    public static boolean available(){try{return ON&&nativeBound();}catch(UnsatisfiedLinkError e){return false;}}
    public static int canAuthenticate(int mask){return available()?nativeCanAuthenticate(mask):12;}
    public static long lastAuthenticationTime(int mask){return available()?nativeLastAuthenticationTime(mask):-1;}
    /** Revokes a previous operation's authorization. This never grants access. */
    public static void beginKeyOperation(Object spi){if(available()&&spi!=null)nativeBeginKeyOperation(spi);}
    public static boolean consumeAuthorization(Object spi,boolean perUse,int seconds,int mask){
        return available()&&spi!=null&&nativeConsumeAuthorization(spi,perUse,seconds,mask);
    }
    @FunctionalInterface public interface Callback {
        void completed(boolean success,int authenticationType,int errorCode,String message);
    }
    public static void authenticate(int mask,String reason,CancellationSignal cancel,Executor executor,Callback callback){authenticate(mask,reason,null,null,cancel,executor,callback);}
    public static void authenticate(int mask,String reason,Object crypto,CancellationSignal cancel,Executor executor,Callback callback){authenticate(mask,reason,crypto,null,cancel,executor,callback);}
    public static void authenticate(int mask,String reason,Object crypto,String negativeText,CancellationSignal cancel,Executor executor,Callback callback){
        Objects.requireNonNull(executor,"executor");Objects.requireNonNull(callback,"callback");
        long id=IDS.incrementAndGet();Session session=new Session(id,cancel,executor,callback);SESSIONS.put(id,session);
        if(cancel!=null)cancel.setOnCancelListener(session::cancel);
        session.begin(mask,reason,crypto,negativeText);
    }
    private static final class Session {
        final long id;final CancellationSignal signal;final Executor executor;final Callback callback;
        boolean done,nativeStarted;
        Session(long id,CancellationSignal signal,Executor executor,Callback callback){this.id=id;this.signal=signal;this.executor=executor;this.callback=callback;}
        synchronized void begin(int mask,String reason,Object crypto,String negativeText){
            if(done)return;
            if(signal!=null&&signal.isCanceled()){finish(false,0,5,"User verification was canceled");return;}
            if(!available()){finish(false,0,12,"Host user verification is not enabled in this runtime");return;}
            int capability=nativeCanAuthenticate(mask);
            if(capability!=0){finish(false,0,capability==11?11:capability==1?1:12,capability==-2?"The host cannot provide the requested Android authenticator strength or type":"The requested user verification method is unavailable");return;}
            nativeStarted=nativeBegin(id,mask,reason==null||reason.isBlank()?"Verify your identity":reason,crypto,negativeText);
            if(!nativeStarted)finish(false,0,2,"Could not create a user verification request bound to this operation");
        }
        synchronized void cancel(){
            if(done)return;
            if(nativeStarted)nativeCancel(id);
            finish(false,0,5,"User verification was canceled");
        }
        synchronized void completed(){
            if(done)return;
            int[] result=nativeTakeResult(id);
            if(result==null||result.length<2)return; // Java cannot manufacture a native result.
            int error=result[0],type=result[1];finish(error==0,type,error,message(error));
        }
        private void finish(boolean success,int type,int error,String message){
            if(done)return;done=true;SESSIONS.remove(id,this);
            // Do not clear a cancellation listener inside its own callback:
            // CancellationSignal waits for that callback and would deadlock.
            executor.execute(()->callback.completed(success,type,error,message));
        }
    }
    private static String message(int error){return switch(error){
        case 0->"User verification succeeded";case 3->"User verification timed out";case 5->"User verification was canceled";
        case 7->"Biometric verification is temporarily locked";case 10->"You canceled user verification";case 11->"The requested verification method is not set up";
        case 13->"Another verification method was chosen";case 14->"No device password is set";case 12->"The requested verification method is unavailable";
        default->"The system could not complete user verification";
    };}
    /** Native notifies only an ID. Status and authorization stay native-owned. */
    private static void onNativeCompleted(long id){Session session=SESSIONS.get(id);if(session!=null)session.completed();}
}
