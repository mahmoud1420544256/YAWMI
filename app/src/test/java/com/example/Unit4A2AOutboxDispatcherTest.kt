package com.example

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.core.di.DefaultAppContainer
import com.example.core.time.GreetingPeriod
import com.example.core.time.LocalClock
import com.example.data.local.db.PendingIntentIdentitySnapshot
import com.example.data.local.db.YawmiDatabase
import com.example.data.local.db.YawmiMetadataKeys
import com.example.data.local.entity.AlarmSyncOutboxEntity
import com.example.data.local.entity.GoalEntity
import com.example.data.local.entity.GoalStatus
import com.example.data.local.entity.HabitEntity
import com.example.data.local.entity.OutboxAction
import com.example.data.local.entity.OutboxStatus
import com.example.data.local.entity.OutboxSystemTarget
import com.example.data.local.entity.ReminderOwnerType
import com.example.data.local.entity.ReminderScheduleState
import com.example.data.local.entity.TaskEntity
import com.example.data.local.entity.TaskStatus
import com.example.reminder.AlarmCancelOutcome
import com.example.reminder.AlarmOutboxDispatcher
import com.example.reminder.AlarmScheduleOutcome
import com.example.reminder.AlarmScheduler
import com.example.reminder.AndroidAlarmManagerScheduler
import com.example.reminder.DispatcherLeaseManager
import com.example.reminder.DrainOutcome
import com.example.reminder.LeaseAcquireResult
import com.example.reminder.NotificationCancelOutcome
import com.example.reminder.NotificationCancellationExecutor
import com.example.reminder.OutboxDispatchHooks
import com.example.reminder.OutboxDispatcherWorker
import com.example.reminder.OutboxDrainTrigger
import com.example.reminder.PendingIntentFactory
import com.example.reminder.SystemNotificationCanceller
import com.example.reminder.WorkManagerOutboxTrigger
import com.example.reminder.YawmiWorkerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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

private class Controllable4A2AClock(
  initialEpochMs: Long = Instant.parse("2026-11-27T08:00:00Z").toEpochMilli(),
  private val zone: ZoneId = ZoneId.of("UTC"),
) : LocalClock {
  var epochMs: Long = initialEpochMs

  override fun currentEpochMs(): Long = epochMs

  override fun currentDate(): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

  override fun currentTime(): LocalTime = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime()

  override fun greetingPeriod(): GreetingPeriod = GreetingPeriod.MORNING

  override fun zoneId(): ZoneId = zone
}

private class RecordingAlarmScheduler(
  var exactAllowed: Boolean = true,
  var nextScheduleOutcome: ((AlarmSyncOutboxEntity, Long) -> AlarmScheduleOutcome)? = null,
  var nextCancelOutcome: ((AlarmSyncOutboxEntity) -> AlarmCancelOutcome)? = null,
) : AlarmScheduler {
  data class ScheduledCall(val entry: AlarmSyncOutboxEntity, val triggerEpochMs: Long)

  val scheduledCalls = mutableListOf<ScheduledCall>()
  val cancelledCalls = mutableListOf<AlarmSyncOutboxEntity>()

  override fun canScheduleExactAlarms(): Boolean = exactAllowed

  override fun scheduleAlarm(
    entry: AlarmSyncOutboxEntity,
    triggerAtEpochMs: Long,
  ): AlarmScheduleOutcome {
    scheduledCalls += ScheduledCall(entry, triggerAtEpochMs)
    nextScheduleOutcome?.let { return it(entry, triggerAtEpochMs) }
    return if (exactAllowed) {
      AlarmScheduleOutcome.ScheduledExact(triggerAtEpochMs)
    } else {
      AlarmScheduleOutcome.ScheduledInexact(triggerAtEpochMs, "Exact alarm permission denied")
    }
  }

  override fun cancelAlarm(entry: AlarmSyncOutboxEntity): AlarmCancelOutcome {
    cancelledCalls += entry
    nextCancelOutcome?.let { return it(entry) }
    return AlarmCancelOutcome.Cancelled
  }
}

private class RecordingNotificationCanceller : NotificationCancellationExecutor {
  data class CancelCall(val tag: String?, val id: Int)

  val calls = mutableListOf<CancelCall>()
  var failNext: Boolean = false

  override fun cancelNotification(tag: String?, notificationId: Int): NotificationCancelOutcome {
    calls += CancelCall(tag, notificationId)
    if (failNext) {
      return NotificationCancelOutcome.FailedRetryable("Simulated notification failure")
    }
    return NotificationCancelOutcome.Cancelled
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Unit4A2AOutboxDispatcherTest {

  private lateinit var context: Context
  private lateinit var db: YawmiDatabase
  private lateinit var clock: Controllable4A2AClock
  private lateinit var scheduler: RecordingAlarmScheduler
  private lateinit var notifCanceller: RecordingNotificationCanceller

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = YawmiDatabase.inMemory(context)
    clock = Controllable4A2AClock()
    scheduler = RecordingAlarmScheduler()
    notifCanceller = RecordingNotificationCanceller()
  }

  @After
  fun tearDown() {
    db.close()
  }

  private fun newContainer(
    trigger: OutboxDrainTrigger = OutboxDrainTrigger.NoOp,
    alarmSchedulerOverride: AlarmScheduler = scheduler,
    notificationCancellerOverride: NotificationCancellationExecutor = notifCanceller,
  ): DefaultAppContainer {
    return DefaultAppContainer(
      context = context,
      databaseProvider = { db },
      localClockProvider = { clock },
      alarmSchedulerProvider = { _, _ -> alarmSchedulerOverride },
      notificationCancellerProvider = { notificationCancellerOverride },
      outboxDrainTriggerProvider = { trigger },
    )
  }

  private suspend fun seedTaskWithReminder(
    container: DefaultAppContainer,
    taskId: String = "task-1",
    reminderId: String = "rem-1",
    triggerDate: String = "2026-11-27",
    triggerTime: String = "09:30",
  ) {
    val now = clock.currentEpochMs()
    container.taskRepository.insert(
      TaskEntity(
        id = taskId,
        title = "Pay bills",
        scheduledDate = triggerDate,
        startTime = triggerTime,
        status = TaskStatus.PENDING,
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

  private fun awaitConditionWithMainLooper(
    timeoutMs: Long = 10_000L,
    condition: () -> Boolean,
  ) {
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

  private fun awaitWorkFinished(wm: WorkManager, uniqueName: String, minCount: Int = 1) {
    awaitConditionWithMainLooper {
      val infos = runBlocking { wm.getWorkInfosForUniqueWorkFlow(uniqueName).first() }
      infos.size >= minCount && infos.all { it.state.isFinished }
    }
  }

  // =========================================================================
  // 1. Lease Lifecycle & Generation Fencing
  // =========================================================================

  @Test
  fun leaseLifecycle_conditionalRelease_preventsOldWorkerReleasingNewerGeneration() = runBlocking<Unit> {
    val leaseManager =
      DispatcherLeaseManager(
        database = db,
        appMetadataDao = db.appMetadataDao(),
        alarmSyncOutboxDao = db.alarmSyncOutboxDao(),
        localClock = clock,
      )

    val first = leaseManager.acquireOrRenewLease("worker-req-1") as LeaseAcquireResult.Acquired
    assertEquals(1L, first.token.generation)
    assertEquals("worker-req-1", db.appMetadataDao().getValue(YawmiMetadataKeys.DISPATCHER_LEASE_OWNER))

    val held = leaseManager.acquireOrRenewLease("worker-req-2")
    assertTrue(held is LeaseAcquireResult.HeldByOther)

    // Advance clock past lease expiry and let worker-req-2 acquire gen 2
    clock.epochMs += DispatcherLeaseManager.DEFAULT_LEASE_DURATION_MS + 1L
    val second = leaseManager.acquireOrRenewLease("worker-req-2") as LeaseAcquireResult.Acquired
    assertEquals(2L, second.token.generation)

    // Old worker attempts release -> rejected, worker-req-2 lease untouched
    assertFalse(leaseManager.releaseLease(first.token))
    assertEquals("worker-req-2", db.appMetadataDao().getValue(YawmiMetadataKeys.DISPATCHER_LEASE_OWNER))
    assertEquals("2", db.appMetadataDao().getValue(YawmiMetadataKeys.DISPATCHER_LEASE_GENERATION))

    // Current owner releases cleanly and preserves generation = 2
    assertTrue(leaseManager.releaseLease(second.token))
    assertEquals("", db.appMetadataDao().getValue(YawmiMetadataKeys.DISPATCHER_LEASE_OWNER))
    assertEquals("0", db.appMetadataDao().getValue(YawmiMetadataKeys.DISPATCHER_LEASE_UNTIL_EPOCH_MS))
    assertEquals("2", db.appMetadataDao().getValue(YawmiMetadataKeys.DISPATCHER_LEASE_GENERATION))
  }

  @Test
  fun staleClaimedRecovery_doesNotTouchValidLeaseClaimedRows_andRecoversAfterExpiryEvenForSameWorkRequest() =
    runBlocking<Unit> {
      val container = newContainer()
      seedTaskWithReminder(container)
      val outboxEntry = db.alarmSyncOutboxDao().getAllOrdered().single()

      val leaseManager = container.dispatcherLeaseManager
      val workRequestId = UUID.randomUUID().toString()
      val gen1 = (leaseManager.acquireOrRenewLease(workRequestId) as LeaseAcquireResult.Acquired).token
      assertEquals(1L, gen1.generation)

      val claimedRows =
        db.alarmSyncOutboxDao().claimPendingFenced(
          id = outboxEntry.id,
          leaseOwner = workRequestId,
          leaseGeneration = gen1.generation,
          leaseGenerationStr = gen1.generation.toString(),
          nowEpochMs = clock.currentEpochMs(),
        )
      assertEquals(1, claimedRows)
      assertEquals(OutboxStatus.CLAIMED, db.alarmSyncOutboxDao().getById(outboxEntry.id)?.status)

      // While lease is valid, another worker cannot steal or recover CLAIMED rows
      clock.epochMs += 5_000L
      val held = leaseManager.acquireOrRenewLease("other-worker")
      assertTrue(held is LeaseAcquireResult.HeldByOther)
      assertEquals(OutboxStatus.CLAIMED, db.alarmSyncOutboxDao().getById(outboxEntry.id)?.status)

      // While lease is still valid, same WorkRequest renewal does NOT increment generation or reset CLAIMED
      val renewedSame = leaseManager.acquireOrRenewLease(workRequestId) as LeaseAcquireResult.Acquired
      assertEquals(1L, renewedSame.token.generation)
      assertEquals(0, renewedSame.recoveredStaleClaimedCount)
      assertEquals(OutboxStatus.CLAIMED, db.alarmSyncOutboxDao().getById(outboxEntry.id)?.status)

      // Advance past expiry: retrying SAME WorkRequest ID gets generation 2 and recovers CLAIMED row
      clock.epochMs = renewedSame.token.untilEpochMs + 100L
      val retriedSame = leaseManager.acquireOrRenewLease(workRequestId) as LeaseAcquireResult.Acquired
      assertEquals(2L, retriedSame.token.generation)
      assertEquals(1, retriedSame.recoveredStaleClaimedCount)
      val recoveredEntry = db.alarmSyncOutboxDao().getById(outboxEntry.id)!!
      assertEquals(OutboxStatus.PENDING, recoveredEntry.status)
      assertNull(recoveredEntry.claimedLeaseGeneration)
    }

  @Test
  fun outboxDispatcherWorker_returnsRetry_whenLeaseHeldByOther() = runBlocking<Unit> {
    val container = newContainer()
    seedTaskWithReminder(container)
    // Hold active lease by another owner
    val held = container.dispatcherLeaseManager.acquireOrRenewLease("active-other-owner")
    assertTrue(held is LeaseAcquireResult.Acquired)

    val trigger = WorkManagerOutboxTrigger(context)
    val factory =
      YawmiWorkerFactory(
        dispatcherProvider = { container.alarmOutboxDispatcher },
        drainTriggerProvider = { trigger },
        hasActiveQueueProvider = { db.alarmSyncOutboxDao().hasActiveQueue() },
      )
    WorkManagerTestInitHelper.initializeTestWorkManager(
      context,
      Configuration.Builder()
        .setMinimumLoggingLevel(android.util.Log.DEBUG)
        .setExecutor(SynchronousExecutor())
        .setWorkerFactory(factory)
        .build(),
    )

    val worker =
      TestListenableWorkerBuilder<OutboxDispatcherWorker>(context)
        .setWorkerFactory(factory)
        .build()

    val result = worker.doWork()
    assertEquals(ListenableWorker.Result.retry(), result)
    // Pending entry must remain PENDING (never silently dropped via success)
    assertEquals(OutboxStatus.PENDING, db.alarmSyncOutboxDao().getAllOrdered().single().status)
  }

  @Test
  fun backoffOnlyQueue_releasesLeaseCleanly_andSchedulesDelayedRetryRelay() = runBlocking<Unit> {
    val container = newContainer()
    seedTaskWithReminder(container)
    scheduler.nextScheduleOutcome = { _, _ -> AlarmScheduleOutcome.FailedRetryable("Transient Binder error") }

    val trigger = WorkManagerOutboxTrigger(context)
    WorkManagerTestInitHelper.initializeTestWorkManager(
      context,
      Configuration.Builder()
        .setExecutor(SynchronousExecutor())
        .setWorkerFactory(
          YawmiWorkerFactory(
            dispatcherProvider = { container.alarmOutboxDispatcher },
            drainTriggerProvider = { trigger },
            hasActiveQueueProvider = { db.alarmSyncOutboxDao().hasActiveQueue() },
          )
        )
        .build(),
    )

    val workerEpoch = trigger.currentDrainEpoch()
    val outcome = container.alarmOutboxDispatcher.drain("worker-backoff-1")
    assertTrue(outcome is DrainOutcome.BackoffScheduled)
    val backoffOutcome = outcome as DrainOutcome.BackoffScheduled
    assertEquals(AlarmOutboxDispatcher.BASE_BACKOFF_MS, backoffOutcome.delayMs)

    // Lease must be released cleanly after entering backoff-only state
    assertEquals("", db.appMetadataDao().getValue(YawmiMetadataKeys.DISPATCHER_LEASE_OWNER))
    val entry = db.alarmSyncOutboxDao().getAllOrdered().single()
    assertEquals(OutboxStatus.PENDING, entry.status)
    assertEquals(1, entry.attemptCount)
    assertEquals(ReminderScheduleState.PENDING_SYNC, db.reminderDao().getTaskReminderById("rem-1")!!.scheduleState)

    // Scheduling retry enqueues RETRY_WORK_NAME
    trigger.scheduleRetry(backoffOutcome.delayMs, workerEpoch)
    val retryInfos =
      WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow(WorkManagerOutboxTrigger.RETRY_WORK_NAME)
        .first()
    assertEquals(1, retryInfos.size)
    assertEquals(WorkInfo.State.ENQUEUED, retryInfos.single().state)
  }

  // =========================================================================
  // 2. WorkManager Trigger Coalescing, Race Protection & Crash Recovery
  // =========================================================================

  @Test
  fun workManagerTrigger_burstEnqueueWithRunningWorker_coalescesToMaxOneBlockedFollower() = runBlocking<Unit> {
    val workerStarted = CompletableDeferred<Unit>()
    val releaseWorker = CompletableDeferred<Unit>()

    // Use a test factory that blocks the first OutboxDispatcherWorker in RUNNING state
    var blockedOnce = false
    val blockingDispatcherContainer = newContainer()
    val testTrigger = WorkManagerOutboxTrigger(context)
    val gateFactory =
      object : androidx.work.WorkerFactory() {
        override fun createWorker(
          appContext: Context,
          workerClassName: String,
          workerParameters: androidx.work.WorkerParameters,
        ): ListenableWorker? {
          if (workerClassName == OutboxDispatcherWorker::class.java.name && !blockedOnce) {
            blockedOnce = true
            return object : androidx.work.CoroutineWorker(appContext, workerParameters) {
              override suspend fun doWork(): Result {
                workerStarted.complete(Unit)
                releaseWorker.await()
                return Result.success()
              }
            }
          }
          return YawmiWorkerFactory(
            dispatcherProvider = { blockingDispatcherContainer.alarmOutboxDispatcher },
            drainTriggerProvider = { testTrigger },
            hasActiveQueueProvider = { db.alarmSyncOutboxDao().hasActiveQueue() },
          ).createWorker(appContext, workerClassName, workerParameters)
        }
      }

    WorkManagerTestInitHelper.initializeTestWorkManager(
      context,
      Configuration.Builder()
        .setExecutor(Executors.newFixedThreadPool(4))
        .setTaskExecutor(Executors.newFixedThreadPool(4))
        .setWorkerFactory(gateFactory)
        .build(),
    )

    // Start initial worker so it moves into RUNNING and blocks
    testTrigger.requestDrainNow()
    awaitConditionWithMainLooper { workerStarted.isCompleted }

    // Burst 12 concurrent triggers across Dispatchers.Default while worker 1 is RUNNING
    coroutineScope {
      (1..12).map { async(Dispatchers.Default) { testTrigger.requestDrainNow() } }.awaitAll()
    }

    val infos =
      WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow(WorkManagerOutboxTrigger.DRAIN_WORK_NAME)
        .first()
    val runningCount = infos.count { it.state == WorkInfo.State.RUNNING }
    val pendingFollowerCount =
      infos.count { it.state == WorkInfo.State.BLOCKED || it.state == WorkInfo.State.ENQUEUED }

    assertEquals(1, runningCount)
    assertEquals(1, pendingFollowerCount)

    releaseWorker.complete(Unit)
    awaitWorkFinished(WorkManager.getInstance(context), WorkManagerOutboxTrigger.DRAIN_WORK_NAME)
  }

  @Test
  fun workManagerTrigger_cancelsEnqueuedRetryOnImmediateDrain_andRejectsStaleRetryRace() = runBlocking<Unit> {
    WorkManagerTestInitHelper.initializeTestWorkManager(
      context,
      Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
    )
    val wm = WorkManager.getInstance(context)
    val testDriver = WorkManagerTestInitHelper.getTestDriver(context)!!
    val trigger = WorkManagerOutboxTrigger(context)

    val initialEpoch = trigger.currentDrainEpoch()
    trigger.scheduleRetry(delayMs = 30_000L, observedDrainEpoch = initialEpoch)
    val retryBefore =
      wm.getWorkInfosForUniqueWorkFlow(WorkManagerOutboxTrigger.RETRY_WORK_NAME).first()
    assertEquals(WorkInfo.State.ENQUEUED, retryBefore.single().state)

    // Simulate worker capturing an old epoch before an immediate requestDrainNow arrives
    val staleEpoch = trigger.currentDrainEpoch()
    trigger.requestDrainNow()

    // Retry must now be CANCELLED, and DRAIN_WORK_NAME must be enqueued
    val retryAfterImmediate =
      wm.getWorkInfosForUniqueWorkFlow(WorkManagerOutboxTrigger.RETRY_WORK_NAME).first()
    assertEquals(WorkInfo.State.CANCELLED, retryAfterImmediate.single().state)

    // Now simulate late stale scheduleRetry call racing after requestDrainNow()
    trigger.scheduleRetry(delayMs = 60_000L, observedDrainEpoch = staleEpoch)
    val retryAfterStaleCall =
      wm.getWorkInfosForUniqueWorkFlow(WorkManagerOutboxTrigger.RETRY_WORK_NAME).first()
    // Must remain CANCELLED — stale retry must NOT overwrite or re-create delayed retry
    assertEquals(WorkInfo.State.CANCELLED, retryAfterStaleCall.single().state)

    // Verify TestDriver can mark delay met on a fresh retry relay if scheduled at current epoch
    val latestEpoch = trigger.currentDrainEpoch()
    // Complete or cancel current drain first so no follower blocks retry scheduling
    wm.cancelUniqueWork(WorkManagerOutboxTrigger.DRAIN_WORK_NAME).result.get()
    trigger.scheduleRetry(delayMs = 15_000L, observedDrainEpoch = latestEpoch)
    val activeRetry =
      wm.getWorkInfosForUniqueWorkFlow(WorkManagerOutboxTrigger.RETRY_WORK_NAME).first().single()
    assertEquals(WorkInfo.State.ENQUEUED, activeRetry.state)
    testDriver.setInitialDelayMet(activeRetry.id)
  }

  @Test
  fun workManagerTrigger_doesNotBlockMainThread() = runBlocking<Unit> {
    WorkManagerTestInitHelper.initializeTestWorkManager(
      context,
      Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
    )
    val trigger = WorkManagerOutboxTrigger(context)

    // Execute on Main thread using Dispatchers.Main.immediate (must complete without .get() deadlock)
    assertTrue(Looper.myLooper() == Looper.getMainLooper())
    kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
      trigger.requestDrainNow()
    }

    val infos =
      WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow(WorkManagerOutboxTrigger.DRAIN_WORK_NAME)
        .first()
    assertTrue(infos.isNotEmpty())
  }

  @Test
  fun arrivalDuringRunningWorkerDrain_andStartupCrashGapRecovery_drainAllPendingCommands() = runBlocking<Unit> {
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

    // Stage command with NoOp trigger (simulates process crash between Room commit and WorkManager enqueue)
    val unTriggeredContainer = newContainer(trigger = OutboxDrainTrigger.NoOp)
    seedTaskWithReminder(unTriggeredContainer, taskId = "task-crash-gap", reminderId = "rem-crash-gap")
    assertTrue(db.alarmSyncOutboxDao().hasActiveQueue())

    // Simulate YawmiApplication startup check finding active queue and triggering WorkManager
    if (db.alarmSyncOutboxDao().hasActiveQueue()) {
      trigger.requestDrainNow()
    }

    val wm = WorkManager.getInstance(context)
    awaitWorkFinished(wm, WorkManagerOutboxTrigger.DRAIN_WORK_NAME)

    assertEquals(OutboxStatus.SUCCEEDED, db.alarmSyncOutboxDao().getAllOrdered().single().status)
    assertEquals(
      ReminderScheduleState.SCHEDULED_EXACT,
      db.reminderDao().getTaskReminderById("rem-crash-gap")!!.scheduleState,
    )
  }

  @Test
  fun arrivalAfterEmptyCheckBeforeWorkerExit_triggersBlockedFollowerAndDrains() = runBlocking<Unit> {
    val firstWorkerCheckedQueue = CompletableDeferred<Unit>()
    val releaseFirstWorker = CompletableDeferred<Unit>()
    val trigger = WorkManagerOutboxTrigger(context)
    val container = newContainer(trigger = trigger)

    var pass = 0
    val factory =
      object : androidx.work.WorkerFactory() {
        override fun createWorker(
          appContext: Context,
          workerClassName: String,
          workerParameters: androidx.work.WorkerParameters,
        ): ListenableWorker? {
          if (workerClassName == OutboxDispatcherWorker::class.java.name && pass == 0) {
            pass++
            return object : androidx.work.CoroutineWorker(appContext, workerParameters) {
              override suspend fun doWork(): Result {
                // First pass drains empty queue and releases lease, then pauses right before exit
                container.alarmOutboxDispatcher.drain(id.toString())
                firstWorkerCheckedQueue.complete(Unit)
                releaseFirstWorker.await()
                return Result.success()
              }
            }
          }
          return YawmiWorkerFactory(
            dispatcherProvider = { container.alarmOutboxDispatcher },
            drainTriggerProvider = { trigger },
            hasActiveQueueProvider = { db.alarmSyncOutboxDao().hasActiveQueue() },
          ).createWorker(appContext, workerClassName, workerParameters)
        }
      }

    WorkManagerTestInitHelper.initializeTestWorkManager(
      context,
      Configuration.Builder()
        .setExecutor(Executors.newFixedThreadPool(2))
        .setTaskExecutor(Executors.newFixedThreadPool(2))
        .setWorkerFactory(factory)
        .build(),
    )

    trigger.requestDrainNow()
    awaitConditionWithMainLooper { firstWorkerCheckedQueue.isCompleted }

    // While Worker 1 is still RUNNING after its drain & lease release, seed a new task reminder -> triggers requestDrainNow()
    seedTaskWithReminder(container, taskId = "task-late-arrival", reminderId = "rem-late-arrival")

    val wm = WorkManager.getInstance(context)
    val midInfos = wm.getWorkInfosForUniqueWorkFlow(WorkManagerOutboxTrigger.DRAIN_WORK_NAME).first()
    assertEquals(1, midInfos.count { it.state == WorkInfo.State.RUNNING })
    assertEquals(1, midInfos.count { it.state == WorkInfo.State.BLOCKED || it.state == WorkInfo.State.ENQUEUED })

    // Allow Worker 1 to exit; BLOCKED follower MUST run and drain the late-arriving command
    releaseFirstWorker.complete(Unit)
    awaitWorkFinished(wm, WorkManagerOutboxTrigger.DRAIN_WORK_NAME, minCount = 2)

    assertEquals(OutboxStatus.SUCCEEDED, db.alarmSyncOutboxDao().getAllOrdered().single().status)
    assertEquals(
      ReminderScheduleState.SCHEDULED_EXACT,
      db.reminderDao().getTaskReminderById("rem-late-arrival")!!.scheduleState,
    )
  }

  // =========================================================================
  // 3. Atomic Fenced Commit, Rollback & Post-CAS Compensation
  // =========================================================================

  @Test
  fun atomicFencedCommit_rollsBackOutboxAndCancelsAlarm_whenReminderUpdateFailsMidFlight() = runBlocking<Unit> {
    val container = newContainer()
    seedTaskWithReminder(container)
    val reminderId = "rem-1"

    val hooks =
      object : OutboxDispatchHooks {
        override suspend fun onBeforeCommit(entry: AlarmSyncOutboxEntity) {
          // Simulate concurrent deletion of reminder right before commit after AlarmManager ran
          db.reminderDao().deleteTaskReminderById(reminderId)
        }
      }

    val outcome = container.alarmOutboxDispatcher.drainWithHooks("worker-cas-rollback", hooks)
    assertTrue(outcome is DrainOutcome.Completed)

    // Because reminder update returned 0 rows inside transaction, outbox MUST NOT be SUCCEEDED
    val outboxEntry = db.alarmSyncOutboxDao().getAllOrdered().single()
    assertEquals(OutboxStatus.SUPERSEDED, outboxEntry.status)

    // Alarm was scheduled before commit, then compensated (cancelled) after CAS rollback
    assertEquals(1, scheduler.scheduledCalls.size)
    assertEquals(1, scheduler.cancelledCalls.size)
  }

  @Test
  fun atomicFencedCommit_rejectsOlderGeneration_andAllowsNewerGenerationIdempotentCompletion() =
    runBlocking<Unit> {
      val container = newContainer()
      seedTaskWithReminder(container)

      val hooks =
        object : OutboxDispatchHooks {
          override suspend fun onBeforeCommit(entry: AlarmSyncOutboxEntity) {
            // Simulate Worker 1 stalling past lease expiry and Worker 2 stealing lease (gen 2)
            clock.epochMs += DispatcherLeaseManager.DEFAULT_LEASE_DURATION_MS + 10L
            container.dispatcherLeaseManager.acquireOrRenewLease("worker-2")
          }
        }

      val w1Outcome = container.alarmOutboxDispatcher.drainWithHooks("worker-1", hooks)
      assertTrue(w1Outcome is DrainOutcome.RetryableError)

      // Outbox must be back to PENDING (recovered by worker-2's gen 2 takeover), NOT SUCCEEDED
      val afterTakeover = db.alarmSyncOutboxDao().getAllOrdered().single()
      assertEquals(OutboxStatus.PENDING, afterTakeover.status)

      // Worker 2 drains cleanly and idempotently finishes
      val w2Outcome = container.alarmOutboxDispatcher.drain("worker-2")
      assertTrue(w2Outcome is DrainOutcome.Completed)
      assertEquals(OutboxStatus.SUCCEEDED, db.alarmSyncOutboxDao().getAllOrdered().single().status)
      assertEquals(
        ReminderScheduleState.SCHEDULED_EXACT,
        db.reminderDao().getTaskReminderById("rem-1")!!.scheduleState,
      )
    }

  @Test
  fun postCasCompensation_cancelsOrphanAlarm_whenReminderDisabledRightBeforeCommit() = runBlocking<Unit> {
    val container = newContainer()
    seedTaskWithReminder(container)

    val hooks =
      object : OutboxDispatchHooks {
        override suspend fun onBeforeCommit(entry: AlarmSyncOutboxEntity) {
          // Concurrent update bumps revision to 2 and disables reminder (without draining yet)
          val rem = db.reminderDao().getTaskReminderById("rem-1")!!
          db.reminderDao().updateTaskReminder(
            rem.copy(
              isEnabled = false,
              scheduleRevision = 2L,
              scheduleState = ReminderScheduleState.PAUSED,
            )
          )
        }
      }

    container.alarmOutboxDispatcher.drainWithHooks("worker-rev-conflict", hooks)

    val entry = db.alarmSyncOutboxDao().getAllOrdered().single()
    assertEquals(OutboxStatus.SUPERSEDED, entry.status)
    assertEquals(1, scheduler.scheduledCalls.size)
    // Post-CAS compensation sees reminder disabled -> cancels orphan alarm immediately
    assertEquals(1, scheduler.cancelledCalls.size)
  }

  @Test
  fun crashAfterAlarmManagerBeforeCommit_recoversAfterLeaseExpiryIdempotently() = runBlocking<Unit> {
    val container = newContainer()
    seedTaskWithReminder(container)

    val crashHook =
      object : OutboxDispatchHooks {
        override suspend fun onBeforeCommit(entry: AlarmSyncOutboxEntity) {
          throw CancellationException("Simulated process death before commit")
        }
      }

    // Worker 1 executes external scheduleAlarm, then cancels cleanly via NonCancellable or simulated crash
    try {
      container.alarmOutboxDispatcher.drainWithHooks("worker-crash-1", crashHook)
    } catch (_: CancellationException) {
      // Expected
    }

    assertEquals(1, scheduler.scheduledCalls.size)
    assertEquals(ReminderScheduleState.PENDING_SYNC, db.reminderDao().getTaskReminderById("rem-1")!!.scheduleState)

    // Worker 2 drains after crash recovery and commits cleanly
    val w2Outcome = container.alarmOutboxDispatcher.drain("worker-recover-2")
    assertTrue(w2Outcome is DrainOutcome.Completed)
    assertEquals(OutboxStatus.SUCCEEDED, db.alarmSyncOutboxDao().getAllOrdered().single().status)
    assertEquals(
      ReminderScheduleState.SCHEDULED_EXACT,
      db.reminderDao().getTaskReminderById("rem-1")!!.scheduleState,
    )
    assertEquals(2, scheduler.scheduledCalls.size)
  }

  // =========================================================================
  // 4. PendingIntent Identity, Flags, Exact/Inexact & Notification Cancel
  // =========================================================================

  @Test
  fun pendingIntentFactory_usesExactIdentity_andSeparatesCreateVsNoCreateFlags() = runBlocking<Unit> {
    val container = newContainer()
    seedTaskWithReminder(container)
    val entry = db.alarmSyncOutboxDao().getAllOrdered().single()

    val factory = PendingIntentFactory(context)
    // Cancel lookup MUST use FLAG_NO_CREATE -> null before anything created
    assertNull(factory.findExistingAlarmPendingIntent(entry))

    // AndroidAlarmManagerScheduler schedules & creates PendingIntent
    val androidScheduler = AndroidAlarmManagerScheduler(context, factory)
    val triggerMs = clock.currentEpochMs() + 3_600_000L
    val outcome = androidScheduler.scheduleAlarm(entry, triggerMs)
    assertTrue(outcome is AlarmScheduleOutcome.ScheduledExact || outcome is AlarmScheduleOutcome.ScheduledInexact)

    // Now lookup MUST find the exact matching PendingIntent
    val found = factory.findExistingAlarmPendingIntent(entry)
    assertNotNull(found)

    val shadowAlarm = Shadows.shadowOf(context.getSystemService(AlarmManager::class.java))
    assertEquals(1, shadowAlarm.scheduledAlarms.size)
    assertEquals(
      PendingIntentIdentitySnapshot.ACTION_FIRE_REMINDER,
      Shadows.shadowOf(found).savedIntent.action,
    )
    assertEquals(entry.dataUri, Shadows.shadowOf(found).savedIntent.dataString)
    assertEquals(entry.component, Shadows.shadowOf(found).savedIntent.component?.className)
    assertEquals(entry.packageName, Shadows.shadowOf(found).savedIntent.component?.packageName)

    // Cancel removes alarm from AlarmManager and invalidates PendingIntent
    assertEquals(AlarmCancelOutcome.Cancelled, androidScheduler.cancelAlarm(entry))
    assertEquals(0, shadowAlarm.scheduledAlarms.size)
  }

  @Test
  fun scheduleStateInvariant_exactVsInexact_onlyWrittenAfterAlarmSchedulerSuccess() = runBlocking<Unit> {
    val container = newContainer()
    seedTaskWithReminder(container, taskId = "task-inexact", reminderId = "rem-inexact")

    // 1. Inexact permission mode -> SCHEDULED_INEXACT
    scheduler.exactAllowed = false
    val outcomeInexact = container.alarmOutboxDispatcher.drain("worker-inexact")
    assertTrue(outcomeInexact is DrainOutcome.Completed)
    assertEquals(
      ReminderScheduleState.SCHEDULED_INEXACT,
      db.reminderDao().getTaskReminderById("rem-inexact")!!.scheduleState,
    )

    // 2. Retryable failure -> stays PENDING_SYNC (never prematurely marked SCHEDULED_*)
    seedTaskWithReminder(container, taskId = "task-fail", reminderId = "rem-fail")
    scheduler.exactAllowed = true
    scheduler.nextScheduleOutcome = { _, _ -> AlarmScheduleOutcome.FailedRetryable("Alarm error") }
    container.alarmOutboxDispatcher.drain("worker-fail")
    assertEquals(
      ReminderScheduleState.PENDING_SYNC,
      db.reminderDao().getTaskReminderById("rem-fail")!!.scheduleState,
    )

    // 3. Recovery with exact allowed -> SCHEDULED_EXACT
    scheduler.nextScheduleOutcome = null
    clock.epochMs += AlarmOutboxDispatcher.BASE_BACKOFF_MS + 1L
    container.alarmOutboxDispatcher.drain("worker-exact")
    assertEquals(
      ReminderScheduleState.SCHEDULED_EXACT,
      db.reminderDao().getTaskReminderById("rem-fail")!!.scheduleState,
    )
  }

  @Test
  fun cancelNotification_executesSystemNotificationCanceller_andAllowsDeletedReminderCancel() = runBlocking<Unit> {
    val container = newContainer()
    seedTaskWithReminder(container, taskId = "task-1", reminderId = "rem-1")
    container.alarmOutboxDispatcher.drain("worker-initial")

    // 1. Snooze stages CANCEL_NOTIFICATION + SCHEDULE_ALARM
    container.reminderSyncRepository.snoozeTaskReminder(
      reminderId = "rem-1",
      triggerDate = "2026-11-27",
      triggerTime = "10:00",
      nowEpochMs = clock.currentEpochMs(),
    )
    container.alarmOutboxDispatcher.drain("worker-snooze")
    assertEquals(1, notifCanceller.calls.size)
    assertEquals(
      PendingIntentIdentitySnapshot.buildNotificationTag(ReminderOwnerType.TASK, "rem-1"),
      notifCanceller.calls.single().tag,
    )
    assertEquals(PendingIntentIdentitySnapshot.DEFAULT_NOTIFICATION_ID, notifCanceller.calls.single().id)

    // 2. Verify real SystemNotificationCanceller cancels posted Robolectric notification
    val notifManager = context.getSystemService(NotificationManager::class.java)
    val shadowNotif = Shadows.shadowOf(notifManager)
    val tag = PendingIntentIdentitySnapshot.buildNotificationTag(ReminderOwnerType.TASK, "rem-1")
    val notif: Notification = NotificationCompat.Builder(context, "yawmi_channel").setContentTitle("Test").build()
    notifManager.notify(tag, PendingIntentIdentitySnapshot.DEFAULT_NOTIFICATION_ID, notif)
    assertEquals(1, shadowNotif.size())
    assertEquals(
      NotificationCancelOutcome.Cancelled,
      SystemNotificationCanceller(context).cancelNotification(tag, PendingIntentIdentitySnapshot.DEFAULT_NOTIFICATION_ID),
    )
    assertEquals(0, shadowNotif.size())

    // 3. Delete task completely (cascades task_reminders row) -> CANCEL_ALARM & CANCEL_NOTIFICATION still commit SUCCEEDED!
    container.taskRepository.deleteById("task-1")
    assertNull(db.reminderDao().getTaskReminderById("rem-1"))

    val cancelOutcome = container.alarmOutboxDispatcher.drain("worker-delete")
    assertTrue(cancelOutcome is DrainOutcome.Completed)
    assertTrue(scheduler.cancelledCalls.isNotEmpty())
    assertEquals(2, notifCanceller.calls.size)
    assertTrue(db.alarmSyncOutboxDao().getAllOrdered().all { it.status == OutboxStatus.SUCCEEDED })
  }

  @Test
  fun expiredOutsideGraceWindow_cancelsAnyLingeringAlarm_andMarksFailedPermanent() = runBlocking<Unit> {
    val container = newContainer()
    // Current clock is 08:00Z; seed a reminder at 07:00Z (60m past -> outside 15m grace window)
    seedTaskWithReminder(
      container = container,
      taskId = "task-expired",
      reminderId = "rem-expired",
      triggerDate = "2026-11-27",
      triggerTime = "07:00",
    )

    val outcome = container.alarmOutboxDispatcher.drain("worker-expired")
    assertTrue(outcome is DrainOutcome.Completed)
    assertEquals(0, scheduler.scheduledCalls.size)
    assertEquals(1, scheduler.cancelledCalls.size)
    assertEquals(
      OutboxStatus.FAILED_PERMANENT,
      db.alarmSyncOutboxDao().getAllOrdered().single().status,
    )
    assertEquals(
      ReminderScheduleState.FAILED_PERMANENT,
      db.reminderDao().getTaskReminderById("rem-expired")!!.scheduleState,
    )
  }

  @Test
  fun retryPolicy_followsFiveAttemptLimitWithApprovedBackoffs_andStopsAtFailedPermanent() = runBlocking<Unit> {
    assertEquals(30_000L, AlarmOutboxDispatcher.BASE_BACKOFF_MS)
    assertEquals(900_000L, AlarmOutboxDispatcher.MAX_BACKOFF_MS)
    assertEquals(5, AlarmOutboxDispatcher.MAX_RETRY_ATTEMPTS)
    assertEquals(30_000L, AlarmOutboxDispatcher.computeRetryBackoffMs(1))
    assertEquals(60_000L, AlarmOutboxDispatcher.computeRetryBackoffMs(2))
    assertEquals(120_000L, AlarmOutboxDispatcher.computeRetryBackoffMs(3))
    assertEquals(240_000L, AlarmOutboxDispatcher.computeRetryBackoffMs(4))
    assertEquals(480_000L, AlarmOutboxDispatcher.computeRetryBackoffMs(5))
    assertEquals(900_000L, AlarmOutboxDispatcher.computeRetryBackoffMs(6))
    assertEquals(900_000L, AlarmOutboxDispatcher.computeRetryBackoffMs(10))

    val container = newContainer()
    seedTaskWithReminder(
      container = container,
      taskId = "task-retry",
      reminderId = "rem-retry",
      triggerDate = "2026-11-27",
      triggerTime = "12:00",
    )
    scheduler.nextScheduleOutcome = { _, _ -> AlarmScheduleOutcome.FailedRetryable("Transient failure") }

    val expectedBackoffs = listOf(30_000L, 60_000L, 120_000L, 240_000L)
    expectedBackoffs.forEachIndexed { idx, expectedDelay ->
      val outcome = container.alarmOutboxDispatcher.drain("worker-retry-${idx + 1}")
      assertTrue("Attempt ${idx + 1} should enter BackoffScheduled", outcome is DrainOutcome.BackoffScheduled)
      assertEquals(expectedDelay, (outcome as DrainOutcome.BackoffScheduled).delayMs)
      val row = db.alarmSyncOutboxDao().getAllOrdered().single()
      assertEquals(OutboxStatus.PENDING, row.status)
      assertEquals(idx + 1, row.attemptCount)
      clock.epochMs += expectedDelay
    }

    // 5th failure transitions permanently to FAILED_PERMANENT with no 6th retry scheduled
    val fifthOutcome = container.alarmOutboxDispatcher.drain("worker-retry-5")
    assertTrue(fifthOutcome is DrainOutcome.Completed)
    assertEquals(1, (fifthOutcome as DrainOutcome.Completed).failedPermanentCount)

    val finalOutbox = db.alarmSyncOutboxDao().getAllOrdered().single()
    assertEquals(OutboxStatus.FAILED_PERMANENT, finalOutbox.status)
    assertEquals(5, finalOutbox.attemptCount)
    assertEquals(
      ReminderScheduleState.FAILED_PERMANENT,
      db.reminderDao().getTaskReminderById("rem-retry")!!.scheduleState,
    )
    assertFalse(db.alarmSyncOutboxDao().hasActiveQueue())
    assertEquals(5, scheduler.scheduledCalls.size)

    // Advancing clock and draining again must NEVER create a 6th attempt
    clock.epochMs += 3_600_000L
    val postPermanentOutcome = container.alarmOutboxDispatcher.drain("worker-retry-6")
    assertTrue(postPermanentOutcome is DrainOutcome.Completed)
    assertEquals(0, (postPermanentOutcome as DrainOutcome.Completed).processedCount)
    assertEquals(5, scheduler.scheduledCalls.size)
  }

  @Test
  fun taskOnlyScope_ignoresHabitAndGoalOutboxCommands_andDrainsTaskOnly() = runBlocking<Unit> {
    val container = newContainer()
    val now = clock.currentEpochMs()

    container.habitRepository.insertHabit(
      HabitEntity(
        id = "habit-1",
        title = "Read 10 pages",
        createdAtEpochMs = now,
        updatedAtEpochMs = now,
      )
    )
    container.reminderSyncRepository.createHabitReminder(
      reminderId = "rem-habit-1",
      habitId = "habit-1",
      triggerDate = "2026-11-27",
      triggerTime = "09:30",
      offsetMinutes = 0,
      nowEpochMs = now,
    )

    container.goalRepository.insertGoal(
      GoalEntity(
        id = "goal-1",
        name = "Ship release",
        startDate = "2026-11-27",
        status = GoalStatus.ACTIVE,
        createdAtEpochMs = now,
        updatedAtEpochMs = now,
      )
    )
    container.reminderSyncRepository.createGoalReminder(
      reminderId = "rem-goal-1",
      goalId = "goal-1",
      triggerDate = "2026-11-27",
      triggerTime = "09:45",
      offsetMinutes = 0,
      nowEpochMs = now,
    )

    // With only HABIT and GOAL rows pending, TASK-scoped queue must report inactive/empty
    assertFalse(db.alarmSyncOutboxDao().hasActiveQueue())
    assertTrue(db.alarmSyncOutboxDao().getPendingOrdered().isEmpty())

    // Lease acquire + fenced claim must not touch HABIT/GOAL rows
    val acquired = container.dispatcherLeaseManager.acquireOrRenewLease("worker-scope-1") as LeaseAcquireResult.Acquired
    val habitEntry = db.alarmSyncOutboxDao().getAllOrdered().first { it.ownerType == ReminderOwnerType.HABIT }
    val goalEntry = db.alarmSyncOutboxDao().getAllOrdered().first { it.ownerType == ReminderOwnerType.GOAL }
    assertEquals(
      0,
      db.alarmSyncOutboxDao().claimPendingFenced(
        id = habitEntry.id,
        leaseOwner = acquired.token.ownerId,
        leaseGeneration = acquired.token.generation,
        leaseGenerationStr = acquired.token.generation.toString(),
        nowEpochMs = now,
      ),
    )
    assertEquals(
      0,
      db.alarmSyncOutboxDao().claimPendingFenced(
        id = goalEntry.id,
        leaseOwner = acquired.token.ownerId,
        leaseGeneration = acquired.token.generation,
        leaseGenerationStr = acquired.token.generation.toString(),
        nowEpochMs = now,
      ),
    )
    container.dispatcherLeaseManager.releaseLease(acquired.token)

    // Add a TASK reminder and drain: ONLY TASK must be executed
    seedTaskWithReminder(container, taskId = "task-only-1", reminderId = "rem-task-only-1")
    assertTrue(db.alarmSyncOutboxDao().hasActiveQueue())
    assertEquals(1, db.alarmSyncOutboxDao().getPendingOrdered().size)

    val outcome = container.alarmOutboxDispatcher.drain("worker-scope-2") as DrainOutcome.Completed
    assertEquals(1, outcome.processedCount)
    assertEquals(1, outcome.succeededCount)
    assertEquals(1, scheduler.scheduledCalls.size)
    assertEquals("rem-task-only-1", scheduler.scheduledCalls.single().entry.reminderId)
    assertEquals(ReminderOwnerType.TASK, scheduler.scheduledCalls.single().entry.ownerType)
    assertEquals(0, notifCanceller.calls.size)

    assertEquals(
      ReminderScheduleState.SCHEDULED_EXACT,
      db.reminderDao().getTaskReminderById("rem-task-only-1")!!.scheduleState,
    )
    assertEquals(
      ReminderScheduleState.PENDING_SYNC,
      db.reminderDao().getHabitReminderById("rem-habit-1")!!.scheduleState,
    )
    assertEquals(
      ReminderScheduleState.PENDING_SYNC,
      db.reminderDao().getGoalReminderById("rem-goal-1")!!.scheduleState,
    )
    assertEquals(OutboxStatus.PENDING, db.alarmSyncOutboxDao().getById(habitEntry.id)!!.status)
    assertEquals(OutboxStatus.PENDING, db.alarmSyncOutboxDao().getById(goalEntry.id)!!.status)
    assertFalse(db.alarmSyncOutboxDao().hasActiveQueue())

    // Stale CLAIMED recovery, fenced lifecycle updates, and newer-command checks must also ignore HABIT/GOAL rows
    db.alarmSyncOutboxDao().updateLifecycle(
      id = habitEntry.id,
      status = OutboxStatus.CLAIMED,
      claimedLeaseGeneration = 1L,
      claimedAtEpochMs = now,
      attemptCount = 0,
      lastAttemptAtEpochMs = null,
      failureReason = null,
    )
    db.alarmSyncOutboxDao().updateLifecycle(
      id = goalEntry.id,
      status = OutboxStatus.CLAIMED,
      claimedLeaseGeneration = 1L,
      claimedAtEpochMs = now,
      attemptCount = 0,
      lastAttemptAtEpochMs = null,
      failureReason = null,
    )
    val lease3 = container.dispatcherLeaseManager.acquireOrRenewLease("worker-scope-3") as LeaseAcquireResult.Acquired
    assertEquals(0, lease3.recoveredStaleClaimedCount)
    assertEquals(0, db.alarmSyncOutboxDao().recoverStaleClaimedBeforeGeneration(lease3.token.generation + 1L))
    assertEquals(OutboxStatus.CLAIMED, db.alarmSyncOutboxDao().getById(habitEntry.id)!!.status)
    assertEquals(OutboxStatus.CLAIMED, db.alarmSyncOutboxDao().getById(goalEntry.id)!!.status)

    assertEquals(
      0,
      db.alarmSyncOutboxDao().commitClaimedFenced(
        id = habitEntry.id,
        leaseOwner = lease3.token.ownerId,
        leaseGeneration = lease3.token.generation,
        leaseGenerationStr = lease3.token.generation.toString(),
        nowEpochMs = now,
      ),
    )
    assertEquals(
      0,
      db.alarmSyncOutboxDao().returnClaimedToPendingForRetryFenced(
        id = habitEntry.id,
        leaseOwner = lease3.token.ownerId,
        leaseGeneration = lease3.token.generation,
        leaseGenerationStr = lease3.token.generation.toString(),
        nowEpochMs = now,
        failureReason = "should-not-touch-habit",
      ),
    )
    assertEquals(
      0,
      db.alarmSyncOutboxDao().markClaimedFailedPermanentFenced(
        id = goalEntry.id,
        leaseOwner = lease3.token.ownerId,
        leaseGeneration = lease3.token.generation,
        leaseGenerationStr = lease3.token.generation.toString(),
        nowEpochMs = now,
        failureReason = "should-not-touch-goal",
      ),
    )
    assertEquals(
      0,
      db.alarmSyncOutboxDao().markClaimedSupersededFenced(
        id = goalEntry.id,
        leaseOwner = lease3.token.ownerId,
        leaseGeneration = lease3.token.generation,
        leaseGenerationStr = lease3.token.generation.toString(),
        nowEpochMs = now,
        failureReason = "should-not-touch-goal",
      ),
    )
    assertFalse(db.alarmSyncOutboxDao().hasNewerAlarmCommand(habitEntry.pendingIntentIdentityKey, 0L))
    assertFalse(
      db.alarmSyncOutboxDao().hasNewerNotificationCommand(
        notificationTag = goalEntry.notificationTag.orEmpty(),
        notificationId = goalEntry.notificationId ?: PendingIntentIdentitySnapshot.DEFAULT_NOTIFICATION_ID,
        currentSeq = 0L,
      )
    )
    container.dispatcherLeaseManager.releaseLease(lease3.token)
  }

  @Test
  fun taskOnlyScope_habitAndGoalMutationsDoNotTriggerWorkManager_whileTaskMutationsDo() = runBlocking<Unit> {
    val trigger = WorkManagerOutboxTrigger(context)
    val container = newContainer(trigger = trigger)
    val factory =
      YawmiWorkerFactory(
        dispatcherProvider = { container.alarmOutboxDispatcher },
        drainTriggerProvider = { trigger },
        hasActiveQueueProvider = { db.alarmSyncOutboxDao().hasActiveQueue() },
      )
    WorkManagerTestInitHelper.initializeTestWorkManager(
      context,
      Configuration.Builder()
        .setExecutor(SynchronousExecutor())
        .setTaskExecutor(SynchronousExecutor())
        .setWorkerFactory(factory)
        .build(),
    )
    val wm = WorkManager.getInstance(context)
    val now = clock.currentEpochMs()

    // Habit & Goal full CRUD + reminder sync + archive/status/delete must NOT enqueue WorkManager drain
    container.habitRepository.insertHabit(
      HabitEntity(id = "h-wm", title = "Walk", createdAtEpochMs = now, updatedAtEpochMs = now)
    )
    container.reminderSyncRepository.createHabitReminder(
      reminderId = "rem-h-wm",
      habitId = "h-wm",
      triggerDate = "2026-11-27",
      triggerTime = "10:00",
      offsetMinutes = 0,
      nowEpochMs = now,
    )
    container.reminderSyncRepository.updateHabitReminderSchedule(
      reminderId = "rem-h-wm",
      triggerDate = "2026-11-27",
      triggerTime = "10:15",
      offsetMinutes = 0,
      isEnabled = true,
      nowEpochMs = now + 100L,
    )
    container.habitRepository.setArchived("h-wm", isArchived = true, updatedAtEpochMs = now + 200L)
    container.reminderSyncRepository.deleteHabitReminder("rem-h-wm", nowEpochMs = now + 300L)
    container.habitRepository.deleteHabitById("h-wm")

    container.goalRepository.insertGoal(
      GoalEntity(
        id = "g-wm",
        name = "Milestone",
        startDate = "2026-11-27",
        createdAtEpochMs = now,
        updatedAtEpochMs = now,
      )
    )
    container.reminderSyncRepository.createGoalReminder(
      reminderId = "rem-g-wm",
      goalId = "g-wm",
      triggerDate = "2026-11-27",
      triggerTime = "11:00",
      offsetMinutes = 0,
      nowEpochMs = now,
    )
    container.reminderSyncRepository.updateGoalReminderSchedule(
      reminderId = "rem-g-wm",
      triggerDate = "2026-11-27",
      triggerTime = "11:30",
      offsetMinutes = 0,
      isEnabled = true,
      nowEpochMs = now + 100L,
    )
    container.goalRepository.updateStatus("g-wm", GoalStatus.COMPLETED, updatedAtEpochMs = now + 200L)
    container.reminderSyncRepository.deleteGoalReminder("rem-g-wm", nowEpochMs = now + 300L)
    container.goalRepository.deleteGoalById("g-wm")

    assertTrue(wm.getWorkInfosForUniqueWorkFlow(WorkManagerOutboxTrigger.DRAIN_WORK_NAME).first().isEmpty())
    assertEquals(0, scheduler.scheduledCalls.size)
    assertEquals(0, scheduler.cancelledCalls.size)
    assertEquals(0, notifCanceller.calls.size)
    assertTrue(
      db.alarmSyncOutboxDao().getAllOrdered().all {
        it.ownerType != ReminderOwnerType.TASK &&
          (it.status == OutboxStatus.PENDING || it.status == OutboxStatus.SUPERSEDED)
      }
    )

    // Task reminder mutation MUST trigger WorkManager and drain only the TASK command
    seedTaskWithReminder(container, taskId = "t-wm", reminderId = "rem-t-wm")
    awaitWorkFinished(wm, WorkManagerOutboxTrigger.DRAIN_WORK_NAME, minCount = 1)

    assertEquals(1, scheduler.scheduledCalls.size)
    assertEquals("rem-t-wm", scheduler.scheduledCalls.single().entry.reminderId)
    assertEquals(
      ReminderScheduleState.SCHEDULED_EXACT,
      db.reminderDao().getTaskReminderById("rem-t-wm")!!.scheduleState,
    )
  }
}
