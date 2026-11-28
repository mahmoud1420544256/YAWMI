package com.example.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface OutboxDrainTrigger {
  suspend fun requestDrainNow()

  fun requestDrainAsync()

  suspend fun scheduleRetry(delayMs: Long, observedDrainEpoch: Long)

  suspend fun currentDrainEpoch(): Long

  companion object {
    val NoOp: OutboxDrainTrigger =
      object : OutboxDrainTrigger {
        override suspend fun requestDrainNow() = Unit

        override fun requestDrainAsync() = Unit

        override suspend fun scheduleRetry(delayMs: Long, observedDrainEpoch: Long) = Unit

        override suspend fun currentDrainEpoch(): Long = 0L
      }
  }
}

class WorkManagerOutboxTrigger(
  private val appContext: Context,
  private val asyncScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : OutboxDrainTrigger {

  private val coordinationMutex = Mutex()
  private var drainEpoch: Long = 0L

  override suspend fun currentDrainEpoch(): Long = coordinationMutex.withLock { drainEpoch }

  override suspend fun requestDrainNow() = withContext(NonCancellable) {
    val workManager = resolveWorkManagerOrNull() ?: return@withContext
    coordinationMutex.withLock {
      drainEpoch += 1L

      val drainInfos = workManager.getWorkInfosForUniqueWorkFlow(DRAIN_WORK_NAME).first()
      val hasPendingFollower =
        drainInfos.any {
          it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
        }

      if (!hasPendingFollower) {
        val request = OneTimeWorkRequestBuilder<OutboxDispatcherWorker>().build()
        workManager
          .enqueueUniqueWork(DRAIN_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
          .result
          .awaitNonBlocking()
      }

      val retryInfos = workManager.getWorkInfosForUniqueWorkFlow(RETRY_WORK_NAME).first()
      val hasPendingRetry =
        retryInfos.any {
          it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
        }
      if (hasPendingRetry) {
        workManager.cancelUniqueWork(RETRY_WORK_NAME).result.awaitNonBlocking()
      }
    }
  }

  override fun requestDrainAsync() {
    asyncScope.launch {
      requestDrainNow()
    }
  }

  override suspend fun scheduleRetry(delayMs: Long, observedDrainEpoch: Long) =
    withContext(NonCancellable) {
      val workManager = resolveWorkManagerOrNull() ?: return@withContext
      coordinationMutex.withLock {
        if (drainEpoch != observedDrainEpoch) {
          return@withLock
        }

        val drainInfos = workManager.getWorkInfosForUniqueWorkFlow(DRAIN_WORK_NAME).first()
        val hasPendingFollower =
          drainInfos.any {
            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
          }
        if (hasPendingFollower) {
          return@withLock
        }

        val retryRequest =
          OneTimeWorkRequestBuilder<OutboxRetryRelayWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            .build()
        workManager
          .enqueueUniqueWork(RETRY_WORK_NAME, ExistingWorkPolicy.REPLACE, retryRequest)
          .result
          .awaitNonBlocking()
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
    const val DRAIN_WORK_NAME = "yawmi_alarm_outbox_drain"
    const val RETRY_WORK_NAME = "yawmi_alarm_outbox_retry"
  }
}

class OutboxDispatcherWorker(
  appContext: Context,
  params: WorkerParameters,
  private val dispatcher: AlarmOutboxDispatcher,
  private val outboxDrainTrigger: OutboxDrainTrigger,
  private val hasActiveQueue: suspend () -> Boolean,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    val workerOwnerId = id.toString()
    while (true) {
      val epochBeforeDrain = outboxDrainTrigger.currentDrainEpoch()
      when (val outcome = dispatcher.drain(workerOwnerId = workerOwnerId)) {
        is DrainOutcome.Completed -> {
          if (hasActiveQueue()) {
            continue
          }
          return Result.success()
        }

        is DrainOutcome.BackoffScheduled -> {
          outboxDrainTrigger.scheduleRetry(
            delayMs = outcome.delayMs,
            observedDrainEpoch = epochBeforeDrain,
          )
          return Result.success()
        }

        is DrainOutcome.LeaseHeldByOther,
        is DrainOutcome.RetryableError -> {
          return Result.retry()
        }
      }
    }
  }
}

class OutboxRetryRelayWorker(
  appContext: Context,
  params: WorkerParameters,
  private val outboxDrainTrigger: OutboxDrainTrigger,
) : CoroutineWorker(appContext, params) {
  override suspend fun doWork(): Result {
    outboxDrainTrigger.requestDrainNow()
    return Result.success()
  }
}

class YawmiWorkerFactory(
  private val dispatcherProvider: () -> AlarmOutboxDispatcher,
  private val drainTriggerProvider: () -> OutboxDrainTrigger,
  private val hasActiveQueueProvider: suspend () -> Boolean,
  private val reconcilerProvider: (() -> ReminderReconciler)? = null,
) : WorkerFactory() {
  override fun createWorker(
    appContext: Context,
    workerClassName: String,
    workerParameters: WorkerParameters,
  ): androidx.work.ListenableWorker? {
    return when (workerClassName) {
      OutboxDispatcherWorker::class.java.name ->
        OutboxDispatcherWorker(
          appContext = appContext,
          params = workerParameters,
          dispatcher = dispatcherProvider(),
          outboxDrainTrigger = drainTriggerProvider(),
          hasActiveQueue = hasActiveQueueProvider,
        )
      OutboxRetryRelayWorker::class.java.name ->
        OutboxRetryRelayWorker(
          appContext = appContext,
          params = workerParameters,
          outboxDrainTrigger = drainTriggerProvider(),
        )
      SystemReconciliationWorker::class.java.name ->
        reconcilerProvider?.let { provider ->
          SystemReconciliationWorker(
            appContext = appContext,
            params = workerParameters,
            reminderReconciler = provider(),
          )
        }
      else -> null
    }
  }
}

internal suspend fun <T> ListenableFuture<T>.awaitNonBlocking(): T =
  suspendCancellableCoroutine { cont ->
    addListener(
      {
        try {
          cont.resume(get())
        } catch (e: ExecutionException) {
          cont.resumeWithException(e.cause ?: e)
        } catch (e: Throwable) {
          cont.resumeWithException(e)
        }
      },
      Executor { runnable -> runnable.run() },
    )
    cont.invokeOnCancellation { cancel(false) }
  }
