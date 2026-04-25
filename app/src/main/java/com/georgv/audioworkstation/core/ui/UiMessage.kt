package com.georgv.audioworkstation.core.ui

import android.content.Context
import androidx.annotation.StringRes

/**
 * Localizable user-facing message produced by ViewModels and resolved by Composables via
 * `stringResource(message.resId, *message.args.toTypedArray())`.
 *
 * Keeping the resource id as a plain `Int` (annotated `@StringRes`) means ViewModels stay free of
 * Android `Context`/`Resources` lookups — this is what makes the JVM unit tests still trivial.
 */
data class UiMessage(
    @StringRes val resId: Int,
    val args: List<Any> = emptyList()
) {
    constructor(@StringRes resId: Int, vararg args: Any) : this(resId, args.toList())
}

/**
 * Resolves the message against the supplied [context] inside non-composable callbacks
 * (e.g. inside `LaunchedEffect { flow.collect { ... } }`). Composables that already have the
 * message instance can call `stringResource(message.resId, *message.args.toTypedArray())` directly.
 */
fun UiMessage.resolve(context: Context): String =
    if (args.isEmpty()) context.getString(resId)
    else context.getString(resId, *args.toTypedArray())
