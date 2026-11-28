package com.example.reminder

import android.content.Context
import androidx.core.os.UserManagerCompat
import androidx.room.withTransaction
import com.example.core.time.LocalClock
import com.example.core.time.SystemLocalClock
import com.example.data.local.dao.AlarmSyncOutboxDao
import com.example.data.local.dao.AppMetadataDao
import com.example.data.local.dao.ReminderDao
import com.example.data.local.dao.ReminderDeliveryDao
import com.example.data.local.dao.TaskDao
import com.example.data.local.db.PendingIntentIdentitySnapshot
import com.example.data.local.db.YawmiDatabase
import com.example.data.local.db.YawmiMetadataKeys
import com.example.data.local.entity.AppMetadataEntity
import com.example.data.local.entity.DeliveryState
import com.example.data.local.entity.ReminderOwnerType
import com.example.data.local.entity.ReminderScheduleState
import com.example.data.local.entity.TaskReminderEntity
import com.example.data.repository.ReminderOutboxCoordinator
import com.example.domain.task.ReminderTriggerCalculator
import com.example.domain.task.ScheduleTimingDecision
import com.example.domain.task.TaskReminderRules
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ReconcileTriggerReason {
  BOOT_COMPLETED,
  MY_PACKAGE_REPLACED,
  TIME_CHANGED,
  TIMEZONE_CHANGED,
  DATE_CHANGED,
  EXACT_ALARM_PERMISSION_GRANTED,
  APP_STARTUP,
  APP_RESUME_CHECK,
  MANUAL,
  ;

  fun requiresForceReschedule(): Boolean =
    when (this) {
      BOOT_COMPLETED,
      MY_PACKAGE_REPLACED,
      TIME_CHANGED,
      TIMEZONE_CHANGED,
      EXACT_ALARM_PERMISSION_GRANTED -> true

      DATE_CHANGED,
      APP_STARTUP,
      APP_RESUME_CHECK,
      MANUAL -> false
    }
}

data class ReconciliationSummary(
  val reason: ReconcileTriggerReason,
  val inspectedCount: Int,
  val stagedScheduleCount: Int,
  val deliveredWithinGraceCount: Int,
  val markedExpiredCount: Int,
  val skippedConsumedCount: Int,
  val skippedInactiveOrInvalidCount: Int,
  val skippedAlreadyPendingCount: Int,
  val skippedAlreadyMatchedCount: Int,
  val canScheduleExactAlarms: Boolean,
  val exactAlarmCapabilityChanged: Boolean,
  val skippedDueToLockedStorage: Boolean = false,
)

class ReminderReconciler(
  private val context: Context,
  private val database: YawmiDatabase,
  private val taskDao: TaskDao,
  private val reminderDao: ReminderDao,
  private val deliveryDao: ReminderDeliveryDao,
  private val outboxDao: AlarmSyncOutboxDao,
  private val appMetadataDao: AppMetadataDao,
  private val outboxCoordinator: ReminderOutboxCoordinator,
  private val alarmScheduler: AlarmScheduler,
  private val notificationDeliveryEngine: NotificationDeliveryEngine,
  private val outboxDrainTrigger: OutboxDrainTrigger,
  private val localClock: LocalClock = SystemLocalClock(),
  private val isUserUnlockedProvider: () -> Boolean = { UserManagerCompat.isUserUnlocked(context) },
) {
  private val reconcileMutex = Mutex()
  private val appPackageName: String = context.applicationContext.packageName

  suspend fun reconcile(
    reason: ReconcileTriggerReason = ReconcileTriggerReason.MANUAL,
    forceRescheduleOverride: Boolean = false,
  ): ReconciliationSummary =
    reconcileMutex.withLock {
      if (!isUserUnlockedProvider()) {
        return@withLock ReconciliationSummary(
          reason = reason,
          inspectedCount = 0,
          stagedScheduleCount = 0,
          deliveredWithinGraceCount = 0,
          markedExpiredCount = 0,
          skippedConsumedCount = 0,
          skippedInactiveOrInvalidCount = 0,
          skippedAlreadyPendingCount = 0,
          skippedAlreadyMatchedCount = 0,
          canScheduleExactAlarms = false,
          exactAlarmCapabilityChanged = false,
          skippedDueToLockedStorage = true,
        )
      }

      notificationDeliveryEngine.recoverPendingDeliveries()

      val now = localClock.currentEpochMs()
      val zoneId = localClock.zoneId()
      val canScheduleExact = alarmScheduler.canScheduleExactAlarms()
      val enabledReminders = reminderDao.getAllEnabledTaskReminders()

      val graceToDeliver = mutableListOf<TaskReminderEntity>()
      val expiredToRecord = mutableListOf<TaskReminderEntity>()

      var stagedCount = 0
      var skippedConsumed = 0
      var skippedInactiveOrInvalid = 0
      var skippedAlreadyPending = 0
      var skippedAlreadyMatched = 0
      var exactCapabilityChanged = false

      database.withTransaction {
        val requestedForceSeq =
          appMetadataDao.getValue(YawmiMetadataKeys.RECONCILE_FORCE_REQUEST_SEQ)?.toLongOrNull() ?: 0L
        val handledForceSeq =
          appMetadataDao.getValue(YawmiMetadataKeys.RECONCILE_FORCE_HANDLED_SEQ)?.toLongOrNull() ?: 0L
        val forceReschedule =
          forceRescheduleOverride ||
            reason.requiresForceReschedule() ||
            (requestedForceSeq > handledForceSeq)

        val previousCanExact =
          appMetadataDao.getValue(YawmiMetadataKeys.LAST_EXACT_ALARM_CAPABILITY)?.toBooleanStrictOrNull()
        exactCapabilityChanged = previousCanExact != null && previousCanExact != canScheduleExact

        val previousZoneId = appMetadataDao.getValue(YawmiMetadataKeys.LAST_RECONCILED_ZONE_ID)
        val zoneChanged = previousZoneId != null && previousZoneId != zoneId.id

        for (reminder in enabledReminders) {
          if (reminder.scheduleState == ReminderScheduleState.CONSUMED) {
            skippedConsumed++
            continue
          }
          if (!isActiveScheduleState(reminder.scheduleState)) {
            skippedInactiveOrInvalid++
            continue
          }

          val task = taskDao.getById(reminder.taskId)
          if (task == null || !TaskReminderRules.isTaskActive(task.status)) {
            skippedInactiveOrInvalid++
            continue
          }

          if (deliveryDao.hasPostedTaskDeliveryForRevision(reminder.id, reminder.scheduleRevision)) {
            skippedConsumed++
            continue
          }

          val evaluation =
            ReminderTriggerCalculator.evaluateForSchedule(
              triggerDate = reminder.triggerDate,
              triggerTime = reminder.triggerTime,
              scheduleRevision = reminder.scheduleRevision,
              nowEpochMs = now,
              zoneId = zoneId,
            )

          when (evaluation.decision) {
            ScheduleTimingDecision.INVALID -> {
              skippedInactiveOrInvalid++
            }

            ScheduleTimingDecision.FIRE_IMMEDIATELY_WITHIN_GRACE -> {
              graceToDeliver += reminder
            }

            ScheduleTimingDecision.EXPIRED_OUTSIDE_GRACE -> {
              expiredToRecord += reminder
            }

            ScheduleTimingDecision.SCHEDULE_AT_TRIGGER -> {
              val capabilityMismatch =
                (reminder.scheduleState == ReminderScheduleState.SCHEDULED_EXACT && !canScheduleExact) ||
                  (reminder.scheduleState == ReminderScheduleState.SCHEDULED_INEXACT && canScheduleExact)
              val needsStage =
                forceReschedule ||
                  zoneChanged ||
                  capabilityMismatch ||
                  reminder.scheduleState == ReminderScheduleState.PENDING_SYNC

              if (!needsStage) {
                skippedAlreadyMatched++
                continue
              }

              val identity =
                PendingIntentIdentitySnapshot.forReminder(
                  ownerType = ReminderOwnerType.TASK,
                  reminderId = reminder.id,
                  requestCode = reminder.requestCode,
                  packageName = appPackageName,
                )
              val alreadyQueued =
                if (forceReschedule || zoneChanged) {
                  outboxDao.hasPendingScheduleAlarmForRevision(
                    pendingIntentIdentityKey = identity.pendingIntentIdentityKey,
                    targetScheduleRevision = reminder.scheduleRevision,
                  )
                } else {
                  outboxDao.hasActiveScheduleAlarmForRevision(
                    pendingIntentIdentityKey = identity.pendingIntentIdentityKey,
                    targetScheduleRevision = reminder.scheduleRevision,
                  )
                }
              if (alreadyQueued) {
                skippedAlreadyPending++
                continue
              }

              if (reminder.scheduleState != ReminderScheduleState.PENDING_SYNC) {
                reminderDao.updateTaskReminderScheduleStateIfRevision(
                  id = reminder.id,
                  expectedRevision = reminder.scheduleRevision,
                  scheduleState = ReminderScheduleState.PENDING_SYNC,
                  updatedAtEpochMs = now,
                )
              }
              outboxCoordinator.stageScheduleAlarmLocked(
                ownerType = ReminderOwnerType.TASK,
                reminderId = reminder.id,
                requestCode = reminder.requestCode,
                targetScheduleRevision = reminder.scheduleRevision,
                nowEpochMs = now,
              )
              stagedCount++
            }
          }
        }

        appMetadataDao.put(
          AppMetadataEntity(
            key = YawmiMetadataKeys.LAST_EXACT_ALARM_CAPABILITY,
            value = canScheduleExact.toString(),
          )
        )
        appMetadataDao.put(
          AppMetadataEntity(
            key = YawmiMetadataKeys.LAST_RECONCILED_ZONE_ID,
            value = zoneId.id,
          )
        )
        if (requestedForceSeq > handledForceSeq) {
          appMetadataDao.put(
            AppMetadataEntity(
              key = YawmiMetadataKeys.RECONCILE_FORCE_HANDLED_SEQ,
              value = requestedForceSeq.toString(),
            )
          )
        }
      }

      var deliveredCount = 0
      for (reminder in graceToDeliver) {
        when (
          notificationDeliveryEngine.deliverTaskReminder(
            reminderId = reminder.id,
            expectedRevision = reminder.scheduleRevision,
          )
        ) {
          is DeliveryOutcome.PostedToSystem,
          is DeliveryOutcome.RecoveredFromActiveNotification -> deliveredCount++
          else -> Unit
        }
      }

      var expiredCount = 0
      for (reminder in expiredToRecord) {
        when (
          notificationDeliveryEngine.deliverTaskReminder(
            reminderId = reminder.id,
            expectedRevision = reminder.scheduleRevision,
          )
        ) {
          is DeliveryOutcome.Expired -> expiredCount++
          else -> Unit
        }
      }

      if (stagedCount > 0 || outboxDao.hasActiveQueue()) {
        outboxDrainTrigger.requestDrainNow()
      }

      ReconciliationSummary(
        reason = reason,
        inspectedCount = enabledReminders.size,
        stagedScheduleCount = stagedCount,
        deliveredWithinGraceCount = deliveredCount,
        markedExpiredCount = expiredCount,
        skippedConsumedCount = skippedConsumed,
        skippedInactiveOrInvalidCount = skippedInactiveOrInvalid,
        skippedAlreadyPendingCount = skippedAlreadyPending,
        skippedAlreadyMatchedCount = skippedAlreadyMatched,
        canScheduleExactAlarms = canScheduleExact,
        exactAlarmCapabilityChanged = exactCapabilityChanged,
      )
    }

  suspend fun isReconciliationNeededOnResume(): Boolean {
    if (!isUserUnlockedProvider()) return false

    val canExact = alarmScheduler.canScheduleExactAlarms()
    val lastCanExact =
      appMetadataDao.getValue(YawmiMetadataKeys.LAST_EXACT_ALARM_CAPABILITY)?.toBooleanStrictOrNull()
    if (lastCanExact != null && lastCanExact != canExact) {
      return true
    }

    val currentZone = localClock.zoneId().id
    val lastZone = appMetadataDao.getValue(YawmiMetadataKeys.LAST_RECONCILED_ZONE_ID)
    if (lastZone != null && lastZone != currentZone) {
      return true
    }

    val requestedForceSeq =
      appMetadataDao.getValue(YawmiMetadataKeys.RECONCILE_FORCE_REQUEST_SEQ)?.toLongOrNull() ?: 0L
    val handledForceSeq =
      appMetadataDao.getValue(YawmiMetadataKeys.RECONCILE_FORCE_HANDLED_SEQ)?.toLongOrNull() ?: 0L
    if (requestedForceSeq > handledForceSeq) {
      return true
    }

    if (deliveryDao.getRecoverableTaskDeliveries().isNotEmpty()) {
      return true
    }

    val now = localClock.currentEpochMs()
    val zoneId = localClock.zoneId()
    val enabledReminders = reminderDao.getAllEnabledTaskReminders()
    for (reminder in enabledReminders) {
      if (!isActiveScheduleState(reminder.scheduleState)) continue
      val task = taskDao.getById(reminder.taskId) ?: continue
      if (!TaskReminderRules.isTaskActive(task.status)) continue
      if (deliveryDao.hasPostedTaskDeliveryForRevision(reminder.id, reminder.scheduleRevision)) continue

      val capabilityMismatch =
        (reminder.scheduleState == ReminderScheduleState.SCHEDULED_EXACT && !canExact) ||
          (reminder.scheduleState == ReminderScheduleState.SCHEDULED_INEXACT && canExact)
      if (reminder.scheduleState == ReminderScheduleState.PENDING_SYNC || capabilityMismatch) {
        return true
      }

      val evaluation =
        ReminderTriggerCalculator.evaluateForSchedule(
          triggerDate = reminder.triggerDate,
          triggerTime = reminder.triggerTime,
          scheduleRevision = reminder.scheduleRevision,
          nowEpochMs = now,
          zoneId = zoneId,
        )
      if (
        evaluation.decision == ScheduleTimingDecision.FIRE_IMMEDIATELY_WITHIN_GRACE ||
          evaluation.decision == ScheduleTimingDecision.EXPIRED_OUTSIDE_GRACE
      ) {
        val occurrenceKey = evaluation.occurrenceKey ?: continue
        val existingDelivery = deliveryDao.getTaskDelivery(reminder.id, occurrenceKey)
        if (
          existingDelivery == null ||
            existingDelivery.state == DeliveryState.PENDING ||
            existingDelivery.state == DeliveryState.FAILED_RETRYABLE
        ) {
          return true
        }
      }
    }

    return false
  }

  private fun isActiveScheduleState(state: ReminderScheduleState): Boolean =
    state == ReminderScheduleState.PENDING_SYNC ||
      state == ReminderScheduleState.SCHEDULED_EXACT ||
      state == ReminderScheduleState.SCHEDULED_INEXACT
}
