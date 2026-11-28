package com.example

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.core.di.DefaultAppContainer
import com.example.core.i18n.ProvideYawmiLocale
import com.example.core.preferences.AppLanguage
import com.example.core.preferences.AppThemeMode
import com.example.core.time.GreetingPeriod
import com.example.core.time.LocalClock
import com.example.data.local.db.YawmiDatabase
import com.example.data.local.entity.OutboxAction
import com.example.data.local.entity.RecurrenceFrequency
import com.example.data.local.entity.TaskStatus
import com.example.domain.task.TaskDomainResult
import com.example.domain.task.TaskInput
import com.example.domain.task.TaskRecurrenceInput
import com.example.domain.task.TaskRecurrenceRules
import com.example.domain.task.TaskReminderResult
import com.example.domain.task.TaskValidationError
import com.example.feature.tasks.detail.TaskDetailScreen
import com.example.feature.tasks.detail.TaskDetailUiEvent
import com.example.feature.tasks.detail.TaskDetailUiState
import com.example.feature.tasks.detail.TaskDetailViewModel
import com.example.ui.theme.YawmiTheme
import java.time.DayOfWeek
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class Unit4A2D3CRecurrenceClock(
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
class Unit4A2D3TaskDetailRecurrenceTest {

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

  private fun newContainer(): Triple<YawmiDatabase, DefaultAppContainer, Unit4A2D3CRecurrenceClock> {
    val db = YawmiDatabase.inMemory(context)
    openDbs += db
    val clock = Unit4A2D3CRecurrenceClock()
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
  fun taskDetailRecurrence_displaysExistingCustomDaysRuleInArabicRtlAndEnglishLtr() {
    val (_, container, clock) = newContainer()
    val taskUseCases = container.taskUseCases

    val task =
      runBlocking {
        val created =
          (taskUseCases.createTask(
            TaskInput(
              title = "مراجعة الأسبوع",
              scheduledDate = "2026-11-25",
              startTime = "09:30",
            )
          ) as TaskDomainResult.Success)
            .value
        val mask =
          TaskRecurrenceRules.maskFromDays(
            setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
          )
        taskUseCases.saveTaskRecurrence(
          taskId = created.id,
          input =
            TaskRecurrenceInput(
              frequency = RecurrenceFrequency.CUSTOM_DAYS,
              interval = 2,
              daysOfWeekMask = mask,
              endDate = "2026-12-31",
            ),
        )
        created
      }

    var activeLanguage by mutableStateOf(AppLanguage.ARABIC)

    composeTestRule.setContent {
      ProvideYawmiLocale(language = activeLanguage) {
        YawmiTheme(themeMode = AppThemeMode.DARK) {
          TaskDetailScreen(
            taskId = task.id,
            taskRepository = container.taskRepository,
            taskUseCases = taskUseCases,
            categoryUseCases = container.categoryUseCases,
            localClock = clock,
            taskReminderUseCases = container.taskReminderUseCases,
            onShowSnackbar = {},
            onNavigateBack = {},
          )
        }
      }
    }

    waitUntilWithIdle {
      composeTestRule
        .onAllNodesWithTag("task_detail_recurrence_summary_box")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }

    composeTestRule
      .onNodeWithTag("task_detail_recurrence_card")
      .performScrollTo()
      .assertIsDisplayed()
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_cadence_text")
      .performScrollTo()
      .assertTextContains("كل ٢ أسبوع", substring = true)
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_days_text")
      .performScrollTo()
      .assertIsDisplayed()
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_end_date_text")
      .performScrollTo()
      .assertTextContains("2026", substring = true)

    // Switch to English LTR and verify exact Monday..Sunday order and "Every 2 weeks"
    activeLanguage = AppLanguage.ENGLISH
    composeTestRule.waitForIdle()

    composeTestRule
      .onNodeWithTag("task_detail_recurrence_cadence_text")
      .performScrollTo()
      .assertTextContains("Every 2 weeks", substring = true)
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_days_text")
      .performScrollTo()
      .assertTextContains("Mon, Wed, Fri", substring = true)
  }

  @Test
  fun taskDetailRecurrence_addEditAllFrequenciesValidationEndDateAndDelete() {
    val (_, container, clock) = newContainer()
    val taskUseCases = container.taskUseCases
    val snackbarMessages = mutableListOf<String>()

    val task =
      runBlocking {
        (taskUseCases.createTask(
          TaskInput(
            title = "Workout routine",
            scheduledDate = "2026-11-25",
            startTime = "08:00",
          )
        ) as TaskDomainResult.Success)
          .value
      }

    composeTestRule.setContent {
      ProvideYawmiLocale(language = AppLanguage.ENGLISH) {
        YawmiTheme(themeMode = AppThemeMode.DARK) {
          TaskDetailScreen(
            taskId = task.id,
            taskRepository = container.taskRepository,
            taskUseCases = taskUseCases,
            categoryUseCases = container.categoryUseCases,
            localClock = clock,
            taskReminderUseCases = container.taskReminderUseCases,
            onShowSnackbar = { snackbarMessages += it },
            onNavigateBack = {},
          )
        }
      }
    }

    waitUntilWithIdle {
      composeTestRule
        .onAllNodes(hasTestTag("task_detail_add_recurrence_btn") and isEnabled())
        .fetchSemanticsNodes()
        .isNotEmpty()
    }

    composeTestRule.onNodeWithTag("task_detail_recurrence_empty").performScrollTo().assertIsDisplayed()

    // 1. Open Add Recurrence dialog -> test CUSTOM_DAYS validation & weekday Mon..Sun order
    composeTestRule.onNodeWithTag("task_detail_add_recurrence_btn").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_detail_recurrence_editor_dialog").assertIsDisplayed()

    composeTestRule
      .onNodeWithTag("task_detail_recurrence_freq_custom_days")
      .performScrollTo()
      .performClick()
    composeTestRule.waitForIdle()

    // Verify weekday chips exist in DayOfWeek.entries (Monday..Sunday) order
    val monBounds =
      composeTestRule
        .onNodeWithTag("task_detail_recurrence_day_monday")
        .fetchSemanticsNode()
        .boundsInRoot
    val tueBounds =
      composeTestRule
        .onNodeWithTag("task_detail_recurrence_day_tuesday")
        .fetchSemanticsNode()
        .boundsInRoot
    assertTrue("Expected Monday before Tuesday in LTR", monBounds.left < tueBounds.left)
    DayOfWeek.entries.forEach { day ->
      composeTestRule
        .onNodeWithTag("task_detail_recurrence_day_${day.name.lowercase()}")
        .performScrollTo()
        .assertIsDisplayed()
    }

    // Attempt save with empty weekday mask -> inline error, dialog remains open
    composeTestRule.onNodeWithTag("task_detail_recurrence_save_btn").performClick()
    composeTestRule.waitForIdle()
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_editor_error")
      .performScrollTo()
      .assertIsDisplayed()

    // Select Monday & Thursday, increment interval -> 2 weeks
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_day_monday")
      .performScrollTo()
      .performClick()
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_day_thursday")
      .performScrollTo()
      .performClick()
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_interval_inc")
      .performScrollTo()
      .performClick()
    composeTestRule.waitForIdle()

    composeTestRule
      .onNodeWithTag("task_detail_recurrence_interval_unit")
      .assertTextContains("Every 2 weeks", substring = true)
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_days_hint")
      .assertTextContains("every 2 week(s)", substring = true)

    composeTestRule.onNodeWithTag("task_detail_recurrence_save_btn").performClick()
    waitUntilWithIdle {
      snackbarMessages.contains("Recurrence rule added.") &&
        runBlocking {
          container.taskRepository.getTaskRecurrence(task.id)?.frequency ==
            RecurrenceFrequency.CUSTOM_DAYS
        }
    }

    val savedCustom = runBlocking { container.taskRepository.getTaskRecurrence(task.id)!! }
    assertEquals(RecurrenceFrequency.CUSTOM_DAYS, savedCustom.frequency)
    assertEquals(2, savedCustom.interval)
    assertEquals(
      TaskRecurrenceRules.maskFromDays(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)),
      savedCustom.daysOfWeekMask,
    )
    assertTrue(snackbarMessages.contains("Recurrence rule added."))

    // 2. Edit -> WEEKLY with no weekdays (same-weekday summary)
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_edit_btn")
      .performScrollTo()
      .performClick()
    composeTestRule.waitForIdle()
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_freq_weekly")
      .performScrollTo()
      .performClick()
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_day_monday")
      .performScrollTo()
      .performClick()
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_day_thursday")
      .performScrollTo()
      .performClick()
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_interval_dec")
      .performScrollTo()
      .performClick()
    composeTestRule.onNodeWithTag("task_detail_recurrence_save_btn").performClick()
    waitUntilWithIdle {
      snackbarMessages.contains("Recurrence rule updated.") &&
        runBlocking {
          container.taskRepository.getTaskRecurrence(task.id)?.frequency ==
            RecurrenceFrequency.WEEKLY
        }
    }
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_days_text")
      .performScrollTo()
      .assertTextContains("Repeats on the task weekday", substring = true)
    assertTrue(snackbarMessages.contains("Recurrence rule updated."))

    // 3. Edit -> MONTHLY (interval = 3)
    composeTestRule.onNodeWithTag("task_detail_recurrence_edit_btn").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_freq_monthly")
      .performScrollTo()
      .performClick()
    composeTestRule.onNodeWithTag("task_detail_recurrence_interval_inc").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("task_detail_recurrence_interval_inc").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("task_detail_recurrence_save_btn").performClick()
    waitUntilWithIdle {
      runBlocking {
        val rule = container.taskRepository.getTaskRecurrence(task.id)
        rule?.frequency == RecurrenceFrequency.MONTHLY && rule.interval == 3
      }
    }
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_cadence_text")
      .performScrollTo()
      .assertTextContains("Every 3 months", substring = true)

    // 4. Edit -> YEARLY (interval = 1)
    composeTestRule.onNodeWithTag("task_detail_recurrence_edit_btn").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_freq_yearly")
      .performScrollTo()
      .performClick()
    composeTestRule.onNodeWithTag("task_detail_recurrence_interval_dec").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("task_detail_recurrence_interval_dec").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("task_detail_recurrence_save_btn").performClick()
    waitUntilWithIdle {
      runBlocking {
        val rule = container.taskRepository.getTaskRecurrence(task.id)
        rule?.frequency == RecurrenceFrequency.YEARLY && rule.interval == 1
      }
    }
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_cadence_text")
      .performScrollTo()
      .assertTextContains("Every 1 year", substring = true)

    // 5. Edit -> DAILY with End Date validation (endDate < scheduledDate) then valid End Date -> Clear
    composeTestRule.onNodeWithTag("task_detail_recurrence_edit_btn").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_freq_daily")
      .performScrollTo()
      .performClick()
    composeTestRule.onNodeWithTag("task_detail_recurrence_interval_inc").performScrollTo().performClick()

    // Open End Date picker -> select Nov 20, 2026 (< Nov 25 scheduledDate)
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_end_date_btn")
      .performScrollTo()
      .performClick()
    composeTestRule.waitForIdle()
    composeTestRule
      .onNode(hasDateCell(month = "November", day = 20, year = 2026))
      .performClick()
    composeTestRule.onNodeWithTag("task_detail_recurrence_end_date_confirm_btn").performClick()
    composeTestRule.waitForIdle()

    // Attempt save -> inline validation error shown
    composeTestRule.onNodeWithTag("task_detail_recurrence_save_btn").performClick()
    composeTestRule.waitForIdle()
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_editor_error")
      .performScrollTo()
      .assertIsDisplayed()

    // Pick valid End Date Nov 28, 2026 -> Save succeeds
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_end_date_btn")
      .performScrollTo()
      .performClick()
    composeTestRule.waitForIdle()
    composeTestRule
      .onNode(hasDateCell(month = "November", day = 28, year = 2026))
      .performClick()
    composeTestRule.onNodeWithTag("task_detail_recurrence_end_date_confirm_btn").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_detail_recurrence_save_btn").performClick()

    waitUntilWithIdle {
      runBlocking {
        val rule = container.taskRepository.getTaskRecurrence(task.id)
        rule?.frequency == RecurrenceFrequency.DAILY &&
          rule.interval == 2 &&
          rule.endDate == "2026-11-28"
      }
    }
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_end_date_text")
      .performScrollTo()
      .assertTextContains("28 Nov 2026", substring = true)

    // Clear End Date
    composeTestRule.onNodeWithTag("task_detail_recurrence_edit_btn").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_end_date_clear")
      .performScrollTo()
      .performClick()
    composeTestRule.onNodeWithTag("task_detail_recurrence_save_btn").performClick()
    waitUntilWithIdle {
      runBlocking { container.taskRepository.getTaskRecurrence(task.id)?.endDate == null }
    }

    // 6. Delete recurrence with confirmation dialog (cancel first, then confirm)
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_delete_btn")
      .performScrollTo()
      .performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_detail_recurrence_delete_dialog").assertIsDisplayed()
    composeTestRule.onNodeWithTag("task_detail_recurrence_delete_cancel_btn").performClick()
    composeTestRule.waitForIdle()
    assertNotNull(runBlocking { container.taskRepository.getTaskRecurrence(task.id) })

    composeTestRule
      .onNodeWithTag("task_detail_recurrence_delete_btn")
      .performScrollTo()
      .performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_detail_recurrence_delete_confirm_btn").performClick()

    waitUntilWithIdle {
      snackbarMessages.contains("Recurrence rule removed.") &&
        runBlocking { container.taskRepository.getTaskRecurrence(task.id) == null } &&
        composeTestRule
          .onAllNodesWithTag("task_detail_recurrence_empty")
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    assertNotNull(runBlocking { container.taskRepository.getById(task.id) })
    assertTrue(snackbarMessages.contains("Recurrence rule removed."))
  }

  @Test
  fun taskDetailRecurrence_completeGeneratesNextOccurrenceAndReopenPreservesItWithWarning() {
    val (db, container, clock) = newContainer()
    val taskUseCases = container.taskUseCases
    val reminderUseCases = container.taskReminderUseCases
    val snackbarMessages = mutableListOf<String>()

    val (task, initialReminder) =
      runBlocking {
        val created =
          (taskUseCases.createTask(
            TaskInput(
              title = "Daily standup",
              scheduledDate = "2026-11-25",
              startTime = "10:00",
            )
          ) as TaskDomainResult.Success)
            .value
        taskUseCases.saveTaskRecurrence(
          taskId = created.id,
          input = TaskRecurrenceInput(frequency = RecurrenceFrequency.DAILY, interval = 1),
        )
        val rem =
          (reminderUseCases.createRelativeReminder(created.id, 15) as TaskReminderResult.Success)
            .value
        created to rem
      }

    composeTestRule.setContent {
      ProvideYawmiLocale(language = AppLanguage.ENGLISH) {
        YawmiTheme(themeMode = AppThemeMode.DARK) {
          TaskDetailScreen(
            taskId = task.id,
            taskRepository = container.taskRepository,
            taskUseCases = taskUseCases,
            categoryUseCases = container.categoryUseCases,
            localClock = clock,
            taskReminderUseCases = reminderUseCases,
            onShowSnackbar = { snackbarMessages += it },
            onNavigateBack = {},
          )
        }
      }
    }

    waitUntilWithIdle {
      composeTestRule
        .onAllNodes(hasTestTag("task_detail_complete_btn") and isEnabled())
        .fetchSemanticsNodes()
        .isNotEmpty()
    }

    // Complete recurring task from TaskDetailScreen
    composeTestRule.onNodeWithTag("task_detail_complete_btn").performScrollTo().performClick()

    waitUntilWithIdle {
      snackbarMessages.contains("Task completed. Next occurrence scheduled for 2026-11-26.") &&
        runBlocking {
          container.taskRepository.getById(task.id)?.status == TaskStatus.COMPLETED &&
            db.taskDao().observeTasksForDate("2026-11-26").first().isNotEmpty()
        }
    }

    // Verify completed task + recurrence history preserved AND next occurrence created with reminders/outbox
    val completedTask = runBlocking { container.taskRepository.getById(task.id)!! }
    assertEquals(TaskStatus.COMPLETED, completedTask.status)
    assertNotNull(runBlocking { container.taskRepository.getTaskRecurrence(task.id) })

    val nextDayTasks = runBlocking { db.taskDao().observeTasksForDate("2026-11-26").first() }
    assertEquals(1, nextDayTasks.size)
    val nextTask = nextDayTasks.single()
    assertEquals(TaskStatus.PENDING, nextTask.status)
    assertEquals("Daily standup", nextTask.title)
    assertEquals("2026-11-26", nextTask.scheduledDate)
    assertEquals("10:00", nextTask.startTime)

    val nextRecurrence = runBlocking { container.taskRepository.getTaskRecurrence(nextTask.id) }
    assertNotNull(nextRecurrence)
    assertEquals(RecurrenceFrequency.DAILY, nextRecurrence!!.frequency)

    val nextReminders = runBlocking { db.reminderDao().getTaskRemindersByTaskId(nextTask.id) }
    assertEquals(1, nextReminders.size)
    val copiedReminder = nextReminders.single()
    assertEquals("2026-11-26", copiedReminder.triggerDate)
    assertEquals("09:45", copiedReminder.triggerTime)
    assertTrue(copiedReminder.requestCode != initialReminder.requestCode)

    val outboxCommands = runBlocking { db.alarmSyncOutboxDao().getAllOrdered() }
    assertTrue(
      outboxCommands.any {
        it.reminderId == copiedReminder.id && it.action == OutboxAction.SCHEDULE_ALARM
      }
    )

    assertTrue(
      snackbarMessages.contains("Task completed. Next occurrence scheduled for 2026-11-26.")
    )

    // Terminal state guards + reopen warning visible on COMPLETED task
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_terminal_hint")
      .performScrollTo()
      .assertIsDisplayed()
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_reopen_hint")
      .performScrollTo()
      .assertIsDisplayed()
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_edit_btn")
      .performScrollTo()
      .assertIsNotEnabled()
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_delete_btn")
      .performScrollTo()
      .assertIsNotEnabled()

    // Reopen task -> next occurrence remains untouched & reopen warning stays visible
    composeTestRule.onNodeWithTag("task_detail_reopen_btn").performScrollTo().performClick()
    waitUntilWithIdle {
      snackbarMessages.contains("Task reopened.") &&
        runBlocking { container.taskRepository.getById(task.id)?.status == TaskStatus.PENDING }
    }

    assertEquals(
      1,
      runBlocking { db.taskDao().observeTasksForDate("2026-11-26").first().size },
    )
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_reopen_hint")
      .performScrollTo()
      .assertIsDisplayed()
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_edit_btn")
      .performScrollTo()
      .assertIsEnabled()
    assertTrue(snackbarMessages.contains("Task reopened."))
  }

  @Test
  fun taskDetailRecurrence_unscheduledAndCancelledUiGuardsDisableActions() {
    val (_, container, clock) = newContainer()
    val taskUseCases = container.taskUseCases

    val (unscheduledTask, cancelledRecurringTask) =
      runBlocking {
        val noDate =
          (taskUseCases.createTask(TaskInput(title = "Someday task")) as TaskDomainResult.Success)
            .value
        val cancelled =
          (taskUseCases.createTask(
            TaskInput(
              title = "Cancelled series",
              scheduledDate = "2026-11-25",
            )
          ) as TaskDomainResult.Success)
            .value
        taskUseCases.saveTaskRecurrence(
          taskId = cancelled.id,
          input = TaskRecurrenceInput(frequency = RecurrenceFrequency.WEEKLY, interval = 1),
        )
        taskUseCases.cancelTask(cancelled.id)
        noDate to cancelled
      }

    var targetTaskId by mutableStateOf(unscheduledTask.id)

    composeTestRule.setContent {
      ProvideYawmiLocale(language = AppLanguage.ENGLISH) {
        YawmiTheme(themeMode = AppThemeMode.DARK) {
          TaskDetailScreen(
            taskId = targetTaskId,
            taskRepository = container.taskRepository,
            taskUseCases = taskUseCases,
            categoryUseCases = container.categoryUseCases,
            localClock = clock,
            taskReminderUseCases = container.taskReminderUseCases,
            onShowSnackbar = {},
            onNavigateBack = {},
          )
        }
      }
    }

    waitUntilWithIdle {
      composeTestRule
        .onAllNodesWithTag("task_detail_recurrence_missing_date_hint")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    composeTestRule
      .onNodeWithTag("task_detail_add_recurrence_btn")
      .performScrollTo()
      .assertIsNotEnabled()

    targetTaskId = cancelledRecurringTask.id
    waitUntilWithIdle {
      composeTestRule
        .onAllNodesWithTag("task_detail_recurrence_terminal_hint")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_edit_btn")
      .performScrollTo()
      .assertIsNotEnabled()
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_delete_btn")
      .performScrollTo()
      .assertIsNotEnabled()
    assertEquals(
      0,
      composeTestRule
        .onAllNodesWithTag("task_detail_recurrence_reopen_hint")
        .fetchSemanticsNodes()
        .size,
    )
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun taskDetailViewModel_rejectsRecurrenceMutationsOnCompletedAndCancelledTasks() {
    Dispatchers.setMain(Dispatchers.Default)
    try {
      runBlocking {
        val (_, container, clock) = newContainer()
        val taskUseCases = container.taskUseCases

        val completedTask =
          (taskUseCases.createTask(
            TaskInput(title = "Completed task", scheduledDate = "2026-11-25")
          ) as TaskDomainResult.Success)
            .value
        taskUseCases.saveTaskRecurrence(
          taskId = completedTask.id,
          input = TaskRecurrenceInput(frequency = RecurrenceFrequency.DAILY, interval = 1),
        )
        taskUseCases.completeTaskWithNextOccurrence(completedTask.id)

        val completedVm =
          TaskDetailViewModel(
            taskId = completedTask.id,
            taskRepository = container.taskRepository,
            categoryUseCases = container.categoryUseCases,
            taskUseCases = taskUseCases,
            localClock = clock,
            taskReminderUseCases = container.taskReminderUseCases,
          )
        completedVm.uiState.first { it is TaskDetailUiState.Loaded }

        val saveCompletedErr =
          async(start = CoroutineStart.UNDISPATCHED) { completedVm.events.first() }
        completedVm.saveRecurrence(
          TaskRecurrenceInput(frequency = RecurrenceFrequency.WEEKLY, interval = 2)
        )
        val saveCompletedEvent = saveCompletedErr.await() as TaskDetailUiEvent.ActionFailed
        assertTrue(saveCompletedEvent.error is TaskValidationError.InvalidStatusTransition)
        assertEquals(
          RecurrenceFrequency.DAILY,
          container.taskRepository.getTaskRecurrence(completedTask.id)?.frequency,
        )

        val removeCompletedErr =
          async(start = CoroutineStart.UNDISPATCHED) { completedVm.events.first() }
        completedVm.removeRecurrence()
        val removeCompletedEvent = removeCompletedErr.await() as TaskDetailUiEvent.ActionFailed
        assertTrue(removeCompletedEvent.error is TaskValidationError.InvalidStatusTransition)
        assertNotNull(container.taskRepository.getTaskRecurrence(completedTask.id))

        val cancelledTask =
          (taskUseCases.createTask(
            TaskInput(title = "Cancelled task", scheduledDate = "2026-11-25")
          ) as TaskDomainResult.Success)
            .value
        taskUseCases.cancelTask(cancelledTask.id)

        val cancelledVm =
          TaskDetailViewModel(
            taskId = cancelledTask.id,
            taskRepository = container.taskRepository,
            categoryUseCases = container.categoryUseCases,
            taskUseCases = taskUseCases,
            localClock = clock,
            taskReminderUseCases = container.taskReminderUseCases,
          )
        cancelledVm.uiState.first { it is TaskDetailUiState.Loaded }

        val saveCancelledErr =
          async(start = CoroutineStart.UNDISPATCHED) { cancelledVm.events.first() }
        cancelledVm.saveRecurrence(
          TaskRecurrenceInput(frequency = RecurrenceFrequency.MONTHLY, interval = 1)
        )
        val saveCancelledEvent = saveCancelledErr.await() as TaskDetailUiEvent.ActionFailed
        assertTrue(saveCancelledEvent.error is TaskValidationError.InvalidStatusTransition)
        assertNull(container.taskRepository.getTaskRecurrence(cancelledTask.id))
      }
    } finally {
      Dispatchers.resetMain()
    }
  }
}

private fun hasDateCell(month: String, day: Int, year: Int): SemanticsMatcher =
  SemanticsMatcher("Date cell for $month $day, $year") { node ->
    val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ").orEmpty()
    text.contains(year.toString()) &&
      text.contains(" $day,") &&
      text.contains(month, ignoreCase = true)
  }
