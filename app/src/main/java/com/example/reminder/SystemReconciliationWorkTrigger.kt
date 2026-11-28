package com.example.reminder

import android.content.Context
import androidx.core.os.UserManagerCompat
import androidx.room.withTransaction
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.local.db.YawmiDatabase
import com.example.data.local.db.YawmiMetadataKeys
import com.example.data.local.entity.AppMetadataEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface SystemReconciliationTrigger {
  suspend fun requestReconcileNow(reason: ReconcileTriggerReason)

  fun requestReconcileAsync(reason: ReconcileTriggerReason)

  fun requestReconcileIfNeededOnResumeAsync()

  companion object {
    val NoOp: SystemReconciliationTrigger =
      object : SystemReconciliationTrigger {
        override suspend fun requestReconcileNow(reason: ReconcileTriggerReason) = Unit

        override fun requestReconcileAsync(reason: ReconcileTriggerReason) = Unit

        override fun requestReconcileIfNeededOnResumeAsync() = Unit
      }
  }
}

class WorkManagerSystemReconciliationTrigger(
  private val appContext: Context,
  private val databaseProvider: () -> YawmiDatabase,
  private val isReconcileNeededOnResumeProvider: suspend () -> Boolean,
  private val hasActiveOutboxQueueProvider: suspend () -> Boolean,
  private val outboxDrainTriggerProvider: () -> OutboxDrainTrigger,
  private val asyncScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
  private val isUserUnlockedProvider: () -> Boolean = { UserManagerCompat.isUserUnlocked(appContext) },
) : SystemReconciliationTrigger {

  private val coordinationMutex = Mutex()

  override suspend fun requestReconcileNow(reason: ReconcileTriggerReason) =
    withContext(NonCancellable + Dispatchers.IO) {
      if (reason.requiresForceReschedule() && isUserUnlockedProvider()) {
        recordForceRescheduleRequestLocked()
      }
      val workManager = resolveWorkManagerOrNull() ?: return@withContext
      coordinationMutex.withLock {
        val existingInfos = workManager.getWorkInfosForUniqueWorkFlow(RECONCILE_WORK_NAME).first()
        val hasPendingFollower =
          existingInfos.any {
            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
          }
        if (!hasPendingFollower) {
          val inputData =
            workDataOf(
              SystemReconciliationWorker.KEY_REASON to reason.name,
              SystemReconciliationWorker.KEY_FORCE_RESCHEDULE to reason.requiresForceReschedule(),
            )
          val request =
            OneTimeWorkRequestBuilder<SystemReconciliationWorker>()
              .setInputData(inputData)
              .build()
          workManager
            .enqueueUniqueWork(
              RECONCILE_WORK_NAME,
              ExistingWorkPolicy.APPEND_OR_REPLACE,
              request,
            )
            .result
            .awaitNonBlocking()
        }
      }
    }

  override fun requestReconcileAsync(reason: ReconcileTriggerReason) {
    asyncScope.launch {
      requestReconcileNow(reason)
    }
  }

  override fun requestReconcileIfNeededOnResumeAsync() {
    asyncScope.launch {
      if (!isUserUnlockedProvider()) return@launch
      if (isReconcileNeededOnResumeProvider()) {
        requestReconcileNow(ReconcileTriggerReason.APP_RESUME_CHECK)
      } else if (hasActiveOutboxQueueProvider()) {
        outboxDrainTriggerProvider().requestDrainNow()
      }
    }
  }

  private suspend fun recordForceRescheduleRequestLocked() {
    val db = databaseProvider()
    val metadataDao = db.appMetadataDao()
    db.withTransaction {
      val currentSeq =
        metadataDao.getValue(YawmiMetadataKeys.RECONCILE_FORCE_REQUEST_SEQ)?.toLongOrNull() ?: 0L
      metadataDao.put(
        AppMetadataEntity(
          key = YawmiMetadataKeys.RECONCILE_FORCE_REQUEST_SEQ,
          value = (currentSeq + 1L).toString(),
        )
      )
    }
  }

  private fun resolveWorkManagerOrNull(): WorkManager? {
    return try {
      WorkManager.getInstance(appContext)
    } catch (_: IllegalStateException) {
      null
    }
  }

  companion object {
    const val RECONCILE_WORK_NAME = "yawmi_system_reconcile"
  }
}
