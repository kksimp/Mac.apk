// updates.h -- where the update check and the installer's download stand.
//
// Everything here happens on the library's thread: the check and the download run on threads of their
// own (update_check_thread, update_download_thread) that only post their results (kUpdateChecked,
// kUpdateProgress, kUpdateDownloaded). So there is no lock; UpdateTracker makes no Windows call either,
// so that test/launcher_state_test.cpp can drive it.
//
// Three things move on their own, and each has its own states:
//
//   the check     kIdle -> kReadingKept | kAsking     begin_check(): at start, daily, "Check"
//                 kReadingKept, kAsking -> kIdle      checked(): kUpdateChecked; abort_check(): no thread
//                 One check at a time: begin_check() refuses while one runs.
//
//   what is known kNothing -> kNoRelease | kRelease   checked(), when the list of releases was read
//                 kNoRelease <-> kRelease             the same, at a later check
//                 A check that read nothing leaves what was known as it was. Beside it: whether the
//                 last check that asked GitHub failed, and whether the InfoBar has told of this release.
//
//   the download  not downloading -> 0..99 %          begin_download(): "Update"
//                 n % -> m %                          progress(): kUpdateProgress
//                 n % -> not downloading              downloaded(): kUpdateDownloaded
//
// checked() also answers what the InfoBar says about the check, so that the rule is in one place.

#pragma once

#include <optional>
#include <string>
#include <utility>

namespace winapk::launcher {

// One check's result, as its thread reports it.
struct CheckResult {
    bool fetch = false;    // GitHub was asked; else only the answer kept from the last check was read
    bool manual = false;   // "Check": the result is said in the InfoBar whatever it is
    bool reached = true;   // GitHub answered (a fetch)
    bool ok = false;       // the list of releases was read, GitHub's answer or the one kept
    bool found = false;    // and it has a release
};

// `Release` is the launcher's: all that is asked of it here is `.version`.
template <class Release>
class UpdateTracker {
public:
    enum class Check { kIdle, kReadingKept, kAsking };
    enum class Known { kNothing, kNoRelease, kRelease };
    enum class Say { kNothing, kCouldNotCheck, kUpToDate, kNewVersion };

    // A check starts. False, changing nothing, while one runs.
    bool begin_check(bool fetch) {
        if (check_ != Check::kIdle) return false;
        check_ = fetch ? Check::kAsking : Check::kReadingKept;
        return true;
    }
    // Its thread could not be started.
    void abort_check() { check_ = Check::kIdle; }

    // The check is back. `latest` is the newest release when r.found, and `newer` whether it is newer
    // than this build; `auto_on`: the daily check is on. Returns what to say in the InfoBar.
    Say checked(const CheckResult& r, Release latest, bool newer, bool auto_on) {
        check_ = Check::kIdle;
        if (r.ok) {
            // Another release than the one told of: it is news again.
            if (r.found && (known_ != Known::kRelease || latest_.version != latest.version)) told_ = false;
            if (r.found) {
                latest_ = std::move(latest);
                newer_ = newer;
            }
            known_ = r.found ? Known::kRelease : Known::kNoRelease;
        }
        if (r.fetch) fetch_failed_ = !r.reached || !r.ok;
        if (r.manual && fetch_failed_) return Say::kCouldNotCheck;
        if (r.manual && !available()) return Say::kUpToDate;
        if (available() && !told_ && (r.manual || auto_on)) {
            told_ = true;
            return Say::kNewVersion;
        }
        return Say::kNothing;
    }

    // The installer's download starts. False when one runs, or there is nothing newer to download.
    bool begin_download() {
        if (download_ || !available()) return false;
        download_ = 0;
        return true;
    }
    void progress(int percent) {
        if (download_) download_ = percent;
    }
    void downloaded() { download_.reset(); }

    Check check() const { return check_; }
    bool asking() const { return check_ == Check::kAsking; }  // GitHub is being asked
    Known known() const { return known_; }
    bool fetch_failed() const { return fetch_failed_; }  // the last check that asked GitHub got no list
    // A release newer than this build is known.
    bool available() const { return known_ == Known::kRelease && newer_; }
    const Release& latest() const { return latest_; }  // the newest release last found; empty before any
    bool downloading() const { return download_.has_value(); }
    int percent() const { return download_.value_or(-1); }

private:
    Check check_ = Check::kIdle;
    Known known_ = Known::kNothing;
    bool newer_ = false;         // latest_ is newer than this build
    bool fetch_failed_ = false;
    bool told_ = false;          // the InfoBar has said this session that latest_ is out
    std::optional<int> download_;  // Win.apk-Setup.exe is downloading: how far
    Release latest_;
};

}  // namespace winapk::launcher
