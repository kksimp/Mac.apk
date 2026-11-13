// screen_camera.h -- optional "screen + camera" picture for the built-in camera.
//
// WHAT IT IS FOR. Many calling apps (video-visit, family-communication and
// tutoring apps among them) have no screen sharing. With this switched on for
// such an app, the camera it opens sends the Mac's screen with the user's own
// camera image floating in a corner, so the other side can see the screen.
// It is opt-in per app and off by default; with it off, nothing here runs.
//
// It is NOT an Android device feature, and the app is not told anything: the
// app opened its camera and receives camera frames, of the size and format it
// configured. Only the pixels differ, the way they would if the user pointed
// the camera at the screen -- which is the honest description of what the
// switch does on the user's behalf.
//
// SWITCHES (environment, read once when the app starts; set them in the app's
// Environment overrides in Mac.apk):
//   MACAPK_SCREEN_CAMERA=1              makes the feature available (default:
//                                       absent, and nothing here runs)
//   MACAPK_SCREEN_CAMERA_START          off (default: the plain camera until the
//                                       user turns the screen on) | on
//   MACAPK_SCREEN_CAMERA_CORNER         top-left | top-right | bottom-left |
//                                       bottom-right (default)
//   MACAPK_SCREEN_CAMERA_SIZE           the camera overlay's height as a
//                                       percent of the frame height, 10-90
//                                       (default 30)
//   MACAPK_SCREEN_CAMERA_SHAPE          rounded (default) | circle | rect
//
// TURNING IT ON AND OFF while the app is open: with the feature available,
// the app's View menu has "Show Screen in Camera" (Shift-Command-S), a
// checkmark item that switches between the plain camera and the screen
// picture at once (cocoa_bridge.mm install_menu_bar). The screen is captured
// only while it is ticked and the camera is running.
//
// WHAT THE USER WILL NOTICE, and why it is right:
//   * Mirrored self-view. Calling apps usually mirror their own preview of a
//     front camera, so the user sees the overlay in the opposite corner and
//     the screen's text backwards. The frames the app SENDS are unmirrored
//     (WebRTC, for one, undoes the front camera's mirror), so the other side
//     sees the chosen corner and readable text.
//   * The app's own background replacement or blur applies to the whole
//     picture, screen included: the app processes the frames it receives, and
//     these are the composite. macOS's Video Effects (Control Center, while the
//     camera is on) act on the camera image BEFORE it is composited, so they
//     touch only the overlay.
//   * The app's own windows are left out of the screen picture: the call
//     window shows the camera preview, which would contain the picture, which
//     contains the preview again. Whatever is behind them shows instead.
//
// The screen is captured with ScreenCaptureKit, so macOS asks the user for
// Screen Recording permission the first time, attributed to the app. If it is
// refused, the plain camera image is sent and the log says why.
//
// Implemented in runtime/src/screen_camera.mm; used by camera_capture.mm. A C
// surface, like camera_capture.h, so plain C++ translation units can include it.
#pragma once

#include <CoreVideo/CoreVideo.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Whether MACAPK_SCREEN_CAMERA makes the feature available in this process. */
bool macapk_screencam_enabled(void);

/** Whether the screen is shown in the camera right now (the menu checkmark). */
bool macapk_screencam_showing(void);

/** Show or hide the screen from the next camera frame on; screen capture
 *  starts or stops to match. Any thread. */
void macapk_screencam_set_showing(bool showing);

/** A camera session that may composite has started (or stopped). The screen
 *  is captured only while at least one such session is running AND the
 *  screen is showing, so the macOS screen-recording indicator is shown only
 *  then. */
void macapk_screencam_session_started(void);
void macapk_screencam_session_stopped(void);

/** The composite for one camera frame: a new buffer (+1, caller releases)
 *  with the same size and pixel format as `camera`. NULL when the screen is
 *  not showing, or there is no screen image yet (or capture was refused):
 *  send the camera frame as is. */
CVPixelBufferRef macapk_screencam_compose(CVPixelBufferRef camera);

#ifdef __cplusplus
}
#endif
