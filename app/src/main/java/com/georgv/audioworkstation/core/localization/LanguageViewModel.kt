package com.georgv.audioworkstation.core.localization

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.core.localization.data.LanguageRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LanguageViewModel(
    private val repo: LanguageRepository
) : ViewModel() {

    val currentTag: StateFlow<String> =
        repo.languageTagFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = "en"
        )

    fun setLanguage(tag: String) {
        viewModelScope.launch {
            repo.setLanguageTag(tag)
        }
    }

    class Factory(private val appContext: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val repo = LanguageRepository(appContext)
            return LanguageViewModel(repo) as T
        }
    }
}


