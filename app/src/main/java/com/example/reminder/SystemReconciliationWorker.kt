package com.example.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.YawmiApplication
import kotlinx.coroutines.CancellationException

class SystemReconciliationWorker(
  appContext: Context,
  params: WorkerParameters,
  private val reminderReconciler: ReminderReconciler,
) : CoroutineWorker(appContext, params) {

  constructor(
    appContext: Context,
    params: WorkerParameters,
  ) : this(
    appContext = appContext,
    params = params,
    reminderReconciler = (appContext.applicationContext as YawmiApplication).resolveReminderReconciler(),
  )

  override suspend fun doWork(): Result {
    val reasonName = inputData.getString(KEY_REASON)
    val reason =
      reasonName?.let { runCatching { ReconcileTriggerReason.valueOf(it) }.getOrNull() }
        ?: ReconcileTriggerReason.MANUAL
    val forceReschedule = inputData.getBoolean(KEY_FORCE_RESCHEDULE, false)

    return try {
      val summary =
        reminderReconciler.reconcile(
          reason = reason,
          forceRescheduleOverride = forceReschedule,
        )
      if (summary.skippedDueToLockedStorage) {
        Result.retry()
      } else {
        Result.success()
      }
    } catch (c: CancellationException) {
      throw c
    } catch (_: Throwable) {
      Result.retry()
    }
  }

  companion object {
    const val KEY_REASON = "reconcile_reason"
    const val KEY_FORCE_RESCHEDULE = "force_reschedule"
  }
}
