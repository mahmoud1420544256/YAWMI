package com.example.reminder

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.room.withTransaction
import com.example.R
import com.example.core.i18n.createLocalizedContext
import com.example.core.preferences.AppLanguage
import com.example.core.time.LocalClock
import com.example.core.time.SystemLocalClock
import com.example.data.local.dao.ReminderDao
import com.example.data.local.dao.ReminderDeliveryDao
import com.example.data.local.dao.TaskDao
import com.example.data.local.db.PendingIntentIdentitySnapshot
import com.example.data.local.db.YawmiDatabase
import com.example.data.local.entity.DeliveryState
import com.example.data.local.entity.ReminderOwnerType
import com.example.data.local.entity.ReminderScheduleState
import com.example.data.local.entity.TaskEntity
import com.example.data.local.entity.TaskReminderDeliveryEntity
import com.example.data.local.entity.TaskReminderEntity
import com.example.domain.task.ReminderTriggerCalculator
import com.example.domain.task.TaskReminderRules
import com.example.domain.task.TriggerEvaluationStatus
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface NotificationPostOutcome {
  data object Posted : NotificationPostOutcome

  data class PermissionDenied(val reason: String) : NotificationPostOutcome

  data class FailedRetryable(val reason: String) : NotificationPostOutcome
}

interface NotificationPoster {
  fun postNotification(tag: String, id: Int, notification: Notification): NotificationPostOutcome

  fun isNotificationActive(tag: String, id: Int): Boolean
}

class AndroidNotificationPoster(
  private val context: Context,
) : NotificationPoster {
  override fun postNotification(
    tag: String,
    id: Int,
    notification: Notification,
  ): NotificationPostOutcome {
    return try {
      NotificationManagerCompat.from(context).notify(tag, id, notification)
      NotificationPostOutcome.Posted
    } catch (sec: SecurityException) {
      NotificationPostOutcome.PermissionDenied(sec.message ?: "POST_NOTIFICATIONS denied")
    } catch (t: Throwable) {
      NotificationPostOutcome.FailedRetryable(t.message ?: "Failed to post notification")
    }
  }

  override fun isNotificationActive(tag: String, id: Int): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    return runCatching {
      manager.activeNotifications.any { sbn -> sbn.tag == tag && sbn.id == id }
    }.getOrDefault(false)
  }
}

internal interface NotificationDeliveryHooks {
  suspend fun onBeforeNotify(delivery: TaskReminderDeliveryEntity) {}

  suspend fun onAfterNotifyBeforeCommit(delivery: TaskReminderDeliveryEntity) {}

  object None : NotificationDeliveryHooks
}

sealed interface DeliveryOutcome {
  data class PostedToSystem(val deliveryId: String, val occurrenceKey: String) : DeliveryOutcome

  data class RecoveredFromActiveNotification(val deliveryId: String, val occurrenceKey: String) :
    DeliveryOutcome

  data class AlreadyHandled(val state: DeliveryState, val occurrenceKey: String) : DeliveryOutcome

  data class SuppressedPermission(val reason: String, val occurrenceKey: String) : DeliveryOutcome

  data class SuppressedChannel(val reason: String, val occurrenceKey: String) : DeliveryOutcome

  data class Expired(val occurrenceKey: String) : DeliveryOutcome

  data class PrematureClockRollback(val reminder: TaskReminderEntity) : DeliveryOutcome

  data class FailedRetryable(val reason: String, val occurrenceKey: String) : DeliveryOutcome

  data class Skipped(val reason: String) : DeliveryOutcome
}

data class CrashRecoverySummary(
  val inspectedCount: Int,
  val recoveredFromSystemCount: Int,
  val repostedCount: Int,
  val markedStaleCount: Int,
  val markedExpiredCount: Int,
  val suppressedCount: Int,
  val failedRetryableCount: Int,
)

class NotificationDeliveryEngine(
  private val context: Context,
  private val database: YawmiDatabase,
  private val taskDao: TaskDao,
  private val reminderDao: ReminderDao,
  private val deliveryDao: ReminderDeliveryDao,
  private val channelManager: NotificationChannelManager,
  private val notificationPoster: NotificationPoster = AndroidNotificationPoster(context),
  private val notificationCanceller: NotificationCancellationExecutor = SystemNotificationCanceller(context),
  private val pendingIntentFactory: NotificationPendingIntentFactory = NotificationPendingIntentFactory(context),
  private val localClock: LocalClock = SystemLocalClock(),
  private val languageProvider: suspend () -> AppLanguage? = { null },
) {
  private val deliveryMutex = Mutex()

  suspend fun deliverTaskReminder(
    reminderId: String,
    expectedRevision: Long? = null,
    isRecoveryAttempt: Boolean = false,
  ): DeliveryOutcome =
    deliverTaskReminderInternal(
      reminderId = reminderId,
      expectedRevision = expectedRevision,
      isRecoveryAttempt = isRecoveryAttempt,
      hooks = NotificationDeliveryHooks.None,
    )

  internal suspend fun deliverTaskReminderWithHooks(
    reminderId: String,
    expectedRevision: Long? = null,
    isRecoveryAttempt: Boolean = false,
    hooks: NotificationDeliveryHooks,
  ): DeliveryOutcome =
    deliverTaskReminderInternal(
      reminderId = reminderId,
      expectedRevision = expectedRevision,
      isRecoveryAttempt = isRecoveryAttempt,
      hooks = hooks,
    )

  private suspend fun deliverTaskReminderInternal(
    reminderId: String,
    expectedRevision: Long?,
    isRecoveryAttempt: Boolean,
    hooks: NotificationDeliveryHooks,
  ): DeliveryOutcome =
    deliveryMutex.withLock {
      val initialReminder =
        reminderDao.getTaskReminderById(reminderId)
          ?: return@withLock DeliveryOutcome.Skipped("Task reminder not found: $reminderId")
      if (!initialReminder.isEnabled) {
        return@withLock DeliveryOutcome.Skipped("Task reminder disabled: $reminderId")
      }
      if (!isReminderStateEligibleForFire(initialReminder.scheduleState)) {
        return@withLock DeliveryOutcome.Skipped(
          "Task reminder scheduleState=${initialReminder.scheduleState} cannot fire"
        )
      }
      if (expectedRevision != null && initialReminder.scheduleRevision != expectedRevision) {
        return@withLock DeliveryOutcome.Skipped(
          "Schedule revision mismatch: expected=$expectedRevision actual=${initialReminder.scheduleRevision}"
        )
      }

      val initialTask =
        taskDao.getById(initialReminder.taskId)
          ?: return@withLock DeliveryOutcome.Skipped("Task not found: ${initialReminder.taskId}")
      if (!TaskReminderRules.isTaskActive(initialTask.status)) {
        return@withLock DeliveryOutcome.Skipped("Task is not active: ${initialTask.status}")
      }

      val now = localClock.currentEpochMs()
      val zoneId = localClock.zoneId()
      val evaluation =
        ReminderTriggerCalculator.evaluateAtFire(
          triggerDate = initialReminder.triggerDate,
          triggerTime = initialReminder.triggerTime,
          scheduleRevision = initialReminder.scheduleRevision,
          nowEpochMs = now,
          zoneId = zoneId,
        )

      return@withLock when (evaluation.status) {
        TriggerEvaluationStatus.PREMATURE_CLOCK_ROLLBACK -> {
          DeliveryOutcome.PrematureClockRollback(initialReminder)
        }

        TriggerEvaluationStatus.INVALID -> {
          DeliveryOutcome.Skipped("Invalid trigger date/time for reminder $reminderId")
        }

        TriggerEvaluationStatus.EXPIRED -> {
          val occurrenceKey = requireNotNull(evaluation.occurrenceKey)
          val triggerEpochMs = requireNotNull(evaluation.triggerEpochMs)
          recordTerminalDeliveryStateLocked(
            reminder = initialReminder,
            occurrenceKey = occurrenceKey,
            scheduledAtEpochMs = triggerEpochMs,
            state = DeliveryState.EXPIRED,
            reason = "Reminder expired outside grace window",
            nowEpochMs = now,
          )
          DeliveryOutcome.Expired(occurrenceKey)
        }

        TriggerEvaluationStatus.ON_TIME_OR_FUTURE,
        TriggerEvaluationStatus.FIRE_WITHIN_GRACE -> {
          executeValidatedDeliveryLocked(
            verifiedRevision = initialReminder.scheduleRevision,
            reminderId = initialReminder.id,
            occurrenceKey = requireNotNull(evaluation.occurrenceKey),
            scheduledAtEpochMs = requireNotNull(evaluation.triggerEpochMs),
            isRecoveryAttempt = isRecoveryAttempt,
            hooks = hooks,
          )
        }
      }
    }

  suspend fun recoverPendingDeliveries(): CrashRecoverySummary =
    recoverPendingDeliveriesInternal(NotificationDeliveryHooks.None)

  internal suspend fun recoverPendingDeliveriesWithHooks(
    hooks: NotificationDeliveryHooks,
  ): CrashRecoverySummary = recoverPendingDeliveriesInternal(hooks)

  private suspend fun recoverPendingDeliveriesInternal(
    hooks: NotificationDeliveryHooks,
  ): CrashRecoverySummary =
    deliveryMutex.withLock {
      val candidates = deliveryDao.getRecoverableTaskDeliveries()
      var recoveredFromSystem = 0
      var reposted = 0
      var markedStale = 0
      var markedExpired = 0
      var suppressed = 0
      var failedRetryable = 0

      for (candidate in candidates) {
        val now = localClock.currentEpochMs()
        val reminder = reminderDao.getTaskReminderById(candidate.taskReminderId)
        val task = reminder?.let { taskDao.getById(it.taskId) }
        val isActiveNotification =
          notificationPoster.isNotificationActive(candidate.notificationTag, candidate.notificationId)

        val validForRecovery =
          reminder != null &&
            reminder.isEnabled &&
            isReminderStateEligibleForFire(reminder.scheduleState) &&
            reminder.scheduleRevision == candidate.firedScheduleRevision &&
            task != null &&
            TaskReminderRules.isTaskActive(task.status)

        if (!validForRecovery) {
          if (isActiveNotification) {
            notificationCanceller.cancelNotification(candidate.notificationTag, candidate.notificationId)
          }
          val changed =
            deliveryDao.updateTaskDeliveryStateIfCurrent(
              id = candidate.id,
              expectedState = candidate.state,
              newState = DeliveryState.STALE,
              lastError = "Reminder/task no longer active or revision changed during recovery",
              updatedAtEpochMs = now,
            )
          if (changed > 0) markedStale++
          continue
        }

        val activeReminder = reminder
        val activeTask = task

        if (isActiveNotification) {
          val updated =
            database.withTransaction {
              val current = deliveryDao.getTaskDelivery(candidate.taskReminderId, candidate.occurrenceKey)
              if (current != null && (current.state == DeliveryState.PENDING || current.state == DeliveryState.FAILED_RETRYABLE)) {
                deliveryDao.updateTaskDeliveryAttempt(
                  id = current.id,
                  state = DeliveryState.POSTED_TO_SYSTEM,
                  firedScheduleRevision = activeReminder.scheduleRevision,
                  attemptCount = maxOf(current.attemptCount, 1),
                  lastAttemptAtEpochMs = current.lastAttemptAtEpochMs ?: now,
                  lastError = null,
                  updatedAtEpochMs = now,
                )
              } else {
                0
              }
            }
          if (updated > 0) recoveredFromSystem++
          continue
        }

        val evaluation =
          ReminderTriggerCalculator.evaluateAtFire(
            triggerDate = activeReminder.triggerDate,
            triggerTime = activeReminder.triggerTime,
            scheduleRevision = activeReminder.scheduleRevision,
            nowEpochMs = now,
            zoneId = localClock.zoneId(),
          )

        when (evaluation.status) {
          TriggerEvaluationStatus.EXPIRED -> {
            val updated =
              deliveryDao.updateTaskDeliveryStateIfCurrent(
                id = candidate.id,
                expectedState = candidate.state,
                newState = DeliveryState.EXPIRED,
                lastError = "Reminder expired before crash recovery",
                updatedAtEpochMs = now,
              )
            if (updated > 0) markedExpired++
          }

          TriggerEvaluationStatus.PREMATURE_CLOCK_ROLLBACK,
          TriggerEvaluationStatus.INVALID -> {
            val updated =
              deliveryDao.updateTaskDeliveryStateIfCurrent(
                id = candidate.id,
                expectedState = candidate.state,
                newState = DeliveryState.STALE,
                lastError = "Trigger status=${evaluation.status} invalid during recovery",
                updatedAtEpochMs = now,
              )
            if (updated > 0) markedStale++
          }

          TriggerEvaluationStatus.ON_TIME_OR_FUTURE,
          TriggerEvaluationStatus.FIRE_WITHIN_GRACE -> {
            val outcome =
              executeValidatedDeliveryLocked(
                verifiedRevision = activeReminder.scheduleRevision,
                reminderId = activeReminder.id,
                occurrenceKey = candidate.occurrenceKey,
                scheduledAtEpochMs = evaluation.triggerEpochMs ?: candidate.scheduledAtEpochMs,
                isRecoveryAttempt = true,
                hooks = hooks,
                fallbackTask = activeTask,
              )
            when (outcome) {
              is DeliveryOutcome.PostedToSystem -> reposted++
              is DeliveryOutcome.RecoveredFromActiveNotification -> recoveredFromSystem++
              is DeliveryOutcome.SuppressedPermission,
              is DeliveryOutcome.SuppressedChannel -> suppressed++
              is DeliveryOutcome.FailedRetryable -> failedRetryable++
              is DeliveryOutcome.Expired -> markedExpired++
              is DeliveryOutcome.Skipped -> markedStale++
              is DeliveryOutcome.AlreadyHandled,
              is DeliveryOutcome.PrematureClockRollback -> Unit
            }
          }
        }
      }

      CrashRecoverySummary(
        inspectedCount = candidates.size,
        recoveredFromSystemCount = recoveredFromSystem,
        repostedCount = reposted,
        markedStaleCount = markedStale,
        markedExpiredCount = markedExpired,
        suppressedCount = suppressed,
        failedRetryableCount = failedRetryable,
      )
    }

  private sealed interface ClaimDeliveryResult {
    data class ReadyToPost(
      val delivery: TaskReminderDeliveryEntity,
      val task: TaskEntity,
      val reminder: TaskReminderEntity,
      val onlyAlertOnce: Boolean,
    ) : ClaimDeliveryResult

    data class AlreadyHandled(val state: DeliveryState) : ClaimDeliveryResult

    data class RecoveredFromActiveNotification(val deliveryId: String) : ClaimDeliveryResult

    data class Skipped(val reason: String) : ClaimDeliveryResult
  }

  private suspend fun executeValidatedDeliveryLocked(
    verifiedRevision: Long,
    reminderId: String,
    occurrenceKey: String,
    scheduledAtEpochMs: Long,
    isRecoveryAttempt: Boolean,
    hooks: NotificationDeliveryHooks,
    fallbackTask: TaskEntity? = null,
  ): DeliveryOutcome {
    val claimResult =
      claimOrRecoverDeliveryTx(
        reminderId = reminderId,
        expectedRevision = verifiedRevision,
        occurrenceKey = occurrenceKey,
        scheduledAtEpochMs = scheduledAtEpochMs,
        isRecoveryAttempt = isRecoveryAttempt,
      )

    val ready =
      when (claimResult) {
        is ClaimDeliveryResult.AlreadyHandled ->
          return DeliveryOutcome.AlreadyHandled(claimResult.state, occurrenceKey)
        is ClaimDeliveryResult.RecoveredFromActiveNotification ->
          return DeliveryOutcome.RecoveredFromActiveNotification(
            deliveryId = claimResult.deliveryId,
            occurrenceKey = occurrenceKey,
          )
        is ClaimDeliveryResult.Skipped ->
          return DeliveryOutcome.Skipped(claimResult.reason)
        is ClaimDeliveryResult.ReadyToPost -> claimResult
      }

    // Re-check runtime permission, app notification toggle, and channel importance BEFORE notify()
    when (val gate = channelManager.inspectDeliveryGate(NotificationChannelManager.CHANNEL_TASK_REMINDERS)) {
      is NotificationGateStatus.SuppressedPermission -> {
        deliveryDao.updateTaskDeliveryStateIfCurrent(
          id = ready.delivery.id,
          expectedState = DeliveryState.PENDING,
          newState = DeliveryState.SUPPRESSED_PERMISSION,
          lastError = gate.reason,
          updatedAtEpochMs = localClock.currentEpochMs(),
        )
        return DeliveryOutcome.SuppressedPermission(gate.reason, occurrenceKey)
      }

      is NotificationGateStatus.SuppressedChannel -> {
        deliveryDao.updateTaskDeliveryStateIfCurrent(
          id = ready.delivery.id,
          expectedState = DeliveryState.PENDING,
          newState = DeliveryState.SUPPRESSED_CHANNEL,
          lastError = gate.reason,
          updatedAtEpochMs = localClock.currentEpochMs(),
        )
        return DeliveryOutcome.SuppressedChannel(gate.reason, occurrenceKey)
      }

      NotificationGateStatus.Ready -> Unit
    }

    val notification =
      buildTaskReminderNotification(
        task = ready.task.takeIf { it.id == ready.reminder.taskId } ?: fallbackTask ?: ready.task,
        reminder = ready.reminder,
        delivery = ready.delivery,
        onlyAlertOnce = ready.onlyAlertOnce,
      )

    hooks.onBeforeNotify(ready.delivery)

    return when (
      val postOutcome =
        notificationPoster.postNotification(
          tag = ready.delivery.notificationTag,
          id = ready.delivery.notificationId,
          notification = notification,
        )
    ) {
      NotificationPostOutcome.Posted -> {
        hooks.onAfterNotifyBeforeCommit(ready.delivery)
        val committed = commitPostedFenced(ready.delivery, verifiedRevision)
        if (committed) {
          DeliveryOutcome.PostedToSystem(ready.delivery.id, occurrenceKey)
        } else {
          // Concurrent cancel/revision change invalidated delivery -> clean up orphan notification
          notificationCanceller.cancelNotification(
            tag = ready.delivery.notificationTag,
            notificationId = ready.delivery.notificationId,
          )
          DeliveryOutcome.Skipped("Delivery became stale during post-notify commit")
        }
      }

      is NotificationPostOutcome.PermissionDenied -> {
        deliveryDao.updateTaskDeliveryStateIfCurrent(
          id = ready.delivery.id,
          expectedState = DeliveryState.PENDING,
          newState = DeliveryState.SUPPRESSED_PERMISSION,
          lastError = postOutcome.reason,
          updatedAtEpochMs = localClock.currentEpochMs(),
        )
        DeliveryOutcome.SuppressedPermission(postOutcome.reason, occurrenceKey)
      }

      is NotificationPostOutcome.FailedRetryable -> {
        deliveryDao.updateTaskDeliveryStateIfCurrent(
          id = ready.delivery.id,
          expectedState = DeliveryState.PENDING,
          newState = DeliveryState.FAILED_RETRYABLE,
          lastError = postOutcome.reason,
          updatedAtEpochMs = localClock.currentEpochMs(),
        )
        DeliveryOutcome.FailedRetryable(postOutcome.reason, occurrenceKey)
      }
    }
  }

  private suspend fun claimOrRecoverDeliveryTx(
    reminderId: String,
    expectedRevision: Long,
    occurrenceKey: String,
    scheduledAtEpochMs: Long,
    isRecoveryAttempt: Boolean,
  ): ClaimDeliveryResult =
    database.withTransaction {
      val now = localClock.currentEpochMs()
      val reminder =
        reminderDao.getTaskReminderById(reminderId)
          ?: return@withTransaction ClaimDeliveryResult.Skipped("Reminder missing before claim")
      if (!reminder.isEnabled || !isReminderStateEligibleForFire(reminder.scheduleState)) {
        return@withTransaction ClaimDeliveryResult.Skipped("Reminder disabled/inactive before claim")
      }
      if (reminder.scheduleRevision != expectedRevision) {
        return@withTransaction ClaimDeliveryResult.Skipped("Reminder revision changed before claim")
      }
      val task =
        taskDao.getById(reminder.taskId)
          ?: return@withTransaction ClaimDeliveryResult.Skipped("Task missing before claim")
      if (!TaskReminderRules.isTaskActive(task.status)) {
        return@withTransaction ClaimDeliveryResult.Skipped("Task inactive before claim: ${task.status}")
      }

      val tag = PendingIntentIdentitySnapshot.buildNotificationTag(ReminderOwnerType.TASK, reminder.id)
      val notifId = PendingIntentIdentitySnapshot.DEFAULT_NOTIFICATION_ID

      val existing = deliveryDao.getTaskDelivery(reminder.id, occurrenceKey)
      if (existing != null) {
        return@withTransaction handleExistingDeliveryTx(
          existing = existing,
          reminder = reminder,
          task = task,
          now = now,
          forceOnlyAlertOnce = isRecoveryAttempt,
        )
      }

      val candidate =
        TaskReminderDeliveryEntity(
          id = UUID.randomUUID().toString(),
          taskReminderId = reminder.id,
          occurrenceKey = occurrenceKey,
          scheduledAtEpochMs = scheduledAtEpochMs,
          firedScheduleRevision = reminder.scheduleRevision,
          state = DeliveryState.PENDING,
          notificationTag = tag,
          notificationId = notifId,
          attemptCount = 1,
          lastAttemptAtEpochMs = now,
          lastError = null,
          createdAtEpochMs = now,
          updatedAtEpochMs = now,
        )

      val insertedRowId = deliveryDao.insertTaskDeliveryIgnore(candidate)
      if (insertedRowId == -1L) {
        val raced =
          deliveryDao.getTaskDelivery(reminder.id, occurrenceKey)
            ?: return@withTransaction ClaimDeliveryResult.Skipped("Unique constraint raced with missing row")
        return@withTransaction handleExistingDeliveryTx(
          existing = raced,
          reminder = reminder,
          task = task,
          now = now,
          forceOnlyAlertOnce = isRecoveryAttempt,
        )
      }

      ClaimDeliveryResult.ReadyToPost(
        delivery = candidate,
        task = task,
        reminder = reminder,
        onlyAlertOnce = isRecoveryAttempt,
      )
    }

  private suspend fun handleExistingDeliveryTx(
    existing: TaskReminderDeliveryEntity,
    reminder: TaskReminderEntity,
    task: TaskEntity,
    now: Long,
    forceOnlyAlertOnce: Boolean,
  ): ClaimDeliveryResult {
    if (existing.state !in RECOVERABLE_STATES) {
      return ClaimDeliveryResult.AlreadyHandled(existing.state)
    }

    if (notificationPoster.isNotificationActive(existing.notificationTag, existing.notificationId)) {
      deliveryDao.updateTaskDeliveryAttempt(
        id = existing.id,
        state = DeliveryState.POSTED_TO_SYSTEM,
        firedScheduleRevision = reminder.scheduleRevision,
        attemptCount = maxOf(existing.attemptCount, 1),
        lastAttemptAtEpochMs = existing.lastAttemptAtEpochMs ?: now,
        lastError = null,
        updatedAtEpochMs = now,
      )
      return ClaimDeliveryResult.RecoveredFromActiveNotification(existing.id)
    }

    val nextAttemptCount = existing.attemptCount + 1
    deliveryDao.updateTaskDeliveryAttempt(
      id = existing.id,
      state = DeliveryState.PENDING,
      firedScheduleRevision = reminder.scheduleRevision,
      attemptCount = nextAttemptCount,
      lastAttemptAtEpochMs = now,
      lastError = null,
      updatedAtEpochMs = now,
    )
    val updated =
      existing.copy(
        state = DeliveryState.PENDING,
        firedScheduleRevision = reminder.scheduleRevision,
        attemptCount = nextAttemptCount,
        lastAttemptAtEpochMs = now,
        lastError = null,
        updatedAtEpochMs = now,
      )
    return ClaimDeliveryResult.ReadyToPost(
      delivery = updated,
      task = task,
      reminder = reminder,
      onlyAlertOnce = true || forceOnlyAlertOnce,
    )
  }

  private suspend fun commitPostedFenced(
    delivery: TaskReminderDeliveryEntity,
    expectedRevision: Long,
  ): Boolean =
    database.withTransaction {
      val now = localClock.currentEpochMs()
      val reminder = reminderDao.getTaskReminderById(delivery.taskReminderId)
      val task = reminder?.let { taskDao.getById(it.taskId) }
      val stillValid =
        reminder != null &&
          reminder.isEnabled &&
          isReminderStateEligibleForFire(reminder.scheduleState) &&
          reminder.scheduleRevision == expectedRevision &&
          task != null &&
          TaskReminderRules.isTaskActive(task.status)

      if (!stillValid) {
        deliveryDao.updateTaskDeliveryStateIfCurrent(
          id = delivery.id,
          expectedState = DeliveryState.PENDING,
          newState = DeliveryState.STALE,
          lastError = "Reminder/task invalidated before POSTED_TO_SYSTEM commit",
          updatedAtEpochMs = now,
        )
        return@withTransaction false
      }

      val updated =
        deliveryDao.updateTaskDeliveryStateIfCurrent(
          id = delivery.id,
          expectedState = DeliveryState.PENDING,
          newState = DeliveryState.POSTED_TO_SYSTEM,
          lastError = null,
          updatedAtEpochMs = now,
        )
      updated == 1
    }

  private suspend fun recordTerminalDeliveryStateLocked(
    reminder: TaskReminderEntity,
    occurrenceKey: String,
    scheduledAtEpochMs: Long,
    state: DeliveryState,
    reason: String,
    nowEpochMs: Long,
  ) {
    database.withTransaction {
      val existing = deliveryDao.getTaskDelivery(reminder.id, occurrenceKey)
      if (existing == null) {
        val entity =
          TaskReminderDeliveryEntity(
            id = UUID.randomUUID().toString(),
            taskReminderId = reminder.id,
            occurrenceKey = occurrenceKey,
            scheduledAtEpochMs = scheduledAtEpochMs,
            firedScheduleRevision = reminder.scheduleRevision,
            state = state,
            notificationTag =
              PendingIntentIdentitySnapshot.buildNotificationTag(
                ReminderOwnerType.TASK,
                reminder.id,
              ),
            notificationId = PendingIntentIdentitySnapshot.DEFAULT_NOTIFICATION_ID,
            attemptCount = 0,
            lastAttemptAtEpochMs = nowEpochMs,
            lastError = reason,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
          )
        deliveryDao.insertTaskDeliveryIgnore(entity)
      } else if (existing.state in RECOVERABLE_STATES) {
        deliveryDao.updateTaskDeliveryStateIfCurrent(
          id = existing.id,
          expectedState = existing.state,
          newState = state,
          lastError = reason,
          updatedAtEpochMs = nowEpochMs,
        )
      }
    }
  }

  private suspend fun buildTaskReminderNotification(
    task: TaskEntity,
    reminder: TaskReminderEntity,
    delivery: TaskReminderDeliveryEntity,
    onlyAlertOnce: Boolean,
  ): Notification {
    val selectedLanguage = languageProvider()
    val localizedContext =
      if (selectedLanguage != null) context.createLocalizedContext(selectedLanguage) else context

    val scheduledText =
      localizedContext.getString(R.string.notification_task_reminder_text, reminder.triggerTime)
    val contentText =
      if (!task.description.isNullOrBlank()) {
        localizedContext.getString(
          R.string.notification_task_reminder_with_desc,
          scheduledText,
          task.description.trim(),
        )
      } else {
        scheduledText
      }

    val openIntent =
      pendingIntentFactory.createOpenTaskDetailsPendingIntent(
        taskId = task.id,
        reminderId = reminder.id,
        occurrenceKey = delivery.occurrenceKey,
        scheduleRevision = reminder.scheduleRevision,
      )
    val completeIntent =
      pendingIntentFactory.createCompleteTaskPendingIntent(
        taskId = task.id,
        reminderId = reminder.id,
        occurrenceKey = delivery.occurrenceKey,
        scheduleRevision = reminder.scheduleRevision,
        notificationTag = delivery.notificationTag,
        notificationId = delivery.notificationId,
      )
    val snoozeIntent =
      pendingIntentFactory.createSnoozeTaskPendingIntent(
        taskId = task.id,
        reminderId = reminder.id,
        occurrenceKey = delivery.occurrenceKey,
        scheduleRevision = reminder.scheduleRevision,
        notificationTag = delivery.notificationTag,
        notificationId = delivery.notificationId,
      )

    return NotificationCompat.Builder(localizedContext, NotificationChannelManager.CHANNEL_TASK_REMINDERS)
      .setSmallIcon(R.drawable.ic_yawmi_notification)
      .setContentTitle(task.title)
      .setContentText(contentText)
      .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
      .setCategory(NotificationCompat.CATEGORY_REMINDER)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setAutoCancel(true)
      .setOnlyAlertOnce(onlyAlertOnce)
      .setContentIntent(openIntent)
      .addAction(
        0,
        localizedContext.getString(R.string.notification_action_complete),
        completeIntent,
      )
      .addAction(
        0,
        localizedContext.getString(R.string.notification_action_snooze),
        snoozeIntent,
      )
      .build()
  }

  private fun isReminderStateEligibleForFire(state: ReminderScheduleState): Boolean =
    state == ReminderScheduleState.PENDING_SYNC ||
      state == ReminderScheduleState.SCHEDULED_EXACT ||
      state == ReminderScheduleState.SCHEDULED_INEXACT

  companion object {
    private val RECOVERABLE_STATES =
      setOf(DeliveryState.PENDING, DeliveryState.FAILED_RETRYABLE)
  }
}
