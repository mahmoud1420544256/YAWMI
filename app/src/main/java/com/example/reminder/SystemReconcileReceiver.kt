package com.example.reminder

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.os.UserManagerCompat
import com.example.YawmiApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SystemReconcileReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    val reason = mapIntentToReason(intent) ?: return
    if (!UserManagerCompat.isUserUnlocked(context)) {
      return
    }
    val trigger = resolveTrigger(context) ?: return
    val pendingResult: PendingResult? = goAsync()
    backgroundScope.launch {
      try {
        trigger.requestReconcileNow(reason)
      } finally {
        pendingResult?.finish()
      }
    }
  }

  private fun resolveTrigger(context: Context): SystemReconciliationTrigger? {
    triggerOverrideForTesting?.let { return it }
    val app = context.applicationContext as? YawmiApplication ?: return null
    return app.resolveSystemReconciliationTrigger()
  }

  companion object {
    internal var backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    internal var triggerOverrideForTesting: SystemReconciliationTrigger? = null

    fun mapIntentToReason(intent: Intent?): ReconcileTriggerReason? {
      return when (intent?.action) {
        Intent.ACTION_BOOT_COMPLETED -> ReconcileTriggerReason.BOOT_COMPLETED
        Intent.ACTION_MY_PACKAGE_REPLACED -> ReconcileTriggerReason.MY_PACKAGE_REPLACED
        Intent.ACTION_TIME_CHANGED -> ReconcileTriggerReason.TIME_CHANGED
        Intent.ACTION_TIMEZONE_CHANGED -> ReconcileTriggerReason.TIMEZONE_CHANGED
        Intent.ACTION_DATE_CHANGED -> ReconcileTriggerReason.DATE_CHANGED
        AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ->
          ReconcileTriggerReason.EXACT_ALARM_PERMISSION_GRANTED
        else -> null
      }
    }
  }
}
