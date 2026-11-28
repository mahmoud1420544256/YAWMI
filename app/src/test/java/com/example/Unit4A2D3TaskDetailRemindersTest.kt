package com.example

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
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
import com.example.data.local.entity.ReminderScheduleState
import com.example.data.local.entity.TaskStatus
import com.example.domain.task.TaskDomainResult
import com.example.domain.task.TaskInput
import com.example.domain.task.TaskReminderError
import com.example.domain.task.TaskReminderResult
import com.example.feature.tasks.detail.TaskDetailScreen
import com.example.feature.tasks.detail.TaskDetailUiEvent
import com.example.feature.tasks.detail.TaskDetailUiState
import com.example.feature.tasks.detail.TaskDetailViewModel
import com.example.ui.theme.YawmiTheme
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class Unit4A2D3Clock(
  private val date: LocalDate = LocalDate.of(2026, 11, 25),
  private val time: LocalTime = LocalTime.of(9, 15),
  private var epochMs: Long =
    LocalDateTime.of(2026, 11, 25, 9, 15).toInstant(ZoneOffset.UTC).toEpochMilli(),
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
class Unit4A2D3TaskDetailRemindersTest {

  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var context: Context
  private val openDbs = mutableListOf<YawmiDatabase>()

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
  }

  @After
  fun tearDown() {
    openDbs.forEach { db ->
      if (db.isOpen) db.close()
    }
    openDbs.clear()
  }

  private fun newContainer(): Triple<YawmiDatabase, DefaultAppContainer, Unit4A2D3Clock> {
    val db = YawmiDatabase.inMemory(context)
    openDbs += db
    val clock = Unit4A2D3Clock()
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

  @Test
  fun taskDetailReminders_observesLiveUpdatesAndDisplaysDeliveryAndScheduleDiagnosticsInArabicRtl() {
    val (db, container, clock) = newContainer()
    val taskUseCases = container.taskUseCases
    val reminderUseCases = container.taskReminderUseCases
    val categoryUseCases = container.categoryUseCases

    val (task, reminder) =
      runBlocking {
        val createdTask =
          (taskUseCases.createTask(
            TaskInput(
              title = "متابعة التقرير اليومي",
              scheduledDate = "2026-11-25",
              startTime = "10:00",
            )
          ) as TaskDomainResult.Success)
            .value
        val rem =
          (reminderUseCases.createRelativeReminder(createdTask.id, 15) as TaskReminderResult.Success)
            .value
        createdTask to rem
      }

    composeTestRule.setContent {
      ProvideYawmiLocale(language = AppLanguage.ARABIC) {
        YawmiTheme(themeMode = AppThemeMode.DARK) {
          TaskDetailScreen(
            taskId = task.id,
            taskRepository = container.taskRepository,
            taskUseCases = taskUseCases,
            categoryUseCases = categoryUseCases,
            localClock = clock,
            taskReminderUseCases = reminderUseCases,
            onNavigateBack = {},
          )
        }
      }
    }

    waitUntilWithIdle {
      composeTestRule
        .onAllNodesWithTag("task_detail_reminder_item_${reminder.id}")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }

    composeTestRule.onNodeWithTag("task_detail_reminders_card").performScrollTo().assertIsDisplayed()
    composeTestRule
      .onNodeWithTag("task_detail_reminder_trigger_${reminder.id}")
      .performScrollTo()
      .assertIsDisplayed()
    composeTestRule
      .onNodeWithTag("task_detail_reminder_schedule_state_${reminder.id}")
      .performScrollTo()
      .assertIsDisplayed()

    // Live update: ReminderScheduleState -> SCHEDULED_INEXACT + DeliveryState -> SUPPRESSED_PERMISSION
    runBlocking {
      db.reminderDao().updateTaskReminderScheduleStateIfRevision(
        id = reminder.id,
        expectedRevision = reminder.scheduleRevision,
        scheduleState = ReminderScheduleState.SCHEDULED_INEXACT,
        updatedAtEpochMs = clock.currentEpochMs(),
      )
      val delivery =
        container.reminderSyncRepository.recordPendingTaskDelivery(
          deliveryId = "del-perm-1",
          taskReminderId = reminder.id,
          occurrenceKey = "2026-11-25T09:45:00",
          scheduledAtEpochMs = clock.currentEpochMs() + 60_000L,
          firedScheduleRevision = reminder.scheduleRevision,
          nowEpochMs = clock.currentEpochMs(),
        )
      container.reminderSyncRepository.updateTaskDeliveryState(
        deliveryId = delivery.id,
        state = DeliveryState.SUPPRESSED_PERMISSION,
        attemptCount = 1,
        lastAttemptAtEpochMs = clock.currentEpochMs(),
        lastError = "POST_NOTIFICATIONS denied",
        nowEpochMs = clock.currentEpochMs(),
      )
    }

    waitUntilWithIdle {
      composeTestRule
        .onAllNodesWithTag("task_detail_reminder_diag_suppressed_permission")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    composeTestRule
      .onNodeWithTag("task_detail_reminder_diag_suppressed_permission")
      .performScrollTo()
      .assertIsDisplayed()
    composeTestRule
      .onNodeWithTag("task_detail_reminder_diag_inexact")
      .performScrollTo()
      .assertIsDisplayed()
    composeTestRule
      .onNodeWithTag("task_detail_reminder_delivery_state_${reminder.id}")
      .performScrollTo()
      .assertIsDisplayed()

    // Update delivery -> SUPPRESSED_CHANNEL
    runBlocking {
      container.reminderSyncRepository.updateTaskDeliveryState(
        deliveryId = "del-perm-1",
        state = DeliveryState.SUPPRESSED_CHANNEL,
        attemptCount = 2,
        lastAttemptAtEpochMs = clock.currentEpochMs(),
        lastError = "Channel disabled",
        nowEpochMs = clock.currentEpochMs(),
      )
    }
    waitUntilWithIdle {
      composeTestRule
        .onAllNodesWithTag("task_detail_reminder_diag_suppressed_channel")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    composeTestRule
      .onNodeWithTag("task_detail_reminder_diag_suppressed_channel")
      .performScrollTo()
      .assertIsDisplayed()

    // Update delivery -> FAILED_PERMANENT
    runBlocking {
      container.reminderSyncRepository.updateTaskDeliveryState(
        deliveryId = "del-perm-1",
        state = DeliveryState.FAILED_PERMANENT,
        attemptCount = 3,
        lastAttemptAtEpochMs = clock.currentEpochMs(),
        lastError = "Permanent delivery failure",
        nowEpochMs = clock.currentEpochMs(),
      )
    }
    waitUntilWithIdle {
      composeTestRule
        .onAllNodesWithTag("task_detail_reminder_diag_failed_permanent")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    composeTestRule
      .onNodeWithTag("task_detail_reminder_diag_failed_permanent")
      .performScrollTo()
      .assertIsDisplayed()
  }

  @Test
  fun taskDetailReminders_createsRelativeAndExplicitReminders_withoutSynthesizingStartTime() {
    val (_, container, clock) = newContainer()
    val taskUseCases = container.taskUseCases
    val reminderUseCases = container.taskReminderUseCases
    val categoryUseCases = container.categoryUseCases

    val timedTask =
      runBlocking {
        (taskUseCases.createTask(
          TaskInput(
            title = "Timed design review",
            scheduledDate = "2026-11-25",
            startTime = "10:00",
          )
        ) as TaskDomainResult.Success)
          .value
      }
    val untimedTask =
      runBlocking {
        (taskUseCases.createTask(
          TaskInput(
            title = "Untimed errand",
            scheduledDate = "2026-11-25",
          )
        ) as TaskDomainResult.Success)
          .value
      }

    var activeTaskId by mutableStateOf(timedTask.id)
    val snackbarMessages = mutableListOf<String>()

    composeTestRule.setContent {
      ProvideYawmiLocale(language = AppLanguage.ENGLISH) {
        YawmiTheme(themeMode = AppThemeMode.LIGHT) {
          TaskDetailScreen(
            taskId = activeTaskId,
            taskRepository = container.taskRepository,
            taskUseCases = taskUseCases,
            categoryUseCases = categoryUseCases,
            localClock = clock,
            taskReminderUseCases = reminderUseCases,
            onNavigateBack = {},
            onShowSnackbar = { snackbarMessages += it },
          )
        }
      }
    }

    // 1. Add relative reminder on timed task
    waitUntilWithIdle {
      composeTestRule
        .onAllNodes(hasTestTag("task_detail_add_reminder_btn") and isEnabled())
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    composeTestRule.onNodeWithTag("task_detail_add_reminder_btn").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_detail_reminder_add_preset_10").performClick()

    waitUntilWithIdle {
      runBlocking { reminderUseCases.getTaskReminders(timedTask.id).size == 1 } &&
        composeTestRule
          .onAllNodes(hasTestTag("task_detail_add_reminder_btn") and isEnabled())
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    val createdRelative = runBlocking { reminderUseCases.getTaskReminders(timedTask.id).single() }
    assertEquals(10, createdRelative.offsetMinutes)
    assertEquals("2026-11-25", createdRelative.triggerDate)
    assertEquals("09:50", createdRelative.triggerTime)
    assertEquals(1L, createdRelative.scheduleRevision)

    // 2. Switch to untimed dated task inside the single Compose tree and create explicit reminder
    activeTaskId = untimedTask.id
    waitUntilWithIdle {
      composeTestRule
        .onAllNodesWithTag("task_detail_reminder_item_${createdRelative.id}")
        .fetchSemanticsNodes()
        .isEmpty() &&
        composeTestRule
          .onAllNodesWithTag("task_detail_reminders_empty")
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    composeTestRule.onNodeWithTag("task_detail_add_reminder_btn").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    // Relative preset must be disabled because untimed task has no startTime
    composeTestRule.onNodeWithTag("task_detail_reminder_add_preset_10").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("task_detail_reminder_add_custom_btn").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("yawmi_time_picker_mode_toggle").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.setTimeInputValues("07", "45")
    composeTestRule.onNodeWithTag("yawmi_time_picker_confirm_btn").performClick()

    waitUntilWithIdle {
      runBlocking { reminderUseCases.getTaskReminders(untimedTask.id).size == 1 }
    }
    val createdExplicit = runBlocking { reminderUseCases.getTaskReminders(untimedTask.id).single() }
    assertEquals(0, createdExplicit.offsetMinutes)
    assertEquals("2026-11-25", createdExplicit.triggerDate)
    assertEquals("07:45", createdExplicit.triggerTime)
    assertNull(runBlocking { container.taskRepository.getById(untimedTask.id)?.startTime })
    val outbox = runBlocking { container.reminderSyncRepository.getAllOutboxOrdered() }
    assertTrue(
      outbox.any {
        it.reminderId == createdExplicit.id &&
          it.action == OutboxAction.SCHEDULE_ALARM &&
          it.targetScheduleRevision == 1L
      }
    )
    assertTrue(snackbarMessages.isNotEmpty())
  }

  @Test
  fun taskDetailReminders_editsRelativeOffsetAndExplicitTime_incrementsRevision_marksPendingDeliveryStale() {
    val (db, container, clock) = newContainer()
    val taskUseCases = container.taskUseCases
    val reminderUseCases = container.taskReminderUseCases
    val categoryUseCases = container.categoryUseCases

    val (task, reminder) =
      runBlocking {
        val createdTask =
          (taskUseCases.createTask(
            TaskInput(
              title = "Edit reminder target",
              scheduledDate = "2026-11-25",
              startTime = "10:00",
            )
          ) as TaskDomainResult.Success)
            .value
        val rem =
          (reminderUseCases.createRelativeReminder(createdTask.id, 10) as TaskReminderResult.Success)
            .value
        container.reminderSyncRepository.recordPendingTaskDelivery(
          deliveryId = "del-edit-1",
          taskReminderId = rem.id,
          occurrenceKey = "2026-11-25T09:50:00",
          scheduledAtEpochMs = clock.currentEpochMs() + 120_000L,
          firedScheduleRevision = 1L,
          nowEpochMs = clock.currentEpochMs(),
        )
        createdTask to rem
      }

    val snackbarMessages = mutableListOf<String>()

    composeTestRule.setContent {
      ProvideYawmiLocale(language = AppLanguage.ENGLISH) {
        YawmiTheme(themeMode = AppThemeMode.DARK) {
          TaskDetailScreen(
            taskId = task.id,
            taskRepository = container.taskRepository,
            taskUseCases = taskUseCases,
            categoryUseCases = categoryUseCases,
            localClock = clock,
            taskReminderUseCases = reminderUseCases,
            onNavigateBack = {},
            onShowSnackbar = { snackbarMessages += it },
          )
        }
      }
    }

    waitUntilWithIdle {
      composeTestRule
        .onAllNodes(hasTestTag("task_detail_reminder_edit_${reminder.id}") and isEnabled())
        .fetchSemanticsNodes()
        .isNotEmpty()
    }

    // 1. Edit relative offset 10m -> 30m
    composeTestRule
      .onNodeWithTag("task_detail_reminder_edit_${reminder.id}")
      .performScrollTo()
      .performClick()
    waitUntilWithIdle {
      composeTestRule
        .onAllNodesWithTag("task_detail_reminder_edit_preset_30")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    composeTestRule.onNodeWithTag("task_detail_reminder_edit_preset_30").performClick()

    waitUntilWithIdle {
      runBlocking { db.reminderDao().getTaskReminderById(reminder.id)?.scheduleRevision == 2L } &&
        composeTestRule
          .onAllNodes(hasTestTag("task_detail_reminder_edit_${reminder.id}") and isEnabled())
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    val afterOffsetEdit = runBlocking { db.reminderDao().getTaskReminderById(reminder.id)!! }
    assertEquals(30, afterOffsetEdit.offsetMinutes)
    assertEquals("09:30", afterOffsetEdit.triggerTime)
    assertEquals(2L, afterOffsetEdit.scheduleRevision)
    val staleDelivery =
      runBlocking {
        db.reminderDeliveryDao().getTaskDelivery(reminder.id, "2026-11-25T09:50:00")
      }
    assertEquals(DeliveryState.STALE, staleDelivery?.state)

    // 2. Edit explicit time -> 08:15 via graphical time picker
    composeTestRule
      .onNodeWithTag("task_detail_reminder_edit_${reminder.id}")
      .performScrollTo()
      .performClick()
    waitUntilWithIdle {
      composeTestRule
        .onAllNodesWithTag("task_detail_reminder_edit_custom_btn")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    composeTestRule.onNodeWithTag("task_detail_reminder_edit_custom_btn").performClick()
    waitUntilWithIdle {
      composeTestRule
        .onAllNodesWithTag("yawmi_time_picker_mode_toggle")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    composeTestRule.onNodeWithTag("yawmi_time_picker_mode_toggle").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.setTimeInputValues("08", "15")
    composeTestRule.onNodeWithTag("yawmi_time_picker_confirm_btn").performClick()

    waitUntilWithIdle {
      runBlocking { db.reminderDao().getTaskReminderById(reminder.id)?.scheduleRevision == 3L }
    }
    val afterExplicitEdit = runBlocking { db.reminderDao().getTaskReminderById(reminder.id)!! }
    assertEquals(0, afterExplicitEdit.offsetMinutes)
    assertEquals("08:15", afterExplicitEdit.triggerTime)
    assertEquals("2026-11-25", afterExplicitEdit.triggerDate)
    val outbox = runBlocking { container.reminderSyncRepository.getAllOutboxOrdered() }
    assertTrue(
      outbox.any {
        it.reminderId == reminder.id &&
          it.action == OutboxAction.SCHEDULE_ALARM &&
          it.targetScheduleRevision == 3L
      }
    )
  }

  @Test
  fun taskDetailReminders_togglesEnablePauseAndDeletesWithConfirmation_stagesExpectedOutbox() {
    val (db, container, clock) = newContainer()
    val taskUseCases = container.taskUseCases
    val reminderUseCases = container.taskReminderUseCases
    val categoryUseCases = container.categoryUseCases

    val (task, reminder) =
      runBlocking {
        val createdTask =
          (taskUseCases.createTask(
            TaskInput(
              title = "Toggle & delete reminder",
              scheduledDate = "2026-11-25",
              startTime = "11:00",
            )
          ) as TaskDomainResult.Success)
            .value
        val rem =
          (reminderUseCases.createRelativeReminder(createdTask.id, 10) as TaskReminderResult.Success)
            .value
        createdTask to rem
      }

    val snackbarMessages = mutableListOf<String>()

    composeTestRule.setContent {
      ProvideYawmiLocale(language = AppLanguage.ENGLISH) {
        YawmiTheme(themeMode = AppThemeMode.DARK) {
          TaskDetailScreen(
            taskId = task.id,
            taskRepository = container.taskRepository,
            taskUseCases = taskUseCases,
            categoryUseCases = categoryUseCases,
            localClock = clock,
            taskReminderUseCases = reminderUseCases,
            onNavigateBack = {},
            onShowSnackbar = { snackbarMessages += it },
          )
        }
      }
    }

    val toggleTag = "task_detail_reminder_toggle_${reminder.id}"
    waitUntilWithIdle {
      composeTestRule
        .onAllNodes(hasTestTag(toggleTag) and isOn() and isEnabled())
        .fetchSemanticsNodes()
        .isNotEmpty()
    }

    // Toggle OFF
    composeTestRule.onNodeWithTag(toggleTag).performScrollTo().assertIsOn().performClick()
    waitUntilWithIdle {
      runBlocking { db.reminderDao().getTaskReminderById(reminder.id)?.isEnabled == false } &&
        composeTestRule
          .onAllNodes(hasTestTag(toggleTag) and isOff() and isEnabled())
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    val paused = runBlocking { db.reminderDao().getTaskReminderById(reminder.id)!! }
    assertEquals(ReminderScheduleState.PAUSED, paused.scheduleState)
    assertEquals(2L, paused.scheduleRevision)

    // Toggle ON
    composeTestRule.onNodeWithTag(toggleTag).performScrollTo().assertIsOff().performClick()
    waitUntilWithIdle {
      runBlocking { db.reminderDao().getTaskReminderById(reminder.id)?.isEnabled == true } &&
        composeTestRule
          .onAllNodes(hasTestTag(toggleTag) and isOn() and isEnabled())
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    val reEnabled = runBlocking { db.reminderDao().getTaskReminderById(reminder.id)!! }
    assertEquals(ReminderScheduleState.PENDING_SYNC, reEnabled.scheduleState)
    assertEquals(3L, reEnabled.scheduleRevision)

    // Delete with confirmation (dismiss first, then confirm)
    composeTestRule
      .onNodeWithTag("task_detail_reminder_delete_${reminder.id}")
      .performScrollTo()
      .performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_detail_reminder_delete_dialog").assertIsDisplayed()
    composeTestRule.onNodeWithTag("task_detail_reminder_delete_cancel_btn").performClick()
    composeTestRule.waitForIdle()
    assertNotNull(runBlocking { db.reminderDao().getTaskReminderById(reminder.id) })

    composeTestRule
      .onNodeWithTag("task_detail_reminder_delete_${reminder.id}")
      .performScrollTo()
      .performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_detail_reminder_delete_confirm_btn").performClick()

    waitUntilWithIdle {
      runBlocking { db.reminderDao().getTaskReminderById(reminder.id) == null } &&
        composeTestRule
          .onAllNodesWithTag("task_detail_reminders_empty")
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    composeTestRule.onNodeWithTag("task_detail_reminders_empty").performScrollTo().assertIsDisplayed()
    val outbox = runBlocking { container.reminderSyncRepository.getAllOutboxOrdered() }
    assertTrue(
      outbox.any {
        it.reminderId == reminder.id &&
          it.action == OutboxAction.CANCEL_ALARM &&
          it.targetScheduleRevision == 4L
      }
    )
    assertTrue(
      outbox.any {
        it.reminderId == reminder.id &&
          it.action == OutboxAction.CANCEL_NOTIFICATION &&
          it.targetScheduleRevision == 4L
      }
    )
  }

  @Test
  fun taskDetailReminders_snoozeUpdatesOnlyReminderAndOutbox_leavingTaskEntityUnchanged() {
    val (db, container, clock) = newContainer()
    val taskUseCases = container.taskUseCases
    val reminderUseCases = container.taskReminderUseCases
    val categoryUseCases = container.categoryUseCases

    val (task, reminder) =
      runBlocking {
        val createdTask =
          (taskUseCases.createTask(
            TaskInput(
              title = "Snooze isolation check",
              scheduledDate = "2026-11-25",
              startTime = "09:15",
            )
          ) as TaskDomainResult.Success)
            .value
        val rem =
          (reminderUseCases.createRelativeReminder(createdTask.id, 0) as TaskReminderResult.Success)
            .value
        container.reminderSyncRepository.recordPendingTaskDelivery(
          deliveryId = "del-snooze-1",
          taskReminderId = rem.id,
          occurrenceKey = "2026-11-25T09:15:00",
          scheduledAtEpochMs = clock.currentEpochMs() + 60_000L,
          firedScheduleRevision = 1L,
          nowEpochMs = clock.currentEpochMs(),
        )
        createdTask to rem
      }

    val snackbarMessages = mutableListOf<String>()

    composeTestRule.setContent {
      ProvideYawmiLocale(language = AppLanguage.ENGLISH) {
        YawmiTheme(themeMode = AppThemeMode.DARK) {
          TaskDetailScreen(
            taskId = task.id,
            taskRepository = container.taskRepository,
            taskUseCases = taskUseCases,
            categoryUseCases = categoryUseCases,
            localClock = clock,
            taskReminderUseCases = reminderUseCases,
            onNavigateBack = {},
            onShowSnackbar = { snackbarMessages += it },
          )
        }
      }
    }

    waitUntilWithIdle {
      composeTestRule
        .onAllNodes(hasTestTag("task_detail_reminder_snooze_${reminder.id}") and isEnabled())
        .fetchSemanticsNodes()
        .isNotEmpty()
    }

    composeTestRule
      .onNodeWithTag("task_detail_reminder_snooze_${reminder.id}")
      .performScrollTo()
      .performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_detail_snooze_dialog").assertIsDisplayed()
    composeTestRule.onNodeWithTag("task_detail_snooze_option_10").performClick()

    waitUntilWithIdle {
      runBlocking { db.reminderDao().getTaskReminderById(reminder.id)?.scheduleRevision == 2L }
    }

    val snoozedReminder = runBlocking { db.reminderDao().getTaskReminderById(reminder.id)!! }
    val unchangedTask = runBlocking { container.taskRepository.getById(task.id)!! }
    assertEquals("2026-11-25", snoozedReminder.triggerDate)
    assertEquals("09:25", snoozedReminder.triggerTime)
    assertEquals("2026-11-25", unchangedTask.scheduledDate)
    assertEquals("09:15", unchangedTask.startTime)

    val delivery =
      runBlocking { db.reminderDeliveryDao().getTaskDelivery(reminder.id, "2026-11-25T09:15:00") }
    assertEquals(DeliveryState.STALE, delivery?.state)
    val outbox = runBlocking { container.reminderSyncRepository.getAllOutboxOrdered() }
    assertTrue(
      outbox.any {
        it.reminderId == reminder.id &&
          it.action == OutboxAction.CANCEL_NOTIFICATION &&
          it.targetScheduleRevision == 2L
      }
    )
    assertTrue(
      outbox.any {
        it.reminderId == reminder.id &&
          it.action == OutboxAction.SCHEDULE_ALARM &&
          it.targetScheduleRevision == 2L
      }
    )
    waitUntilWithIdle { snackbarMessages.any { it.contains("09:25") } }
  }

  @Test
  fun taskDetailReminders_terminalTasksRestrictAddEditEnableSnooze_andReopenAllowsReEnabling() {
    val (db, container, clock) = newContainer()
    val taskUseCases = container.taskUseCases
    val reminderUseCases = container.taskReminderUseCases
    val categoryUseCases = container.categoryUseCases

    val (task, reminder) =
      runBlocking {
        val createdTask =
          (taskUseCases.createTask(
            TaskInput(
              title = "Terminal restriction task",
              scheduledDate = "2026-11-25",
              startTime = "12:00",
            )
          ) as TaskDomainResult.Success)
            .value
        val rem =
          (reminderUseCases.createRelativeReminder(createdTask.id, 30)
              as TaskReminderResult.Success)
            .value
        taskUseCases.completeTask(createdTask.id)
        createdTask to rem
      }

    composeTestRule.setContent {
      ProvideYawmiLocale(language = AppLanguage.ENGLISH) {
        YawmiTheme(themeMode = AppThemeMode.DARK) {
          TaskDetailScreen(
            taskId = task.id,
            taskRepository = container.taskRepository,
            taskUseCases = taskUseCases,
            categoryUseCases = categoryUseCases,
            localClock = clock,
            taskReminderUseCases = reminderUseCases,
            onNavigateBack = {},
          )
        }
      }
    }

    val toggleTag = "task_detail_reminder_toggle_${reminder.id}"

    waitUntilWithIdle {
      composeTestRule
        .onAllNodesWithTag("task_detail_reminders_terminal_hint")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }

    composeTestRule.onNodeWithTag("task_detail_add_reminder_btn").performScrollTo().assertIsNotEnabled()
    composeTestRule.onNodeWithTag(toggleTag).performScrollTo().assertIsOff().assertIsNotEnabled()
    composeTestRule
      .onNodeWithTag("task_detail_reminder_edit_${reminder.id}")
      .performScrollTo()
      .assertIsNotEnabled()
    composeTestRule
      .onNodeWithTag("task_detail_reminder_snooze_${reminder.id}")
      .performScrollTo()
      .assertIsNotEnabled()
    val cancelledRem = runBlocking { db.reminderDao().getTaskReminderById(reminder.id)!! }
    assertEquals(ReminderScheduleState.CANCELLED, cancelledRem.scheduleState)
    assertFalse(cancelledRem.isEnabled)

    // Reopen task -> reminder remains CANCELLED/disabled until user explicitly re-enables it
    composeTestRule.onNodeWithTag("task_detail_reopen_btn").performScrollTo().performClick()
    waitUntilWithIdle {
      runBlocking { container.taskRepository.getById(task.id)?.status == TaskStatus.PENDING } &&
        composeTestRule
          .onAllNodes(hasTestTag(toggleTag) and isOff() and isEnabled())
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    val afterReopen = runBlocking { db.reminderDao().getTaskReminderById(reminder.id)!! }
    assertEquals(ReminderScheduleState.CANCELLED, afterReopen.scheduleState)
    assertFalse(afterReopen.isEnabled)

    // Re-enabling on reopened task succeeds via Switch
    composeTestRule.onNodeWithTag(toggleTag).performScrollTo().assertIsOff().performClick()
    waitUntilWithIdle {
      runBlocking { db.reminderDao().getTaskReminderById(reminder.id)?.isEnabled == true } &&
        composeTestRule
          .onAllNodes(hasTestTag(toggleTag) and isOn() and isEnabled())
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    val reEnabled = runBlocking { db.reminderDao().getTaskReminderById(reminder.id)!! }
    assertEquals(ReminderScheduleState.PENDING_SYNC, reEnabled.scheduleState)
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun taskDetailViewModel_rejectsReminderMutationsOnTerminalTask() {
    Dispatchers.setMain(Dispatchers.Default)
    try {
      runBlocking {
        val (_, container, clock) = newContainer()
        val task =
          (container.taskUseCases.createTask(
            TaskInput(
              title = "Cancelled terminal guard",
              scheduledDate = "2026-11-25",
              startTime = "12:00",
            )
          ) as TaskDomainResult.Success)
            .value
        val rem =
          (container.taskReminderUseCases.createRelativeReminder(task.id, 15)
              as TaskReminderResult.Success)
            .value
        container.taskUseCases.cancelTask(task.id)

        val viewModel =
          TaskDetailViewModel(
            taskId = task.id,
            taskRepository = container.taskRepository,
            categoryUseCases = container.categoryUseCases,
            taskUseCases = container.taskUseCases,
            localClock = clock,
            taskReminderUseCases = container.taskReminderUseCases,
          )
        viewModel.uiState.first { it is TaskDetailUiState.Loaded }

        val addErr = async(start = CoroutineStart.UNDISPATCHED) { viewModel.events.first() }
        viewModel.addRelativeReminder(10)
        assertTrue((addErr.await() as TaskDetailUiEvent.ReminderActionFailed).error is TaskReminderError.TaskNotActive)

        val enableErr = async(start = CoroutineStart.UNDISPATCHED) { viewModel.events.first() }
        viewModel.setReminderEnabled(rem.id, true)
        assertTrue((enableErr.await() as TaskDetailUiEvent.ReminderActionFailed).error is TaskReminderError.TaskNotActive)

        val snoozeErr = async(start = CoroutineStart.UNDISPATCHED) { viewModel.events.first() }
        viewModel.snoozeReminder(rem.id, 10)
        assertTrue((snoozeErr.await() as TaskDetailUiEvent.ReminderActionFailed).error is TaskReminderError.TaskNotActive)
      }
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun taskDetailReminders_zeroOffsetAmbiguity_doesNotInferTypeAndTestsScheduleChangePauseResume() {
    val (db, container, clock) = newContainer()
    val taskUseCases = container.taskUseCases
    val reminderUseCases = container.taskReminderUseCases
    val categoryUseCases = container.categoryUseCases

    val (task, zeroRelativeRem, explicitRem) =
      runBlocking {
        val createdTask =
          (taskUseCases.createTask(
            TaskInput(
              title = "Zero offset check",
              scheduledDate = "2026-11-25",
              startTime = "10:00",
            )
          ) as TaskDomainResult.Success)
            .value
        val r0 =
          (reminderUseCases.createRelativeReminder(createdTask.id, 0)
              as TaskReminderResult.Success)
            .value
        val rExplicit =
          (reminderUseCases.createExplicitReminder(createdTask.id, "08:15")
              as TaskReminderResult.Success)
            .value
        Triple(createdTask, r0, rExplicit)
      }

    composeTestRule.setContent {
      ProvideYawmiLocale(language = AppLanguage.ENGLISH) {
        YawmiTheme(themeMode = AppThemeMode.DARK) {
          TaskDetailScreen(
            taskId = task.id,
            taskRepository = container.taskRepository,
            taskUseCases = taskUseCases,
            categoryUseCases = categoryUseCases,
            localClock = clock,
            taskReminderUseCases = reminderUseCases,
            onNavigateBack = {},
          )
        }
      }
    }

    val zeroToggleTag = "task_detail_reminder_toggle_${zeroRelativeRem.id}"

    waitUntilWithIdle {
      composeTestRule
        .onAllNodes(hasTestTag("task_detail_reminder_edit_${zeroRelativeRem.id}") and isEnabled())
        .fetchSemanticsNodes()
        .isNotEmpty()
    }

    // 1. UI must NOT pre-select preset 0 solely from offsetMinutes == 0
    composeTestRule
      .onNodeWithTag("task_detail_reminder_edit_${zeroRelativeRem.id}")
      .performScrollTo()
      .performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_detail_reminder_edit_preset_0").assertIsNotSelected()
    composeTestRule.onNodeWithTag("task_detail_edit_reminder_cancel_btn").performClick()
    composeTestRule.waitForIdle()

    // 2. Pause zero-offset reminder before schedule change; leave explicitRem enabled for comparison
    composeTestRule.onNodeWithTag(zeroToggleTag).performScrollTo().performClick()
    waitUntilWithIdle {
      runBlocking { db.reminderDao().getTaskReminderById(zeroRelativeRem.id)?.isEnabled == false } &&
        composeTestRule
          .onAllNodes(hasTestTag(zeroToggleTag) and isOff() and isEnabled())
          .fetchSemanticsNodes()
          .isNotEmpty()
    }

    // 3. Change task schedule from 2026-11-25 10:00 -> 2026-11-26 14:00
    runBlocking {
      taskUseCases.updateTask(
        taskId = task.id,
        input =
          TaskInput(
            title = "Zero offset check",
            scheduledDate = "2026-11-26",
            startTime = "14:00",
          ),
      )
    }

    waitUntilWithIdle {
      runBlocking {
        db.reminderDao().getTaskReminderById(explicitRem.id)?.triggerDate == "2026-11-26"
      }
    }

    // Documented Schema v1 behavior verification:
    // - Paused zero-offset reminder did NOT recalculate while paused
    val pausedDuringChange = runBlocking { db.reminderDao().getTaskReminderById(zeroRelativeRem.id)!! }
    assertEquals("2026-11-25", pausedDuringChange.triggerDate)
    assertEquals("10:00", pausedDuringChange.triggerTime)

    // - Enabled explicit reminder (offsetMinutes == 0, "08:15" != "10:00") moves to updated date ("2026-11-26")
    //   while preserving its explicit user-chosen triggerTime ("08:15").
    val enabledZeroOffsetAfterChange = runBlocking { db.reminderDao().getTaskReminderById(explicitRem.id)!! }
    assertEquals("2026-11-26", enabledZeroOffsetAfterChange.triggerDate)
    assertEquals("08:15", enabledZeroOffsetAfterChange.triggerTime)

    // 4. Resume paused zero-offset reminder via UI toggle
    composeTestRule.onNodeWithTag(zeroToggleTag).performScrollTo().performClick()
    waitUntilWithIdle {
      runBlocking { db.reminderDao().getTaskReminderById(zeroRelativeRem.id)?.isEnabled == true }
    }

    // Resuming offsetMinutes == 0 routes through explicit branch in TaskReminderUseCases.setReminderEnabled:
    // uses updated task.scheduledDate ("2026-11-26") while preserving stored triggerTime ("10:00").
    val resumedZeroOffset = runBlocking { db.reminderDao().getTaskReminderById(zeroRelativeRem.id)!! }
    assertEquals("2026-11-26", resumedZeroOffset.triggerDate)
    assertEquals("10:00", resumedZeroOffset.triggerTime)
    assertEquals(3L, resumedZeroOffset.scheduleRevision)
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
