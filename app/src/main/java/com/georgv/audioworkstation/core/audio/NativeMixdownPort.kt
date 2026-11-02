package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.core.coroutines.checkNotMainThreadForNativeLifecycle
import com.georgv.audioworkstation.core.coroutines.withAudioIo
import com.georgv.audioworkstation.engine.NativeEngine
import com.georgv.audioworkstation.engine.NativeMixdownStatus
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NativeMixdownPort @Inject constructor(
    private val nativeEngine: NativeEngine,
    private val dispatchers: AppDispatchers,
) : MixdownPort {
    override suspend fun renderOfflineMixdown(
        spec: MultiPlaybackSpec,
        outputPath: String,
        onProgress: (Float) -> Unit,
    ): MixdownResult =
        withAudioIo(dispatchers, "renderOfflineMixdown") {
            checkNotMainThreadForNativeLifecycle("renderOfflineMixdown")
            when (nativeEngine.renderOfflineMixdown(spec, outputPath, onProgress)) {
                NativeMixdownStatus.Success -> MixdownResult.Success(outputPath)
                NativeMixdownStatus.Cancelled -> MixdownResult.Cancelled
                NativeMixdownStatus.Failed -> MixdownResult.Failed
            }
        }

    override fun cancelOfflineMixdown() {
        nativeEngine.cancelOfflineMixdown()
    }
}
