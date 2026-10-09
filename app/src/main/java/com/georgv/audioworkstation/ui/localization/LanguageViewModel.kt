package com.georgv.audioworkstation.ui.localization

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.core.localization.data.AppLanguageStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LanguageViewModel @Inject constructor(
    private val appLanguageStore: AppLanguageStore
) : ViewModel() {

    val currentTag: StateFlow<String> =
        appLanguageStore.languageTagFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = appLanguageStore.defaultLanguageTag
        )

    init {
        viewModelScope.launch {
            appLanguageStore.ensureInitialized()
        }
    }

    fun setLanguage(tag: String) {
        viewModelScope.launch { appLanguageStore.setLanguageTag(tag) }
    }
}
