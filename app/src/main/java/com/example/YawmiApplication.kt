package com.example

import android.app.Application
import androidx.work.Configuration
import com.example.core.di.AppContainer
import com.example.core.di.DefaultAppContainer
import com.example.reminder.AlarmFireHandler
import com.example.reminder.NotificationActionHandler
import com.example.reminder.ReconcileTriggerReason
import com.example.reminder.ReminderReconciler
import com.example.reminder.SystemReconciliationTrigger
import com.example.reminder.YawmiWorkerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class YawmiApplication : Application(), Configuration.Provider {
  lateinit var container: AppContainer
    private set

  private lateinit var defaultContainer: DefaultAppContainer
  private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onCreate() {
    super.onCreate()
    defaultContainer = DefaultAppContainer(this)
    container = defaultContainer
    startupScope.launch {
      val language = defaultContainer.appearanceRepository.appearanceState.first().language
      defaultContainer.notificationChannelManager.ensureChannelsCreated(language)
      defaultContainer.systemReconciliationTrigger.requestReconcileNow(
        ReconcileTriggerReason.APP_STARTUP
      )
    }
  }

  internal fun resolveAlarmFireHandler(): AlarmFireHandler = defaultContainer.alarmFireHandler

  internal fun resolveNotificationActionHandler(): NotificationActionHandler =
    defaultContainer.notificationActionHandler

  internal fun resolveSystemReconciliationTrigger(): SystemReconciliationTrigger =
    defaultContainer.systemReconciliationTrigger

  internal fun resolveReminderReconciler(): ReminderReconciler = defaultContainer.reminderReconciler

  internal fun onActivityResumed() {
    defaultContainer.systemReconciliationTrigger.requestReconcileIfNeededOnResumeAsync()
  }

  override val workManagerConfiguration: Configuration
    get() =
      Configuration.Builder()
        .setWorkerFactory(
          YawmiWorkerFactory(
            dispatcherProvider = { defaultContainer.alarmOutboxDispatcher },
            drainTriggerProvider = { defaultContainer.outboxDrainTrigger },
            hasActiveQueueProvider = { defaultContainer.database.alarmSyncOutboxDao().hasActiveQueue() },
            reconcilerProvider = { defaultContainer.reminderReconciler },
          )
        )
        .build()
}
