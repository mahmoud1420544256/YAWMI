package com.example.feature.tasks.components

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
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.preferences.AppLanguage
import com.example.core.time.LocalClock
import com.example.domain.task.TaskRules
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalYawmiSemanticColors
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskEditorScheduleSection(
  scheduledDate: String,
  startTime: String,
  endTime: String,
  spansNextDay: Boolean,
  dateError: String?,
  startTimeError: String?,
  endTimeError: String?,
  localClock: LocalClock,
  onSelectDate: (String) -> Unit,
  onOpenStartDatePicker: () -> Unit,
  onOpenStartTimePicker: () -> Unit,
  onOpenEndTimePicker: () -> Unit,
  onClearStartTime: () -> Unit,
  onClearEndTime: () -> Unit,
  onSpansNextDayChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val semantic = LocalYawmiSemanticColors.current
  val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
  val language = if (isRtl) AppLanguage.ARABIC else AppLanguage.ENGLISH

  val todayStr =
    remember(localClock) { localClock.currentDate().format(DateTimeFormatter.ISO_LOCAL_DATE) }
  val tomorrowStr =
    remember(localClock) {
      localClock.currentDate().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
    }

  val parsedStartDate = remember(scheduledDate) { TaskRules.parseStrictIsoDate(scheduledDate) }
  val parsedStartTime = remember(startTime) { TaskRules.parseStrictTime(startTime) }
  val parsedEndTime = remember(endTime) { TaskRules.parseStrictTime(endTime) }
  val canToggleNextDay = parsedStartTime != null && parsedEndTime != null

  val scheduleWindow =
    remember(scheduledDate, startTime, endTime, spansNextDay, localClock) {
      TaskRules.resolveScheduleWindow(
        scheduledDate = scheduledDate,
        startTime = startTime,
        endTime = endTime,
        spansNextDayOverride = spansNextDay,
        zoneId = localClock.zoneId(),
      )
    }
  val formattedSchedule =
    remember(scheduleWindow, language, context) {
      TaskScheduleFormatters.formatWindow(
        context = context,
        window = scheduleWindow,
        language = language,
      )
    }

  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(
      text = stringResource(R.string.task_field_schedule_section),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      FilterChip(
        selected = scheduledDate.isBlank(),
        onClick = { onSelectDate("") },
        label = { Text(stringResource(R.string.task_schedule_unscheduled)) },
        modifier = Modifier.minimumInteractiveComponentSize().testTag("task_date_unscheduled"),
      )
      FilterChip(
        selected = scheduledDate == todayStr,
        onClick = { onSelectDate(todayStr) },
        label = { Text(stringResource(R.string.task_schedule_today)) },
        modifier = Modifier.minimumInteractiveComponentSize().testTag("task_date_today"),
      )
      FilterChip(
        selected = scheduledDate == tomorrowStr,
        onClick = { onSelectDate(tomorrowStr) },
        label = { Text(stringResource(R.string.task_schedule_tomorrow)) },
        modifier = Modifier.minimumInteractiveComponentSize().testTag("task_date_tomorrow"),
      )
      FilterChip(
        selected = scheduledDate.isNotBlank() && scheduledDate != todayStr && scheduledDate != tomorrowStr,
        onClick = onOpenStartDatePicker,
        leadingIcon = {
          Icon(
            imageVector = Icons.Outlined.CalendarToday,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
        },
        label = { Text(stringResource(R.string.task_schedule_pick_date)) },
        modifier = Modifier.minimumInteractiveComponentSize().testTag("task_date_picker_btn"),
      )
    }

    Surface(
      shape = MaterialTheme.shapes.medium,
      color = MaterialTheme.colorScheme.surfaceContainerLow,
      border =
        BorderStroke(
          width = 1.dp,
          color =
            if (dateError != null || startTimeError != null || endTimeError != null) {
              MaterialTheme.colorScheme.error
            } else {
              semantic.borderSubtle
            },
        ),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        // Start Date Selector Row
        val startDateValueText =
          parsedStartDate?.let { TaskScheduleFormatters.formatDate(it, language) }
            ?: stringResource(R.string.task_schedule_not_set)
        Surface(
          shape = MaterialTheme.shapes.small,
          color = MaterialTheme.colorScheme.surfaceContainer,
          border =
            BorderStroke(
              1.dp,
              if (dateError != null) MaterialTheme.colorScheme.error else semantic.borderSubtle,
            ),
          modifier =
            Modifier.fillMaxWidth()
              .minimumInteractiveComponentSize()
              .semantics { stateDescription = startDateValueText }
              .clickable(role = Role.Button, onClick = onOpenStartDatePicker)
              .testTag("task_start_date_btn"),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Outlined.Event,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = stringResource(R.string.task_schedule_start_date_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                text = startDateValueText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color =
                  if (parsedStartDate != null) MaterialTheme.colorScheme.onSurface
                  else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                  Modifier.semantics(mergeDescendants = true) {}
                    .testTag("task_start_date_value"),
              )
            }
            if (scheduledDate.isNotBlank()) {
              IconButton(
                onClick = { onSelectDate("") },
                modifier =
                  Modifier.minimumInteractiveComponentSize().testTag("task_clear_date_btn"),
              ) {
                Icon(
                  imageVector = Icons.Outlined.Close,
                  contentDescription = stringResource(R.string.task_clear_date_desc),
                  modifier = Modifier.size(18.dp),
                )
              }
            }
          }
        }

        dateError?.let { msg ->
          Text(
            text = msg,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("task_date_error"),
          )
        }

        // Graphical Start Time and End Time Selectors
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          TimePickerSelectorTile(
            label = stringResource(R.string.task_schedule_start_time_label),
            valueText =
              parsedStartTime?.let { TaskScheduleFormatters.formatTime(it) }
                ?: stringResource(R.string.task_schedule_not_set),
            hasValue = parsedStartTime != null,
            isError = startTimeError != null,
            clearContentDescription = stringResource(R.string.task_clear_start_time_desc),
            onClick = onOpenStartTimePicker,
            onClear = onClearStartTime,
            cardTag = "task_start_time_btn",
            valueTag = "task_start_time_value",
            clearTag = "task_clear_start_time_btn",
            modifier = Modifier.weight(1f),
          )
          TimePickerSelectorTile(
            label = stringResource(R.string.task_schedule_end_time_label),
            valueText =
              parsedEndTime?.let { TaskScheduleFormatters.formatTime(it) }
                ?: stringResource(R.string.task_schedule_not_set),
            hasValue = parsedEndTime != null,
            isError = endTimeError != null,
            clearContentDescription = stringResource(R.string.task_clear_end_time_desc),
            onClick = onOpenEndTimePicker,
            onClear = onClearEndTime,
            cardTag = "task_end_time_btn",
            valueTag = "task_end_time_value",
            clearTag = "task_clear_end_time_btn",
            modifier = Modifier.weight(1f),
          )
        }

        startTimeError?.let { msg ->
          Text(
            text = msg,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("task_start_time_error"),
          )
        }

        endTimeError?.let { msg ->
          Text(
            text = msg,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("task_end_time_error"),
          )
        }

        // Next-day Material 3 Switch row: label on right, Switch on left in Arabic RTL
        val nextDayFullDesc = stringResource(R.string.task_next_day_full)
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            Text(
              text = stringResource(R.string.task_next_day_short),
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.Medium,
              color =
                if (canToggleNextDay) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.testTag("task_next_day_label"),
            )
            Text(
              text = nextDayFullDesc,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.testTag("task_next_day_full_label"),
            )
          }
          Switch(
            checked = spansNextDay,
            onCheckedChange = onSpansNextDayChange,
            enabled = canToggleNextDay,
            modifier =
              Modifier.minimumInteractiveComponentSize()
                .semantics { contentDescription = nextDayFullDesc }
                .testTag("task_next_day_switch"),
          )
        }

        // Calculated End Date, Schedule Range Preview (Start Date+Time -> End Date+Time), and Duration
        if (
          scheduleWindow.endDate != null ||
            formattedSchedule.rangeText != null ||
            formattedSchedule.durationText != null
        ) {
          HorizontalDivider(color = semantic.borderSubtle)
          Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            scheduleWindow.endDate?.let { resolvedEnd ->
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = stringResource(R.string.task_schedule_end_date_label),
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = TaskScheduleFormatters.formatDate(resolvedEnd, language),
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.Medium,
                  color = MaterialTheme.colorScheme.onSurface,
                  modifier = Modifier.testTag("task_end_date_value"),
                )
              }
            }

            formattedSchedule.rangeText?.let { preview ->
              Text(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.testTag("task_schedule_preview"),
              )
            }

            formattedSchedule.durationText?.let { duration ->
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = stringResource(R.string.task_schedule_duration_label),
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                  text = duration,
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.SemiBold,
                  color = MaterialTheme.colorScheme.onSurface,
                  modifier = Modifier.testTag("task_schedule_duration"),
                )
              }
            }

            if (formattedSchedule.hasDstShift) {
              Text(
                text = stringResource(R.string.task_duration_dst_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.testTag("task_schedule_dst_note"),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun TimePickerSelectorTile(
  label: String,
  valueText: String,
  hasValue: Boolean,
  isError: Boolean,
  clearContentDescription: String,
  onClick: () -> Unit,
  onClear: () -> Unit,
  cardTag: String,
  valueTag: String,
  clearTag: String,
  modifier: Modifier = Modifier,
) {
  val semantic = LocalYawmiSemanticColors.current
  Surface(
    shape = MaterialTheme.shapes.small,
    color = MaterialTheme.colorScheme.surfaceContainer,
    border =
      BorderStroke(
        width = 1.dp,
        color = if (isError) MaterialTheme.colorScheme.error else semantic.borderSubtle,
      ),
    modifier =
      modifier
        .minimumInteractiveComponentSize()
        .clickable(onClick = onClick)
        .testTag(cardTag),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.Outlined.Schedule,
        contentDescription = null,
        tint =
          if (hasValue) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(16.dp),
      )
      Spacer(modifier = Modifier.width(6.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = label,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
        )
        Text(
          text = valueText,
          style = MaterialTheme.typography.bodyMedium,
          fontFamily = if (hasValue) JetBrainsMonoFontFamily else null,
          fontWeight = if (hasValue) FontWeight.SemiBold else FontWeight.Normal,
          color =
            if (hasValue) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          modifier = Modifier.testTag(valueTag),
        )
      }
      if (hasValue) {
        IconButton(
          onClick = onClear,
          modifier = Modifier.size(38.dp).testTag(clearTag),
        ) {
          Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = clearContentDescription,
            modifier = Modifier.size(16.dp),
          )
        }
      }
    }
  }
}
