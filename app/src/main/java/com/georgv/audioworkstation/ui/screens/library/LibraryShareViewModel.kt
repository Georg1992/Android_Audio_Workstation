package com.georgv.audioworkstation.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.ui.UiMessage
import com.georgv.audioworkstation.core.util.logWarning
import com.georgv.audioworkstation.online.ProjectShareCoordinator
import com.georgv.audioworkstation.online.ShareResult
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@HiltViewModel
class LibraryShareViewModel @Inject constructor(
    private val share: ProjectShareCoordinator,
) : ViewModel() {
    private val messages = Channel<UiMessage>(capacity = Channel.BUFFERED)
    val userMessages = messages.receiveAsFlow()
    private val busy = AtomicBoolean(false)

    fun share(localProjectId: String, title: String, onNeedsLogin: () -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                when (val result = share.share(localProjectId, title)) {
                    ShareResult.NeedsLogin -> onNeedsLogin()
                    is ShareResult.Shared -> messages.send(UiMessage(R.string.share_completed))
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                logWarning(TAG, "share failed: $localProjectId", error)
                messages.send(UiMessage(R.string.error_share_failed))
            } finally {
                busy.set(false)
            }
        }
    }

    private companion object {
        const val TAG = "LibraryShare"
    }
}
