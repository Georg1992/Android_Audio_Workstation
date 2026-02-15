import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.georgv.audioworkstation.core.localization.LanguageViewModel
import com.georgv.audioworkstation.ui.components.AppSplash
import com.georgv.audioworkstation.ui.local.LocalLanguageVm
import com.georgv.audioworkstation.ui.navigation.AppNavHost

@Composable
fun AppRoot() {
    val languageVm: LanguageViewModel = viewModel(
        factory = LanguageViewModel.Factory(LocalContext.current.applicationContext)
    )

    val tag by languageVm.currentTag.collectAsStateWithLifecycle()

    MaterialTheme {
        if (tag == null) {
            AppSplash(true)
            return@MaterialTheme
        }

        CompositionLocalProvider(LocalLanguageVm provides languageVm) {
            val navController = rememberNavController()
            val snackbarHostState = remember { SnackbarHostState() }

            Scaffold(
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
            ) { padding ->
                Surface(Modifier.padding(padding)) {
                    AppNavHost(navController = navController)
                }
            }
        }
    }
}








