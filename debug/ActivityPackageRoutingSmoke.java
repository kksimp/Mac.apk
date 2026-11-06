import android.app.Application;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import com.macapk.loader.ActivityHost;
import com.macapk.loader.Boot;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Exercise the real start router with an Application that overrides its package identity. */
public final class ActivityPackageRoutingSmoke {
    static final String OWN = "fixture.installed";
    static final class Wrapper extends Application {
        private final String reportedPackage;
        Wrapper(String value) { reportedPackage = value; }
        @Override public String getPackageName() { return reportedPackage; }
        @Override public PackageManager getPackageManager() { return null; }
    }
    static Intent route(Method method, Intent intent) throws Exception {
        try { return (Intent) method.invoke(null, intent); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            throw e;
        }
    }
    public static void main(String[] args) throws Exception {
        Field identity = Boot.class.getDeclaredField("sPackageName");
        identity.setAccessible(true);
        identity.set(null, OWN); // Boot's parsed-manifest process record.
        Method router = ActivityHost.class.getDeclaredMethod("macapkRouteStart", Intent.class);
        router.setAccessible(true);
        for (String reported : new String[] {"", "fixture.foreign"}) {
            Wrapper wrapper = new Wrapper(reported);
            ActivityHost.init(wrapper, ActivityPackageRoutingSmoke.class.getClassLoader());
            Intent own = new Intent().setComponent(new ComponentName(OWN, OWN + ".Splash"));
            if (route(router, own) != own) throw new AssertionError("same-package start was rerouted");
            Intent foreign = new Intent().setComponent(new ComponentName("fixture.foreign", "fixture.foreign.Screen"));
            try {
                route(router, foreign);
                throw new AssertionError("wrapper getter authorized a foreign component");
            } catch (ActivityNotFoundException expected) { /* real resolution still applies */ }
            Activity activity = new Activity();
            activity.macapkAttach(wrapper, own);
            Intent next = new Intent(activity, Activity.class);
            if (!OWN.equals(activity.getPackageName()) || !OWN.equals(next.getComponent().getPackageName()))
                throw new AssertionError("Activity's bound package was replaced by wrapper getter");
            if (route(router, next) != next) throw new AssertionError("Activity-created same-package Intent rerouted");
        }
        Method allowed = ActivityHost.class.getDeclaredMethod("macapkLaunchAllowed", String.class);
        allowed.setAccessible(true);
        if ((Boolean) allowed.invoke(null, "fixture external start"))
            throw new AssertionError("external launch policy was bypassed");
        identity.set(null, null);
        ActivityHost.init(new Wrapper(OWN), ActivityPackageRoutingSmoke.class.getClassLoader());
        Intent fallback = new Intent().setComponent(new ComponentName(OWN, OWN + ".Splash"));
        if (route(router, fallback) != fallback) throw new AssertionError("pre-Boot fallback changed");
        System.out.println("activity-package-routing: PASS (manifest identity, empty/spoofed wrapper, foreign resolution, host policy, pre-Boot fallback)");
    }
}
