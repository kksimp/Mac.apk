// screen_camera.h -- optional "screen + camera" picture for the built-in camera.
//
// WHAT IT IS FOR. Many calling apps (video-visit, family-communication and
// tutoring apps among them) have no screen sharing. With this switched on in
// such an app, the camera it opens sends the Mac's screen with the user's own
// camera image floating in a corner, so the other side can see the screen.
//
// IT IS THE USER'S CHOICE, EVERY TIME. Nothing is captured and no Screen
// Recording permission is asked for until the user ticks "Share Screen in
// Camera" in the Camera menu. That menu is not in the menu bar at launch: it
// appears once the app first opens a camera, and stays for the rest of the run.
// The choice is never saved: every launch starts with it off. Unticking stops
// the capture at once. If macOS refuses the permission (or ends the capture),
// the item unticks itself and the plain camera carries on.
//
// It is NOT an Android device feature, and the app is not told anything: the
// app opened its camera and receives camera frames, of the size and format it
// configured. Only the pixels differ, the way they would if the user pointed
// the camera at the screen -- which is the honest description of what the
// switch does on the user's behalf.
//
// SWITCHES (environment, read once when first needed; set them in the app's
// Environment overrides in Mac.apk). None is needed to use the feature:
//   MACAPK_SCREEN_CAMERA=0              kill switch: the feature and its menu
//                                       item are not offered at all
//   MACAPK_SCREEN_CAMERA_CORNER         top-left | top-right | bottom-left |
//                                       bottom-right (default)
//   MACAPK_SCREEN_CAMERA_SIZE           the camera overlay's height as a
//                                       percent of the frame height, 10-90
//                                       (default 30)
//   MACAPK_SCREEN_CAMERA_SHAPE          rounded (default) | circle | rect
//
// THE CAMERA MENU (cocoa_bridge.mm install_camera_menu) is built from this
// file's state: camera_capture.mm reports each camera the app opens
// (macapk_screencam_camera_opened), and the menu shows the camera in use plus
// the "Share Screen in Camera" checkmark item (Shift-Command-S). The screen is
// captured only while it is ticked and the camera is running.
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
// Screen Recording permission the first time the user ticks the item,
// attributed to the app. If it is refused, the item unticks itself, the plain
// camera image is sent and the log says why.
//
// Implemented in runtime/src/screen_camera.mm; used by camera_capture.mm and cocoa_bridge.mm. A C
// surface, like camera_capture.h, so plain C++ translation units can include it.
#pragma once

#include <CoreVideo/CoreVideo.h>
#include <stdbool.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Whether the feature is offered in this process: true unless
 *  MACAPK_SCREEN_CAMERA=0. Offered is not on: see macapk_screencam_showing. */
bool macapk_screencam_enabled(void);

/** Whether the screen is shown in the camera right now (the menu checkmark).
 *  False at every launch; only the user's tick turns it on. Any thread. */
bool macapk_screencam_showing(void);

/** Show or hide the screen from the next camera frame on; screen capture
 *  starts or stops to match. Called for the user's tick of the menu item, never
 *  on its own. Any thread. */
void macapk_screencam_set_showing(bool showing);

/** A camera session that may composite has started (or stopped). The screen
 *  is captured only while at least one such session is running AND the
 *  screen is showing, so the macOS screen-recording indicator is shown only
 *  then. */
void macapk_screencam_session_started(void);
void macapk_screencam_session_stopped(void);

/** The app opened a camera. `deviceName` is its display name; `canShowScreen`
 *  is whether this camera's frames can carry the screen picture. The first call
 *  makes the Camera menu appear (see macapk_screencam_set_menu_handler). Any
 *  thread. Does nothing when the feature is not offered. */
void macapk_screencam_camera_opened(const char* deviceName, bool canShowScreen);

/** What the Camera menu shows: false until a camera has been opened. `name`
 *  receives the most recently opened camera's name (NUL terminated, truncated
 *  to `nameSize`); `canShowScreen` is true if any camera the app opened can
 *  carry the screen picture. Any thread. */
bool macapk_screencam_camera_info(char* name, size_t nameSize, bool* canShowScreen);

/** Registers the function that adds the Camera menu. It is called once, on the
 *  main thread, after the app first opens a camera; registering after that
 *  first open calls it right away. There is one handler; a later call replaces
 *  it. Any thread. */
void macapk_screencam_set_menu_handler(void (*handler)(void));

/** The composite for one camera frame: a new buffer (+1, caller releases)
 *  with the same size and pixel format as `camera`. NULL when the screen is
 *  not showing, or there is no screen image yet (or capture was refused):
 *  send the camera frame as is. */
CVPixelBufferRef macapk_screencam_compose(CVPixelBufferRef camera);

#ifdef __cplusplus
}
#endif
