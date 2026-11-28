package com.example.domain.task

import com.example.data.local.entity.TaskEntity
import com.example.data.local.entity.TaskReminderEntity
import com.example.data.local.entity.TaskStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class ResolvedReminderTrigger(
  val triggerDate: String,
  val triggerTime: String,
  val offsetMinutes: Int,
)

/**
 * Outcome when synchronizing an existing [TaskReminderEntity] against an updated [TaskEntity] schedule.
 */
sealed interface ReminderScheduleSyncAction {
  data class Reschedule(
    val triggerDate: String,
    val triggerTime: String,
  ) : ReminderScheduleSyncAction

  data object PauseMissingReference : ReminderScheduleSyncAction

  data object Unchanged : ReminderScheduleSyncAction
}

/**
 * Pure domain rules for validating and recalculating task reminder triggers without ever
 * inventing a default time when a task has a date without a start time.
 */
object TaskReminderRules {
  private val ISO_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
  private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

  fun isTaskActive(status: TaskStatus): Boolean =
    status == TaskStatus.PENDING || status == TaskStatus.IN_PROGRESS

  fun validateAndResolveRelativeReminder(
    task: TaskEntity,
    offsetMinutes: Int,
  ): TaskReminderResult<ResolvedReminderTrigger> {
    if (!isTaskActive(task.status)) {
      return TaskReminderResult.Failure(TaskReminderError.TaskNotActive(task.status))
    }
    if (offsetMinutes < 0) {
      return TaskReminderResult.Failure(TaskReminderError.InvalidOffsetMinutes(offsetMinutes))
    }
    val scheduledDate =
      task.scheduledDate?.let { TaskRules.parseStrictIsoDate(it) }
        ?: return TaskReminderResult.Failure(TaskReminderError.MissingScheduledDate)
    val startTime =
      task.startTime?.let { TaskRules.parseStrictTime(it) }
        ?: return TaskReminderResult.Failure(TaskReminderError.RelativeReminderRequiresStartTime)

    val triggerDateTime = LocalDateTime.of(scheduledDate, startTime).minusMinutes(offsetMinutes.toLong())
    return TaskReminderResult.Success(
      ResolvedReminderTrigger(
        triggerDate = triggerDateTime.toLocalDate().format(ISO_DATE_FORMATTER),
        triggerTime = triggerDateTime.toLocalTime().format(TIME_FORMATTER),
        offsetMinutes = offsetMinutes,
      )
    )
  }

  /**
   * Validates an explicit user-chosen reminder trigger time. When a task has a `scheduledDate`
   * without `startTime`, only an explicit user-chosen `triggerTime` (`offsetMinutes = 0`) is
   * permitted—never a synthesized default time.
   */
  fun validateAndResolveExplicitReminder(
    task: TaskEntity,
    triggerTimeRaw: String,
    triggerDateRaw: String? = null,
  ): TaskReminderResult<ResolvedReminderTrigger> {
    if (!isTaskActive(task.status)) {
      return TaskReminderResult.Failure(TaskReminderError.TaskNotActive(task.status))
    }

    val effectiveDateRaw = triggerDateRaw ?: task.scheduledDate
    if (effectiveDateRaw.isNullOrBlank()) {
      return TaskReminderResult.Failure(TaskReminderError.MissingScheduledDate)
    }
    val parsedDate =
      TaskRules.parseStrictIsoDate(effectiveDateRaw)
        ?: return TaskReminderResult.Failure(TaskReminderError.InvalidTriggerDate(effectiveDateRaw))

    val parsedTime =
      TaskRules.parseStrictTime(triggerTimeRaw)
        ?: return TaskReminderResult.Failure(TaskReminderError.InvalidTriggerTime(triggerTimeRaw))

    return TaskReminderResult.Success(
      ResolvedReminderTrigger(
        triggerDate = parsedDate.format(ISO_DATE_FORMATTER),
        triggerTime = parsedTime.format(TIME_FORMATTER),
        offsetMinutes = 0,
      )
    )
  }

  /**
   * Determines how an existing enabled [reminder] reacts when [previousTask] is edited or postponed
   * into [updatedTask].
   */
  fun resolveSyncActionOnTaskScheduleChange(
    previousTask: TaskEntity,
    updatedTask: TaskEntity,
    reminder: TaskReminderEntity,
  ): ReminderScheduleSyncAction {
    if (!reminder.isEnabled) return ReminderScheduleSyncAction.Unchanged

    val updatedDate = updatedTask.scheduledDate?.let { TaskRules.parseStrictIsoDate(it) }
    if (updatedDate == null) {
      return ReminderScheduleSyncAction.PauseMissingReference
    }

    val updatedTime = updatedTask.startTime?.let { TaskRules.parseStrictTime(it) }
    if (updatedTime == null && reminder.offsetMinutes != 0) {
      return ReminderScheduleSyncAction.PauseMissingReference
    }

    val scheduleChanged =
      previousTask.scheduledDate != updatedTask.scheduledDate ||
        previousTask.startTime != updatedTask.startTime

    if (!scheduleChanged) {
      return ReminderScheduleSyncAction.Unchanged
    }

    val previousDateMatchesReminder = previousTask.scheduledDate == reminder.triggerDate
    val isAnchoredAtStartZeroOffset =
      reminder.offsetMinutes == 0 &&
        previousTask.startTime != null &&
        previousTask.scheduledDate != null &&
        reminder.triggerTime == previousTask.startTime &&
        previousDateMatchesReminder

    // Task now has date without startTime:
    // - Relative reminders (offsetMinutes != 0) and reliably anchored 0m-at-start reminders lose
    //   their reference -> pause cleanly.
    // - Explicit reminders (never anchored to previousTask.startTime) keep their user-chosen
    //   triggerTime and only move triggerDate if scheduledDate changed.
    if (updatedTime == null) {
      if (reminder.offsetMinutes != 0 || isAnchoredAtStartZeroOffset) {
        return ReminderScheduleSyncAction.PauseMissingReference
      }
      val targetDateStr = updatedDate.format(ISO_DATE_FORMATTER)
      return if (previousTask.scheduledDate != updatedTask.scheduledDate && previousDateMatchesReminder && targetDateStr != reminder.triggerDate) {
        ReminderScheduleSyncAction.Reschedule(
          triggerDate = targetDateStr,
          triggerTime = reminder.triggerTime,
        )
      } else {
        ReminderScheduleSyncAction.Unchanged
      }
    }

    if (reminder.offsetMinutes > 0) {
      val triggerDateTime =
        LocalDateTime.of(updatedDate, updatedTime).minusMinutes(reminder.offsetMinutes.toLong())
      val newDate = triggerDateTime.toLocalDate().format(ISO_DATE_FORMATTER)
      val newTime = triggerDateTime.toLocalTime().format(TIME_FORMATTER)
      return if (newDate == reminder.triggerDate && newTime == reminder.triggerTime) {
        ReminderScheduleSyncAction.Unchanged
      } else {
        ReminderScheduleSyncAction.Reschedule(
          triggerDate = newDate,
          triggerTime = newTime,
        )
      }
    }

    // Conservative Schema v1 policy for offsetMinutes == 0:
    // Because Schema v1 stores both "at start" (0m) and "explicit HH:mm" with offsetMinutes = 0,
    // only update triggerTime to updatedTask.startTime when previousTask.startTime != null AND the
    // stored reminder matched previousTask.scheduledDate + previousTask.startTime. Otherwise preserve
    // the explicit triggerTime and update triggerDate only if scheduledDate changed.
    return if (isAnchoredAtStartZeroOffset) {
      val newDate = updatedDate.format(ISO_DATE_FORMATTER)
      val newTime = updatedTime.format(TIME_FORMATTER)
      if (newDate == reminder.triggerDate && newTime == reminder.triggerTime) {
        ReminderScheduleSyncAction.Unchanged
      } else {
        ReminderScheduleSyncAction.Reschedule(
          triggerDate = newDate,
          triggerTime = newTime,
        )
      }
    } else {
      val targetDateStr = updatedDate.format(ISO_DATE_FORMATTER)
      if (previousTask.scheduledDate != updatedTask.scheduledDate && previousDateMatchesReminder && targetDateStr != reminder.triggerDate) {
        ReminderScheduleSyncAction.Reschedule(
          triggerDate = targetDateStr,
          triggerTime = reminder.triggerTime,
        )
      } else {
        ReminderScheduleSyncAction.Unchanged
      }
    }
  }

  /**
   * Resolves trigger date & time when generating the next occurrence of a recurring task.
   * Returns `null` if the reminder cannot be anchored without inventing a time.
   */
  fun resolveNextOccurrenceReminderTrigger(
    nextTask: TaskEntity,
    previousReminder: TaskReminderEntity,
    previousTaskStartTime: String? = nextTask.startTime,
  ): ResolvedReminderTrigger? {
    val nextDate = nextTask.scheduledDate?.let { TaskRules.parseStrictIsoDate(it) } ?: return null
    val nextStartTime = nextTask.startTime?.let { TaskRules.parseStrictTime(it) }

    if (previousReminder.offsetMinutes > 0) {
      val anchorTime = nextStartTime ?: return null
      val dt = LocalDateTime.of(nextDate, anchorTime).minusMinutes(previousReminder.offsetMinutes.toLong())
      return ResolvedReminderTrigger(
        triggerDate = dt.toLocalDate().format(ISO_DATE_FORMATTER),
        triggerTime = dt.toLocalTime().format(TIME_FORMATTER),
        offsetMinutes = previousReminder.offsetMinutes,
      )
    }

    val isAnchoredAtStartZeroOffset =
      nextStartTime != null &&
        previousTaskStartTime != null &&
        previousReminder.triggerTime == previousTaskStartTime
    if (isAnchoredAtStartZeroOffset) {
      return ResolvedReminderTrigger(
        triggerDate = nextDate.format(ISO_DATE_FORMATTER),
        triggerTime = nextStartTime.format(TIME_FORMATTER),
        offsetMinutes = 0,
      )
    }

    val explicitTime = TaskRules.parseStrictTime(previousReminder.triggerTime) ?: return null
    return ResolvedReminderTrigger(
      triggerDate = nextDate.format(ISO_DATE_FORMATTER),
      triggerTime = explicitTime.format(TIME_FORMATTER),
      offsetMinutes = 0,
    )
  }

  fun computeSnoozeDateTime(
    fromEpochMs: Long,
    snoozeMinutes: Int,
    zoneId: java.time.ZoneId,
  ): Pair<String, String> {
    val targetZoned =
      java.time.Instant.ofEpochMilli(fromEpochMs)
        .atZone(zoneId)
        .plusMinutes(snoozeMinutes.toLong())
    return targetZoned.toLocalDate().format(ISO_DATE_FORMATTER) to
      targetZoned.toLocalTime().format(TIME_FORMATTER)
  }

  fun formatDate(date: LocalDate): String = date.format(ISO_DATE_FORMATTER)

  fun formatTime(time: LocalTime): String = time.format(TIME_FORMATTER)
}
