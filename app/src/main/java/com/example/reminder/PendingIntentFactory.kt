package com.example.reminder

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.example.data.local.entity.AlarmSyncOutboxEntity

class PendingIntentFactory(
  private val context: Context,
) {
  fun buildIntent(entry: AlarmSyncOutboxEntity): Intent {
    return Intent(entry.actionName).apply {
      component = ComponentName(entry.packageName, entry.component)
      val uri = Uri.parse(entry.dataUri)
      if (!entry.mimeType.isNullOrBlank()) {
        setDataAndType(uri, entry.mimeType)
      } else {
        data = uri
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !entry.intentIdentifier.isNullOrBlank()) {
        identifier = entry.intentIdentifier
      }
      entry.categoriesCanonical
        .split(',')
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { addCategory(it) }
      putExtra(EXTRA_REMINDER_ID, entry.reminderId)
      putExtra(EXTRA_OWNER_TYPE, entry.ownerType.name)
      putExtra(EXTRA_REQUEST_CODE, entry.requestCode)
      putExtra(EXTRA_OUTBOX_SEQ, entry.seq)
      entry.targetScheduleRevision?.let { putExtra(EXTRA_SCHEDULE_REVISION, it) }
    }
  }

  /**
   * Creates or updates the system PendingIntent for scheduling an alarm.
   * Never combines [PendingIntent.FLAG_NO_CREATE] with creation/update flags.
   */
  fun createOrUpdateAlarmPendingIntent(entry: AlarmSyncOutboxEntity): PendingIntent {
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    return PendingIntent.getBroadcast(
      context,
      entry.requestCode,
      buildIntent(entry),
      flags,
    )
  }

  /**
   * Looks up an existing system PendingIntent for cancellation without creating a new one.
   * Returns null if no matching PendingIntent exists.
   */
  fun findExistingAlarmPendingIntent(entry: AlarmSyncOutboxEntity): PendingIntent? {
    val flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    return PendingIntent.getBroadcast(
      context,
      entry.requestCode,
      buildIntent(entry),
      flags,
    )
  }

  companion object {
    const val EXTRA_REMINDER_ID = "com.yawmi.productivity.extra.REMINDER_ID"
    const val EXTRA_OWNER_TYPE = "com.yawmi.productivity.extra.OWNER_TYPE"
    const val EXTRA_REQUEST_CODE = "com.yawmi.productivity.extra.REQUEST_CODE"
    const val EXTRA_SCHEDULE_REVISION = "com.yawmi.productivity.extra.SCHEDULE_REVISION"
    const val EXTRA_OUTBOX_SEQ = "com.yawmi.productivity.extra.OUTBOX_SEQ"
  }
}
