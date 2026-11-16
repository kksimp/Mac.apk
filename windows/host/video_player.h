// video_player.h -- MediaPlayer's video backend: a Source (video_decode.h)
// played with its sound through the audio engine (audio_engine.h) and its
// pictures handed out one at a time, when each is due.
//
// A pump thread demuxes and decodes: audio goes into an AudioTrack-style
// stream of the engine, whose buffer (a quarter second) throttles it to real
// time; pictures wait in a short queue for the consumer. The consumer is the
// Java side's own thread calling await_frame, which blocks until the next
// picture is due by the clock: the audio stream's playback position when
// there is sound (so the picture follows what is heard, and pausing freezes
// both), the wall clock otherwise. A picture that is due but followed by
// another that is also due is dropped, so a slow machine falls behind by
// frames, not by time.

#pragma once

#include <cstdint>
#include <memory>

#include "video_decode.h"

namespace winapk::video {

class Player {
public:
    // Takes a Source with a video stream. Null without one.
    static std::shared_ptr<Player> open(std::unique_ptr<Source> source);
    ~Player();

    int width() const;
    int height() const;
    int duration_ms() const;

    void start();
    void pause();
    // Back to the start, paused (MediaPlayer's stop + the next start playing
    // from the beginning).
    void stop();
    void seek(int ms);
    void set_looping(bool loop);
    void set_volume(float left, float right);
    int position_ms();
    // Started, not paused, not at the end.
    bool playing();

    // The next picture to show, once it is due, or null after `timeout_ms`
    // (the caller shows nothing and asks again). Null with *released set once
    // release() was called: the consumer's thread is done. The first picture
    // after open and after a seek comes at once, in any state, as a device
    // shows the frame it is positioned on.
    std::shared_ptr<Frame> await_frame(int timeout_ms, bool* released);

    // Stops the pump and the sound; every wait returns.
    void release();

private:
    struct Impl;
    explicit Player(std::unique_ptr<Impl> impl);
    std::unique_ptr<Impl> impl_;
};

}  // namespace winapk::video
