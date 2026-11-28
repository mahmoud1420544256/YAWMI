package com.example.reminder

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.MainActivity
import kotlin.math.absoluteValue

class NotificationPendingIntentFactory(
  private val context: Context,
) {
  fun createOpenTaskDetailsPendingIntent(
    taskId: String,
    reminderId: String,
    occurrenceKey: String,
    scheduleRevision: Long,
  ): PendingIntent {
    val intent =
      Intent(context, MainActivity::class.java).apply {
        action = ACTION_OPEN_TASK_DETAIL
        data = Uri.parse("yawmi://notification/task/$reminderId/open")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(EXTRA_OPEN_TASK_ID, taskId)
        putExtra(PendingIntentFactory.EXTRA_REMINDER_ID, reminderId)
        putExtra(EXTRA_OCCURRENCE_KEY, occurrenceKey)
        putExtra(PendingIntentFactory.EXTRA_SCHEDULE_REVISION, scheduleRevision)
      }
    return PendingIntent.getActivity(
      context,
      requestCodeFor("open", reminderId, occurrenceKey),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  fun createCompleteTaskPendingIntent(
    taskId: String,
    reminderId: String,
    occurrenceKey: String,
    scheduleRevision: Long,
    notificationTag: String,
    notificationId: Int,
  ): PendingIntent {
    val intent =
      Intent(context, NotificationActionReceiver::class.java).apply {
        action = ACTION_COMPLETE_TASK
        data = Uri.parse("yawmi://notification/task/$reminderId/complete")
        putExtra(EXTRA_TASK_ID, taskId)
        putExtra(PendingIntentFactory.EXTRA_REMINDER_ID, reminderId)
        putExtra(EXTRA_OCCURRENCE_KEY, occurrenceKey)
        putExtra(PendingIntentFactory.EXTRA_SCHEDULE_REVISION, scheduleRevision)
        putExtra(EXTRA_NOTIFICATION_TAG, notificationTag)
        putExtra(EXTRA_NOTIFICATION_ID, notificationId)
      }
    return PendingIntent.getBroadcast(
      context,
      requestCodeFor("complete", reminderId, occurrenceKey),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  fun createSnoozeTaskPendingIntent(
    taskId: String,
    reminderId: String,
    occurrenceKey: String,
    scheduleRevision: Long,
    notificationTag: String,
    notificationId: Int,
    snoozeMinutes: Int = DEFAULT_SNOOZE_MINUTES,
  ): PendingIntent {
    val intent =
      Intent(context, NotificationActionReceiver::class.java).apply {
        action = ACTION_SNOOZE_TASK
        data = Uri.parse("yawmi://notification/task/$reminderId/snooze")
        putExtra(EXTRA_TASK_ID, taskId)
        putExtra(PendingIntentFactory.EXTRA_REMINDER_ID, reminderId)
        putExtra(EXTRA_OCCURRENCE_KEY, occurrenceKey)
        putExtra(PendingIntentFactory.EXTRA_SCHEDULE_REVISION, scheduleRevision)
        putExtra(EXTRA_NOTIFICATION_TAG, notificationTag)
        putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        putExtra(EXTRA_SNOOZE_MINUTES, snoozeMinutes)
      }
    return PendingIntent.getBroadcast(
      context,
      requestCodeFor("snooze", reminderId, occurrenceKey),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun requestCodeFor(actionKind: String, reminderId: String, occurrenceKey: String): Int {
    val hash = "$actionKind|$reminderId|$occurrenceKey".hashCode().absoluteValue
    return (hash % 1_000_000_000) + 100_000
  }

  companion object {
    const val ACTION_OPEN_TASK_DETAIL = "com.yawmi.productivity.action.OPEN_TASK_DETAIL"
    const val ACTION_COMPLETE_TASK = "com.yawmi.productivity.action.COMPLETE_TASK_FROM_NOTIFICATION"
    const val ACTION_SNOOZE_TASK = "com.yawmi.productivity.action.SNOOZE_TASK_FROM_NOTIFICATION"

    const val EXTRA_OPEN_TASK_ID = "com.yawmi.productivity.extra.OPEN_TASK_ID"
    const val EXTRA_TASK_ID = "com.yawmi.productivity.extra.TASK_ID"
    const val EXTRA_OCCURRENCE_KEY = "com.yawmi.productivity.extra.OCCURRENCE_KEY"
    const val EXTRA_NOTIFICATION_TAG = "com.yawmi.productivity.extra.NOTIFICATION_TAG"
    const val EXTRA_NOTIFICATION_ID = "com.yawmi.productivity.extra.NOTIFICATION_ID"
    const val EXTRA_SNOOZE_MINUTES = "com.yawmi.productivity.extra.SNOOZE_MINUTES"

    const val DEFAULT_SNOOZE_MINUTES = 10
  }
}
