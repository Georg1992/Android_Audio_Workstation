package com.georgv.audioworkstation.online.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.core.util.logWarning
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@HiltViewModel
class SharedFileRestoreViewModel @Inject constructor(
    private val share: ProjectShare,
) : ViewModel() {
    fun restore(projectId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                share.restoreMissing(projectId)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                logWarning(TAG, "restoreMissing failed: $projectId", error)
            }
        }
    }

    private companion object {
        const val TAG = "SharedFileRestore"
    }
}
