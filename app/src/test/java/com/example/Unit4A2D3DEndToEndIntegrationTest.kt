package com.example

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.example.core.di.DefaultAppContainer
import com.example.core.i18n.ProvideYawmiLocale
import com.example.core.preferences.AppLanguage
import com.example.core.preferences.AppThemeMode
import com.example.core.time.GreetingPeriod
import com.example.core.time.LocalClock
import com.example.data.local.db.PendingIntentIdentitySnapshot
import com.example.data.local.db.YawmiDatabase
import com.example.data.local.entity.AlarmSyncOutboxEntity
import com.example.data.local.entity.DeliveryState
import com.example.data.local.entity.OutboxAction
import com.example.data.local.entity.OutboxStatus
import com.example.data.local.entity.RecurrenceFrequency
import com.example.data.local.entity.ReminderOwnerType
import com.example.data.local.entity.ReminderScheduleState
import com.example.data.local.entity.TaskEntity
import com.example.data.local.entity.TaskRecurrenceEntity
import com.example.data.local.entity.TaskReminderDeliveryEntity
import com.example.data.local.entity.TaskReminderEntity
import com.example.data.local.entity.TaskStatus
import com.example.domain.repository.ReminderSyncRepository
import com.example.domain.repository.TaskRepository
import com.example.domain.task.TaskDomainResult
import com.example.domain.task.TaskInput
import com.example.domain.task.TaskRecurrenceInput
import com.example.domain.task.TaskReminderResult
import com.example.domain.task.TaskReminderUseCases
import com.example.domain.task.TaskUseCases
import com.example.feature.tasks.components.TaskEditorSheet
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
import com.example.reminder.OutboxDrainTrigger
import com.example.reminder.PendingIntentFactory
import com.example.reminder.SystemReconcileReceiver
import com.example.reminder.SystemReconciliationTrigger
import com.example.ui.theme.YawmiTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

private class Def07Clock(
  initialEpochMs: Long = Instant.parse("2026-11-28T07:00:00Z").toEpochMilli(),
  private val zone: ZoneId = ZoneId.of("UTC"),
) : LocalClock {
  var epochMs: Long = initialEpochMs

  override fun currentEpochMs(): Long = epochMs

  override fun currentDate(): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

  override fun currentTime(): LocalTime = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime()

  override fun greetingPeriod(): GreetingPeriod = GreetingPeriod.MORNING

  override fun zoneId(): ZoneId = zone
}

private class Def07FakeAlarmScheduler(
  private val pendingIntentFactory: PendingIntentFactory,
  var exactAllowed: Boolean = true,
) : AlarmScheduler {
  data class ActiveAlarm(
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

  val activeAlarms = linkedMapOf<String, ActiveAlarm>()
  val scheduleCalls = mutableListOf<ScheduleCall>()
  val cancelCalls = mutableListOf<CancelCall>()

  override fun canScheduleExactAlarms(): Boolean = exactAllowed

  override fun scheduleAlarm(
    entry: AlarmSyncOutboxEntity,
    triggerAtEpochMs: Long,
  ): AlarmScheduleOutcome {
    activeAlarms[entry.pendingIntentIdentityKey] =
      ActiveAlarm(
        entry = entry,
        triggerAtEpochMs = triggerAtEpochMs,
        intent = pendingIntentFactory.buildIntent(entry),
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

private class Def07SpyNotificationPoster(
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
class Unit4A2D3DEndToEndIntegrationTest {

  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var context: Context
  private lateinit var app: Application
  private lateinit var notificationManager: NotificationManager
  private lateinit var db: YawmiDatabase
  private lateinit var clock: Def07Clock
  private lateinit var fakeAlarmScheduler: Def07FakeAlarmScheduler
  private lateinit var spyPoster: Def07SpyNotificationPoster
  private lateinit var container: DefaultAppContainer

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    app = context as Application
    notificationManager = context.getSystemService(NotificationManager::class.java)
    db = YawmiDatabase.inMemory(context)
    clock = Def07Clock()
    fakeAlarmScheduler = Def07FakeAlarmScheduler(PendingIntentFactory(context))
    spyPoster = Def07SpyNotificationPoster(AndroidNotificationPoster(context))

    Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    Shadows.shadowOf(notificationManager).setNotificationsEnabled(true)
    NotificationChannelManager(context).ensureChannelsCreated(AppLanguage.ENGLISH)

    container =
      DefaultAppContainer(
        context = context,
        databaseProvider = { db },
        localClockProvider = { clock },
        alarmSchedulerProvider = { _, _ -> fakeAlarmScheduler },
        outboxDrainTriggerProvider = { OutboxDrainTrigger.NoOp },
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

  private fun waitUntilWithIdle(timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      composeTestRule.waitForIdle()
      if (predicate()) return
      Thread.sleep(20)
    }
    composeTestRule.waitForIdle()
    if (!predicate()) {
      throw AssertionError("Condition still not satisfied after $timeoutMs ms")
    }
  }

  @Test
  fun def07_e2e_createScheduledTaskWithRelativeReminderViaEditor_drainsOutboxAndSchedulesAlarm() {
    var savedTask: TaskEntity? = null

    composeTestRule.setContent {
      ProvideYawmiLocale(language = AppLanguage.ENGLISH) {
        YawmiTheme(themeMode = AppThemeMode.DARK) {
          TaskEditorSheet(
            existingTask = null,
            categories = emptyList(),
            taskUseCases = container.taskUseCases,
            taskReminderUseCases = container.taskReminderUseCases,
            localClock = clock,
            onManageCategoriesClick = {},
            onSaved = { savedTask = it },
            onDismiss = {},
          )
        }
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_title_input").performScrollTo().performTextInput("Sprint planning")
    composeTestRule.onNodeWithTag("task_date_today").performScrollTo().performClick()
    composeTestRule.pickTimeInDialog(buttonTag = "task_start_time_btn", hour = "10", minute = "00")
    composeTestRule.pickTimeInDialog(buttonTag = "task_end_time_btn", hour = "11", minute = "00")

    composeTestRule.onNodeWithTag("task_reminders_toggle").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_reminder_preset_10").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("task_save_btn").performScrollTo().performClick()

    waitUntilWithIdle { savedTask != null }
    val createdTask = savedTask!!

    runBlocking {
      val persistedTask = container.taskRepository.getById(createdTask.id)!!
      assertEquals("Sprint planning", persistedTask.title)
      assertEquals("2026-11-28", persistedTask.scheduledDate)
      assertEquals("10:00", persistedTask.startTime)
      assertEquals(TaskStatus.PENDING, persistedTask.status)

      val reminders = container.taskReminderUseCases.getTaskReminders(createdTask.id)
      assertEquals(1, reminders.size)
      val rel = reminders.single()
      assertEquals(10, rel.offsetMinutes)
      assertEquals("2026-11-28", rel.triggerDate)
      assertEquals("09:50", rel.triggerTime)
      assertEquals(1L, rel.scheduleRevision)
      assertTrue(rel.isEnabled)
      assertEquals(ReminderScheduleState.PENDING_SYNC, rel.scheduleState)

      val preDrainOutbox = db.alarmSyncOutboxDao().getAllOrdered()
      assertEquals(1, preDrainOutbox.size)
      assertEquals(OutboxAction.SCHEDULE_ALARM, preDrainOutbox.single().action)
      assertEquals(OutboxStatus.PENDING, preDrainOutbox.single().status)
      assertEquals(1L, preDrainOutbox.single().targetScheduleRevision)

      val drain = container.alarmOutboxDispatcher.drain("def07-create-rel")
      assertTrue(drain is DrainOutcome.Completed)
      assertEquals(1, (drain as DrainOutcome.Completed).succeededCount)

      assertEquals(1, fakeAlarmScheduler.activeAlarms.size)
      val activeAlarm = fakeAlarmScheduler.activeAlarms.values.single()
      assertEquals(rel.id, activeAlarm.entry.reminderId)
      assertEquals(1L, activeAlarm.entry.targetScheduleRevision)
      assertEquals(
        Instant.parse("2026-11-28T09:50:00Z").toEpochMilli(),
        activeAlarm.triggerAtEpochMs,
      )

      val postDrainReminder = db.reminderDao().getTaskReminderById(rel.id)!!
      assertEquals(ReminderScheduleState.SCHEDULED_EXACT, postDrainReminder.scheduleState)
      val postDrainOutbox = db.alarmSyncOutboxDao().getAllOrdered()
      assertEquals(OutboxStatus.SUCCEEDED, postDrainOutbox.single().status)
      assertFalse(db.alarmSyncOutboxDao().hasActiveQueue())
    }
  }

  @Test
  fun def07_e2e_createScheduledTaskWithExplicitReminderDifferentFromStartTime_drainsAndFires() {
    var savedTask: TaskEntity? = null

    composeTestRule.setContent {
      ProvideYawmiLocale(language = AppLanguage.ENGLISH) {
        YawmiTheme(themeMode = AppThemeMode.LIGHT) {
          TaskEditorSheet(
            existingTask = null,
            categories = emptyList(),
            taskUseCases = container.taskUseCases,
            taskReminderUseCases = container.taskReminderUseCases,
            localClock = clock,
            onManageCategoriesClick = {},
            onSaved = { savedTask = it },
            onDismiss = {},
          )
        }
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_title_input").performScrollTo().performTextInput("Explicit alert task")
    composeTestRule.onNodeWithTag("task_date_today").performScrollTo().performClick()
    composeTestRule.pickTimeInDialog(buttonTag = "task_start_time_btn", hour = "10", minute = "00")
    composeTestRule.pickTimeInDialog(buttonTag = "task_end_time_btn", hour = "11", minute = "00")

    composeTestRule.onNodeWithTag("task_reminders_toggle").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.pickTimeInDialog(
      buttonTag = "task_reminder_add_custom_btn",
      hour = "08",
      minute = "15",
    )
    composeTestRule.onNodeWithTag("task_save_btn").performScrollTo().performClick()

    waitUntilWithIdle { savedTask != null }
    val createdTask = savedTask!!

    runBlocking {
      val rem = container.taskReminderUseCases.getTaskReminders(createdTask.id).single()
      assertEquals(0, rem.offsetMinutes)
      assertEquals("2026-11-28", rem.triggerDate)
      assertEquals("08:15", rem.triggerTime)
      assertEquals(1L, rem.scheduleRevision)

      val drain = container.alarmOutboxDispatcher.drain("def07-create-explicit")
      assertTrue(drain is DrainOutcome.Completed)
      assertEquals(1, fakeAlarmScheduler.activeAlarms.size)
      val scheduled = fakeAlarmScheduler.activeAlarms.values.single()
      assertEquals(
        Instant.parse("2026-11-28T08:15:00Z").toEpochMilli(),
        scheduled.triggerAtEpochMs,
      )
      assertEquals(
        ReminderScheduleState.SCHEDULED_EXACT,
        db.reminderDao().getTaskReminderById(rem.id)!!.scheduleState,
      )
      assertFalse(db.alarmSyncOutboxDao().hasActiveQueue())

      // Fire at 08:15 -> real notification delivered
      clock.epochMs = Instant.parse("2026-11-28T08:15:00Z").toEpochMilli()
      val fireOutcome = container.alarmFireHandler.handleIntent(scheduled.intent)
      assertTrue(
        fireOutcome is AlarmFireOutcome.Delivered &&
          fireOutcome.outcome is DeliveryOutcome.PostedToSystem
      )
      assertEquals(1, spyPoster.postedCalls.size)
      assertTrue(db.reminderDeliveryDao().hasPostedTaskDeliveryForRevision(rem.id, 1L))
    }
  }

  @Test
  fun def07_e2e_editDateAndStartTimeViaEditor_bumpsRevisionUpdatesSchedulerRejectsStaleIntentAndPreservesBatch1Policies() {
    val (initialTask, relativeRem, explicitRem, pausedRem) =
      runBlocking {
        val t =
          (container.taskUseCases.createTask(
            TaskInput(
              title = "Architecture review",
              scheduledDate = "2026-11-28",
              startTime = "10:00",
              endTime = "11:00",
            )
          ) as TaskDomainResult.Success)
            .value
        val rel =
          (container.taskReminderUseCases.createRelativeReminder(t.id, 10)
            as TaskReminderResult.Success)
            .value
        val exp =
          (container.taskReminderUseCases.createExplicitReminder(t.id, "08:15")
            as TaskReminderResult.Success)
            .value
        val pausedCreated =
          (container.taskReminderUseCases.createRelativeReminder(t.id, 30)
            as TaskReminderResult.Success)
            .value
        val paused =
          (container.taskReminderUseCases.setReminderEnabled(t.id, pausedCreated.id, false)
            as TaskReminderResult.Success)
            .value
        assertEquals(ReminderScheduleState.PAUSED, paused.scheduleState)
        assertTrue(container.alarmOutboxDispatcher.drain("def07-edit-init") is DrainOutcome.Completed)
        listOf(t, rel, exp, paused)
      }
        .let {
          @Suppress("UNCHECKED_CAST")
          Tuple4(
            it[0] as TaskEntity,
            it[1] as TaskReminderEntity,
            it[2] as TaskReminderEntity,
            it[3] as TaskReminderEntity,
          )
        }

    // Capture old rev=1 intents before editing schedule
    val staleRelIntentRev1 =
      Intent(
        fakeAlarmScheduler.activeAlarms.values
          .first { it.entry.reminderId == relativeRem.id }
          .intent
      )
    val staleExpIntentRev1 =
      Intent(
        fakeAlarmScheduler.activeAlarms.values
          .first { it.entry.reminderId == explicitRem.id }
          .intent
      )
    assertEquals(
      1L,
      staleRelIntentRev1.getLongExtra(PendingIntentFactory.EXTRA_SCHEDULE_REVISION, -1L),
    )
    assertEquals(
      1L,
      staleExpIntentRev1.getLongExtra(PendingIntentFactory.EXTRA_SCHEDULE_REVISION, -1L),
    )

    var updatedSavedTask: TaskEntity? = null
    composeTestRule.setContent {
      ProvideYawmiLocale(language = AppLanguage.ENGLISH) {
        YawmiTheme(themeMode = AppThemeMode.DARK) {
          TaskEditorSheet(
            existingTask = initialTask,
            categories = emptyList(),
            taskUseCases = container.taskUseCases,
            taskReminderUseCases = container.taskReminderUseCases,
            localClock = clock,
            onManageCategoriesClick = {},
            onSaved = { updatedSavedTask = it },
            onDismiss = {},
          )
        }
      }
    }

    waitUntilWithIdle {
      composeTestRule
        .onAllNodesWithTag("task_reminders_summary", useUnmergedTree = true)
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    val pausedIndex =
      runBlocking {
        container.taskReminderUseCases.getTaskReminders(initialTask.id).indexOfFirst {
          it.id == pausedRem.id
        }
      }
    assertTrue(pausedIndex >= 0)

    composeTestRule.onNodeWithTag("task_reminders_toggle").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    waitUntilWithIdle {
      composeTestRule
        .onAllNodesWithTag("task_reminder_status_badge_$pausedIndex")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    composeTestRule
      .onNodeWithTag("task_reminder_status_badge_$pausedIndex")
      .performScrollTo()
      .assertTextContains("Paused")

    // Edit scheduledDate (Tomorrow = 2026-11-29) AND startTime (14:00 -> 15:00) WITHOUT re-enabling pausedRem
    composeTestRule.onNodeWithTag("task_date_tomorrow").performScrollTo().performClick()
    composeTestRule.pickTimeInDialog(buttonTag = "task_start_time_btn", hour = "14", minute = "00")
    composeTestRule.pickTimeInDialog(buttonTag = "task_end_time_btn", hour = "15", minute = "00")
    composeTestRule.onNodeWithTag("task_save_btn").performScrollTo().performClick()

    waitUntilWithIdle { updatedSavedTask != null }

    runBlocking {
      val relAfterEdit = db.reminderDao().getTaskReminderById(relativeRem.id)!!
      assertTrue(relAfterEdit.isEnabled)
      assertEquals(2L, relAfterEdit.scheduleRevision)
      assertEquals("2026-11-29", relAfterEdit.triggerDate)
      assertEquals("13:50", relAfterEdit.triggerTime)
      assertEquals(ReminderScheduleState.PENDING_SYNC, relAfterEdit.scheduleState)

      // DEF-02 policy: explicit 08:15 preserves explicit triggerTime across startTime edit
      val expAfterEdit = db.reminderDao().getTaskReminderById(explicitRem.id)!!
      assertTrue(expAfterEdit.isEnabled)
      assertEquals(2L, expAfterEdit.scheduleRevision)
      assertEquals("2026-11-29", expAfterEdit.triggerDate)
      assertEquals("08:15", expAfterEdit.triggerTime)
      assertEquals(ReminderScheduleState.PENDING_SYNC, expAfterEdit.scheduleState)

      // DEF-03 policy: PAUSED reminder stays PAUSED, isEnabled == false, no auto re-enable
      val pausedAfterEdit = db.reminderDao().getTaskReminderById(pausedRem.id)!!
      assertFalse(pausedAfterEdit.isEnabled)
      assertEquals(ReminderScheduleState.PAUSED, pausedAfterEdit.scheduleState)
      assertEquals(2L, pausedAfterEdit.scheduleRevision)

      val outboxBeforeDrain = db.alarmSyncOutboxDao().getAllOrdered()
      assertTrue(
        outboxBeforeDrain.none {
          it.reminderId == pausedRem.id &&
            it.action == OutboxAction.SCHEDULE_ALARM &&
            it.status == OutboxStatus.PENDING
        }
      )
      assertTrue(
        outboxBeforeDrain.any {
          it.reminderId == relativeRem.id &&
            it.action == OutboxAction.SCHEDULE_ALARM &&
            it.targetScheduleRevision == 2L &&
            it.status == OutboxStatus.PENDING
        }
      )
      assertTrue(
        outboxBeforeDrain.any {
          it.reminderId == explicitRem.id &&
            it.action == OutboxAction.SCHEDULE_ALARM &&
            it.targetScheduleRevision == 2L &&
            it.status == OutboxStatus.PENDING
        }
      )

      val editDrain = container.alarmOutboxDispatcher.drain("def07-edit-drain")
      assertTrue(editDrain is DrainOutcome.Completed)
      assertEquals(2, (editDrain as DrainOutcome.Completed).succeededCount)
      assertFalse(db.alarmSyncOutboxDao().hasActiveQueue())

      assertEquals(2, fakeAlarmScheduler.activeAlarms.size)
      val activeRel = fakeAlarmScheduler.activeAlarms.values.first { it.entry.reminderId == relativeRem.id }
      val activeExp = fakeAlarmScheduler.activeAlarms.values.first { it.entry.reminderId == explicitRem.id }
      assertEquals(2L, activeRel.entry.targetScheduleRevision)
      assertEquals(Instant.parse("2026-11-29T13:50:00Z").toEpochMilli(), activeRel.triggerAtEpochMs)
      assertEquals(2L, activeExp.entry.targetScheduleRevision)
      assertEquals(Instant.parse("2026-11-29T08:15:00Z").toEpochMilli(), activeExp.triggerAtEpochMs)
      assertTrue(fakeAlarmScheduler.activeAlarms.values.none { it.entry.reminderId == pausedRem.id })

      // Pass stale rev=1 intents at old times -> must be rejected with 0 notifications
      clock.epochMs = Instant.parse("2026-11-28T08:15:00Z").toEpochMilli()
      val staleExpOutcome = container.alarmFireHandler.handleIntent(staleExpIntentRev1)
      assertTrue(
        staleExpOutcome is AlarmFireOutcome.Delivered &&
          staleExpOutcome.outcome is DeliveryOutcome.Skipped
      )

      clock.epochMs = Instant.parse("2026-11-28T09:50:00Z").toEpochMilli()
      val staleRelOutcome = container.alarmFireHandler.handleIntent(staleRelIntentRev1)
      assertTrue(
        staleRelOutcome is AlarmFireOutcome.Delivered &&
          staleRelOutcome.outcome is DeliveryOutcome.Skipped
      )

      assertTrue(spyPoster.postedCalls.isEmpty())
      assertTrue(Shadows.shadowOf(notificationManager).allNotifications.isEmpty())
      assertFalse(db.reminderDeliveryDao().hasPostedTaskDeliveryForRevision(relativeRem.id, 1L))
      assertFalse(db.reminderDeliveryDao().hasPostedTaskDeliveryForRevision(explicitRem.id, 1L))
    }
  }

  @Test
  fun def07_e2e_cancelTaskThenDrain_invalidatesRemindersDeliveriesAndCancelsSchedulerAlarms() {
    var createdTask: TaskEntity? = null
    composeTestRule.setContent {
      ProvideYawmiLocale(language = AppLanguage.ENGLISH) {
        YawmiTheme(themeMode = AppThemeMode.LIGHT) {
          TaskEditorSheet(
            existingTask = null,
            categories = emptyList(),
            taskUseCases = container.taskUseCases,
            taskReminderUseCases = container.taskReminderUseCases,
            localClock = clock,
            onManageCategoriesClick = {},
            onSaved = { createdTask = it },
            onDismiss = {},
          )
        }
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_title_input").performScrollTo().performTextInput("Cancel flow task")
    composeTestRule.onNodeWithTag("task_date_today").performScrollTo().performClick()
    composeTestRule.pickTimeInDialog(buttonTag = "task_start_time_btn", hour = "11", minute = "00")
    composeTestRule.onNodeWithTag("task_reminders_toggle").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_reminder_preset_10").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("task_save_btn").performScrollTo().performClick()
    waitUntilWithIdle { createdTask != null }

    val task = createdTask!!
    runBlocking {
      assertTrue(container.alarmOutboxDispatcher.drain("def07-cancel-init") is DrainOutcome.Completed)
      val rem = container.taskReminderUseCases.getTaskReminders(task.id).single()
      val rev1Intent = Intent(fakeAlarmScheduler.activeAlarms.values.single().intent)

      // Seed a PENDING delivery row to prove cancel marks it STALE
      val now = clock.currentEpochMs()
      db.reminderDeliveryDao().insertTaskDelivery(
        TaskReminderDeliveryEntity(
          id = UUID.randomUUID().toString(),
          taskReminderId = rem.id,
          occurrenceKey = "2026-11-28T10:50@UTC#rev1",
          scheduledAtEpochMs = Instant.parse("2026-11-28T10:50:00Z").toEpochMilli(),
          firedScheduleRevision = 1L,
          state = DeliveryState.PENDING,
          notificationTag =
            PendingIntentIdentitySnapshot.buildNotificationTag(ReminderOwnerType.TASK, rem.id),
          notificationId = PendingIntentIdentitySnapshot.DEFAULT_NOTIFICATION_ID,
          createdAtEpochMs = now,
          updatedAtEpochMs = now,
        )
      )

      assertTrue(container.taskUseCases.cancelTask(task.id) is TaskDomainResult.Success)
      assertEquals(
        DeliveryState.STALE,
        db.reminderDeliveryDao().getTaskDelivery(rem.id, "2026-11-28T10:50@UTC#rev1")?.state,
      )

      val preDrainOutbox = db.alarmSyncOutboxDao().getAllOrdered()
      assertTrue(
        preDrainOutbox.any {
          it.reminderId == rem.id &&
            it.action == OutboxAction.CANCEL_ALARM &&
            it.status == OutboxStatus.PENDING
        }
      )
      assertTrue(
        preDrainOutbox.any {
          it.reminderId == rem.id &&
            it.action == OutboxAction.CANCEL_NOTIFICATION &&
            it.status == OutboxStatus.PENDING
        }
      )

      val cancelDrain = container.alarmOutboxDispatcher.drain("def07-cancel-drain")
      assertTrue(cancelDrain is DrainOutcome.Completed)
      assertTrue(fakeAlarmScheduler.activeAlarms.isEmpty())
      assertTrue(fakeAlarmScheduler.cancelCalls.any { it.reminderId == rem.id })

      val cancelledRem = db.reminderDao().getTaskReminderById(rem.id)!!
      assertFalse(cancelledRem.isEnabled)
      assertEquals(ReminderScheduleState.CANCELLED, cancelledRem.scheduleState)
      assertFalse(db.alarmSyncOutboxDao().hasActiveQueue())

      clock.epochMs = Instant.parse("2026-11-28T10:50:00Z").toEpochMilli()
      val fireResult = container.alarmFireHandler.handleIntent(rev1Intent)
      assertTrue(
        fireResult is AlarmFireOutcome.Delivered && fireResult.outcome is DeliveryOutcome.Skipped
      )
      assertTrue(spyPoster.postedCalls.isEmpty())
    }
  }

  @Test
  fun def07_e2e_deleteTaskThenDrain_cancelsAllAlarmsAndLeavesZeroOrphanScheduleCommands() {
    var createdTask: TaskEntity? = null
    composeTestRule.setContent {
      ProvideYawmiLocale(language = AppLanguage.ENGLISH) {
        YawmiTheme(themeMode = AppThemeMode.DARK) {
          TaskEditorSheet(
            existingTask = null,
            categories = emptyList(),
            taskUseCases = container.taskUseCases,
            taskReminderUseCases = container.taskReminderUseCases,
            localClock = clock,
            onManageCategoriesClick = {},
            onSaved = { createdTask = it },
            onDismiss = {},
          )
        }
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_title_input").performScrollTo().performTextInput("Delete flow task")
    composeTestRule.onNodeWithTag("task_date_today").performScrollTo().performClick()
    composeTestRule.pickTimeInDialog(buttonTag = "task_start_time_btn", hour = "13", minute = "00")
    composeTestRule.onNodeWithTag("task_reminders_toggle").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_reminder_preset_10").performScrollTo().performClick()
    composeTestRule.pickTimeInDialog(
      buttonTag = "task_reminder_add_custom_btn",
      hour = "09",
      minute = "00",
    )
    composeTestRule.onNodeWithTag("task_save_btn").performScrollTo().performClick()
    waitUntilWithIdle { createdTask != null }

    val task = createdTask!!
    runBlocking {
      assertTrue(container.alarmOutboxDispatcher.drain("def07-delete-init") is DrainOutcome.Completed)
      assertEquals(2, fakeAlarmScheduler.activeAlarms.size)
      val reminders = container.taskReminderUseCases.getTaskReminders(task.id)
      val capturedIntents = fakeAlarmScheduler.activeAlarms.values.map { Intent(it.intent) }

      val deleted = container.taskUseCases.deleteTask(task.id)
      assertTrue(deleted)
      assertTrue(container.taskReminderUseCases.getTaskReminders(task.id).isEmpty())

      val preDrainOutbox = db.alarmSyncOutboxDao().getAllOrdered()
      for (rem in reminders) {
        assertTrue(
          preDrainOutbox.any {
            it.reminderId == rem.id &&
              it.action == OutboxAction.CANCEL_ALARM &&
              it.status == OutboxStatus.PENDING
          }
        )
        assertTrue(
          preDrainOutbox.any {
            it.reminderId == rem.id &&
              it.action == OutboxAction.CANCEL_NOTIFICATION &&
              it.status == OutboxStatus.PENDING
          }
        )
      }

      val deleteDrain = container.alarmOutboxDispatcher.drain("def07-delete-drain")
      assertTrue(deleteDrain is DrainOutcome.Completed)
      assertTrue(fakeAlarmScheduler.activeAlarms.isEmpty())
      for (rem in reminders) {
        assertTrue(fakeAlarmScheduler.cancelCalls.any { it.reminderId == rem.id })
      }
      assertFalse(db.alarmSyncOutboxDao().hasActiveQueue())

      // Zero valid pending/active SCHEDULE_ALARM for deleted task
      val postDrainOutbox = db.alarmSyncOutboxDao().getAllOrdered()
      assertTrue(
        postDrainOutbox.none {
          it.action == OutboxAction.SCHEDULE_ALARM &&
            (it.status == OutboxStatus.PENDING || it.status == OutboxStatus.CLAIMED)
        }
      )

      // Firing captured old intents fails cleanly with 0 notifications
      clock.epochMs = Instant.parse("2026-11-28T12:50:00Z").toEpochMilli()
      for (staleIntent in capturedIntents) {
        val outcome = container.alarmFireHandler.handleIntent(staleIntent)
        assertTrue(
          outcome is AlarmFireOutcome.Delivered && outcome.outcome is DeliveryOutcome.Skipped
        )
      }
      assertTrue(spyPoster.postedCalls.isEmpty())
    }
  }

  @Test
  fun def07_race_saveBeforeLoadExistingExtrasCompletes_preservesRemindersRecurrenceAndPausedStateWithoutBogusOutbox() {
    val (existingTask, pausedRem, explicitRem) =
      runBlocking {
        val t =
          (container.taskUseCases.createTask(
            TaskInput(
              title = "Existing task with extras",
              scheduledDate = "2026-11-28",
              startTime = "10:00",
              endTime = "11:00",
            )
          ) as TaskDomainResult.Success)
            .value
        container.taskUseCases.saveTaskRecurrence(
          taskId = t.id,
          input = TaskRecurrenceInput(frequency = RecurrenceFrequency.DAILY, interval = 1),
        )
        val pCreated =
          (container.taskReminderUseCases.createRelativeReminder(t.id, 10)
            as TaskReminderResult.Success)
            .value
        val p =
          (container.taskReminderUseCases.setReminderEnabled(t.id, pCreated.id, false)
            as TaskReminderResult.Success)
            .value
        val exp =
          (container.taskReminderUseCases.createExplicitReminder(t.id, "08:15")
            as TaskReminderResult.Success)
            .value
        assertTrue(container.alarmOutboxDispatcher.drain("def07-race-init") is DrainOutcome.Completed)
        Triple(t, p, exp)
      }

    // Deterministic barrier: loadExistingExtras() suspends in getTaskRecurrence() before loading reminders,
    // while handleSave()'s getTaskReminders() reload is NOT gated so premature save truly executes if unblocked.
    val extrasLoadStarted = CompletableDeferred<Unit>()
    val extrasReleaseGate = CompletableDeferred<Unit>()
    val gatedTaskRepo =
      object : TaskRepository by container.taskRepository {
        override suspend fun getTaskRecurrence(taskId: String): TaskRecurrenceEntity? {
          extrasLoadStarted.complete(Unit)
          extrasReleaseGate.await()
          return container.taskRepository.getTaskRecurrence(taskId)
        }
      }
    val gatedTaskUseCases =
      TaskUseCases(
        taskRepository = gatedTaskRepo,
        categoryRepository = container.categoryRepository,
        localClock = clock,
      )

    val outboxCountBeforeOpen = runBlocking { db.alarmSyncOutboxDao().getAllOrdered().size }
    var savedCallbackCount = 0

    composeTestRule.setContent {
      ProvideYawmiLocale(language = AppLanguage.ENGLISH) {
        YawmiTheme(themeMode = AppThemeMode.DARK) {
          TaskEditorSheet(
            existingTask = existingTask,
            categories = emptyList(),
            taskUseCases = gatedTaskUseCases,
            taskReminderUseCases = container.taskReminderUseCases,
            localClock = clock,
            onManageCategoriesClick = {},
            onSaved = { savedCallbackCount += 1 },
            onDismiss = {},
          )
        }
      }
    }

    runBlocking { extrasLoadStarted.await() }
    composeTestRule.waitForIdle()

    // While loadExistingExtras is STILL suspended:
    // Save must NOT execute or invoke onSaved before existing recurrence & reminders are loaded.
    composeTestRule.onNodeWithTag("task_title_input").performScrollTo().performTextReplacement("Edited quickly")
    composeTestRule.onNodeWithTag("task_save_btn").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    Thread.sleep(150)
    composeTestRule.waitForIdle()
    assertEquals(
      "onSaved must not fire before loadExistingExtras() finishes",
      0,
      savedCallbackCount,
    )

    // Release loadExistingExtras gate -> extras finish loading, user can now save safely
    extrasReleaseGate.complete(Unit)
    waitUntilWithIdle {
      composeTestRule.onAllNodes(hasTestTag("task_save_btn") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("task_save_btn").performScrollTo().performClick()
    waitUntilWithIdle { savedCallbackCount == 1 }

    runBlocking {
      val updated = container.taskRepository.getById(existingTask.id)!!
      assertEquals("Edited quickly", updated.title)
      val rec = container.taskRepository.getTaskRecurrence(existingTask.id)
      assertNotNull(rec)
      assertEquals(RecurrenceFrequency.DAILY, rec!!.frequency)

      val rems = container.taskReminderUseCases.getTaskReminders(existingTask.id)
      assertEquals(2, rems.size)
      val pausedAfter = rems.first { it.id == pausedRem.id }
      assertFalse(pausedAfter.isEnabled)
      assertEquals(ReminderScheduleState.PAUSED, pausedAfter.scheduleState)
      val explicitAfter = rems.first { it.id == explicitRem.id }
      assertTrue(explicitAfter.isEnabled)

      // No spurious outbox commands created by title-only save
      val postOutbox = db.alarmSyncOutboxDao().getAllOrdered()
      assertEquals(outboxCountBeforeOpen, postOutbox.size)
    }
  }
}

private data class Tuple4<A, B, C, D>(
  val first: A,
  val second: B,
  val third: C,
  val fourth: D,
)

private fun ComposeContentTestRule.setTimeInputValues(hour: String, minute: String) {
  val fields =
    onAllNodes(
      hasSetTextAction() and hasAnyAncestor(hasTestTag("yawmi_time_picker_input")),
      useUnmergedTree = true,
    )
  fields[0].performTextReplacement(hour)
  fields[1].performTextReplacement(minute)
  waitForIdle()
}

private fun ComposeContentTestRule.pickTimeInDialog(
  buttonTag: String,
  hour: String,
  minute: String,
) {
  onNodeWithTag(buttonTag).performScrollTo().performClick()
  waitForIdle()
  onNodeWithTag("yawmi_time_picker_mode_toggle").performClick()
  waitForIdle()
  setTimeInputValues(hour, minute)
  onNodeWithTag("yawmi_time_picker_confirm_btn").performClick()
  waitForIdle()
}
