# YAWMI — Unit 4A.2D.3B Final Engineering Report

**Unit:** Unit 4A.2D.3B — Task Detail Reminder Management UI  
**Final Status:** `READY FOR USER ACCEPTANCE`  
**Execution Completed At:** `2026-11-28T03:26:11Z` (`2026-11-27T19:26:11-08:00`, verified with `:app:assembleDebug` at `2026-11-27T19:41:41-08:00`)

---

## A. Executive Summary

- **Unit & Final Status:** Unit **4A.2D.3B (Task Detail Reminder Management UI)** implementation and verification are complete within the strict single-unit scope.
- **Completion Timestamp:**
  - All 21 non-Calendar unit test suites completed at `2026-11-28T03:26:11Z` (`2026-11-27T19:26:11-08:00`).
  - `:app:assembleDebug` verification completed at `2026-11-27T19:41:41-08:00` (`BUILD SUCCESSFUL`, APK up-to-date from `2026-11-28T03:25:55.799663253Z`).
- **Test Completion Summary:**
  - **21 of 21 executed unit test suites passed (`144 / 144` tests PASS, `0` failed, `0` errors, `0` skipped)**.
  - **`Unit4BCalendarTest` was intentionally excluded** per unit constraints and was not modified or executed.
  - **Instrumented / Emulator / `adb` tests were not executed** (no emulator/ADB in execution environment).

---

## B. Modified Files Audit

Only the 7 authorized Unit 4A.2D.3B files were modified or created (timestamps `2026-11-28 03:14:52Z` – `03:21:31Z`):

| # | File Path | Action | Role & Summary of Changes |
|---|-----------|--------|--------------------------|
| 1 | `app/src/main/java/com/example/domain/task/TaskReminderUseCases.kt` | Modified | Added minimal pass-through reactive observation methods `observeTaskReminders(taskId)` and `observeTaskDeliveries(reminderId)` delegating directly to `ReminderSyncRepository`. |
| 2 | `app/src/main/java/com/example/feature/tasks/detail/TaskDetailViewModel.kt` | Modified | Added `TaskReminderWithDelivery`, extended `TaskDetailUiState.Loaded` with defaulted `reminders` and `isReminderManagementEnabled`, added `ReminderSnoozed` / `ReminderActionFailed` events, added trailing defaulted `taskReminderUseCases: TaskReminderUseCases? = null` on `TaskDetailViewModel` and `TaskDetailViewModelFactory` (constructor compatibility preserved), added reactive `remindersFlow`, and added mutation-guarded actions (`addRelativeReminder`, `addExplicitReminder`, `setReminderEnabled`, `updateReminderOffset`, `updateExplicitReminderTime`, `snoozeReminder`, `deleteReminder`). |
| 3 | `app/src/main/res/values/strings.xml` | Modified | Added English (LTR) string resources for the Reminders card, schedule/delivery state labels, diagnostic banners, add/edit/snooze/delete dialogs, and feedback messages. |
| 4 | `app/src/main/res/values-ar/strings.xml` | Modified | Added matching Arabic (RTL) string resources for all reminder management UI labels, state badges, diagnostic banners, dialogs, and feedback messages. |
| 5 | `app/src/main/java/com/example/feature/tasks/detail/TaskDetailScreen.kt` | Modified | Passed `taskReminderUseCases` to `TaskDetailViewModelFactory`, handled `ReminderSnoozed` and `ReminderActionFailed` events in `LaunchedEffect`, and rendered `TaskDetailRemindersCard` below `TaskScheduleCard` when `state.isReminderManagementEnabled` is true. |
| 6 | `app/src/main/java/com/example/feature/tasks/detail/TaskDetailRemindersCard.kt` | Created | Dedicated Material 3 composable card (`task_detail_reminders_card`) supporting reactive reminder rows, relative/explicit add & edit dialogs (`YawmiTimePickerDialog`), enable/pause `Switch`, snooze dialog (`5/10/30/60`m), delete confirmation dialog, `ScheduleState` & latest `DeliveryState` badges, diagnostic callouts, terminal task restrictions, >= `48.dp` touch targets, and `snake_case` `testTag`s. |
| 7 | `app/src/test/java/com/example/Unit4A2D3TaskDetailRemindersTest.kt` | Created | Added 8 Robolectric/Compose & ViewModel unit tests covering live Room updates, Arabic RTL diagnostics, relative/explicit creation, offset/explicit editing, `scheduleRevision` & `DeliveryState.STALE`, enable/pause/delete + outbox staging, snooze isolation, terminal task guards + reopen, and `offsetMinutes == 0` ambiguity. |

### Scope Boundary Confirmation
- **No out-of-scope files were modified in Unit 4A.2D.3B:**
  - `YawmiDatabase.kt`, `YawmiMigrations.kt`, and `app/schemas/com.example.data.local.db.YawmiDatabase/1.json` remain untouched (`2026-11-28 01:15Z–01:17Z`).
  - `CalendarScreen.kt` and `Unit4BCalendarTest.kt` remain untouched (`2026-11-28 01:17:08Z`).
  - `app/src/main/java/com/example/reminder/*` (`AlarmOutboxDispatcher.kt`, `ReminderReconciler.kt`, `AlarmScheduler.kt`, `PendingIntentFactory.kt`, `NotificationDeliveryEngine.kt`, etc.), `RoomReminderSyncRepository.kt`, and `ReminderOutboxCoordinator.kt` remain untouched (`2026-11-28 01:17:08Z`).
  - Recurrence UI (Unit 3C) and Unit 3D were **not** added or modified (`grep` on `feature/tasks/detail/` returned 0 matches for `Recurrence` / `TaskRecurrence`).

---

## C. Implementation Verification

| Capability | Status | Verification Evidence |
|---|---|---|
| Live reminder observation | **IMPLEMENTED** | `TaskDetailViewModel.remindersFlow` observes `observeTaskReminders` + `observeTaskDeliveries`. Verified by `Unit4A2D3TaskDetailRemindersTest.taskDetailReminders_observesLiveUpdatesAndDisplaysDeliveryAndScheduleDiagnosticsInArabicRtl` (Room DAO/repository mutations reflect immediately in UI). |
| Create relative & explicit reminders | **IMPLEMENTED** | `AddTaskReminderDialog` offers presets (`0`, `10`, `30`, `60`m) and explicit time via `YawmiTimePickerDialog` without synthesizing `TaskEntity.startTime`. Verified by `Unit4A2D3TaskDetailRemindersTest.taskDetailReminders_createsRelativeAndExplicitReminders_withoutSynthesizingStartTime`. |
| Edit reminders (relative offset & explicit time) | **IMPLEMENTED** | `EditTaskReminderDialog` updates relative offset or explicit trigger time via `YawmiTimePickerDialog` without inferring preset `0` from `offsetMinutes == 0`. Verified by `Unit4A2D3TaskDetailRemindersTest.taskDetailReminders_editsRelativeOffsetAndExplicitTime_incrementsRevision_marksPendingDeliveryStale`. |
| Enable / Disable (`Switch`) | **IMPLEMENTED** | `TaskDetailReminderRow` `Switch` delegates to `TaskDetailViewModel.setReminderEnabled`. Verified by `Unit4A2D3TaskDetailRemindersTest.taskDetailReminders_togglesEnablePauseAndDeletesWithConfirmation_stagesExpectedOutbox` (ON -> OFF `PAUSED` -> ON `PENDING_SYNC`). |
| Snooze | **IMPLEMENTED** | `SnoozeReminderDialog` (`5`, `10`, `30`, `60`m) delegates to `TaskReminderUseCases.snoozeReminder`. Verified by `Unit4A2D3TaskDetailRemindersTest.taskDetailReminders_snoozeUpdatesOnlyReminderAndOutbox_leavingTaskEntityUnchanged`. |
| Delete with confirmation | **IMPLEMENTED** | Delete button opens confirmation `AlertDialog` (`task_detail_reminder_delete_dialog`); dismissing preserves DB row, confirming deletes it. Verified by `Unit4A2D3TaskDetailRemindersTest.taskDetailReminders_togglesEnablePauseAndDeletesWithConfirmation_stagesExpectedOutbox`. |
| Display `ScheduleState` & `DeliveryState` + diagnostics | **IMPLEMENTED** (Partial enum UI coverage) | UI renders `ScheduleState` badge, latest `DeliveryState` badge, and diagnostic callouts (`SUPPRESSED_PERMISSION`, `SUPPRESSED_CHANNEL`, `FAILED_PERMANENT`, `SCHEDULED_INEXACT`). Verified live in `taskDetailReminders_observesLiveUpdatesAndDisplaysDeliveryAndScheduleDiagnosticsInArabicRtl`. Individual UI rendering of `SCHEDULED_EXACT`, `CONSUMED`, `POSTED_TO_SYSTEM`, `EXPIRED`, `FAILED_RETRYABLE` badges relies on exhaustive `when` mappers and is **NOT VERIFIED** individually in Compose tests. |
| Arabic RTL & English LTR compatibility | **IMPLEMENTED** | Dedicated EN/AR strings and `LocalLayoutDirection` formatting verified via `ProvideYawmiLocale(AppLanguage.ARABIC)` in `taskDetailReminders_observesLiveUpdatesAndDisplaysDeliveryAndScheduleDiagnosticsInArabicRtl` and `AppLanguage.ENGLISH` across the remaining 7 tests in `Unit4A2D3TaskDetailRemindersTest`. |
| Protect `COMPLETED` & `CANCELLED` tasks | **IMPLEMENTED** | Card disables Add, Edit, Snooze, and Switch for non-active tasks and displays `task_detail_reminders_terminal_hint`; ViewModel/use-case rejects mutations with `TaskReminderError.TaskNotActive`. Verified by `taskDetailReminders_terminalTasksRestrictAddEditEnableSnooze_andReopenAllowsReEnabling` and `taskDetailViewModel_rejectsReminderMutationsOnTerminalTask`. |

---

## D. Critical Engineering Verification

1. **`offsetMinutes == 0` ambiguity:**
   - **VERIFIED.** `TaskDetailRemindersCard` displays a neutral label (`task_detail_reminder_zero_offset_label`) rather than inferring explicit vs 0m relative, and `EditTaskReminderDialog` explicitly checks `reminder.offsetMinutes > 0 && reminder.offsetMinutes == offset` so preset `0` is **never** pre-selected from `offsetMinutes == 0`.
   - Test evidence: `Unit4A2D3TaskDetailRemindersTest.taskDetailReminders_zeroOffsetAmbiguity_doesNotInferTypeAndTestsScheduleChangePauseResume` asserts `onNodeWithTag("task_detail_reminder_edit_preset_0").assertIsNotSelected()`.

2. **Task schedule edit followed by Pause / Resume:**
   - **VERIFIED.** Tested in `Unit4A2D3TaskDetailRemindersTest.taskDetailReminders_zeroOffsetAmbiguity_doesNotInferTypeAndTestsScheduleChangePauseResume`:
     - Pausing a `0m` relative reminder (`10:00`, `2026-11-25`) before changing the task schedule from `2026-11-25 10:00` to `2026-11-26 14:00` left the paused reminder unchanged (`2026-11-25`, `10:00`), whereas an enabled `offsetMinutes == 0` reminder on the same task recalculated to `2026-11-26`, `14:00`.
     - Resuming the paused `offsetMinutes == 0` reminder updated `triggerDate` to the task's updated `scheduledDate` (`2026-11-26`) while preserving its stored `triggerTime` (`10:00`) and incrementing `scheduleRevision` to `3L` (consistent with `TaskReminderUseCases.setReminderEnabled` routing `offsetMinutes == 0` through `validateAndResolveExplicitReminder`).

3. **Snooze without altering `TaskEntity.scheduledDate` or `TaskEntity.startTime`:**
   - **VERIFIED.** `Unit4A2D3TaskDetailRemindersTest.taskDetailReminders_snoozeUpdatesOnlyReminderAndOutbox_leavingTaskEntityUnchanged` snoozes a `09:15` reminder by `10` minutes, verifying the reminder updates to `2026-11-25` / `09:25` (`scheduleRevision = 2L`) while `TaskEntity.scheduledDate` remains `"2026-11-25"` and `TaskEntity.startTime` remains `"09:15"`.

4. **`scheduleRevision` incrementation:**
   - **VERIFIED.** Verified across `Unit4A2D3TaskDetailRemindersTest`:
     - Creation starts at `1L` (`createsRelativeAndExplicitReminders_withoutSynthesizingStartTime`).
     - Relative offset edit increments `1L -> 2L`, explicit edit increments `2L -> 3L` (`editsRelativeOffsetAndExplicitTime_incrementsRevision_marksPendingDeliveryStale`).
     - Pause increments `1L -> 2L`, resume increments `2L -> 3L` (`togglesEnablePauseAndDeletesWithConfirmation_stagesExpectedOutbox` and `zeroOffsetAmbiguity`).
     - Snooze increments `1L -> 2L` (`snoozeUpdatesOnlyReminderAndOutbox_leavingTaskEntityUnchanged`).

5. **Marking `PENDING` deliveries as `DeliveryState.STALE`:**
   - **VERIFIED.** Both `editsRelativeOffsetAndExplicitTime_incrementsRevision_marksPendingDeliveryStale` (on offset edit) and `snoozeUpdatesOnlyReminderAndOutbox_leavingTaskEntityUnchanged` (on snooze) seed a `DeliveryState.PENDING` delivery at revision `1L` and verify it transitions to `DeliveryState.STALE`.

6. **Outbox command generation (`CANCEL_ALARM`, `CANCEL_NOTIFICATION`, `SCHEDULE_ALARM`):**
   - **VERIFIED.**
     - In `Unit4A2D3TaskDetailRemindersTest`:
       - Explicit reminder creation stages `OutboxAction.SCHEDULE_ALARM` (`targetScheduleRevision = 1L`).
       - Explicit time edit stages `OutboxAction.SCHEDULE_ALARM` (`targetScheduleRevision = 3L`).
       - Snooze stages `OutboxAction.CANCEL_NOTIFICATION` and `OutboxAction.SCHEDULE_ALARM` (`targetScheduleRevision = 2L`).
       - Delete stages `OutboxAction.CANCEL_ALARM` and `OutboxAction.CANCEL_NOTIFICATION` (`targetScheduleRevision = 4L`).
     - Note: Pause (`setReminderEnabled(..., false)`) staging of `CANCEL_ALARM` is verified in the repository/sync layer (`Unit4ATaskSyncAndRulesTest`); `Unit4A2D3TaskDetailRemindersTest` verified `PAUSED` state and `scheduleRevision = 2L` on toggle OFF.

7. **No direct `AlarmManager` or `NotificationManager` calls from UI / ViewModel:**
   - **VERIFIED.** Static `grep` across `app/src/main/java/com/example/feature/` and `app/src/main/java/com/example/presentation/` returned **0 occurrences** of `AlarmManager`, `NotificationManager`, or `PendingIntent`.

8. **No parallel Scheduler or Dispatcher:**
   - **VERIFIED.** Static `grep` across `feature/` and `presentation/` plus `find` on reminder infrastructure confirmed only the existing `app/src/main/java/com/example/reminder/` classes (`AlarmOutboxDispatcher`, `AlarmScheduler`, `ReminderReconciler`, etc., all unmodified since `2026-11-28 01:17:08Z`) exist. No new scheduler or dispatcher was created.

---

## E. Test Results

All 21 non-Calendar test suites were executed sequentially via `:app:testDebugUnitTest` (completed at `2026-11-28T03:26:11Z`). Exact figures extracted directly from the generated XML reports in `app/build/test-results/testDebugUnitTest/`:

| Test Suite | Tests | Passed | Failed | Errors | Skipped | XML Report Path & Last Modified (UTC) |
|---|---:|---:|---:|---:|---:|---|
| `Unit4A2D3TaskDetailRemindersTest` | 8 | 8 | 0 | 0 | 0 | `app/build/test-results/testDebugUnitTest/TEST-com.example.Unit4A2D3TaskDetailRemindersTest.xml` (`2026-11-28 03:26:11Z`) |
| `Unit3B3TaskDetailTest` | 5 | 5 | 0 | 0 | 0 | `app/build/test-results/testDebugUnitTest/TEST-com.example.Unit3B3TaskDetailTest.xml` (`2026-11-28 03:26:11Z`) |
| `Unit4A2DTimePickerAndEditorTest` | 5 | 5 | 0 | 0 | 0 | `app/build/test-results/testDebugUnitTest/TEST-com.example.Unit4A2DTimePickerAndEditorTest.xml` (`2026-11-28 03:26:11Z`) |
| `Unit4A2DScheduleAndDurationTest` | 10 | 10 | 0 | 0 | 0 | `app/build/test-results/testDebugUnitTest/TEST-com.example.Unit4A2DScheduleAndDurationTest.xml` (`2026-11-28 03:26:11Z`) |
| `Unit4ATaskSyncAndRulesTest` | 10 | 10 | 0 | 0 | 0 | `app/build/test-results/testDebugUnitTest/TEST-com.example.Unit4ATaskSyncAndRulesTest.xml` (`2026-11-28 03:26:11Z`) |
| `Unit4ATimeAndDstTest` | 5 | 5 | 0 | 0 | 0 | `app/build/test-results/testDebugUnitTest/TEST-com.example.Unit4ATimeAndDstTest.xml` (`2026-11-28 03:26:11Z`) |
| `Unit4A2AOutboxDispatcherTest` | 20 | 20 | 0 | 0 | 0 | `app/build/test-results/testDebugUnitTest/TEST-com.example.Unit4A2AOutboxDispatcherTest.xml` (`2026-11-28 03:26:11Z`) |
| `Unit4A2BNotificationDeliveryTest` | 14 | 14 | 0 | 0 | 0 | `app/build/test-results/testDebugUnitTest/TEST-com.example.Unit4A2BNotificationDeliveryTest.xml` (`2026-11-28 03:26:11Z`) |
| `Unit4A2CSystemReconciliationTest` | 16 | 16 | 0 | 0 | 0 | `app/build/test-results/testDebugUnitTest/TEST-com.example.Unit4A2CSystemReconciliationTest.xml` (`2026-11-28 03:26:11Z`) |
| `Unit3B1EditorAndCategoryTest` | 4 | 4 | 0 | 0 | 0 | `app/build/test-results/testDebugUnitTest/TEST-com.example.Unit3B1EditorAndCategoryTest.xml` (`2026-11-28 03:26:11Z`) |
| `Unit3B2TaskQueryRulesTest` | 3 | 3 | 0 | 0 | 0 | `app/build/test-results/testDebugUnitTest/TEST-com.example.Unit3B2TaskQueryRulesTest.xml` (`2026-11-28 03:26:11Z`) |
| `Unit3B2TasksScreenTest` | 3 | 3 | 0 | 0 | 0 | `app/build/test-results/testDebugUnitTest/TEST-com.example.Unit3B2TasksScreenTest.xml` (`2026-11-28 03:26:11Z`) |
| `Unit3B4TodayDashboardRulesTest` | 3 | 3 | 0 | 0 | 0 | `app/build/test-results/testDebugUnitTest/TEST-com.example.Unit3B4TodayDashboardRulesTest.xml` (`2026-11-28 03:26:11Z`) |
| `Unit3B4TodayLifecycleAndTimeTest` | 7 | 7 | 0 | 0 | 0 | `app/build/test-results/testDebugUnitTest/TEST-com.example.Unit3B4TodayLifecycleAndTimeTest.xml` (`2026-11-28 03:26:11Z`) |
| `Unit3B4TodayScreenTest` | 4 | 4 | 0 | 0 | 0 | `app/build/test-results/testDebugUnitTest/TEST-com.example.Unit3B4TodayScreenTest.xml` (`2026-11-28 03:26:11Z`) |
| `Unit3ATaskPersistenceTest` | 1 | 1 | 0 | 0 | 0 | `app/build/test-results/testDebugUnitTest/TEST-com.example.Unit3ATaskPersistenceTest.xml` (`2026-11-28 03:26:11Z`) |
| `Unit3ATaskRulesTest` | 6 | 6 | 0 | 0 | 0 | `app/build/test-results/testDebugUnitTest/TEST-com.example.Unit3ATaskRulesTest.xml` (`2026-11-28 03:26:11Z`) |
| `Phase2PersistenceTest` | 12 | 12 | 0 | 0 | 0 | `app/build/test-results/testDebugUnitTest/TEST-com.example.Phase2PersistenceTest.xml` (`2026-11-28 03:26:11Z`) |
| `Phase1FoundationTest` | 6 | 6 | 0 | 0 | 0 | `app/build/test-results/testDebugUnitTest/TEST-com.example.Phase1FoundationTest.xml` (`2026-11-28 03:26:11Z`) |
| `ExampleRobolectricTest` | 1 | 1 | 0 | 0 | 0 | `app/build/test-results/testDebugUnitTest/TEST-com.example.ExampleRobolectricTest.xml` (`2026-11-28 03:26:11Z`) |
| `ExampleUnitTest` | 1 | 1 | 0 | 0 | 0 | `app/build/test-results/testDebugUnitTest/TEST-com.example.ExampleUnitTest.xml` (`2026-11-28 03:26:11Z`) |
| **Total (21 Executed Suites)** | **144** | **144** | **0** | **0** | **0** | All 21 XML files updated at `2026-11-28 03:26:11Z` |

- **Excluded Suite:** `Unit4BCalendarTest` (excluded per constraint; no XML report generated).

---

## F. Build Verification

- **Command:** `gradle :app:assembleDebug`
- **Result:** **`BUILD SUCCESSFUL`** (39 actionable tasks: 39 up-to-date, executed at `2026-11-27T19:41:41-08:00`)
- **APK Path:** `app/build/outputs/apk/debug/app-debug.apk`
- **APK Size & Timestamp:** `21,096,291` bytes, created/updated at `2026-11-28 03:25:55.799663253 +0000` (`2026-11-27T19:25:55-08:00`)
- **Freshness Confirmation:** The latest production source modification in Unit 4A.2D.3B (`TaskDetailRemindersCard.kt`) occurred at `2026-11-28 03:19:14Z`, so `app-debug.apk` (`03:25:55Z`, verified up-to-date by `:app:assembleDebug` at `03:41:41Z`) incorporates all Unit 4A.2D.3B changes.

---

## G. Database Integrity

- **`CURRENT_DB_VERSION`:** `1` (`YawmiDatabase.kt` lines 59, 86)
- **`identityHash`:** `"37c351a60c04e881c6bd50156833f191"` (`app/schemas/com.example.data.local.db.YawmiDatabase/1.json` line 5)
- **Room Schema v1 Unchanged:** Confirmed via file timestamps (`YawmiDatabase.kt` at `01:17:08Z`, `1.json` at `01:15:54Z`) and verified by passing test `Unit4A2DScheduleAndDurationTest.roomSchemaV1AndIdentityHash_remainCompletelyUnchanged`.
- **Migrations Unchanged:** `YawmiMigrations.kt` (`01:17:08Z`) remains `ALL_MIGRATIONS = emptyArray()`. No migrations were created or modified.

---

## H. Known Issues and Limitations

1. **Zero-Minute (`offsetMinutes == 0`) Ambiguity (Schema v1 Constraint):**
   - Schema v1 (`task_reminders`) does not store a separate `reminderType` column distinguishing a `0m` relative reminder from an explicit-time reminder (both store `offsetMinutes = 0`).
   - Consequently:
     - `TaskDetailRemindersCard` displays a neutral label (`task_detail_reminder_zero_offset_label`) and `EditTaskReminderDialog` never pre-selects the `0m` preset solely from `offsetMinutes == 0`.
     - Under existing domain rules (`RoomTaskRepository.update()`), enabled `offsetMinutes == 0` reminders recalculate to the task's new `startTime` when a timed task's schedule changes.
     - Under `TaskReminderUseCases.setReminderEnabled(..., true)`, resuming a paused `offsetMinutes == 0` reminder routes through the explicit branch, applying the updated `task.scheduledDate` while preserving the stored `triggerTime` rather than recalculating to the new `startTime`.
2. **Untested / Partially Verified Cases:**
   - Instrumented (`androidTest`), Android Emulator, and `adb` runtime tests were not run (no emulator/ADB available in environment), so live Android OS `AlarmManager` firing and `NotificationManager` posting on an interactive device are **NOT VERIFIED** in UI E2E.
   - `Unit4BCalendarTest` was **excluded** (documented Robolectric hang) and remains unmodified.
   - Individual Compose badge rendering for `SCHEDULED_EXACT`, `CONSUMED`, `POSTED_TO_SYSTEM`, `EXPIRED`, `FAILED_RETRYABLE` and specific validation error snackbars (e.g., `TriggerInPast`, `MissingScheduledDate`) were not individually exercised in `Unit4A2D3TaskDetailRemindersTest` (though underlying domain validation is tested in `Unit4ATaskSyncAndRulesTest`).
3. **Standalone `TaskDetailScreen` Snackbar Overlay Behavior on Compact Viewports:**
   - When `TaskDetailScreen` is rendered without an external `onShowSnackbar` callback, its fallback `localSnackbarHostState` overlays the bottom of `Scaffold` during `SnackbarDuration.Short` (4s), which on compact 320×470 viewports can intercept pointer clicks on a control immediately scrolled to the bottom edge. In production (`YawmiShell.kt`), `onShowSnackbar` is always provided by the outer app shell, and Compose tests follow `Unit3B3TaskDetailTest`'s pattern of passing `onShowSnackbar`.

---

## I. Final Acceptance Recommendation

**Recommendation:** **`READY FOR USER ACCEPTANCE`**

### Reasons
1. All 7 Unit 4A.2D.3B files strictly satisfy the authorized single-unit scope (no Calendar, Schema v1/migration, dispatcher/reconciler, or unauthorized Unit 3C/3D changes).
2. All 21 executed unit test suites (`144 / 144` tests, including all 8 new tests in `Unit4A2D3TaskDetailRemindersTest`) passed with 0 failures, 0 errors, and 0 skipped after the final code edit.
3. `:app:assembleDebug` succeeded cleanly (`BUILD SUCCESSFUL`) with `app-debug.apk` reflecting the latest Unit 4A.2D.3B changes.
4. Room Schema v1 (`CURRENT_DB_VERSION = 1`, `identityHash = "37c351a60c04e881c6bd50156833f191"`) and all engineering/architecture boundaries are verified intact, with all known Schema v1 (`offsetMinutes == 0`) and environment constraints transparently documented.

---

**Stop Notice:** Unit 4A.2D.3B is complete. **Unit 3C** and **Unit 3D** have **NOT** started. Awaiting user review and acceptance.
