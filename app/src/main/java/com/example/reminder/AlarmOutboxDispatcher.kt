package com.example.reminder

import androidx.room.withTransaction
import com.example.core.time.LocalClock
import com.example.core.time.SystemLocalClock
import com.example.data.local.dao.AlarmSyncOutboxDao
import com.example.data.local.dao.ReminderDao
import com.example.data.local.db.PendingIntentIdentitySnapshot
import com.example.data.local.db.YawmiDatabase
import com.example.data.local.entity.AlarmSyncOutboxEntity
import com.example.data.local.entity.OutboxAction
import com.example.data.local.entity.OutboxStatus
import com.example.data.local.entity.OutboxSystemTarget
import com.example.data.local.entity.ReminderOwnerType
import com.example.data.local.entity.ReminderScheduleState
import com.example.domain.task.ReminderTriggerCalculator
import com.example.domain.task.ScheduleTimingDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

sealed interface DrainOutcome {
  data class Completed(
    val processedCount: Int,
    val succeededCount: Int,
    val supersededCount: Int,
    val failedPermanentCount: Int,
    val leaseToken: LeaseToken,
  ) : DrainOutcome

  data class BackoffScheduled(
    val delayMs: Long,
    val processedCount: Int,
    val leaseToken: LeaseToken,
  ) : DrainOutcome

  data class RetryableError(
    val reason: String,
    val processedCount: Int,
    val leaseToken: LeaseToken?,
  ) : DrainOutcome

  data class LeaseHeldByOther(
    val currentOwner: String,
    val currentGeneration: Long,
    val untilEpochMs: Long,
  ) : DrainOutcome
}

internal interface OutboxDispatchHooks {
  suspend fun onBeforeExternalExecute(entry: AlarmSyncOutboxEntity) {}

  suspend fun onBeforeCommit(entry: AlarmSyncOutboxEntity) {}

  object None : OutboxDispatchHooks
}

private data class ReminderSnapshot(
  val id: String,
  val triggerDate: String,
  val triggerTime: String,
  val scheduleRevision: Long,
  val isEnabled: Boolean,
  val scheduleState: ReminderScheduleState,
)

private enum class ConflictReason {
  SUPERSEDED_BY_NEWER_COMMAND,
  LEASE_OR_CLAIM_LOST,
  REMINDER_REVISION_MISMATCH_OR_MISSING,
}

private class FencedCommitConflictException(
  val reason: ConflictReason,
  message: String,
) : RuntimeException(message)

class AlarmOutboxDispatcher(
  private val database: YawmiDatabase,
  private val outboxDao: AlarmSyncOutboxDao,
  private val reminderDao: ReminderDao,
  private val leaseManager: DispatcherLeaseManager,
  private val alarmScheduler: AlarmScheduler,
  private val notificationCanceller: NotificationCancellationExecutor,
  private val localClock: LocalClock = SystemLocalClock(),
) {

  suspend fun drain(workerOwnerId: String): DrainOutcome =
    drainInternal(workerOwnerId = workerOwnerId, hooks = OutboxDispatchHooks.None)

  internal suspend fun drainWithHooks(
    workerOwnerId: String,
    hooks: OutboxDispatchHooks,
  ): DrainOutcome = drainInternal(workerOwnerId = workerOwnerId, hooks = hooks)

  private suspend fun drainInternal(
    workerOwnerId: String,
    hooks: OutboxDispatchHooks,
  ): DrainOutcome {
    var currentToken =
      when (val acquired = leaseManager.acquireOrRenewLease(workerOwnerId)) {
        is LeaseAcquireResult.HeldByOther -> {
          return DrainOutcome.LeaseHeldByOther(
            currentOwner = acquired.currentOwner,
            currentGeneration = acquired.currentGeneration,
            untilEpochMs = acquired.untilEpochMs,
          )
        }
        is LeaseAcquireResult.Acquired -> acquired.token
      }

    var processedCount = 0
    var succeededCount = 0
    var supersededCount = 0
    var failedPermanentCount = 0
    var releasedNormally = false

    try {
      while (true) {
        val renewed = leaseManager.renewIfNeeded(currentToken)
        if (renewed == null) {
          releasedNormally = true
          return DrainOutcome.RetryableError(
            reason = "Lease lost before claim",
            processedCount = processedCount,
            leaseToken = null,
          )
        }
        currentToken = renewed

        val claimStep = claimNextEligibleLocked(currentToken)
        when (claimStep) {
          is ClaimStepResult.QueueEmpty -> {
            leaseManager.releaseLease(currentToken)
            releasedNormally = true
            return DrainOutcome.Completed(
              processedCount = processedCount,
              succeededCount = succeededCount,
              supersededCount = supersededCount,
              failedPermanentCount = failedPermanentCount,
              leaseToken = currentToken,
            )
          }

          is ClaimStepResult.AllPendingDelayed -> {
            leaseManager.releaseLease(currentToken)
            releasedNormally = true
            return DrainOutcome.BackoffScheduled(
              delayMs = claimStep.minRemainingDelayMs,
              processedCount = processedCount,
              leaseToken = currentToken,
            )
          }

          is ClaimStepResult.LeaseLost -> {
            releasedNormally = true
            return DrainOutcome.RetryableError(
              reason = "Lease lost during claim",
              processedCount = processedCount,
              leaseToken = null,
            )
          }

          is ClaimStepResult.Claimed -> {
            val claimedEntry = claimStep.entry
            if (claimedEntry.ownerType != ReminderOwnerType.TASK) {
              leaseManager.releaseLease(currentToken)
              releasedNormally = true
              return DrainOutcome.RetryableError(
                reason = "Non-TASK outbox command is out of scope for Unit 4A.2A: ${claimedEntry.ownerType}",
                processedCount = processedCount,
                leaseToken = null,
              )
            }
            when (val execPlan = buildExecutionPlan(claimedEntry)) {
              is ExecutionPlan.Superseded -> {
                markSuperseded(claimedEntry, currentToken, execPlan.reason)
                supersededCount++
                processedCount++
              }

              is ExecutionPlan.ExpiredOrInvalidSchedule -> {
                alarmScheduler.cancelAlarm(claimedEntry)
                val updated = commitPermanentScheduleFailure(claimedEntry, currentToken, execPlan.reason)
                if (updated) {
                  failedPermanentCount++
                  processedCount++
                } else {
                  releasedNormally = true
                  return DrainOutcome.RetryableError(
                    reason = "Lease or revision conflict during permanent failure commit",
                    processedCount = processedCount,
                    leaseToken = null,
                  )
                }
              }

              is ExecutionPlan.Execute -> {
                hooks.onBeforeExternalExecute(claimedEntry)
                when (val systemResult = executeExternal(claimedEntry, execPlan)) {
                  is ExternalExecutionResult.RetryableFailure -> {
                    if (handleRetryableFailure(claimedEntry, currentToken, systemResult.reason)) {
                      failedPermanentCount++
                    }
                    processedCount++
                  }

                  is ExternalExecutionResult.PermanentFailure -> {
                    commitPermanentScheduleFailure(claimedEntry, currentToken, systemResult.reason)
                    failedPermanentCount++
                    processedCount++
                  }

                  is ExternalExecutionResult.Success -> {
                    hooks.onBeforeCommit(claimedEntry)
                    when (val commitResult = commitSuccessFenced(claimedEntry, currentToken, systemResult)) {
                      is FencedCommitResult.Committed -> {
                        succeededCount++
                        processedCount++
                      }

                      is FencedCommitResult.Conflict -> {
                        compensateAfterCasFailure(claimedEntry, currentToken, commitResult.reason)
                        if (commitResult.reason == ConflictReason.LEASE_OR_CLAIM_LOST) {
                          releasedNormally = true
                          return DrainOutcome.RetryableError(
                            reason = "Lease lost during fenced commit",
                            processedCount = processedCount,
                            leaseToken = null,
                          )
                        } else {
                          supersededCount++
                          processedCount++
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    } catch (c: CancellationException) {
      throw c
    } finally {
      if (!releasedNormally) {
        withContext(NonCancellable) {
          leaseManager.releaseLease(currentToken)
        }
      }
    }
  }

  private sealed interface ClaimStepResult {
    data object QueueEmpty : ClaimStepResult

    data class AllPendingDelayed(val minRemainingDelayMs: Long) : ClaimStepResult

    data object LeaseLost : ClaimStepResult

    data class Claimed(val entry: AlarmSyncOutboxEntity) : ClaimStepResult
  }

  private suspend fun claimNextEligibleLocked(token: LeaseToken): ClaimStepResult =
    database.withTransaction {
      val pending = outboxDao.getPendingOrdered()
      if (pending.isEmpty()) {
        return@withTransaction ClaimStepResult.QueueEmpty
      }

      val now = localClock.currentEpochMs()
      var eligibleEntry: AlarmSyncOutboxEntity? = null
      var minRemainingDelayMs = Long.MAX_VALUE

      for (entry in pending) {
        val lastAttempt = entry.lastAttemptAtEpochMs
        if (entry.attemptCount > 0 && lastAttempt != null) {
          val backoffMs = computeRetryBackoffMs(entry.attemptCount)
          val remainingMs = (lastAttempt + backoffMs) - now
          if (remainingMs > 0L) {
            minRemainingDelayMs = minOf(minRemainingDelayMs, remainingMs)
            continue
          }
        }
        eligibleEntry = entry
        break
      }

      if (eligibleEntry == null) {
        return@withTransaction ClaimStepResult.AllPendingDelayed(
          minRemainingDelayMs = minRemainingDelayMs.coerceAtLeast(1L)
        )
      }

      val claimedRows =
        outboxDao.claimPendingFenced(
          id = eligibleEntry.id,
          leaseOwner = token.ownerId,
          leaseGeneration = token.generation,
          leaseGenerationStr = token.generation.toString(),
          nowEpochMs = now,
        )
      if (claimedRows != 1) {
        return@withTransaction ClaimStepResult.LeaseLost
      }
      val claimed = outboxDao.getById(eligibleEntry.id)
      if (claimed == null) {
        ClaimStepResult.LeaseLost
      } else {
        ClaimStepResult.Claimed(claimed)
      }
    }

  private sealed interface ExecutionPlan {
    data class Superseded(val reason: String) : ExecutionPlan

    data class ExpiredOrInvalidSchedule(val reason: String) : ExecutionPlan

    sealed interface Execute : ExecutionPlan {
      data class ScheduleAlarm(val triggerEpochMs: Long) : Execute

      data object CancelAlarm : Execute

      data object CancelNotification : Execute
    }
  }

  private suspend fun buildExecutionPlan(entry: AlarmSyncOutboxEntity): ExecutionPlan {
    require(entry.ownerType == ReminderOwnerType.TASK) {
      "Unit 4A.2A dispatcher executes TASK reminders only, got ${entry.ownerType}"
    }
    if (hasNewerCommandForTarget(entry)) {
      return ExecutionPlan.Superseded("Superseded by newer outbox sequence")
    }

    val reminder = loadReminderSnapshot(entry.ownerType, entry.reminderId)
    return when (entry.action) {
      OutboxAction.SCHEDULE_ALARM -> {
        if (reminder == null || !reminder.isEnabled) {
          return ExecutionPlan.Superseded("Reminder missing or disabled before schedule")
        }
        if (entry.targetScheduleRevision != null && reminder.scheduleRevision != entry.targetScheduleRevision) {
          return ExecutionPlan.Superseded("Reminder revision changed before schedule")
        }
        val now = localClock.currentEpochMs()
        val evaluation =
          ReminderTriggerCalculator.evaluateForSchedule(
            triggerDate = reminder.triggerDate,
            triggerTime = reminder.triggerTime,
            scheduleRevision = reminder.scheduleRevision,
            nowEpochMs = now,
            zoneId = localClock.zoneId(),
          )
        when (evaluation.decision) {
          ScheduleTimingDecision.SCHEDULE_AT_TRIGGER -> {
            ExecutionPlan.Execute.ScheduleAlarm(evaluation.resolved!!.triggerEpochMs)
          }

          ScheduleTimingDecision.FIRE_IMMEDIATELY_WITHIN_GRACE -> {
            ExecutionPlan.Execute.ScheduleAlarm(maxOf(evaluation.resolved!!.triggerEpochMs, now))
          }

          ScheduleTimingDecision.EXPIRED_OUTSIDE_GRACE -> {
            ExecutionPlan.ExpiredOrInvalidSchedule("Reminder trigger expired outside grace window")
          }

          ScheduleTimingDecision.INVALID -> {
            ExecutionPlan.ExpiredOrInvalidSchedule("Reminder trigger date/time invalid")
          }
        }
      }

      OutboxAction.CANCEL_ALARM -> {
        if (
          reminder != null &&
          entry.targetScheduleRevision != null &&
          reminder.scheduleRevision != entry.targetScheduleRevision
        ) {
          ExecutionPlan.Superseded("Reminder moved to newer revision before alarm cancel")
        } else {
          ExecutionPlan.Execute.CancelAlarm
        }
      }

      OutboxAction.CANCEL_NOTIFICATION -> {
        if (
          reminder != null &&
          entry.targetScheduleRevision != null &&
          reminder.scheduleRevision != entry.targetScheduleRevision
        ) {
          ExecutionPlan.Superseded("Reminder moved to newer revision before notification cancel")
        } else {
          ExecutionPlan.Execute.CancelNotification
        }
      }
    }
  }

  private sealed interface ExternalExecutionResult {
    data class Success(val resultingScheduleState: ReminderScheduleState?) : ExternalExecutionResult

    data class RetryableFailure(val reason: String) : ExternalExecutionResult

    data class PermanentFailure(val reason: String) : ExternalExecutionResult
  }

  private fun executeExternal(
    entry: AlarmSyncOutboxEntity,
    plan: ExecutionPlan.Execute,
  ): ExternalExecutionResult {
    if (entry.ownerType != ReminderOwnerType.TASK) {
      return ExternalExecutionResult.RetryableFailure(
        "Non-TASK outbox command is out of scope for Unit 4A.2A: ${entry.ownerType}"
      )
    }
    return when (plan) {
      is ExecutionPlan.Execute.ScheduleAlarm -> {
        when (val outcome = alarmScheduler.scheduleAlarm(entry, plan.triggerEpochMs)) {
          is AlarmScheduleOutcome.ScheduledExact ->
            ExternalExecutionResult.Success(ReminderScheduleState.SCHEDULED_EXACT)
          is AlarmScheduleOutcome.ScheduledInexact ->
            ExternalExecutionResult.Success(ReminderScheduleState.SCHEDULED_INEXACT)
          is AlarmScheduleOutcome.FailedRetryable ->
            ExternalExecutionResult.RetryableFailure(outcome.reason)
          is AlarmScheduleOutcome.FailedPermanent ->
            ExternalExecutionResult.PermanentFailure(outcome.reason)
        }
      }

      ExecutionPlan.Execute.CancelAlarm -> {
        when (val outcome = alarmScheduler.cancelAlarm(entry)) {
          AlarmCancelOutcome.Cancelled -> ExternalExecutionResult.Success(null)
          is AlarmCancelOutcome.FailedRetryable ->
            ExternalExecutionResult.RetryableFailure(outcome.reason)
        }
      }

      ExecutionPlan.Execute.CancelNotification -> {
        val id = entry.notificationId ?: PendingIntentIdentitySnapshot.DEFAULT_NOTIFICATION_ID
        when (val outcome = notificationCanceller.cancelNotification(entry.notificationTag, id)) {
          NotificationCancelOutcome.Cancelled -> ExternalExecutionResult.Success(null)
          is NotificationCancelOutcome.FailedRetryable ->
            ExternalExecutionResult.RetryableFailure(outcome.reason)
        }
      }
    }
  }

  private sealed interface FencedCommitResult {
    data object Committed : FencedCommitResult

    data class Conflict(val reason: ConflictReason) : FencedCommitResult
  }

  private suspend fun commitSuccessFenced(
    entry: AlarmSyncOutboxEntity,
    token: LeaseToken,
    systemResult: ExternalExecutionResult.Success,
  ): FencedCommitResult {
    require(entry.ownerType == ReminderOwnerType.TASK) {
      "Unit 4A.2A dispatcher commits TASK reminders only, got ${entry.ownerType}"
    }
    return try {
      database.withTransaction {
        if (hasNewerCommandForTarget(entry)) {
          throw FencedCommitConflictException(
            ConflictReason.SUPERSEDED_BY_NEWER_COMMAND,
            "Newer command exists for target before fenced commit",
          )
        }

        val now = localClock.currentEpochMs()
        val committedOutboxRows =
          outboxDao.commitClaimedFenced(
            id = entry.id,
            leaseOwner = token.ownerId,
            leaseGeneration = token.generation,
            leaseGenerationStr = token.generation.toString(),
            nowEpochMs = now,
          )
        if (committedOutboxRows != 1) {
          throw FencedCommitConflictException(
            ConflictReason.LEASE_OR_CLAIM_LOST,
            "Failed fenced outbox commit (status/generation/owner/expiry mismatch)",
          )
        }

        val reminder = loadReminderSnapshot(entry.ownerType, entry.reminderId)
        when (entry.action) {
          OutboxAction.SCHEDULE_ALARM -> {
            val targetRevision =
              entry.targetScheduleRevision
                ?: throw FencedCommitConflictException(
                  ConflictReason.REMINDER_REVISION_MISMATCH_OR_MISSING,
                  "Missing targetScheduleRevision for SCHEDULE_ALARM",
                )
            val newState =
              systemResult.resultingScheduleState ?: ReminderScheduleState.SCHEDULED_INEXACT
            if (reminder == null || !reminder.isEnabled) {
              throw FencedCommitConflictException(
                ConflictReason.REMINDER_REVISION_MISMATCH_OR_MISSING,
                "Reminder missing or disabled before commit",
              )
            }
            val updatedReminderRows =
              updateReminderScheduleStateIfRevision(
                ownerType = entry.ownerType,
                reminderId = entry.reminderId,
                expectedRevision = targetRevision,
                scheduleState = newState,
                updatedAtEpochMs = now,
              )
            if (updatedReminderRows != 1) {
              throw FencedCommitConflictException(
                ConflictReason.REMINDER_REVISION_MISMATCH_OR_MISSING,
                "Reminder update failed or revision mismatch",
              )
            }
          }

          OutboxAction.CANCEL_ALARM -> {
            if (reminder != null && entry.targetScheduleRevision != null) {
              if (reminder.scheduleRevision != entry.targetScheduleRevision) {
                throw FencedCommitConflictException(
                  ConflictReason.REMINDER_REVISION_MISMATCH_OR_MISSING,
                  "Reminder revision changed during CANCEL_ALARM",
                )
              }
              val preservedCancelState =
                if (reminder.scheduleState == ReminderScheduleState.PAUSED) {
                  ReminderScheduleState.PAUSED
                } else {
                  ReminderScheduleState.CANCELLED
                }
              val updatedRows =
                updateReminderScheduleStateIfRevision(
                  ownerType = entry.ownerType,
                  reminderId = entry.reminderId,
                  expectedRevision = entry.targetScheduleRevision,
                  scheduleState = preservedCancelState,
                  updatedAtEpochMs = now,
                )
              if (updatedRows != 1) {
                throw FencedCommitConflictException(
                  ConflictReason.REMINDER_REVISION_MISMATCH_OR_MISSING,
                  "Reminder revision fence failed during CANCEL_ALARM",
                )
              }
            }
          }

          OutboxAction.CANCEL_NOTIFICATION -> {
            if (reminder != null && entry.targetScheduleRevision != null) {
              if (reminder.scheduleRevision != entry.targetScheduleRevision) {
                throw FencedCommitConflictException(
                  ConflictReason.REMINDER_REVISION_MISMATCH_OR_MISSING,
                  "Reminder revision changed during CANCEL_NOTIFICATION",
                )
              }
            }
          }
        }
      }
      FencedCommitResult.Committed
    } catch (conflict: FencedCommitConflictException) {
      FencedCommitResult.Conflict(conflict.reason)
    }
  }

  private suspend fun compensateAfterCasFailure(
    entry: AlarmSyncOutboxEntity,
    token: LeaseToken,
    reason: ConflictReason,
  ) {
    if (reason != ConflictReason.LEASE_OR_CLAIM_LOST) {
      markSuperseded(entry, token, "Compensated after CAS conflict: $reason")
    }
    if (entry.ownerType == ReminderOwnerType.TASK && entry.action == OutboxAction.SCHEDULE_ALARM) {
      val currentReminder = loadReminderSnapshot(entry.ownerType, entry.reminderId)
      val newerExists = hasNewerCommandForTarget(entry)
      if (currentReminder == null || !currentReminder.isEnabled || !newerExists) {
        if (reason != ConflictReason.LEASE_OR_CLAIM_LOST || currentReminder == null || !currentReminder.isEnabled) {
          alarmScheduler.cancelAlarm(entry)
        }
      }
    }
  }

  private suspend fun handleRetryableFailure(
    entry: AlarmSyncOutboxEntity,
    token: LeaseToken,
    reason: String,
  ): Boolean {
    val activeToken = leaseManager.renewIfNeeded(token) ?: token
    val now = localClock.currentEpochMs()
    val nextAttempt = entry.attemptCount + 1
    return database.withTransaction {
      if (nextAttempt >= MAX_RETRY_ATTEMPTS) {
        val marked =
          outboxDao.markClaimedFailedPermanentFenced(
            id = entry.id,
            leaseOwner = activeToken.ownerId,
            leaseGeneration = activeToken.generation,
            leaseGenerationStr = activeToken.generation.toString(),
            nowEpochMs = now,
            failureReason = reason,
          )
        if (marked == 1 && entry.action == OutboxAction.SCHEDULE_ALARM && entry.targetScheduleRevision != null) {
          updateReminderScheduleStateIfRevision(
            ownerType = entry.ownerType,
            reminderId = entry.reminderId,
            expectedRevision = entry.targetScheduleRevision,
            scheduleState = ReminderScheduleState.FAILED_PERMANENT,
            updatedAtEpochMs = now,
          )
        }
        marked == 1
      } else {
        outboxDao.returnClaimedToPendingForRetryFenced(
          id = entry.id,
          leaseOwner = activeToken.ownerId,
          leaseGeneration = activeToken.generation,
          leaseGenerationStr = activeToken.generation.toString(),
          nowEpochMs = now,
          failureReason = reason,
        )
        false
      }
    }
  }

  private suspend fun commitPermanentScheduleFailure(
    entry: AlarmSyncOutboxEntity,
    token: LeaseToken,
    reason: String,
  ): Boolean {
    return try {
      database.withTransaction {
        val now = localClock.currentEpochMs()
        val marked =
          outboxDao.markClaimedFailedPermanentFenced(
            id = entry.id,
            leaseOwner = token.ownerId,
            leaseGeneration = token.generation,
            leaseGenerationStr = token.generation.toString(),
            nowEpochMs = now,
            failureReason = reason,
          )
        if (marked != 1) {
          throw FencedCommitConflictException(
            ConflictReason.LEASE_OR_CLAIM_LOST,
            "Failed fenced permanent failure update",
          )
        }
        val reminder = loadReminderSnapshot(entry.ownerType, entry.reminderId)
        if (reminder != null && entry.targetScheduleRevision != null) {
          val updated =
            updateReminderScheduleStateIfRevision(
              ownerType = entry.ownerType,
              reminderId = entry.reminderId,
              expectedRevision = entry.targetScheduleRevision,
              scheduleState = ReminderScheduleState.FAILED_PERMANENT,
              updatedAtEpochMs = now,
            )
          if (updated != 1) {
            throw FencedCommitConflictException(
              ConflictReason.REMINDER_REVISION_MISMATCH_OR_MISSING,
              "Reminder revision mismatch on permanent failure update",
            )
          }
        }
      }
      true
    } catch (_: FencedCommitConflictException) {
      false
    }
  }

  private suspend fun markSuperseded(
    entry: AlarmSyncOutboxEntity,
    token: LeaseToken,
    reason: String,
  ) {
    database.withTransaction {
      outboxDao.markClaimedSupersededFenced(
        id = entry.id,
        leaseOwner = token.ownerId,
        leaseGeneration = token.generation,
        leaseGenerationStr = token.generation.toString(),
        nowEpochMs = localClock.currentEpochMs(),
        failureReason = reason,
      )
    }
  }

  private suspend fun hasNewerCommandForTarget(entry: AlarmSyncOutboxEntity): Boolean {
    if (entry.ownerType != ReminderOwnerType.TASK) {
      return false
    }
    return when (entry.systemTarget) {
      OutboxSystemTarget.ALARM_MANAGER ->
        outboxDao.hasNewerAlarmCommand(entry.pendingIntentIdentityKey, entry.seq)
      OutboxSystemTarget.NOTIFICATION_MANAGER ->
        outboxDao.hasNewerNotificationCommand(
          notificationTag = entry.notificationTag.orEmpty(),
          notificationId = entry.notificationId ?: PendingIntentIdentitySnapshot.DEFAULT_NOTIFICATION_ID,
          currentSeq = entry.seq,
        )
    }
  }

  private suspend fun loadReminderSnapshot(
    ownerType: ReminderOwnerType,
    reminderId: String,
  ): ReminderSnapshot? {
    if (ownerType != ReminderOwnerType.TASK) {
      return null
    }
    return reminderDao.getTaskReminderById(reminderId)?.let {
      ReminderSnapshot(
        id = it.id,
        triggerDate = it.triggerDate,
        triggerTime = it.triggerTime,
        scheduleRevision = it.scheduleRevision,
        isEnabled = it.isEnabled,
        scheduleState = it.scheduleState,
      )
    }
  }

  private suspend fun updateReminderScheduleStateIfRevision(
    ownerType: ReminderOwnerType,
    reminderId: String,
    expectedRevision: Long,
    scheduleState: ReminderScheduleState,
    updatedAtEpochMs: Long,
  ): Int {
    if (ownerType != ReminderOwnerType.TASK) {
      return 0
    }
    return reminderDao.updateTaskReminderScheduleStateIfRevision(
      id = reminderId,
      expectedRevision = expectedRevision,
      scheduleState = scheduleState,
      updatedAtEpochMs = updatedAtEpochMs,
    )
  }

  companion object {
    const val BASE_BACKOFF_MS = 30_000L
    const val MAX_BACKOFF_MS = 900_000L
    const val MAX_RETRY_ATTEMPTS = 5

    fun computeRetryBackoffMs(attemptCountAfterFailure: Int): Long {
      val shift = (attemptCountAfterFailure - 1).coerceIn(0, 10)
      return (BASE_BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
    }
  }
}
