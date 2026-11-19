public final class JniSignalRecoveryCallbacks {
    static final class Cell { int value = 7; }
    public static int nullAccess(Cell cell) { return cell.value; }
    public static int checkNulls() {
        Cell value = new Cell();
        int sum = 0;
        for (int i = 0; i < 1_000_000; i++) sum += nullAccess(value);
        if (sum != 7_000_000) throw new AssertionError("Valid Java reads changed");
        int caught = 0;
        for (int i = 0; i < 128; i++) {
            try { nullAccess(null); }
            catch (NullPointerException expected) { caught++; }
        }
        return caught;
    }
}
