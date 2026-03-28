import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.georgv.audioworkstation.core.localization.LanguageViewModel
import com.georgv.audioworkstation.core.localization.ProvideAppLocale
import com.georgv.audioworkstation.ui.local.LocalLanguageVm
import com.georgv.audioworkstation.ui.navigation.AppNavHost

@Composable
fun AppRoot() {
    val languageVm: LanguageViewModel = viewModel(
        factory = LanguageViewModel.Factory(LocalContext.current.applicationContext)
    )

    val tag by languageVm.currentTag.collectAsStateWithLifecycle()

    MaterialTheme {
        ProvideAppLocale(languageTag = tag) {
            CompositionLocalProvider(LocalLanguageVm provides languageVm) {
                val navController = rememberNavController()

                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    AppNavHost(navController = navController)
                }
            }
        }
    }
}
