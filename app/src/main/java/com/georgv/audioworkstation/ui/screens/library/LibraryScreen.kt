package com.georgv.audioworkstation.ui.screens.library

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.ui.components.ScreenScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onBack: () -> Unit) {
    ScreenScaffold(title = "Library", onBack = onBack) {padding ->
        Text("Library Screen", modifier = Modifier.padding(padding).padding(16.dp))
    }
}

