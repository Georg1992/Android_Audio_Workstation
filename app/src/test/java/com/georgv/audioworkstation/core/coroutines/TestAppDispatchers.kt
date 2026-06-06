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
    audioParam: CoroutineDispatcher = UnconfinedTestDispatcher(),
) : AppDispatchers {
    override val main: CoroutineDispatcher = main
    override val io: CoroutineDispatcher = io
    override val default: CoroutineDispatcher = default
    override val audioIo: CoroutineDispatcher = audioIo
    override val audioParam: CoroutineDispatcher = audioParam

    companion object {
        /** Single scheduler for Main/IO/audio/Default — keeps JVM unit tests on one virtual-time clock. */
        fun unified(main: CoroutineDispatcher): TestAppDispatchers =
            TestAppDispatchers(
                main = main,
                io = main,
                default = main,
                audioIo = main,
                audioParam = main,
            )

        /** Main unified; [AppDispatchers.audioParam] on a separate test dispatcher for thread audits. */
        fun withSeparateAudioParam(
            main: CoroutineDispatcher,
            audioParam: CoroutineDispatcher,
        ): TestAppDispatchers =
            TestAppDispatchers(
                main = main,
                io = main,
                default = main,
                audioIo = main,
                audioParam = audioParam,
            )
    }
}
