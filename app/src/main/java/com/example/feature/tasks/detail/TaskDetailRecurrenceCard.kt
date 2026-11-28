package com.example.feature.tasks.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.i18n.ProvideYawmiLocale
import com.example.core.preferences.AppLanguage
import com.example.core.time.DatePickerLocalDateMapper
import com.example.core.time.LocalClock
import com.example.data.local.entity.RecurrenceFrequency
import com.example.data.local.entity.TaskEntity
import com.example.data.local.entity.TaskRecurrenceEntity
import com.example.domain.task.TaskDomainResult
import com.example.domain.task.TaskRecurrenceInput
import com.example.domain.task.TaskRecurrenceRules
import com.example.domain.task.TaskReminderRules
import com.example.domain.task.TaskRules
import com.example.feature.tasks.components.TaskScheduleFormatters
import com.example.feature.tasks.components.toLocalizedMessage
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalYawmiSemanticColors
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskDetailRecurrenceCard(
  task: TaskEntity,
  recurrence: TaskRecurrenceEntity?,
  showReopenWarning: Boolean,
  isMutating: Boolean,
  localClock: LocalClock,
  onSaveRecurrence: (TaskRecurrenceInput) -> Unit,
  onRemoveRecurrence: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val semantic = LocalYawmiSemanticColors.current
  val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
  val language = if (isRtl) AppLanguage.ARABIC else AppLanguage.ENGLISH
  val dayLocale = if (isRtl) Locale.forLanguageTag("ar") else Locale.ENGLISH

  val isTaskActive = TaskReminderRules.isTaskActive(task.status)
  val hasScheduledDate = task.scheduledDate?.let { TaskRules.parseStrictIsoDate(it) } != null

  var showEditorDialog by remember { mutableStateOf(false) }
  var showDeleteDialog by remember { mutableStateOf(false) }

  if (showEditorDialog) {
    TaskRecurrenceEditorDialog(
      initialRecurrence = recurrence,
      taskScheduledDate = task.scheduledDate,
      localClock = localClock,
      language = language,
      dayLocale = dayLocale,
      onSave = { input ->
        showEditorDialog = false
        onSaveRecurrence(input)
      },
      onDismiss = { showEditorDialog = false },
    )
  }

  if (showDeleteDialog) {
    AlertDialog(
      onDismissRequest = { showDeleteDialog = false },
      title = { Text(stringResource(R.string.task_detail_recurrence_delete_dialog_title)) },
      text = { Text(stringResource(R.string.task_detail_recurrence_delete_dialog_message)) },
      confirmButton = {
        Button(
          onClick = {
            showDeleteDialog = false
            onRemoveRecurrence()
          },
          colors =
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.error,
              contentColor = MaterialTheme.colorScheme.onError,
            ),
          modifier =
            Modifier.minimumInteractiveComponentSize()
              .testTag("task_detail_recurrence_delete_confirm_btn"),
        ) {
          Text(stringResource(R.string.action_delete))
        }
      },
      dismissButton = {
        TextButton(
          onClick = { showDeleteDialog = false },
          modifier =
            Modifier.minimumInteractiveComponentSize()
              .testTag("task_detail_recurrence_delete_cancel_btn"),
        ) {
          Text(stringResource(R.string.action_cancel))
        }
      },
      modifier = Modifier.testTag("task_detail_recurrence_delete_dialog"),
    )
  }

  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, semantic.borderSubtle),
    shape = MaterialTheme.shapes.large,
    modifier = modifier.fillMaxWidth().testTag("task_detail_recurrence_card"),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.weight(1f),
        ) {
          Icon(
            imageVector = Icons.Outlined.Repeat,
            contentDescription = null,
            tint =
              if (recurrence != null) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
          )
          Text(
            text = stringResource(R.string.task_detail_section_recurrence),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }

        if (recurrence == null) {
          OutlinedButton(
            onClick = { showEditorDialog = true },
            enabled = isTaskActive && hasScheduledDate && !isMutating,
            modifier =
              Modifier.minimumInteractiveComponentSize()
                .testTag("task_detail_add_recurrence_btn"),
          ) {
            Icon(
              imageVector = Icons.Outlined.Add,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.task_detail_recurrence_add))
          }
        }
      }

      if (!isTaskActive) {
        RecurrenceFeedbackCallout(
          icon = Icons.Outlined.WarningAmber,
          message = stringResource(R.string.task_detail_recurrence_terminal_hint),
          containerColor = MaterialTheme.colorScheme.surfaceVariant,
          contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
          tag = "task_detail_recurrence_terminal_hint",
        )
      } else if (!hasScheduledDate && recurrence == null) {
        RecurrenceFeedbackCallout(
          icon = Icons.Outlined.Info,
          message = stringResource(R.string.task_detail_recurrence_requires_date_hint),
          containerColor = MaterialTheme.colorScheme.surfaceVariant,
          contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
          tag = "task_detail_recurrence_missing_date_hint",
        )
      }

      if (showReopenWarning && recurrence != null) {
        RecurrenceFeedbackCallout(
          icon = Icons.Outlined.Info,
          message = stringResource(R.string.task_detail_recurrence_reopen_hint),
          containerColor = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
          tag = "task_detail_recurrence_reopen_hint",
        )
      }

      if (recurrence == null) {
        Text(
          text = stringResource(R.string.task_detail_recurrence_empty),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.testTag("task_detail_recurrence_empty"),
        )
      } else {
        val currentRuleValidation =
          TaskRecurrenceRules.validateInput(
            input =
              TaskRecurrenceInput(
                frequency = recurrence.frequency,
                interval = recurrence.interval,
                daysOfWeekMask = recurrence.daysOfWeekMask,
                endDate = recurrence.endDate,
              ),
            taskScheduledDate = task.scheduledDate,
          )
        if (isTaskActive && currentRuleValidation is TaskDomainResult.Failure) {
          RecurrenceFeedbackCallout(
            icon = Icons.Outlined.WarningAmber,
            message = stringResource(R.string.task_detail_recurrence_invalid_schedule_hint),
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            tag = "task_detail_recurrence_invalid_schedule_hint",
          )
        }

        val selectedDays = remember(recurrence.daysOfWeekMask) {
          TaskRecurrenceRules.daysFromMask(recurrence.daysOfWeekMask)
        }
        val parsedEndDate = remember(recurrence.endDate) {
          recurrence.endDate?.let { TaskRules.parseStrictIsoDate(it) }
        }

        Surface(
          shape = MaterialTheme.shapes.medium,
          color = MaterialTheme.colorScheme.surfaceContainerLow,
          border = BorderStroke(1.dp, semantic.borderSubtle),
          modifier = Modifier.fillMaxWidth().testTag("task_detail_recurrence_summary_box"),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag("task_detail_recurrence_frequency_badge"),
              ) {
                Text(
                  text = recurrenceFrequencyLabel(recurrence.frequency),
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = FontWeight.Medium,
                  color = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
              }

              Text(
                text = formatCadenceSummary(recurrence.frequency, recurrence.interval),
                style = MaterialTheme.typography.titleSmall,
                color =
                  if (isTaskActive) MaterialTheme.colorScheme.onSurface
                  else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).testTag("task_detail_recurrence_cadence_text"),
              )
            }

            if (
              recurrence.frequency == RecurrenceFrequency.CUSTOM_DAYS ||
                recurrence.frequency == RecurrenceFrequency.WEEKLY
            ) {
              val daysText =
                if (selectedDays.isNotEmpty()) {
                  val formattedDays =
                    selectedDays.joinToString(", ") { day ->
                      day.getDisplayName(TextStyle.SHORT, dayLocale)
                    }
                  stringResource(R.string.task_detail_recurrence_days_summary, formattedDays)
                } else {
                  stringResource(R.string.task_detail_recurrence_same_weekday_summary)
                }
              Text(
                text = daysText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("task_detail_recurrence_days_text"),
              )
            }

            Row(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                imageVector = Icons.Outlined.Event,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
              )
              Text(
                text =
                  if (parsedEndDate != null) {
                    stringResource(
                      R.string.task_detail_recurrence_end_date_value,
                      TaskScheduleFormatters.formatDate(parsedEndDate, language),
                    )
                  } else {
                    stringResource(R.string.task_recurrence_end_date_none)
                  },
                style =
                  if (parsedEndDate != null) {
                    MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily)
                  } else {
                    MaterialTheme.typography.bodySmall
                  },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("task_detail_recurrence_end_date_text"),
              )
            }

            HorizontalDivider(color = semantic.borderSubtle)

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              TextButton(
                onClick = { showEditorDialog = true },
                enabled = isTaskActive && !isMutating,
                modifier =
                  Modifier.minimumInteractiveComponentSize()
                    .testTag("task_detail_recurrence_edit_btn"),
              ) {
                Icon(
                  imageVector = Icons.Outlined.Edit,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.action_edit))
              }

              IconButton(
                onClick = { showDeleteDialog = true },
                enabled = isTaskActive && !isMutating,
                modifier =
                  Modifier.minimumInteractiveComponentSize()
                    .testTag("task_detail_recurrence_delete_btn"),
              ) {
                Icon(
                  imageVector = Icons.Outlined.Delete,
                  contentDescription = stringResource(R.string.task_detail_recurrence_delete_desc),
                  tint =
                    if (isTaskActive && !isMutating) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun RecurrenceFeedbackCallout(
  icon: ImageVector,
  message: String,
  containerColor: Color,
  contentColor: Color,
  tag: String,
) {
  Surface(
    color = containerColor,
    shape = MaterialTheme.shapes.medium,
    modifier = Modifier.fillMaxWidth().testTag(tag),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.Top,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = contentColor,
        modifier = Modifier.padding(top = 2.dp).size(18.dp),
      )
      Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = contentColor,
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TaskRecurrenceEditorDialog(
  initialRecurrence: TaskRecurrenceEntity?,
  taskScheduledDate: String?,
  localClock: LocalClock,
  language: AppLanguage,
  dayLocale: Locale,
  onSave: (TaskRecurrenceInput) -> Unit,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val semantic = LocalYawmiSemanticColors.current
  val isEditMode = initialRecurrence != null

  var frequency by remember(initialRecurrence) {
    mutableStateOf(initialRecurrence?.frequency ?: RecurrenceFrequency.DAILY)
  }
  var interval by remember(initialRecurrence) {
    mutableIntStateOf((initialRecurrence?.interval ?: 1).coerceAtLeast(1))
  }
  var daysOfWeekMask by remember(initialRecurrence) {
    mutableIntStateOf(initialRecurrence?.daysOfWeekMask ?: 0)
  }
  var endDate by remember(initialRecurrence) {
    mutableStateOf(initialRecurrence?.endDate.orEmpty())
  }
  var validationError by remember { mutableStateOf<String?>(null) }
  var showEndDatePicker by remember { mutableStateOf(false) }

  if (showEndDatePicker) {
    val datePickerState =
      rememberDatePickerState(
        initialSelectedDateMillis =
          DatePickerLocalDateMapper.initialPickerMillis(
            existingIsoDate = endDate.ifBlank { taskScheduledDate },
            localClock = localClock,
          )
      )
    DatePickerDialog(
      onDismissRequest = { showEndDatePicker = false },
      confirmButton = {
        ProvideYawmiLocale(language = language) {
          TextButton(
            onClick = {
              datePickerState.selectedDateMillis?.let { millis ->
                endDate =
                  DatePickerLocalDateMapper.pickerMillisToLocalDate(millis)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE)
                validationError = null
              }
              showEndDatePicker = false
            },
            modifier =
              Modifier.minimumInteractiveComponentSize()
                .testTag("task_detail_recurrence_end_date_confirm_btn"),
          ) {
            Text(stringResource(R.string.action_confirm))
          }
        }
      },
      dismissButton = {
        ProvideYawmiLocale(language = language) {
          TextButton(
            onClick = { showEndDatePicker = false },
            modifier =
              Modifier.minimumInteractiveComponentSize()
                .testTag("task_detail_recurrence_end_date_cancel_btn"),
          ) {
            Text(stringResource(R.string.action_cancel))
          }
        }
      },
    ) {
      ProvideYawmiLocale(language = language) {
        DatePicker(state = datePickerState)
      }
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text =
          stringResource(
            if (isEditMode) R.string.task_detail_recurrence_edit_title
            else R.string.task_detail_recurrence_add_title
          )
      )
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          text = stringResource(R.string.task_detail_recurrence_frequency_group),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FlowRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          RecurrenceFrequency.entries.forEach { freq ->
            FilterChip(
              selected = frequency == freq,
              onClick = {
                frequency = freq
                validationError = null
              },
              label = { Text(recurrenceFrequencyLabel(freq)) },
              modifier =
                Modifier.minimumInteractiveComponentSize()
                  .testTag("task_detail_recurrence_freq_${freq.name.lowercase()}"),
            )
          }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = stringResource(R.string.task_recurrence_interval_label),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              IconButton(
                onClick = {
                  if (interval > 1) {
                    interval -= 1
                    validationError = null
                  }
                },
                enabled = interval > 1,
                modifier =
                  Modifier.minimumInteractiveComponentSize()
                    .testTag("task_detail_recurrence_interval_dec"),
              ) {
                Icon(
                  imageVector = Icons.Outlined.Remove,
                  contentDescription = stringResource(R.string.task_recurrence_interval_decrease),
                )
              }
              Text(
                text = interval.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = JetBrainsMonoFontFamily,
                modifier = Modifier.testTag("task_detail_recurrence_interval_value"),
              )
              IconButton(
                onClick = {
                  interval += 1
                  validationError = null
                },
                modifier =
                  Modifier.minimumInteractiveComponentSize()
                    .testTag("task_detail_recurrence_interval_inc"),
              ) {
                Icon(
                  imageVector = Icons.Outlined.Add,
                  contentDescription = stringResource(R.string.task_recurrence_interval_increase),
                )
              }
            }
          }

          Text(
            text = formatCadenceSummary(frequency, interval),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("task_detail_recurrence_interval_unit"),
          )
        }

        if (
          frequency == RecurrenceFrequency.CUSTOM_DAYS ||
            frequency == RecurrenceFrequency.WEEKLY
        ) {
          Text(
            text = stringResource(R.string.task_detail_recurrence_days_group),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            DayOfWeek.entries.forEach { day ->
              val bit = TaskRecurrenceRules.dayBit(day)
              val selected = (daysOfWeekMask and bit) != 0
              FilterChip(
                selected = selected,
                onClick = {
                  daysOfWeekMask = daysOfWeekMask xor bit
                  validationError = null
                },
                label = { Text(day.getDisplayName(TextStyle.SHORT, dayLocale)) },
                modifier =
                  Modifier.minimumInteractiveComponentSize()
                    .testTag("task_detail_recurrence_day_${day.name.lowercase()}"),
              )
            }
          }

          Text(
            text =
              if (frequency == RecurrenceFrequency.CUSTOM_DAYS) {
                stringResource(R.string.task_detail_recurrence_custom_days_hint, interval)
              } else {
                stringResource(R.string.task_detail_recurrence_weekly_days_hint)
              },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("task_detail_recurrence_days_hint"),
          )
        }

        val parsedEndDate = TaskRules.parseStrictIsoDate(endDate)
        Surface(
          shape = MaterialTheme.shapes.small,
          color = MaterialTheme.colorScheme.surfaceContainer,
          border = BorderStroke(1.dp, semantic.borderSubtle),
          modifier =
            Modifier.fillMaxWidth()
              .minimumInteractiveComponentSize()
              .clickable { showEndDatePicker = true }
              .testTag("task_detail_recurrence_end_date_btn"),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Outlined.Event,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = stringResource(R.string.task_recurrence_end_date_optional),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                text =
                  parsedEndDate?.let { TaskScheduleFormatters.formatDate(it, language) }
                    ?: stringResource(R.string.task_recurrence_end_date_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("task_detail_recurrence_editor_end_date_value"),
              )
            }
            if (endDate.isNotBlank()) {
              IconButton(
                onClick = {
                  endDate = ""
                  validationError = null
                },
                modifier =
                  Modifier.minimumInteractiveComponentSize()
                    .testTag("task_detail_recurrence_end_date_clear"),
              ) {
                Icon(
                  imageVector = Icons.Outlined.Close,
                  contentDescription = stringResource(R.string.action_clear),
                  modifier = Modifier.size(16.dp),
                )
              }
            }
          }
        }

        validationError?.let { message ->
          Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("task_detail_recurrence_editor_error"),
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val rawInput =
            TaskRecurrenceInput(
              frequency = frequency,
              interval = interval,
              daysOfWeekMask =
                if (
                  frequency == RecurrenceFrequency.WEEKLY ||
                    frequency == RecurrenceFrequency.CUSTOM_DAYS
                ) {
                  daysOfWeekMask
                } else {
                  0
                },
              endDate = endDate.trim().takeIf { it.isNotEmpty() },
            )
          when (val result = TaskRecurrenceRules.validateInput(rawInput, taskScheduledDate)) {
            is TaskDomainResult.Success -> onSave(result.value)
            is TaskDomainResult.Failure -> {
              validationError = result.errors.first().toLocalizedMessage(context)
            }
          }
        },
        modifier =
          Modifier.minimumInteractiveComponentSize().testTag("task_detail_recurrence_save_btn"),
      ) {
        Text(stringResource(R.string.action_save))
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier =
          Modifier.minimumInteractiveComponentSize().testTag("task_detail_recurrence_cancel_btn"),
      ) {
        Text(stringResource(R.string.action_cancel))
      }
    },
    modifier = Modifier.testTag("task_detail_recurrence_editor_dialog"),
  )
}

@Composable
private fun recurrenceFrequencyLabel(frequency: RecurrenceFrequency): String =
  when (frequency) {
    RecurrenceFrequency.DAILY -> stringResource(R.string.task_recurrence_daily)
    RecurrenceFrequency.WEEKLY -> stringResource(R.string.task_recurrence_weekly)
    RecurrenceFrequency.MONTHLY -> stringResource(R.string.task_recurrence_monthly)
    RecurrenceFrequency.YEARLY -> stringResource(R.string.task_recurrence_yearly)
    RecurrenceFrequency.CUSTOM_DAYS -> stringResource(R.string.task_recurrence_custom_days)
  }

@Composable
private fun formatCadenceSummary(frequency: RecurrenceFrequency, interval: Int): String =
  when (frequency) {
    RecurrenceFrequency.DAILY ->
      if (interval == 1) stringResource(R.string.task_detail_recurrence_every_1_day)
      else stringResource(R.string.task_detail_recurrence_every_n_days, interval)
    RecurrenceFrequency.WEEKLY,
    RecurrenceFrequency.CUSTOM_DAYS ->
      if (interval == 1) stringResource(R.string.task_detail_recurrence_every_1_week)
      else stringResource(R.string.task_detail_recurrence_every_n_weeks, interval)
    RecurrenceFrequency.MONTHLY ->
      if (interval == 1) stringResource(R.string.task_detail_recurrence_every_1_month)
      else stringResource(R.string.task_detail_recurrence_every_n_months, interval)
    RecurrenceFrequency.YEARLY ->
      if (interval == 1) stringResource(R.string.task_detail_recurrence_every_1_year)
      else stringResource(R.string.task_detail_recurrence_every_n_years, interval)
  }
