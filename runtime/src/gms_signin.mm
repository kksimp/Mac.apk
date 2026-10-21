// gms_signin.mm -- the Google sign-in window (see macapk/gms_signin.h for the
// contract and the privacy posture).
//
// WHY THIS LOOKS LIKE A BROWSER AND NOT A FORM. Google does not offer a
// programmatic password API, and it should not: a third-party app collecting a
// Google password is exactly the phishing shape 2FA exists to defeat. The
// supported path for a device adding an account is to render Google's OWN page
// and take the token the completed flow deposits. So this file's whole job is:
// show that page, wait, read one cookie.
//
// ---- WHAT MAKES GOOGLE SERVE THE EMBEDDED FLOW ----------------------------
// accounts.google.com/EmbeddedSetup serves the "EmbeddedSetupAndroid" flow only
// to an Android WebView User-Agent with ` MinuteMaid` appended -- that suffix is
// the signal a device-setup WebView sends, and without it Google serves the
// ordinary desktop sign-in, which never produces an `oauth_token` cookie and
// leaves the user stuck on a page that looks like it worked.
// webview_bridge.mm's header records this already ("accounts.google.com, which
// serves the EmbeddedSetupAndroid flow to our Android UA"), proven on a
// hardened-runtime, ad-hoc-signed process. The UA below names Mac.apk in its
// Build token rather than impersonating a specific handset: it is a statement
// about the ENGINE ("an Android WebView"), which is true, and the device fields
// in it are our own.
//
// ---- HOW WE KNOW THE FLOW FINISHED ---------------------------------------
// Three independent triggers, because any one of them alone has a hole:
//   * every committed navigation (the flow is mostly form POSTs);
//   * the `#close` fragment Google appends at the end, which is a SAME-DOCUMENT
//     navigation -- it fires no didFinishNavigation:, only a `URL` KVO. That
//     exact gap is what broke Aurora's sign-in for a week (v1.0.1167), so this
//     file does not rely on navigation callbacks alone;
//   * a 1-second poll while the window is open, which catches anything the other
//     two miss and costs nothing.
// All three converge on the same check: does the cookie jar hold `oauth_token`?
#import <Cocoa/Cocoa.h>
#import <WebKit/WebKit.h>

#include "macapk/gms_signin.h"

#include <atomic>
#include <string>

namespace {

std::atomic<bool> g_active{false};

/// The Android WebView UA plus the MinuteMaid suffix. See the header comment:
/// without the suffix Google serves a flow that cannot complete here.
NSString* const kSignInUserAgent =
    @"Mozilla/5.0 (Linux; Android 10; Mac.apk Build/PR1; wv) "
    @"AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 "
    @"Chrome/120.0.0.0 Mobile Safari/537.36 MinuteMaid";

NSString* const kSignInURL = @"https://accounts.google.com/EmbeddedSetup";

/// A hard ceiling so a forgotten window cannot park a guest thread forever. Ten
/// minutes is long enough for a password plus a 2FA prompt on a phone, which is
/// the slowest realistic path.
const NSTimeInterval kSignInTimeout = 600.0;

}  // namespace

// ===========================================================================

@interface MacApkSignInController : NSObject <WKNavigationDelegate, NSWindowDelegate>
@property(nonatomic, strong) NSWindow* window;
@property(nonatomic, strong) WKWebView* webView;
@property(nonatomic, strong) NSTimer* poll;
@property(nonatomic, strong) NSTextField* status;
@property(nonatomic, copy)   NSString* token;
@property(nonatomic, assign) BOOL finished;
@end

@implementation MacApkSignInController

- (void)present {
    // A NON-PERSISTENT data store, deliberately. The Google session then lives
    // only as long as this window: it is not written to disk, it is not shared
    // with the guest app's WebView jar (webview_bridge.mm gives each APP its own
    // persistent store), and closing the window disposes of it. We want exactly
    // one value out of this flow and no residue.
    WKWebViewConfiguration* cfg = [WKWebViewConfiguration new];
    cfg.websiteDataStore = [WKWebsiteDataStore nonPersistentDataStore];

    NSRect frame = NSMakeRect(0, 0, 460, 720);   // portrait: the flow is phone-shaped
    self.webView = [[WKWebView alloc] initWithFrame:frame configuration:cfg];
    self.webView.customUserAgent = kSignInUserAgent;
    self.webView.navigationDelegate = self;
    self.webView.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;

    // The URL KVO is the ONLY signal a same-document `#close` produces. See the
    // header: relying on navigation callbacks alone is the bug that cost a week.
    [self.webView addObserver:self forKeyPath:@"URL" options:0 context:NULL];

    self.window = [[NSWindow alloc]
        initWithContentRect:frame
                  styleMask:(NSWindowStyleMaskTitled | NSWindowStyleMaskClosable |
                             NSWindowStyleMaskResizable)
                    backing:NSBackingStoreBuffered
                      defer:NO];
    self.window.title = @"Sign in to Google";
    self.window.delegate = self;
    self.window.releasedWhenClosed = NO;

    // A one-line reassurance under the title bar. It is not decoration: a window
    // asking for a Google password needs to say whose page it is, or the honest
    // thing and the phishing thing look identical to the user.
    NSView* content = [[NSView alloc] initWithFrame:frame];
    self.status = [NSTextField labelWithString:
        @"This is Google's own sign-in page. Mac.apk never sees your password."];
    self.status.font = [NSFont systemFontOfSize:11];
    self.status.textColor = [NSColor secondaryLabelColor];
    self.status.alignment = NSTextAlignmentCenter;
    self.status.frame = NSMakeRect(0, frame.size.height - 22, frame.size.width, 18);
    self.status.autoresizingMask = NSViewWidthSizable | NSViewMinYMargin;
    self.webView.frame = NSMakeRect(0, 0, frame.size.width, frame.size.height - 24);
    [content addSubview:self.webView];
    [content addSubview:self.status];
    self.window.contentView = content;

    [self.window center];
    // The runtime may be running as an accessory (no Dock icon) when this is a
    // bare `--gms signin-window`; a window the user must type into has to be able
    // to take focus, so become a regular app for the duration.
    [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
    [self.window makeKeyAndOrderFront:nil];
    [NSApp activateIgnoringOtherApps:YES];

    fprintf(stderr, "[gms-signin] window up, loading %s\n", kSignInURL.UTF8String);
    [self.webView loadRequest:[NSURLRequest requestWithURL:[NSURL URLWithString:kSignInURL]]];

    self.poll = [NSTimer scheduledTimerWithTimeInterval:1.0
                                                repeats:YES
                                                  block:^(NSTimer* t) { (void)t; [self checkCookies]; }];
}

// ---- the three triggers, all converging on checkCookies -------------------

- (void)webView:(WKWebView*)wv didFinishNavigation:(WKNavigation*)nav {
    (void)nav;
    // Scheme + host + path only: the query/fragment of the sign-in redirects
    // carry account identifiers and one-time tokens, and stderr ends up in the
    // user's log file.
    NSURL* u = wv.URL;
    NSString* shown = u ? [NSString stringWithFormat:@"%@://%@%@", u.scheme ?: @"",
                                                     u.host ?: @"", u.path ?: @""]
                        : @"(none)";
    fprintf(stderr, "[gms-signin] loaded %s\n", shown.UTF8String ?: "(none)");
    [self checkCookies];
}

- (void)webView:(WKWebView*)wv didFailNavigation:(WKNavigation*)nav withError:(NSError*)err {
    (void)wv; (void)nav;
    // A failed navigation is NOT necessarily the end: the flow redirects through
    // custom schemes that WebKit refuses, and the token may already be in the jar.
    fprintf(stderr, "[gms-signin] navigation failed: %s\n",
            err.localizedDescription.UTF8String ?: "(unknown)");
    [self checkCookies];
}

- (void)observeValueForKeyPath:(NSString*)path ofObject:(id)obj
                        change:(NSDictionary*)change context:(void*)ctx {
    (void)obj; (void)change; (void)ctx;
    if ([path isEqualToString:@"URL"]) [self checkCookies];
}

/// The whole detection mechanism: does the jar hold `oauth_token` yet?
- (void)checkCookies {
    if (self.finished) return;
    WKHTTPCookieStore* jar = self.webView.configuration.websiteDataStore.httpCookieStore;
    [jar getAllCookies:^(NSArray<NSHTTPCookie*>* cookies) {
        if (self.finished) return;
        for (NSHTTPCookie* c in cookies) {
            if ([c.name isEqualToString:@"oauth_token"] && c.value.length > 0) {
                self.token = c.value;
                [self finish];
                return;
            }
        }
    }];
}

- (void)finish {
    if (self.finished) return;
    self.finished = YES;
    [self.poll invalidate];
    self.poll = nil;
    @try { [self.webView removeObserver:self forKeyPath:@"URL"]; }
    @catch (NSException* e) { (void)e; }
    self.webView.navigationDelegate = nil;
    [self.window close];
}

/// The user closed the window. That is a cancel, and it must resolve the wait --
/// a caller parked on a semaphore that never signals is a hang, not a cancel.
- (void)windowWillClose:(NSNotification*)n {
    (void)n;
    if (!self.finished) {
        self.finished = YES;
        [self.poll invalidate];
        self.poll = nil;
        @try { [self.webView removeObserver:self forKeyPath:@"URL"]; }
        @catch (NSException* e) { (void)e; }
    }
}

@end

// ===========================================================================

bool MacApkGoogleSignInActive() { return g_active.load(); }

std::string MacApkPresentGoogleSignIn() {
    // One window at a time. A second licence check arriving while the user is
    // still typing must not stack a second window on top of the first.
    bool expected = false;
    if (!g_active.compare_exchange_strong(expected, true)) {
        fprintf(stderr, "[gms-signin] a sign-in window is already open\n");
        return std::string();
    }

    __block MacApkSignInController* controller = nil;
    dispatch_semaphore_t done = dispatch_semaphore_create(0);

    void (^run)(void) = ^{
        // In the `--gms signin-window` path nothing has touched AppKit yet, so
        // NSApp does not exist and creating a window would crash. In the in-app
        // path it already exists and this is a no-op returning the same
        // instance. Cheaper and less fragile than threading a "did the app boot"
        // flag through two entry points.
        [NSApplication sharedApplication];
        // ⚠ finishLaunching IS REQUIRED, and its absence fails SILENTLY.
        // MEASURED: without it, `--gms signin-window` blocked correctly inside
        // the flow and NO WINDOW EVER APPEARED -- the process was alive, the
        // controller existed, makeKeyAndOrderFront: had been called, and System
        // Events reported the process owning zero windows. NSApp does the work
        // that actually maps a window (activation, the window server connection,
        // the first UI update) inside finishLaunching, and an app that reaches
        // -run gets it for free. This path never calls -run, because it drives
        // the run loop by hand, so it has to do this itself. Idempotent: AppKit
        // ignores a second call, so the in-app path is unaffected.
        static dispatch_once_t once;
        dispatch_once(&once, ^{ [NSApp finishLaunching]; });
        controller = [MacApkSignInController new];
        [controller present];
        // Poll for completion on the main queue rather than signalling from
        // inside the controller: `finished` is set on three different paths
        // (token found, window closed, timeout) and one waiter is simpler to
        // reason about than three signal sites.
        __block NSTimer* watch = nil;
        __block NSTimeInterval waited = 0;
        watch = [NSTimer scheduledTimerWithTimeInterval:0.1 repeats:YES
                                                  block:^(NSTimer* t) {
            waited += 0.1;
            if (controller.finished) {
                [t invalidate];
                dispatch_semaphore_signal(done);
            } else if (waited >= kSignInTimeout) {
                fprintf(stderr, "[gms-signin] timed out after %.0f s\n", kSignInTimeout);
                [t invalidate];
                [controller finish];
                dispatch_semaphore_signal(done);
            }
        }];
        (void)watch;
    };

    if ([NSThread isMainThread]) {
        // Running inline on the main thread would deadlock against the wait
        // below, so pump the event loop by hand instead. This is the
        // `--gms signin-window` path, where nothing has called -[NSApp run].
        run();
        // ⚠ IT MUST BE AN APPKIT EVENT PUMP, NOT `[NSRunLoop runMode:]`.
        // MEASURED, and the failure is deeply misleading: with a plain
        // NSRunLoop the window appeared with its correct title, the page
        // NAVIGATED (didFinishNavigation fired for Google's real
        // EmbeddedSetupAndroid URL), and the content area stayed BLANK
        // forever. A run loop services timers and input sources, but AppKit
        // draws and delivers input from -[NSApplication sendEvent:]; with
        // nothing dequeuing NSEvents, nothing repaints and the user could not
        // have typed into the page even if it had. -[NSApp run] is the usual
        // answer and is unavailable here because it never returns, so this is
        // the same loop unrolled: dequeue, send, update.
        while (!controller.finished) {
            @autoreleasepool {
                NSEvent* e = [NSApp nextEventMatchingMask:NSEventMaskAny
                                                untilDate:[NSDate dateWithTimeIntervalSinceNow:0.05]
                                                   inMode:NSDefaultRunLoopMode
                                                  dequeue:YES];
                if (e) [NSApp sendEvent:e];
                [NSApp updateWindows];
            }
        }
    } else {
        dispatch_async(dispatch_get_main_queue(), run);
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
    }

    std::string token;
    if (controller.token.length > 0) token = controller.token.UTF8String;
    g_active.store(false);
    if (token.empty()) fprintf(stderr, "[gms-signin] no token (cancelled or failed)\n");
    return token;
}
