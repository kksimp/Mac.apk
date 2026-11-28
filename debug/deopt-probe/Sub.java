package deoptprobe;

/** Loaded on purpose once {@code rec} is compiled: that deoptimizes it. */
public class Sub extends Leaf {
    @Override
    public int plus(int x) {
        return x + 2;
    }
}
