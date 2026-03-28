package com.georgv.audioworkstation.core.localization

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.core.localization.data.LanguageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LanguageViewModel @Inject constructor(
    private val repo: LanguageRepository
) : ViewModel() {

    val currentTag: StateFlow<String> =
        repo.languageTagFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = repo.defaultLanguageTag
        )

    init {
        viewModelScope.launch {
            repo.ensureInitialized()
        }
    }

    fun setLanguage(tag: String) {
        viewModelScope.launch { repo.setLanguageTag(tag) }
    }
}
