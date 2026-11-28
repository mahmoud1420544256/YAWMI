package com.example.feature.tasks.components

import android.content.Context
import com.example.R
import com.example.core.i18n.createLocalizedContext
import com.example.core.preferences.AppLanguage
import com.example.data.local.entity.TaskEntity
import com.example.domain.task.TaskRules
import com.example.domain.task.TaskScheduleWindow
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class FormattedTaskSchedule(
  val startEndpoint: String?,
  val endEndpoint: String?,
  val rangeText: String?,
  val durationText: String?,
  val spansNextDay: Boolean,
  val hasDstShift: Boolean,
)

object TaskScheduleFormatters {
  private val TIME_24H_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.US)

  private fun Context.forLanguage(language: AppLanguage): Context {
    val locales = resources.configuration.locales
    val currentTag = if (!locales.isEmpty) locales.get(0)?.language else null
    return if (currentTag == language.tag) this else createLocalizedContext(language)
  }

  fun formatDate(date: LocalDate, language: AppLanguage): String {
    val locale =
      if (language == AppLanguage.ARABIC) Locale.forLanguageTag("ar") else Locale.ENGLISH
    val pattern = if (language == AppLanguage.ARABIC) "d MMMM yyyy" else "d MMM yyyy"
    return date.format(DateTimeFormatter.ofPattern(pattern, locale))
  }

  fun formatTime(time: LocalTime): String = time.format(TIME_24H_FORMATTER)

  fun formatDateTimeEndpoint(
    context: Context,
    date: LocalDate,
    time: LocalTime,
    language: AppLanguage,
  ): String =
    context.forLanguage(language).getString(
      R.string.task_schedule_endpoint_format,
      formatDate(date, language),
      formatTime(time),
    )

  fun formatDuration(
    context: Context,
    duration: Duration,
    language: AppLanguage,
  ): String {
    val localized = context.forLanguage(language)
    val totalMinutes = duration.toMinutes().coerceAtLeast(0L)
    val hours = (totalMinutes / 60L).toInt()
    val minutes = (totalMinutes % 60L).toInt()

    return if (language == AppLanguage.ARABIC) {
      val res = localized.resources
      val hoursArg = hours.toString()
      val minutesArg = minutes.toString()
      when {
        hours > 0 && minutes > 0 -> {
          val hText = res.getQuantityString(R.plurals.task_duration_hours_plural, hours, hoursArg)
          val mText =
            res.getQuantityString(R.plurals.task_duration_minutes_plural, minutes, minutesArg)
          localized.getString(R.string.task_duration_hours_minutes, hText, mText)
        }
        hours > 0 -> res.getQuantityString(R.plurals.task_duration_hours_plural, hours, hoursArg)
        else ->
          res.getQuantityString(R.plurals.task_duration_minutes_plural, minutes, minutesArg)
      }
    } else {
      when {
        hours > 0 && minutes > 0 ->
          localized.getString(R.string.task_duration_hours_minutes, hours, minutes)
        hours > 0 -> localized.getString(R.string.task_duration_hours_only, hours)
        else -> localized.getString(R.string.task_duration_minutes_only, minutes)
      }
    }
  }

  fun formatWindow(
    context: Context,
    window: TaskScheduleWindow,
    language: AppLanguage,
  ): FormattedTaskSchedule {
    val localized = context.forLanguage(language)
    val startDate = window.startDate
    val startTime = window.startTime
    val endDate = window.endDate
    val endTime = window.endTime

    val startEndpoint =
      when {
        startDate != null && startTime != null ->
          formatDateTimeEndpoint(localized, startDate, startTime, language)
        startDate != null -> formatDate(startDate, language)
        startTime != null -> formatTime(startTime)
        else -> null
      }

    val endEndpoint =
      when {
        endDate != null && endTime != null ->
          formatDateTimeEndpoint(localized, endDate, endTime, language)
        endTime != null -> formatTime(endTime)
        else -> null
      }

    val rangeText =
      when {
        startEndpoint != null && endEndpoint != null ->
          localized.getString(R.string.task_schedule_range_format, startEndpoint, endEndpoint)
        startEndpoint != null -> startEndpoint
        else -> null
      }

    val durationText = window.duration?.let { formatDuration(localized, it, language) }

    return FormattedTaskSchedule(
      startEndpoint = startEndpoint,
      endEndpoint = endEndpoint,
      rangeText = rangeText,
      durationText = durationText,
      spansNextDay = window.spansNextDay,
      hasDstShift = window.hasDstShift,
    )
  }

  fun formatTaskSchedule(
    context: Context,
    task: TaskEntity,
    zoneId: ZoneId,
    language: AppLanguage,
  ): FormattedTaskSchedule {
    val window = TaskRules.resolveScheduleWindow(task = task, zoneId = zoneId)
    return formatWindow(context = context, window = window, language = language)
  }
}
