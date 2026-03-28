package com.georgv.audioworkstation.core.localization

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

@Composable
fun ProvideAppLocale(
    languageTag: String,
    content: @Composable () -> Unit
) {
    val baseContext = LocalContext.current
    val localizedContext = remember(languageTag, baseContext) {
        contextForLanguageTag(baseContext, languageTag)
    }
    val localizedConfiguration = remember(localizedContext) {
        Configuration(localizedContext.resources.configuration)
    }
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedConfiguration
    ) {
        content()
    }
}

private class LocaleResourcesContext(
    base: Context,
    private val localizedResources: Resources
) : ContextWrapper(base) {
    override fun getResources(): Resources = localizedResources
}

private fun contextForLanguageTag(base: Context, languageTag: String): Context {
    val config = Configuration(base.resources.configuration)
    config.setLocales(LocaleList.forLanguageTags(languageTag))
    val configContext = base.createConfigurationContext(config)
    return LocaleResourcesContext(base, configContext.resources)
}
