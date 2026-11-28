package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.di.AppContainer
import com.example.core.i18n.ProvideYawmiLocale
import com.example.core.preferences.AppAppearanceState
import com.example.presentation.navigation.YawmiShell
import com.example.reminder.NotificationPendingIntentFactory
import com.example.ui.theme.YawmiTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private val pendingOpenTaskId = MutableStateFlow<String?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    extractOpenTaskId(intent)?.let { pendingOpenTaskId.value = it }
    val container = (application as YawmiApplication).container
    setContent {
      val openTaskId by pendingOpenTaskId.collectAsStateWithLifecycle()
      YawmiRootApp(
        container = container,
        pendingOpenTaskId = openTaskId,
        onConsumeOpenTaskId = { pendingOpenTaskId.value = null },
      )
    }
  }

  override fun onResume() {
    super.onResume()
    (application as? YawmiApplication)?.onActivityResumed()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    extractOpenTaskId(intent)?.let { pendingOpenTaskId.value = it }
  }

  private fun extractOpenTaskId(intent: Intent?): String? =
    intent?.getStringExtra(NotificationPendingIntentFactory.EXTRA_OPEN_TASK_ID)
      ?.takeIf { it.isNotBlank() }
}

@Composable
fun YawmiRootApp(
  container: AppContainer,
  pendingOpenTaskId: String? = null,
  onConsumeOpenTaskId: () -> Unit = {},
) {
  val appearanceState by
    container.appearanceRepository.appearanceState.collectAsStateWithLifecycle(
      initialValue = AppAppearanceState()
    )
  val scope = rememberCoroutineScope()

  ProvideYawmiLocale(language = appearanceState.language) {
    YawmiTheme(themeMode = appearanceState.themeMode) {
      YawmiShell(
        appearanceState = appearanceState,
        localClock = container.localClock,
        taskRepository = container.taskRepository,
        taskUseCases = container.taskUseCases,
        taskReminderUseCases = container.taskReminderUseCases,
        categoryUseCases = container.categoryUseCases,
        onSelectThemeMode = { mode ->
          scope.launch { container.appearanceRepository.setThemeMode(mode) }
        },
        onSelectLanguage = { lang ->
          scope.launch { container.appearanceRepository.setLanguage(lang) }
        },
        pendingOpenTaskId = pendingOpenTaskId,
        onConsumeOpenTaskId = onConsumeOpenTaskId,
      )
    }
  }
}
