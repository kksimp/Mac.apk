// video_player.cpp -- see video_player.h.

#include "video_player.h"

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <mutex>
#include <thread>

#include <windows.h>
#include <objbase.h>

#include "audio_engine.h"

namespace winapk::video {

namespace au = winapk::audio;

struct Player::Impl {
    std::unique_ptr<Source> src;
    std::shared_ptr<au::Stream> audio;  // null without a sound track
    int rate = 0, channels = 0;
    int width = 0, height = 0;
    int64_t duration_us = 0;

    std::mutex lock;
    std::condition_variable consumer;  // a picture arrived, or the state changed
    std::condition_variable pump;      // room in the queue, play, a seek, release

    enum State { kPrepared, kPlaying, kPaused, kCompleted };
    State state = kPrepared;
    bool released = false, looping = false;
    int64_t seek_request_us = -1;
    bool show_one = true;      // the next picture is due whatever the clock says
    int64_t drop_before_us = 0;  // pictures and sound before a seek's target
    bool eos = false;          // the pump is idle at the end (not looping)
    bool video_eos = false, audio_eos = false;
    // Pictures and the clock belong to a segment: bumped by a seek and at each
    // loop, so a picture from after the loop point is not "late" against a
    // clock still in the previous pass.
    int segment = 0, clock_segment = 0;

    struct Queued {
        std::shared_ptr<Frame> frame;
        int segment;
    };
    std::deque<Queued> queue;
    static constexpr size_t kQueueMax = 3;
    int64_t video_end_us = 0, audio_end_us = 0;  // how far each stream has been read

    // The audio clock: the stream's playback position, anchored where a
    // segment's first sound was written. frames_written counts since the last
    // flush, as the position does.
    int64_t anchor_pts_us = 0, anchor_frame = 0, frames_written = 0;
    bool anchor_pending = true;
    // The wall clock, when there is no sound: frozen while not playing.
    int64_t wall_base_us = 0, wall_start_ns = 0;

    // Sound read but not yet written (the stream was full).
    std::vector<float> pending;
    size_t pending_at = 0;
    int64_t pending_pts_us = 0;

    // Lock held.
    int64_t clock_us() {
        if (seek_request_us >= 0) return seek_request_us;
        if (audio) return anchor_pts_us + (au::stream_position(audio.get()) - anchor_frame) * 1000000 / rate;
        return wall_base_us + (state == kPlaying ? (au::nano_time() - wall_start_ns) / 1000 : 0);
    }

    // Lock held. At the end of the last picture and the last sound. The mixer
    // interpolates between neighbouring samples, so it holds the last frame
    // written back and the position stops one short of frames_written.
    // Not while a seek is pending: eos, the queue and frames_written are still
    // the old pass's until the pump has done it (seek then start at once).
    void note_completion() {
        if (state == kPlaying && seek_request_us < 0 && eos && queue.empty() &&
            (!audio || au::stream_position(audio.get()) + 1 >= frames_written)) {
            state = kCompleted;
            wall_base_us = duration_us;
            consumer.notify_all();
        }
    }

    // ---- the pump thread ---------------------------------------------------------------

    void run() {
        CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        for (;;) {
            std::unique_lock<std::mutex> hold(lock);
            if (released) break;
            if (seek_request_us >= 0) {
                do_seek(hold);
                continue;
            }
            const bool have_pending = pending_at < pending.size();
            const bool room = queue.size() < kQueueMax && !video_eos;
            // The sound stays a little ahead of the pictures, so the clock
            // runs when a picture is due and a chunked file's interleaving
            // never starves either side.
            const bool audio_behind =
                audio && !audio_eos && !have_pending && (video_eos || audio_end_us < video_end_us + 300000);
            hold.unlock();
            if (have_pending) {
                const size_t took = au::stream_write(audio.get(), pending.data() + pending_at,
                                                     pending.size() - pending_at, 4, false);
                hold.lock();
                if (took) {
                    if (anchor_pending) {
                        anchor_pts_us = pending_pts_us + static_cast<int64_t>(pending_at / channels) * 1000000 / rate;
                        anchor_frame = frames_written;
                        anchor_pending = false;
                        clock_segment = segment;
                        consumer.notify_all();
                    }
                    frames_written += static_cast<int64_t>(took / channels);
                    pending_at += took;
                    continue;
                }
                hold.unlock();
                // Full: while playing it drains within milliseconds; paused,
                // until play. Pictures can still be read meanwhile.
            } else if (audio_behind) {
                read_audio();
                continue;
            }
            if (room) {
                read_video();
                continue;
            }
            hold.lock();
            if (video_eos && audio_eos && !have_pending) {
                if (looping) {
                    do_loop(hold);
                    continue;
                }
                if (!eos) {
                    eos = true;
                    consumer.notify_all();
                }
            }
            pump.wait_for(hold, std::chrono::milliseconds(20));
        }
    }

    void read_audio() {
        Source::Packet p;
        const bool ok = src->read_audio(&p);
        std::lock_guard<std::mutex> hold(lock);
        if (seek_request_us >= 0) return;  // a seek will restart the stream
        if (!ok || p.kind == Source::Packet::kEnd) {
            audio_eos = true;
            return;
        }
        const int64_t frames = static_cast<int64_t>(p.pcm.size() / channels);
        const int64_t end_us = p.pts_us + frames * 1000000 / rate;
        audio_end_us = end_us;
        if (end_us <= drop_before_us) return;
        size_t skip = 0;
        if (p.pts_us < drop_before_us) skip = static_cast<size_t>((drop_before_us - p.pts_us) * rate / 1000000);
        pending = std::move(p.pcm);
        pending_at = std::min(skip * channels, pending.size());
        pending_pts_us = p.pts_us;
    }

    void read_video() {
        Source::Packet p;
        const bool ok = src->read_video(&p);
        std::lock_guard<std::mutex> hold(lock);
        if (seek_request_us >= 0) return;
        if (!ok || p.kind == Source::Packet::kEnd) {
            video_eos = true;
            consumer.notify_all();
            return;
        }
        if (!p.frame) return;
        video_end_us = p.pts_us;
        if (p.pts_us < drop_before_us) return;  // decoded from the key frame before the target
        queue.push_back({std::move(p.frame), segment});
        consumer.notify_all();
    }

    // Lock held (and dropped around the reader).
    void do_seek(std::unique_lock<std::mutex>& hold) {
        const int64_t target = std::clamp<int64_t>(seek_request_us, 0, duration_us > 0 ? duration_us : seek_request_us);
        queue.clear();
        pending.clear();
        pending_at = 0;
        video_eos = audio_eos = eos = false;
        drop_before_us = target;
        segment++;
        clock_segment = segment;
        frames_written = 0;
        anchor_frame = 0;
        anchor_pts_us = target;
        anchor_pending = true;
        audio_end_us = video_end_us = target;
        wall_base_us = target;
        wall_start_ns = au::nano_time();
        show_one = true;
        const State st = state;
        hold.unlock();
        if (audio) {
            // The stream drops what it holds only while not playing.
            au::stream_pause(audio.get());
            au::stream_flush(audio.get());
            if (st == kPlaying) au::stream_play(audio.get());
        }
        src->seek(target);
        hold.lock();
        // start() may have set kPlaying (and played the stream) between the
        // state read above and the pause: then the stream must play again, or
        // the clock never moves and no picture after the first is ever due.
        if (audio && st != kPlaying && state == kPlaying) {
            hold.unlock();
            au::stream_play(audio.get());
            hold.lock();
        }
        seek_request_us = -1;
        consumer.notify_all();
    }

    // Lock held. The end, looping: back to the start without a break in the
    // sound (what is buffered plays on; the clock follows into the new pass
    // with its first sound).
    void do_loop(std::unique_lock<std::mutex>& hold) {
        hold.unlock();
        src->seek(0);
        hold.lock();
        // eos too: it is left over when looping was turned on after the end,
        // and note_completion would otherwise complete the new pass at once.
        video_eos = audio_eos = eos = false;
        drop_before_us = 0;
        segment++;
        anchor_pending = true;
        audio_end_us = video_end_us = 0;
    }
};

Player::Player(std::unique_ptr<Impl> impl) : impl_(std::move(impl)) {}

Player::~Player() { release(); }

std::shared_ptr<Player> Player::open(std::unique_ptr<Source> source) {
    if (!source || !source->has_video()) return nullptr;
    auto impl = std::make_unique<Impl>();
    impl->src = std::move(source);
    impl->width = impl->src->width();
    impl->height = impl->src->height();
    impl->duration_us = impl->src->duration_us();
    if (impl->src->has_audio()) {
        impl->rate = impl->src->audio_rate();
        impl->channels = impl->src->audio_channels();
        // A quarter second of sound: the pump writes ahead by that much and
        // the pictures follow the stream's position, so the buffer is the
        // bound on how far pause, seek and volume lag.
        impl->audio = au::stream_create(impl->rate, impl->channels, 4, impl->rate / 4);
        if (!impl->audio) impl->rate = impl->channels = 0;
    }
    auto p = std::shared_ptr<Player>(new Player(std::move(impl)));
    // The pump owns a reference: release() need not wait for a read that is
    // blocked on the network.
    std::shared_ptr<Player> keep = p;
    std::thread([keep] { keep->impl_->run(); }).detach();
    return p;
}

int Player::width() const { return impl_->width; }
int Player::height() const { return impl_->height; }
int Player::duration_ms() const { return static_cast<int>(impl_->duration_us / 1000); }

void Player::start() {
    Impl& d = *impl_;
    {
        std::lock_guard<std::mutex> hold(d.lock);
        if (d.released) return;
        if (d.state == Impl::kCompleted && d.seek_request_us < 0) {
            d.seek_request_us = 0;
            d.show_one = true;
        }
        if (d.state != Impl::kPlaying) {
            d.wall_start_ns = au::nano_time();
            d.state = Impl::kPlaying;
        }
        d.consumer.notify_all();
        d.pump.notify_all();
    }
    if (d.audio) au::stream_play(d.audio.get());
}

void Player::pause() {
    Impl& d = *impl_;
    {
        std::lock_guard<std::mutex> hold(d.lock);
        if (d.state != Impl::kPlaying) return;
        d.wall_base_us = d.clock_us();
        d.state = Impl::kPaused;
        d.consumer.notify_all();
    }
    if (d.audio) au::stream_pause(d.audio.get());
}

void Player::stop() {
    pause();
    seek(0);
}

void Player::seek(int ms) {
    Impl& d = *impl_;
    std::lock_guard<std::mutex> hold(d.lock);
    if (d.released) return;
    d.seek_request_us = static_cast<int64_t>(ms < 0 ? 0 : ms) * 1000;
    d.show_one = true;
    if (d.state == Impl::kCompleted) d.state = Impl::kPaused;
    d.consumer.notify_all();
    d.pump.notify_all();
}

void Player::set_looping(bool loop) {
    std::lock_guard<std::mutex> hold(impl_->lock);
    impl_->looping = loop;
    impl_->pump.notify_all();
}

void Player::set_volume(float left, float right) {
    if (impl_->audio) au::stream_set_volume(impl_->audio.get(), left, right);
}

int Player::position_ms() {
    Impl& d = *impl_;
    std::lock_guard<std::mutex> hold(d.lock);
    d.note_completion();
    if (d.state == Impl::kCompleted) return duration_ms();
    const int64_t us = std::clamp<int64_t>(d.clock_us(), 0, d.duration_us > 0 ? d.duration_us : INT64_MAX);
    return static_cast<int>(us / 1000);
}

bool Player::playing() {
    Impl& d = *impl_;
    std::lock_guard<std::mutex> hold(d.lock);
    d.note_completion();
    return d.state == Impl::kPlaying;
}

std::shared_ptr<Frame> Player::await_frame(int timeout_ms, bool* released) {
    Impl& d = *impl_;
    *released = false;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);
    std::unique_lock<std::mutex> hold(d.lock);
    for (;;) {
        if (d.released) {
            *released = true;
            return nullptr;
        }
        d.note_completion();
        if (d.queue.empty()) {
            if (d.consumer.wait_until(hold, deadline) == std::cv_status::timeout) return nullptr;
            continue;
        }
        const Impl::Queued& head = d.queue.front();
        bool due, timed = false;
        if (d.show_one) {
            due = true;
        } else if (head.segment > d.clock_segment) {
            // The next pass: with sound, its first sound brings the clock
            // along; without, showing its first picture does.
            due = !d.audio && d.state == Impl::kPlaying;
        } else if (head.segment < d.clock_segment) {
            due = true;  // the previous pass's last pictures, older than the clock
        } else if (d.state != Impl::kPlaying) {
            due = false;
        } else {
            due = head.frame->pts_us <= d.clock_us() + 2000;
            timed = !due;
        }
        if (!due) {
            if (!timed) {
                if (d.consumer.wait_until(hold, deadline) == std::cv_status::timeout) return nullptr;
                continue;
            }
            // The audio clock advances without a signal: wake when the picture
            // should be due, then look again.
            const auto wake = std::chrono::steady_clock::now() +
                              std::chrono::microseconds(std::max<int64_t>(head.frame->pts_us - d.clock_us(), 1000));
            d.consumer.wait_until(hold, std::min(wake, deadline));
            if (std::chrono::steady_clock::now() >= deadline) return nullptr;
            continue;
        }
        // Behind: skip to the newest picture that is also due.
        if (!d.show_one && d.state == Impl::kPlaying) {
            while (d.queue.size() > 1 && d.queue[1].segment == d.queue[0].segment &&
                   d.queue[1].frame->pts_us <= d.clock_us()) {
                d.queue.pop_front();
            }
        }
        std::shared_ptr<Frame> f = std::move(d.queue.front().frame);
        const int seg = d.queue.front().segment;
        d.queue.pop_front();
        d.show_one = false;
        if (!d.audio && seg > d.clock_segment) {
            d.wall_base_us = f->pts_us;
            d.wall_start_ns = au::nano_time();
            d.clock_segment = seg;
        }
        d.pump.notify_all();
        return f;
    }
}

void Player::release() {
    Impl& d = *impl_;
    {
        std::lock_guard<std::mutex> hold(d.lock);
        if (d.released) return;
        d.released = true;
        d.consumer.notify_all();
        d.pump.notify_all();
    }
    if (d.audio) au::stream_release(d.audio);
}

}  // namespace winapk::video
