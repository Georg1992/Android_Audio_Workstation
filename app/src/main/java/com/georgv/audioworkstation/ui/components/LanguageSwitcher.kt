package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.localization.LanguageViewModel
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens

@Composable
fun LanguageSwitcher(
    languageVm: LanguageViewModel = viewModel(
        factory = LanguageViewModel.Factory(LocalContext.current.applicationContext)),
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    val currentTag by languageVm.currentTag.collectAsState()

    val currentLabel = when {
        currentTag.startsWith("ru") -> "RU"
        currentTag.startsWith("zh") -> "CH"
        else -> "EN"
    }

    fun setLang(tag: String) {
        languageVm.setLanguage(tag)
        expanded = false
    }

    // TODO: замени на свои AppColors/Dimens если они есть
    val bg = Color(0xFF121212)
    val line = Color(0xFFE0E0E0)
    val stroke = 1.dp
    val shape = RoundedCornerShape(6.dp)

    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Box(
                modifier = Modifier
                    .size(Dimens.LangChipSize)
                    .background(AppColors.Bg)
                    .border(Dimens.Stroke, AppColors.Line, shape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = currentLabel,
                    color = AppColors.Line,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lang_english)) },
                onClick = { setLang("en") }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lang_russian)) },
                onClick = { setLang("ru") }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lang_chinese)) },
                onClick = { setLang("zh-CN") }
            )
        }
    }
}







