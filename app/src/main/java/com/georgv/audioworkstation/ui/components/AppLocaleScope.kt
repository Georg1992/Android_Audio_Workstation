package com.georgv.audioworkstation.ui.components

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens

/**
 * Menus and dialogs draw in a new window that replaces [LocalContext].
 * Capture the app locale outside that window and put it back inside the content.
 */
class AppLocaleScope(
    private val context: Context,
    private val configuration: Configuration,
) {
    @Composable
    fun Provide(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            content = content,
        )
    }
}

@Composable
fun rememberAppLocaleScope(): AppLocaleScope {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) { AppLocaleScope(context, configuration) }
}

@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Dimens.TileRadius),
    content: @Composable ColumnScope.() -> Unit,
) {
    val locale = rememberAppLocaleScope()
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = shape,
        containerColor = AppColors.Bg,
        tonalElevation = 0.dp,
    ) {
        val menuScope = this
        locale.Provide {
            menuScope.content()
        }
    }
}
