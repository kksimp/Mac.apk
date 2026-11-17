// Runs the probes inside Win.apk and prints them: "PROBE <line>" on stdout,
// which the runtime's log holds, then "PROBE done".
package dev.winapk.probe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class ProbeActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView label = new TextView(this);
        label.setText("UI probe");
        setContentView(label);
        for (String line : Probes.runAll(getApplicationContext(), new java.io.File(getFilesDir(), "probe"))) System.out.println("PROBE " + line);
        System.out.println("PROBE done");
        System.out.flush();
    }
}
