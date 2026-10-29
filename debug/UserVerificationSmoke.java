import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.CancellationSignal;
import com.macapk.loader.UserVerificationBridge;
import java.lang.reflect.*;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/** No authentication is started. Only read-only capabilities and explicitly
 * failing native replies are exercised. There is no test success/grant hook. */
public final class UserVerificationSmoke {
    private static native void fixtureFailure(long id,int error);
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        System.load(args[0]);boolean on="1".equals(System.getenv("MACAPK_USER_VERIFICATION_HOST"));
        check(UserVerificationBridge.available()==on,"opt-in gate");
        int credential=UserVerificationBridge.canAuthenticate(UserVerificationBridge.DEVICE_CREDENTIAL);
        int weak=UserVerificationBridge.canAuthenticate(UserVerificationBridge.WEAK);
        check(UserVerificationBridge.canAuthenticate(UserVerificationBridge.STRONG)==(on?-2:12),"host Touch ID cannot claim Android Class 3");
        check(UserVerificationBridge.canAuthenticate(0x123456)==(on?-2:12),"unknown authenticator flags rejected");
        check(UserVerificationBridge.lastAuthenticationTime(UserVerificationBridge.DEVICE_CREDENTIAL)==-1,"query does not authenticate");
        Object arbitrary=new Object();UserVerificationBridge.beginKeyOperation(arbitrary);
        check(!UserVerificationBridge.consumeAuthorization(arbitrary,true,0,UserVerificationBridge.DEVICE_CREDENTIAL),"arbitrary object has no per-operation grant");
        check(!UserVerificationBridge.consumeAuthorization(arbitrary,false,Integer.MAX_VALUE,UserVerificationBridge.DEVICE_CREDENTIAL),"arbitrary object has no window grant");
        AtomicInteger canceled=new AtomicInteger();CancellationSignal signal=new CancellationSignal();signal.cancel();
        UserVerificationBridge.authenticate(UserVerificationBridge.DEVICE_CREDENTIAL,"Fixture only",signal,Runnable::run,(success,type,error,message)->{check(!success&&type==0&&error==5,"pre-cancel stays canceled");canceled.incrementAndGet();});
        signal.cancel();check(canceled.get()==1,"cancellation callback once");
        AtomicInteger promptCanceled=new AtomicInteger();
        BiometricPrompt prompt=new BiometricPrompt.Builder(null).setTitle("Fixture only").setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK).setNegativeButton("取消",Runnable::run,(d,w)->{}).build();
        BiometricPrompt defaults=new BiometricPrompt.Builder(null).setTitle("Fixture only").setNegativeButton("取消",Runnable::run,(d,w)->{}).build();
        Method effective=BiometricPrompt.class.getDeclaredMethod("effectiveAuthenticators",BiometricPrompt.CryptoObject.class);effective.setAccessible(true);
        check((Integer)effective.invoke(defaults,new Object[]{null})==UserVerificationBridge.WEAK,"non-crypto default is WEAK");
        BiometricPrompt.CryptoObject crypto=new BiometricPrompt.CryptoObject(java.security.Signature.getInstance("SHA256withRSA"));
        check((Integer)effective.invoke(defaults,crypto)==UserVerificationBridge.STRONG,"crypto default is STRONG");
        BiometricPrompt explicit=new BiometricPrompt.Builder(null).setTitle("Fixture only").setDeviceCredentialAllowed(true).setAllowedAuthenticators(UserVerificationBridge.WEAK).setNegativeButton("取消",Runnable::run,(d,w)->{}).build();
        check((Integer)effective.invoke(explicit,new Object[]{null})==UserVerificationBridge.WEAK,"explicit authenticator flags override deprecated toggle");
        boolean weakCryptoRejected=false;
        try{explicit.authenticate(crypto,new CancellationSignal(),Runnable::run,new BiometricPrompt.AuthenticationCallback(){});}catch(IllegalArgumentException expected){weakCryptoRejected=true;}
        check(weakCryptoRejected,"explicit weak crypto rejected before starting auth");
        CancellationSignal pc=new CancellationSignal();pc.cancel();
        prompt.authenticate(pc,Runnable::run,new BiometricPrompt.AuthenticationCallback(){
            @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r){throw new AssertionError("canceled prompt succeeded");}
            @Override public void onAuthenticationError(int code,CharSequence message){check(code==5,"framework prompt forwards cancel");promptCanceled.incrementAndGet();}
        });check(promptCanceled.get()==1,"framework prompt canceled once");
        Method notify=UserVerificationBridge.class.getDeclaredMethod("onNativeCompleted",long.class);notify.setAccessible(true);notify.invoke(null,9999999L);
        check(UserVerificationBridge.lastAuthenticationTime(UserVerificationBridge.DEVICE_CREDENTIAL)==-1,"forged Java callback cannot authenticate");
        if(on){
            for(int error:new int[]{3,12}){
                long id=700000+error;AtomicInteger calls=new AtomicInteger();
                Class<?> session=Class.forName("com.macapk.loader.UserVerificationBridge$Session");
                Constructor<?> constructor=session.getDeclaredConstructor(long.class,CancellationSignal.class,Executor.class,UserVerificationBridge.Callback.class);constructor.setAccessible(true);
                Object pending=constructor.newInstance(id,null,(Executor)Runnable::run,(UserVerificationBridge.Callback)(success,type,code,message)->{check(!success&&type==0&&code==error,"native failure remains a failure");calls.incrementAndGet();});
                Field sessions=UserVerificationBridge.class.getDeclaredField("SESSIONS");sessions.setAccessible(true);
                @SuppressWarnings("unchecked") Map<Long,Object> map=(Map<Long,Object>)sessions.get(null);map.put(id,pending);
                fixtureFailure(id,error);notify.invoke(null,id);check(calls.get()==1,"native failure/timeout completed once");
                check(!map.containsKey(id),"terminal session removed");
            }
        }
        check(UserVerificationBridge.lastAuthenticationTime(UserVerificationBridge.DEVICE_CREDENTIAL)==-1,"failure/cancel/timeout do not issue authentication");
        System.out.println("user-verification: PASS (gate="+on+", credentialCapability="+credential+", weakCapability="+weak+", strong unsupported, failure/cancel/timeout exactly once, no forged grants; no prompt)");
    }
}
