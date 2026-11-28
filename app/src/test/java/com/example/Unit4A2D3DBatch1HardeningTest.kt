package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.example.core.di.DefaultAppContainer
import com.example.core.i18n.ProvideYawmiLocale
import com.example.core.preferences.AppLanguage
import com.example.core.preferences.AppThemeMode
import com.example.core.time.GreetingPeriod
import com.example.core.time.LocalClock
import com.example.data.local.db.YawmiDatabase
import com.example.data.local.entity.DeliveryState
import com.example.data.local.entity.OutboxAction
import com.example.data.local.entity.RecurrenceFrequency
import com.example.data.local.entity.ReminderScheduleState
import com.example.data.local.entity.TaskEntity
import com.example.data.local.entity.TaskReminderDeliveryEntity
import com.example.data.local.entity.TaskStatus
import com.example.domain.task.TaskDomainResult
import com.example.domain.task.TaskInput
import com.example.domain.task.TaskRecurrenceInput
import com.example.domain.task.TaskReminderError
import com.example.domain.task.TaskReminderResult
import com.example.domain.task.TaskRules
import com.example.domain.task.TaskValidationError
import com.example.feature.tasks.components.TaskEditorSheet
import com.example.feature.tasks.components.toLocalizedMessage
import com.example.feature.tasks.detail.TaskDetailScreen
import com.example.ui.theme.YawmiTheme
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
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
import org.robolectric.annotation.Config

private class Batch1Clock(
  private val date: LocalDate = LocalDate.of(2026, 11, 28),
  private val time: LocalTime = LocalTime.of(9, 0),
  private var epochMs: Long =
    LocalDateTime.of(2026, 11, 28, 9, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
) : LocalClock {
  override fun currentDate(): LocalDate = date

  override fun currentTime(): LocalTime = time

  override fun currentEpochMs(): Long {
    epochMs += 1_000L
    return epochMs
  }

  override fun greetingPeriod(): GreetingPeriod = GreetingPeriod.MORNING

  override fun zoneId(): ZoneId = ZoneId.of("UTC")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Unit4A2D3DBatch1HardeningTest {

  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var context: Context
  private val openDbs = mutableListOf<YawmiDatabase>()

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
  }

  @After
  fun tearDown() {
    openDbs.forEach { db -> if (db.isOpen) db.close() }
    openDbs.clear()
  }

  private fun newContainer(): Triple<YawmiDatabase, DefaultAppContainer, Batch1Clock> {
    val db = YawmiDatabase.inMemory(context)
    openDbs += db
    val clock = Batch1Clock()
    val container =
      DefaultAppContainer(
        context = context,
        databaseProvider = { db },
        localClockProvider = { clock },
      )
    return Triple(db, container, clock)
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

  // ==================== DEF-01 ====================

  @Test
  fun def01_completedTask_rejectsAllRecurrenceAndReminderMutations() = runBlocking {
    val (db, container, _) = newContainer()
    val taskUseCases = container.taskUseCases
    val reminderUseCases = container.taskReminderUseCases

    val task =
      (taskUseCases.createTask(
        TaskInput(
          title = "Completed guard",
          scheduledDate = "2026-11-28",
          startTime = "10:00",
        )
      ) as TaskDomainResult.Success)
        .value

    val initialRec =
      (taskUseCases.saveTaskRecurrence(
        task.id,
        TaskRecurrenceInput(
          frequency = RecurrenceFrequency.DAILY,
          interval = 1,
          endDate = "2026-11-28",
        ),
      ) as TaskDomainResult.Success)
        .value

    val reminder =
      (reminderUseCases.createRelativeReminder(task.id, 15) as TaskReminderResult.Success).value

    val completion = (taskUseCases.completeTask(task.id) as TaskDomainResult.Success).value
    assertEquals(TaskStatus.COMPLETED, completion.status)

    val outboxCountAfterComplete = container.reminderSyncRepository.getAllOutboxOrdered().size

    // Recurrence mutations must fail with InvalidStatusTransition(COMPLETED, COMPLETED)
    val saveRecResult =
      taskUseCases.saveTaskRecurrence(
        task.id,
        TaskRecurrenceInput(frequency = RecurrenceFrequency.WEEKLY, interval = 2),
      )
    assertTrue(saveRecResult is TaskDomainResult.Failure)
    val saveErr = (saveRecResult as TaskDomainResult.Failure).errors.single()
    assertEquals(
      TaskValidationError.InvalidStatusTransition(TaskStatus.COMPLETED, TaskStatus.COMPLETED),
      saveErr,
    )
    assertEquals(initialRec.frequency, taskUseCases.getTaskRecurrence(task.id)?.frequency)

    val removeRecResult = taskUseCases.removeTaskRecurrence(task.id)
    assertTrue(removeRecResult is TaskDomainResult.Failure)
    assertEquals(
      TaskValidationError.InvalidStatusTransition(TaskStatus.COMPLETED, TaskStatus.COMPLETED),
      (removeRecResult as TaskDomainResult.Failure).errors.single(),
    )
    assertNotNull(taskUseCases.getTaskRecurrence(task.id))

    // All reminder mutations must fail with TaskNotActive(COMPLETED)
    val createRel = reminderUseCases.createRelativeReminder(task.id, 10)
    assertEquals(
      TaskReminderError.TaskNotActive(TaskStatus.COMPLETED),
      (createRel as TaskReminderResult.Failure).error,
    )

    val createExp = reminderUseCases.createExplicitReminder(task.id, "08:00")
    assertEquals(
      TaskReminderError.TaskNotActive(TaskStatus.COMPLETED),
      (createExp as TaskReminderResult.Failure).error,
    )

    val enableRes = reminderUseCases.setReminderEnabled(task.id, reminder.id, true)
    assertEquals(
      TaskReminderError.TaskNotActive(TaskStatus.COMPLETED),
      (enableRes as TaskReminderResult.Failure).error,
    )

    // Crucial: pausing an already-cancelled reminder on a completed task must NOT downgrade CANCELLED -> PAUSED
    val pauseRes = reminderUseCases.setReminderEnabled(task.id, reminder.id, false)
    assertEquals(
      TaskReminderError.TaskNotActive(TaskStatus.COMPLETED),
      (pauseRes as TaskReminderResult.Failure).error,
    )
    assertEquals(
      ReminderScheduleState.CANCELLED,
      db.reminderDao().getTaskReminderById(reminder.id)?.scheduleState,
    )

    val updateOff = reminderUseCases.updateReminderOffset(task.id, reminder.id, 30)
    assertEquals(
      TaskReminderError.TaskNotActive(TaskStatus.COMPLETED),
      (updateOff as TaskReminderResult.Failure).error,
    )

    val updateExp = reminderUseCases.updateExplicitReminderTime(task.id, reminder.id, "07:30")
    assertEquals(
      TaskReminderError.TaskNotActive(TaskStatus.COMPLETED),
      (updateExp as TaskReminderResult.Failure).error,
    )

    val snoozeRes = reminderUseCases.snoozeReminder(task.id, reminder.id, 10)
    assertEquals(
      TaskReminderError.TaskNotActive(TaskStatus.COMPLETED),
      (snoozeRes as TaskReminderResult.Failure).error,
    )

    val deleteRes = reminderUseCases.deleteReminder(reminder.id)
    assertEquals(
      TaskReminderError.TaskNotActive(TaskStatus.COMPLETED),
      (deleteRes as TaskReminderResult.Failure).error,
    )
    assertNotNull(db.reminderDao().getTaskReminderById(reminder.id))
    assertEquals(outboxCountAfterComplete, container.reminderSyncRepository.getAllOutboxOrdered().size)
  }

  @Test
  fun def01_cancelledTask_rejectsAllRecurrenceAndReminderMutations() = runBlocking {
    val (db, container, _) = newContainer()
    val taskUseCases = container.taskUseCases
    val reminderUseCases = container.taskReminderUseCases

    val task =
      (taskUseCases.createTask(
        TaskInput(
          title = "Cancelled guard",
          scheduledDate = "2026-11-28",
          startTime = "12:00",
        )
      ) as TaskDomainResult.Success)
        .value

    taskUseCases.saveTaskRecurrence(
      task.id,
      TaskRecurrenceInput(frequency = RecurrenceFrequency.DAILY, interval = 1),
    )
    val reminder =
      (reminderUseCases.createExplicitReminder(task.id, "11:00") as TaskReminderResult.Success).value

    val cancelled = (taskUseCases.cancelTask(task.id) as TaskDomainResult.Success).value
    assertEquals(TaskStatus.CANCELLED, cancelled.status)
    val outboxCountAfterCancel = container.reminderSyncRepository.getAllOutboxOrdered().size

    assertTrue(
      taskUseCases.saveTaskRecurrence(
        task.id,
        TaskRecurrenceInput(frequency = RecurrenceFrequency.MONTHLY, interval = 1),
      ) is TaskDomainResult.Failure
    )
    assertTrue(taskUseCases.removeTaskRecurrence(task.id) is TaskDomainResult.Failure)
    assertNotNull(taskUseCases.getTaskRecurrence(task.id))

    assertEquals(
      TaskReminderError.TaskNotActive(TaskStatus.CANCELLED),
      (reminderUseCases.createRelativeReminder(task.id, 10) as TaskReminderResult.Failure).error,
    )
    assertEquals(
      TaskReminderError.TaskNotActive(TaskStatus.CANCELLED),
      (reminderUseCases.createExplicitReminder(task.id, "08:30") as TaskReminderResult.Failure).error,
    )
    assertEquals(
      TaskReminderError.TaskNotActive(TaskStatus.CANCELLED),
      (reminderUseCases.setReminderEnabled(task.id, reminder.id, false) as TaskReminderResult.Failure).error,
    )
    assertEquals(
      TaskReminderError.TaskNotActive(TaskStatus.CANCELLED),
      (reminderUseCases.updateReminderOffset(task.id, reminder.id, 15) as TaskReminderResult.Failure).error,
    )
    assertEquals(
      TaskReminderError.TaskNotActive(TaskStatus.CANCELLED),
      (reminderUseCases.updateExplicitReminderTime(task.id, reminder.id, "09:00") as TaskReminderResult.Failure).error,
    )
    assertEquals(
      TaskReminderError.TaskNotActive(TaskStatus.CANCELLED),
      (reminderUseCases.snoozeReminder(task.id, reminder.id, 10) as TaskReminderResult.Failure).error,
    )
    assertEquals(
      TaskReminderError.TaskNotActive(TaskStatus.CANCELLED),
      (reminderUseCases.deleteReminder(reminder.id) as TaskReminderResult.Failure).error,
    )

    assertEquals(
      ReminderScheduleState.CANCELLED,
      db.reminderDao().getTaskReminderById(reminder.id)?.scheduleState,
    )
    assertEquals(outboxCountAfterCancel, container.reminderSyncRepository.getAllOutboxOrdered().size)
  }

  @Test
  fun def01_pendingAndInProgressTasks_preserveNormalRecurrenceAndReminderMutations() = runBlocking {
    val (db, container, _) = newContainer()
    val taskUseCases = container.taskUseCases
    val reminderUseCases = container.taskReminderUseCases

    val task =
      (taskUseCases.createTask(
        TaskInput(
          title = "Active task",
          scheduledDate = "2026-11-28",
          startTime = "14:00",
        )
      ) as TaskDomainResult.Success)
        .value

    // PENDING
    assertTrue(
      taskUseCases.saveTaskRecurrence(
        task.id,
        TaskRecurrenceInput(frequency = RecurrenceFrequency.DAILY, interval = 1),
      ) is TaskDomainResult.Success
    )
    val rem =
      (reminderUseCases.createRelativeReminder(task.id, 15) as TaskReminderResult.Success).value
    assertEquals("13:45", rem.triggerTime)

    // IN_PROGRESS
    val started = (taskUseCases.startTask(task.id) as TaskDomainResult.Success).value
    assertEquals(TaskStatus.IN_PROGRESS, started.status)

    assertTrue(
      reminderUseCases.updateReminderOffset(task.id, rem.id, 30) is TaskReminderResult.Success
    )
    assertEquals("13:30", db.reminderDao().getTaskReminderById(rem.id)?.triggerTime)

    val removeRec = taskUseCases.removeTaskRecurrence(task.id)
    assertTrue(removeRec is TaskDomainResult.Success && removeRec.value)
    val delRem = reminderUseCases.deleteReminder(rem.id)
    assertTrue(delRem is TaskReminderResult.Success && delRem.value)

    // TaskRules transition & basic edit compatibility preserved
    val edited =
      TaskRules.applyEdit(
        existing = started,
        input = TaskInput(title = "Edited active", scheduledDate = "2026-11-28", startTime = "14:00"),
        nowEpochMs = 1234L,
      )
    assertTrue(edited is TaskDomainResult.Success)
  }

  @Test
  fun def01_taskDetailAndEditorUi_lockRecurrenceAndRemindersOnTerminalTasks_enAndAr() {
    val (_, container, clock) = newContainer()
    val taskUseCases = container.taskUseCases
    val reminderUseCases = container.taskReminderUseCases

    val (completedTask, completedRem) =
      runBlocking {
        val t =
          (taskUseCases.createTask(
            TaskInput(
              title = "Finished work",
              scheduledDate = "2026-11-28",
              startTime = "10:00",
            )
          ) as TaskDomainResult.Success)
            .value
        taskUseCases.saveTaskRecurrence(
          t.id,
          TaskRecurrenceInput(
            frequency = RecurrenceFrequency.DAILY,
            interval = 1,
            endDate = "2026-11-28",
          ),
        )
        val r = (reminderUseCases.createRelativeReminder(t.id, 10) as TaskReminderResult.Success).value
        val done = (taskUseCases.completeTask(t.id) as TaskDomainResult.Success).value
        done to r
      }

    // Verify localized terminal mapper in EN and AR
    val enConfig = android.content.res.Configuration(context.resources.configuration).apply {
      setLocale(Locale.ENGLISH)
    }
    val arConfig = android.content.res.Configuration(context.resources.configuration).apply {
      setLocale(Locale.forLanguageTag("ar"))
    }
    val enCtx = context.createConfigurationContext(enConfig)
    val arCtx = context.createConfigurationContext(arConfig)
    assertEquals(
      enCtx.getString(R.string.task_error_terminal_locked),
      TaskValidationError.InvalidStatusTransition(TaskStatus.COMPLETED, TaskStatus.COMPLETED)
        .toLocalizedMessage(enCtx),
    )
    assertEquals(
      arCtx.getString(R.string.task_error_terminal_locked),
      TaskValidationError.InvalidStatusTransition(TaskStatus.CANCELLED, TaskStatus.CANCELLED)
        .toLocalizedMessage(arCtx),
    )

    // 1. TaskDetailScreen delete icon is disabled on completed task
    composeTestRule.setContent {
      ProvideYawmiLocale(language = AppLanguage.ENGLISH) {
        YawmiTheme(themeMode = AppThemeMode.DARK) {
          TaskDetailScreen(
            taskId = completedTask.id,
            taskRepository = container.taskRepository,
            taskUseCases = taskUseCases,
            categoryUseCases = container.categoryUseCases,
            localClock = clock,
            taskReminderUseCases = reminderUseCases,
            onNavigateBack = {},
          )
        }
      }
    }

    waitUntilWithIdle {
      composeTestRule
        .onAllNodesWithTag("task_detail_reminder_delete_${completedRem.id}")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    composeTestRule
      .onNodeWithTag("task_detail_reminder_delete_${completedRem.id}")
      .performScrollTo()
      .assertIsNotEnabled()
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_delete_btn")
      .performScrollTo()
      .assertIsNotEnabled()
  }

  @Test
  fun def01_taskEditorSheet_locksRecurrenceAndRemindersOnCancelledTask_arabicRtl() {
    val (db, container, clock) = newContainer()
    val taskUseCases = container.taskUseCases
    val reminderUseCases = container.taskReminderUseCases

    val cancelledTask =
      runBlocking {
        val t =
          (taskUseCases.createTask(
            TaskInput(
              title = "مهمة ملغاة",
              scheduledDate = "2026-11-28",
              startTime = "15:00",
            )
          ) as TaskDomainResult.Success)
            .value
        taskUseCases.saveTaskRecurrence(
          t.id,
          TaskRecurrenceInput(frequency = RecurrenceFrequency.WEEKLY, interval = 1),
        )
        reminderUseCases.createRelativeReminder(t.id, 10)
        (taskUseCases.cancelTask(t.id) as TaskDomainResult.Success).value
      }

    var savedTask: TaskEntity? = null
    composeTestRule.setContent {
      ProvideYawmiLocale(language = AppLanguage.ARABIC) {
        YawmiTheme(themeMode = AppThemeMode.DARK) {
          TaskEditorSheet(
            existingTask = cancelledTask,
            categories = emptyList(),
            taskUseCases = taskUseCases,
            taskReminderUseCases = reminderUseCases,
            localClock = clock,
            onManageCategoriesClick = {},
            onSaved = { savedTask = it },
            onDismiss = {},
          )
        }
      }
    }

    waitUntilWithIdle {
      composeTestRule
        .onAllNodesWithTag("task_recurrence_toggle")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }

    composeTestRule.onNodeWithTag("task_recurrence_toggle").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_editor_recurrence_terminal_hint").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("task_recurrence_freq_none").performScrollTo().assertIsNotEnabled()
    composeTestRule.onNodeWithTag("task_recurrence_freq_daily").performScrollTo().assertIsNotEnabled()

    composeTestRule.onNodeWithTag("task_reminders_toggle").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_editor_reminders_terminal_hint").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("task_reminder_preset_10").performScrollTo().assertIsNotEnabled()
    composeTestRule.onNodeWithTag("task_reminder_add_custom_btn").performScrollTo().assertIsNotEnabled()
    composeTestRule.onNodeWithTag("task_reminder_reenable_0").performScrollTo().assertIsNotEnabled()
    composeTestRule.onNodeWithTag("task_reminder_remove_0").performScrollTo().assertIsNotEnabled()

    // Editing allowed text field & saving leaves recurrence and cancelled reminder untouched
    composeTestRule.onNodeWithTag("task_title_input").performScrollTo().performTextReplacement("مهمة ملغاة محدثة")
    composeTestRule.onNodeWithTag("task_save_btn").performScrollTo().performClick()
    waitUntilWithIdle { savedTask != null }

    assertEquals("مهمة ملغاة محدثة", savedTask?.title)
    runBlocking {
      assertNotNull(taskUseCases.getTaskRecurrence(cancelledTask.id))
      val rems = reminderUseCases.getTaskReminders(cancelledTask.id)
      assertEquals(1, rems.size)
      assertEquals(ReminderScheduleState.CANCELLED, rems.single().scheduleState)
      assertNotNull(db.reminderDao().getTaskReminderById(rems.single().id))
    }
  }

  // ==================== DEF-02 ====================

  @Test
  fun def02_explicitReminderOnTimedTask_survivesTimeDateAndClearEdits_whileRelativeRecalculates() =
    runBlocking {
      val (db, container, clock) = newContainer()
      val taskUseCases = container.taskUseCases
      val reminderUseCases = container.taskReminderUseCases

      val task =
        (taskUseCases.createTask(
          TaskInput(
            title = "Schedule edits",
            scheduledDate = "2026-11-28",
            startTime = "10:00",
            endTime = "11:00",
          )
        ) as TaskDomainResult.Success)
          .value

      val explicitRem =
        (reminderUseCases.createExplicitReminder(task.id, "08:00") as TaskReminderResult.Success)
          .value
      val relativeRem =
        (reminderUseCases.createRelativeReminder(task.id, 15) as TaskReminderResult.Success).value
      assertEquals("09:45", relativeRem.triggerTime)

      // Seed a PENDING delivery on relativeRem so we verify STALE marking on reschedule
      val seededAt = clock.currentEpochMs()
      db.reminderDeliveryDao()
        .insertTaskDelivery(
          TaskReminderDeliveryEntity(
            id = "del-rel-1",
            taskReminderId = relativeRem.id,
            occurrenceKey = "occ-rel-1",
            scheduledAtEpochMs = seededAt,
            firedScheduleRevision = relativeRem.scheduleRevision,
            state = DeliveryState.PENDING,
            notificationTag = "task_${relativeRem.id}",
            createdAtEpochMs = seededAt,
            updatedAtEpochMs = seededAt,
          )
        )

      val outboxCountBeforeEdit = container.reminderSyncRepository.getAllOutboxOrdered().size

      // 1. Change ONLY startTime (10:00 -> 11:30): explicit 08:00 must stay untouched & NO spurious outbox!
      taskUseCases.updateTask(
        task.id,
        TaskInput(
          title = "Schedule edits",
          scheduledDate = "2026-11-28",
          startTime = "11:30",
          endTime = "12:30",
        ),
      )

      val expAfterTimeEdit = db.reminderDao().getTaskReminderById(explicitRem.id)!!
      assertEquals("2026-11-28", expAfterTimeEdit.triggerDate)
      assertEquals("08:00", expAfterTimeEdit.triggerTime)
      assertEquals(1L, expAfterTimeEdit.scheduleRevision)

      val relAfterTimeEdit = db.reminderDao().getTaskReminderById(relativeRem.id)!!
      assertEquals("2026-11-28", relAfterTimeEdit.triggerDate)
      assertEquals("11:15", relAfterTimeEdit.triggerTime)
      assertEquals(2L, relAfterTimeEdit.scheduleRevision)
      assertEquals(
        DeliveryState.STALE,
        db.reminderDeliveryDao().getTaskDelivery(relativeRem.id, "occ-rel-1")?.state,
      )

      val newOutboxAfterTimeEdit =
        container.reminderSyncRepository.getAllOutboxOrdered().drop(outboxCountBeforeEdit)
      assertFalse(newOutboxAfterTimeEdit.any { it.reminderId == explicitRem.id })
      assertTrue(
        newOutboxAfterTimeEdit.any {
          it.reminderId == relativeRem.id &&
            it.action == OutboxAction.SCHEDULE_ALARM &&
            it.targetScheduleRevision == 2L
        }
      )

      // 2. Change BOTH scheduledDate (2026-11-29) and startTime (14:00)
      taskUseCases.updateTask(
        task.id,
        TaskInput(
          title = "Schedule edits",
          scheduledDate = "2026-11-29",
          startTime = "14:00",
          endTime = "15:00",
        ),
      )

      val expAfterDateAndTime = db.reminderDao().getTaskReminderById(explicitRem.id)!!
      assertEquals("2026-11-29", expAfterDateAndTime.triggerDate)
      assertEquals("08:00", expAfterDateAndTime.triggerTime)
      assertEquals(2L, expAfterDateAndTime.scheduleRevision)

      val relAfterDateAndTime = db.reminderDao().getTaskReminderById(relativeRem.id)!!
      assertEquals("2026-11-29", relAfterDateAndTime.triggerDate)
      assertEquals("13:45", relAfterDateAndTime.triggerTime)
      assertEquals(3L, relAfterDateAndTime.scheduleRevision)

      // 3. Clear startTime while keeping date -> explicit stays enabled at 08:00, relative pauses cleanly
      taskUseCases.updateTask(
        task.id,
        TaskInput(
          title = "Schedule edits",
          scheduledDate = "2026-11-29",
          startTime = "",
          endTime = "",
        ),
      )

      val expAfterClearStart = db.reminderDao().getTaskReminderById(explicitRem.id)!!
      assertTrue(expAfterClearStart.isEnabled)
      assertEquals("2026-11-29", expAfterClearStart.triggerDate)
      assertEquals("08:00", expAfterClearStart.triggerTime)
      assertEquals(2L, expAfterClearStart.scheduleRevision)

      val relAfterClearStart = db.reminderDao().getTaskReminderById(relativeRem.id)!!
      assertFalse(relAfterClearStart.isEnabled)
      assertEquals(ReminderScheduleState.PAUSED, relAfterClearStart.scheduleState)
    }

  @Test
  fun def02_postponeAndUntimedToTimedTask_preserveExplicitTime_andZeroOffsetStartUpdate() =
    runBlocking {
      val (db, container, _) = newContainer()
      val taskUseCases = container.taskUseCases
      val reminderUseCases = container.taskReminderUseCases

      // A. Untimed task with explicit reminder -> adding startTime must NOT overwrite 08:00
      val untimed =
        (taskUseCases.createTask(
          TaskInput(title = "Untimed", scheduledDate = "2026-11-28")
        ) as TaskDomainResult.Success)
          .value
      val untimedExp =
        (reminderUseCases.createExplicitReminder(untimed.id, "08:00") as TaskReminderResult.Success)
          .value
      val outboxBeforeAddStart = container.reminderSyncRepository.getAllOutboxOrdered().size

      taskUseCases.updateTask(
        untimed.id,
        TaskInput(title = "Untimed", scheduledDate = "2026-11-28", startTime = "10:00"),
      )
      val afterAddStart = db.reminderDao().getTaskReminderById(untimedExp.id)!!
      assertEquals("2026-11-28", afterAddStart.triggerDate)
      assertEquals("08:00", afterAddStart.triggerTime)
      assertEquals(1L, afterAddStart.scheduleRevision)
      assertEquals(outboxBeforeAddStart, container.reminderSyncRepository.getAllOutboxOrdered().size)

      // B. Postpone timed task with explicit (08:00), anchored 0m-at-start (10:00), and relative 15m (09:45)
      val timed =
        (taskUseCases.createTask(
          TaskInput(
            title = "Postpone test",
            scheduledDate = "2026-11-28",
            startTime = "10:00",
          )
        ) as TaskDomainResult.Success)
          .value
      val exp =
        (reminderUseCases.createExplicitReminder(timed.id, "08:00") as TaskReminderResult.Success)
          .value
      val atStart =
        (reminderUseCases.createRelativeReminder(timed.id, 0) as TaskReminderResult.Success).value
      val rel15 =
        (reminderUseCases.createRelativeReminder(timed.id, 15) as TaskReminderResult.Success).value

      taskUseCases.postponeTask(timed.id, LocalDate.of(2026, 12, 1), clearTimeWindow = false)
      assertEquals("2026-12-01", db.reminderDao().getTaskReminderById(exp.id)?.triggerDate)
      assertEquals("08:00", db.reminderDao().getTaskReminderById(exp.id)?.triggerTime)
      assertEquals("2026-12-01", db.reminderDao().getTaskReminderById(atStart.id)?.triggerDate)
      assertEquals("10:00", db.reminderDao().getTaskReminderById(atStart.id)?.triggerTime)
      assertEquals("2026-12-01", db.reminderDao().getTaskReminderById(rel15.id)?.triggerDate)
      assertEquals("09:45", db.reminderDao().getTaskReminderById(rel15.id)?.triggerTime)

      // C. Postpone with clearTimeWindow = true: explicit keeps 08:00 enabled; atStart & rel15 pause
      taskUseCases.postponeTask(timed.id, LocalDate.of(2026, 12, 2), clearTimeWindow = true)
      val expCleared = db.reminderDao().getTaskReminderById(exp.id)!!
      assertTrue(expCleared.isEnabled)
      assertEquals("2026-12-02", expCleared.triggerDate)
      assertEquals("08:00", expCleared.triggerTime)
      assertEquals(ReminderScheduleState.PAUSED, db.reminderDao().getTaskReminderById(atStart.id)?.scheduleState)
      assertEquals(ReminderScheduleState.PAUSED, db.reminderDao().getTaskReminderById(rel15.id)?.scheduleState)

      // D. Recurring task next-occurrence reminder copy preserves explicit 08:00 and anchored 0m-at-start 12:00
      val recurring =
        (taskUseCases.createTask(
          TaskInput(
            title = "Recurring explicit vs at-start",
            scheduledDate = "2026-11-28",
            startTime = "12:00",
          )
        ) as TaskDomainResult.Success)
          .value
      taskUseCases.saveTaskRecurrence(
        recurring.id,
        TaskRecurrenceInput(frequency = RecurrenceFrequency.DAILY, interval = 1),
      )
      reminderUseCases.createExplicitReminder(recurring.id, "08:00")
      reminderUseCases.createRelativeReminder(recurring.id, 0)

      val completed =
        (taskUseCases.completeTaskWithNextOccurrence(recurring.id) as TaskDomainResult.Success)
          .value
      val spawnedTimes = completed.generatedReminders.map { it.triggerDate to it.triggerTime }.toSet()
      assertEquals(
        setOf("2026-11-29" to "08:00", "2026-11-29" to "12:00"),
        spawnedTimes,
      )
    }

  // ==================== DEF-03 ====================

  @Test
  fun def03_taskEditorSheet_pausedReminderStaysPausedAcrossScheduleEdits_untilExplicitReEnable_enLtr() {
    val (db, container, clock) = newContainer()
    val taskUseCases = container.taskUseCases
    val reminderUseCases = container.taskReminderUseCases

    val (task, pausedRem) =
      runBlocking {
        val created =
          (taskUseCases.createTask(
            TaskInput(
              title = "Paused reminder task",
              scheduledDate = "2026-11-28",
              startTime = "10:00",
            )
          ) as TaskDomainResult.Success)
            .value
        val rem =
          (reminderUseCases.createRelativeReminder(created.id, 30) as TaskReminderResult.Success)
            .value
        // Clear startTime so reminder transitions to PAUSED (isEnabled = false, scheduleState = PAUSED)
        val untimed =
          (taskUseCases.updateTask(
            created.id,
            TaskInput(
              title = "Paused reminder task",
              scheduledDate = "2026-11-28",
              startTime = "",
            ),
          ) as TaskDomainResult.Success)
            .value
        untimed to rem
      }

    var currentTaskForSheet = task
    var saveCount = 0

    composeTestRule.setContent {
      ProvideYawmiLocale(language = AppLanguage.ENGLISH) {
        YawmiTheme(themeMode = AppThemeMode.DARK) {
          TaskEditorSheet(
            existingTask = currentTaskForSheet,
            categories = emptyList(),
            taskUseCases = taskUseCases,
            taskReminderUseCases = reminderUseCases,
            localClock = clock,
            onManageCategoriesClick = {},
            onSaved = {
              currentTaskForSheet = it
              saveCount += 1
            },
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
    composeTestRule.onNodeWithTag("task_reminders_toggle").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    waitUntilWithIdle {
      composeTestRule
        .onAllNodesWithTag("task_reminder_status_badge_0")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }

    // 1. Paused badge & summary visible; re-enable disabled until startTime is restored
    composeTestRule
      .onNodeWithTag("task_reminders_summary", useUnmergedTree = true)
      .assertTextContains("1 paused")
    composeTestRule.onNodeWithTag("task_reminder_status_badge_0").performScrollTo().assertTextContains("Paused")
    composeTestRule.onNodeWithTag("task_reminder_paused_hint_0").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("task_reminder_reenable_0").performScrollTo().assertIsNotEnabled()

    val outboxBeforeEdits = runBlocking { container.reminderSyncRepository.getAllOutboxOrdered().size }

    // 2. Edit scheduledDate only -> Save -> reminder must stay PAUSED with 0 SCHEDULE_ALARM entries
    composeTestRule.onNodeWithTag("task_date_tomorrow").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("task_save_btn").performScrollTo().performClick()
    waitUntilWithIdle { saveCount == 1 }

    runBlocking {
      val remAfterDateEdit = db.reminderDao().getTaskReminderById(pausedRem.id)!!
      assertFalse(remAfterDateEdit.isEnabled)
      assertEquals(ReminderScheduleState.PAUSED, remAfterDateEdit.scheduleState)
      val addedOutbox = container.reminderSyncRepository.getAllOutboxOrdered().drop(outboxBeforeEdits)
      assertFalse(addedOutbox.any { it.action == OutboxAction.SCHEDULE_ALARM })
    }

    // 3. Edit startTime only (without clicking Re-enable) -> Save -> STILL PAUSED, 0 SCHEDULE_ALARM!
    composeTestRule.pickTimeInDialog(buttonTag = "task_start_time_btn", hour = "14", minute = "00")
    composeTestRule.onNodeWithTag("task_reminder_reenable_0").performScrollTo().assertIsEnabled()
    composeTestRule.onNodeWithTag("task_reminder_status_badge_0").performScrollTo().assertTextContains("Paused")
    composeTestRule.onNodeWithTag("task_save_btn").performScrollTo().performClick()
    waitUntilWithIdle { saveCount == 2 }

    runBlocking {
      val remAfterTimeEdit = db.reminderDao().getTaskReminderById(pausedRem.id)!!
      assertFalse(remAfterTimeEdit.isEnabled)
      assertEquals(ReminderScheduleState.PAUSED, remAfterTimeEdit.scheduleState)
      val addedOutbox = container.reminderSyncRepository.getAllOutboxOrdered().drop(outboxBeforeEdits)
      assertFalse(addedOutbox.any { it.action == OutboxAction.SCHEDULE_ALARM })
    }

    // 4. Edit BOTH date & startTime (without clicking Re-enable) -> Save -> STILL PAUSED, 0 SCHEDULE_ALARM!
    composeTestRule.onNodeWithTag("task_date_today").performScrollTo().performClick()
    composeTestRule.pickTimeInDialog(buttonTag = "task_start_time_btn", hour = "16", minute = "00")
    composeTestRule.onNodeWithTag("task_reminder_status_badge_0").performScrollTo().assertTextContains("Paused")
    composeTestRule.onNodeWithTag("task_save_btn").performScrollTo().performClick()
    waitUntilWithIdle { saveCount == 3 }

    runBlocking {
      val remAfterBothEdits = db.reminderDao().getTaskReminderById(pausedRem.id)!!
      assertFalse(remAfterBothEdits.isEnabled)
      assertEquals(ReminderScheduleState.PAUSED, remAfterBothEdits.scheduleState)
      val addedOutbox = container.reminderSyncRepository.getAllOutboxOrdered().drop(outboxBeforeEdits)
      assertFalse(addedOutbox.any { it.action == OutboxAction.SCHEDULE_ALARM })
    }

    // 5. Explicitly click Re-enable -> Save -> reminder re-enabled & SCHEDULE_ALARM staged
    composeTestRule.onNodeWithTag("task_reminder_reenable_0").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_reminder_status_badge_0").performScrollTo().assertTextContains("Will re-enable")
    composeTestRule.onNodeWithTag("task_save_btn").performScrollTo().performClick()
    waitUntilWithIdle { saveCount == 4 }

    runBlocking {
      val reEnabled = db.reminderDao().getTaskReminderById(pausedRem.id)!!
      assertTrue(reEnabled.isEnabled)
      assertEquals(ReminderScheduleState.PENDING_SYNC, reEnabled.scheduleState)
      assertEquals("2026-11-28", reEnabled.triggerDate)
      assertEquals("15:30", reEnabled.triggerTime)
      val addedOutbox = container.reminderSyncRepository.getAllOutboxOrdered().drop(outboxBeforeEdits)
      assertTrue(
        addedOutbox.any {
          it.reminderId == pausedRem.id && it.action == OutboxAction.SCHEDULE_ALARM
        }
      )
    }
  }

  @Test
  fun def03_taskEditorSheet_pausedReminderVisibilityAndExplicitReEnable_arabicRtl() {
    val (db, container, clock) = newContainer()
    val taskUseCases = container.taskUseCases
    val reminderUseCases = container.taskReminderUseCases

    val (task, pausedRem) =
      runBlocking {
        val created =
          (taskUseCases.createTask(
            TaskInput(
              title = "تذكير متوقف",
              scheduledDate = "2026-11-28",
              startTime = "10:00",
            )
          ) as TaskDomainResult.Success)
            .value
        val rem =
          (reminderUseCases.createExplicitReminder(created.id, "08:30") as TaskReminderResult.Success)
            .value
        // Explicitly pause then clear date to also verify paused explicit reminder hint
        reminderUseCases.setReminderEnabled(created.id, rem.id, false)
        val clearedDate =
          (taskUseCases.updateTask(
            created.id,
            TaskInput(title = "تذكير متوقف", scheduledDate = ""),
          ) as TaskDomainResult.Success)
            .value
        clearedDate to rem
      }

    var saveCount = 0
    composeTestRule.setContent {
      ProvideYawmiLocale(language = AppLanguage.ARABIC) {
        YawmiTheme(themeMode = AppThemeMode.DARK) {
          TaskEditorSheet(
            existingTask = task,
            categories = emptyList(),
            taskUseCases = taskUseCases,
            taskReminderUseCases = reminderUseCases,
            localClock = clock,
            onManageCategoriesClick = {},
            onSaved = { saveCount += 1 },
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

    composeTestRule.onNodeWithTag("task_reminders_toggle").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    waitUntilWithIdle {
      composeTestRule
        .onAllNodesWithTag("task_reminder_status_badge_0")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    composeTestRule
      .onNodeWithTag("task_reminders_summary", useUnmergedTree = true)
      .assertTextContains("متوقف مؤقتاً", substring = true)
    composeTestRule.onNodeWithTag("task_reminder_status_badge_0").performScrollTo().assertTextContains("متوقف مؤقتاً")
    composeTestRule.onNodeWithTag("task_reminder_paused_hint_0").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("task_reminder_reenable_0").performScrollTo().assertIsNotEnabled()

    // Restore date only -> still paused until user clicks Arabic Re-enable
    composeTestRule.onNodeWithTag("task_date_tomorrow").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_reminder_reenable_0").performScrollTo().assertIsEnabled()
    composeTestRule.onNodeWithTag("task_reminder_status_badge_0").performScrollTo().assertTextContains("متوقف مؤقتاً")

    composeTestRule.onNodeWithTag("task_reminder_reenable_0").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_reminder_status_badge_0").performScrollTo().assertTextContains("سيُعاد تفعيله")
    composeTestRule.onNodeWithTag("task_save_btn").performScrollTo().performClick()
    waitUntilWithIdle { saveCount == 1 }

    runBlocking {
      val reEnabled = db.reminderDao().getTaskReminderById(pausedRem.id)!!
      assertTrue(reEnabled.isEnabled)
      assertEquals(ReminderScheduleState.PENDING_SYNC, reEnabled.scheduleState)
      assertEquals("2026-11-29", reEnabled.triggerDate)
      assertEquals("08:30", reEnabled.triggerTime)
    }
  }
}

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
