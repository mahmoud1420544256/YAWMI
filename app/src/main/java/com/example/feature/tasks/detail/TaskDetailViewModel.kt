package com.example.feature.tasks.detail

import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.core.time.LocalClock
import com.example.data.local.entity.CategoryEntity
import com.example.data.local.entity.TaskEntity
import com.example.data.local.entity.TaskRecurrenceEntity
import com.example.data.local.entity.TaskReminderDeliveryEntity
import com.example.data.local.entity.TaskReminderEntity
import com.example.data.local.entity.TaskStatus
import com.example.domain.category.CategoryUseCases
import com.example.domain.repository.TaskRepository
import com.example.domain.task.TaskDomainResult
import com.example.domain.task.TaskQueryRules
import com.example.domain.task.TaskRecurrenceInput
import com.example.domain.task.TaskReminderError
import com.example.domain.task.TaskReminderResult
import com.example.domain.task.TaskReminderUseCases
import com.example.domain.task.TaskRules
import com.example.domain.task.TaskUseCases
import com.example.domain.task.TaskValidationError
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class TaskReminderWithDelivery(
  val reminder: TaskReminderEntity,
  val latestDelivery: TaskReminderDeliveryEntity? = null,
)

sealed interface TaskDetailUiState {
  data object Loading : TaskDetailUiState

  data object NotFound : TaskDetailUiState

  data class Loaded(
    val task: TaskEntity,
    val category: CategoryEntity?,
    val categories: List<CategoryEntity>,
    val isOverdue: Boolean,
    val allowedTransitions: Set<TaskStatus>,
    val canReopen: Boolean,
    val canPostpone: Boolean,
    val todayDate: LocalDate,
    val zoneId: ZoneId,
    val isMutating: Boolean,
    val reminders: List<TaskReminderWithDelivery> = emptyList(),
    val isReminderManagementEnabled: Boolean = false,
    val recurrence: TaskRecurrenceEntity? = null,
    val showReopenRecurrenceWarning: Boolean = false,
  ) : TaskDetailUiState
}

sealed interface TaskDetailUiEvent {
  data class ActionSucceeded(@param:StringRes val messageRes: Int) : TaskDetailUiEvent

  data class PostponeSucceeded(val formattedDate: String) : TaskDetailUiEvent

  data class RecurringTaskCompleted(val nextScheduledDate: String) : TaskDetailUiEvent

  data class ReminderSnoozed(
    val triggerDate: String,
    val triggerTime: String,
  ) : TaskDetailUiEvent

  data class ActionFailed(val error: TaskValidationError) : TaskDetailUiEvent

  data class ReminderActionFailed(val error: TaskReminderError) : TaskDetailUiEvent

  data object Deleted : TaskDetailUiEvent
}

private data class TaskDetailExtras(
  val reminders: List<TaskReminderWithDelivery>,
  val recurrence: TaskRecurrenceEntity?,
  val reopenedRecurringWarning: Boolean,
)

private data class TaskDetailTimeContext(
  val date: LocalDate,
  val zoneId: ZoneId,
)

class TaskDetailViewModel(
  private val taskId: String,
  private val taskRepository: TaskRepository,
  categoryUseCases: CategoryUseCases,
  private val taskUseCases: TaskUseCases,
  private val localClock: LocalClock,
  private val midnightDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
  private val taskReminderUseCases: TaskReminderUseCases? = null,
) : ViewModel() {

  private val mutatingState = MutableStateFlow(false)
  private val reopenedRecurringState = MutableStateFlow(false)
  private val timeContext = MutableStateFlow(readTimeContext())
  private var midnightJob: Job? = null

  private val _activeMidnightTimerCount = AtomicInteger(0)
  @VisibleForTesting
  internal val activeMidnightTimerCount: Int
    get() = _activeMidnightTimerCount.get()

  private val _events = MutableSharedFlow<TaskDetailUiEvent>(extraBufferCapacity = 8)
  val events: SharedFlow<TaskDetailUiEvent> = _events.asSharedFlow()

  @OptIn(ExperimentalCoroutinesApi::class)
  private val remindersFlow: Flow<List<TaskReminderWithDelivery>> =
    if (taskReminderUseCases == null) {
      flowOf(emptyList())
    } else {
      taskReminderUseCases.observeTaskReminders(taskId).flatMapLatest { reminders ->
        if (reminders.isEmpty()) {
          flowOf(emptyList())
        } else {
          combine(
            reminders.map { reminder ->
              taskReminderUseCases.observeTaskDeliveries(reminder.id).map { deliveries ->
                TaskReminderWithDelivery(
                  reminder = reminder,
                  latestDelivery =
                    deliveries.maxWithOrNull(
                      compareBy<TaskReminderDeliveryEntity> { it.scheduledAtEpochMs }
                        .thenBy { it.updatedAtEpochMs }
                    ),
                )
              }
            }
          ) { items -> items.toList() }
        }
      }
    }

  private val extrasFlow: Flow<TaskDetailExtras> =
    combine(
      remindersFlow,
      taskRepository.observeTaskRecurrence(taskId),
      reopenedRecurringState,
    ) { reminders, recurrence, reopenedWarning ->
      TaskDetailExtras(
        reminders = reminders,
        recurrence = recurrence,
        reopenedRecurringWarning = reopenedWarning && recurrence != null,
      )
    }

  val uiState: StateFlow<TaskDetailUiState> =
    combine(
        taskRepository.observeTaskById(taskId),
        categoryUseCases.observeActiveCategories(),
        mutatingState,
        timeContext,
        extrasFlow,
      ) { task, categories, isMutating, currentTime, extras ->
        if (task == null) {
          TaskDetailUiState.NotFound
        } else {
          val today = currentTime.date
          val category = task.categoryId?.let { id -> categories.firstOrNull { it.id == id } }
          TaskDetailUiState.Loaded(
            task = task,
            category = category,
            categories = categories,
            isOverdue = TaskQueryRules.isTaskOverdue(task, today),
            allowedTransitions = TaskRules.allowedNextStatuses(task.status),
            canReopen = TaskRules.canReopen(task.status),
            canPostpone =
              task.status == TaskStatus.PENDING || task.status == TaskStatus.IN_PROGRESS,
            todayDate = today,
            zoneId = currentTime.zoneId,
            isMutating = isMutating,
            reminders = extras.reminders,
            isReminderManagementEnabled = taskReminderUseCases != null,
            recurrence = extras.recurrence,
            showReopenRecurrenceWarning =
              extras.recurrence != null &&
                (task.status == TaskStatus.COMPLETED || extras.reopenedRecurringWarning),
          )
        }
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = TaskDetailUiState.Loading,
      )

  init {
    ensureMidnightTimerScheduled()
  }

  fun onForegroundStarted() {
    ensureMidnightTimerScheduled()
  }

  fun onForegroundResumed() {
    val latest = readTimeContext()
    if (latest != timeContext.value) {
      timeContext.value = latest
      rescheduleMidnightTimer()
    } else {
      ensureMidnightTimerScheduled()
    }
  }

  fun onForegroundStopped() {
    cancelMidnightTimer()
  }

  fun onSystemTimeOrZoneChanged() {
    val latest = readTimeContext()
    if (latest != timeContext.value) {
      timeContext.value = latest
    }
    rescheduleMidnightTimer()
  }

  private fun readTimeContext(): TaskDetailTimeContext =
    TaskDetailTimeContext(
      date = localClock.currentDate(),
      zoneId = localClock.zoneId(),
    )

  private fun ensureMidnightTimerScheduled() {
    if (midnightJob?.isActive == true) return
    rescheduleMidnightTimer()
  }

  private fun rescheduleMidnightTimer() {
    midnightJob?.cancel()
    midnightJob =
      viewModelScope.launch(midnightDispatcher) {
        _activeMidnightTimerCount.incrementAndGet()
        try {
          while (isActive) {
            val delayMs = localClock.millisUntilNextMidnight()
            delay(delayMs)
            val latest = readTimeContext()
            if (latest != timeContext.value) {
              timeContext.value = latest
            }
          }
        } finally {
          _activeMidnightTimerCount.decrementAndGet()
        }
      }
  }

  private fun cancelMidnightTimer() {
    midnightJob?.cancel()
    midnightJob = null
  }

  override fun onCleared() {
    cancelMidnightTimer()
    super.onCleared()
  }

  fun startTask() {
    executeTransition(successMessageRes = R.string.task_detail_msg_started) {
      taskUseCases.startTask(taskId)
    }
  }

  fun completeTask() {
    if (mutatingState.value) return
    viewModelScope.launch {
      mutatingState.value = true
      try {
        when (val result = taskUseCases.completeTaskWithNextOccurrence(taskId)) {
          is TaskDomainResult.Success -> {
            reopenedRecurringState.value = false
            val nextDate = result.value.generatedNextTask?.scheduledDate
            if (nextDate != null) {
              _events.tryEmit(TaskDetailUiEvent.RecurringTaskCompleted(nextDate))
            } else {
              _events.tryEmit(TaskDetailUiEvent.ActionSucceeded(R.string.task_detail_msg_completed))
            }
          }
          is TaskDomainResult.Failure -> {
            _events.tryEmit(TaskDetailUiEvent.ActionFailed(result.errors.first()))
          }
        }
      } finally {
        mutatingState.value = false
      }
    }
  }

  fun cancelTask() {
    executeTransition(successMessageRes = R.string.task_detail_msg_cancelled) {
      taskUseCases.cancelTask(taskId)
    }
  }

  fun reopenTask() {
    if (mutatingState.value) return
    viewModelScope.launch {
      mutatingState.value = true
      try {
        val previousTask = taskRepository.getById(taskId)
        val hadRecurrence = taskRepository.getTaskRecurrence(taskId) != null
        when (val result = taskUseCases.reopenTask(taskId)) {
          is TaskDomainResult.Success -> {
            reopenedRecurringState.value =
              previousTask?.status == TaskStatus.COMPLETED && hadRecurrence
            _events.tryEmit(TaskDetailUiEvent.ActionSucceeded(R.string.task_detail_msg_reopened))
          }
          is TaskDomainResult.Failure -> {
            _events.tryEmit(TaskDetailUiEvent.ActionFailed(result.errors.first()))
          }
        }
      } finally {
        mutatingState.value = false
      }
    }
  }

  fun postponeToDate(targetDate: LocalDate, clearTimeWindow: Boolean = false) {
    if (mutatingState.value) return
    viewModelScope.launch {
      mutatingState.value = true
      try {
        when (
          val result =
            taskUseCases.postponeTask(
              taskId = taskId,
              targetDate = targetDate,
              clearTimeWindow = clearTimeWindow,
            )
        ) {
          is TaskDomainResult.Success -> {
            _events.tryEmit(
              TaskDetailUiEvent.PostponeSucceeded(
                targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
              )
            )
          }
          is TaskDomainResult.Failure -> {
            _events.tryEmit(TaskDetailUiEvent.ActionFailed(result.errors.first()))
          }
        }
      } finally {
        mutatingState.value = false
      }
    }
  }

  fun saveRecurrence(input: TaskRecurrenceInput) {
    if (mutatingState.value) return
    viewModelScope.launch {
      mutatingState.value = true
      try {
        val hadExisting = taskRepository.getTaskRecurrence(taskId) != null
        when (val result = taskUseCases.saveTaskRecurrence(taskId, input)) {
          is TaskDomainResult.Success -> {
            _events.tryEmit(
              TaskDetailUiEvent.ActionSucceeded(
                if (hadExisting) R.string.task_detail_recurrence_msg_updated
                else R.string.task_detail_recurrence_msg_added
              )
            )
          }
          is TaskDomainResult.Failure -> {
            _events.tryEmit(TaskDetailUiEvent.ActionFailed(result.errors.first()))
          }
        }
      } finally {
        mutatingState.value = false
      }
    }
  }

  fun removeRecurrence() {
    if (mutatingState.value) return
    viewModelScope.launch {
      mutatingState.value = true
      try {
        when (val result = taskUseCases.removeTaskRecurrence(taskId)) {
          is TaskDomainResult.Success -> {
            reopenedRecurringState.value = false
            if (result.value) {
              _events.tryEmit(
                TaskDetailUiEvent.ActionSucceeded(R.string.task_detail_recurrence_msg_deleted)
              )
            }
          }
          is TaskDomainResult.Failure -> {
            _events.tryEmit(TaskDetailUiEvent.ActionFailed(result.errors.first()))
          }
        }
      } finally {
        mutatingState.value = false
      }
    }
  }

  fun deleteTask() {
    if (mutatingState.value) return
    viewModelScope.launch {
      mutatingState.value = true
      try {
        val deleted = taskUseCases.deleteTask(taskId)
        if (deleted) {
          _events.tryEmit(TaskDetailUiEvent.Deleted)
        } else {
          _events.tryEmit(
            TaskDetailUiEvent.ActionFailed(TaskValidationError.TaskNotFound(taskId))
          )
        }
      } finally {
        mutatingState.value = false
      }
    }
  }

  fun addRelativeReminder(offsetMinutes: Int) {
    executeReminderAction(successMessageRes = R.string.task_detail_reminder_msg_added) { useCases ->
      useCases.createRelativeReminder(taskId = taskId, offsetMinutes = offsetMinutes)
    }
  }

  fun addExplicitReminder(triggerTime: String, triggerDate: String? = null) {
    executeReminderAction(successMessageRes = R.string.task_detail_reminder_msg_added) { useCases ->
      useCases.createExplicitReminder(
        taskId = taskId,
        triggerTime = triggerTime,
        triggerDate = triggerDate,
      )
    }
  }

  fun setReminderEnabled(reminderId: String, enabled: Boolean) {
    executeReminderAction(
      successMessageRes =
        if (enabled) R.string.task_detail_reminder_msg_enabled
        else R.string.task_detail_reminder_msg_paused
    ) { useCases ->
      useCases.setReminderEnabled(taskId = taskId, reminderId = reminderId, enabled = enabled)
    }
  }

  fun updateReminderOffset(reminderId: String, offsetMinutes: Int) {
    executeReminderAction(successMessageRes = R.string.task_detail_reminder_msg_updated) { useCases ->
      useCases.updateReminderOffset(
        taskId = taskId,
        reminderId = reminderId,
        offsetMinutes = offsetMinutes,
      )
    }
  }

  fun updateExplicitReminderTime(
    reminderId: String,
    triggerTime: String,
    triggerDate: String? = null,
  ) {
    executeReminderAction(successMessageRes = R.string.task_detail_reminder_msg_updated) { useCases ->
      useCases.updateExplicitReminderTime(
        taskId = taskId,
        reminderId = reminderId,
        triggerTime = triggerTime,
        triggerDate = triggerDate,
      )
    }
  }

  fun snoozeReminder(reminderId: String, snoozeMinutes: Int) {
    val useCases = taskReminderUseCases ?: return
    if (mutatingState.value) return
    viewModelScope.launch {
      mutatingState.value = true
      try {
        when (
          val result =
            useCases.snoozeReminder(
              taskId = taskId,
              reminderId = reminderId,
              snoozeMinutes = snoozeMinutes,
            )
        ) {
          is TaskReminderResult.Success -> {
            _events.tryEmit(
              TaskDetailUiEvent.ReminderSnoozed(
                triggerDate = result.value.triggerDate,
                triggerTime = result.value.triggerTime,
              )
            )
          }
          is TaskReminderResult.Failure -> {
            _events.tryEmit(TaskDetailUiEvent.ReminderActionFailed(result.error))
          }
        }
      } finally {
        mutatingState.value = false
      }
    }
  }

  fun deleteReminder(reminderId: String) {
    val useCases = taskReminderUseCases ?: return
    if (mutatingState.value) return
    viewModelScope.launch {
      mutatingState.value = true
      try {
        when (val result = useCases.deleteReminder(reminderId)) {
          is TaskReminderResult.Success -> {
            _events.tryEmit(
              TaskDetailUiEvent.ActionSucceeded(R.string.task_detail_reminder_msg_deleted)
            )
          }
          is TaskReminderResult.Failure -> {
            _events.tryEmit(TaskDetailUiEvent.ReminderActionFailed(result.error))
          }
        }
      } finally {
        mutatingState.value = false
      }
    }
  }

  private fun executeReminderAction(
    @StringRes successMessageRes: Int,
    action: suspend (TaskReminderUseCases) -> TaskReminderResult<TaskReminderEntity>,
  ) {
    val useCases = taskReminderUseCases ?: return
    if (mutatingState.value) return
    viewModelScope.launch {
      mutatingState.value = true
      try {
        when (val result = action(useCases)) {
          is TaskReminderResult.Success ->
            _events.tryEmit(TaskDetailUiEvent.ActionSucceeded(successMessageRes))
          is TaskReminderResult.Failure ->
            _events.tryEmit(TaskDetailUiEvent.ReminderActionFailed(result.error))
        }
      } finally {
        mutatingState.value = false
      }
    }
  }

  private fun executeTransition(
    @StringRes successMessageRes: Int,
    action: suspend () -> TaskDomainResult<TaskEntity>,
  ) {
    if (mutatingState.value) return
    viewModelScope.launch {
      mutatingState.value = true
      try {
        when (val result = action()) {
          is TaskDomainResult.Success ->
            _events.tryEmit(TaskDetailUiEvent.ActionSucceeded(successMessageRes))
          is TaskDomainResult.Failure ->
            _events.tryEmit(TaskDetailUiEvent.ActionFailed(result.errors.first()))
        }
      } finally {
        mutatingState.value = false
      }
    }
  }
}

class TaskDetailViewModelFactory(
  private val taskId: String,
  private val taskRepository: TaskRepository,
  private val categoryUseCases: CategoryUseCases,
  private val taskUseCases: TaskUseCases,
  private val localClock: LocalClock,
  private val taskReminderUseCases: TaskReminderUseCases? = null,
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    require(modelClass.isAssignableFrom(TaskDetailViewModel::class.java)) {
      "Unknown ViewModel class: ${modelClass.name}"
    }
    return TaskDetailViewModel(
      taskId = taskId,
      taskRepository = taskRepository,
      categoryUseCases = categoryUseCases,
      taskUseCases = taskUseCases,
      localClock = localClock,
      taskReminderUseCases = taskReminderUseCases,
    ) as T
  }
}
