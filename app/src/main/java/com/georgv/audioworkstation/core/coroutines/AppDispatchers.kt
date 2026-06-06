package com.georgv.audioworkstation.core.coroutines

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher

interface AppDispatchers {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val audioIo: CoroutineDispatcher
    /** Serialized queue for coalesced live gain/pan JNI updates. */
    val audioParam: CoroutineDispatcher
}

class DefaultAppDispatchers : AppDispatchers {
    override val main: CoroutineDispatcher = Dispatchers.Main.immediate
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val audioIo: CoroutineDispatcher = Dispatchers.IO
    override val audioParam: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "audio-param").apply { isDaemon = true }
        }.asCoroutineDispatcher()
}
