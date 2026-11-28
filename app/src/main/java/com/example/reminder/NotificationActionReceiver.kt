package com.example.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.YawmiApplication
import com.example.data.local.dao.ReminderDao
import com.example.data.local.dao.ReminderDeliveryDao
import com.example.data.local.db.PendingIntentIdentitySnapshot
import com.example.data.local.entity.DeliveryState
import com.example.data.local.entity.ReminderOwnerType
import com.example.domain.repository.TaskRepository
import com.example.domain.task.TaskDomainResult
import com.example.domain.task.TaskReminderResult
import com.example.domain.task.TaskReminderRules
import com.example.domain.task.TaskReminderUseCases
import com.example.domain.task.TaskUseCases
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

sealed interface NotificationActionOutcome {
  data class TaskCompleted(val taskId: String, val reminderId: String) : NotificationActionOutcome

  data class TaskSnoozed(
    val taskId: String,
    val reminderId: String,
    val nextScheduleRevision: Long,
  ) : NotificationActionOutcome

  data class Ignored(val reason: String) : NotificationActionOutcome
}

class NotificationActionHandler(
  private val taskRepository: TaskRepository,
  private val reminderDao: ReminderDao,
  private val deliveryDao: ReminderDeliveryDao,
  private val taskUseCases: TaskUseCases,
  private val taskReminderUseCases: TaskReminderUseCases,
  private val notificationCanceller: NotificationCancellationExecutor,
) {
  suspend fun handleIntent(intent: Intent?): NotificationActionOutcome {
    if (intent == null) {
      return NotificationActionOutcome.Ignored("Null intent")
    }
    val action = intent.action
    if (
      action != NotificationPendingIntentFactory.ACTION_COMPLETE_TASK &&
      action != NotificationPendingIntentFactory.ACTION_SNOOZE_TASK
    ) {
      return NotificationActionOutcome.Ignored("Unsupported action: $action")
    }

    val taskId =
      intent.getStringExtra(NotificationPendingIntentFactory.EXTRA_TASK_ID)
        ?.takeIf { it.isNotBlank() }
        ?: return NotificationActionOutcome.Ignored("Missing taskId")
    val reminderId =
      intent.getStringExtra(PendingIntentFactory.EXTRA_REMINDER_ID)
        ?.takeIf { it.isNotBlank() }
        ?: return NotificationActionOutcome.Ignored("Missing reminderId")
    val occurrenceKey =
      intent.getStringExtra(NotificationPendingIntentFactory.EXTRA_OCCURRENCE_KEY)
        ?.takeIf { it.isNotBlank() }
    val expectedRevision =
      if (intent.hasExtra(PendingIntentFactory.EXTRA_SCHEDULE_REVISION)) {
        intent.getLongExtra(PendingIntentFactory.EXTRA_SCHEDULE_REVISION, -1L).takeIf { it > 0L }
      } else {
        null
      }
    val notificationTag =
      intent.getStringExtra(NotificationPendingIntentFactory.EXTRA_NOTIFICATION_TAG)
        ?: PendingIntentIdentitySnapshot.buildNotificationTag(ReminderOwnerType.TASK, reminderId)
    val notificationId =
      intent.getIntExtra(
        NotificationPendingIntentFactory.EXTRA_NOTIFICATION_ID,
        PendingIntentIdentitySnapshot.DEFAULT_NOTIFICATION_ID,
      )

    val task = taskRepository.getById(taskId)
    val reminder = reminderDao.getTaskReminderById(reminderId)
    if (task == null || reminder == null || reminder.taskId != task.id) {
      notificationCanceller.cancelNotification(notificationTag, notificationId)
      return NotificationActionOutcome.Ignored("Task or reminder missing/mismatched")
    }
    if (!TaskReminderRules.isTaskActive(task.status)) {
      notificationCanceller.cancelNotification(notificationTag, notificationId)
      return NotificationActionOutcome.Ignored("Task is already inactive: ${task.status}")
    }
    if (!reminder.isEnabled) {
      notificationCanceller.cancelNotification(notificationTag, notificationId)
      return NotificationActionOutcome.Ignored("Reminder is disabled")
    }
    if (expectedRevision != null && reminder.scheduleRevision != expectedRevision) {
      notificationCanceller.cancelNotification(notificationTag, notificationId)
      return NotificationActionOutcome.Ignored(
        "Reminder revision mismatch: expected=$expectedRevision actual=${reminder.scheduleRevision}"
      )
    }
    if (occurrenceKey != null) {
      val delivery = deliveryDao.getTaskDelivery(reminder.id, occurrenceKey)
      if (delivery != null && (delivery.state == DeliveryState.STALE || delivery.state == DeliveryState.EXPIRED)) {
        notificationCanceller.cancelNotification(notificationTag, notificationId)
        return NotificationActionOutcome.Ignored("Occurrence already ${delivery.state}")
      }
    }

    return when (action) {
      NotificationPendingIntentFactory.ACTION_COMPLETE_TASK -> {
        when (val result = taskUseCases.completeTask(task.id)) {
          is TaskDomainResult.Success -> {
            notificationCanceller.cancelNotification(notificationTag, notificationId)
            NotificationActionOutcome.TaskCompleted(task.id, reminder.id)
          }
          is TaskDomainResult.Failure -> {
            NotificationActionOutcome.Ignored("Complete failed: ${result.errors}")
          }
        }
      }

      NotificationPendingIntentFactory.ACTION_SNOOZE_TASK -> {
        val snoozeMinutes =
          intent.getIntExtra(
            NotificationPendingIntentFactory.EXTRA_SNOOZE_MINUTES,
            NotificationPendingIntentFactory.DEFAULT_SNOOZE_MINUTES,
          ).coerceAtLeast(1)
        when (
          val result =
            taskReminderUseCases.snoozeReminder(
              taskId = task.id,
              reminderId = reminder.id,
              snoozeMinutes = snoozeMinutes,
            )
        ) {
          is TaskReminderResult.Success -> {
            notificationCanceller.cancelNotification(notificationTag, notificationId)
            NotificationActionOutcome.TaskSnoozed(
              taskId = task.id,
              reminderId = reminder.id,
              nextScheduleRevision = result.value.scheduleRevision,
            )
          }
          is TaskReminderResult.Failure -> {
            NotificationActionOutcome.Ignored("Snooze failed: ${result.error}")
          }
        }
      }

      else -> NotificationActionOutcome.Ignored("Unsupported action: $action")
    }
  }
}

class NotificationActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    val action = intent?.action ?: return
    if (
      action != NotificationPendingIntentFactory.ACTION_COMPLETE_TASK &&
      action != NotificationPendingIntentFactory.ACTION_SNOOZE_TASK
    ) {
      return
    }
    val handler = resolveHandler(context) ?: return
    val pendingResult: PendingResult? = goAsync()
    backgroundScope.launch {
      try {
        handler.handleIntent(intent)
      } finally {
        pendingResult?.finish()
      }
    }
  }

  private fun resolveHandler(context: Context): NotificationActionHandler? {
    handlerOverrideForTesting?.let { return it }
    val app = context.applicationContext as? YawmiApplication ?: return null
    return app.resolveNotificationActionHandler()
  }

  companion object {
    internal var backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    internal var handlerOverrideForTesting: NotificationActionHandler? = null
  }
}
