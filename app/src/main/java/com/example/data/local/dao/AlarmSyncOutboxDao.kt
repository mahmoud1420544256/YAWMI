package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.AlarmSyncOutboxEntity
import com.example.data.local.entity.OutboxStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmSyncOutboxDao {
  @Query("SELECT * FROM alarm_sync_outbox ORDER BY seq ASC")
  fun observeAllOrdered(): Flow<List<AlarmSyncOutboxEntity>>

  @Query("SELECT * FROM alarm_sync_outbox WHERE status IN ('PENDING', 'CLAIMED') ORDER BY seq ASC")
  fun observeActiveQueue(): Flow<List<AlarmSyncOutboxEntity>>

  @Query("SELECT * FROM alarm_sync_outbox WHERE status IN ('PENDING', 'CLAIMED') ORDER BY seq ASC")
  suspend fun getActiveQueueOrdered(): List<AlarmSyncOutboxEntity>

  @Query("SELECT * FROM alarm_sync_outbox ORDER BY seq ASC")
  suspend fun getAllOrdered(): List<AlarmSyncOutboxEntity>

  @Query("SELECT * FROM alarm_sync_outbox WHERE id = :id")
  suspend fun getById(id: String): AlarmSyncOutboxEntity?

  @Query("SELECT MAX(seq) FROM alarm_sync_outbox")
  suspend fun getMaxSeq(): Long?

  @Query("SELECT MAX(requestCode) FROM alarm_sync_outbox")
  suspend fun getMaxRequestCode(): Int?

  @Query("SELECT EXISTS(SELECT 1 FROM alarm_sync_outbox WHERE requestCode = :requestCode)")
  suspend fun isRequestCodeReservedInOutbox(requestCode: Int): Boolean

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(entry: AlarmSyncOutboxEntity)

  @Query(
    """
    UPDATE alarm_sync_outbox
    SET status = :status,
        claimedLeaseGeneration = :claimedLeaseGeneration,
        claimedAtEpochMs = :claimedAtEpochMs,
        attemptCount = :attemptCount,
        lastAttemptAtEpochMs = :lastAttemptAtEpochMs,
        failureReason = :failureReason
    WHERE id = :id
    """
  )
  suspend fun updateLifecycle(
    id: String,
    status: OutboxStatus,
    claimedLeaseGeneration: Long?,
    claimedAtEpochMs: Long?,
    attemptCount: Int,
    lastAttemptAtEpochMs: Long?,
    failureReason: String?,
  ): Int

  @Query(
    """
    UPDATE alarm_sync_outbox
    SET status = 'SUPERSEDED'
    WHERE status = 'PENDING'
      AND systemTarget = 'ALARM_MANAGER'
      AND pendingIntentIdentityKey = :pendingIntentIdentityKey
      AND seq < :newerSeq
    """
  )
  suspend fun supersedePendingAlarmByIdentityBeforeSeq(
    pendingIntentIdentityKey: String,
    newerSeq: Long,
  ): Int

  @Query(
    """
    UPDATE alarm_sync_outbox
    SET status = 'SUPERSEDED'
    WHERE status = 'PENDING'
      AND systemTarget = 'NOTIFICATION_MANAGER'
      AND notificationTag = :notificationTag
      AND notificationId = :notificationId
      AND seq < :newerSeq
    """
  )
  suspend fun supersedePendingNotificationByTagIdBeforeSeq(
    notificationTag: String,
    notificationId: Int,
    newerSeq: Long,
  ): Int

  @Query("SELECT EXISTS(SELECT 1 FROM alarm_sync_outbox WHERE status IN ('PENDING', 'CLAIMED') AND ownerType = 'TASK')")
  suspend fun hasActiveQueue(): Boolean

  @Query("SELECT * FROM alarm_sync_outbox WHERE status = 'PENDING' AND ownerType = 'TASK' ORDER BY seq ASC")
  suspend fun getPendingOrdered(): List<AlarmSyncOutboxEntity>

  @Query(
    """
    UPDATE alarm_sync_outbox
    SET status = 'PENDING',
        claimedLeaseGeneration = NULL,
        claimedAtEpochMs = NULL
    WHERE status = 'CLAIMED'
      AND ownerType = 'TASK'
      AND (claimedLeaseGeneration IS NULL OR claimedLeaseGeneration < :newGeneration)
    """
  )
  suspend fun recoverStaleClaimedBeforeGeneration(newGeneration: Long): Int

  @Query(
    """
    UPDATE alarm_sync_outbox
    SET status = 'CLAIMED',
        claimedLeaseGeneration = :leaseGeneration,
        claimedAtEpochMs = :nowEpochMs
    WHERE id = :id
      AND ownerType = 'TASK'
      AND status = 'PENDING'
      AND EXISTS (
        SELECT 1 FROM app_metadata AS ownerMeta
        WHERE ownerMeta.`key` = 'dispatcherLeaseOwner' AND ownerMeta.value = :leaseOwner
      )
      AND EXISTS (
        SELECT 1 FROM app_metadata AS genMeta
        WHERE genMeta.`key` = 'dispatcherLeaseGeneration' AND genMeta.value = :leaseGenerationStr
      )
      AND EXISTS (
        SELECT 1 FROM app_metadata AS untilMeta
        WHERE untilMeta.`key` = 'dispatcherLeaseUntilEpochMs' AND CAST(untilMeta.value AS INTEGER) > :nowEpochMs
      )
    """
  )
  suspend fun claimPendingFenced(
    id: String,
    leaseOwner: String,
    leaseGeneration: Long,
    leaseGenerationStr: String,
    nowEpochMs: Long,
  ): Int

  @Query(
    """
    UPDATE alarm_sync_outbox
    SET status = 'SUCCEEDED',
        claimedLeaseGeneration = NULL,
        claimedAtEpochMs = NULL,
        attemptCount = attemptCount + 1,
        lastAttemptAtEpochMs = :nowEpochMs,
        failureReason = NULL
    WHERE id = :id
      AND ownerType = 'TASK'
      AND status = 'CLAIMED'
      AND claimedLeaseGeneration = :leaseGeneration
      AND EXISTS (
        SELECT 1 FROM app_metadata AS ownerMeta
        WHERE ownerMeta.`key` = 'dispatcherLeaseOwner' AND ownerMeta.value = :leaseOwner
      )
      AND EXISTS (
        SELECT 1 FROM app_metadata AS genMeta
        WHERE genMeta.`key` = 'dispatcherLeaseGeneration' AND genMeta.value = :leaseGenerationStr
      )
      AND EXISTS (
        SELECT 1 FROM app_metadata AS untilMeta
        WHERE untilMeta.`key` = 'dispatcherLeaseUntilEpochMs' AND CAST(untilMeta.value AS INTEGER) > :nowEpochMs
      )
    """
  )
  suspend fun commitClaimedFenced(
    id: String,
    leaseOwner: String,
    leaseGeneration: Long,
    leaseGenerationStr: String,
    nowEpochMs: Long,
  ): Int

  @Query(
    """
    UPDATE alarm_sync_outbox
    SET status = 'PENDING',
        claimedLeaseGeneration = NULL,
        claimedAtEpochMs = NULL,
        attemptCount = attemptCount + 1,
        lastAttemptAtEpochMs = :nowEpochMs,
        failureReason = :failureReason
    WHERE id = :id
      AND ownerType = 'TASK'
      AND status = 'CLAIMED'
      AND claimedLeaseGeneration = :leaseGeneration
      AND EXISTS (
        SELECT 1 FROM app_metadata AS ownerMeta
        WHERE ownerMeta.`key` = 'dispatcherLeaseOwner' AND ownerMeta.value = :leaseOwner
      )
      AND EXISTS (
        SELECT 1 FROM app_metadata AS genMeta
        WHERE genMeta.`key` = 'dispatcherLeaseGeneration' AND genMeta.value = :leaseGenerationStr
      )
    """
  )
  suspend fun returnClaimedToPendingForRetryFenced(
    id: String,
    leaseOwner: String,
    leaseGeneration: Long,
    leaseGenerationStr: String,
    nowEpochMs: Long,
    failureReason: String,
  ): Int

  @Query(
    """
    UPDATE alarm_sync_outbox
    SET status = 'FAILED_PERMANENT',
        claimedLeaseGeneration = NULL,
        claimedAtEpochMs = NULL,
        attemptCount = attemptCount + 1,
        lastAttemptAtEpochMs = :nowEpochMs,
        failureReason = :failureReason
    WHERE id = :id
      AND ownerType = 'TASK'
      AND status = 'CLAIMED'
      AND claimedLeaseGeneration = :leaseGeneration
      AND EXISTS (
        SELECT 1 FROM app_metadata AS ownerMeta
        WHERE ownerMeta.`key` = 'dispatcherLeaseOwner' AND ownerMeta.value = :leaseOwner
      )
      AND EXISTS (
        SELECT 1 FROM app_metadata AS genMeta
        WHERE genMeta.`key` = 'dispatcherLeaseGeneration' AND genMeta.value = :leaseGenerationStr
      )
    """
  )
  suspend fun markClaimedFailedPermanentFenced(
    id: String,
    leaseOwner: String,
    leaseGeneration: Long,
    leaseGenerationStr: String,
    nowEpochMs: Long,
    failureReason: String,
  ): Int

  @Query(
    """
    UPDATE alarm_sync_outbox
    SET status = 'SUPERSEDED',
        claimedLeaseGeneration = NULL,
        claimedAtEpochMs = NULL,
        lastAttemptAtEpochMs = :nowEpochMs,
        failureReason = :failureReason
    WHERE id = :id
      AND ownerType = 'TASK'
      AND status = 'CLAIMED'
      AND claimedLeaseGeneration = :leaseGeneration
      AND EXISTS (
        SELECT 1 FROM app_metadata AS ownerMeta
        WHERE ownerMeta.`key` = 'dispatcherLeaseOwner' AND ownerMeta.value = :leaseOwner
      )
      AND EXISTS (
        SELECT 1 FROM app_metadata AS genMeta
        WHERE genMeta.`key` = 'dispatcherLeaseGeneration' AND genMeta.value = :leaseGenerationStr
      )
    """
  )
  suspend fun markClaimedSupersededFenced(
    id: String,
    leaseOwner: String,
    leaseGeneration: Long,
    leaseGenerationStr: String,
    nowEpochMs: Long,
    failureReason: String?,
  ): Int

  @Query(
    """
    SELECT EXISTS(
      SELECT 1 FROM alarm_sync_outbox
      WHERE ownerType = 'TASK'
        AND systemTarget = 'ALARM_MANAGER'
        AND pendingIntentIdentityKey = :pendingIntentIdentityKey
        AND seq > :currentSeq
        AND status IN ('PENDING', 'CLAIMED', 'SUCCEEDED')
    )
    """
  )
  suspend fun hasNewerAlarmCommand(
    pendingIntentIdentityKey: String,
    currentSeq: Long,
  ): Boolean

  @Query(
    """
    SELECT EXISTS(
      SELECT 1 FROM alarm_sync_outbox
      WHERE ownerType = 'TASK'
        AND systemTarget = 'NOTIFICATION_MANAGER'
        AND notificationTag = :notificationTag
        AND notificationId = :notificationId
        AND seq > :currentSeq
        AND status IN ('PENDING', 'CLAIMED', 'SUCCEEDED')
    )
    """
  )
  suspend fun hasNewerNotificationCommand(
    notificationTag: String,
    notificationId: Int,
    currentSeq: Long,
  ): Boolean

  @Query(
    """
    SELECT EXISTS(
      SELECT 1 FROM alarm_sync_outbox
      WHERE ownerType = 'TASK'
        AND systemTarget = 'ALARM_MANAGER'
        AND action = 'SCHEDULE_ALARM'
        AND pendingIntentIdentityKey = :pendingIntentIdentityKey
        AND targetScheduleRevision = :targetScheduleRevision
        AND status = 'PENDING'
    )
    """
  )
  suspend fun hasPendingScheduleAlarmForRevision(
    pendingIntentIdentityKey: String,
    targetScheduleRevision: Long,
  ): Boolean

  @Query(
    """
    SELECT EXISTS(
      SELECT 1 FROM alarm_sync_outbox
      WHERE ownerType = 'TASK'
        AND systemTarget = 'ALARM_MANAGER'
        AND action = 'SCHEDULE_ALARM'
        AND pendingIntentIdentityKey = :pendingIntentIdentityKey
        AND targetScheduleRevision = :targetScheduleRevision
        AND status IN ('PENDING', 'CLAIMED')
    )
    """
  )
  suspend fun hasActiveScheduleAlarmForRevision(
    pendingIntentIdentityKey: String,
    targetScheduleRevision: Long,
  ): Boolean
}
