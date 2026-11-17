// com.google.android.gms.maps.internal.CreatorImpl -- Google Play services'
// side of the Maps SDK, for apps whose Maps client library asks for it.
//
// On a device the client library (inside the app, usually renamed by R8) does
// not draw the map: it loads this class from Play services' own code
// (createPackageContext("com.google.android.gms", INCLUDE_CODE)), takes it for
// the ICreator binder, and asks it for the map view, the camera-update factory
// and the bitmap-descriptor factory. Win.apk has no second package's code in
// an app's process (createPackageContext is the app's own context), so the
// class was not found and MapsInitializer.initialize threw "Unable to find
// dynamic class com.google.android.gms.maps.internal.CreatorImpl". Apps call
// that from Application.onCreate: Moovit's died there, its activity then found
// no data manager, and the window stayed empty. (An app whose client library
// kept its names gets Win.apk's own com.google.android.gms.maps classes and
// never comes here.)
//
// This is the binder the client library expects, answering as a device whose
// map never finishes loading: initialization succeeds, the two factories hand
// out opaque handles, a map view or fragment gets a plain view to lay out, and
// getMapAsync does not call back. There is no map in it (docs/APPS.md: the
// Maps SDK lays out a blank map); what it gives is the rest of the app.
//
// The protocol is AIDL's: each call is transact(code, data, reply), data
// starting with the interface name, reply with "no exception" then the result.
// The codes are the method order of Google's ICreator, IMapViewDelegate and
// IMapFragmentDelegate, read off client libraries of several years (the
// StreetView ones have the same shape). A call that is not named here returns
// nothing, which the caller reads as void, 0, false or null.
package com.google.android.gms.maps.internal;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.View;
import android.widget.FrameLayout;

import java.lang.reflect.Field;

public final class CreatorImpl extends Binder {
    private static final String TAG = "Win.apk.Maps";

    // ICreator
    private static final int NEW_MAP_FRAGMENT_DELEGATE = 2;
    private static final int NEW_MAP_VIEW_DELEGATE = 3;
    private static final int NEW_CAMERA_UPDATE_FACTORY_DELEGATE = 4;
    private static final int NEW_BITMAP_DESCRIPTOR_FACTORY_DELEGATE = 5;
    private static final int NEW_STREET_VIEW_PANORAMA_VIEW_DELEGATE = 7;
    private static final int NEW_STREET_VIEW_PANORAMA_FRAGMENT_DELEGATE = 8;
    private static final int GET_RENDERER_TYPE = 9;
    // IMapViewDelegate.getView, IMapFragmentDelegate.onCreateView
    private static final int VIEW_GET_VIEW = 8;
    private static final int FRAGMENT_ON_CREATE_VIEW = 4;
    // MapsInitializer.Renderer.LATEST, what a current device answers.
    private static final int RENDERER_LATEST = 2;
    // The land colour of Google's map, so the place of the map reads as one.
    private static final int MAP_BACKGROUND = 0xFFE5E3DF;

    private static boolean sSaid;

    public CreatorImpl() {}

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code < FIRST_CALL_TRANSACTION || code > LAST_CALL_TRANSACTION) {
            return super.onTransact(code, data, reply, flags);
        }
        IBinder result = null;
        switch (code) {
            case NEW_MAP_VIEW_DELEGATE:
            case NEW_STREET_VIEW_PANORAMA_VIEW_DELEGATE:
                result = new ViewDelegate(contextArgument(data), VIEW_GET_VIEW);
                break;
            case NEW_MAP_FRAGMENT_DELEGATE:
            case NEW_STREET_VIEW_PANORAMA_FRAGMENT_DELEGATE:
                result = new ViewDelegate(contextArgument(data), FRAGMENT_ON_CREATE_VIEW);
                break;
            case NEW_CAMERA_UPDATE_FACTORY_DELEGATE:
            case NEW_BITMAP_DESCRIPTOR_FACTORY_DELEGATE:
                result = new Factory();
                break;
            default:
                break;
        }
        if (reply == null) return true;
        reply.writeNoException();
        if (result != null) {
            reply.writeStrongBinder(result);
        } else if (code == GET_RENDERER_TYPE) {
            reply.writeInt(RENDERER_LATEST);
        }
        return true;
    }

    /** The first argument of the calls that make a view: an IObjectWrapper around the
     *  activity or context. The application stands in when it cannot be read. */
    private static Context contextArgument(Parcel data) {
        Object o = null;
        try {
            data.readString();  // the interface name (Parcel.writeInterfaceToken)
            o = unwrap(data.readStrongBinder());
        } catch (RuntimeException e) {
            // an argument list this does not know: the application below
        }
        return o instanceof Context ? (Context) o : android.app.Application.getInstance();
    }

    /** What an IObjectWrapper holds: the one field its binder declares, which is how the
     *  client library itself reads a wrapper that is not its own class. */
    private static Object unwrap(IBinder wrapper) {
        if (wrapper == null) return null;
        Field only = null;
        for (Field f : wrapper.getClass().getDeclaredFields()) {
            if (f.isSynthetic()) continue;
            if (only != null) return null;
            only = f;
        }
        if (only == null) return null;
        try {
            only.setAccessible(true);
            return only.get(wrapper);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** An IObjectWrapper: a binder with exactly one declared field, private, holding the object. */
    private static final class Wrapper extends Binder {
        private final Object object;

        Wrapper(Object object) {
            this.object = object;
        }
    }

    /** ICameraUpdateFactoryDelegate and IBitmapDescriptorFactoryDelegate: every method returns a
     *  wrapped object the app only hands back to the map. */
    private static final class Factory extends Binder {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code < FIRST_CALL_TRANSACTION || code > LAST_CALL_TRANSACTION) {
                return super.onTransact(code, data, reply, flags);
            }
            if (reply != null) {
                reply.writeNoException();
                reply.writeStrongBinder(new Wrapper(new Object()));
            }
            return true;
        }
    }

    /** IMapViewDelegate or IMapFragmentDelegate (and StreetView's two): the life-cycle calls do
     *  nothing, the one that asks for the view gets a plain one, and the map is never ready. */
    private static final class ViewDelegate extends Binder {
        private final Context context;
        private final int viewCode;
        private View view;

        ViewDelegate(Context context, int viewCode) {
            this.context = context;
            this.viewCode = viewCode;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code < FIRST_CALL_TRANSACTION || code > LAST_CALL_TRANSACTION) {
                return super.onTransact(code, data, reply, flags);
            }
            if (reply == null) return true;
            reply.writeNoException();
            if (code == viewCode) {
                if (view == null) {
                    view = new FrameLayout(context);
                    view.setBackgroundColor(MAP_BACKGROUND);
                    synchronized (CreatorImpl.class) {
                        if (!sSaid) {
                            sSaid = true;
                            android.util.Log.w(TAG, "the Maps SDK's map is Play services' own code, which does not run "
                                + "in an app's process here: the map's place stays blank and never reports ready");
                        }
                    }
                }
                reply.writeStrongBinder(new Wrapper(view));
            }
            return true;
        }
    }
}
