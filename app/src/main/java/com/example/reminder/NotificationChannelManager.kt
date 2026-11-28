package com.example.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.R
import com.example.core.i18n.createLocalizedContext
import com.example.core.preferences.AppLanguage

sealed interface NotificationGateStatus {
  data object Ready : NotificationGateStatus

  data class SuppressedPermission(
    val reason: String,
    val missingRuntimePermission: Boolean,
    val appNotificationsDisabled: Boolean,
  ) : NotificationGateStatus

  data class SuppressedChannel(
    val channelId: String,
    val reason: String,
    val channelMissing: Boolean,
  ) : NotificationGateStatus
}

class NotificationChannelManager(
  private val context: Context,
) {
  fun ensureChannelsCreated(language: AppLanguage? = null) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    val localizedContext = language?.let { context.createLocalizedContext(it) } ?: context
    val channelName = localizedContext.getString(R.string.notification_channel_task_reminders_name)
    val channelDescription = localizedContext.getString(R.string.notification_channel_task_reminders_desc)

    val existing = manager.getNotificationChannel(CHANNEL_TASK_REMINDERS)
    if (existing == null) {
      val channel =
        NotificationChannel(
          CHANNEL_TASK_REMINDERS,
          channelName,
          DEFAULT_TASK_REMINDERS_IMPORTANCE,
        ).apply {
          description = channelDescription
          enableVibration(true)
          setShowBadge(true)
        }
      manager.createNotificationChannel(channel)
    } else if (existing.name?.toString() != channelName || existing.description != channelDescription) {
      // Update localized name/description without ever deleting or altering user-controlled settings.
      val updatedMetadataOnly =
        NotificationChannel(
          CHANNEL_TASK_REMINDERS,
          channelName,
          existing.importance,
        ).apply {
          description = channelDescription
        }
      manager.createNotificationChannel(updatedMetadataOnly)
    }
  }

  fun hasPostNotificationsPermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
      ) == PackageManager.PERMISSION_GRANTED
    } else {
      true
    }
  }

  fun areAppNotificationsEnabled(): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

  fun isChannelEnabled(channelId: String = CHANNEL_TASK_REMINDERS): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    val channel = manager.getNotificationChannel(channelId) ?: return false
    return channel.importance != NotificationManager.IMPORTANCE_NONE
  }

  fun inspectDeliveryGate(channelId: String = CHANNEL_TASK_REMINDERS): NotificationGateStatus {
    val runtimeGranted = hasPostNotificationsPermission()
    val appEnabled = areAppNotificationsEnabled()
    if (!runtimeGranted || !appEnabled) {
      val reason =
        when {
          !runtimeGranted && !appEnabled ->
            "POST_NOTIFICATIONS denied and app notifications disabled"
          !runtimeGranted -> "POST_NOTIFICATIONS permission denied"
          else -> "App notifications disabled by user"
        }
      return NotificationGateStatus.SuppressedPermission(
        reason = reason,
        missingRuntimePermission = !runtimeGranted,
        appNotificationsDisabled = !appEnabled,
      )
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val manager = context.getSystemService(NotificationManager::class.java)
      val channel = manager?.getNotificationChannel(channelId)
      if (channel == null) {
        return NotificationGateStatus.SuppressedChannel(
          channelId = channelId,
          reason = "Notification channel '$channelId' does not exist",
          channelMissing = true,
        )
      }
      if (channel.importance == NotificationManager.IMPORTANCE_NONE) {
        return NotificationGateStatus.SuppressedChannel(
          channelId = channelId,
          reason = "Notification channel '$channelId' is disabled (IMPORTANCE_NONE)",
          channelMissing = false,
        )
      }
    }

    return NotificationGateStatus.Ready
  }

  companion object {
    const val CHANNEL_TASK_REMINDERS = "yawmi_task_reminders"
    const val DEFAULT_TASK_REMINDERS_IMPORTANCE = NotificationManager.IMPORTANCE_HIGH
  }
}
