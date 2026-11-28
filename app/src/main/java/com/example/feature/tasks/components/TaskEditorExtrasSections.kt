package com.example.feature.tasks.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Repeat
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.preferences.AppLanguage
import com.example.data.local.entity.RecurrenceFrequency
import com.example.data.local.entity.ReminderScheduleState
import com.example.domain.task.TaskRecurrenceRules
import com.example.domain.task.TaskRules
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalYawmiSemanticColors
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

data class DraftRecurrenceState(
  val enabled: Boolean = false,
  val frequency: RecurrenceFrequency = RecurrenceFrequency.DAILY,
  val interval: Int = 1,
  val daysOfWeekMask: Int = 0,
  val endDate: String = "",
)

data class DraftReminderItem(
  val localKey: String,
  val existingId: String? = null,
  val offsetMinutes: Int? = null,
  val explicitTime: String? = null,
  val isEnabled: Boolean = true,
  val scheduleState: ReminderScheduleState? = null,
  val triggerDate: String? = null,
  val triggerTime: String? = null,
  val requestReEnable: Boolean = false,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskEditorRecurrenceSection(
  expanded: Boolean,
  onToggleExpanded: () -> Unit,
  draft: DraftRecurrenceState,
  onDraftChange: (DraftRecurrenceState) -> Unit,
  onOpenEndDatePicker: () -> Unit,
  hasScheduledDate: Boolean,
  modifier: Modifier = Modifier,
  isTaskActive: Boolean = true,
) {
  val semantic = LocalYawmiSemanticColors.current
  val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
  val language = if (isRtl) AppLanguage.ARABIC else AppLanguage.ENGLISH
  val dayLocale = if (isRtl) Locale.forLanguageTag("ar") else Locale.ENGLISH

  val summaryText =
    if (!draft.enabled) {
      stringResource(R.string.task_recurrence_none)
    } else {
      frequencyLabel(draft.frequency)
    }

  Surface(
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, semantic.borderSubtle),
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable(onClick = onToggleExpanded)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("task_recurrence_toggle"),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = Icons.Outlined.Repeat,
          contentDescription = null,
          tint =
            if (draft.enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
          text = stringResource(R.string.task_recurrence_optional_title),
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.weight(1f),
        )
        Text(
          text = summaryText,
          style = MaterialTheme.typography.labelMedium,
          color =
            if (draft.enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(end = 6.dp).testTag("task_recurrence_summary"),
        )
        Icon(
          imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
          contentDescription =
            stringResource(
              if (expanded) R.string.task_section_collapse_desc
              else R.string.task_section_expand_desc
            ),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      AnimatedVisibility(visible = expanded) {
        Column(
          modifier =
            Modifier.fillMaxWidth()
              .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
              .testTag("task_recurrence_content"),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          HorizontalDivider(color = semantic.borderSubtle)

          if (!isTaskActive) {
            Surface(
              shape = MaterialTheme.shapes.small,
              color = MaterialTheme.colorScheme.surfaceVariant,
              modifier = Modifier.fillMaxWidth().testTag("task_editor_recurrence_terminal_hint"),
            ) {
              Text(
                text = stringResource(R.string.task_editor_recurrence_terminal_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
              )
            }
          }

          FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            FilterChip(
              selected = !draft.enabled,
              enabled = isTaskActive,
              onClick = { onDraftChange(draft.copy(enabled = false)) },
              label = { Text(stringResource(R.string.task_recurrence_none)) },
              modifier =
                Modifier.minimumInteractiveComponentSize().testTag("task_recurrence_freq_none"),
            )
            RecurrenceFrequency.entries.forEach { freq ->
              FilterChip(
                selected = draft.enabled && draft.frequency == freq,
                enabled = isTaskActive,
                onClick = {
                  onDraftChange(
                    draft.copy(
                      enabled = true,
                      frequency = freq,
                    )
                  )
                },
                label = { Text(frequencyLabel(freq)) },
                modifier =
                  Modifier.minimumInteractiveComponentSize()
                    .testTag("task_recurrence_freq_${freq.name.lowercase()}"),
              )
            }
          }

          if (draft.enabled) {
            if (!hasScheduledDate) {
              Text(
                text = stringResource(R.string.task_recurrence_requires_date_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("task_recurrence_date_hint"),
              )
            }

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
                    if (draft.interval > 1) {
                      onDraftChange(draft.copy(interval = draft.interval - 1))
                    }
                  },
                  enabled = isTaskActive && draft.interval > 1,
                  modifier =
                    Modifier.minimumInteractiveComponentSize()
                      .testTag("task_recurrence_interval_dec"),
                ) {
                  Icon(
                    imageVector = Icons.Outlined.Remove,
                    contentDescription = stringResource(R.string.task_recurrence_interval_decrease),
                  )
                }
                Text(
                  text = draft.interval.toString(),
                  style = MaterialTheme.typography.titleMedium,
                  fontFamily = JetBrainsMonoFontFamily,
                  modifier = Modifier.testTag("task_recurrence_interval_value"),
                )
                IconButton(
                  onClick = { onDraftChange(draft.copy(interval = draft.interval + 1)) },
                  enabled = isTaskActive,
                  modifier =
                    Modifier.minimumInteractiveComponentSize()
                      .testTag("task_recurrence_interval_inc"),
                ) {
                  Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = stringResource(R.string.task_recurrence_interval_increase),
                  )
                }
              }
            }

            if (
              draft.frequency == RecurrenceFrequency.CUSTOM_DAYS ||
                draft.frequency == RecurrenceFrequency.WEEKLY
            ) {
              FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                DayOfWeek.entries.forEach { day ->
                  val bit = TaskRecurrenceRules.dayBit(day)
                  val selected = (draft.daysOfWeekMask and bit) != 0
                  FilterChip(
                    selected = selected,
                    enabled = isTaskActive,
                    onClick = {
                      val nextMask = draft.daysOfWeekMask xor bit
                      onDraftChange(draft.copy(daysOfWeekMask = nextMask))
                    },
                    label = { Text(day.getDisplayName(TextStyle.SHORT, dayLocale)) },
                    modifier =
                      Modifier.minimumInteractiveComponentSize()
                        .testTag("task_recurrence_day_${day.name.lowercase()}"),
                  )
                }
              }
            }

            val parsedEndDate = TaskRules.parseStrictIsoDate(draft.endDate)
            Surface(
              shape = MaterialTheme.shapes.small,
              color = MaterialTheme.colorScheme.surfaceContainer,
              border = BorderStroke(1.dp, semantic.borderSubtle),
              modifier =
                Modifier.fillMaxWidth()
                  .minimumInteractiveComponentSize()
                  .clickable(enabled = isTaskActive, onClick = onOpenEndDatePicker)
                  .testTag("task_recurrence_end_date_btn"),
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
                  )
                }
                if (draft.endDate.isNotBlank()) {
                  IconButton(
                    onClick = { onDraftChange(draft.copy(endDate = "")) },
                    enabled = isTaskActive,
                    modifier =
                      Modifier.minimumInteractiveComponentSize()
                        .testTag("task_recurrence_end_date_clear"),
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
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskEditorRemindersSection(
  expanded: Boolean,
  onToggleExpanded: () -> Unit,
  reminders: List<DraftReminderItem>,
  hasScheduledDate: Boolean,
  hasStartTime: Boolean,
  onAddRelativeOffset: (Int) -> Unit,
  onOpenCustomTimePicker: () -> Unit,
  onRemoveReminder: (String) -> Unit,
  modifier: Modifier = Modifier,
  isTaskActive: Boolean = true,
  onToggleReEnableReminder: (String) -> Unit = {},
) {
  val semantic = LocalYawmiSemanticColors.current
  val activeCount = reminders.count { it.isEnabled || it.requestReEnable }
  val pausedCount = reminders.count { it.existingId != null && !it.isEnabled && !it.requestReEnable }
  val summaryText =
    when {
      reminders.isEmpty() -> stringResource(R.string.task_reminders_empty_summary)
      activeCount == 0 && pausedCount > 0 ->
        stringResource(R.string.task_reminders_paused_only_summary, pausedCount.toString())
      pausedCount > 0 ->
        stringResource(
          R.string.task_reminders_mixed_summary,
          activeCount.toString(),
          pausedCount.toString(),
        )
      else -> stringResource(R.string.task_reminders_count_summary, reminders.size.toString())
    }

  Surface(
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, semantic.borderSubtle),
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable(onClick = onToggleExpanded)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("task_reminders_toggle"),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = Icons.Outlined.NotificationsNone,
          contentDescription = null,
          tint =
            if (activeCount > 0) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
          text = stringResource(R.string.task_reminders_optional_title),
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.weight(1f),
        )
        Text(
          text = summaryText,
          style = MaterialTheme.typography.labelMedium,
          color =
            if (activeCount > 0) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(end = 6.dp).testTag("task_reminders_summary"),
        )
        Icon(
          imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
          contentDescription =
            stringResource(
              if (expanded) R.string.task_section_collapse_desc
              else R.string.task_section_expand_desc
            ),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      AnimatedVisibility(visible = expanded) {
        Column(
          modifier =
            Modifier.fillMaxWidth()
              .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
              .testTag("task_reminders_content"),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          HorizontalDivider(color = semantic.borderSubtle)

          if (!isTaskActive) {
            Surface(
              shape = MaterialTheme.shapes.small,
              color = MaterialTheme.colorScheme.surfaceVariant,
              modifier = Modifier.fillMaxWidth().testTag("task_editor_reminders_terminal_hint"),
            ) {
              Text(
                text = stringResource(R.string.task_editor_reminders_terminal_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
              )
            }
          }

          val presets =
            listOf(
              0 to R.string.task_reminder_preset_at_time,
              10 to R.string.task_reminder_preset_10m,
              30 to R.string.task_reminder_preset_30m,
              60 to R.string.task_reminder_preset_1h,
            )

          FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            presets.forEach { (offsetMinutes, labelRes) ->
              val alreadyAdded =
                reminders.any {
                  it.offsetMinutes == offsetMinutes &&
                    it.explicitTime == null &&
                    (it.isEnabled || it.requestReEnable)
                }
              FilterChip(
                selected = alreadyAdded,
                enabled = isTaskActive && hasScheduledDate && hasStartTime,
                onClick = { onAddRelativeOffset(offsetMinutes) },
                label = { Text(stringResource(labelRes)) },
                modifier =
                  Modifier.minimumInteractiveComponentSize()
                    .testTag("task_reminder_preset_$offsetMinutes"),
              )
            }
          }

          if (isTaskActive) {
            if (!hasScheduledDate) {
              Text(
                text = stringResource(R.string.task_reminder_explicit_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            } else if (!hasStartTime) {
              Text(
                text = stringResource(R.string.task_reminder_relative_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          OutlinedButton(
            onClick = onOpenCustomTimePicker,
            enabled = isTaskActive && hasScheduledDate,
            modifier =
              Modifier.fillMaxWidth()
                .minimumInteractiveComponentSize()
                .testTag("task_reminder_add_custom_btn"),
          ) {
            Icon(
              imageVector = Icons.Outlined.Add,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.task_reminder_add_custom_time))
          }

          reminders.forEachIndexed { index, item ->
            val isPausedExisting = item.existingId != null && !item.isEnabled
            val isRelativeNeedingStartTime =
              item.explicitTime == null && (item.offsetMinutes ?: 0) > 0
            val meetsScheduleReqs =
              hasScheduledDate && (!isRelativeNeedingStartTime || hasStartTime)
            val canToggleReEnable = isTaskActive && meetsScheduleReqs

            Surface(
              shape = MaterialTheme.shapes.small,
              color =
                if (isPausedExisting && !item.requestReEnable) {
                  MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                } else {
                  MaterialTheme.colorScheme.surfaceContainer
                },
              border = BorderStroke(1.dp, semantic.borderSubtle),
              modifier = Modifier.fillMaxWidth().testTag("task_reminder_draft_item_$index"),
            ) {
              Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                  ) {
                    Text(
                      text = formatDraftReminderLabel(item),
                      style = MaterialTheme.typography.bodyMedium,
                      fontWeight = FontWeight.Medium,
                      color =
                        if (isPausedExisting && !item.requestReEnable) {
                          MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                          MaterialTheme.colorScheme.onSurface
                        },
                    )
                    if (isPausedExisting) {
                      val badgeText =
                        when {
                          item.requestReEnable ->
                            stringResource(R.string.task_reminder_reenable_pending_badge)
                          item.scheduleState == ReminderScheduleState.CANCELLED ->
                            stringResource(R.string.reminder_schedule_state_cancelled)
                          else -> stringResource(R.string.reminder_schedule_state_paused)
                        }
                      val badgeBg =
                        if (item.requestReEnable) {
                          MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        } else {
                          MaterialTheme.colorScheme.surfaceVariant
                        }
                      val badgeFg =
                        if (item.requestReEnable) {
                          MaterialTheme.colorScheme.primary
                        } else {
                          MaterialTheme.colorScheme.onSurfaceVariant
                        }
                      Surface(
                        color = badgeBg,
                        shape = MaterialTheme.shapes.small,
                        modifier =
                          Modifier.semantics(mergeDescendants = true) {}
                            .testTag("task_reminder_status_badge_$index"),
                      ) {
                        Text(
                          text = badgeText,
                          style = MaterialTheme.typography.labelSmall,
                          fontWeight = FontWeight.Medium,
                          color = badgeFg,
                          modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                      }
                    }
                  }

                  if (isPausedExisting) {
                    TextButton(
                      onClick = { onToggleReEnableReminder(item.localKey) },
                      enabled = canToggleReEnable,
                      modifier =
                        Modifier.minimumInteractiveComponentSize()
                          .testTag("task_reminder_reenable_$index"),
                    ) {
                      Text(
                        text =
                          stringResource(
                            if (item.requestReEnable) R.string.task_reminder_undo_reenable_action
                            else R.string.task_reminder_reenable_action
                          )
                      )
                    }
                  }

                  IconButton(
                    onClick = { onRemoveReminder(item.localKey) },
                    enabled = isTaskActive,
                    modifier =
                      Modifier.minimumInteractiveComponentSize()
                        .testTag("task_reminder_remove_$index"),
                  ) {
                    Icon(
                      imageVector = Icons.Outlined.Close,
                      contentDescription = stringResource(R.string.task_reminder_remove_desc),
                      modifier = Modifier.size(16.dp),
                    )
                  }
                }

                if (isPausedExisting && !item.requestReEnable && isTaskActive && !meetsScheduleReqs) {
                  Text(
                    text =
                      stringResource(
                        if (isRelativeNeedingStartTime) {
                          R.string.task_reminder_paused_restore_schedule_hint
                        } else {
                          R.string.task_reminder_paused_restore_date_hint
                        }
                      ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("task_reminder_paused_hint_$index"),
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun frequencyLabel(frequency: RecurrenceFrequency): String =
  when (frequency) {
    RecurrenceFrequency.DAILY -> stringResource(R.string.task_recurrence_daily)
    RecurrenceFrequency.WEEKLY -> stringResource(R.string.task_recurrence_weekly)
    RecurrenceFrequency.MONTHLY -> stringResource(R.string.task_recurrence_monthly)
    RecurrenceFrequency.YEARLY -> stringResource(R.string.task_recurrence_yearly)
    RecurrenceFrequency.CUSTOM_DAYS -> stringResource(R.string.task_recurrence_custom_days)
  }

@Composable
private fun formatDraftReminderLabel(item: DraftReminderItem): String {
  item.explicitTime?.let { time -> return time }
  return when (item.offsetMinutes) {
    0 -> stringResource(R.string.task_reminder_preset_at_time)
    10 -> stringResource(R.string.task_reminder_preset_10m)
    30 -> stringResource(R.string.task_reminder_preset_30m)
    60 -> stringResource(R.string.task_reminder_preset_1h)
    else -> "${item.offsetMinutes ?: 0}m"
  }
}
