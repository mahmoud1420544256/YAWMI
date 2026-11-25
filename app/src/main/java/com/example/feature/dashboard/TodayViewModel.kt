package com.example.feature.dashboard

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.core.time.GreetingPeriod
import com.example.core.time.LocalClock
import com.example.data.local.entity.CategoryEntity
import com.example.data.local.entity.TaskEntity
import com.example.data.local.entity.TaskStatus
import com.example.domain.category.CategoryUseCases
import com.example.domain.repository.TaskRepository
import com.example.domain.task.TaskDomainResult
import com.example.domain.task.TaskUseCases
import com.example.domain.task.TaskValidationError
import com.example.domain.task.TodayDashboardMetrics
import com.example.domain.task.TodayDashboardRules
import com.example.domain.task.TodayTaskItem
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TodayUiState(
  val isLoading: Boolean = true,
  val todayDate: LocalDate = LocalDate.now(),
  val greetingPeriod: GreetingPeriod = GreetingPeriod.MORNING,
  val metrics: TodayDashboardMetrics =
    TodayDashboardMetrics(
      totalCount = 0,
      completedCount = 0,
      remainingCount = 0,
      overdueCount = 0,
      completionRatio = 0f,
    ),
  val priorityItems: List<TodayTaskItem> = emptyList(),
  val scheduleItems: List<TodayTaskItem> = emptyList(),
  val unscheduledItems: List<TodayTaskItem> = emptyList(),
  val categories: List<CategoryEntity> = emptyList(),
)

sealed interface TodayUiEvent {
  data class ActionSucceeded(@param:StringRes val messageRes: Int) : TodayUiEvent

  data class ActionFailed(val error: TaskValidationError) : TodayUiEvent
}

class TodayViewModel(
  taskRepository: TaskRepository,
  categoryUseCases: CategoryUseCases,
  private val taskUseCases: TaskUseCases,
  private val localClock: LocalClock,
) : ViewModel() {

  private val _events = MutableSharedFlow<TodayUiEvent>(extraBufferCapacity = 8)
  val events: SharedFlow<TodayUiEvent> = _events.asSharedFlow()

  val uiState: StateFlow<TodayUiState> =
    combine(
        taskRepository.observeAllTasks(),
        categoryUseCases.observeActiveCategories(),
      ) { tasks, categories ->
        val today = localClock.currentDate()
        val snapshot =
          TodayDashboardRules.compute(
            tasks = tasks,
            categories = categories,
            today = today,
            zoneId = localClock.zoneId(),
          )
        TodayUiState(
          isLoading = false,
          todayDate = snapshot.todayDate,
          greetingPeriod = localClock.greetingPeriod(),
          metrics = snapshot.metrics,
          priorityItems = snapshot.priorityItems,
          scheduleItems = snapshot.scheduleItems,
          unscheduledItems = snapshot.unscheduledItems,
          categories = categories,
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue =
          TodayUiState(
            isLoading = true,
            todayDate = localClock.currentDate(),
            greetingPeriod = localClock.greetingPeriod(),
          ),
      )

  fun toggleTaskCompletion(task: TaskEntity) {
    viewModelScope.launch {
      val isTerminal = task.status == TaskStatus.COMPLETED || task.status == TaskStatus.CANCELLED
      val result =
        if (isTerminal) taskUseCases.reopenTask(task.id)
        else taskUseCases.completeTask(task.id)

      when (result) {
        is TaskDomainResult.Success -> {
          val msg =
            if (isTerminal) R.string.today_msg_task_reopened else R.string.today_msg_task_completed
          _events.tryEmit(TodayUiEvent.ActionSucceeded(msg))
        }
        is TaskDomainResult.Failure -> {
          _events.tryEmit(TodayUiEvent.ActionFailed(result.errors.first()))
        }
      }
    }
  }
}

class TodayViewModelFactory(
  private val taskRepository: TaskRepository,
  private val categoryUseCases: CategoryUseCases,
  private val taskUseCases: TaskUseCases,
  private val localClock: LocalClock,
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    require(modelClass.isAssignableFrom(TodayViewModel::class.java)) {
      "Unknown ViewModel class: ${modelClass.name}"
    }
    return TodayViewModel(
      taskRepository = taskRepository,
      categoryUseCases = categoryUseCases,
      taskUseCases = taskUseCases,
      localClock = localClock,
    ) as T
  }
}
