package com.example.reminder

import androidx.room.withTransaction
import com.example.core.time.LocalClock
import com.example.core.time.SystemLocalClock
import com.example.data.local.dao.AlarmSyncOutboxDao
import com.example.data.local.dao.AppMetadataDao
import com.example.data.local.db.YawmiDatabase
import com.example.data.local.db.YawmiMetadataKeys
import com.example.data.local.entity.AppMetadataEntity

data class LeaseToken(
  val ownerId: String,
  val generation: Long,
  val untilEpochMs: Long,
)

sealed interface LeaseAcquireResult {
  data class Acquired(
    val token: LeaseToken,
    val recoveredStaleClaimedCount: Int,
    val tookOverExpiredOrUnowned: Boolean,
  ) : LeaseAcquireResult

  data class HeldByOther(
    val currentOwner: String,
    val currentGeneration: Long,
    val untilEpochMs: Long,
  ) : LeaseAcquireResult
}

class DispatcherLeaseManager(
  private val database: YawmiDatabase,
  private val appMetadataDao: AppMetadataDao,
  private val alarmSyncOutboxDao: AlarmSyncOutboxDao,
  private val localClock: LocalClock = SystemLocalClock(),
  private val defaultLeaseDurationMs: Long = DEFAULT_LEASE_DURATION_MS,
  private val renewThresholdMs: Long = DEFAULT_RENEW_THRESHOLD_MS,
) {

  suspend fun acquireOrRenewLease(
    ownerId: String,
    leaseDurationMs: Long = defaultLeaseDurationMs,
  ): LeaseAcquireResult = database.withTransaction {
    require(ownerId.isNotBlank()) { "workerOwnerId must not be blank" }
    val now = localClock.currentEpochMs()
    val currentOwner = appMetadataDao.getValue(YawmiMetadataKeys.DISPATCHER_LEASE_OWNER).orEmpty()
    val currentUntil =
      appMetadataDao.getValue(YawmiMetadataKeys.DISPATCHER_LEASE_UNTIL_EPOCH_MS)?.toLongOrNull() ?: 0L
    val currentGeneration =
      appMetadataDao.getValue(YawmiMetadataKeys.DISPATCHER_LEASE_GENERATION)?.toLongOrNull() ?: 0L

    when {
      currentOwner == ownerId && currentUntil > now -> {
        val renewedUntil = now + leaseDurationMs
        appMetadataDao.put(
          AppMetadataEntity(
            key = YawmiMetadataKeys.DISPATCHER_LEASE_UNTIL_EPOCH_MS,
            value = renewedUntil.toString(),
          )
        )
        LeaseAcquireResult.Acquired(
          token = LeaseToken(ownerId = ownerId, generation = currentGeneration, untilEpochMs = renewedUntil),
          recoveredStaleClaimedCount = 0,
          tookOverExpiredOrUnowned = false,
        )
      }

      currentOwner.isNotEmpty() && currentOwner != ownerId && currentUntil > now -> {
        LeaseAcquireResult.HeldByOther(
          currentOwner = currentOwner,
          currentGeneration = currentGeneration,
          untilEpochMs = currentUntil,
        )
      }

      else -> {
        val nextGeneration = currentGeneration + 1L
        val nextUntil = now + leaseDurationMs
        appMetadataDao.put(
          AppMetadataEntity(
            key = YawmiMetadataKeys.DISPATCHER_LEASE_OWNER,
            value = ownerId,
          )
        )
        appMetadataDao.put(
          AppMetadataEntity(
            key = YawmiMetadataKeys.DISPATCHER_LEASE_UNTIL_EPOCH_MS,
            value = nextUntil.toString(),
          )
        )
        appMetadataDao.put(
          AppMetadataEntity(
            key = YawmiMetadataKeys.DISPATCHER_LEASE_GENERATION,
            value = nextGeneration.toString(),
          )
        )
        val recovered = alarmSyncOutboxDao.recoverStaleClaimedBeforeGeneration(nextGeneration)
        LeaseAcquireResult.Acquired(
          token = LeaseToken(ownerId = ownerId, generation = nextGeneration, untilEpochMs = nextUntil),
          recoveredStaleClaimedCount = recovered,
          tookOverExpiredOrUnowned = true,
        )
      }
    }
  }

  suspend fun renewIfNeeded(
    token: LeaseToken,
    leaseDurationMs: Long = defaultLeaseDurationMs,
  ): LeaseToken? {
    val now = localClock.currentEpochMs()
    if (token.untilEpochMs - now > renewThresholdMs) {
      return token
    }
    return database.withTransaction {
      val currentNow = localClock.currentEpochMs()
      val currentOwner = appMetadataDao.getValue(YawmiMetadataKeys.DISPATCHER_LEASE_OWNER).orEmpty()
      val currentUntil =
        appMetadataDao.getValue(YawmiMetadataKeys.DISPATCHER_LEASE_UNTIL_EPOCH_MS)?.toLongOrNull() ?: 0L
      val currentGeneration =
        appMetadataDao.getValue(YawmiMetadataKeys.DISPATCHER_LEASE_GENERATION)?.toLongOrNull() ?: 0L
      if (currentOwner == token.ownerId && currentGeneration == token.generation && currentUntil > currentNow) {
        val nextUntil = currentNow + leaseDurationMs
        appMetadataDao.put(
          AppMetadataEntity(
            key = YawmiMetadataKeys.DISPATCHER_LEASE_UNTIL_EPOCH_MS,
            value = nextUntil.toString(),
          )
        )
        token.copy(untilEpochMs = nextUntil)
      } else {
        null
      }
    }
  }

  /**
   * Conditionally releases the lease only if [app_metadata] still belongs to the exact
   * (ownerId, generation) pair. Preserves [YawmiMetadataKeys.DISPATCHER_LEASE_GENERATION] monotonically.
   */
  suspend fun releaseLease(token: LeaseToken): Boolean = database.withTransaction {
    val currentOwner = appMetadataDao.getValue(YawmiMetadataKeys.DISPATCHER_LEASE_OWNER).orEmpty()
    val currentGeneration =
      appMetadataDao.getValue(YawmiMetadataKeys.DISPATCHER_LEASE_GENERATION)?.toLongOrNull() ?: 0L
    if (currentOwner == token.ownerId && currentGeneration == token.generation) {
      appMetadataDao.put(
        AppMetadataEntity(
          key = YawmiMetadataKeys.DISPATCHER_LEASE_OWNER,
          value = "",
        )
      )
      appMetadataDao.put(
        AppMetadataEntity(
          key = YawmiMetadataKeys.DISPATCHER_LEASE_UNTIL_EPOCH_MS,
          value = "0",
        )
      )
      true
    } else {
      false
    }
  }

  companion object {
    const val DEFAULT_LEASE_DURATION_MS = 120_000L
    const val DEFAULT_RENEW_THRESHOLD_MS = 30_000L
  }
}
