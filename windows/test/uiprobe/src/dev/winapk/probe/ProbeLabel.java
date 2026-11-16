// A widget whose look lives in its default style (the theme attribute its
// constructor passes), as a design system's base text view does.
package dev.winapk.probe;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextView;

public class ProbeLabel extends TextView {
    public ProbeLabel(Context context, AttributeSet attrs) {
        super(context, attrs, R.attr.probeLabelStyle);
    }
}
