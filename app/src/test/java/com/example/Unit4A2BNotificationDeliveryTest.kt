package com.example

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.core.di.DefaultAppContainer
import com.example.core.preferences.AppLanguage
import com.example.core.time.GreetingPeriod
import com.example.core.time.LocalClock
import com.example.data.local.db.PendingIntentIdentitySnapshot
import com.example.data.local.db.YawmiDatabase
import com.example.data.local.entity.DeliveryState
import com.example.data.local.entity.OutboxAction
import com.example.data.local.entity.OutboxStatus
import com.example.data.local.entity.RecurrenceFrequency
import com.example.data.local.entity.ReminderOwnerType
import com.example.data.local.entity.ReminderScheduleState
import com.example.data.local.entity.TaskEntity
import com.example.data.local.entity.TaskReminderDeliveryEntity
import com.example.data.local.entity.TaskStatus
import com.example.domain.task.ReminderTriggerCalculator
import com.example.domain.task.TaskRecurrenceInput
import com.example.reminder.AlarmCancelOutcome
import com.example.reminder.AlarmFireHandler
import com.example.reminder.AlarmFireOutcome
import com.example.reminder.AlarmReceiver
import com.example.reminder.AlarmScheduleOutcome
import com.example.reminder.AlarmScheduler
import com.example.reminder.AndroidNotificationPoster
import com.example.reminder.DeliveryOutcome
import com.example.reminder.NotificationActionHandler
import com.example.reminder.NotificationActionOutcome
import com.example.reminder.NotificationActionReceiver
import com.example.reminder.NotificationChannelManager
import com.example.reminder.NotificationDeliveryEngine
import com.example.reminder.NotificationDeliveryHooks
import com.example.reminder.NotificationPendingIntentFactory
import com.example.reminder.NotificationPostOutcome
import com.example.reminder.NotificationPoster
import com.example.reminder.OutboxDrainTrigger
import com.example.reminder.PendingIntentFactory
import com.example.reminder.WorkManagerOutboxTrigger
import com.example.reminder.YawmiWorkerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

private class Controllable4A2BClock(
  initialEpochMs: Long = Instant.parse("2026-11-27T09:30:00Z").toEpochMilli(),
  private val zone: ZoneId = ZoneId.of("UTC"),
) : LocalClock {
  var epochMs: Long = initialEpochMs

  override fun currentEpochMs(): Long = epochMs

  override fun currentDate(): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

  override fun currentTime(): LocalTime = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime()

  override fun greetingPeriod(): GreetingPeriod = GreetingPeriod.MORNING

  override fun zoneId(): ZoneId = zone
}

private class Recording4A2BAlarmScheduler : AlarmScheduler {
  val scheduledCalls = mutableListOf<Pair<String, Long>>()
  val cancelledIds = mutableListOf<String>()

  override fun canScheduleExactAlarms(): Boolean = true

  override fun scheduleAlarm(
    entry: com.example.data.local.entity.AlarmSyncOutboxEntity,
    triggerAtEpochMs: Long,
  ): AlarmScheduleOutcome {
    scheduledCalls += entry.reminderId to triggerAtEpochMs
    return AlarmScheduleOutcome.ScheduledExact(triggerAtEpochMs)
  }

  override fun cancelAlarm(
    entry: com.example.data.local.entity.AlarmSyncOutboxEntity,
  ): AlarmCancelOutcome {
    cancelledIds += entry.reminderId
    return AlarmCancelOutcome.Cancelled
  }
}

private class SpyNotificationPoster(
  private val delegate: NotificationPoster,
) : NotificationPoster {
  data class PostedCall(val tag: String, val id: Int, val notification: Notification)

  val postedCalls = mutableListOf<PostedCall>()

  override fun postNotification(
    tag: String,
    id: Int,
    notification: Notification,
  ): NotificationPostOutcome {
    postedCalls += PostedCall(tag, id, notification)
    return delegate.postNotification(tag, id, notification)
  }

  override fun isNotificationActive(tag: String, id: Int): Boolean =
    delegate.isNotificationActive(tag, id)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Unit4A2BNotificationDeliveryTest {

  private lateinit var context: Context
  private lateinit var app: Application
  private lateinit var notificationManager: NotificationManager
  private lateinit var db: YawmiDatabase
  private lateinit var clock: Controllable4A2BClock
  private lateinit var alarmScheduler: Recording4A2BAlarmScheduler
  private lateinit var spyPoster: SpyNotificationPoster

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    app = context as Application
    notificationManager = context.getSystemService(NotificationManager::class.java)
    db = YawmiDatabase.inMemory(context)
    clock = Controllable4A2BClock()
    alarmScheduler = Recording4A2BAlarmScheduler()
    spyPoster = SpyNotificationPoster(AndroidNotificationPoster(context))

    Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    Shadows.shadowOf(notificationManager).setNotificationsEnabled(true)
    NotificationChannelManager(context).ensureChannelsCreated(AppLanguage.ENGLISH)
  }

  @After
  fun tearDown() {
    AlarmReceiver.handlerOverrideForTesting = null
    NotificationActionReceiver.handlerOverrideForTesting = null
    db.close()
  }

  private fun newContainer(
    trigger: OutboxDrainTrigger = OutboxDrainTrigger.NoOp,
  ): DefaultAppContainer {
    return DefaultAppContainer(
      context = context,
      databaseProvider = { db },
      localClockProvider = { clock },
      alarmSchedulerProvider = { _, _ -> alarmScheduler },
      outboxDrainTriggerProvider = { trigger },
      notificationPosterProvider = { spyPoster },
    )
  }

  private suspend fun seedTaskAndReminder(
    container: DefaultAppContainer,
    taskId: String = "task-1",
    title: String = "Morning Standup",
    description: String? = "Sync with team",
    scheduledDate: String = "2026-11-27",
    startTime: String? = "09:30",
    endTime: String? = "10:00",
    reminderId: String = "rem-1",
    triggerDate: String = "2026-11-27",
    triggerTime: String = "09:30",
    status: TaskStatus = TaskStatus.PENDING,
  ) {
    val now = clock.currentEpochMs()
    container.taskRepository.insert(
      TaskEntity(
        id = taskId,
        title = title,
        description = description,
        scheduledDate = scheduledDate,
        startTime = startTime,
        endTime = endTime,
        status = status,
        createdAtEpochMs = now,
        updatedAtEpochMs = now,
      )
    )
    container.reminderSyncRepository.createTaskReminder(
      reminderId = reminderId,
      taskId = taskId,
      triggerDate = triggerDate,
      triggerTime = triggerTime,
      offsetMinutes = 0,
      nowEpochMs = now,
    )
  }

  private fun buildFireIntent(
    reminderId: String = "rem-1",
    ownerType: String = ReminderOwnerType.TASK.name,
    revision: Long? = 1L,
    spoofTitle: String? = null,
    spoofTriggerTime: String? = null,
  ): Intent {
    return Intent(PendingIntentIdentitySnapshot.ACTION_FIRE_REMINDER).apply {
      setClassName(context, "com.example.reminder.AlarmReceiver")
      data = Uri.parse("yawmi://reminder/${ownerType.lowercase()}/$reminderId")
      putExtra(PendingIntentFactory.EXTRA_REMINDER_ID, reminderId)
      putExtra(PendingIntentFactory.EXTRA_OWNER_TYPE, ownerType)
      revision?.let { putExtra(PendingIntentFactory.EXTRA_SCHEDULE_REVISION, it) }
      spoofTitle?.let { putExtra("spoof_title", it) }
      spoofTriggerTime?.let { putExtra("spoof_time", it) }
    }
  }

  private fun awaitCondition(timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!predicate()) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      if (predicate()) return
      if (System.currentTimeMillis() >= deadline) {
        org.junit.Assert.fail("Timed out waiting for async receiver/work completion")
      }
      Thread.sleep(15)
    }
  }

  // =========================================================================
  // 1-2. Receive FIRE_REMINDER, Validate Room Source of Truth & POSTED_TO_SYSTEM
  // =========================================================================

  @Test
  fun alarmReceiver_receivesFireReminder_readsRoomAsSourceOfTruth_andRecordsPostedToSystem() =
    runBlocking<Unit> {
      val container = newContainer()
      container.appearanceRepository.setLanguage(AppLanguage.ENGLISH)
      seedTaskAndReminder(
        container = container,
        title = "Real Room Title",
        triggerTime = "09:30",
      )
      AlarmReceiver.handlerOverrideForTesting = container.alarmFireHandler

      // Non-TASK ownerType must be ignored even if reminderId matches
      val nonTaskOutcome =
        container.alarmFireHandler.handleIntent(
          buildFireIntent(reminderId = "rem-1", ownerType = ReminderOwnerType.HABIT.name)
        )
      assertTrue(nonTaskOutcome is AlarmFireOutcome.Ignored)
      assertEquals(0, spyPoster.postedCalls.size)

      // Dispatch via real AlarmReceiver.onReceive() with spoofed extras -> Room wins
      val fireIntent =
        buildFireIntent(
          reminderId = "rem-1",
          ownerType = ReminderOwnerType.TASK.name,
          revision = 1L,
          spoofTitle = "Spoofed Fake Title",
          spoofTriggerTime = "23:59",
        )
      AlarmReceiver().onReceive(context, fireIntent)

      val expectedKey =
        ReminderTriggerCalculator.buildOccurrenceKey("2026-11-27", "09:30", 1L, clock.zoneId())
      awaitCondition {
        runBlocking { db.reminderDeliveryDao().getTaskDelivery("rem-1", expectedKey) }?.state ==
          DeliveryState.POSTED_TO_SYSTEM
      }

      val delivery = db.reminderDeliveryDao().getTaskDelivery("rem-1", expectedKey)!!
      assertEquals(DeliveryState.POSTED_TO_SYSTEM, delivery.state)
      assertEquals(1, delivery.attemptCount)
      assertEquals(1L, delivery.firedScheduleRevision)
      assertEquals("yawmi:reminder:task:rem-1", delivery.notificationTag)
      assertEquals(PendingIntentIdentitySnapshot.DEFAULT_NOTIFICATION_ID, delivery.notificationId)

      assertEquals(1, spyPoster.postedCalls.size)
      val posted = spyPoster.postedCalls.single().notification
      assertEquals("Real Room Title", posted.extras.getString(NotificationCompat.EXTRA_TITLE))
      assertTrue(
        posted.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString().contains("09:30")
      )
      assertNotNull(posted.contentIntent)
      assertEquals(2, posted.actions?.size)
      assertEquals(0, posted.flags and Notification.FLAG_ONLY_ALERT_ONCE)
    }

  @Test
  fun alarmFire_rejectsDisabledReminder_inactiveTask_andMismatchedRevision() = runBlocking<Unit> {
    val container = newContainer()
    seedTaskAndReminder(container, taskId = "t-dis", reminderId = "r-dis")
    container.reminderSyncRepository.updateTaskReminderSchedule(
      reminderId = "r-dis",
      triggerDate = "2026-11-27",
      triggerTime = "09:30",
      offsetMinutes = 0,
      isEnabled = false,
      nowEpochMs = clock.currentEpochMs(),
    )
    val disabledOutcome =
      container.alarmFireHandler.handleIntent(buildFireIntent(reminderId = "r-dis", revision = 2L))
    assertTrue(
      disabledOutcome is AlarmFireOutcome.Delivered &&
        disabledOutcome.outcome is DeliveryOutcome.Skipped
    )

    seedTaskAndReminder(
      container,
      taskId = "t-done",
      reminderId = "r-done",
      status = TaskStatus.COMPLETED,
    )
    val inactiveTaskOutcome =
      container.alarmFireHandler.handleIntent(buildFireIntent(reminderId = "r-done", revision = 1L))
    assertTrue(
      inactiveTaskOutcome is AlarmFireOutcome.Delivered &&
        inactiveTaskOutcome.outcome is DeliveryOutcome.Skipped
    )

    seedTaskAndReminder(container, taskId = "t-rev", reminderId = "r-rev")
    container.reminderSyncRepository.updateTaskReminderSchedule(
      reminderId = "r-rev",
      triggerDate = "2026-11-27",
      triggerTime = "09:45",
      offsetMinutes = 0,
      isEnabled = true,
      nowEpochMs = clock.currentEpochMs(),
    )
    // Stale broadcast with revision=1 when Room is at revision=2
    val staleRevOutcome =
      container.alarmFireHandler.handleIntent(buildFireIntent(reminderId = "r-rev", revision = 1L))
    assertTrue(
      staleRevOutcome is AlarmFireOutcome.Delivered &&
        staleRevOutcome.outcome is DeliveryOutcome.Skipped
    )
    assertEquals(0, spyPoster.postedCalls.size)
  }

  // =========================================================================
  // 3 & 17. Prevent Duplicate occurrenceKey & Duplicate Broadcast Processing
  // =========================================================================

  @Test
  fun duplicateOccurrenceKey_andConcurrentDuplicateBroadcasts_neverPostTwice() = runBlocking<Unit> {
    val container = newContainer()
    container.appearanceRepository.setLanguage(AppLanguage.ENGLISH)
    seedTaskAndReminder(container)

    // Fire concurrently from multiple coroutines
    val outcomes = coroutineScope {
      (1..4)
        .map {
          async(Dispatchers.Default) {
            container.alarmFireHandler.handleIntent(buildFireIntent(reminderId = "rem-1", revision = 1L))
          }
        }
        .awaitAll()
    }

    val postedOutcomes =
      outcomes.filterIsInstance<AlarmFireOutcome.Delivered>().map { it.outcome }
    assertEquals(1, postedOutcomes.count { it is DeliveryOutcome.PostedToSystem })
    assertEquals(3, postedOutcomes.count { it is DeliveryOutcome.AlreadyHandled })
    assertEquals(1, spyPoster.postedCalls.size)

    val rows = db.reminderDeliveryDao().observeTaskDeliveries("rem-1").first()
    assertEquals(1, rows.size)
    assertEquals(DeliveryState.POSTED_TO_SYSTEM, rows.single().state)
  }

  // =========================================================================
  // 5-8. Crash After notify(), Recovery via activeNotifications & Absent Recovery, setOnlyAlertOnce
  // =========================================================================

  @Test
  fun crashAfterNotify_recoversFromActiveNotificationsWithoutReposting() = runBlocking<Unit> {
    val container = newContainer()
    container.appearanceRepository.setLanguage(AppLanguage.ENGLISH)
    seedTaskAndReminder(container)

    // Simulate process death right after NotificationManager.notify() succeeded before Room update
    val crashHooks =
      object : NotificationDeliveryHooks {
        override suspend fun onAfterNotifyBeforeCommit(delivery: TaskReminderDeliveryEntity) {
          throw CancellationException("Simulated crash before POSTED_TO_SYSTEM commit")
        }
      }

    try {
      container.notificationDeliveryEngine.deliverTaskReminderWithHooks(
        reminderId = "rem-1",
        expectedRevision = 1L,
        hooks = crashHooks,
      )
      org.junit.Assert.fail("Expected simulated CancellationException")
    } catch (_: CancellationException) {
      // expected crash
    }

    val occKey =
      ReminderTriggerCalculator.buildOccurrenceKey("2026-11-27", "09:30", 1L, clock.zoneId())
    val stuckRow = db.reminderDeliveryDao().getTaskDelivery("rem-1", occKey)!!
    assertEquals(DeliveryState.PENDING, stuckRow.state)
    assertEquals(1, stuckRow.attemptCount)
    assertEquals(1, spyPoster.postedCalls.size)
    assertTrue(
      notificationManager.activeNotifications.any {
        it.tag == "yawmi:reminder:task:rem-1" && it.id == 0
      }
    )

    // Startup crash recovery sees active notification in system tray -> upgrades to POSTED_TO_SYSTEM without notify()
    val summary = container.notificationDeliveryEngine.recoverPendingDeliveries()
    assertEquals(1, summary.inspectedCount)
    assertEquals(1, summary.recoveredFromSystemCount)
    assertEquals(0, summary.repostedCount)

    // No second notify() call occurred!
    assertEquals(1, spyPoster.postedCalls.size)
    val recovered = db.reminderDeliveryDao().getTaskDelivery("rem-1", occKey)!!
    assertEquals(DeliveryState.POSTED_TO_SYSTEM, recovered.state)
    assertEquals(1, recovered.attemptCount)
    assertEquals(1, db.reminderDeliveryDao().observeTaskDeliveries("rem-1").first().size)
  }

  @Test
  fun crashRecovery_whenNotificationAbsentInSystem_repostsWithSetOnlyAlertOnceTrue() =
    runBlocking<Unit> {
      val container = newContainer()
      container.appearanceRepository.setLanguage(AppLanguage.ENGLISH)
      seedTaskAndReminder(container)

      val occKey =
        ReminderTriggerCalculator.buildOccurrenceKey("2026-11-27", "09:30", 1L, clock.zoneId())
      // Seed an orphaned PENDING row (simulating crash BEFORE notify() was accepted by system)
      container.reminderSyncRepository.recordPendingTaskDelivery(
        deliveryId = "stuck-pending-1",
        taskReminderId = "rem-1",
        occurrenceKey = occKey,
        scheduledAtEpochMs = clock.currentEpochMs(),
        firedScheduleRevision = 1L,
        nowEpochMs = clock.currentEpochMs(),
      )
      assertFalse(
        notificationManager.activeNotifications.any { it.tag == "yawmi:reminder:task:rem-1" }
      )

      val summary = container.notificationDeliveryEngine.recoverPendingDeliveries()
      assertEquals(1, summary.inspectedCount)
      assertEquals(0, summary.recoveredFromSystemCount)
      assertEquals(1, summary.repostedCount)

      assertEquals(1, spyPoster.postedCalls.size)
      val repostedNotif = spyPoster.postedCalls.single().notification
      assertTrue((repostedNotif.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0)

      val rows = db.reminderDeliveryDao().observeTaskDeliveries("rem-1").first()
      assertEquals(1, rows.size)
      assertEquals("stuck-pending-1", rows.single().id)
      assertEquals(DeliveryState.POSTED_TO_SYSTEM, rows.single().state)
      assertEquals(1, rows.single().attemptCount)
    }

  @Test
  fun recovery_alsoHandlesFailedRetryableRowWithOnlyAlertOnceAndNoDuplicateRow() = runBlocking<Unit> {
    val container = newContainer()
    container.appearanceRepository.setLanguage(AppLanguage.ENGLISH)
    seedTaskAndReminder(container)

    val occKey =
      ReminderTriggerCalculator.buildOccurrenceKey("2026-11-27", "09:30", 1L, clock.zoneId())
    val inserted =
      container.reminderSyncRepository.recordPendingTaskDelivery(
        deliveryId = "retryable-1",
        taskReminderId = "rem-1",
        occurrenceKey = occKey,
        scheduledAtEpochMs = clock.currentEpochMs(),
        firedScheduleRevision = 1L,
        nowEpochMs = clock.currentEpochMs(),
      )
    container.reminderSyncRepository.updateTaskDeliveryState(
      deliveryId = inserted.id,
      state = DeliveryState.FAILED_RETRYABLE,
      attemptCount = 1,
      lastAttemptAtEpochMs = clock.currentEpochMs(),
      lastError = "Transient system error",
      nowEpochMs = clock.currentEpochMs(),
    )

    val summary = container.notificationDeliveryEngine.recoverPendingDeliveries()
    assertEquals(1, summary.repostedCount)
    assertEquals(1, spyPoster.postedCalls.size)
    assertTrue(
      (spyPoster.postedCalls.single().notification.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0
    )

    val updated = db.reminderDeliveryDao().getTaskDelivery("rem-1", occKey)!!
    assertEquals(DeliveryState.POSTED_TO_SYSTEM, updated.state)
    assertEquals(2, updated.attemptCount)
  }

  // =========================================================================
  // 9-12. Permissions, App Notifications Disabled, Channel Disabled & Idempotency
  // =========================================================================

  @Test
  fun postNotificationsDenied_recordsSuppressedPermission_andDoesNotPost() = runBlocking<Unit> {
    Shadows.shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    val container = newContainer()
    seedTaskAndReminder(container)

    val outcome = container.notificationDeliveryEngine.deliverTaskReminder("rem-1", 1L)
    assertTrue(outcome is DeliveryOutcome.SuppressedPermission)
    assertEquals(0, spyPoster.postedCalls.size)

    val occKey =
      ReminderTriggerCalculator.buildOccurrenceKey("2026-11-27", "09:30", 1L, clock.zoneId())
    val delivery = db.reminderDeliveryDao().getTaskDelivery("rem-1", occKey)!!
    assertEquals(DeliveryState.SUPPRESSED_PERMISSION, delivery.state)
    assertTrue(delivery.lastError.orEmpty().contains("POST_NOTIFICATIONS"))
  }

  @Test
  fun appNotificationsDisabled_recordsSuppressedPermission_distinctFromChannelDisabled() =
    runBlocking<Unit> {
      Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
      Shadows.shadowOf(notificationManager).setNotificationsEnabled(false)
      val container = newContainer()
      seedTaskAndReminder(container)

      val outcome = container.notificationDeliveryEngine.deliverTaskReminder("rem-1", 1L)
      assertTrue(outcome is DeliveryOutcome.SuppressedPermission)
      assertEquals(0, spyPoster.postedCalls.size)

      val occKey =
        ReminderTriggerCalculator.buildOccurrenceKey("2026-11-27", "09:30", 1L, clock.zoneId())
      val delivery = db.reminderDeliveryDao().getTaskDelivery("rem-1", occKey)!!
      assertEquals(DeliveryState.SUPPRESSED_PERMISSION, delivery.state)
      assertTrue(delivery.lastError.orEmpty().contains("App notifications disabled"))
    }

  @Test
  fun channelDisabledOrMissing_recordsSuppressedChannel_andNeverOverridesUserChannelSettings() =
    runBlocking<Unit> {
      val container = newContainer()
      val channelManager = container.notificationChannelManager
      channelManager.ensureChannelsCreated(AppLanguage.ENGLISH)

      // Simulate user lowering importance to IMPORTANCE_NONE
      val channel =
        notificationManager.getNotificationChannel(NotificationChannelManager.CHANNEL_TASK_REMINDERS)!!
      channel.importance = NotificationManager.IMPORTANCE_NONE
      notificationManager.createNotificationChannel(channel)

      // Idempotent re-creation MUST NOT restore/override user's IMPORTANCE_NONE decision
      channelManager.ensureChannelsCreated(AppLanguage.ARABIC)
      assertEquals(
        NotificationManager.IMPORTANCE_NONE,
        notificationManager
          .getNotificationChannel(NotificationChannelManager.CHANNEL_TASK_REMINDERS)!!
          .importance,
      )

      seedTaskAndReminder(container, taskId = "t-ch1", reminderId = "r-ch1")
      val disabledOutcome = container.notificationDeliveryEngine.deliverTaskReminder("r-ch1", 1L)
      assertTrue(disabledOutcome is DeliveryOutcome.SuppressedChannel)
      assertEquals(0, spyPoster.postedCalls.size)

      val occKey1 =
        ReminderTriggerCalculator.buildOccurrenceKey("2026-11-27", "09:30", 1L, clock.zoneId())
      assertEquals(
        DeliveryState.SUPPRESSED_CHANNEL,
        db.reminderDeliveryDao().getTaskDelivery("r-ch1", occKey1)!!.state,
      )

      // Also verify missing channel is detected as SUPPRESSED_CHANNEL and never silently recreated
      notificationManager.deleteNotificationChannel(NotificationChannelManager.CHANNEL_TASK_REMINDERS)
      seedTaskAndReminder(container, taskId = "t-ch2", reminderId = "r-ch2")
      val missingOutcome = container.notificationDeliveryEngine.deliverTaskReminder("r-ch2", 1L)
      assertTrue(missingOutcome is DeliveryOutcome.SuppressedChannel)
      assertNull(
        notificationManager.getNotificationChannel(NotificationChannelManager.CHANNEL_TASK_REMINDERS)
      )
      val occKey2 =
        ReminderTriggerCalculator.buildOccurrenceKey("2026-11-27", "09:30", 1L, clock.zoneId())
      assertEquals(
        DeliveryState.SUPPRESSED_CHANNEL,
        db.reminderDeliveryDao().getTaskDelivery("r-ch2", occKey2)!!.state,
      )
    }

  // =========================================================================
  // 13-14. Grace Window, EXPIRED & PREMATURE_CLOCK_ROLLBACK
  // =========================================================================

  @Test
  fun graceWindow_deliversWithin15Min_andMarksExpiredOutsideGrace() = runBlocking<Unit> {
    val container = newContainer()
    container.appearanceRepository.setLanguage(AppLanguage.ENGLISH)

    // 10 minutes past 09:30 -> 09:40 -> FIRE_WITHIN_GRACE -> delivers
    clock.epochMs = Instant.parse("2026-11-27T09:40:00Z").toEpochMilli()
    seedTaskAndReminder(container, taskId = "t-grace", reminderId = "r-grace", triggerTime = "09:30")
    val graceOutcome = container.notificationDeliveryEngine.deliverTaskReminder("r-grace", 1L)
    assertTrue(graceOutcome is DeliveryOutcome.PostedToSystem)
    assertEquals(1, spyPoster.postedCalls.size)

    // 20 minutes past 09:30 -> 09:50 -> EXPIRED -> does NOT post
    clock.epochMs = Instant.parse("2026-11-27T09:50:00Z").toEpochMilli()
    seedTaskAndReminder(container, taskId = "t-exp", reminderId = "r-exp", triggerTime = "09:30")
    val expiredOutcome = container.notificationDeliveryEngine.deliverTaskReminder("r-exp", 1L)
    assertTrue(expiredOutcome is DeliveryOutcome.Expired)
    assertEquals(1, spyPoster.postedCalls.size)

    val expKey =
      ReminderTriggerCalculator.buildOccurrenceKey("2026-11-27", "09:30", 1L, clock.zoneId())
    assertEquals(
      DeliveryState.EXPIRED,
      db.reminderDeliveryDao().getTaskDelivery("r-exp", expKey)!!.state,
    )
  }

  @Test
  fun prematureClockRollback_doesNotNotify_andTriggersReliableOutboxReschedule() =
    runBlocking<Unit> {
      val trigger = WorkManagerOutboxTrigger(context)
      val container = newContainer(trigger = trigger)
      WorkManagerTestInitHelper.initializeTestWorkManager(
        context,
        Configuration.Builder()
          .setExecutor(SynchronousExecutor())
          .setTaskExecutor(SynchronousExecutor())
          .setWorkerFactory(
            YawmiWorkerFactory(
              dispatcherProvider = { container.alarmOutboxDispatcher },
              drainTriggerProvider = { trigger },
              hasActiveQueueProvider = { db.alarmSyncOutboxDao().hasActiveQueue() },
            )
          )
          .build(),
      )

      // Reminder is scheduled for 09:30, but device clock rolled back to 08:00 (90 min early)
      clock.epochMs = Instant.parse("2026-11-27T08:00:00Z").toEpochMilli()
      seedTaskAndReminder(
        container = container,
        taskId = "t-rollback",
        reminderId = "r-rollback",
        triggerTime = "09:30",
      )
      // Drain initial schedule so outbox starts clean at rev=1
      container.alarmOutboxDispatcher.drain("init-worker")
      val initialScheduleCount = alarmScheduler.scheduledCalls.size

      val outcome =
        container.alarmFireHandler.handleIntent(
          buildFireIntent(reminderId = "r-rollback", revision = 1L)
        )
      assertTrue(outcome is AlarmFireOutcome.RescheduledOnPrematureClockRollback)
      assertEquals(0, spyPoster.postedCalls.size)
      assertTrue(db.reminderDeliveryDao().observeTaskDeliveries("r-rollback").first().isEmpty())

      awaitCondition {
        db.alarmSyncOutboxDao().let { dao ->
          runBlocking {
            dao.getAllOrdered().any {
              it.reminderId == "r-rollback" &&
                it.targetScheduleRevision == 2L &&
                it.status == OutboxStatus.SUCCEEDED
            }
          }
        }
      }

      val remAfter = db.reminderDao().getTaskReminderById("r-rollback")!!
      assertEquals(2L, remAfter.scheduleRevision)
      assertEquals(ReminderScheduleState.SCHEDULED_EXACT, remAfter.scheduleState)
      assertTrue(alarmScheduler.scheduledCalls.size > initialScheduleCount)
    }

  // =========================================================================
  // 15-16. Notification Actions: Snooze, Complete (incl. Recurrence) & Open Details
  // =========================================================================

  @Test
  fun notificationAction_snooze_updatesOnlyReminder_preservesTaskSchedule_andTriggersOutbox() =
    runBlocking<Unit> {
      val trigger = WorkManagerOutboxTrigger(context)
      val container = newContainer(trigger = trigger)
      container.appearanceRepository.setLanguage(AppLanguage.ENGLISH)
      WorkManagerTestInitHelper.initializeTestWorkManager(
        context,
        Configuration.Builder()
          .setExecutor(SynchronousExecutor())
          .setTaskExecutor(SynchronousExecutor())
          .setWorkerFactory(
            YawmiWorkerFactory(
              dispatcherProvider = { container.alarmOutboxDispatcher },
              drainTriggerProvider = { trigger },
              hasActiveQueueProvider = { db.alarmSyncOutboxDao().hasActiveQueue() },
            )
          )
          .build(),
      )

      seedTaskAndReminder(
        container = container,
        taskId = "t-snooze",
        reminderId = "r-snooze",
        scheduledDate = "2026-11-27",
        startTime = "09:30",
        endTime = "10:15",
        triggerDate = "2026-11-27",
        triggerTime = "09:30",
      )
      container.alarmOutboxDispatcher.drain("init-snooze-worker")
      container.notificationDeliveryEngine.deliverTaskReminder("r-snooze", 1L)
      assertTrue(
        notificationManager.activeNotifications.any { it.tag == "yawmi:reminder:task:r-snooze" }
      )

      NotificationActionReceiver.handlerOverrideForTesting = container.notificationActionHandler
      val occKey =
        ReminderTriggerCalculator.buildOccurrenceKey("2026-11-27", "09:30", 1L, clock.zoneId())
      val snoozeIntent =
        Intent(NotificationPendingIntentFactory.ACTION_SNOOZE_TASK).apply {
          setClassName(context, "com.example.reminder.NotificationActionReceiver")
          putExtra(NotificationPendingIntentFactory.EXTRA_TASK_ID, "t-snooze")
          putExtra(PendingIntentFactory.EXTRA_REMINDER_ID, "r-snooze")
          putExtra(NotificationPendingIntentFactory.EXTRA_OCCURRENCE_KEY, occKey)
          putExtra(PendingIntentFactory.EXTRA_SCHEDULE_REVISION, 1L)
          putExtra(NotificationPendingIntentFactory.EXTRA_NOTIFICATION_TAG, "yawmi:reminder:task:r-snooze")
          putExtra(NotificationPendingIntentFactory.EXTRA_NOTIFICATION_ID, 0)
          putExtra(NotificationPendingIntentFactory.EXTRA_SNOOZE_MINUTES, 10)
        }

      NotificationActionReceiver().onReceive(context, snoozeIntent)

      awaitCondition {
        val updated = runBlocking { db.reminderDao().getTaskReminderById("r-snooze") }?.scheduleRevision == 2L
        val cleared = notificationManager.activeNotifications.none { it.tag == "yawmi:reminder:task:r-snooze" }
        updated && cleared
      }

      // TaskEntity date & time window MUST remain unchanged
      val taskAfter = container.taskRepository.getById("t-snooze")!!
      assertEquals("2026-11-27", taskAfter.scheduledDate)
      assertEquals("09:30", taskAfter.startTime)
      assertEquals("10:15", taskAfter.endTime)

      // TaskReminderEntity updated to 09:40 & rev=2, notification cancelled, outbox staged & drained
      val remAfter = db.reminderDao().getTaskReminderById("r-snooze")!!
      assertEquals("2026-11-27", remAfter.triggerDate)
      assertEquals("09:40", remAfter.triggerTime)
      assertEquals(2L, remAfter.scheduleRevision)
      assertFalse(
        notificationManager.activeNotifications.any { it.tag == "yawmi:reminder:task:r-snooze" }
      )

      awaitCondition {
        runBlocking {
          val entries = db.alarmSyncOutboxDao().getAllOrdered().filter { it.reminderId == "r-snooze" }
          entries.any {
            it.action == OutboxAction.CANCEL_NOTIFICATION &&
              it.targetScheduleRevision == 2L &&
              it.status == OutboxStatus.SUCCEEDED
          } &&
            entries.any {
              it.action == OutboxAction.SCHEDULE_ALARM &&
                it.targetScheduleRevision == 2L &&
                it.status == OutboxStatus.SUCCEEDED
            }
        }
      }
    }

  @Test
  fun notificationAction_complete_delegatesToTaskUseCases_spawnsRecurrence_andCancelsNotification() =
    runBlocking<Unit> {
      val trigger = WorkManagerOutboxTrigger(context)
      val container = newContainer(trigger = trigger)
      container.appearanceRepository.setLanguage(AppLanguage.ENGLISH)
      WorkManagerTestInitHelper.initializeTestWorkManager(
        context,
        Configuration.Builder()
          .setExecutor(SynchronousExecutor())
          .setTaskExecutor(SynchronousExecutor())
          .setWorkerFactory(
            YawmiWorkerFactory(
              dispatcherProvider = { container.alarmOutboxDispatcher },
              drainTriggerProvider = { trigger },
              hasActiveQueueProvider = { db.alarmSyncOutboxDao().hasActiveQueue() },
            )
          )
          .build(),
      )

      seedTaskAndReminder(container, taskId = "t-comp", reminderId = "r-comp")
      container.taskUseCases.saveTaskRecurrence(
        taskId = "t-comp",
        input = TaskRecurrenceInput(frequency = RecurrenceFrequency.DAILY, interval = 1),
      )
      container.alarmOutboxDispatcher.drain("init-complete-worker")
      container.notificationDeliveryEngine.deliverTaskReminder("r-comp", 1L)
      assertTrue(
        notificationManager.activeNotifications.any { it.tag == "yawmi:reminder:task:r-comp" }
      )

      val occKey =
        ReminderTriggerCalculator.buildOccurrenceKey("2026-11-27", "09:30", 1L, clock.zoneId())
      val completeIntent =
        Intent(NotificationPendingIntentFactory.ACTION_COMPLETE_TASK).apply {
          putExtra(NotificationPendingIntentFactory.EXTRA_TASK_ID, "t-comp")
          putExtra(PendingIntentFactory.EXTRA_REMINDER_ID, "r-comp")
          putExtra(NotificationPendingIntentFactory.EXTRA_OCCURRENCE_KEY, occKey)
          putExtra(PendingIntentFactory.EXTRA_SCHEDULE_REVISION, 1L)
          putExtra(NotificationPendingIntentFactory.EXTRA_NOTIFICATION_TAG, "yawmi:reminder:task:r-comp")
          putExtra(NotificationPendingIntentFactory.EXTRA_NOTIFICATION_ID, 0)
        }

      val outcome = container.notificationActionHandler.handleIntent(completeIntent)
      assertTrue(outcome is NotificationActionOutcome.TaskCompleted)

      val completedTask = container.taskRepository.getById("t-comp")!!
      assertEquals(TaskStatus.COMPLETED, completedTask.status)
      assertFalse(
        notificationManager.activeNotifications.any { it.tag == "yawmi:reminder:task:r-comp" }
      )

      // Recurring next occurrence + reminder spawned cleanly by TaskUseCases
      val allTasks = container.taskRepository.observeAllTasks().first()
      assertEquals(2, allTasks.size)
      val nextTask = allTasks.first { it.id != "t-comp" }
      assertEquals("2026-11-28", nextTask.scheduledDate)
      assertEquals(TaskStatus.PENDING, nextTask.status)
      val nextReminder = container.reminderSyncRepository.getTaskReminders(nextTask.id).single()
      assertEquals("2026-11-28", nextReminder.triggerDate)

      // Idempotent duplicate complete action on already completed task is safely ignored
      val duplicateAction = container.notificationActionHandler.handleIntent(completeIntent)
      assertTrue(duplicateAction is NotificationActionOutcome.Ignored)

      // Also verify open task details PendingIntent carries target taskId
      val openPending =
        NotificationPendingIntentFactory(context).createOpenTaskDetailsPendingIntent(
          taskId = "t-comp",
          reminderId = "r-comp",
          occurrenceKey = occKey,
          scheduleRevision = 1L,
        )
      val savedOpenIntent = Shadows.shadowOf(openPending).savedIntent
      assertEquals(MainActivity::class.java.name, savedOpenIntent.component?.className)
      assertEquals("t-comp", savedOpenIntent.getStringExtra(NotificationPendingIntentFactory.EXTRA_OPEN_TASK_ID))
    }

  // =========================================================================
  // 18. Arabic & English Localization in Notification Channel & Content
  // =========================================================================

  @Test
  fun arabicAndEnglishLocalization_rendersLocalizedChannelAndNotificationContent() = runBlocking<Unit> {
    val container = newContainer()
    val channelManager = container.notificationChannelManager

    // 1. Arabic channel + Arabic notification
    container.appearanceRepository.setLanguage(AppLanguage.ARABIC)
    channelManager.ensureChannelsCreated(AppLanguage.ARABIC)
    val arChannel: NotificationChannel =
      notificationManager.getNotificationChannel(NotificationChannelManager.CHANNEL_TASK_REMINDERS)!!
    assertEquals("تذكيرات المهام", arChannel.name.toString())

    seedTaskAndReminder(
      container = container,
      taskId = "t-ar",
      title = "مراجعة يومية",
      description = "خطة الصباح",
      reminderId = "r-ar",
    )
    container.notificationDeliveryEngine.deliverTaskReminder("r-ar", 1L)
    val arNotif = spyPoster.postedCalls.last().notification
    assertEquals("مراجعة يومية", arNotif.extras.getString(NotificationCompat.EXTRA_TITLE))
    assertTrue(
      arNotif.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString().contains("موعد المهمة")
    )
    assertEquals("إكمال", arNotif.actions[0].title.toString())
    assertEquals("تأجيل ١٠ د", arNotif.actions[1].title.toString())

    // 2. Switch to English -> updates channel title idempotently & renders English notification
    container.appearanceRepository.setLanguage(AppLanguage.ENGLISH)
    channelManager.ensureChannelsCreated(AppLanguage.ENGLISH)
    val enChannel: NotificationChannel =
      notificationManager.getNotificationChannel(NotificationChannelManager.CHANNEL_TASK_REMINDERS)!!
    assertEquals("Task Reminders", enChannel.name.toString())

    seedTaskAndReminder(
      container = container,
      taskId = "t-en",
      title = "Daily Review",
      description = "Morning plan",
      reminderId = "r-en",
    )
    container.notificationDeliveryEngine.deliverTaskReminder("r-en", 1L)
    val enNotif = spyPoster.postedCalls.last().notification
    assertEquals("Daily Review", enNotif.extras.getString(NotificationCompat.EXTRA_TITLE))
    assertTrue(
      enNotif.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString().contains("Scheduled for")
    )
    assertEquals("Complete", enNotif.actions[0].title.toString())
    assertEquals("Snooze 10m", enNotif.actions[1].title.toString())
  }
}
