package com.georgv.audioworkstation.core.audio

/**
 * Threading contract for the Android DAW audio stack.
 *
 * ## Main
 * - Jetpack Compose UI and immediate UI state ([kotlinx.coroutines.flow.StateFlow] writes for display).
 * - ViewModel intent entry points that only update Kotlin UI/optimistic state.
 * - Must not call JNI, Oboe lifecycle, file I/O, or Room.
 *
 * ## [com.georgv.audioworkstation.core.coroutines.AppDispatchers.audioIo]
 * - Native engine lifecycle: acquire/release session, [com.georgv.audioworkstation.core.audio.PlaybackPort.release].
 * - Transport: start/stop playback, start/stop recording, seek/restart that arms native lanes.
 * - Hot-join lane prepare/commit/cancel, lane audibility batch updates during transport.
 * - Native peak reset and other session-scoped native mutations (non-live-parameter).
 * - Serialized via [AudioEngineSession] mutex where lifecycle overlaps release.
 *
 * ## [com.georgv.audioworkstation.core.coroutines.AppDispatchers.audioParam]
 * - High-frequency live mix parameters (per-lane gain/pan) via [AudioParameterCommandQueue].
 * - Single consumer, coalesced last-value per lane+parameter; no coroutine per pointer event.
 * - Commands carry [AudioEngineSession.parameterEpoch]; stale commands are dropped after release.
 * - Native C++ applies gain/pan with lock-free atomic stores only (safe across threads).
 *
 * ## [com.georgv.audioworkstation.core.coroutines.AppDispatchers.io]
 * - Room/database, filesystem, import reads, WAV writes/deletes, StatFs.
 *
 * ## [com.georgv.audioworkstation.core.coroutines.AppDispatchers.default]
 * - CPU work: timeline projection combiners, waveform peak math (after file read).
 * - Background polling of native transport/peak meters (not Main).
 *
 * ## Native Oboe render callback ([dawengine::AudioEngine::render])
 * - No mutex, no allocation, no file I/O, no JNI.
 * - Reads live gain/pan/audibility via atomics only.
 *
 * Debug builds may log Main-thread native lifecycle violations via [com.georgv.audioworkstation.core.diagnostics.ThreadingDiagnostics].
 */
object AudioThreadingContract
