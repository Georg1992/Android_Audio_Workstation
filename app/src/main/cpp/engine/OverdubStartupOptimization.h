#pragma once

namespace dawengine {
class AudioEngine;
} // namespace dawengine

namespace overdub_startup_opt {

void logStartupTiming(const dawengine::AudioEngine *engine, const char *trigger);

} // namespace overdub_startup_opt
