package com.georgv.audioworkstation.core.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** Runs IO/audio/default work immediately in unit tests without extra scheduler advances. */
@OptIn(ExperimentalCoroutinesApi::class)
class TestAppDispatchers(
    main: CoroutineDispatcher = UnconfinedTestDispatcher(),
    io: CoroutineDispatcher = UnconfinedTestDispatcher(),
    default: CoroutineDispatcher = UnconfinedTestDispatcher(),
    audioIo: CoroutineDispatcher = UnconfinedTestDispatcher(),
) : AppDispatchers {
    override val main: CoroutineDispatcher = main
    override val io: CoroutineDispatcher = io
    override val default: CoroutineDispatcher = default
    override val audioIo: CoroutineDispatcher = audioIo

    companion object {
        /** Single scheduler for Main/IO/audio/Default — keeps JVM unit tests on one virtual-time clock. */
        fun unified(main: CoroutineDispatcher): TestAppDispatchers =
            TestAppDispatchers(main = main, io = main, default = main, audioIo = main)
    }
}
