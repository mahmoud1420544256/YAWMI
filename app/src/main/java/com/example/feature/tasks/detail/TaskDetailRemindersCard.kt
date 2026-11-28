package com.example.feature.tasks.detail

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.preferences.AppLanguage
import com.example.data.local.entity.DeliveryState
import com.example.data.local.entity.ReminderScheduleState
import com.example.data.local.entity.TaskEntity
import com.example.data.local.entity.TaskReminderEntity
import com.example.domain.task.TaskReminderRules
import com.example.domain.task.TaskRules
import com.example.feature.tasks.components.TaskScheduleFormatters
import com.example.feature.tasks.components.YawmiTimePickerDialog
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalYawmiSemanticColors
import java.time.LocalTime

private enum class ReminderTimePickerPurpose {
  ADD_EXPLICIT,
  EDIT_EXPLICIT,
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskDetailRemindersCard(
  task: TaskEntity,
  reminders: List<TaskReminderWithDelivery>,
  isMutating: Boolean,
  onAddRelativeReminder: (Int) -> Unit,
  onAddExplicitReminder: (String) -> Unit,
  onSetReminderEnabled: (String, Boolean) -> Unit,
  onUpdateReminderOffset: (String, Int) -> Unit,
  onUpdateExplicitReminderTime: (String, String, String?) -> Unit,
  onSnoozeReminder: (String, Int) -> Unit,
  onDeleteReminder: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val semantic = LocalYawmiSemanticColors.current
  val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
  val language = if (isRtl) AppLanguage.ARABIC else AppLanguage.ENGLISH
  val isTaskActive = TaskReminderRules.isTaskActive(task.status)
  val hasScheduledDate = task.scheduledDate?.let { TaskRules.parseStrictIsoDate(it) } != null
  val hasStartTime = task.startTime?.let { TaskRules.parseStrictTime(it) } != null

  var showAddDialog by remember { mutableStateOf(false) }
  var editingReminder by remember { mutableStateOf<TaskReminderEntity?>(null) }
  var snoozingReminder by remember { mutableStateOf<TaskReminderEntity?>(null) }
  var deletingReminder by remember { mutableStateOf<TaskReminderEntity?>(null) }
  var activeTimePickerPurpose by remember { mutableStateOf<ReminderTimePickerPurpose?>(null) }
  var timePickerTargetReminder by remember { mutableStateOf<TaskReminderEntity?>(null) }

  if (showAddDialog) {
    AddTaskReminderDialog(
      hasScheduledDate = hasScheduledDate,
      hasStartTime = hasStartTime,
      onSelectRelativeOffset = { offset ->
        showAddDialog = false
        onAddRelativeReminder(offset)
      },
      onPickExplicitTime = {
        showAddDialog = false
        timePickerTargetReminder = null
        activeTimePickerPurpose = ReminderTimePickerPurpose.ADD_EXPLICIT
      },
      onDismiss = { showAddDialog = false },
    )
  }

  editingReminder?.let { target ->
    EditTaskReminderDialog(
      reminder = target,
      hasScheduledDate = hasScheduledDate,
      hasStartTime = hasStartTime,
      onSelectRelativeOffset = { offset ->
        editingReminder = null
        onUpdateReminderOffset(target.id, offset)
      },
      onPickExplicitTime = {
        editingReminder = null
        timePickerTargetReminder = target
        activeTimePickerPurpose = ReminderTimePickerPurpose.EDIT_EXPLICIT
      },
      onDismiss = { editingReminder = null },
    )
  }

  snoozingReminder?.let { target ->
    SnoozeReminderDialog(
      onSelectDuration = { minutes ->
        snoozingReminder = null
        onSnoozeReminder(target.id, minutes)
      },
      onDismiss = { snoozingReminder = null },
    )
  }

  deletingReminder?.let { target ->
    AlertDialog(
      onDismissRequest = { deletingReminder = null },
      title = { Text(stringResource(R.string.task_detail_reminder_delete_dialog_title)) },
      text = {
        Text(
          stringResource(
            R.string.task_detail_reminder_delete_dialog_message,
            target.triggerDate,
            target.triggerTime,
          )
        )
      },
      confirmButton = {
        Button(
          onClick = {
            deletingReminder = null
            onDeleteReminder(target.id)
          },
          colors =
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.error,
              contentColor = MaterialTheme.colorScheme.onError,
            ),
          modifier =
            Modifier.minimumInteractiveComponentSize()
              .testTag("task_detail_reminder_delete_confirm_btn"),
        ) {
          Text(stringResource(R.string.action_delete))
        }
      },
      dismissButton = {
        TextButton(
          onClick = { deletingReminder = null },
          modifier =
            Modifier.minimumInteractiveComponentSize()
              .testTag("task_detail_reminder_delete_cancel_btn"),
        ) {
          Text(stringResource(R.string.action_cancel))
        }
      },
      modifier = Modifier.testTag("task_detail_reminder_delete_dialog"),
    )
  }

  activeTimePickerPurpose?.let { purpose ->
    val initialTime =
      when (purpose) {
        ReminderTimePickerPurpose.ADD_EXPLICIT ->
          task.startTime?.let { TaskRules.parseStrictTime(it) } ?: LocalTime.of(9, 0)
        ReminderTimePickerPurpose.EDIT_EXPLICIT ->
          timePickerTargetReminder?.triggerTime?.let { TaskRules.parseStrictTime(it) }
            ?: task.startTime?.let { TaskRules.parseStrictTime(it) }
            ?: LocalTime.of(9, 0)
      }
    YawmiTimePickerDialog(
      title = stringResource(R.string.task_time_picker_reminder_title),
      initialTime = initialTime,
      onConfirm = { pickedTime ->
        val formattedTime = TaskScheduleFormatters.formatTime(pickedTime)
        val target = timePickerTargetReminder
        activeTimePickerPurpose = null
        timePickerTargetReminder = null
        when (purpose) {
          ReminderTimePickerPurpose.ADD_EXPLICIT -> onAddExplicitReminder(formattedTime)
          ReminderTimePickerPurpose.EDIT_EXPLICIT ->
            if (target != null) {
              onUpdateExplicitReminderTime(target.id, formattedTime, target.triggerDate)
            }
        }
      },
      onDismiss = {
        activeTimePickerPurpose = null
        timePickerTargetReminder = null
      },
    )
  }

  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, semantic.borderSubtle),
    shape = MaterialTheme.shapes.large,
    modifier = modifier.fillMaxWidth().testTag("task_detail_reminders_card"),
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
            imageVector =
              if (reminders.any { it.reminder.isEnabled }) Icons.Outlined.NotificationsActive
              else Icons.Outlined.NotificationsOff,
            contentDescription = null,
            tint =
              if (reminders.any { it.reminder.isEnabled }) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
          )
          Text(
            text = stringResource(R.string.task_detail_section_reminders),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }

        OutlinedButton(
          onClick = { showAddDialog = true },
          enabled = isTaskActive && hasScheduledDate && !isMutating,
          modifier =
            Modifier.minimumInteractiveComponentSize().testTag("task_detail_add_reminder_btn"),
        ) {
          Icon(
            imageVector = Icons.Outlined.Add,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(stringResource(R.string.task_detail_reminders_add))
        }
      }

      if (!isTaskActive) {
        ReminderFeedbackCallout(
          message = stringResource(R.string.task_detail_reminders_terminal_hint),
          containerColor = MaterialTheme.colorScheme.surfaceVariant,
          contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
          tag = "task_detail_reminders_terminal_hint",
        )
      } else if (!hasScheduledDate) {
        ReminderFeedbackCallout(
          message = stringResource(R.string.task_reminder_explicit_hint),
          containerColor = MaterialTheme.colorScheme.surfaceVariant,
          contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
          tag = "task_detail_reminders_missing_date_hint",
        )
      }

      val hasSuppressedPermission =
        reminders.any { it.latestDelivery?.state == DeliveryState.SUPPRESSED_PERMISSION }
      val hasSuppressedChannel =
        reminders.any { it.latestDelivery?.state == DeliveryState.SUPPRESSED_CHANNEL }
      val hasPermanentFailure =
        reminders.any {
          it.reminder.scheduleState == ReminderScheduleState.FAILED_PERMANENT ||
            it.latestDelivery?.state == DeliveryState.FAILED_PERMANENT
        }
      val hasInexactAlarm =
        reminders.any { it.reminder.scheduleState == ReminderScheduleState.SCHEDULED_INEXACT }

      if (hasSuppressedPermission) {
        ReminderFeedbackCallout(
          message = stringResource(R.string.task_detail_reminder_diag_suppressed_permission),
          containerColor = MaterialTheme.colorScheme.errorContainer,
          contentColor = MaterialTheme.colorScheme.onErrorContainer,
          tag = "task_detail_reminder_diag_suppressed_permission",
        )
      }
      if (hasSuppressedChannel) {
        ReminderFeedbackCallout(
          message = stringResource(R.string.task_detail_reminder_diag_suppressed_channel),
          containerColor = MaterialTheme.colorScheme.errorContainer,
          contentColor = MaterialTheme.colorScheme.onErrorContainer,
          tag = "task_detail_reminder_diag_suppressed_channel",
        )
      }
      if (hasPermanentFailure) {
        ReminderFeedbackCallout(
          message = stringResource(R.string.task_detail_reminder_diag_failed_permanent),
          containerColor = MaterialTheme.colorScheme.errorContainer,
          contentColor = MaterialTheme.colorScheme.onErrorContainer,
          tag = "task_detail_reminder_diag_failed_permanent",
        )
      }
      if (hasInexactAlarm) {
        ReminderFeedbackCallout(
          message = stringResource(R.string.task_detail_reminder_diag_inexact),
          containerColor = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
          tag = "task_detail_reminder_diag_inexact",
        )
      }

      if (reminders.isEmpty()) {
        Text(
          text = stringResource(R.string.task_detail_reminders_empty),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.testTag("task_detail_reminders_empty"),
        )
      } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          reminders.forEach { item ->
            TaskDetailReminderRow(
              item = item,
              language = language,
              isTaskActive = isTaskActive,
              isMutating = isMutating,
              onSetEnabled = { enabled -> onSetReminderEnabled(item.reminder.id, enabled) },
              onEditClick = { editingReminder = item.reminder },
              onSnoozeClick = { snoozingReminder = item.reminder },
              onDeleteClick = { deletingReminder = item.reminder },
            )
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskDetailReminderRow(
  item: TaskReminderWithDelivery,
  language: AppLanguage,
  isTaskActive: Boolean,
  isMutating: Boolean,
  onSetEnabled: (Boolean) -> Unit,
  onEditClick: () -> Unit,
  onSnoozeClick: () -> Unit,
  onDeleteClick: () -> Unit,
) {
  val semantic = LocalYawmiSemanticColors.current
  val reminder = item.reminder
  val parsedDate = TaskRules.parseStrictIsoDate(reminder.triggerDate)
  val formattedDate =
    parsedDate?.let { TaskScheduleFormatters.formatDate(it, language) } ?: reminder.triggerDate
  val toggleDesc = stringResource(R.string.task_detail_reminder_toggle_desc)

  Surface(
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, semantic.borderSubtle),
    modifier = Modifier.fillMaxWidth().testTag("task_detail_reminder_item_${reminder.id}"),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          Text(
            text =
              stringResource(
                R.string.task_detail_reminder_trigger_at,
                formattedDate,
                reminder.triggerTime,
              ),
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMonoFontFamily),
            color =
              if (reminder.isEnabled && isTaskActive) MaterialTheme.colorScheme.onSurface
              else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("task_detail_reminder_trigger_${reminder.id}"),
          )
          Text(
            text =
              if (reminder.offsetMinutes > 0) {
                stringResource(
                  R.string.task_detail_reminder_offset_minutes_before,
                  reminder.offsetMinutes,
                )
              } else {
                stringResource(R.string.task_detail_reminder_zero_offset_label)
              },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("task_detail_reminder_type_${reminder.id}"),
          )
        }

        Switch(
          checked = reminder.isEnabled,
          onCheckedChange = onSetEnabled,
          enabled = isTaskActive && !isMutating,
          modifier =
            Modifier.minimumInteractiveComponentSize()
              .semantics { contentDescription = toggleDesc }
              .testTag("task_detail_reminder_toggle_${reminder.id}"),
        )
      }

      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        val scheduleColors = resolveScheduleStateColors(reminder.scheduleState)
        ReminderStateBadge(
          text = stringResource(reminder.scheduleState.labelRes()),
          contentColor = scheduleColors.first,
          containerColor = scheduleColors.second,
          modifier = Modifier.testTag("task_detail_reminder_schedule_state_${reminder.id}"),
        )

        item.latestDelivery?.let { delivery ->
          val deliveryColors = resolveDeliveryStateColors(delivery.state)
          ReminderStateBadge(
            text = stringResource(delivery.state.labelRes()),
            contentColor = deliveryColors.first,
            containerColor = deliveryColors.second,
            modifier = Modifier.testTag("task_detail_reminder_delivery_state_${reminder.id}"),
          )
        }
      }

      HorizontalDivider(color = semantic.borderSubtle)

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier.weight(1f),
        ) {
          TextButton(
            onClick = onEditClick,
            enabled = isTaskActive && !isMutating,
            modifier =
              Modifier.minimumInteractiveComponentSize()
                .testTag("task_detail_reminder_edit_${reminder.id}"),
          ) {
            Icon(
              imageVector = Icons.Outlined.Edit,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.action_edit))
          }

          TextButton(
            onClick = onSnoozeClick,
            enabled = isTaskActive && !isMutating,
            modifier =
              Modifier.minimumInteractiveComponentSize()
                .testTag("task_detail_reminder_snooze_${reminder.id}"),
          ) {
            Icon(
              imageVector = Icons.Outlined.Snooze,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.task_detail_reminder_snooze_action))
          }
        }

        IconButton(
          onClick = onDeleteClick,
          enabled = isTaskActive && !isMutating,
          modifier =
            Modifier.minimumInteractiveComponentSize()
              .testTag("task_detail_reminder_delete_${reminder.id}"),
        ) {
          Icon(
            imageVector = Icons.Outlined.Delete,
            contentDescription = stringResource(R.string.task_reminder_remove_desc),
            tint =
              if (isTaskActive && !isMutating) MaterialTheme.colorScheme.error
              else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
          )
        }
      }
    }
  }
}

@Composable
private fun ReminderFeedbackCallout(
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
        imageVector = Icons.Outlined.WarningAmber,
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

@Composable
private fun ReminderStateBadge(
  text: String,
  contentColor: Color,
  containerColor: Color,
  modifier: Modifier = Modifier,
) {
  Surface(
    color = containerColor,
    shape = MaterialTheme.shapes.small,
    modifier = modifier,
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.Medium,
      color = contentColor,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddTaskReminderDialog(
  hasScheduledDate: Boolean,
  hasStartTime: Boolean,
  onSelectRelativeOffset: (Int) -> Unit,
  onPickExplicitTime: () -> Unit,
  onDismiss: () -> Unit,
) {
  val presets =
    listOf(
      0 to R.string.task_reminder_preset_at_time,
      10 to R.string.task_reminder_preset_10m,
      30 to R.string.task_reminder_preset_30m,
      60 to R.string.task_reminder_preset_1h,
    )

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.task_detail_reminder_add_title)) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          text = stringResource(R.string.task_detail_reminder_relative_group),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          presets.forEach { (offset, labelRes) ->
            FilterChip(
              selected = false,
              enabled = hasScheduledDate && hasStartTime,
              onClick = { onSelectRelativeOffset(offset) },
              label = { Text(stringResource(labelRes)) },
              modifier =
                Modifier.minimumInteractiveComponentSize()
                  .testTag("task_detail_reminder_add_preset_$offset"),
            )
          }
        }

        if (!hasStartTime) {
          Text(
            text = stringResource(R.string.task_reminder_relative_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        HorizontalDivider()

        Text(
          text = stringResource(R.string.task_detail_reminder_explicit_group),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedButton(
          onClick = onPickExplicitTime,
          enabled = hasScheduledDate,
          modifier =
            Modifier.fillMaxWidth()
              .minimumInteractiveComponentSize()
              .testTag("task_detail_reminder_add_custom_btn"),
        ) {
          Icon(
            imageVector = Icons.Outlined.Schedule,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(stringResource(R.string.task_detail_reminder_pick_exact_time))
        }
      }
    },
    confirmButton = {},
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier =
          Modifier.minimumInteractiveComponentSize()
            .testTag("task_detail_add_reminder_cancel_btn"),
      ) {
        Text(stringResource(R.string.action_cancel))
      }
    },
    modifier = Modifier.testTag("task_detail_add_reminder_dialog"),
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditTaskReminderDialog(
  reminder: TaskReminderEntity,
  hasScheduledDate: Boolean,
  hasStartTime: Boolean,
  onSelectRelativeOffset: (Int) -> Unit,
  onPickExplicitTime: () -> Unit,
  onDismiss: () -> Unit,
) {
  val presets =
    listOf(
      0 to R.string.task_reminder_preset_at_time,
      10 to R.string.task_reminder_preset_10m,
      30 to R.string.task_reminder_preset_30m,
      60 to R.string.task_reminder_preset_1h,
    )

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.task_detail_reminder_edit_title)) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          text =
            stringResource(
              R.string.task_detail_reminder_edit_current_trigger,
              reminder.triggerDate,
              reminder.triggerTime,
            ),
          style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFontFamily),
          color = MaterialTheme.colorScheme.onSurface,
        )

        Text(
          text = stringResource(R.string.task_detail_reminder_relative_group),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          presets.forEach { (offset, labelRes) ->
            // Schema v1 offsetMinutes == 0 is ambiguous (relative 0m vs explicit), so never infer
            // preset 0 as selected solely from offsetMinutes == 0.
            val isSelected = reminder.offsetMinutes > 0 && reminder.offsetMinutes == offset
            FilterChip(
              selected = isSelected,
              enabled = hasScheduledDate && hasStartTime,
              onClick = { onSelectRelativeOffset(offset) },
              label = { Text(stringResource(labelRes)) },
              modifier =
                Modifier.minimumInteractiveComponentSize()
                  .testTag("task_detail_reminder_edit_preset_$offset"),
            )
          }
        }

        if (!hasStartTime) {
          Text(
            text = stringResource(R.string.task_reminder_relative_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        HorizontalDivider()

        Text(
          text = stringResource(R.string.task_detail_reminder_explicit_group),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedButton(
          onClick = onPickExplicitTime,
          enabled = hasScheduledDate,
          modifier =
            Modifier.fillMaxWidth()
              .minimumInteractiveComponentSize()
              .testTag("task_detail_reminder_edit_custom_btn"),
        ) {
          Icon(
            imageVector = Icons.Outlined.Schedule,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(stringResource(R.string.task_detail_reminder_pick_exact_time))
        }
      }
    },
    confirmButton = {},
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier =
          Modifier.minimumInteractiveComponentSize()
            .testTag("task_detail_edit_reminder_cancel_btn"),
      ) {
        Text(stringResource(R.string.action_cancel))
      }
    },
    modifier = Modifier.testTag("task_detail_edit_reminder_dialog"),
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SnoozeReminderDialog(
  onSelectDuration: (Int) -> Unit,
  onDismiss: () -> Unit,
) {
  val durations =
    listOf(
      5 to R.string.task_detail_reminder_snooze_5m,
      10 to R.string.task_detail_reminder_snooze_10m,
      30 to R.string.task_detail_reminder_snooze_30m,
      60 to R.string.task_detail_reminder_snooze_1h,
    )

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.task_detail_reminder_snooze_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
          text = stringResource(R.string.task_detail_reminder_snooze_desc),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          durations.forEach { (minutes, labelRes) ->
            OutlinedButton(
              onClick = { onSelectDuration(minutes) },
              modifier =
                Modifier.minimumInteractiveComponentSize()
                  .testTag("task_detail_snooze_option_$minutes"),
            ) {
              Text(stringResource(labelRes))
            }
          }
        }
      }
    },
    confirmButton = {},
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier =
          Modifier.minimumInteractiveComponentSize().testTag("task_detail_snooze_cancel_btn"),
      ) {
        Text(stringResource(R.string.action_cancel))
      }
    },
    modifier = Modifier.testTag("task_detail_snooze_dialog"),
  )
}

@Composable
private fun resolveScheduleStateColors(state: ReminderScheduleState): Pair<Color, Color> {
  val semantic = LocalYawmiSemanticColors.current
  return when (state) {
    ReminderScheduleState.SCHEDULED_EXACT ->
      semantic.success to semantic.success.copy(alpha = 0.14f)
    ReminderScheduleState.SCHEDULED_INEXACT ->
      MaterialTheme.colorScheme.secondary to MaterialTheme.colorScheme.secondaryContainer
    ReminderScheduleState.PENDING_SYNC ->
      MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    ReminderScheduleState.PAUSED,
    ReminderScheduleState.CONSUMED,
    ReminderScheduleState.CANCELLED ->
      MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceVariant
    ReminderScheduleState.FAILED_PERMANENT ->
      MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.errorContainer
  }
}

@Composable
private fun resolveDeliveryStateColors(state: DeliveryState): Pair<Color, Color> {
  val semantic = LocalYawmiSemanticColors.current
  return when (state) {
    DeliveryState.POSTED_TO_SYSTEM -> semantic.success to semantic.success.copy(alpha = 0.14f)
    DeliveryState.PENDING ->
      MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    DeliveryState.FAILED_RETRYABLE ->
      MaterialTheme.colorScheme.secondary to MaterialTheme.colorScheme.secondaryContainer
    DeliveryState.STALE,
    DeliveryState.EXPIRED ->
      MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceVariant
    DeliveryState.SUPPRESSED_PERMISSION,
    DeliveryState.SUPPRESSED_CHANNEL,
    DeliveryState.FAILED_PERMANENT ->
      MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.errorContainer
  }
}

private fun ReminderScheduleState.labelRes(): Int =
  when (this) {
    ReminderScheduleState.PENDING_SYNC -> R.string.reminder_schedule_state_pending_sync
    ReminderScheduleState.SCHEDULED_EXACT -> R.string.reminder_schedule_state_exact
    ReminderScheduleState.SCHEDULED_INEXACT -> R.string.reminder_schedule_state_inexact
    ReminderScheduleState.PAUSED -> R.string.reminder_schedule_state_paused
    ReminderScheduleState.CONSUMED -> R.string.reminder_schedule_state_consumed
    ReminderScheduleState.CANCELLED -> R.string.reminder_schedule_state_cancelled
    ReminderScheduleState.FAILED_PERMANENT -> R.string.reminder_schedule_state_failed_permanent
  }

private fun DeliveryState.labelRes(): Int =
  when (this) {
    DeliveryState.PENDING -> R.string.reminder_delivery_state_pending
    DeliveryState.POSTED_TO_SYSTEM -> R.string.reminder_delivery_state_posted
    DeliveryState.SUPPRESSED_PERMISSION -> R.string.reminder_delivery_state_suppressed_permission
    DeliveryState.SUPPRESSED_CHANNEL -> R.string.reminder_delivery_state_suppressed_channel
    DeliveryState.STALE -> R.string.reminder_delivery_state_stale
    DeliveryState.EXPIRED -> R.string.reminder_delivery_state_expired
    DeliveryState.FAILED_RETRYABLE -> R.string.reminder_delivery_state_failed_retryable
    DeliveryState.FAILED_PERMANENT -> R.string.reminder_delivery_state_failed_permanent
  }
