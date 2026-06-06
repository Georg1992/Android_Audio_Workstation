package com.georgv.audioworkstation.core.coroutines

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/** Process-scoped scope for audio teardown that must outlive a single [androidx.lifecycle.ViewModel]. */
@Singleton
class AudioIoScope @Inject constructor(dispatchers: AppDispatchers) {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.audioIo)
}
