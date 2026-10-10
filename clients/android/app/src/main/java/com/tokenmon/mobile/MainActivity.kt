package com.tokenmon.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tokenmon.mobile.ui.DashboardScreen
import com.tokenmon.mobile.ui.DashboardViewModel
import com.tokenmon.mobile.ui.SettingsScreen
import com.tokenmon.mobile.ui.theme.TokenMonitorTheme
import com.tokenmon.mobile.work.QuotaRefreshWorker

/**
 * Two screens, one boolean.
 *
 * A navigation graph library would add a dependency to solve a problem this app
 * does not have — there is a dashboard and a settings page, and back means the
 * dashboard. System back is handled explicitly so the gesture feels right.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        QuotaRefreshWorker.schedule(this)

        setContent {
            TokenMonitorTheme {
                TokenMonitorAppRoot()
            }
        }
    }
}

@Composable
private fun TokenMonitorAppRoot(viewModel: DashboardViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }

    // The SSE stream follows the visible lifetime of the screen. In the
    // background the widget refresh job takes over instead.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.onForeground()
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                viewModel.onBackground()
            }
        }
    }

    BackHandler(enabled = showSettings) { showSettings = false }

    AnimatedContent(
        targetState = showSettings,
        transitionSpec = {
            if (targetState) {
                (slideInHorizontally { it / 3 } + fadeIn()) togetherWith fadeOut()
            } else {
                fadeIn() togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
            }
        },
        label = "screen"
    ) { inSettings ->
        if (inSettings) {
            SettingsScreen(
                state = state,
                onBack = { showSettings = false },
                onSaveUrl = viewModel::saveHubUrl,
                onSaveSecret = viewModel::saveSecret,
                onClearSecret = viewModel::clearSecret,
                onSetAutoRefresh = viewModel::setAutoRefresh,
                onSetWidgetAppearance = viewModel::setWidgetAppearance,
                onTestConnection = viewModel::testConnection,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            DashboardScreen(
                state = state,
                onRefresh = viewModel::refreshNow,
                onOpenSettings = { showSettings = true },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
