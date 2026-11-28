package com.example

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.example.core.di.DefaultAppContainer
import com.example.core.i18n.ProvideYawmiLocale
import com.example.core.preferences.AppLanguage
import com.example.core.preferences.AppThemeMode
import com.example.core.time.GreetingPeriod
import com.example.core.time.LocalClock
import com.example.data.local.db.YawmiDatabase
import com.example.data.local.entity.TaskEntity
import com.example.data.local.entity.TaskPriority
import com.example.data.local.entity.TaskStatus
import com.example.domain.task.TaskDomainResult
import com.example.domain.task.TaskInput
import com.example.domain.task.TaskRules
import com.example.domain.task.TaskUseCases
import com.example.feature.tasks.components.TaskEditorSheet
import com.example.feature.tasks.components.YawmiTimePickerDialog
import com.example.ui.theme.YawmiTheme
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class Stage2TestClock(
  private val date: LocalDate = LocalDate.of(2026, 11, 27),
  private val time: LocalTime = LocalTime.of(9, 0),
  private val epochMs: Long = 1_764_234_000_000L,
) : LocalClock {
  override fun currentDate(): LocalDate = date

  override fun currentTime(): LocalTime = time

  override fun currentEpochMs(): Long = epochMs

  override fun greetingPeriod(): GreetingPeriod = GreetingPeriod.MORNING

  override fun zoneId(): ZoneId = ZoneId.of("UTC")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Unit4A2DTimePickerAndEditorTest {

  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var context: Context
  private lateinit var db: YawmiDatabase
  private lateinit var container: DefaultAppContainer
  private lateinit var clock: Stage2TestClock
  private lateinit var taskUseCases: TaskUseCases

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = YawmiDatabase.inMemory(context)
    container = DefaultAppContainer(context = context, databaseProvider = { db })
    clock = Stage2TestClock()
    taskUseCases = TaskUseCases(container.taskRepository, container.categoryRepository, clock)
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun yawmiTimePickerDialog_opensDial_togglesInput_cancelsWithoutSaving_andConfirmsHHmm() {
    var selectedTime by mutableStateOf<LocalTime?>(null)
    var showDialog by mutableStateOf(true)

    composeTestRule.setContent {
      ProvideYawmiLocale(language = AppLanguage.ARABIC) {
        YawmiTheme(themeMode = AppThemeMode.DARK) {
          if (showDialog) {
            YawmiTimePickerDialog(
              title = "تحديد وقت البداية",
              initialTime = LocalTime.of(9, 0),
              onConfirm = {
                selectedTime = it
                showDialog = false
              },
              onDismiss = { showDialog = false },
            )
          }
        }
      }
    }

    composeTestRule.waitForIdle()
    // 1. Opens in dial mode
    composeTestRule.onNodeWithTag("yawmi_time_picker_dial").assertIsDisplayed()

    // 2. Toggle to TimeInput
    composeTestRule.onNodeWithTag("yawmi_time_picker_mode_toggle").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("yawmi_time_picker_input").assertIsDisplayed()

    // Modify inputs, then cancel -> must NOT save
    composeTestRule.setTimeInputValues("14", "45")
    composeTestRule.onNodeWithTag("yawmi_time_picker_cancel_btn").performClick()
    composeTestRule.waitForIdle()
    assertNull(selectedTime)

    // Reopen, toggle back to dial then input, enter 07:05, confirm -> saved as 07:05
    showDialog = true
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("yawmi_time_picker_dial").assertIsDisplayed()
    composeTestRule.onNodeWithTag("yawmi_time_picker_mode_toggle").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.setTimeInputValues("07", "05")
    composeTestRule.onNodeWithTag("yawmi_time_picker_confirm_btn").performClick()
    composeTestRule.waitForIdle()

    assertEquals(LocalTime.of(7, 5), selectedTime)
  }

  @Test
  fun arabicRtlEditor_graphicalDateTimePickers_andNextDaySwitchLayoutAndLivePreview() {
    var savedTask: TaskEntity? = null

    composeTestRule.setContent {
      ProvideYawmiLocale(language = AppLanguage.ARABIC) {
        YawmiTheme(themeMode = AppThemeMode.DARK) {
          val categories by
            container.categoryUseCases.observeActiveCategories().collectAsState(initial = emptyList())
          TaskEditorSheet(
            existingTask = null,
            categories = categories,
            taskUseCases = taskUseCases,
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
    // Switch initially disabled without scheduledDate & full time window
    composeTestRule.onNodeWithTag("task_next_day_switch").performScrollTo().assertIsNotEnabled()
    composeTestRule.onNodeWithTag("task_next_day_switch").assertIsOff()

    // Verify RTL horizontal Switch placement: Switch is strictly to the LEFT of «اليوم التالي»
    val labelBounds =
      composeTestRule.onNodeWithTag("task_next_day_label").fetchSemanticsNode().boundsInRoot
    val switchBounds =
      composeTestRule.onNodeWithTag("task_next_day_switch").fetchSemanticsNode().boundsInRoot
    assertTrue(
      "Expected Switch left (${switchBounds.left}) < Arabic label left (${labelBounds.left}) in RTL",
      switchBounds.left < labelBounds.left,
    )
    composeTestRule.onNodeWithTag("task_next_day_label").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("اليوم التالي").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("دعم امتداد وقت المهمة إلى اليوم التالي").performScrollTo().assertIsDisplayed()

    // Pick date via DatePickerDialog (defaults to clock's currentDate = 2026-11-27)
    composeTestRule.onNodeWithTag("task_title_input").performScrollTo().performTextInput("مناوبة ليلية")
    composeTestRule.onNodeWithTag("task_start_date_btn").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("تأكيد").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_start_date_value").performScrollTo().assertIsDisplayed()

    // Pick Start Time 09:00 and End Time 17:00 -> same-day endpoints & 8 ساعات
    composeTestRule.pickTimeInDialog(buttonTag = "task_start_time_btn", hour = "09", minute = "00")
    composeTestRule.pickTimeInDialog(buttonTag = "task_end_time_btn", hour = "17", minute = "00")

    composeTestRule.onNodeWithTag("task_next_day_switch").performScrollTo().assertIsEnabled()
    composeTestRule.onNodeWithTag("task_end_date_value").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("27 نوفمبر 2026، 09:00 ← 27 نوفمبر 2026، 17:00").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("8 ساعات").performScrollTo().assertIsDisplayed()

    // Now change times to 21:00 -> 07:00, toggle Next-Day Switch ON -> immediate next date & 10 ساعات
    composeTestRule.pickTimeInDialog(buttonTag = "task_start_time_btn", hour = "21", minute = "00")
    composeTestRule.pickTimeInDialog(buttonTag = "task_end_time_btn", hour = "07", minute = "00")
    composeTestRule.onNodeWithTag("task_next_day_switch").performScrollTo().performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("task_next_day_switch").assertIsOn()
    composeTestRule.onNodeWithText("28 نوفمبر 2026").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("27 نوفمبر 2026، 21:00 ← 28 نوفمبر 2026، 07:00").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("10 ساعات").performScrollTo().assertIsDisplayed()

    // Toggle Next-Day Switch OFF -> EndDate reverts to StartDate immediately, user's 21:00/07:00 preserved, validation error on save
    composeTestRule.onNodeWithTag("task_next_day_switch").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_next_day_switch").assertIsOff()
    composeTestRule.onNodeWithText("27 نوفمبر 2026، 21:00 ← 27 نوفمبر 2026، 07:00").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("task_save_btn").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_end_time_error").performScrollTo().assertIsDisplayed()

    // Toggle Switch back ON -> next-day preview returns immediately and save succeeds
    composeTestRule.onNodeWithTag("task_next_day_switch").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_next_day_switch").assertIsOn()
    composeTestRule.onNodeWithText("10 ساعات").performScrollTo().assertIsDisplayed()

    // Save -> persisted cleanly
    composeTestRule.onNodeWithTag("task_save_btn").performScrollTo().performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000L) { savedTask != null }
    val created = savedTask!!
    assertEquals("2026-11-27", created.scheduledDate)
    assertEquals("21:00", created.startTime)
    assertEquals("07:00", created.endTime)
    assertTrue(TaskRules.inferSpansNextDay(created.startTime, created.endTime))
  }

  @Test
  fun reopeningSavedOvernightTask_restoresSwitchOn_andEditingTitleOnlyPreservesSpillover() {
    val existing = runBlocking {
      (taskUseCases.createTask(
          TaskInput(
            title = "مراقبة الخادم",
            scheduledDate = "2026-11-27",
            startTime = "21:00",
            endTime = "07:00",
            spansNextDay = true,
          )
        ) as TaskDomainResult.Success)
        .value
    }
    var savedUpdated: TaskEntity? = null

    composeTestRule.setContent {
      ProvideYawmiLocale(language = AppLanguage.ARABIC) {
        YawmiTheme(themeMode = AppThemeMode.DARK) {
          TaskEditorSheet(
            existingTask = existing,
            categories = emptyList(),
            taskUseCases = taskUseCases,
            taskReminderUseCases = container.taskReminderUseCases,
            localClock = clock,
            onManageCategoriesClick = {},
            onSaved = { savedUpdated = it },
            onDismiss = {},
          )
        }
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_next_day_switch").performScrollTo().assertIsOn()
    composeTestRule.onNodeWithText("27 نوفمبر 2026، 21:00 ← 28 نوفمبر 2026، 07:00").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("10 ساعات").performScrollTo().assertIsDisplayed()

    // Edit ONLY title and save -> must NOT fail validation or lose next-day spillover
    composeTestRule.onNodeWithTag("task_title_input").performScrollTo().performTextClearance()
    composeTestRule.onNodeWithTag("task_title_input").performScrollTo().performTextInput("مراقبة الخادم المحدّثة")
    composeTestRule.onNodeWithTag("task_save_btn").performScrollTo().performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000L) { savedUpdated != null }
    val updated = savedUpdated!!
    assertEquals("مراقبة الخادم المحدّثة", updated.title)
    assertEquals("2026-11-27", updated.scheduledDate)
    assertEquals("21:00", updated.startTime)
    assertEquals("07:00", updated.endTime)
  }

  @Test
  fun titleOnlyTask_withoutDateOrTime_createsTaskWithoutFabricatingSchedule() {
    var savedTask: TaskEntity? = null

    composeTestRule.setContent {
      ProvideYawmiLocale(language = AppLanguage.ENGLISH) {
        YawmiTheme(themeMode = AppThemeMode.LIGHT) {
          TaskEditorSheet(
            existingTask = null,
            categories = emptyList(),
            taskUseCases = taskUseCases,
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
    composeTestRule.onNodeWithTag("task_title_input").performScrollTo().performTextInput("Quick idea")
    composeTestRule.onNodeWithTag("task_save_btn").performScrollTo().performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000L) { savedTask != null }
    val created = savedTask!!
    assertEquals("Quick idea", created.title)
    assertNull(created.scheduledDate)
    assertNull(created.startTime)
    assertNull(created.endTime)
    assertEquals(TaskPriority.MEDIUM, created.priority)
    assertEquals(TaskStatus.PENDING, created.status)
  }

  @Test
  fun englishLtrEditor_showsSameDayAndNextDayFormats_andCollapsibleExtrasWithPartialSaveProtection() {
    var savedTask: TaskEntity? = null

    composeTestRule.setContent {
      ProvideYawmiLocale(language = AppLanguage.ENGLISH) {
        YawmiTheme(themeMode = AppThemeMode.LIGHT) {
          TaskEditorSheet(
            existingTask = null,
            categories = emptyList(),
            taskUseCases = taskUseCases,
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

    // Collapsible sections start collapsed
    assertTrue(
      composeTestRule.onAllNodesWithTag("task_recurrence_content").fetchSemanticsNodes().isEmpty()
    )
    assertTrue(
      composeTestRule.onAllNodesWithTag("task_reminders_content").fetchSemanticsNodes().isEmpty()
    )

    // Enable Daily recurrence WITHOUT scheduledDate -> task saves, recurrence fails safely, no duplicate!
    composeTestRule.onNodeWithTag("task_title_input").performScrollTo().performTextInput("Daily sync")
    composeTestRule.onNodeWithTag("task_recurrence_toggle").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_recurrence_freq_daily").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("task_save_btn").performScrollTo().performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000L) {
      composeTestRule.onAllNodesWithTag("task_partial_save_warning").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("task_partial_save_warning").performScrollTo().assertIsDisplayed()
    assertEquals(1, runBlocking { container.taskRepository.observeAllTasks().first().size })

    // Fix by choosing Today, 09:00 -> 17:00, and toggling a 10m reminder, then save again (updates existing, still 1 task!)
    composeTestRule.onNodeWithTag("task_date_today").performScrollTo().performClick()
    composeTestRule.pickTimeInDialog(buttonTag = "task_start_time_btn", hour = "09", minute = "00")
    composeTestRule.pickTimeInDialog(buttonTag = "task_end_time_btn", hour = "17", minute = "00")
    composeTestRule.onNodeWithText("27 Nov 2026, 09:00 → 27 Nov 2026, 17:00").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("8h").performScrollTo().assertIsDisplayed()

    composeTestRule.onNodeWithTag("task_reminders_toggle").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_reminder_preset_10").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("task_save_btn").performScrollTo().performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000L) { savedTask != null }
    val allTasks = runBlocking { container.taskRepository.observeAllTasks().first() }
    assertEquals(1, allTasks.size)
    val finalTask = savedTask!!
    assertTrue(runBlocking { taskUseCases.getTaskRecurrence(finalTask.id) } != null)
    val savedReminders = runBlocking { container.taskReminderUseCases.getTaskReminders(finalTask.id) }
    assertEquals(1, savedReminders.size)
    assertEquals(10, savedReminders.single().offsetMinutes)
  }

  @Test
  fun unscheduledTimesEnteredFirst_nextDaySwitchIsEnabledAutoSelectsTodayAndSaves() {
    var savedTask: TaskEntity? = null

    composeTestRule.setContent {
      ProvideYawmiLocale(language = AppLanguage.ARABIC) {
        YawmiTheme(themeMode = AppThemeMode.DARK) {
          TaskEditorSheet(
            existingTask = null,
            categories = emptyList(),
            taskUseCases = taskUseCases,
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
    composeTestRule.onNodeWithTag("task_title_input").performScrollTo().performTextInput("مناوبة مسائية")
    composeTestRule.pickTimeInDialog(buttonTag = "task_start_time_btn", hour = "21", minute = "00")
    composeTestRule.pickTimeInDialog(buttonTag = "task_end_time_btn", hour = "07", minute = "00")

    // Even when starting from Unscheduled, setting both times enables Next-Day switch
    composeTestRule.onNodeWithTag("task_next_day_switch").performScrollTo().assertIsEnabled()
    composeTestRule.onNodeWithTag("task_next_day_switch").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("task_next_day_switch").assertIsOn()
    composeTestRule.onNodeWithText("27 نوفمبر 2026، 21:00 ← 28 نوفمبر 2026، 07:00").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("10 ساعات").performScrollTo().assertIsDisplayed()

    composeTestRule.onNodeWithTag("task_save_btn").performScrollTo().performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000L) { savedTask != null }
    val created = savedTask!!
    assertEquals("2026-11-27", created.scheduledDate)
    assertEquals("21:00", created.startTime)
    assertEquals("07:00", created.endTime)
    assertTrue(TaskRules.inferSpansNextDay(created.startTime, created.endTime))
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
