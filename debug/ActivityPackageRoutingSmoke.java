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
        String resolvedPackage;
        Wrapper(String value) { reportedPackage = value; }
        @Override public String getPackageName() { return reportedPackage; }
        @Override public PackageManager getPackageManager() {
            if (resolvedPackage == null) return null;
            return new PackageManager() {
                @Override public android.content.pm.PackageInfo getPackageInfo(String name, int flags) throws NameNotFoundException { throw new NameNotFoundException(name); }
                @Override public android.content.pm.ApplicationInfo getApplicationInfo(String name, int flags) throws NameNotFoundException { throw new NameNotFoundException(name); }
                @Override public int checkPermission(String permission, String name) { return -1; }
                @Override public boolean hasSystemFeature(String name) { return false; }
                @Override public String getInstallerPackageName(String name) { return null; }
                @Override public android.content.pm.ServiceInfo getServiceInfo(ComponentName name, int flags) throws NameNotFoundException { throw new NameNotFoundException(name.toString()); }
                @Override public android.content.pm.ProviderInfo getProviderInfo(ComponentName name, int flags) throws NameNotFoundException { throw new NameNotFoundException(name.toString()); }
                @Override public java.util.List<android.content.pm.ResolveInfo> queryIntentActivities(Intent intent, int flags) {
                    var row = new android.content.pm.ResolveInfo();
                    row.activityInfo = new android.content.pm.ActivityInfo();
                    row.activityInfo.packageName = resolvedPackage;
                    row.activityInfo.name = resolvedPackage + ".Screen";
                    row.activityInfo.exported = true;
                    return java.util.List.of(row);
                }
            };
        }
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
        for (String reported : new String[] {"", "fixture.plugin"}) {
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
            String expectedPackage = reported.isEmpty() ? OWN : reported;
            if (!expectedPackage.equals(activity.getPackageName()) || !expectedPackage.equals(next.getComponent().getPackageName()))
                throw new AssertionError("Activity's bound package was replaced by wrapper getter");
            if (route(router, next) != next) throw new AssertionError("Activity-created same-package Intent rerouted");
            for (String target : new String[] {OWN, expectedPackage}) {
                wrapper.resolvedPackage = target;
                Intent implicit = route(router, new Intent("fixture.LOCAL"));
                if (implicit == null || !target.equals(implicit.getComponent().getPackageName()))
                    throw new AssertionError("implicit local identity lost: " + target);
            }
            wrapper.resolvedPackage = null;
        }
        Method allowed = ActivityHost.class.getDeclaredMethod("macapkLaunchAllowed", String.class);
        allowed.setAccessible(true);
        if ((Boolean) allowed.invoke(null, "fixture external start"))
            throw new AssertionError("external launch policy was bypassed");
        identity.set(null, null);
        ActivityHost.init(new Wrapper(OWN), ActivityPackageRoutingSmoke.class.getClassLoader());
        Intent fallback = new Intent().setComponent(new ComponentName(OWN, OWN + ".Splash"));
        if (route(router, fallback) != fallback) throw new AssertionError("pre-Boot fallback changed");
        System.out.println("activity-package-routing: PASS (activity/application identity, empty wrapper, foreign resolution, host policy, pre-Boot fallback)");
    }
}
