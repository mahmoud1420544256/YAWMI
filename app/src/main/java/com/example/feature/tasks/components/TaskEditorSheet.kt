package com.example.feature.tasks.components

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.i18n.ProvideYawmiLocale
import com.example.core.preferences.AppLanguage
import com.example.core.time.DatePickerLocalDateMapper
import com.example.core.time.LocalClock
import com.example.data.local.entity.CategoryEntity
import com.example.data.local.entity.TaskEntity
import com.example.data.local.entity.TaskPriority
import com.example.data.local.entity.TaskRecurrenceEntity
import com.example.data.local.entity.TaskReminderEntity
import com.example.domain.task.TaskDomainResult
import com.example.domain.task.TaskInput
import com.example.domain.task.TaskRecurrenceInput
import com.example.domain.task.TaskReminderResult
import com.example.domain.task.TaskReminderRules
import com.example.domain.task.TaskReminderUseCases
import com.example.domain.task.TaskRules
import com.example.domain.task.TaskUseCases
import com.example.domain.task.TaskValidationError
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal enum class TimePickerTarget {
  START_TIME,
  END_TIME,
  CUSTOM_REMINDER,
}

@Stable
internal class TaskEditorFormState(
  private val taskUseCases: TaskUseCases,
  private val taskReminderUseCases: TaskReminderUseCases?,
  private val localClock: LocalClock,
  private val scope: CoroutineScope,
  onSaved: (TaskEntity) -> Unit,
  onPartialSaveWarning: ((String) -> Unit)?,
  persistedTaskState: MutableState<TaskEntity?>,
  titleState: MutableState<String>,
  descriptionState: MutableState<String>,
  scheduledDateState: MutableState<String>,
  startTimeState: MutableState<String>,
  endTimeState: MutableState<String>,
  spansNextDayState: MutableState<Boolean>,
  priorityState: MutableState<TaskPriority>,
  selectedCategoryIdState: MutableState<String?>,
  notesState: MutableState<String>,
  recurrenceExpandedState: MutableState<Boolean>,
  remindersExpandedState: MutableState<Boolean>,
  initialRecurrenceState: MutableState<TaskRecurrenceEntity?>,
  recurrenceDraftState: MutableState<DraftRecurrenceState>,
  initialRemindersState: MutableState<List<TaskReminderEntity>>,
  reminderDraftsState: MutableState<List<DraftReminderItem>>,
  nextDraftCounterState: MutableIntState,
  titleErrorState: MutableState<String?>,
  dateErrorState: MutableState<String?>,
  startTimeErrorState: MutableState<String?>,
  endTimeErrorState: MutableState<String?>,
  generalErrorState: MutableState<String?>,
  partialSaveWarningState: MutableState<String?>,
  isSavingState: MutableState<Boolean>,
  showStartDatePickerState: MutableState<Boolean>,
  showRecurrenceEndDatePickerState: MutableState<Boolean>,
  activeTimePickerTargetState: MutableState<TimePickerTarget?>,
) {
  var onSavedCallback: (TaskEntity) -> Unit = onSaved
  var onPartialSaveWarningCallback: ((String) -> Unit)? = onPartialSaveWarning

  var persistedTask by persistedTaskState
  val isEditMode: Boolean
    get() = persistedTask != null

  val isTaskActive: Boolean
    get() = persistedTask?.let { TaskReminderRules.isTaskActive(it.status) } ?: true

  var title by titleState
  var description by descriptionState
  var scheduledDate by scheduledDateState
  var startTime by startTimeState
  var endTime by endTimeState
  var spansNextDay by spansNextDayState
  var priority by priorityState
  var selectedCategoryId by selectedCategoryIdState
  var notes by notesState

  var recurrenceExpanded by recurrenceExpandedState
  var remindersExpanded by remindersExpandedState
  var initialRecurrence by initialRecurrenceState
  var recurrenceDraft by recurrenceDraftState
  var initialReminders by initialRemindersState
  var reminderDrafts by reminderDraftsState
  var nextDraftCounter by nextDraftCounterState

  var titleError by titleErrorState
  var dateError by dateErrorState
  var startTimeError by startTimeErrorState
  var endTimeError by endTimeErrorState
  var generalError by generalErrorState
  var partialSaveWarning by partialSaveWarningState
  var isSaving by isSavingState

  var showStartDatePicker by showStartDatePickerState
  var showRecurrenceEndDatePicker by showRecurrenceEndDatePickerState
  var activeTimePickerTarget by activeTimePickerTargetState

  suspend fun loadExistingExtras(taskId: String) {
    val loadedRecurrence = taskUseCases.getTaskRecurrence(taskId)
    initialRecurrence = loadedRecurrence
    if (loadedRecurrence != null) {
      recurrenceDraft =
        DraftRecurrenceState(
          enabled = true,
          frequency = loadedRecurrence.frequency,
          interval = loadedRecurrence.interval,
          daysOfWeekMask = loadedRecurrence.daysOfWeekMask,
          endDate = loadedRecurrence.endDate.orEmpty(),
        )
    }
    if (taskReminderUseCases != null) {
      val loadedReminders = taskReminderUseCases.getTaskReminders(taskId)
      initialReminders = loadedReminders
      reminderDrafts =
        loadedReminders.map { rem ->
          DraftReminderItem(
            localKey = "existing_${rem.id}",
            existingId = rem.id,
            offsetMinutes = if (rem.offsetMinutes > 0) rem.offsetMinutes else null,
            explicitTime = if (rem.offsetMinutes == 0) rem.triggerTime else null,
            isEnabled = rem.isEnabled,
            scheduleState = rem.scheduleState,
            triggerDate = rem.triggerDate,
            triggerTime = rem.triggerTime,
            requestReEnable = false,
          )
        }
    }
  }

  fun updateTitle(value: String) {
    title = value
    titleError = null
    generalError = null
  }

  fun updateDescription(value: String) {
    description = value
  }

  fun selectDate(newDate: String) {
    scheduledDate = newDate
    dateError = null
    generalError = null
    if (newDate.isBlank()) {
      spansNextDay = false
      reminderDrafts = reminderDrafts.map { it.copy(requestReEnable = false) }
    }
  }

  fun confirmStartDateMillis(selectedDateMillis: Long?) {
    selectedDateMillis?.let { millis ->
      scheduledDate =
        DatePickerLocalDateMapper.pickerMillisToLocalDate(millis)
          .format(DateTimeFormatter.ISO_LOCAL_DATE)
      dateError = null
    }
    showStartDatePicker = false
  }

  fun confirmRecurrenceEndDateMillis(selectedDateMillis: Long?) {
    if (!isTaskActive) {
      showRecurrenceEndDatePicker = false
      return
    }
    selectedDateMillis?.let { millis ->
      val picked =
        DatePickerLocalDateMapper.pickerMillisToLocalDate(millis)
          .format(DateTimeFormatter.ISO_LOCAL_DATE)
      recurrenceDraft = recurrenceDraft.copy(endDate = picked)
    }
    showRecurrenceEndDatePicker = false
  }

  fun openStartDatePicker() {
    showStartDatePicker = true
  }

  fun dismissStartDatePicker() {
    showStartDatePicker = false
  }

  fun openRecurrenceEndDatePicker() {
    if (!isTaskActive) return
    showRecurrenceEndDatePicker = true
  }

  fun dismissRecurrenceEndDatePicker() {
    showRecurrenceEndDatePicker = false
  }

  fun openStartTimePicker() {
    activeTimePickerTarget = TimePickerTarget.START_TIME
  }

  fun openEndTimePicker() {
    activeTimePickerTarget = TimePickerTarget.END_TIME
  }

  fun openCustomReminderTimePicker() {
    if (!isTaskActive) return
    activeTimePickerTarget = TimePickerTarget.CUSTOM_REMINDER
  }

  fun dismissTimePicker() {
    activeTimePickerTarget = null
  }

  fun confirmTimePick(target: TimePickerTarget, picked: LocalTime) {
    val formatted = TaskScheduleFormatters.formatTime(picked)
    when (target) {
      TimePickerTarget.START_TIME -> {
        startTime = formatted
        startTimeError = null
        endTimeError = null
        generalError = null
      }
      TimePickerTarget.END_TIME -> {
        endTime = formatted
        endTimeError = null
        generalError = null
      }
      TimePickerTarget.CUSTOM_REMINDER -> {
        if (isTaskActive) {
          nextDraftCounter += 1
          reminderDrafts =
            reminderDrafts +
              DraftReminderItem(
                localKey = "draft_explicit_$nextDraftCounter",
                explicitTime = formatted,
              )
        }
      }
    }
    activeTimePickerTarget = null
  }

  fun clearStartTime() {
    startTime = ""
    startTimeError = null
    endTimeError = null
    spansNextDay = false
    reminderDrafts =
      reminderDrafts.map { item ->
        val needsStartTime = item.explicitTime == null && (item.offsetMinutes ?: 0) > 0
        if (needsStartTime && item.requestReEnable) {
          item.copy(requestReEnable = false)
        } else {
          item
        }
      }
  }

  fun clearEndTime() {
    endTime = ""
    endTimeError = null
    spansNextDay = false
  }

  fun updateSpansNextDay(checked: Boolean) {
    spansNextDay = checked
    if (checked && scheduledDate.isBlank()) {
      scheduledDate = localClock.currentDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
    }
    endTimeError = null
    dateError = null
    generalError = null
  }

  fun updatePriority(value: TaskPriority) {
    priority = value
  }

  fun selectCategory(categoryId: String?) {
    selectedCategoryId = categoryId
  }

  fun toggleRecurrenceExpanded() {
    recurrenceExpanded = !recurrenceExpanded
  }

  fun updateRecurrenceDraft(draft: DraftRecurrenceState) {
    if (!isTaskActive) return
    recurrenceDraft = draft
    partialSaveWarning = null
  }

  fun toggleRemindersExpanded() {
    remindersExpanded = !remindersExpanded
  }

  fun addRelativeOffset(offset: Int) {
    if (!isTaskActive) return
    val existingActiveOrPending =
      reminderDrafts.firstOrNull {
        it.offsetMinutes == offset &&
          it.explicitTime == null &&
          (it.isEnabled || it.requestReEnable)
      }
    if (existingActiveOrPending != null) {
      reminderDrafts =
        if (!existingActiveOrPending.isEnabled) {
          reminderDrafts.map { item ->
            if (item.localKey == existingActiveOrPending.localKey) {
              item.copy(requestReEnable = false)
            } else {
              item
            }
          }
        } else {
          reminderDrafts.filterNot { it.localKey == existingActiveOrPending.localKey }
        }
    } else {
      val existingPaused =
        reminderDrafts.firstOrNull {
          it.offsetMinutes == offset &&
            it.explicitTime == null &&
            !it.isEnabled &&
            !it.requestReEnable
        }
      reminderDrafts =
        if (existingPaused != null) {
          reminderDrafts.map { item ->
            if (item.localKey == existingPaused.localKey) item.copy(requestReEnable = true)
            else item
          }
        } else {
          nextDraftCounter += 1
          reminderDrafts +
            DraftReminderItem(
              localKey = "draft_rel_${offset}_$nextDraftCounter",
              offsetMinutes = offset,
            )
        }
    }
    partialSaveWarning = null
  }

  fun toggleReminderReEnable(key: String) {
    if (!isTaskActive) return
    reminderDrafts =
      reminderDrafts.map { item ->
        if (item.localKey == key && item.existingId != null && !item.isEnabled) {
          item.copy(requestReEnable = !item.requestReEnable)
        } else {
          item
        }
      }
    partialSaveWarning = null
  }

  fun removeReminder(key: String) {
    if (!isTaskActive) return
    reminderDrafts = reminderDrafts.filterNot { it.localKey == key }
    partialSaveWarning = null
  }

  fun updateNotes(value: String) {
    notes = value
  }

  fun handleSave(localizedContext: Context) {
    titleError = null
    dateError = null
    startTimeError = null
    endTimeError = null
    generalError = null
    partialSaveWarning = null
    isSaving = true

    scope.launch {
      try {
        val input =
          TaskInput(
            title = title,
            description = description,
            scheduledDate = scheduledDate,
            startTime = startTime,
            endTime = endTime,
            spansNextDay = spansNextDay,
            priority = priority,
            categoryId = selectedCategoryId,
            notes = notes,
          )
        val targetTask = persistedTask
        val result =
          if (targetTask == null) {
            taskUseCases.createTask(input)
          } else {
            taskUseCases.updateTask(targetTask.id, input)
          }
        when (result) {
          is TaskDomainResult.Failure -> {
            result.errors.forEach { err ->
              val localized = err.toLocalizedMessage(localizedContext)
              when (err) {
                TaskValidationError.EmptyTitle -> titleError = localized
                is TaskValidationError.InvalidScheduledDate,
                TaskValidationError.NextDayRequiresScheduledDate -> dateError = localized
                is TaskValidationError.InvalidStartTime -> startTimeError = localized
                is TaskValidationError.InvalidEndTime,
                TaskValidationError.EndTimeWithoutStartTime,
                TaskValidationError.EndTimeBeforeStartTime,
                TaskValidationError.EndTimeEqualsStartTime,
                TaskValidationError.NextDayRequiresTimeWindow,
                TaskValidationError.NextDayRequiresEndBeforeStart -> endTimeError = localized
                is TaskValidationError.CategoryNotFound,
                is TaskValidationError.TaskNotFound,
                is TaskValidationError.InvalidStatusTransition,
                is TaskValidationError.InvalidRecurrenceInterval,
                is TaskValidationError.InvalidRecurrenceEndDate,
                TaskValidationError.RecurrenceEndDateBeforeScheduledDate,
                TaskValidationError.RecurrenceRequiresScheduledDate,
                TaskValidationError.RecurrenceRequiresDaysMask -> generalError = localized
              }
            }
          }
          is TaskDomainResult.Success -> {
            val savedTask = result.value
            persistedTask = savedTask
            val warnings = mutableListOf<String>()

            if (TaskReminderRules.isTaskActive(savedTask.status)) {
              if (recurrenceDraft.enabled) {
                val recResult =
                  taskUseCases.saveTaskRecurrence(
                    taskId = savedTask.id,
                    input =
                      TaskRecurrenceInput(
                        frequency = recurrenceDraft.frequency,
                        interval = recurrenceDraft.interval,
                        daysOfWeekMask = recurrenceDraft.daysOfWeekMask,
                        endDate = recurrenceDraft.endDate.takeIf { it.isNotBlank() },
                      ),
                  )
                when (recResult) {
                  is TaskDomainResult.Success -> initialRecurrence = recResult.value
                  is TaskDomainResult.Failure -> {
                    val errMsg = recResult.errors.first().toLocalizedMessage(localizedContext)
                    warnings +=
                      localizedContext.getString(R.string.task_partial_save_recurrence, errMsg)
                  }
                }
              } else if (initialRecurrence != null) {
                when (val remRecResult = taskUseCases.removeTaskRecurrence(savedTask.id)) {
                  is TaskDomainResult.Success -> initialRecurrence = null
                  is TaskDomainResult.Failure -> {
                    val errMsg = remRecResult.errors.first().toLocalizedMessage(localizedContext)
                    warnings +=
                      localizedContext.getString(R.string.task_partial_save_recurrence, errMsg)
                  }
                }
              }

              if (taskReminderUseCases != null) {
                val updatedDrafts = reminderDrafts.toMutableList()
                var failedRemindersCount = 0
                var firstReminderFailureMsg: String? = null

                val removedExisting =
                  initialReminders.filter { old ->
                    reminderDrafts.none { it.existingId == old.id }
                  }
                removedExisting.forEach { old ->
                  when (val delResult = taskReminderUseCases.deleteReminder(old.id)) {
                    is TaskReminderResult.Success -> Unit
                    is TaskReminderResult.Failure -> {
                      failedRemindersCount += 1
                      if (firstReminderFailureMsg == null) {
                        firstReminderFailureMsg =
                          delResult.error.toLocalizedMessage(localizedContext)
                      }
                    }
                  }
                }

                updatedDrafts.forEachIndexed { index, draft ->
                  if (draft.existingId == null) {
                    val remResult =
                      if (draft.explicitTime != null) {
                        taskReminderUseCases.createExplicitReminder(
                          taskId = savedTask.id,
                          triggerTime = draft.explicitTime,
                          triggerDate = savedTask.scheduledDate,
                        )
                      } else {
                        taskReminderUseCases.createRelativeReminder(
                          taskId = savedTask.id,
                          offsetMinutes = draft.offsetMinutes ?: 0,
                        )
                      }
                    when (remResult) {
                      is TaskReminderResult.Success -> {
                        val created = remResult.value
                        updatedDrafts[index] =
                          draft.copy(
                            existingId = created.id,
                            isEnabled = created.isEnabled,
                            scheduleState = created.scheduleState,
                            triggerDate = created.triggerDate,
                            triggerTime = created.triggerTime,
                            requestReEnable = false,
                          )
                      }
                      is TaskReminderResult.Failure -> {
                        failedRemindersCount += 1
                        if (firstReminderFailureMsg == null) {
                          firstReminderFailureMsg =
                            remResult.error.toLocalizedMessage(localizedContext)
                        }
                      }
                    }
                  } else if (!draft.isEnabled && draft.requestReEnable) {
                    val enableResult =
                      taskReminderUseCases.setReminderEnabled(
                        taskId = savedTask.id,
                        reminderId = draft.existingId,
                        enabled = true,
                      )
                    when (enableResult) {
                      is TaskReminderResult.Success -> {
                        val enabledRem = enableResult.value
                        updatedDrafts[index] =
                          draft.copy(
                            isEnabled = enabledRem.isEnabled,
                            scheduleState = enabledRem.scheduleState,
                            triggerDate = enabledRem.triggerDate,
                            triggerTime = enabledRem.triggerTime,
                            requestReEnable = false,
                          )
                      }
                      is TaskReminderResult.Failure -> {
                        failedRemindersCount += 1
                        if (firstReminderFailureMsg == null) {
                          firstReminderFailureMsg =
                            enableResult.error.toLocalizedMessage(localizedContext)
                        }
                      }
                    }
                  }
                }

                val reloadedReminders = taskReminderUseCases.getTaskReminders(savedTask.id)
                val reloadedById = reloadedReminders.associateBy { it.id }
                initialReminders = reloadedReminders
                reminderDrafts =
                  updatedDrafts.map { draft ->
                    val latest = draft.existingId?.let { reloadedById[it] }
                    if (latest != null) {
                      draft.copy(
                        isEnabled = latest.isEnabled,
                        scheduleState = latest.scheduleState,
                        triggerDate = latest.triggerDate,
                        triggerTime = latest.triggerTime,
                      )
                    } else {
                      draft
                    }
                  }

                val firstErr = firstReminderFailureMsg
                if (failedRemindersCount > 0 && firstErr != null) {
                  warnings +=
                    localizedContext.getString(
                      R.string.task_partial_save_reminders,
                      failedRemindersCount.toString(),
                      firstErr,
                    )
                }
              }
            }

            if (warnings.isEmpty()) {
              onSavedCallback(savedTask)
            } else {
              val combinedWarning = warnings.joinToString("\n")
              partialSaveWarning = combinedWarning
              onPartialSaveWarningCallback?.invoke(combinedWarning)
            }
          }
        }
      } finally {
        isSaving = false
      }
    }
  }
}

@Composable
internal fun rememberTaskEditorFormState(
  existingTask: TaskEntity?,
  initialScheduledDate: String?,
  taskUseCases: TaskUseCases,
  taskReminderUseCases: TaskReminderUseCases?,
  localClock: LocalClock,
  onSaved: (TaskEntity) -> Unit,
  onPartialSaveWarning: ((String) -> Unit)?,
): TaskEditorFormState {
  val scope = rememberCoroutineScope()
  val taskKey = existingTask?.id

  val persistedTaskState = remember(taskKey) { mutableStateOf(existingTask) }
  val titleState = rememberSaveable(taskKey) { mutableStateOf(existingTask?.title.orEmpty()) }
  val descriptionState =
    rememberSaveable(taskKey) { mutableStateOf(existingTask?.description.orEmpty()) }
  val scheduledDateState =
    rememberSaveable(taskKey, initialScheduledDate) {
      mutableStateOf(existingTask?.scheduledDate ?: initialScheduledDate.orEmpty())
    }
  val startTimeState =
    rememberSaveable(taskKey) { mutableStateOf(existingTask?.startTime.orEmpty()) }
  val endTimeState = rememberSaveable(taskKey) { mutableStateOf(existingTask?.endTime.orEmpty()) }
  val spansNextDayState =
    rememberSaveable(taskKey) {
      mutableStateOf(TaskRules.inferSpansNextDay(existingTask?.startTime, existingTask?.endTime))
    }
  val priorityState =
    rememberSaveable(taskKey) { mutableStateOf(existingTask?.priority ?: TaskPriority.MEDIUM) }
  val selectedCategoryIdState = rememberSaveable(taskKey) { mutableStateOf(existingTask?.categoryId) }
  val notesState = rememberSaveable(taskKey) { mutableStateOf(existingTask?.notes.orEmpty()) }

  val recurrenceExpandedState = rememberSaveable(taskKey) { mutableStateOf(false) }
  val remindersExpandedState = rememberSaveable(taskKey) { mutableStateOf(false) }
  val initialRecurrenceState = remember(taskKey) { mutableStateOf<TaskRecurrenceEntity?>(null) }
  val recurrenceDraftState = remember(taskKey) { mutableStateOf(DraftRecurrenceState()) }
  val initialRemindersState =
    remember(taskKey) { mutableStateOf<List<TaskReminderEntity>>(emptyList()) }
  val reminderDraftsState =
    remember(taskKey) { mutableStateOf<List<DraftReminderItem>>(emptyList()) }
  val nextDraftCounterState = remember(taskKey) { mutableIntStateOf(0) }

  val titleErrorState = remember(taskKey) { mutableStateOf<String?>(null) }
  val dateErrorState = remember(taskKey) { mutableStateOf<String?>(null) }
  val startTimeErrorState = remember(taskKey) { mutableStateOf<String?>(null) }
  val endTimeErrorState = remember(taskKey) { mutableStateOf<String?>(null) }
  val generalErrorState = remember(taskKey) { mutableStateOf<String?>(null) }
  val partialSaveWarningState = remember(taskKey) { mutableStateOf<String?>(null) }
  val isSavingState = remember(taskKey) { mutableStateOf(false) }

  val showStartDatePickerState = remember(taskKey) { mutableStateOf(false) }
  val showRecurrenceEndDatePickerState = remember(taskKey) { mutableStateOf(false) }
  val activeTimePickerTargetState = remember(taskKey) { mutableStateOf<TimePickerTarget?>(null) }

  return remember(taskKey, initialScheduledDate, taskUseCases, taskReminderUseCases, localClock, scope) {
    TaskEditorFormState(
      taskUseCases = taskUseCases,
      taskReminderUseCases = taskReminderUseCases,
      localClock = localClock,
      scope = scope,
      onSaved = onSaved,
      onPartialSaveWarning = onPartialSaveWarning,
      persistedTaskState = persistedTaskState,
      titleState = titleState,
      descriptionState = descriptionState,
      scheduledDateState = scheduledDateState,
      startTimeState = startTimeState,
      endTimeState = endTimeState,
      spansNextDayState = spansNextDayState,
      priorityState = priorityState,
      selectedCategoryIdState = selectedCategoryIdState,
      notesState = notesState,
      recurrenceExpandedState = recurrenceExpandedState,
      remindersExpandedState = remindersExpandedState,
      initialRecurrenceState = initialRecurrenceState,
      recurrenceDraftState = recurrenceDraftState,
      initialRemindersState = initialRemindersState,
      reminderDraftsState = reminderDraftsState,
      nextDraftCounterState = nextDraftCounterState,
      titleErrorState = titleErrorState,
      dateErrorState = dateErrorState,
      startTimeErrorState = startTimeErrorState,
      endTimeErrorState = endTimeErrorState,
      generalErrorState = generalErrorState,
      partialSaveWarningState = partialSaveWarningState,
      isSavingState = isSavingState,
      showStartDatePickerState = showStartDatePickerState,
      showRecurrenceEndDatePickerState = showRecurrenceEndDatePickerState,
      activeTimePickerTargetState = activeTimePickerTargetState,
    )
  }.also { formState ->
    formState.onSavedCallback = onSaved
    formState.onPartialSaveWarningCallback = onPartialSaveWarning
  }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskEditorSheet(
  existingTask: TaskEntity?,
  categories: List<CategoryEntity>,
  taskUseCases: TaskUseCases,
  localClock: LocalClock,
  onManageCategoriesClick: () -> Unit,
  onSaved: (TaskEntity) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  initialScheduledDate: String? = null,
  taskReminderUseCases: TaskReminderUseCases? = null,
  onPartialSaveWarning: ((String) -> Unit)? = null,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val currentLanguage =
    if (LocalLayoutDirection.current == LayoutDirection.Rtl) AppLanguage.ARABIC
    else AppLanguage.ENGLISH
  val formState =
    rememberTaskEditorFormState(
      existingTask = existingTask,
      initialScheduledDate = initialScheduledDate,
      taskUseCases = taskUseCases,
      taskReminderUseCases = taskReminderUseCases,
      localClock = localClock,
      onSaved = onSaved,
      onPartialSaveWarning = onPartialSaveWarning,
    )

  LaunchedEffect(existingTask?.id, taskReminderUseCases) {
    val taskId = existingTask?.id ?: return@LaunchedEffect
    formState.loadExistingExtras(taskId)
  }

  if (formState.showStartDatePicker) {
    val datePickerState =
      rememberDatePickerState(
        initialSelectedDateMillis =
          DatePickerLocalDateMapper.initialPickerMillis(
            existingIsoDate = formState.scheduledDate,
            localClock = localClock,
          )
      )
    DatePickerDialog(
      onDismissRequest = formState::dismissStartDatePicker,
      confirmButton = {
        ProvideYawmiLocale(language = currentLanguage) {
          TextButton(
            onClick = { formState.confirmStartDateMillis(datePickerState.selectedDateMillis) }
          ) {
            Text(stringResource(R.string.action_confirm))
          }
        }
      },
      dismissButton = {
        ProvideYawmiLocale(language = currentLanguage) {
          TextButton(onClick = formState::dismissStartDatePicker) {
            Text(stringResource(R.string.action_cancel))
          }
        }
      },
    ) {
      ProvideYawmiLocale(language = currentLanguage) {
        DatePicker(state = datePickerState)
      }
    }
  }

  if (formState.showRecurrenceEndDatePicker) {
    val datePickerState =
      rememberDatePickerState(
        initialSelectedDateMillis =
          DatePickerLocalDateMapper.initialPickerMillis(
            existingIsoDate = formState.recurrenceDraft.endDate.ifBlank { formState.scheduledDate },
            localClock = localClock,
          )
      )
    DatePickerDialog(
      onDismissRequest = formState::dismissRecurrenceEndDatePicker,
      confirmButton = {
        ProvideYawmiLocale(language = currentLanguage) {
          TextButton(
            onClick = {
              formState.confirmRecurrenceEndDateMillis(datePickerState.selectedDateMillis)
            }
          ) {
            Text(stringResource(R.string.action_confirm))
          }
        }
      },
      dismissButton = {
        ProvideYawmiLocale(language = currentLanguage) {
          TextButton(onClick = formState::dismissRecurrenceEndDatePicker) {
            Text(stringResource(R.string.action_cancel))
          }
        }
      },
    ) {
      ProvideYawmiLocale(language = currentLanguage) {
        DatePicker(state = datePickerState)
      }
    }
  }

  formState.activeTimePickerTarget?.let { target ->
    val dialogTitle =
      when (target) {
        TimePickerTarget.START_TIME -> stringResource(R.string.task_time_picker_start_title)
        TimePickerTarget.END_TIME -> stringResource(R.string.task_time_picker_end_title)
        TimePickerTarget.CUSTOM_REMINDER ->
          stringResource(R.string.task_time_picker_reminder_title)
      }
    val initialTime =
      when (target) {
        TimePickerTarget.START_TIME ->
          TaskRules.parseStrictTime(formState.startTime)
            ?: localClock.currentTime().withSecond(0).withNano(0)
        TimePickerTarget.END_TIME ->
          TaskRules.parseStrictTime(formState.endTime)
            ?: TaskRules.parseStrictTime(formState.startTime)?.plusHours(1)
            ?: LocalTime.of(10, 0)
        TimePickerTarget.CUSTOM_REMINDER ->
          TaskRules.parseStrictTime(formState.startTime)
            ?: localClock.currentTime().withSecond(0).withNano(0)
      }

    YawmiTimePickerDialog(
      title = dialogTitle,
      initialTime = initialTime,
      onConfirm = { picked -> formState.confirmTimePick(target, picked) },
      onDismiss = formState::dismissTimePicker,
    )
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
    modifier = modifier.testTag("task_editor_sheet"),
  ) {
    ProvideYawmiLocale(language = currentLanguage) {
      TaskEditorSheetContent(
        isEditMode = formState.isEditMode,
        isTaskActive = formState.isTaskActive,
        title = formState.title,
        description = formState.description,
        scheduledDate = formState.scheduledDate,
        startTime = formState.startTime,
        endTime = formState.endTime,
        spansNextDay = formState.spansNextDay,
        priority = formState.priority,
        categories = categories,
        selectedCategoryId = formState.selectedCategoryId,
        notes = formState.notes,
        recurrenceExpanded = formState.recurrenceExpanded,
        recurrenceDraft = formState.recurrenceDraft,
        showRemindersSection = taskReminderUseCases != null,
        remindersExpanded = formState.remindersExpanded,
        reminderDrafts = formState.reminderDrafts,
        titleError = formState.titleError,
        dateError = formState.dateError,
        startTimeError = formState.startTimeError,
        endTimeError = formState.endTimeError,
        generalError = formState.generalError,
        partialSaveWarning = formState.partialSaveWarning,
        isSaving = formState.isSaving,
        localClock = localClock,
        onTitleChange = formState::updateTitle,
        onDescriptionChange = formState::updateDescription,
        onSelectDate = formState::selectDate,
        onOpenStartDatePicker = formState::openStartDatePicker,
        onOpenStartTimePicker = formState::openStartTimePicker,
        onOpenEndTimePicker = formState::openEndTimePicker,
        onClearStartTime = formState::clearStartTime,
        onClearEndTime = formState::clearEndTime,
        onSpansNextDayChange = formState::updateSpansNextDay,
        onPriorityChange = formState::updatePriority,
        onManageCategoriesClick = onManageCategoriesClick,
        onSelectCategory = formState::selectCategory,
        onToggleRecurrenceExpanded = formState::toggleRecurrenceExpanded,
        onRecurrenceDraftChange = formState::updateRecurrenceDraft,
        onOpenRecurrenceEndDatePicker = formState::openRecurrenceEndDatePicker,
        onToggleRemindersExpanded = formState::toggleRemindersExpanded,
        onAddRelativeOffset = formState::addRelativeOffset,
        onOpenCustomReminderTimePicker = formState::openCustomReminderTimePicker,
        onToggleReminderReEnable = formState::toggleReminderReEnable,
        onRemoveReminder = formState::removeReminder,
        onNotesChange = formState::updateNotes,
        onDismiss = onDismiss,
        onSaveClick = formState::handleSave,
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskEditorSheetContent(
  isEditMode: Boolean,
  isTaskActive: Boolean,
  title: String,
  description: String,
  scheduledDate: String,
  startTime: String,
  endTime: String,
  spansNextDay: Boolean,
  priority: TaskPriority,
  categories: List<CategoryEntity>,
  selectedCategoryId: String?,
  notes: String,
  recurrenceExpanded: Boolean,
  recurrenceDraft: DraftRecurrenceState,
  showRemindersSection: Boolean,
  remindersExpanded: Boolean,
  reminderDrafts: List<DraftReminderItem>,
  titleError: String?,
  dateError: String?,
  startTimeError: String?,
  endTimeError: String?,
  generalError: String?,
  partialSaveWarning: String?,
  isSaving: Boolean,
  localClock: LocalClock,
  onTitleChange: (String) -> Unit,
  onDescriptionChange: (String) -> Unit,
  onSelectDate: (String) -> Unit,
  onOpenStartDatePicker: () -> Unit,
  onOpenStartTimePicker: () -> Unit,
  onOpenEndTimePicker: () -> Unit,
  onClearStartTime: () -> Unit,
  onClearEndTime: () -> Unit,
  onSpansNextDayChange: (Boolean) -> Unit,
  onPriorityChange: (TaskPriority) -> Unit,
  onManageCategoriesClick: () -> Unit,
  onSelectCategory: (String?) -> Unit,
  onToggleRecurrenceExpanded: () -> Unit,
  onRecurrenceDraftChange: (DraftRecurrenceState) -> Unit,
  onOpenRecurrenceEndDatePicker: () -> Unit,
  onToggleRemindersExpanded: () -> Unit,
  onAddRelativeOffset: (Int) -> Unit,
  onOpenCustomReminderTimePicker: () -> Unit,
  onToggleReminderReEnable: (String) -> Unit,
  onRemoveReminder: (String) -> Unit,
  onNotesChange: (String) -> Unit,
  onDismiss: () -> Unit,
  onSaveClick: (Context) -> Unit,
  modifier: Modifier = Modifier,
) {
  val localizedContext = LocalContext.current

  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      text =
        if (!isEditMode) stringResource(R.string.task_editor_new_title)
        else stringResource(R.string.task_editor_edit_title),
      style = MaterialTheme.typography.titleLarge,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.testTag("task_editor_heading"),
    )

    OutlinedTextField(
      value = title,
      onValueChange = onTitleChange,
      label = { Text(stringResource(R.string.task_field_title_label)) },
      placeholder = { Text(stringResource(R.string.task_field_title_hint)) },
      singleLine = true,
      isError = titleError != null,
      supportingText =
        titleError?.let { msg -> { Text(msg, modifier = Modifier.testTag("task_title_error")) } },
      modifier = Modifier.fillMaxWidth().testTag("task_title_input"),
    )

    OutlinedTextField(
      value = description,
      onValueChange = onDescriptionChange,
      label = { Text(stringResource(R.string.task_field_description_label)) },
      maxLines = 3,
      modifier = Modifier.fillMaxWidth().testTag("task_description_input"),
    )

    TaskEditorScheduleSection(
      scheduledDate = scheduledDate,
      startTime = startTime,
      endTime = endTime,
      spansNextDay = spansNextDay,
      dateError = dateError,
      startTimeError = startTimeError,
      endTimeError = endTimeError,
      localClock = localClock,
      onSelectDate = onSelectDate,
      onOpenStartDatePicker = onOpenStartDatePicker,
      onOpenStartTimePicker = onOpenStartTimePicker,
      onOpenEndTimePicker = onOpenEndTimePicker,
      onClearStartTime = onClearStartTime,
      onClearEndTime = onClearEndTime,
      onSpansNextDayChange = onSpansNextDayChange,
    )

    Text(
      text = stringResource(R.string.task_field_priority_section),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      listOf(TaskPriority.HIGH, TaskPriority.MEDIUM, TaskPriority.LOW).forEach { option ->
        FilterChip(
          selected = priority == option,
          onClick = { onPriorityChange(option) },
          label = { Text(stringResource(option.labelRes())) },
          colors =
            FilterChipDefaults.filterChipColors(
              selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
              selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
          modifier =
            Modifier.weight(1f)
              .minimumInteractiveComponentSize()
              .testTag("task_priority_${option.name.lowercase()}"),
        )
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = stringResource(R.string.task_field_category_section),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      TextButton(
        onClick = onManageCategoriesClick,
        modifier =
          Modifier.minimumInteractiveComponentSize().testTag("task_manage_categories_btn"),
      ) {
        Icon(
          imageVector = Icons.Outlined.Settings,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(stringResource(R.string.category_manage))
      }
    }

    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      FilterChip(
        selected = selectedCategoryId == null,
        onClick = { onSelectCategory(null) },
        label = { Text(stringResource(R.string.category_none)) },
        modifier = Modifier.minimumInteractiveComponentSize().testTag("task_category_none"),
      )
      categories.forEach { cat ->
        val dotColor = CategoryTokens.resolveColor(cat.colorToken)
        FilterChip(
          selected = selectedCategoryId == cat.id,
          onClick = { onSelectCategory(cat.id) },
          leadingIcon = {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(dotColor))
          },
          label = { Text(cat.name) },
          modifier =
            Modifier.minimumInteractiveComponentSize().testTag("task_category_${cat.id}"),
        )
      }
    }

    TaskEditorRecurrenceSection(
      expanded = recurrenceExpanded,
      onToggleExpanded = onToggleRecurrenceExpanded,
      draft = recurrenceDraft,
      onDraftChange = onRecurrenceDraftChange,
      onOpenEndDatePicker = onOpenRecurrenceEndDatePicker,
      hasScheduledDate = scheduledDate.isNotBlank(),
      isTaskActive = isTaskActive,
    )

    if (showRemindersSection) {
      TaskEditorRemindersSection(
        expanded = remindersExpanded,
        onToggleExpanded = onToggleRemindersExpanded,
        reminders = reminderDrafts,
        hasScheduledDate = scheduledDate.isNotBlank(),
        hasStartTime = startTime.isNotBlank(),
        onAddRelativeOffset = onAddRelativeOffset,
        onOpenCustomTimePicker = onOpenCustomReminderTimePicker,
        onRemoveReminder = onRemoveReminder,
        isTaskActive = isTaskActive,
        onToggleReEnableReminder = onToggleReminderReEnable,
      )
    }

    OutlinedTextField(
      value = notes,
      onValueChange = onNotesChange,
      label = { Text(stringResource(R.string.task_field_notes_label)) },
      maxLines = 3,
      modifier = Modifier.fillMaxWidth().testTag("task_notes_input"),
    )

    generalError?.let { msg ->
      Text(
        text = msg,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("task_general_error"),
      )
    }

    partialSaveWarning?.let { warning ->
      Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth().testTag("task_partial_save_warning"),
      ) {
        Text(
          text = warning,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onErrorContainer,
          modifier = Modifier.padding(10.dp),
        )
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      OutlinedButton(
        onClick = onDismiss,
        modifier =
          Modifier.weight(1f).minimumInteractiveComponentSize().testTag("task_cancel_btn"),
      ) {
        Text(
          stringResource(
            if (partialSaveWarning != null) R.string.action_close else R.string.action_cancel
          )
        )
      }
      Button(
        enabled = !isSaving,
        onClick = { onSaveClick(localizedContext) },
        modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("task_save_btn"),
      ) {
        Text(stringResource(R.string.action_save))
      }
    }
    Spacer(modifier = Modifier.height(12.dp))
  }
}
