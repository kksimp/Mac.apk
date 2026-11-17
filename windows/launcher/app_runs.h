// app_runs.h -- the runs of the apps this launcher started, one state machine per app.
//
// A run is touched by two threads: the library's (launch, Stop, the splash's X, the messages a run
// posts) and the run's own (run_thread: it starts the runtime, waits for its window and for its end).
// RunTable is the one record both go by. It holds no lock of its own and makes no Windows call, so
// that test/launcher_state_test.cpp can drive it; launcher_main.cpp calls it only with g_proc_lock
// held (shared for the const members, exclusive for the rest).
//
//   state      what it means                                         the process
//   (none)     not started from here, or over                        -
//   kStarting  the run's thread is starting the runtime              none yet
//   kRunning   the runtime is up, its window is not                  held
//   kReady     the app's window has painted (WINAPK_READY_EVENT)     held
//   kEnded     the runtime is gone; the library has yet to hear      none (closed by the run's thread)
//
//   from                  to          by               when
//   (none)                kStarting   library          begin(): launch(), start_play()
//   kStarting             kRunning    run's thread     attach(): the runtime started
//   kStarting             kEnded      run's thread     ended(): it did not start, or was stopped before it did
//   kRunning              kReady      run's thread     ready(): the ready event
//   kRunning, kReady      kEnded      run's thread     ended(): the process exited
//   kEnded                (none)      library          finish(): kRunExited
//
// Beside the state, `stopped`: the user ended the run, so however it ends is no failure. stop() sets it
// (and ends the process when there is one; a run still starting is ended by attach()'s caller, or never
// started), expect_end() sets it for a run that something else is ending. Nothing clears it.
//
// What the library says once a run is over is RunEnd::kind(), from `stopped`, whether the window ever
// came up, and the exit code.

#pragma once

#include <map>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace winapk::launcher {

enum class RunState { kStarting, kRunning, kReady, kEnded };

// How a run ended, for the library to tell the user.
struct RunEnd {
    bool stopped = false;    // the user ended it
    bool was_ready = false;  // its window had come up
    bool clean = false;      // exit code 0
    enum Kind {
        kQuiet,        // closed by the app or its user: nothing to say
        kStopped,      // stopped from the launcher: nothing to say, and a splash still up goes
        kDidNotStart,  // over before its window came up
        kFailed,       // its window was up, and it exited with an error
    };
    Kind kind() const {
        if (stopped) return kStopped;
        if (!was_ready) return kDidNotStart;
        return clean ? kQuiet : kFailed;
    }
};

class RunTable {
public:
    using Process = void*;  // a HANDLE; the table never looks inside

    // The library starts a run. False, changing nothing, when the app has one (even one that ended and
    // the library has not heard of yet: its kRunExited is on the way).
    bool begin(const std::wstring& name) { return runs_.emplace(name, Run{}).second; }

    // The run's thread, before it starts the runtime: stopped already, so there is nothing to start.
    bool stop_requested(const std::wstring& name) const {
        const Run* r = find(name);
        return r && r->stopped;
    }

    // The run's thread: the runtime started. True when the run was stopped meanwhile: the caller ends
    // the process at once (it is recorded all the same: its exit is waited for as any other's).
    bool attach(const std::wstring& name, Process process) {
        Run* r = find(name);
        if (!r || r->state != RunState::kStarting || !process) return false;
        r->state = RunState::kRunning;
        r->process = process;
        return r->stopped;
    }

    // The run's thread: the app's window is up. False when the run is not one waiting for its window.
    bool ready(const std::wstring& name) {
        Run* r = find(name);
        if (!r || r->state != RunState::kRunning) return false;
        r->state = RunState::kReady;
        return true;
    }

    // The run's thread: the runtime is gone (`clean`: exit code 0), or never started. The process is
    // forgotten here, under the caller's lock, so that stop() cannot end a handle about to be closed.
    bool ended(const std::wstring& name, bool clean) {
        Run* r = find(name);
        if (!r || r->state == RunState::kEnded) return false;
        r->was_ready = r->state == RunState::kReady;
        r->clean = clean;
        r->state = RunState::kEnded;
        r->process = nullptr;
        return true;
    }

    // The library: stop the app. `kill(process)` ends a runtime and says whether it did. True when
    // there was a run to stop: one with a process that `kill` ended, or one still starting. False
    // (nothing changed) when the app has no run here, its run is over, or its process would not end:
    // the caller may then look for a runtime someone else started.
    template <class Kill>
    bool stop(const std::wstring& name, Kill&& kill) {
        Run* r = find(name);
        if (!r || r->state == RunState::kEnded) return false;
        if (r->state != RunState::kStarting && !kill(r->process)) return false;
        r->stopped = true;
        return true;
    }

    // The library: the run is being ended on the user's word by other means, or was just asked to stop
    // and may be over already. However it ends is no failure. False when the app has no run here.
    bool expect_end(const std::wstring& name) {
        Run* r = find(name);
        if (r) r->stopped = true;
        return r != nullptr;
    }

    // The library, on kRunExited: the run is over and forgotten. Nothing when it is not at its end.
    std::optional<RunEnd> finish(const std::wstring& name) {
        const auto it = runs_.find(name);
        if (it == runs_.end() || it->second.state != RunState::kEnded) return std::nullopt;
        const RunEnd end{it->second.stopped, it->second.was_ready, it->second.clean};
        runs_.erase(it);
        return end;
    }

    // An app with a run counts as running until the library has heard of its end.
    bool active(const std::wstring& name) const { return find(name) != nullptr; }
    bool empty() const { return runs_.empty(); }
    std::optional<RunState> state(const std::wstring& name) const {
        const Run* r = find(name);
        return r ? std::optional<RunState>(r->state) : std::nullopt;
    }
    // The runtime of a run that has one, else null.
    Process process(const std::wstring& name) const {
        const Run* r = find(name);
        return r ? r->process : nullptr;
    }
    std::vector<std::wstring> names() const {
        std::vector<std::wstring> out;
        for (const auto& r : runs_) out.push_back(r.first);
        return out;
    }

private:
    struct Run {
        RunState state = RunState::kStarting;
        Process process = nullptr;
        bool stopped = false, was_ready = false, clean = false;
    };
    const Run* find(const std::wstring& name) const {
        const auto it = runs_.find(name);
        return it == runs_.end() ? nullptr : &it->second;
    }
    Run* find(const std::wstring& name) { return const_cast<Run*>(std::as_const(*this).find(name)); }

    std::map<std::wstring, Run> runs_;
};

}  // namespace winapk::launcher
