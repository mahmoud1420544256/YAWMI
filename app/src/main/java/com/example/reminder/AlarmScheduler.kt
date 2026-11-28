package com.example.reminder

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.example.data.local.entity.AlarmSyncOutboxEntity

sealed interface AlarmScheduleOutcome {
  data class ScheduledExact(val triggerEpochMs: Long) : AlarmScheduleOutcome

  data class ScheduledInexact(val triggerEpochMs: Long, val reason: String) : AlarmScheduleOutcome

  data class FailedRetryable(val reason: String, val cause: Throwable? = null) :
    AlarmScheduleOutcome

  data class FailedPermanent(val reason: String) : AlarmScheduleOutcome
}

sealed interface AlarmCancelOutcome {
  data object Cancelled : AlarmCancelOutcome

  data class FailedRetryable(val reason: String, val cause: Throwable? = null) :
    AlarmCancelOutcome
}

sealed interface NotificationCancelOutcome {
  data object Cancelled : NotificationCancelOutcome

  data class FailedRetryable(val reason: String, val cause: Throwable? = null) :
    NotificationCancelOutcome
}

interface AlarmScheduler {
  fun canScheduleExactAlarms(): Boolean

  fun scheduleAlarm(entry: AlarmSyncOutboxEntity, triggerAtEpochMs: Long): AlarmScheduleOutcome

  fun cancelAlarm(entry: AlarmSyncOutboxEntity): AlarmCancelOutcome
}

interface NotificationCancellationExecutor {
  fun cancelNotification(tag: String?, notificationId: Int): NotificationCancelOutcome
}

class AndroidAlarmManagerScheduler(
  private val context: Context,
  private val pendingIntentFactory: PendingIntentFactory = PendingIntentFactory(context),
) : AlarmScheduler {

  private val alarmManager: AlarmManager?
    get() = context.getSystemService(AlarmManager::class.java)

  override fun canScheduleExactAlarms(): Boolean {
    val manager = alarmManager ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      manager.canScheduleExactAlarms()
    } else {
      true
    }
  }

  override fun scheduleAlarm(
    entry: AlarmSyncOutboxEntity,
    triggerAtEpochMs: Long,
  ): AlarmScheduleOutcome {
    val manager =
      alarmManager ?: return AlarmScheduleOutcome.FailedRetryable("AlarmManager unavailable")
    val pendingIntent =
      try {
        pendingIntentFactory.createOrUpdateAlarmPendingIntent(entry)
      } catch (t: Throwable) {
        return AlarmScheduleOutcome.FailedRetryable(
          reason = t.message ?: "Failed to create PendingIntent",
          cause = t,
        )
      }

    return if (canScheduleExactAlarms()) {
      try {
        manager.setExactAndAllowWhileIdle(
          AlarmManager.RTC_WAKEUP,
          triggerAtEpochMs,
          pendingIntent,
        )
        AlarmScheduleOutcome.ScheduledExact(triggerAtEpochMs)
      } catch (sec: SecurityException) {
        try {
          manager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtEpochMs,
            pendingIntent,
          )
          AlarmScheduleOutcome.ScheduledInexact(
            triggerEpochMs = triggerAtEpochMs,
            reason = sec.message ?: "Exact alarm permission revoked; fell back to inexact",
          )
        } catch (t: Throwable) {
          AlarmScheduleOutcome.FailedRetryable(
            reason = t.message ?: "Inexact fallback scheduling failed",
            cause = t,
          )
        }
      } catch (t: Throwable) {
        AlarmScheduleOutcome.FailedRetryable(
          reason = t.message ?: "Exact alarm scheduling failed",
          cause = t,
        )
      }
    } else {
      try {
        manager.setAndAllowWhileIdle(
          AlarmManager.RTC_WAKEUP,
          triggerAtEpochMs,
          pendingIntent,
        )
        AlarmScheduleOutcome.ScheduledInexact(
          triggerEpochMs = triggerAtEpochMs,
          reason = "SCHEDULE_EXACT_ALARM not granted; used inexact alarm",
        )
      } catch (t: Throwable) {
        AlarmScheduleOutcome.FailedRetryable(
          reason = t.message ?: "Inexact alarm scheduling failed",
          cause = t,
        )
      }
    }
  }

  override fun cancelAlarm(entry: AlarmSyncOutboxEntity): AlarmCancelOutcome {
    val manager =
      alarmManager ?: return AlarmCancelOutcome.FailedRetryable("AlarmManager unavailable")
    return try {
      val existing = pendingIntentFactory.findExistingAlarmPendingIntent(entry)
      if (existing != null) {
        manager.cancel(existing)
        existing.cancel()
      }
      AlarmCancelOutcome.Cancelled
    } catch (t: Throwable) {
      AlarmCancelOutcome.FailedRetryable(
        reason = t.message ?: "Failed to cancel PendingIntent alarm",
        cause = t,
      )
    }
  }
}

class SystemNotificationCanceller(
  private val context: Context,
) : NotificationCancellationExecutor {
  override fun cancelNotification(tag: String?, notificationId: Int): NotificationCancelOutcome {
    return try {
      NotificationManagerCompat.from(context).cancel(tag, notificationId)
      NotificationCancelOutcome.Cancelled
    } catch (t: Throwable) {
      NotificationCancelOutcome.FailedRetryable(
        reason = t.message ?: "Failed to cancel system notification",
        cause = t,
      )
    }
  }
}
