package com.example.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.YawmiApplication
import com.example.core.time.LocalClock
import com.example.data.local.db.PendingIntentIdentitySnapshot
import com.example.data.local.entity.ReminderOwnerType
import com.example.domain.repository.ReminderSyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

sealed interface AlarmFireOutcome {
  data class Delivered(val outcome: DeliveryOutcome) : AlarmFireOutcome

  data class RescheduledOnPrematureClockRollback(
    val reminderId: String,
    val updatedRevision: Long,
  ) : AlarmFireOutcome

  data class Ignored(val reason: String) : AlarmFireOutcome
}

class AlarmFireHandler(
  private val deliveryEngine: NotificationDeliveryEngine,
  private val reminderSyncRepository: ReminderSyncRepository,
  private val localClock: LocalClock,
) {
  suspend fun handleIntent(intent: Intent?): AlarmFireOutcome {
    if (intent == null) {
      return AlarmFireOutcome.Ignored("Null intent")
    }
    if (intent.action != PendingIntentIdentitySnapshot.ACTION_FIRE_REMINDER) {
      return AlarmFireOutcome.Ignored("Unexpected action: ${intent.action}")
    }

    val uriSegments = intent.data?.pathSegments.orEmpty()
    val uriOwnerType = uriSegments.getOrNull(0)?.uppercase()
    val uriReminderId = uriSegments.getOrNull(1)

    val extraOwnerType = intent.getStringExtra(PendingIntentFactory.EXTRA_OWNER_TYPE)?.uppercase()
    val effectiveOwner = extraOwnerType ?: uriOwnerType
    if (effectiveOwner != null && effectiveOwner != ReminderOwnerType.TASK.name) {
      return AlarmFireOutcome.Ignored("Out-of-scope ownerType for Unit 4A.2B: $effectiveOwner")
    }

    val reminderId =
      intent.getStringExtra(PendingIntentFactory.EXTRA_REMINDER_ID)?.takeIf { it.isNotBlank() }
        ?: uriReminderId?.takeIf { it.isNotBlank() }
        ?: return AlarmFireOutcome.Ignored("Missing reminderId lookup key")

    // Schedule revision extra is used strictly as a staleness fence; Room remains source of truth.
    val expectedRevision =
      if (intent.hasExtra(PendingIntentFactory.EXTRA_SCHEDULE_REVISION)) {
        intent.getLongExtra(PendingIntentFactory.EXTRA_SCHEDULE_REVISION, -1L).takeIf { it > 0L }
      } else {
        null
      }

    return when (
      val outcome =
        deliveryEngine.deliverTaskReminder(
          reminderId = reminderId,
          expectedRevision = expectedRevision,
        )
    ) {
      is DeliveryOutcome.PrematureClockRollback -> {
        val rem = outcome.reminder
        val updated =
          reminderSyncRepository.updateTaskReminderSchedule(
            reminderId = rem.id,
            triggerDate = rem.triggerDate,
            triggerTime = rem.triggerTime,
            offsetMinutes = rem.offsetMinutes,
            isEnabled = true,
            nowEpochMs = localClock.currentEpochMs(),
          )
        AlarmFireOutcome.RescheduledOnPrematureClockRollback(
          reminderId = updated.id,
          updatedRevision = updated.scheduleRevision,
        )
      }

      else -> AlarmFireOutcome.Delivered(outcome)
    }
  }
}

class AlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    if (intent?.action != PendingIntentIdentitySnapshot.ACTION_FIRE_REMINDER) {
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

  private fun resolveHandler(context: Context): AlarmFireHandler? {
    handlerOverrideForTesting?.let { return it }
    val app = context.applicationContext as? YawmiApplication ?: return null
    return app.resolveAlarmFireHandler()
  }

  companion object {
    internal var backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    internal var handlerOverrideForTesting: AlarmFireHandler? = null
  }
}
