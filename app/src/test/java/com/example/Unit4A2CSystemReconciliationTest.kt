package com.example

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.core.di.DefaultAppContainer
import com.example.core.preferences.AppLanguage
import com.example.core.time.GreetingPeriod
import com.example.core.time.LocalClock
import com.example.data.local.db.PendingIntentIdentitySnapshot
import com.example.data.local.db.YawmiDatabase
import com.example.data.local.db.YawmiMetadataKeys
import com.example.data.local.entity.AlarmSyncOutboxEntity
import com.example.data.local.entity.DeliveryState
import com.example.data.local.entity.GoalEntity
import com.example.data.local.entity.GoalStatus
import com.example.data.local.entity.HabitEntity
import com.example.data.local.entity.OutboxAction
import com.example.data.local.entity.OutboxStatus
import com.example.data.local.entity.ReminderOwnerType
import com.example.data.local.entity.ReminderScheduleState
import com.example.data.local.entity.TaskEntity
import com.example.data.local.entity.TaskStatus
import com.example.domain.task.DstAdjustmentPolicy
import com.example.domain.task.ReminderTriggerCalculator
import com.example.reminder.AlarmCancelOutcome
import com.example.reminder.AlarmScheduleOutcome
import com.example.reminder.AlarmScheduler
import com.example.reminder.AndroidNotificationPoster
import com.example.reminder.DeliveryOutcome
import com.example.reminder.NotificationChannelManager
import com.example.reminder.NotificationPostOutcome
import com.example.reminder.NotificationPoster
import com.example.reminder.OutboxDrainTrigger
import com.example.reminder.PendingIntentFactory
import com.example.reminder.ReconcileTriggerReason
import com.example.reminder.ReminderReconciler
import com.example.reminder.SystemReconcileReceiver
import com.example.reminder.SystemReconciliationTrigger
import com.example.reminder.WorkManagerOutboxTrigger
import com.example.reminder.WorkManagerSystemReconciliationTrigger
import com.example.reminder.YawmiWorkerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

private class Controllable4A2CClock(
  initialEpochMs: Long = Instant.parse("2026-11-27T08:00:00Z").toEpochMilli(),
  var zone: ZoneId = ZoneId.of("UTC"),
) : LocalClock {
  var epochMs: Long = initialEpochMs

  override fun currentEpochMs(): Long = epochMs

  override fun currentDate(): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

  override fun currentTime(): LocalTime = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime()

  override fun greetingPeriod(): GreetingPeriod = GreetingPeriod.MORNING

  override fun zoneId(): ZoneId = zone
}

private class Recording4A2CAlarmScheduler(
  var exactAllowed: Boolean = true,
) : AlarmScheduler {
  data class ScheduledCall(
    val reminderId: String,
    val triggerEpochMs: Long,
    val exact: Boolean,
  )

  val scheduledCalls = mutableListOf<ScheduledCall>()
  val cancelledIds = mutableListOf<String>()

  override fun canScheduleExactAlarms(): Boolean = exactAllowed

  override fun scheduleAlarm(
    entry: AlarmSyncOutboxEntity,
    triggerAtEpochMs: Long,
  ): AlarmScheduleOutcome {
    val isExact = exactAllowed
    scheduledCalls += ScheduledCall(entry.reminderId, triggerAtEpochMs, isExact)
    return if (isExact) {
      AlarmScheduleOutcome.ScheduledExact(triggerAtEpochMs)
    } else {
      AlarmScheduleOutcome.ScheduledInexact(triggerAtEpochMs, "SCHEDULE_EXACT_ALARM denied")
    }
  }

  override fun cancelAlarm(entry: AlarmSyncOutboxEntity): AlarmCancelOutcome {
    cancelledIds += entry.reminderId
    return AlarmCancelOutcome.Cancelled
  }
}

private class Spy4A2CNotificationPoster(
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
class Unit4A2CSystemReconciliationTest {

  private lateinit var context: Context
  private lateinit var app: Application
  private lateinit var notificationManager: NotificationManager
  private lateinit var db: YawmiDatabase
  private lateinit var clock: Controllable4A2CClock
  private lateinit var alarmScheduler: Recording4A2CAlarmScheduler
  private lateinit var spyPoster: Spy4A2CNotificationPoster
  private val fileDatabasesToDelete = mutableListOf<String>()

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    app = context as Application
    notificationManager = context.getSystemService(NotificationManager::class.java)
    db = YawmiDatabase.inMemory(context)
    clock = Controllable4A2CClock()
    alarmScheduler = Recording4A2CAlarmScheduler()
    spyPoster = Spy4A2CNotificationPoster(AndroidNotificationPoster(context))

    Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    Shadows.shadowOf(notificationManager).setNotificationsEnabled(true)
    NotificationChannelManager(context).ensureChannelsCreated(AppLanguage.ENGLISH)
  }

  @After
  fun tearDown() {
    SystemReconcileReceiver.triggerOverrideForTesting = null
    db.close()
    for (name in fileDatabasesToDelete) {
      context.deleteDatabase(name)
    }
  }

  private fun newContainer(
    database: YawmiDatabase = db,
    drainTrigger: OutboxDrainTrigger = OutboxDrainTrigger.NoOp,
    reconcileTrigger: SystemReconciliationTrigger = SystemReconciliationTrigger.NoOp,
  ): DefaultAppContainer {
    return DefaultAppContainer(
      context = context,
      databaseProvider = { database },
      localClockProvider = { clock },
      alarmSchedulerProvider = { _, _ -> alarmScheduler },
      outboxDrainTriggerProvider = { drainTrigger },
      notificationPosterProvider = { spyPoster },
      systemReconciliationTriggerProvider = { reconcileTrigger },
    )
  }

  private fun initSynchronousWorkManager(
    containerProvider: () -> DefaultAppContainer,
    drainTriggerProvider: () -> OutboxDrainTrigger,
    databaseProvider: () -> YawmiDatabase = { db },
    reconcilerProvider: (() -> ReminderReconciler)? = null,
  ) {
    WorkManagerTestInitHelper.initializeTestWorkManager(
      context,
      Configuration.Builder()
        .setExecutor(SynchronousExecutor())
        .setTaskExecutor(SynchronousExecutor())
        .setWorkerFactory(
          YawmiWorkerFactory(
            dispatcherProvider = { containerProvider().alarmOutboxDispatcher },
            drainTriggerProvider = drainTriggerProvider,
            hasActiveQueueProvider = { databaseProvider().alarmSyncOutboxDao().hasActiveQueue() },
            reconcilerProvider = reconcilerProvider ?: { containerProvider().reminderReconciler },
          )
        )
        .build(),
    )
  }

  private suspend fun seedTaskAndReminder(
    container: DefaultAppContainer,
    taskId: String = "task-1",
    title: String = "Daily Focus",
    scheduledDate: String = "2026-11-27",
    startTime: String? = "10:00",
    reminderId: String = "rem-1",
    triggerDate: String = "2026-11-27",
    triggerTime: String = "10:00",
    taskStatus: TaskStatus = TaskStatus.PENDING,
    isEnabled: Boolean = true,
  ) {
    val now = clock.currentEpochMs()
    container.taskRepository.insert(
      TaskEntity(
        id = taskId,
        title = title,
        scheduledDate = scheduledDate,
        startTime = startTime,
        status = taskStatus,
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
    if (!isEnabled) {
      container.reminderSyncRepository.updateTaskReminderSchedule(
        reminderId = reminderId,
        triggerDate = triggerDate,
        triggerTime = triggerTime,
        offsetMinutes = 0,
        isEnabled = false,
        nowEpochMs = now + 1,
      )
    }
  }

  private fun awaitCondition(timeoutMs: Long = 10_000L, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      if (condition()) return
      if (System.currentTimeMillis() >= deadline) {
        org.junit.Assert.fail("Timed out after ${timeoutMs}ms waiting for condition")
      }
      Thread.sleep(15)
    }
  }

  // =========================================================================
  // 1 & 19. BOOT_COMPLETED Reschedules Future Reminders (even if DB says SCHEDULED_EXACT)
  //         and preserves scheduleRevision
  // =========================================================================

  @Test
  fun bootCompleted_reschedulesFutureRemindersEvenWhenDbSaysScheduledExact_preservingRevision() =
    runBlocking<Unit> {
      lateinit var container: DefaultAppContainer
      val outboxTrigger = WorkManagerOutboxTrigger(context)
      val reconcileTrigger =
        WorkManagerSystemReconciliationTrigger(
          appContext = context,
          databaseProvider = { db },
          isReconcileNeededOnResumeProvider = { container.reminderReconciler.isReconciliationNeededOnResume() },
          hasActiveOutboxQueueProvider = { db.alarmSyncOutboxDao().hasActiveQueue() },
          outboxDrainTriggerProvider = { outboxTrigger },
        )
      container = newContainer(drainTrigger = outboxTrigger, reconcileTrigger = reconcileTrigger)
      initSynchronousWorkManager(
        containerProvider = { container },
        drainTriggerProvider = { outboxTrigger },
      )

      seedTaskAndReminder(container, reminderId = "rem-boot", triggerTime = "10:00")
      awaitCondition {
        runBlocking {
          db.reminderDao().getTaskReminderById("rem-boot")?.scheduleState ==
            ReminderScheduleState.SCHEDULED_EXACT
        }
      }
      val callsBeforeBoot = alarmScheduler.scheduledCalls.size
      assertEquals(1, callsBeforeBoot)

      // Simulate reboot via SystemReconcileReceiver
      SystemReconcileReceiver.triggerOverrideForTesting = reconcileTrigger
      val receiver = SystemReconcileReceiver()
      receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

      awaitCondition { alarmScheduler.scheduledCalls.size >= 2 }

      val reminderAfter = db.reminderDao().getTaskReminderById("rem-boot")!!
      assertEquals(1L, reminderAfter.scheduleRevision)
      assertEquals(ReminderScheduleState.SCHEDULED_EXACT, reminderAfter.scheduleState)
      assertEquals(Instant.parse("2026-11-27T10:00:00Z").toEpochMilli(), alarmScheduler.scheduledCalls.last().triggerEpochMs)
    }

  // =========================================================================
  // 2. MY_PACKAGE_REPLACED Reconciliation
  // =========================================================================

  @Test
  fun myPackageReplaced_triggersReconciliationAndReschedulesFutureReminder() = runBlocking<Unit> {
    lateinit var container: DefaultAppContainer
    val outboxTrigger = WorkManagerOutboxTrigger(context)
    val reconcileTrigger =
      WorkManagerSystemReconciliationTrigger(
        appContext = context,
        databaseProvider = { db },
        isReconcileNeededOnResumeProvider = { container.reminderReconciler.isReconciliationNeededOnResume() },
        hasActiveOutboxQueueProvider = { db.alarmSyncOutboxDao().hasActiveQueue() },
        outboxDrainTriggerProvider = { outboxTrigger },
      )
    container = newContainer(drainTrigger = outboxTrigger, reconcileTrigger = reconcileTrigger)
    initSynchronousWorkManager(
      containerProvider = { container },
      drainTriggerProvider = { outboxTrigger },
    )

    seedTaskAndReminder(container, reminderId = "rem-pkg", triggerTime = "11:00")
    awaitCondition { alarmScheduler.scheduledCalls.size == 1 }

    SystemReconcileReceiver.triggerOverrideForTesting = reconcileTrigger
    SystemReconcileReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

    awaitCondition { alarmScheduler.scheduledCalls.size >= 2 }
    assertEquals(1L, db.reminderDao().getTaskReminderById("rem-pkg")!!.scheduleRevision)
  }

  // =========================================================================
  // 3. TIME_CHANGED & PREMATURE_CLOCK_ROLLBACK Protection
  // =========================================================================

  @Test
  fun timeChanged_recalculatesFuture_andPreventsEarlyFireOrRepeatedConsumed() = runBlocking<Unit> {
    val container = newContainer()
    seedTaskAndReminder(container, taskId = "t-fut", reminderId = "r-fut", triggerTime = "10:00")
    seedTaskAndReminder(container, taskId = "t-done", reminderId = "r-done", triggerTime = "07:55")
    container.alarmOutboxDispatcher.drain("init")

    // Deliver r-done within grace at 08:00
    val delivered = container.notificationDeliveryEngine.deliverTaskReminder("r-done", 1L)
    assertTrue(delivered is DeliveryOutcome.PostedToSystem)
    assertEquals(1, spyPoster.postedCalls.size)

    // Roll clock backwards from 08:00 to 06:00 (PREMATURE_CLOCK_ROLLBACK relative to 07:55 & 10:00)
    clock.epochMs = Instant.parse("2026-11-27T06:00:00Z").toEpochMilli()
    val summary = container.reminderReconciler.reconcile(ReconcileTriggerReason.TIME_CHANGED)
    container.alarmOutboxDispatcher.drain("time-changed")

    // r-fut is rescheduled for 10:00, never fired early; r-done is ALREADY POSTED so never rescheduled or re-fired!
    assertEquals(1, summary.stagedScheduleCount)
    assertEquals(1, summary.skippedConsumedCount)
    assertEquals(1, spyPoster.postedCalls.size)
    assertTrue(
      alarmScheduler.scheduledCalls.none { it.reminderId == "r-done" && it.triggerEpochMs == Instant.parse("2026-11-27T07:55:00Z").toEpochMilli() && alarmScheduler.scheduledCalls.count { c -> c.reminderId == "r-done" } > 1 }
    )

    // Also verify AlarmFireHandler at 06:00 for r-fut triggers PREMATURE_CLOCK_ROLLBACK without notifying
    val prematureEvaluation =
      ReminderTriggerCalculator.evaluateAtFire(
        triggerDate = "2026-11-27",
        triggerTime = "10:00",
        scheduleRevision = 1L,
        nowEpochMs = clock.currentEpochMs(),
        zoneId = clock.zoneId(),
      )
    assertEquals(
      com.example.domain.task.TriggerEvaluationStatus.PREMATURE_CLOCK_ROLLBACK,
      prematureEvaluation.status,
    )
    assertEquals(1, spyPoster.postedCalls.size)
  }

  // =========================================================================
  // 4. TIMEZONE_CHANGED + DST (GAP_SHIFTED_FORWARD & OVERLAP_EARLIER_OFFSET)
  //    + Consumed reminder never re-triggers when traveling east or west
  // =========================================================================

  @Test
  fun timezoneChanged_recomputesTriggerEpoch_respectsDstPolicies_andNeverReFiresConsumed() =
    runBlocking<Unit> {
      val container = newContainer()
      // 1) Active future reminder at 09:00 local
      seedTaskAndReminder(container, taskId = "t-tz", reminderId = "r-tz", triggerTime = "09:00")
      // 2) Reminder at 07:55 UTC delivered at 08:00 UTC (already POSTED_TO_SYSTEM)
      seedTaskAndReminder(container, taskId = "t-east-west", reminderId = "r-ew", triggerTime = "07:55")
      container.alarmOutboxDispatcher.drain("init")
      assertTrue(container.notificationDeliveryEngine.deliverTaskReminder("r-ew", 1L) is DeliveryOutcome.PostedToSystem)
      assertEquals(1, spyPoster.postedCalls.size)

      // Travel WEST to America/New_York (UTC-5): 08:00Z is 03:00 local!
      // r-ew (07:55 local) would look 4h55m in the future in NY if we didn't check POSTED_TO_SYSTEM!
      clock.zone = ZoneId.of("America/New_York")
      val westSummary = container.reminderReconciler.reconcile(ReconcileTriggerReason.TIMEZONE_CHANGED)
      container.alarmOutboxDispatcher.drain("tz-west")

      assertEquals(1, westSummary.stagedScheduleCount)
      assertEquals(1, westSummary.skippedConsumedCount)
      val rTzCall = alarmScheduler.scheduledCalls.last { it.reminderId == "r-tz" }
      assertEquals(
        Instant.parse("2026-11-27T14:00:00Z").toEpochMilli(), // 09:00 EST (UTC-5) = 14:00Z
        rTzCall.triggerEpochMs,
      )
      assertEquals(1, alarmScheduler.scheduledCalls.count { it.reminderId == "r-ew" })
      assertEquals(1, spyPoster.postedCalls.size)

      // Travel EAST to Asia/Tokyo (UTC+9): still never re-triggers r-ew
      clock.zone = ZoneId.of("Asia/Tokyo")
      container.reminderReconciler.reconcile(ReconcileTriggerReason.TIMEZONE_CHANGED)
      assertEquals(1, spyPoster.postedCalls.size)
      assertEquals(1, alarmScheduler.scheduledCalls.count { it.reminderId == "r-ew" })

      // Verify DST policies: America/New_York 2026-03-08 02:30 (Spring Forward Gap) & 2026-11-01 01:30 (Fall Back Overlap)
      clock.zone = ZoneId.of("America/New_York")
      clock.epochMs = Instant.parse("2026-03-08T05:00:00Z").toEpochMilli()
      seedTaskAndReminder(
        container,
        taskId = "t-gap",
        reminderId = "r-gap",
        scheduledDate = "2026-03-08",
        triggerDate = "2026-03-08",
        triggerTime = "02:30",
      )
      clock.epochMs = Instant.parse("2026-11-01T04:00:00Z").toEpochMilli()
      seedTaskAndReminder(
        container,
        taskId = "t-ovl",
        reminderId = "r-ovl",
        scheduledDate = "2026-11-01",
        triggerDate = "2026-11-01",
        triggerTime = "01:30",
      )
      val gapResolved =
        ReminderTriggerCalculator.resolveTrigger("2026-03-08", "02:30", ZoneId.of("America/New_York"))!!
      val ovlResolved =
        ReminderTriggerCalculator.resolveTrigger("2026-11-01", "01:30", ZoneId.of("America/New_York"))!!
      assertEquals(DstAdjustmentPolicy.GAP_SHIFTED_FORWARD, gapResolved.dstAdjustment)
      assertEquals(DstAdjustmentPolicy.OVERLAP_EARLIER_OFFSET, ovlResolved.dstAdjustment)

      clock.epochMs = Instant.parse("2026-03-08T05:00:00Z").toEpochMilli()
      container.reminderReconciler.reconcile(ReconcileTriggerReason.TIMEZONE_CHANGED)
      container.alarmOutboxDispatcher.drain("dst-gap")
      assertEquals(
        gapResolved.triggerEpochMs,
        alarmScheduler.scheduledCalls.last { it.reminderId == "r-gap" }.triggerEpochMs,
      )
    }

  // =========================================================================
  // 5. DATE_CHANGED Handling Without Modifying TaskEntity
  // =========================================================================

  @Test
  fun dateChanged_evaluatesDueAndExpiredReminders_withoutModifyingTaskEntity() = runBlocking<Unit> {
    val container = newContainer()
    clock.epochMs = Instant.parse("2026-11-27T22:50:00Z").toEpochMilli()
    // r-grace at 23:55 (will be within grace at 00:01)
    seedTaskAndReminder(
      container,
      taskId = "t-grace-midnight",
      reminderId = "r-grace-midnight",
      scheduledDate = "2026-11-27",
      triggerDate = "2026-11-27",
      triggerTime = "23:55",
    )
    // r-exp at 23:00 (already expired at 00:01)
    seedTaskAndReminder(
      container,
      taskId = "t-exp-midnight",
      reminderId = "r-exp-midnight",
      scheduledDate = "2026-11-27",
      triggerDate = "2026-11-27",
      triggerTime = "23:00",
    )
    container.alarmOutboxDispatcher.drain("init")
    val taskBefore = db.taskDao().getById("t-grace-midnight")!!

    // Midnight rollover -> 2026-11-28 00:01Z
    clock.epochMs = Instant.parse("2026-11-28T00:01:00Z").toEpochMilli()
    val summary = container.reminderReconciler.reconcile(ReconcileTriggerReason.DATE_CHANGED)

    assertEquals(1, summary.deliveredWithinGraceCount)
    assertEquals(1, summary.markedExpiredCount)
    assertEquals(1, spyPoster.postedCalls.size)
    assertEquals(taskBefore, db.taskDao().getById("t-grace-midnight"))

    val expKey =
      ReminderTriggerCalculator.buildOccurrenceKey("2026-11-27", "23:00", 1L, clock.zoneId())
    assertEquals(
      DeliveryState.EXPIRED,
      db.reminderDeliveryDao().getTaskDelivery("r-exp-midnight", expKey)!!.state,
    )
  }

  // =========================================================================
  // 6, 7, 8, 9. Exact Alarm Permission Grant / Revoke Detection / Transitions
  // =========================================================================

  @Test
  fun exactAlarmPermission_transitionsExactToInexactOnResumeRevoke_andInexactToExactOnGrantBroadcast() =
    runBlocking<Unit> {
      lateinit var container: DefaultAppContainer
      val outboxTrigger = WorkManagerOutboxTrigger(context)
      val reconcileTrigger =
        WorkManagerSystemReconciliationTrigger(
          appContext = context,
          databaseProvider = { db },
          isReconcileNeededOnResumeProvider = { container.reminderReconciler.isReconciliationNeededOnResume() },
          hasActiveOutboxQueueProvider = { db.alarmSyncOutboxDao().hasActiveQueue() },
          outboxDrainTriggerProvider = { outboxTrigger },
        )
      container = newContainer(drainTrigger = outboxTrigger, reconcileTrigger = reconcileTrigger)
      initSynchronousWorkManager(
        containerProvider = { container },
        drainTriggerProvider = { outboxTrigger },
      )

      // Start with Exact Alarm permission GRANTED
      alarmScheduler.exactAllowed = true
      seedTaskAndReminder(container, reminderId = "r-perm", triggerTime = "10:00")
      awaitCondition {
        runBlocking {
          db.reminderDao().getTaskReminderById("r-perm")?.scheduleState ==
            ReminderScheduleState.SCHEDULED_EXACT
        }
      }
      // Run resume check when nothing changed -> NOT needed
      container.reminderReconciler.reconcile(ReconcileTriggerReason.APP_STARTUP)
      assertFalse(container.reminderReconciler.isReconciliationNeededOnResume())

      // Android revokes permission WITHOUT sending any broadcast
      alarmScheduler.exactAllowed = false
      // On Activity ON_RESUME -> detects revocation!
      assertTrue(container.reminderReconciler.isReconciliationNeededOnResume())
      reconcileTrigger.requestReconcileIfNeededOnResumeAsync()

      awaitCondition {
        runBlocking {
          db.reminderDao().getTaskReminderById("r-perm")?.scheduleState ==
            ReminderScheduleState.SCHEDULED_INEXACT
        }
      }
      assertFalse(alarmScheduler.scheduledCalls.last().exact)
      // Once reconciled to INEXACT, subsequent resumes skip full reconciliation
      assertFalse(container.reminderReconciler.isReconciliationNeededOnResume())

      // User re-grants permission -> system sends ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
      alarmScheduler.exactAllowed = true
      SystemReconcileReceiver.triggerOverrideForTesting = reconcileTrigger
      SystemReconcileReceiver().onReceive(
        context,
        Intent(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED),
      )

      awaitCondition {
        runBlocking {
          db.reminderDao().getTaskReminderById("r-perm")?.scheduleState ==
            ReminderScheduleState.SCHEDULED_EXACT
        }
      }
      assertTrue(alarmScheduler.scheduledCalls.last().exact)
      assertEquals(1L, db.reminderDao().getTaskReminderById("r-perm")!!.scheduleRevision)
    }

  // =========================================================================
  // 10 & 11. Grace Window Delivery Once & Expired Outside Grace Without Notify
  // =========================================================================

  @Test
  fun graceWindow_deliversOnceViaDeliveryEngine_andExpiredMarksExpiredWithoutNotification() =
    runBlocking<Unit> {
      val container = newContainer()
      seedTaskAndReminder(container, taskId = "t-grace", reminderId = "r-grace", triggerTime = "09:50")
      seedTaskAndReminder(container, taskId = "t-exp", reminderId = "r-exp", triggerTime = "09:30")
      container.alarmOutboxDispatcher.drain("init")

      // Move clock to 10:00Z -> r-grace is 10 min past (within 15m grace); r-exp is 30 min past (EXPIRED)
      clock.epochMs = Instant.parse("2026-11-27T10:00:00Z").toEpochMilli()

      val first = container.reminderReconciler.reconcile(ReconcileTriggerReason.BOOT_COMPLETED)
      assertEquals(1, first.deliveredWithinGraceCount)
      assertEquals(1, first.markedExpiredCount)
      assertEquals(1, spyPoster.postedCalls.size)

      // Repeat reconciliation -> still posted ONLY once!
      val second = container.reminderReconciler.reconcile(ReconcileTriggerReason.BOOT_COMPLETED)
      assertEquals(0, second.deliveredWithinGraceCount)
      assertEquals(1, second.skippedConsumedCount)
      assertEquals(1, spyPoster.postedCalls.size)

      val graceKey =
        ReminderTriggerCalculator.buildOccurrenceKey("2026-11-27", "09:50", 1L, clock.zoneId())
      val expKey =
        ReminderTriggerCalculator.buildOccurrenceKey("2026-11-27", "09:30", 1L, clock.zoneId())
      assertEquals(
        DeliveryState.POSTED_TO_SYSTEM,
        db.reminderDeliveryDao().getTaskDelivery("r-grace", graceKey)!!.state,
      )
      assertEquals(
        DeliveryState.EXPIRED,
        db.reminderDeliveryDao().getTaskDelivery("r-exp", expKey)!!.state,
      )
    }

  // =========================================================================
  // 12, 15, 16. Exclusions: CONSUMED, HABIT, GOAL, Disabled, Completed/Cancelled Tasks
  // =========================================================================

  @Test
  fun reconciler_excludesConsumedHabitGoalDisabledAndInactiveTasks() = runBlocking<Unit> {
    val container = newContainer()
    val now = clock.currentEpochMs()

    // Consumed task reminder
    seedTaskAndReminder(container, taskId = "t-cons", reminderId = "r-cons", triggerTime = "10:00")
    // Disabled task reminder
    seedTaskAndReminder(
      container,
      taskId = "t-dis",
      reminderId = "r-dis",
      triggerTime = "10:00",
      isEnabled = false,
    )
    // Completed task reminder
    seedTaskAndReminder(
      container,
      taskId = "t-comp",
      reminderId = "r-comp",
      triggerTime = "10:00",
      taskStatus = TaskStatus.PENDING,
    )
    // Cancelled task reminder
    seedTaskAndReminder(
      container,
      taskId = "t-canc",
      reminderId = "r-canc",
      triggerTime = "10:00",
      taskStatus = TaskStatus.PENDING,
    )
    container.alarmOutboxDispatcher.drain("init")

    // Mark r-cons CONSUMED, complete t-comp, cancel t-canc
    db.reminderDao().updateTaskReminderScheduleStateIfRevision(
      id = "r-cons",
      expectedRevision = 1L,
      scheduleState = ReminderScheduleState.CONSUMED,
      updatedAtEpochMs = now,
    )
    container.taskRepository.updateStatus("t-comp", TaskStatus.COMPLETED, now, now)
    container.taskRepository.updateStatus("t-canc", TaskStatus.CANCELLED, now, null)
    container.alarmOutboxDispatcher.drain("cleanup")

    // Seed HABIT & GOAL reminders (must remain untouched and outbox-free for 4A.2C)
    container.habitRepository.insertHabit(
      HabitEntity(id = "h-1", title = "Drink water", createdAtEpochMs = now, updatedAtEpochMs = now)
    )
    container.reminderSyncRepository.createHabitReminder(
      reminderId = "hr-1",
      habitId = "h-1",
      triggerDate = "2026-11-27",
      triggerTime = "10:00",
      offsetMinutes = 0,
      nowEpochMs = now,
    )
    container.goalRepository.insertGoal(
      GoalEntity(
        id = "g-1",
        name = "Ship YAWMI",
        startDate = "2026-11-27",
        status = GoalStatus.ACTIVE,
        createdAtEpochMs = now,
        updatedAtEpochMs = now,
      )
    )
    container.reminderSyncRepository.createGoalReminder(
      reminderId = "gr-1",
      goalId = "g-1",
      triggerDate = "2026-11-27",
      triggerTime = "10:00",
      offsetMinutes = 0,
      nowEpochMs = now,
    )

    val outboxBefore = db.alarmSyncOutboxDao().getAllOrdered().size
    val summary = container.reminderReconciler.reconcile(ReconcileTriggerReason.BOOT_COMPLETED)
    val outboxAfter = db.alarmSyncOutboxDao().getAllOrdered().size

    assertEquals(0, summary.stagedScheduleCount)
    assertEquals(0, summary.deliveredWithinGraceCount)
    assertEquals(outboxBefore, outboxAfter)
    assertTrue(
      db.alarmSyncOutboxDao().getAllOrdered().none {
        it.ownerType == ReminderOwnerType.HABIT && it.status == OutboxStatus.SUCCEEDED
      }
    )
  }

  // =========================================================================
  // 13. Idempotency: Prevents Unbounded Outbox Commands on Repeated Reconciliation
  // =========================================================================

  @Test
  fun repeatedReconciliation_doesNotCreateUnboundedOutboxCommands() = runBlocking<Unit> {
    val container = newContainer()
    seedTaskAndReminder(container, reminderId = "r-idem", triggerTime = "10:00")
    container.alarmOutboxDispatcher.drain("init")
    val baselineCount = db.alarmSyncOutboxDao().getAllOrdered().size
    assertEquals(1, baselineCount)

    // Repeated BOOT_COMPLETED before outbox drains -> stages ONLY 1 new PENDING command
    repeat(5) {
      container.reminderReconciler.reconcile(ReconcileTriggerReason.BOOT_COMPLETED)
    }
    val afterRepeatedBoot = db.alarmSyncOutboxDao().getAllOrdered()
    assertEquals(2, afterRepeatedBoot.size)
    assertEquals(1, afterRepeatedBoot.count { it.status == OutboxStatus.PENDING })

    // Drain, then call APP_STARTUP / APP_RESUME_CHECK repeatedly -> 0 new commands!
    container.alarmOutboxDispatcher.drain("drain-boot")
    repeat(5) {
      container.reminderReconciler.reconcile(ReconcileTriggerReason.APP_STARTUP)
      container.reminderReconciler.reconcile(ReconcileTriggerReason.APP_RESUME_CHECK)
    }
    assertEquals(2, db.alarmSyncOutboxDao().getAllOrdered().size)
  }

  // =========================================================================
  // 14. Never Relies on PendingIntent.FLAG_NO_CREATE as Proof of Existing Alarm
  // =========================================================================

  @Test
  fun bootCompleted_doesNotTrustFlagNoCreate_andStillReschedulesWhenPendingIntentExists() =
    runBlocking<Unit> {
      val container = newContainer()
      seedTaskAndReminder(container, reminderId = "r-no-create", triggerTime = "10:00")
      container.alarmOutboxDispatcher.drain("init")

      // Pre-populate system PendingIntent registry so FLAG_NO_CREATE returns NON-NULL
      val reminder = db.reminderDao().getTaskReminderById("r-no-create")!!
      val identity =
        PendingIntentIdentitySnapshot.forReminder(
          ownerType = ReminderOwnerType.TASK,
          reminderId = reminder.id,
          requestCode = reminder.requestCode,
          packageName = context.packageName,
        )
      val outboxEntry = db.alarmSyncOutboxDao().getAllOrdered().last()
      val createdPi = PendingIntentFactory(context).createOrUpdateAlarmPendingIntent(outboxEntry)
      assertNotNull(createdPi)
      val existingViaNoCreate =
        PendingIntent.getBroadcast(
          context,
          reminder.requestCode,
          PendingIntentFactory(context).buildIntent(outboxEntry),
          PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
      assertNotNull(existingViaNoCreate)
      assertEquals(identity.pendingIntentIdentityKey, outboxEntry.pendingIntentIdentityKey)

      // Even though FLAG_NO_CREATE is non-null, BOOT_COMPLETED MUST still reschedule via Outbox!
      val summary = container.reminderReconciler.reconcile(ReconcileTriggerReason.BOOT_COMPLETED)
      assertEquals(1, summary.stagedScheduleCount)
      container.alarmOutboxDispatcher.drain("boot")
      assertEquals(2, alarmScheduler.scheduledCalls.size)
    }

  // =========================================================================
  // 17. Bursty Reconciliation Events Coalesced Without Unbounded WorkManager Chains
  // =========================================================================

  @Test
  fun burstyReconcileTriggers_coalesceWithoutUnboundedChain_andNeverDropTrailingEventWhileRunning() =
    runBlocking<Unit> {
      val workerStarted = CountDownLatch(1)
      val allowFirstWorkerToFinish = CountDownLatch(1)
      val reconcileExecutions = AtomicInteger(0)
      val activeExecutions = AtomicInteger(0)
      val maxConcurrentExecutions = AtomicInteger(0)

      val container = newContainer()
      val outboxTrigger = WorkManagerOutboxTrigger(context)
      val asyncExecutor = Executors.newFixedThreadPool(2)

      val slowReconciler =
        ReminderReconciler(
          context = context,
          database = db,
          taskDao = db.taskDao(),
          reminderDao = db.reminderDao(),
          deliveryDao = db.reminderDeliveryDao(),
          outboxDao = db.alarmSyncOutboxDao(),
          appMetadataDao = db.appMetadataDao(),
          outboxCoordinator =
            com.example.data.repository.ReminderOutboxCoordinator(
              context.packageName,
              db.appMetadataDao(),
              db.reminderDao(),
              db.alarmSyncOutboxDao(),
            ),
          alarmScheduler = alarmScheduler,
          notificationDeliveryEngine = container.notificationDeliveryEngine,
          outboxDrainTrigger = outboxTrigger,
          localClock = clock,
          isUserUnlockedProvider = {
            val run = reconcileExecutions.incrementAndGet()
            val current = activeExecutions.incrementAndGet()
            maxConcurrentExecutions.updateAndGet { maxOf(it, current) }
            if (run == 1) {
              workerStarted.countDown()
              allowFirstWorkerToFinish.await(5, TimeUnit.SECONDS)
            }
            activeExecutions.decrementAndGet()
            true
          },
        )

      val reconcileTrigger =
        WorkManagerSystemReconciliationTrigger(
          appContext = context,
          databaseProvider = { db },
          isReconcileNeededOnResumeProvider = { false },
          hasActiveOutboxQueueProvider = { false },
          outboxDrainTriggerProvider = { outboxTrigger },
        )

      WorkManagerTestInitHelper.initializeTestWorkManager(
        context,
        Configuration.Builder()
          .setExecutor(asyncExecutor)
          .setTaskExecutor(asyncExecutor)
          .setWorkerFactory(
            YawmiWorkerFactory(
              dispatcherProvider = { container.alarmOutboxDispatcher },
              drainTriggerProvider = { outboxTrigger },
              hasActiveQueueProvider = { db.alarmSyncOutboxDao().hasActiveQueue() },
              reconcilerProvider = { slowReconciler },
            )
          )
          .build(),
      )

      try {
        reconcileTrigger.requestReconcileNow(ReconcileTriggerReason.BOOT_COMPLETED)
        assertTrue(workerStarted.await(5, TimeUnit.SECONDS))

        // While worker 1 is RUNNING, burst 8 overlapping events -> must coalesce into 1 BLOCKED follower!
        repeat(8) { idx ->
          reconcileTrigger.requestReconcileNow(
            if (idx % 2 == 0) ReconcileTriggerReason.TIME_CHANGED else ReconcileTriggerReason.TIMEZONE_CHANGED
          )
        }

        val wm = WorkManager.getInstance(context)
        val midInfos =
          wm.getWorkInfosForUniqueWorkFlow(WorkManagerSystemReconciliationTrigger.RECONCILE_WORK_NAME)
            .first()
        assertTrue("Expected <= 2 work infos, got ${midInfos.size}", midInfos.size <= 2)
        assertTrue(midInfos.any { it.state == WorkInfo.State.RUNNING })
        assertTrue(midInfos.any { it.state == WorkInfo.State.BLOCKED || it.state == WorkInfo.State.ENQUEUED })

        allowFirstWorkerToFinish.countDown()
        awaitCondition {
          val infos =
            runBlocking {
              wm.getWorkInfosForUniqueWorkFlow(WorkManagerSystemReconciliationTrigger.RECONCILE_WORK_NAME)
                .first()
            }
          infos.isNotEmpty() && infos.all { it.state.isFinished } && reconcileExecutions.get() == 2
        }

        assertEquals(1, maxConcurrentExecutions.get())
        assertEquals(2, reconcileExecutions.get())
      } finally {
        allowFirstWorkerToFinish.countDown()
        asyncExecutor.shutdownNow()
      }
    }

  // =========================================================================
  // 18. Never Blocks Main Thread
  // =========================================================================

  @Test
  fun systemReconcileReceiver_andAsyncTriggers_doNotBlockMainThread() = runBlocking<Unit> {
    val latch = CountDownLatch(1)
    val invokedOnThread = java.util.concurrent.atomic.AtomicReference<String>()
    val fakeTrigger =
      object : SystemReconciliationTrigger {
        override suspend fun requestReconcileNow(reason: ReconcileTriggerReason) {
          invokedOnThread.set(Thread.currentThread().name)
          latch.countDown()
        }

        override fun requestReconcileAsync(reason: ReconcileTriggerReason) = Unit

        override fun requestReconcileIfNeededOnResumeAsync() = Unit
      }
    SystemReconcileReceiver.triggerOverrideForTesting = fakeTrigger

    val mainThreadName = Thread.currentThread().name
    val receiver = SystemReconcileReceiver()
    receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

    assertTrue(latch.await(5, TimeUnit.SECONDS))
    assertTrue(invokedOnThread.get() != mainThreadName)
  }

  // =========================================================================
  // 20. Process Death & Restart Reconciliation (File-Backed DB)
  // =========================================================================

  @Test
  fun processDeathAndRestart_recoversAndReconcilesPersistedDatabase() = runBlocking<Unit> {
    val dbName = "yawmi-process-restart-${System.nanoTime()}.db"
    fileDatabasesToDelete += dbName

    // Process 1: seed future reminder + grace reminder, then simulate abrupt process death before drain/delivery
    val db1 = YawmiDatabase.openWithName(context, dbName)
    val c1 = newContainer(database = db1)
    seedTaskAndReminder(c1, taskId = "t-p1-fut", reminderId = "r-p1-fut", triggerTime = "12:00")
    seedTaskAndReminder(c1, taskId = "t-p1-grace", reminderId = "r-p1-grace", triggerTime = "09:50")
    db1.close()

    // Process 2 starts later at 10:00Z with a fresh container + WorkManager
    clock.epochMs = Instant.parse("2026-11-27T10:00:00Z").toEpochMilli()
    val db2 = YawmiDatabase.openWithName(context, dbName)
    try {
      lateinit var c2: DefaultAppContainer
      val outboxTrigger = WorkManagerOutboxTrigger(context)
      val reconcileTrigger =
        WorkManagerSystemReconciliationTrigger(
          appContext = context,
          databaseProvider = { db2 },
          isReconcileNeededOnResumeProvider = { c2.reminderReconciler.isReconciliationNeededOnResume() },
          hasActiveOutboxQueueProvider = { db2.alarmSyncOutboxDao().hasActiveQueue() },
          outboxDrainTriggerProvider = { outboxTrigger },
        )
      c2 = newContainer(database = db2, drainTrigger = outboxTrigger, reconcileTrigger = reconcileTrigger)
      initSynchronousWorkManager(
        containerProvider = { c2 },
        drainTriggerProvider = { outboxTrigger },
        databaseProvider = { db2 },
      )

      reconcileTrigger.requestReconcileNow(ReconcileTriggerReason.APP_STARTUP)

      awaitCondition {
        runBlocking {
          db2.reminderDao().getTaskReminderById("r-p1-fut")?.scheduleState ==
            ReminderScheduleState.SCHEDULED_EXACT && spyPoster.postedCalls.size == 1
        }
      }

      val graceKey =
        ReminderTriggerCalculator.buildOccurrenceKey("2026-11-27", "09:50", 1L, clock.zoneId())
      assertEquals(
        DeliveryState.POSTED_TO_SYSTEM,
        db2.reminderDeliveryDao().getTaskDelivery("r-p1-grace", graceKey)!!.state,
      )
      assertEquals(1L, db2.reminderDao().getTaskReminderById("r-p1-fut")!!.scheduleRevision)
    } finally {
      db2.close()
    }
  }

  // =========================================================================
  // Separation of Exact Alarm & POST_NOTIFICATIONS + Locked Storage + Manifest
  // =========================================================================

  @Test
  fun exactAlarmAndPostNotificationsPermissions_areStrictlyDecoupled() = runBlocking<Unit> {
    val container = newContainer()
    // 1) POST_NOTIFICATIONS denied, Exact Alarm granted -> future alarm STILL schedules EXACT!
    Shadows.shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    alarmScheduler.exactAllowed = true
    seedTaskAndReminder(container, taskId = "t-sep-1", reminderId = "r-sep-1", triggerTime = "10:00")
    container.reminderReconciler.reconcile(ReconcileTriggerReason.BOOT_COMPLETED)
    container.alarmOutboxDispatcher.drain("sep-1")
    assertEquals(
      ReminderScheduleState.SCHEDULED_EXACT,
      db.reminderDao().getTaskReminderById("r-sep-1")!!.scheduleState,
    )

    // 2) POST_NOTIFICATIONS granted, Exact Alarm denied -> future falls back INEXACT, grace STILL notifies!
    Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    alarmScheduler.exactAllowed = false
    seedTaskAndReminder(container, taskId = "t-sep-2", reminderId = "r-sep-2", triggerTime = "07:55")
    val summary = container.reminderReconciler.reconcile(ReconcileTriggerReason.APP_RESUME_CHECK)
    container.alarmOutboxDispatcher.drain("sep-2")
    assertEquals(
      ReminderScheduleState.SCHEDULED_INEXACT,
      db.reminderDao().getTaskReminderById("r-sep-1")!!.scheduleState,
    )
    assertEquals(1, summary.deliveredWithinGraceCount)
    assertEquals(1, spyPoster.postedCalls.size)
  }

  @Test
  fun lockedCredentialEncryptedStorage_skipsReconcileSafely() = runBlocking<Unit> {
    val container = newContainer()
    val lockedReconciler =
      ReminderReconciler(
        context = context,
        database = db,
        taskDao = db.taskDao(),
        reminderDao = db.reminderDao(),
        deliveryDao = db.reminderDeliveryDao(),
        outboxDao = db.alarmSyncOutboxDao(),
        appMetadataDao = db.appMetadataDao(),
        outboxCoordinator =
          com.example.data.repository.ReminderOutboxCoordinator(
            context.packageName,
            db.appMetadataDao(),
            db.reminderDao(),
            db.alarmSyncOutboxDao(),
          ),
        alarmScheduler = alarmScheduler,
        notificationDeliveryEngine = container.notificationDeliveryEngine,
        outboxDrainTrigger = OutboxDrainTrigger.NoOp,
        localClock = clock,
        isUserUnlockedProvider = { false },
      )

    val summary = lockedReconciler.reconcile(ReconcileTriggerReason.BOOT_COMPLETED)
    assertTrue(summary.skippedDueToLockedStorage)
    assertFalse(lockedReconciler.isReconciliationNeededOnResume())
  }

  @Test
  fun manifest_declaresBootCompleted_exportedFalseReceiver_andNoForbiddenExactAlarmOrDirectBoot() {
    val pm = context.packageManager
    val pkgInfo =
      pm.getPackageInfo(
        context.packageName,
        PackageManager.GET_PERMISSIONS or PackageManager.GET_RECEIVERS,
      )
    val permissions = pkgInfo.requestedPermissions?.toSet().orEmpty()
    assertTrue(permissions.contains(Manifest.permission.RECEIVE_BOOT_COMPLETED))
    assertFalse(permissions.contains("android.permission.USE_EXACT_ALARM"))

    val receiverInfo =
      pkgInfo.receivers?.firstOrNull {
        it.name == SystemReconcileReceiver::class.java.name
      }
    assertNotNull(receiverInfo)
    assertFalse(receiverInfo!!.exported)
    assertFalse(receiverInfo.directBootAware)
  }
}
