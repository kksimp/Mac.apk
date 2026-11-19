import android.app.Activity;
import android.app.ActivityManager;
import java.util.List;

/** ActivityManager must describe ActivityHost's real task, not a fixed empty one. */
public class ActivityManagerTasksTest {
    public static final class RootActivity extends Activity {}
    public static final class ChildActivity extends Activity {}

    private static void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }

    public static void main(String[] args) {
        ActivityManager manager = new ActivityManager();
        check(manager.getAppTasks().isEmpty(), "an empty process invented an app task");

        RootActivity root = new RootActivity();
        ChildActivity child = new ChildActivity();
        com.macapk.loader.ActivityHost.push(root);
        com.macapk.loader.ActivityHost.push(child);

        List<ActivityManager.AppTask> handles = manager.getAppTasks();
        check(handles.size() == 1, "single-task host did not expose one AppTask");
        ActivityManager.RecentTaskInfo info = handles.get(0).getTaskInfo();
        check(info.taskId == 1 && info.persistentId == 1, "task identity is not stable");
        check(info.baseActivity.getClassName().equals(RootActivity.class.getName()),
              "baseActivity is not the bottom Activity");
        check(info.topActivity.getClassName().equals(ChildActivity.class.getName()),
              "topActivity is not the current Activity");
        check(info.numActivities == 2 && info.isRunning,
              "task count/run state does not describe the live stack");

        info.baseActivity = null;
        check(handles.get(0).getTaskInfo().baseActivity != null,
              "caller mutation escaped the task-info snapshot");
        System.out.println("PASS: AppTask exposes coherent base/top/count/run-state snapshots");
    }
}
