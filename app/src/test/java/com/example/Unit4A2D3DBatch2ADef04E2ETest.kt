package com.example

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.core.di.DefaultAppContainer
import com.example.core.preferences.AppLanguage
import com.example.core.time.GreetingPeriod
import com.example.core.time.LocalClock
import com.example.data.local.db.PendingIntentIdentitySnapshot
import com.example.data.local.db.YawmiDatabase
import com.example.data.local.entity.AlarmSyncOutboxEntity
import com.example.data.local.entity.DeliveryState
import com.example.data.local.entity.OutboxAction
import com.example.data.local.entity.OutboxStatus
import com.example.data.local.entity.ReminderOwnerType
import com.example.data.local.entity.ReminderScheduleState
import com.example.data.local.entity.TaskReminderDeliveryEntity
import com.example.data.local.entity.TaskStatus
import com.example.domain.task.ReminderTriggerCalculator
import com.example.domain.task.TaskDomainResult
import com.example.domain.task.TaskInput
import com.example.domain.task.TaskReminderResult
import com.example.reminder.AlarmCancelOutcome
import com.example.reminder.AlarmFireOutcome
import com.example.reminder.AlarmReceiver
import com.example.reminder.AlarmScheduleOutcome
import com.example.reminder.AlarmScheduler
import com.example.reminder.AndroidNotificationPoster
import com.example.reminder.DeliveryOutcome
import com.example.reminder.DrainOutcome
import com.example.reminder.NotificationActionReceiver
import com.example.reminder.NotificationChannelManager
import com.example.reminder.NotificationPostOutcome
import com.example.reminder.NotificationPoster
import com.example.reminder.OutboxDispatchHooks
import com.example.reminder.OutboxDrainTrigger
import com.example.reminder.PendingIntentFactory
import com.example.reminder.ReconcileTriggerReason
import com.example.reminder.SystemReconcileReceiver
import com.example.reminder.SystemReconciliationTrigger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

private class Def04E2EClock(
  initialEpochMs: Long = Instant.parse("2026-11-28T09:00:00Z").toEpochMilli(),
  private val zone: ZoneId = ZoneId.of("UTC"),
) : LocalClock {
  var epochMs: Long = initialEpochMs

  override fun currentEpochMs(): Long = epochMs

  override fun currentDate(): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

  override fun currentTime(): LocalTime = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime()

  override fun greetingPeriod(): GreetingPeriod = GreetingPeriod.MORNING

  override fun zoneId(): ZoneId = zone
}

private class FakeSystemBoundaryAlarmScheduler(
  private val pendingIntentFactory: PendingIntentFactory,
  var exactAllowed: Boolean = true,
) : AlarmScheduler {
  data class ActiveAlarmRecord(
    val entry: AlarmSyncOutboxEntity,
    val triggerAtEpochMs: Long,
    val intent: Intent,
  )

  data class ScheduleCall(
    val reminderId: String,
    val seq: Long,
    val targetRevision: Long?,
    val triggerAtEpochMs: Long,
  )

  data class CancelCall(
    val reminderId: String,
    val seq: Long,
    val targetRevision: Long?,
  )

  val activeAlarms = linkedMapOf<String, ActiveAlarmRecord>()
  val scheduleCalls = mutableListOf<ScheduleCall>()
  val cancelCalls = mutableListOf<CancelCall>()

  override fun canScheduleExactAlarms(): Boolean = exactAllowed

  override fun scheduleAlarm(
    entry: AlarmSyncOutboxEntity,
    triggerAtEpochMs: Long,
  ): AlarmScheduleOutcome {
    val builtIntent = pendingIntentFactory.buildIntent(entry)
    activeAlarms[entry.pendingIntentIdentityKey] =
      ActiveAlarmRecord(
        entry = entry,
        triggerAtEpochMs = triggerAtEpochMs,
        intent = builtIntent,
      )
    scheduleCalls +=
      ScheduleCall(
        reminderId = entry.reminderId,
        seq = entry.seq,
        targetRevision = entry.targetScheduleRevision,
        triggerAtEpochMs = triggerAtEpochMs,
      )
    return if (exactAllowed) {
      AlarmScheduleOutcome.ScheduledExact(triggerAtEpochMs)
    } else {
      AlarmScheduleOutcome.ScheduledInexact(triggerAtEpochMs, "SCHEDULE_EXACT_ALARM denied")
    }
  }

  override fun cancelAlarm(entry: AlarmSyncOutboxEntity): AlarmCancelOutcome {
    activeAlarms.remove(entry.pendingIntentIdentityKey)
    cancelCalls +=
      CancelCall(
        reminderId = entry.reminderId,
        seq = entry.seq,
        targetRevision = entry.targetScheduleRevision,
      )
    return AlarmCancelOutcome.Cancelled
  }
}

private class SpyE2ENotificationPoster(
  private val delegate: NotificationPoster,
) : NotificationPoster {
  data class PostedCall(
    val tag: String,
    val id: Int,
    val notification: Notification,
  )

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
class Unit4A2D3DBatch2ADef04E2ETest {

  private lateinit var context: Context
  private lateinit var app: Application
  private lateinit var notificationManager: NotificationManager
  private lateinit var db: YawmiDatabase
  private lateinit var clock: Def04E2EClock
  private lateinit var fakeAlarmScheduler: FakeSystemBoundaryAlarmScheduler
  private lateinit var spyPoster: SpyE2ENotificationPoster
  private lateinit var container: DefaultAppContainer
  private var drainRequests = 0

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    app = context as Application
    notificationManager = context.getSystemService(NotificationManager::class.java)
    db = YawmiDatabase.inMemory(context)
    clock = Def04E2EClock()
    fakeAlarmScheduler = FakeSystemBoundaryAlarmScheduler(PendingIntentFactory(context))
    spyPoster = SpyE2ENotificationPoster(AndroidNotificationPoster(context))
    drainRequests = 0

    Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    Shadows.shadowOf(notificationManager).setNotificationsEnabled(true)
    NotificationChannelManager(context).ensureChannelsCreated(AppLanguage.ENGLISH)

    container =
      DefaultAppContainer(
        context = context,
        databaseProvider = { db },
        localClockProvider = { clock },
        alarmSchedulerProvider = { _, _ -> fakeAlarmScheduler },
        outboxDrainTriggerProvider = {
          object : OutboxDrainTrigger {
            override suspend fun requestDrainNow() {
              drainRequests++
            }

            override fun requestDrainAsync() {
              drainRequests++
            }

            override suspend fun scheduleRetry(delayMs: Long, observedDrainEpoch: Long) = Unit

            override suspend fun currentDrainEpoch(): Long = 0L
          }
        },
        notificationPosterProvider = { spyPoster },
        systemReconciliationTriggerProvider = { SystemReconciliationTrigger.NoOp },
      )
  }

  @After
  fun tearDown() {
    AlarmReceiver.handlerOverrideForTesting = null
    NotificationActionReceiver.handlerOverrideForTesting = null
    SystemReconcileReceiver.triggerOverrideForTesting = null
    if (db.isOpen) {
      db.close()
    }
  }

  @Test
  fun def04_e2e_cancelThenReopenBeforeDrain_keepsReminderCancelledAndBlocksStaleAlarmAndReconcileUntilManualReenable() =
    runBlocking {
      // 1-4. Create active scheduled task with enabled reminder and drain initial schedule.
      val task =
        (container.taskUseCases.createTask(
          TaskInput(
            title = "Quarterly tax review",
            scheduledDate = "2026-11-28",
            startTime = "12:00",
          )
        ) as TaskDomainResult.Success)
          .value
      val createdReminder =
        (container.taskReminderUseCases.createRelativeReminder(
          taskId = task.id,
          offsetMinutes = 15,
        ) as TaskReminderResult.Success)
          .value
      assertEquals("2026-11-28", createdReminder.triggerDate)
      assertEquals("11:45", createdReminder.triggerTime)
      assertEquals(1L, createdReminder.scheduleRevision)
      assertTrue(createdReminder.isEnabled)
      assertEquals(ReminderScheduleState.PENDING_SYNC, createdReminder.scheduleState)

      val initialDrain = container.alarmOutboxDispatcher.drain("def04-e2e-initial")
      assertTrue(initialDrain is DrainOutcome.Completed)
      assertEquals(1, (initialDrain as DrainOutcome.Completed).succeededCount)
      assertEquals(1, fakeAlarmScheduler.activeAlarms.size)

      val activeRev1Record = fakeAlarmScheduler.activeAlarms.values.single()
      val staleRev1AlarmIntent = Intent(activeRev1Record.intent)
      assertEquals(
        1L,
        staleRev1AlarmIntent.getLongExtra(PendingIntentFactory.EXTRA_SCHEDULE_REVISION, -1L),
      )
      val afterInitialReminder =
        container.reminderSyncRepository.getTaskReminders(task.id).single()
      assertEquals(ReminderScheduleState.SCHEDULED_EXACT, afterInitialReminder.scheduleState)

      // 5. Cancel task, then immediately reopen BEFORE draining cancel outbox commands.
      val cancelResult = container.taskUseCases.cancelTask(task.id)
      assertTrue(cancelResult is TaskDomainResult.Success)
      assertEquals(TaskStatus.CANCELLED, (cancelResult as TaskDomainResult.Success).value.status)

      val reopenResult = container.taskUseCases.reopenTask(task.id)
      assertTrue(reopenResult is TaskDomainResult.Success)
      assertEquals(TaskStatus.PENDING, (reopenResult as TaskDomainResult.Success).value.status)

      // Before drain: cancel commands are pending and no SCHEDULE_ALARM was staged by reopen.
      val preDrainOutbox = db.alarmSyncOutboxDao().getAllOrdered()
      assertTrue(
        preDrainOutbox.any {
          it.action == OutboxAction.CANCEL_ALARM &&
            it.status == OutboxStatus.PENDING &&
            it.targetScheduleRevision == 2L
        }
      )
      assertTrue(
        preDrainOutbox.none {
          it.action == OutboxAction.SCHEDULE_ALARM && it.status == OutboxStatus.PENDING
        }
      )

      // 6. Drain outbox after cancel + reopen.
      val cancelDrain = container.alarmOutboxDispatcher.drain("def04-e2e-cancel-reopen")
      assertTrue(cancelDrain is DrainOutcome.Completed)

      // 7. Verify alarm removed, reminder NOT auto-re-enabled, and no valid SCHEDULE_ALARM after cancel.
      assertTrue(fakeAlarmScheduler.activeAlarms.isEmpty())
      assertTrue(
        fakeAlarmScheduler.cancelCalls.any {
          it.reminderId == createdReminder.id && it.targetRevision == 2L
        }
      )

      val postDrainReminder =
        container.reminderSyncRepository.getTaskReminders(task.id).single()
      assertFalse(postDrainReminder.isEnabled)
      assertEquals(ReminderScheduleState.CANCELLED, postDrainReminder.scheduleState)
      assertEquals(2L, postDrainReminder.scheduleRevision)

      val postDrainOutbox = db.alarmSyncOutboxDao().getAllOrdered()
      val cancelAlarmSeq =
        postDrainOutbox
          .first { it.action == OutboxAction.CANCEL_ALARM && it.targetScheduleRevision == 2L }
          .seq
      assertTrue(
        postDrainOutbox.none {
          it.action == OutboxAction.SCHEDULE_ALARM &&
            (it.status == OutboxStatus.PENDING || it.status == OutboxStatus.CLAIMED)
        }
      )
      assertTrue(
        postDrainOutbox.none {
          it.action == OutboxAction.SCHEDULE_ALARM && it.seq > cancelAlarmSeq
        }
      )
      assertTrue(
        postDrainOutbox.none {
          it.action == OutboxAction.SCHEDULE_ALARM &&
            (it.targetScheduleRevision ?: 0L) >= 2L
        }
      )

      // 8. Advance clock into trigger firing window (within 60s early tolerance of 11:45:00)
      //    and pass the old rev=1 alarm intent -> must be rejected with 0 notifications.
      clock.epochMs = Instant.parse("2026-11-28T11:44:30Z").toEpochMilli()
      val staleFireOutcome = container.alarmFireHandler.handleIntent(staleRev1AlarmIntent)
      assertTrue(staleFireOutcome is AlarmFireOutcome.Delivered)
      val deliveryOutcome = (staleFireOutcome as AlarmFireOutcome.Delivered).outcome
      assertTrue(deliveryOutcome is DeliveryOutcome.Skipped)
      assertTrue(spyPoster.postedCalls.isEmpty())
      assertTrue(Shadows.shadowOf(notificationManager).allNotifications.isEmpty())
      assertFalse(db.reminderDeliveryDao().hasPostedTaskDeliveryForRevision(createdReminder.id, 1L))
      assertFalse(db.reminderDeliveryDao().hasPostedTaskDeliveryForRevision(createdReminder.id, 2L))

      // 9. Run crash recovery and system reconciler -> must NOT recreate old alarm or notify.
      val recoverySummary = container.notificationDeliveryEngine.recoverPendingDeliveries()
      assertEquals(0, recoverySummary.repostedCount)
      assertEquals(0, recoverySummary.recoveredFromSystemCount)

      val reconcileSummary =
        container.reminderReconciler.reconcile(reason = ReconcileTriggerReason.APP_STARTUP)
      assertEquals(0, reconcileSummary.stagedScheduleCount)
      assertEquals(0, reconcileSummary.deliveredWithinGraceCount)
      assertTrue(fakeAlarmScheduler.activeAlarms.isEmpty())
      assertTrue(spyPoster.postedCalls.isEmpty())

      val afterReconcileReminder =
        container.reminderSyncRepository.getTaskReminders(task.id).single()
      assertFalse(afterReconcileReminder.isEnabled)
      assertEquals(ReminderScheduleState.CANCELLED, afterReconcileReminder.scheduleState)
      assertEquals(2L, afterReconcileReminder.scheduleRevision)

      // 10. Explicitly re-enable via TaskReminderUseCases, drain outbox, verify new rev=3 schedule.
      val reenableResult =
        container.taskReminderUseCases.setReminderEnabled(
          taskId = task.id,
          reminderId = createdReminder.id,
          enabled = true,
        )
      assertTrue(reenableResult is TaskReminderResult.Success)
      val reenabledReminder = (reenableResult as TaskReminderResult.Success).value
      assertTrue(reenabledReminder.isEnabled)
      assertEquals(3L, reenabledReminder.scheduleRevision)
      assertEquals(ReminderScheduleState.PENDING_SYNC, reenabledReminder.scheduleState)

      val reenableDrain = container.alarmOutboxDispatcher.drain("def04-e2e-reenable")
      assertTrue(reenableDrain is DrainOutcome.Completed)
      assertEquals(1, (reenableDrain as DrainOutcome.Completed).succeededCount)

      assertEquals(1, fakeAlarmScheduler.activeAlarms.size)
      val activeRev3Record = fakeAlarmScheduler.activeAlarms.values.single()
      assertEquals(3L, activeRev3Record.entry.targetScheduleRevision)
      assertEquals(
        3L,
        activeRev3Record.intent.getLongExtra(PendingIntentFactory.EXTRA_SCHEDULE_REVISION, -1L),
      )

      val finalReminder = container.reminderSyncRepository.getTaskReminders(task.id).single()
      assertTrue(finalReminder.isEnabled)
      assertEquals(ReminderScheduleState.SCHEDULED_EXACT, finalReminder.scheduleState)
      assertEquals(3L, finalReminder.scheduleRevision)

      val finalOutbox = db.alarmSyncOutboxDao().getAllOrdered()
      val postCancelSchedules =
        finalOutbox.filter {
          it.action == OutboxAction.SCHEDULE_ALARM && it.seq > cancelAlarmSeq
        }
      assertEquals(1, postCancelSchedules.size)
      assertEquals(3L, postCancelSchedules.single().targetScheduleRevision)
      assertEquals(OutboxStatus.SUCCEEDED, postCancelSchedules.single().status)

      // Bonus check: firing the new rev=3 alarm at 11:45:00 posts cleanly, leaving rev=1 unused.
      clock.epochMs = Instant.parse("2026-11-28T11:45:00Z").toEpochMilli()
      val validFireOutcome = container.alarmFireHandler.handleIntent(activeRev3Record.intent)
      assertTrue(validFireOutcome is AlarmFireOutcome.Delivered)
      assertTrue(
        (validFireOutcome as AlarmFireOutcome.Delivered).outcome is DeliveryOutcome.PostedToSystem
      )
      assertEquals(1, spyPoster.postedCalls.size)
      assertFalse(db.reminderDeliveryDao().hasPostedTaskDeliveryForRevision(createdReminder.id, 1L))
      assertTrue(db.reminderDeliveryDao().hasPostedTaskDeliveryForRevision(createdReminder.id, 3L))
    }

  @Test
  fun def04_e2e_drainOrderingVariations_undrainedScheduleSupersededAndMidFlightCasCompensated() =
    runBlocking {
      // Variation A: Create task + reminder (rev=1), cancel + reopen BEFORE initial SCHEDULE_ALARM ever drains.
      val taskA =
        (container.taskUseCases.createTask(
          TaskInput(
            title = "Order variation A",
            scheduledDate = "2026-11-28",
            startTime = "14:00",
          )
        ) as TaskDomainResult.Success)
          .value
      val reminderA =
        (container.taskReminderUseCases.createRelativeReminder(
          taskId = taskA.id,
          offsetMinutes = 30,
        ) as TaskReminderResult.Success)
          .value

      // Cancel + reopen while rev=1 SCHEDULE_ALARM is still PENDING in outbox.
      assertTrue(container.taskUseCases.cancelTask(taskA.id) is TaskDomainResult.Success)
      assertTrue(container.taskUseCases.reopenTask(taskA.id) is TaskDomainResult.Success)

      val drainA = container.alarmOutboxDispatcher.drain("def04-order-var-a")
      assertTrue(drainA is DrainOutcome.Completed)

      // Initial SCHEDULE_ALARM must be SUPERSEDED and never even invoke AlarmScheduler.scheduleAlarm.
      assertTrue(fakeAlarmScheduler.scheduleCalls.none { it.reminderId == reminderA.id })
      assertTrue(fakeAlarmScheduler.activeAlarms.isEmpty())

      val persistedA = container.reminderSyncRepository.getTaskReminders(taskA.id).single()
      assertFalse(persistedA.isEnabled)
      assertEquals(ReminderScheduleState.CANCELLED, persistedA.scheduleState)
      assertEquals(2L, persistedA.scheduleRevision)

      val outboxA = db.alarmSyncOutboxDao().getAllOrdered().filter { it.reminderId == reminderA.id }
      val rev1ScheduleA =
        outboxA.first { it.action == OutboxAction.SCHEDULE_ALARM && it.targetScheduleRevision == 1L }
      assertEquals(OutboxStatus.SUPERSEDED, rev1ScheduleA.status)
      assertEquals(
        OutboxStatus.SUCCEEDED,
        outboxA.first { it.action == OutboxAction.CANCEL_ALARM }.status,
      )

      // Even if a synthetic rev=1 intent arrives, it is rejected, and recovery/reconcile stay quiet.
      clock.epochMs = Instant.parse("2026-11-28T13:30:00Z").toEpochMilli()
      val syntheticRev1Intent = PendingIntentFactory(context).buildIntent(rev1ScheduleA)
      val staleOutcomeA = container.alarmFireHandler.handleIntent(syntheticRev1Intent)
      assertTrue(
        staleOutcomeA is AlarmFireOutcome.Delivered &&
          staleOutcomeA.outcome is DeliveryOutcome.Skipped
      )
      assertEquals(0, container.notificationDeliveryEngine.recoverPendingDeliveries().repostedCount)
      assertEquals(
        0,
        container.reminderReconciler.reconcile(ReconcileTriggerReason.MANUAL).stagedScheduleCount,
      )
      assertTrue(spyPoster.postedCalls.isEmpty())
      assertTrue(fakeAlarmScheduler.activeAlarms.isEmpty())

      // Variation B: Cancel + reopen interleaved MID-FLIGHT after external scheduleAlarm() and before CAS commit.
      clock.epochMs = Instant.parse("2026-11-28T09:30:00Z").toEpochMilli()
      val taskB =
        (container.taskUseCases.createTask(
          TaskInput(
            title = "Order variation B",
            scheduledDate = "2026-11-28",
            startTime = "16:00",
          )
        ) as TaskDomainResult.Success)
          .value
      val reminderB =
        (container.taskReminderUseCases.createRelativeReminder(
          taskId = taskB.id,
          offsetMinutes = 15,
        ) as TaskReminderResult.Success)
          .value

      val triggeredInterleave = AtomicBoolean(false)
      val hooks =
        object : OutboxDispatchHooks {
          override suspend fun onBeforeCommit(entry: AlarmSyncOutboxEntity) {
            if (
              entry.reminderId == reminderB.id &&
              entry.action == OutboxAction.SCHEDULE_ALARM &&
              triggeredInterleave.compareAndSet(false, true)
            ) {
              container.taskUseCases.cancelTask(taskB.id)
              container.taskUseCases.reopenTask(taskB.id)
            }
          }
        }

      val drainB =
        container.alarmOutboxDispatcher.drainWithHooks("def04-order-var-b", hooks)
      assertTrue(drainB is DrainOutcome.Completed)
      assertTrue(triggeredInterleave.get())

      // External scheduleAlarm ran once for rev=1, then CAS conflict + CANCEL_ALARM rev=2 cleaned it up.
      assertEquals(1, fakeAlarmScheduler.scheduleCalls.count { it.reminderId == reminderB.id })
      assertTrue(fakeAlarmScheduler.cancelCalls.any { it.reminderId == reminderB.id })
      assertTrue(fakeAlarmScheduler.activeAlarms.isEmpty())

      val persistedB = container.reminderSyncRepository.getTaskReminders(taskB.id).single()
      assertFalse(persistedB.isEnabled)
      assertEquals(ReminderScheduleState.CANCELLED, persistedB.scheduleState)
      assertEquals(2L, persistedB.scheduleRevision)

      val outboxB = db.alarmSyncOutboxDao().getAllOrdered().filter { it.reminderId == reminderB.id }
      val rev1ScheduleB =
        outboxB.first { it.action == OutboxAction.SCHEDULE_ALARM && it.targetScheduleRevision == 1L }
      assertEquals(OutboxStatus.SUPERSEDED, rev1ScheduleB.status)
      assertEquals(
        OutboxStatus.SUCCEEDED,
        outboxB.first { it.action == OutboxAction.CANCEL_ALARM }.status,
      )

      // Stale rev=1 intent & reconcile must stay inactive; only manual re-enable schedules rev=3.
      clock.epochMs = Instant.parse("2026-11-28T15:45:00Z").toEpochMilli()
      val staleOutcomeB =
        container.alarmFireHandler.handleIntent(
          PendingIntentFactory(context).buildIntent(rev1ScheduleB)
        )
      assertTrue(
        staleOutcomeB is AlarmFireOutcome.Delivered &&
          staleOutcomeB.outcome is DeliveryOutcome.Skipped
      )
      assertEquals(0, container.notificationDeliveryEngine.recoverPendingDeliveries().repostedCount)
      assertEquals(
        0,
        container.reminderReconciler.reconcile(ReconcileTriggerReason.APP_RESUME_CHECK)
          .stagedScheduleCount,
      )
      assertTrue(fakeAlarmScheduler.activeAlarms.isEmpty())
      assertTrue(spyPoster.postedCalls.isEmpty())

      val manualEnableB =
        container.taskReminderUseCases.setReminderEnabled(taskB.id, reminderB.id, enabled = true)
      assertTrue(manualEnableB is TaskReminderResult.Success)
      assertTrue(container.alarmOutboxDispatcher.drain("def04-order-var-b-reenable") is DrainOutcome.Completed)
      assertEquals(1, fakeAlarmScheduler.activeAlarms.size)
      assertEquals(
        3L,
        fakeAlarmScheduler.activeAlarms.values.single().entry.targetScheduleRevision,
      )
    }

  @Test
  fun def04_e2e_cancelDrainedBeforeReopenWithRecoverableDelivery_marksDeliveryStaleAndStaysCancelledUntilManualReenable() =
    runBlocking {
      val task =
        (container.taskUseCases.createTask(
          TaskInput(
            title = "Drained cancel before reopen",
            scheduledDate = "2026-11-28",
            startTime = "18:00",
          )
        ) as TaskDomainResult.Success)
          .value
      val reminder =
        (container.taskReminderUseCases.createRelativeReminder(
          taskId = task.id,
          offsetMinutes = 15,
        ) as TaskReminderResult.Success)
          .value

      assertTrue(container.alarmOutboxDispatcher.drain("def04-drained-first-init") is DrainOutcome.Completed)
      val staleRev1Intent = Intent(fakeAlarmScheduler.activeAlarms.values.single().intent)

      // Seed a pre-existing FAILED_RETRYABLE delivery from rev=1 (not touched by markPendingTaskDeliveriesStale).
      val occurrenceKeyRev1 =
        ReminderTriggerCalculator.buildOccurrenceKey(
          triggerDate = reminder.triggerDate,
          triggerTime = reminder.triggerTime,
          scheduleRevision = 1L,
          zoneId = clock.zoneId(),
        )
      val deliveryId = UUID.randomUUID().toString()
      db.reminderDeliveryDao().insertTaskDelivery(
        TaskReminderDeliveryEntity(
          id = deliveryId,
          taskReminderId = reminder.id,
          occurrenceKey = occurrenceKeyRev1,
          scheduledAtEpochMs = Instant.parse("2026-11-28T17:45:00Z").toEpochMilli(),
          firedScheduleRevision = 1L,
          state = DeliveryState.FAILED_RETRYABLE,
          notificationTag =
            PendingIntentIdentitySnapshot.buildNotificationTag(ReminderOwnerType.TASK, reminder.id),
          notificationId = PendingIntentIdentitySnapshot.DEFAULT_NOTIFICATION_ID,
          attemptCount = 1,
          lastAttemptAtEpochMs = clock.currentEpochMs(),
          lastError = "Transient failure prior to cancel",
          createdAtEpochMs = clock.currentEpochMs(),
          updatedAtEpochMs = clock.currentEpochMs(),
        )
      )

      // Cancel task AND drain cancel commands BEFORE reopening.
      assertTrue(container.taskUseCases.cancelTask(task.id) is TaskDomainResult.Success)
      assertTrue(
        container.alarmOutboxDispatcher.drain("def04-drained-first-cancel") is DrainOutcome.Completed
      )
      assertTrue(fakeAlarmScheduler.activeAlarms.isEmpty())

      assertTrue(container.taskUseCases.reopenTask(task.id) is TaskDomainResult.Success)
      val afterReopenReminder = container.reminderSyncRepository.getTaskReminders(task.id).single()
      assertFalse(afterReopenReminder.isEnabled)
      assertEquals(ReminderScheduleState.CANCELLED, afterReopenReminder.scheduleState)
      assertEquals(2L, afterReopenReminder.scheduleRevision)

      // Stale rev=1 alarm intent + recoverPendingDeliveries + reconcile must mark delivery STALE without posting.
      clock.epochMs = Instant.parse("2026-11-28T17:45:00Z").toEpochMilli()
      val fireResult = container.alarmFireHandler.handleIntent(staleRev1Intent)
      assertTrue(
        fireResult is AlarmFireOutcome.Delivered && fireResult.outcome is DeliveryOutcome.Skipped
      )

      val recovery = container.notificationDeliveryEngine.recoverPendingDeliveries()
      assertEquals(1, recovery.inspectedCount)
      assertEquals(1, recovery.markedStaleCount)
      assertEquals(0, recovery.repostedCount)
      assertEquals(
        DeliveryState.STALE,
        db.reminderDeliveryDao().getTaskDelivery(reminder.id, occurrenceKeyRev1)?.state,
      )

      val reconcile = container.reminderReconciler.reconcile(ReconcileTriggerReason.APP_STARTUP)
      assertEquals(0, reconcile.stagedScheduleCount)
      assertEquals(0, reconcile.deliveredWithinGraceCount)
      assertTrue(fakeAlarmScheduler.activeAlarms.isEmpty())
      assertTrue(spyPoster.postedCalls.isEmpty())

      // Only manual re-enable creates rev=3 schedule.
      clock.epochMs = Instant.parse("2026-11-28T17:30:00Z").toEpochMilli()
      assertTrue(
        container.taskReminderUseCases.setReminderEnabled(task.id, reminder.id, enabled = true)
          is TaskReminderResult.Success
      )
      assertTrue(
        container.alarmOutboxDispatcher.drain("def04-drained-first-reenable")
          is DrainOutcome.Completed
      )
      assertEquals(1, fakeAlarmScheduler.activeAlarms.size)
      assertEquals(
        3L,
        fakeAlarmScheduler.activeAlarms.values.single().entry.targetScheduleRevision,
      )
      val finalReminder = container.reminderSyncRepository.getTaskReminders(task.id).single()
      assertTrue(finalReminder.isEnabled)
      assertEquals(ReminderScheduleState.SCHEDULED_EXACT, finalReminder.scheduleState)
      assertEquals(3L, finalReminder.scheduleRevision)
    }
}
