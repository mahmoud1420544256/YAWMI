package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.i18n.createLocalizedContext
import com.example.core.preferences.AppLanguage
import com.example.data.local.db.YawmiDatabase
import com.example.data.local.entity.TaskEntity
import com.example.domain.task.DstAdjustmentPolicy
import com.example.domain.task.TaskDomainResult
import com.example.domain.task.TaskInput
import com.example.domain.task.TaskReminderError
import com.example.domain.task.TaskRules
import com.example.domain.task.TaskValidationError
import com.example.feature.tasks.components.TaskScheduleFormatters
import com.example.feature.tasks.components.toLocalizedMessage
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Unit4A2DScheduleAndDurationTest {

  private lateinit var context: Context

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
  }

  @Test
  fun sameDayTask_0900To1700_computes8HoursAndFormatsBothDateTimeEndpoints() {
    val result =
      TaskRules.buildNewTaskEntity(
        input =
          TaskInput(
            title = "Deep focus",
            scheduledDate = "2026-11-27",
            startTime = "09:00",
            endTime = "17:00",
            spansNextDay = false,
          ),
        nowEpochMs = 1_000L,
        idGenerator = { "task-same-day" },
      )
    assertTrue(result is TaskDomainResult.Success)
    val entity = (result as TaskDomainResult.Success).value

    val window = TaskRules.resolveScheduleWindow(entity, ZoneId.of("UTC"))
    assertFalse(window.spansNextDay)
    assertEquals(LocalDate.of(2026, 11, 27), window.startDate)
    assertEquals(LocalDate.of(2026, 11, 27), window.endDate)
    assertEquals(Duration.ofHours(8), window.duration)

    val arContext = context.createLocalizedContext(AppLanguage.ARABIC)
    val arFormatted =
      TaskScheduleFormatters.formatTaskSchedule(
        context = arContext,
        task = entity,
        zoneId = ZoneId.of("UTC"),
        language = AppLanguage.ARABIC,
      )
    assertEquals("27 نوفمبر 2026، 09:00", arFormatted.startEndpoint)
    assertEquals("27 نوفمبر 2026، 17:00", arFormatted.endEndpoint)
    assertEquals("27 نوفمبر 2026، 09:00 ← 27 نوفمبر 2026، 17:00", arFormatted.rangeText)
    assertEquals("8 ساعات", arFormatted.durationText)

    val enContext = context.createLocalizedContext(AppLanguage.ENGLISH)
    val enFormatted =
      TaskScheduleFormatters.formatTaskSchedule(
        context = enContext,
        task = entity,
        zoneId = ZoneId.of("UTC"),
        language = AppLanguage.ENGLISH,
      )
    assertEquals("27 Nov 2026, 09:00 → 27 Nov 2026, 17:00", enFormatted.rangeText)
    assertEquals("8h", enFormatted.durationText)
  }

  @Test
  fun nextDayExtension_2100To0700_acceptedWhenOnAndComputes10Hours() {
    val result =
      TaskRules.buildNewTaskEntity(
        input =
          TaskInput(
            title = "Cross-midnight run",
            scheduledDate = "2026-11-27",
            startTime = "21:00",
            endTime = "07:00",
            spansNextDay = true,
          ),
        nowEpochMs = 2_000L,
        idGenerator = { "task-next-day" },
      )
    assertTrue(result is TaskDomainResult.Success)
    val entity = (result as TaskDomainResult.Success).value
    assertEquals("2026-11-27", entity.scheduledDate)
    assertEquals("21:00", entity.startTime)
    assertEquals("07:00", entity.endTime)

    val window = TaskRules.resolveScheduleWindow(entity, ZoneId.of("UTC"))
    assertTrue(window.spansNextDay)
    assertEquals(LocalDate.of(2026, 11, 27), window.startDate)
    assertEquals(LocalDate.of(2026, 11, 28), window.endDate)
    assertEquals(Duration.ofHours(10), window.duration)

    val arContext = context.createLocalizedContext(AppLanguage.ARABIC)
    val arFormatted =
      TaskScheduleFormatters.formatTaskSchedule(
        context = arContext,
        task = entity,
        zoneId = ZoneId.of("UTC"),
        language = AppLanguage.ARABIC,
      )
    assertEquals("27 نوفمبر 2026، 21:00 ← 28 نوفمبر 2026، 07:00", arFormatted.rangeText)
    assertEquals("10 ساعات", arFormatted.durationText)
  }

  @Test
  fun endBeforeStart_isRejectedWhenNextDayOff_andEndAfterStartIsRejectedWhenNextDayOn() {
    val offResult =
      TaskRules.validateInput(
        TaskInput(
          title = "Invalid same day",
          scheduledDate = "2026-11-27",
          startTime = "21:00",
          endTime = "07:00",
          spansNextDay = false,
        )
      )
    assertTrue(offResult is TaskDomainResult.Failure)
    assertTrue(
      (offResult as TaskDomainResult.Failure).errors.contains(TaskValidationError.EndTimeBeforeStartTime)
    )

    // Under Schema v1, ON requires endTime < startTime (< 24h)
    val onGreaterResult =
      TaskRules.validateInput(
        TaskInput(
          title = "Invalid 24h+ spillover",
          scheduledDate = "2026-11-27",
          startTime = "09:00",
          endTime = "17:00",
          spansNextDay = true,
        )
      )
    assertTrue(onGreaterResult is TaskDomainResult.Failure)
    assertTrue(
      (onGreaterResult as TaskDomainResult.Failure).errors.contains(
        TaskValidationError.NextDayRequiresEndBeforeStart
      )
    )
  }

  @Test
  fun equalStartAndEndTimes_areRejectedForBothOffAndOn() {
    val equalOff =
      TaskRules.validateInput(
        TaskInput(
          title = "Zero length",
          scheduledDate = "2026-11-27",
          startTime = "09:00",
          endTime = "09:00",
          spansNextDay = false,
        )
      )
    assertTrue(equalOff is TaskDomainResult.Failure)
    assertTrue(
      (equalOff as TaskDomainResult.Failure).errors.contains(TaskValidationError.EndTimeEqualsStartTime)
    )

    val equalOn =
      TaskRules.validateInput(
        TaskInput(
          title = "Exact 24h disallowed",
          scheduledDate = "2026-11-27",
          startTime = "21:00",
          endTime = "21:00",
          spansNextDay = true,
        )
      )
    assertTrue(equalOn is TaskDomainResult.Failure)
    assertTrue(
      (equalOn as TaskDomainResult.Failure).errors.contains(TaskValidationError.EndTimeEqualsStartTime)
    )
  }

  @Test
  fun nextDayExtensionOn_rejectedWhenScheduledDateOrTimesAreMissing() {
    val missingDate =
      TaskRules.validateInput(
        TaskInput(
          title = "Unscheduled spillover",
          scheduledDate = null,
          startTime = "21:00",
          endTime = "07:00",
          spansNextDay = true,
        )
      )
    assertTrue(missingDate is TaskDomainResult.Failure)
    assertTrue(
      (missingDate as TaskDomainResult.Failure).errors.contains(
        TaskValidationError.NextDayRequiresScheduledDate
      )
    )

    val missingStart =
      TaskRules.validateInput(
        TaskInput(
          title = "Missing start",
          scheduledDate = "2026-11-27",
          startTime = null,
          endTime = "07:00",
          spansNextDay = true,
        )
      )
    val missingStartErrors = (missingStart as TaskDomainResult.Failure).errors
    assertTrue(missingStartErrors.contains(TaskValidationError.EndTimeWithoutStartTime))
    assertTrue(missingStartErrors.contains(TaskValidationError.NextDayRequiresTimeWindow))

    val missingEnd =
      TaskRules.validateInput(
        TaskInput(
          title = "Missing end",
          scheduledDate = "2026-11-27",
          startTime = "21:00",
          endTime = null,
          spansNextDay = true,
        )
      )
    assertTrue(
      (missingEnd as TaskDomainResult.Failure).errors.contains(
        TaskValidationError.NextDayRequiresTimeWindow
      )
    )
  }

  @Test
  fun savedOvernightTask_restoresNextDayStateWithoutSchemaColumns_andPreservesNullTimeBehavior() {
    val persistedOvernight =
      TaskEntity(
        id = "saved-overnight",
        title = "Overnight backup",
        scheduledDate = "2026-11-27",
        startTime = "21:00",
        endTime = "07:00",
        createdAtEpochMs = 100L,
        updatedAtEpochMs = 100L,
      )
    assertTrue(TaskRules.inferSpansNextDay(persistedOvernight.startTime, persistedOvernight.endTime))

    val restoredWindow = TaskRules.resolveScheduleWindow(persistedOvernight, ZoneId.of("UTC"))
    assertTrue(restoredWindow.spansNextDay)
    assertEquals(LocalDate.of(2026, 11, 27), restoredWindow.startDate)
    assertEquals(LocalDate.of(2026, 11, 28), restoredWindow.endDate)
    assertEquals(Duration.ofHours(10), restoredWindow.duration)

    val untimedTask =
      TaskEntity(
        id = "saved-untimed",
        title = "Date only",
        scheduledDate = "2026-11-27",
        startTime = null,
        endTime = null,
        createdAtEpochMs = 100L,
        updatedAtEpochMs = 100L,
      )
    assertFalse(TaskRules.inferSpansNextDay(untimedTask.startTime, untimedTask.endTime))
    val untimedWindow = TaskRules.resolveScheduleWindow(untimedTask, ZoneId.of("UTC"))
    assertNull(untimedWindow.startTime)
    assertNull(untimedWindow.endTime)
    assertNull(untimedWindow.duration)
  }

  @Test
  fun dstSpringForwardAndFallBack_useZonedDateTimeWithGapShiftedForwardAndOverlapEarlierOffset() {
    val nyZone = ZoneId.of("America/New_York")

    // 1. Spring Forward overnight (2026-03-07 21:00 EST -> 2026-03-08 07:00 EDT = 9 actual hours)
    val springOvernight =
      TaskRules.resolveScheduleWindow(
        scheduledDate = "2026-03-07",
        startTime = "21:00",
        endTime = "07:00",
        spansNextDayOverride = true,
        zoneId = nyZone,
      )
    assertEquals(Duration.ofHours(9), springOvernight.duration)
    assertEquals(Duration.ofHours(10), springOvernight.wallClockDuration)
    assertTrue(springOvernight.hasDstShift)

    // 2. Spring Forward gap (2026-03-08 02:30 -> 04:00): 02:30 shifts forward to 03:30 => 30 min
    val springGap =
      TaskRules.resolveScheduleWindow(
        scheduledDate = "2026-03-08",
        startTime = "02:30",
        endTime = "04:00",
        spansNextDayOverride = false,
        zoneId = nyZone,
      )
    assertEquals(DstAdjustmentPolicy.GAP_SHIFTED_FORWARD, springGap.startDstAdjustment)
    assertEquals(DstAdjustmentPolicy.NONE, springGap.endDstAdjustment)
    assertEquals(Duration.ofMinutes(30), springGap.duration)
    assertTrue(springGap.hasDstShift)

    // 3. Fall Back overnight (2026-10-31 21:00 EDT -> 2026-11-01 07:00 EST = 11 actual hours)
    val fallOvernight =
      TaskRules.resolveScheduleWindow(
        scheduledDate = "2026-10-31",
        startTime = "21:00",
        endTime = "07:00",
        spansNextDayOverride = true,
        zoneId = nyZone,
      )
    assertEquals(Duration.ofHours(11), fallOvernight.duration)
    assertEquals(Duration.ofHours(10), fallOvernight.wallClockDuration)
    assertTrue(fallOvernight.hasDstShift)

    // 4. Fall Back overlap (2026-11-01 01:30 -> 03:00): 01:30 uses earlier offset (-04:00) => 2h 30m
    val fallOverlap =
      TaskRules.resolveScheduleWindow(
        scheduledDate = "2026-11-01",
        startTime = "01:30",
        endTime = "03:00",
        spansNextDayOverride = false,
        zoneId = nyZone,
      )
    assertEquals(DstAdjustmentPolicy.OVERLAP_EARLIER_OFFSET, fallOverlap.startDstAdjustment)
    assertEquals(DstAdjustmentPolicy.NONE, fallOverlap.endDstAdjustment)
    assertEquals(Duration.ofMinutes(150), fallOverlap.duration)
    assertTrue(fallOverlap.hasDstShift)
  }

  @Test
  fun roomSchemaV1AndIdentityHash_remainCompletelyUnchanged() {
    assertEquals(1, YawmiDatabase.CURRENT_DB_VERSION)

    val schemaFile =
      listOf(
          File("schemas/com.example.data.local.db.YawmiDatabase/1.json"),
          File("app/schemas/com.example.data.local.db.YawmiDatabase/1.json"),
        )
        .firstOrNull { it.exists() }
    assertTrue("Expected Room 1.json schema export to exist", schemaFile != null)
    val schemaJson = schemaFile!!.readText()
    assertTrue(schemaJson.contains("\"identityHash\": \"37c351a60c04e881c6bd50156833f191\""))

    val db = YawmiDatabase.inMemory(context)
    try {
      val sqlite = db.openHelper.readableDatabase
      assertEquals(1, sqlite.version)
      val columns = mutableSetOf<String>()
      sqlite.query("PRAGMA table_info(tasks)").use { cursor ->
        val nameIdx = cursor.getColumnIndexOrThrow("name")
        while (cursor.moveToNext()) {
          columns += cursor.getString(nameIdx)
        }
      }
      assertFalse(columns.contains("endDate"))
      assertFalse(columns.contains("spansNextDay"))
      assertTrue(columns.containsAll(listOf("scheduledDate", "startTime", "endTime")))
    } finally {
      db.close()
    }
  }

  @Test
  fun validationAndReminderErrorMappers_provideNonBlankArabicAndEnglishMessages() {
    val ar = context.createLocalizedContext(AppLanguage.ARABIC)
    val en = context.createLocalizedContext(AppLanguage.ENGLISH)

    val validationErrors =
      listOf(
        TaskValidationError.EndTimeEqualsStartTime,
        TaskValidationError.NextDayRequiresScheduledDate,
        TaskValidationError.NextDayRequiresTimeWindow,
        TaskValidationError.NextDayRequiresEndBeforeStart,
      )
    validationErrors.forEach { err ->
      assertTrue(err.toLocalizedMessage(ar).isNotBlank())
      assertTrue(err.toLocalizedMessage(en).isNotBlank())
    }

    val reminderErrors =
      listOf(
        TaskReminderError.MissingScheduledDate,
        TaskReminderError.RelativeReminderRequiresStartTime,
        TaskReminderError.InvalidSnoozeMinutes(0),
      )
    reminderErrors.forEach { err ->
      assertTrue(err.toLocalizedMessage(ar).isNotBlank())
      assertTrue(err.toLocalizedMessage(en).isNotBlank())
    }
  }

  @Test
  fun durationFormatting_handlesArabicPluralsAndCombinedHoursMinutes() {
    val ar = context.createLocalizedContext(AppLanguage.ARABIC)
    val en = context.createLocalizedContext(AppLanguage.ENGLISH)

    assertEquals(
      "ساعة واحدة",
      TaskScheduleFormatters.formatDuration(ar, Duration.ofHours(1), AppLanguage.ARABIC),
    )
    assertEquals(
      "ساعتان",
      TaskScheduleFormatters.formatDuration(ar, Duration.ofHours(2), AppLanguage.ARABIC),
    )
    assertEquals(
      "ساعة واحدة و30 دقيقة",
      TaskScheduleFormatters.formatDuration(ar, Duration.ofMinutes(90), AppLanguage.ARABIC),
    )
    assertEquals(
      "15 دقيقة",
      TaskScheduleFormatters.formatDuration(ar, Duration.ofMinutes(15), AppLanguage.ARABIC),
    )
    assertEquals(
      "1h 30m",
      TaskScheduleFormatters.formatDuration(en, Duration.ofMinutes(90), AppLanguage.ENGLISH),
    )
  }
}
