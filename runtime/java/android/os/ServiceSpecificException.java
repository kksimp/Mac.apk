// android.os.ServiceSpecificException -- AOSP frameworks/base
// core/java/android/os/ServiceSpecificException.java: a service-defined error
// code that crosses a Binder as Parcel EX_SERVICE_SPECIFIC (code -8) followed
// by errorCode.
package android.os;

public class ServiceSpecificException extends RuntimeException {
    public final int errorCode;

    public ServiceSpecificException(int errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ServiceSpecificException(int errorCode) {
        this.errorCode = errorCode;
    }

    @Override
    public String toString() {
        return super.toString() + " (code " + errorCode + ")";
    }
}
