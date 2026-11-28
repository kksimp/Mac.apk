package deoptprobe;

/** A class with no subclasses while {@link Sub} is not loaded: what the compiled {@code rec} relies on. */
public class Leaf {
    public int plus(int x) {
        return x + 1;
    }
}
