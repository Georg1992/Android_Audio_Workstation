#pragma once

#include <cstdint>

namespace dawengine {

/**
 * Per-slot playback lane lifecycle (HJ.2).
 *
 * Valid transitions:
 *   Inactive -> Preparing           (hot-join requested)
 *   Preparing -> ReadyToCommit      (WAV open + staging ring built)
 *   Preparing -> Cancelled          (deselect while preparing)
 *   ReadyToCommit -> Active         (commit at transportFrame)
 *   ReadyToCommit -> Cancelled      (deselect before commit)
 *   Cancelled -> Inactive           (staging torn down)
 *   Inactive -> Active              (session arm via setPlaybackSources)
 *   Active -> Active                (HJ.1: audibleEnabled toggles only)
 *   Active -> Exhausted             (source drained; still participates in completion)
 *   Active/Exhausted -> Inactive    (stopPlayback / release / clear)
 *
 * HJ.1 "disabled" audibility uses Active + audibleEnabled=false (no Disabled state).
 */
enum class PlaybackLaneLifecycle : uint8_t {
    Inactive = 0,
    Preparing,
    ReadyToCommit,
    Active,
    Cancelled,
    Exhausted,
};

inline bool laneLifecycleParticipatesInMix(PlaybackLaneLifecycle state) {
    return state == PlaybackLaneLifecycle::Active ||
           state == PlaybackLaneLifecycle::Exhausted;
}

inline bool laneLifecycleParticipatesInCompletion(PlaybackLaneLifecycle state) {
    return state == PlaybackLaneLifecycle::Active ||
           state == PlaybackLaneLifecycle::Exhausted;
}

} // namespace dawengine
