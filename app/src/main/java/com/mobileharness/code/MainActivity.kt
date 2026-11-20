package com.mobileharness.code

import android.content.Context
import android.os.Bundle
import android.content.ComponentCallbacks2
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.LayoutDirection.Ltr
import androidx.compose.ui.unit.LayoutDirection.Rtl
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobileharness.code.devtools.CrashHandler
import com.mobileharness.code.ui.AppStrings
import com.mobileharness.code.ui.LocaleHelper
import com.mobileharness.code.ui.MainViewModel
import com.mobileharness.code.ui.PocketDevApp
import com.mobileharness.code.ui.theme.PocketTheme

class MainActivity : ComponentActivity() {
    private var appliedLanguageCode: String? = null

    // Apply the in-app language to the Activity itself, not only to the Compose tree:
    // dialogs, dropdown menus and popups get their own window whose context is the Activity.
    override fun attachBaseContext(newBase: Context) {
        appliedLanguageCode = AppStrings.languageCode(newBase)
        super.attachBaseContext(AppStrings.context(newBase))
    }
    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashHandler.install(applicationContext)
        enableEdgeToEdge()
        setContent {
            val vm = mainViewModel
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.languageCode) {
                if (state.languageCode != appliedLanguageCode) recreate()
            }
            val context = LocalContext.current
            val localizedContext = remember(state.languageCode, context) {
                LocaleHelper.applyLanguage(context, state.languageCode)
            }
            val localizedConfiguration = localizedContext.resources.configuration
            val layoutDirection: LayoutDirection =
                if (localizedConfiguration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) Rtl else Ltr
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedConfiguration,
                LocalLayoutDirection provides layoutDirection,
                LocalActivityResultRegistryOwner provides this,
            ) {
                PocketTheme(themeMode = state.themeMode, dynamicColor = state.dynamicColorEnabled) {
                    PocketDevApp(vm)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Return-from-background edge: refresh live SSH status (and diagnostics after a long
        // absence) so a stale "connected" never lies. Fail-closed — never reconnects.
        mainViewModel.onAppForeground()
    }

    override fun onStop() {
        mainViewModel.onAppBackground()
        super.onStop()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            mainViewModel.releaseMemoryCaches()
        }
    }
}
