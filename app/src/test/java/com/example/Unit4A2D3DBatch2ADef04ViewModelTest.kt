package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
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
import com.example.data.local.entity.RecurrenceFrequency
import com.example.data.local.entity.TaskStatus
import com.example.domain.task.TaskDomainResult
import com.example.domain.task.TaskInput
import com.example.domain.task.TaskRecurrenceInput
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
import java.util.Collections
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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

private class Def04VmTestClock(
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
class Unit4A2D3DBatch2ADef04ViewModelTest {

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

  private fun newContainer(): Triple<YawmiDatabase, DefaultAppContainer, Def04VmTestClock> {
    val db = YawmiDatabase.inMemory(context)
    openDbs += db
    val clock = Def04VmTestClock()
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
  fun def04_viewModel_reopenCompletedRecurringTask_setsReopenedRecurringStateAndEmitsExpectedEvent() {
    Dispatchers.setMain(Dispatchers.Default)
    try {
      runBlocking {
        withTimeout(10_000L) {
          val (_, container, clock) = newContainer()
          val task =
            (container.taskUseCases.createTask(
              TaskInput(title = "Daily workout", scheduledDate = "2026-11-28")
            ) as TaskDomainResult.Success)
              .value
          container.taskUseCases.saveTaskRecurrence(
            taskId = task.id,
            input = TaskRecurrenceInput(frequency = RecurrenceFrequency.DAILY, interval = 1),
          )
          val completion =
            (container.taskUseCases.completeTaskWithNextOccurrence(task.id)
              as TaskDomainResult.Success)
              .value
          assertNotNull(completion.generatedNextTask)

          val vm =
            TaskDetailViewModel(
              taskId = task.id,
              taskRepository = container.taskRepository,
              categoryUseCases = container.categoryUseCases,
              taskUseCases = container.taskUseCases,
              localClock = clock,
              midnightDispatcher = Dispatchers.Default,
              taskReminderUseCases = container.taskReminderUseCases,
            )
          val emittedEvents = Collections.synchronizedList(mutableListOf<TaskDetailUiEvent>())
          val eventJob =
            launch(start = CoroutineStart.UNDISPATCHED) { vm.events.collect { emittedEvents += it } }

          try {
            val beforeReopen =
              vm.uiState.first {
                it is TaskDetailUiState.Loaded && it.task.status == TaskStatus.COMPLETED
              } as TaskDetailUiState.Loaded
            assertTrue(beforeReopen.showReopenRecurrenceWarning)

            val firstEventDeferred =
              async(start = CoroutineStart.UNDISPATCHED) { vm.events.first() }
            vm.reopenTask()
            val firstEvent = firstEventDeferred.await()

            val afterReopen =
              vm.uiState.first {
                it is TaskDetailUiState.Loaded && it.task.status == TaskStatus.PENDING
              } as TaskDetailUiState.Loaded
            assertTrue(afterReopen.showReopenRecurrenceWarning)
            assertNotNull(afterReopen.recurrence)
            assertEquals(
              TaskDetailUiEvent.ActionSucceeded(R.string.task_detail_msg_reopened),
              firstEvent,
            )
            assertEquals(
              listOf(TaskDetailUiEvent.ActionSucceeded(R.string.task_detail_msg_reopened)),
              emittedEvents.toList(),
            )
          } finally {
            eventJob.cancel()
            vm.onForegroundStopped()
          }
        }
      }
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun def04_viewModel_reopenCancelledRecurringTask_doesNotSetReopenedRecurringStateOrHintEvent() {
    Dispatchers.setMain(Dispatchers.Default)
    try {
      runBlocking {
        withTimeout(10_000L) {
          val (_, container, clock) = newContainer()
          val task =
            (container.taskUseCases.createTask(
              TaskInput(title = "Weekly report", scheduledDate = "2026-11-28")
            ) as TaskDomainResult.Success)
              .value
          container.taskUseCases.saveTaskRecurrence(
            taskId = task.id,
            input = TaskRecurrenceInput(frequency = RecurrenceFrequency.WEEKLY, interval = 1),
          )
          container.taskUseCases.cancelTask(task.id)

          val vm =
            TaskDetailViewModel(
              taskId = task.id,
              taskRepository = container.taskRepository,
              categoryUseCases = container.categoryUseCases,
              taskUseCases = container.taskUseCases,
              localClock = clock,
              midnightDispatcher = Dispatchers.Default,
              taskReminderUseCases = container.taskReminderUseCases,
            )
          val emittedEvents = Collections.synchronizedList(mutableListOf<TaskDetailUiEvent>())
          val eventJob =
            launch(start = CoroutineStart.UNDISPATCHED) { vm.events.collect { emittedEvents += it } }

          try {
            val beforeReopen =
              vm.uiState.first {
                it is TaskDetailUiState.Loaded && it.task.status == TaskStatus.CANCELLED
              } as TaskDetailUiState.Loaded
            assertFalse(beforeReopen.showReopenRecurrenceWarning)

            val firstEventDeferred =
              async(start = CoroutineStart.UNDISPATCHED) { vm.events.first() }
            vm.reopenTask()
            val firstEvent = firstEventDeferred.await()

            val afterReopen =
              vm.uiState.first {
                it is TaskDetailUiState.Loaded && it.task.status == TaskStatus.PENDING
              } as TaskDetailUiState.Loaded
            assertFalse(afterReopen.showReopenRecurrenceWarning)
            assertNotNull(afterReopen.recurrence)
            assertEquals(
              TaskDetailUiEvent.ActionSucceeded(R.string.task_detail_msg_reopened),
              firstEvent,
            )
            assertEquals(
              listOf(TaskDetailUiEvent.ActionSucceeded(R.string.task_detail_msg_reopened)),
              emittedEvents.toList(),
            )
            assertTrue(emittedEvents.none { it is TaskDetailUiEvent.RecurringTaskCompleted })
            assertEquals(1, container.taskRepository.observeAllTasks().first().size)
          } finally {
            eventJob.cancel()
            vm.onForegroundStopped()
          }
        }
      }
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun def04_viewModel_reopenTaskWithoutRecurrence_doesNotSetReopenedRecurringState() {
    Dispatchers.setMain(Dispatchers.Default)
    try {
      runBlocking {
        withTimeout(10_000L) {
          val (_, container, clock) = newContainer()
          val completedPlainTask =
            (container.taskUseCases.createTask(
              TaskInput(title = "One-off completed", scheduledDate = "2026-11-28")
            ) as TaskDomainResult.Success)
              .value
          container.taskUseCases.completeTaskWithNextOccurrence(completedPlainTask.id)

          val cancelledPlainTask =
            (container.taskUseCases.createTask(
              TaskInput(title = "One-off cancelled", scheduledDate = "2026-11-28")
            ) as TaskDomainResult.Success)
              .value
          container.taskUseCases.cancelTask(cancelledPlainTask.id)

          val completedVm =
            TaskDetailViewModel(
              taskId = completedPlainTask.id,
              taskRepository = container.taskRepository,
              categoryUseCases = container.categoryUseCases,
              taskUseCases = container.taskUseCases,
              localClock = clock,
              midnightDispatcher = Dispatchers.Default,
              taskReminderUseCases = container.taskReminderUseCases,
            )
          val cancelledVm =
            TaskDetailViewModel(
              taskId = cancelledPlainTask.id,
              taskRepository = container.taskRepository,
              categoryUseCases = container.categoryUseCases,
              taskUseCases = container.taskUseCases,
              localClock = clock,
              midnightDispatcher = Dispatchers.Default,
              taskReminderUseCases = container.taskReminderUseCases,
            )
          val completedEvents = Collections.synchronizedList(mutableListOf<TaskDetailUiEvent>())
          val cancelledEvents = Collections.synchronizedList(mutableListOf<TaskDetailUiEvent>())
          val completedJob =
            launch(start = CoroutineStart.UNDISPATCHED) {
              completedVm.events.collect { completedEvents += it }
            }
          val cancelledJob =
            launch(start = CoroutineStart.UNDISPATCHED) {
              cancelledVm.events.collect { cancelledEvents += it }
            }

          try {
            completedVm.uiState.first {
              it is TaskDetailUiState.Loaded && it.task.status == TaskStatus.COMPLETED
            }
            cancelledVm.uiState.first {
              it is TaskDetailUiState.Loaded && it.task.status == TaskStatus.CANCELLED
            }

            val completedEventDeferred =
              async(start = CoroutineStart.UNDISPATCHED) { completedVm.events.first() }
            completedVm.reopenTask()
            val completedEvent = completedEventDeferred.await()

            val cancelledEventDeferred =
              async(start = CoroutineStart.UNDISPATCHED) { cancelledVm.events.first() }
            cancelledVm.reopenTask()
            val cancelledEvent = cancelledEventDeferred.await()

            val completedLoaded =
              completedVm.uiState.first {
                it is TaskDetailUiState.Loaded && it.task.status == TaskStatus.PENDING
              } as TaskDetailUiState.Loaded
            assertNull(completedLoaded.recurrence)
            assertFalse(completedLoaded.showReopenRecurrenceWarning)
            assertEquals(
              TaskDetailUiEvent.ActionSucceeded(R.string.task_detail_msg_reopened),
              completedEvent,
            )
            assertEquals(
              listOf(TaskDetailUiEvent.ActionSucceeded(R.string.task_detail_msg_reopened)),
              completedEvents.toList(),
            )

            val cancelledLoaded =
              cancelledVm.uiState.first {
                it is TaskDetailUiState.Loaded && it.task.status == TaskStatus.PENDING
              } as TaskDetailUiState.Loaded
            assertNull(cancelledLoaded.recurrence)
            assertFalse(cancelledLoaded.showReopenRecurrenceWarning)
            assertEquals(
              TaskDetailUiEvent.ActionSucceeded(R.string.task_detail_msg_reopened),
              cancelledEvent,
            )
            assertEquals(
              listOf(TaskDetailUiEvent.ActionSucceeded(R.string.task_detail_msg_reopened)),
              cancelledEvents.toList(),
            )
          } finally {
            completedJob.cancel()
            cancelledJob.cancel()
            completedVm.onForegroundStopped()
            cancelledVm.onForegroundStopped()
          }
        }
      }
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun def04_reopenCompletedRecurringTask_showsReopenRecurrenceHint() {
    val (_, container, clock) = newContainer()
    val snackbarMessages = mutableListOf<String>()
    val task =
      runBlocking {
        val created =
          (container.taskUseCases.createTask(
            TaskInput(
              title = "Morning review",
              scheduledDate = "2026-11-28",
              startTime = "09:30",
            )
          ) as TaskDomainResult.Success)
            .value
        container.taskUseCases.saveTaskRecurrence(
          taskId = created.id,
          input = TaskRecurrenceInput(frequency = RecurrenceFrequency.DAILY, interval = 1),
        )
        created
      }

    composeTestRule.setContent {
      ProvideYawmiLocale(language = AppLanguage.ENGLISH) {
        YawmiTheme(themeMode = AppThemeMode.DARK) {
          TaskDetailScreen(
            taskId = task.id,
            taskRepository = container.taskRepository,
            taskUseCases = container.taskUseCases,
            categoryUseCases = container.categoryUseCases,
            localClock = clock,
            onNavigateBack = {},
            onShowSnackbar = { snackbarMessages += it },
            taskReminderUseCases = container.taskReminderUseCases,
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

    composeTestRule.onNodeWithTag("task_detail_complete_btn").performScrollTo().performClick()
    waitUntilWithIdle {
      runBlocking { container.taskRepository.getById(task.id)?.status == TaskStatus.COMPLETED } &&
        composeTestRule
          .onAllNodes(hasTestTag("task_detail_reopen_btn") and isEnabled())
          .fetchSemanticsNodes()
          .isNotEmpty()
    }

    composeTestRule.onNodeWithTag("task_detail_reopen_btn").performScrollTo().performClick()
    waitUntilWithIdle {
      runBlocking { container.taskRepository.getById(task.id)?.status == TaskStatus.PENDING } &&
        composeTestRule
          .onAllNodesWithTag("task_detail_recurrence_reopen_hint")
          .fetchSemanticsNodes()
          .isNotEmpty()
    }

    composeTestRule
      .onNodeWithTag("task_detail_recurrence_reopen_hint")
      .performScrollTo()
      .assertIsDisplayed()
    assertTrue(snackbarMessages.contains("Task reopened."))
    val allTasks = runBlocking { container.taskRepository.observeAllTasks().first() }
    assertEquals(2, allTasks.size)
    assertNotNull(runBlocking { container.taskRepository.getTaskRecurrence(task.id) })
  }

  @Test
  fun def04_reopenCancelledRecurringTask_doesNotShowReopenRecurrenceHint() {
    val (_, container, clock) = newContainer()
    val snackbarMessages = mutableListOf<String>()
    val task =
      runBlocking {
        val created =
          (container.taskUseCases.createTask(
            TaskInput(
              title = "مهمة متكررة ملغاة",
              scheduledDate = "2026-11-28",
              startTime = "10:00",
            )
          ) as TaskDomainResult.Success)
            .value
        container.taskUseCases.saveTaskRecurrence(
          taskId = created.id,
          input = TaskRecurrenceInput(frequency = RecurrenceFrequency.DAILY, interval = 2),
        )
        created
      }

    composeTestRule.setContent {
      ProvideYawmiLocale(language = AppLanguage.ARABIC) {
        YawmiTheme(themeMode = AppThemeMode.LIGHT) {
          TaskDetailScreen(
            taskId = task.id,
            taskRepository = container.taskRepository,
            taskUseCases = container.taskUseCases,
            categoryUseCases = container.categoryUseCases,
            localClock = clock,
            onNavigateBack = {},
            onShowSnackbar = { snackbarMessages += it },
            taskReminderUseCases = container.taskReminderUseCases,
          )
        }
      }
    }

    waitUntilWithIdle {
      composeTestRule
        .onAllNodes(hasTestTag("task_detail_cancel_btn") and isEnabled())
        .fetchSemanticsNodes()
        .isNotEmpty()
    }

    composeTestRule.onNodeWithTag("task_detail_cancel_btn").performScrollTo().performClick()
    waitUntilWithIdle {
      composeTestRule.onAllNodesWithTag("task_detail_cancel_confirm_btn").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("task_detail_cancel_confirm_btn").performClick()
    waitUntilWithIdle {
      runBlocking { container.taskRepository.getById(task.id)?.status == TaskStatus.CANCELLED } &&
        composeTestRule
          .onAllNodes(hasTestTag("task_detail_reopen_btn") and isEnabled())
          .fetchSemanticsNodes()
          .isNotEmpty()
    }

    composeTestRule.onNodeWithTag("task_detail_reopen_btn").performScrollTo().performClick()
    waitUntilWithIdle {
      runBlocking { container.taskRepository.getById(task.id)?.status == TaskStatus.PENDING } &&
        snackbarMessages.contains("تمت إعادة فتح المهمة.")
    }
    composeTestRule.waitForIdle()

    // Recurrence rule preserved, zero next occurrences generated, and no misleading reopen warning shown.
    assertTrue(
      composeTestRule
        .onAllNodesWithTag("task_detail_recurrence_reopen_hint")
        .fetchSemanticsNodes()
        .isEmpty()
    )
    composeTestRule
      .onNodeWithTag("task_detail_recurrence_summary_box")
      .performScrollTo()
      .assertIsDisplayed()
    val allTasks = runBlocking { container.taskRepository.observeAllTasks().first() }
    assertEquals(1, allTasks.size)
    val recurrence = runBlocking { container.taskRepository.getTaskRecurrence(task.id) }
    assertNotNull(recurrence)
    assertEquals(RecurrenceFrequency.DAILY, recurrence!!.frequency)
    assertEquals(2, recurrence.interval)
  }
}
