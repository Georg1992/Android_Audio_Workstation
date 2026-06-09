#pragma once

#include <cstdint>

namespace dawengine {
class AudioEngine;
} // namespace dawengine

namespace device_latency_budget_diag {

void resetDeviceLatencyBudgetDiagnostics();

void logDeviceLatencyBudget(dawengine::AudioEngine *engine,
                            int64_t legacyFirstSampleTransportFrame,
                            int64_t appReceiveMonotonicNs);

} // namespace device_latency_budget_diag
