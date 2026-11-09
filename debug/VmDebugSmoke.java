import dalvik.system.VMDebug;
public final class VmDebugSmoke {
    public static void main(String[] args) {
        boolean expected=Boolean.parseBoolean(args[0]);
        if(VMDebug.isDebuggingEnabled()!=expected || VMDebug.isDebuggerConnected()!=expected
                || VMDebug.lastDebuggerActivity()!=(expected?0:-1))throw new AssertionError("debugger state");
        System.out.println("vm-debug: PASS (enabled="+expected+")");
    }
}
