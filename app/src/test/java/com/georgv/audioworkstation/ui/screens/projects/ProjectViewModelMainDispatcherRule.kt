package com.georgv.audioworkstation.ui.screens.projects

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Exposes one [TestDispatcher] for both [kotlinx.coroutines.test.runTest] and [Dispatchers.setMain], so virtual-time
 * advances drive `viewModelScope` work on the same scheduler as the test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectViewModelMainDispatcherRule : TestWatcher() {
    lateinit var dispatcher: TestDispatcher

    override fun starting(description: Description) {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
