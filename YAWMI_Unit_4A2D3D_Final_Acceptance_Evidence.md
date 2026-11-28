# YAWMI — Unit 4A.2D.3D: Batch 1 Final Acceptance Evidence Audit

**Checkpoint:** `DEF01_DEF02_DEF03_FINAL_EVIDENCE_REVIEW`  
**Audit Mode:** STRICT READ-ONLY (Zero source/test/resource/schema edits; zero test re-runs)  
**Reference Report:** `YAWMI_Unit_4A2D3D_Batch1_Final_Report.md`  
**Audit Timestamp:** `2026-11-28T01:05:30-08:00` (`2026-11-28T09:05:30Z`)

---

## A. DEF-01 — مراجعة كل عملية على حدة (Domain-Layer Terminal Guard Audit)

تم فحص الشيفرة المصدرية الفعلية لكل عملية من العمليات التسع على مستوى طبقة المجال (`TaskUseCases.kt`, `TaskReminderUseCases.kt`, `TaskReminderRules.kt`) مع التحقق من نتائج ملفات XML الفعلية في `app/build/test-results/testDebugUnitTest/`:

| # | العملية | الملف ورقم السطر | جلب حالة المهمة الحالية من المصدر الموثوق (`TaskRepository`) | الرفض عند `COMPLETED` أو `CANCELLED` | الرفض قبل تعديل DB أو Outbox | الاختبار الذي يثبت ذلك ونتيجته (XML) |
|---|---|---|---|---|---|---|
| 1 | `TaskUseCases.saveTaskRecurrence()` | `TaskUseCases.kt:163–200` (جلب: `168`، حراسة: `171–180`، كتابة: `198`) | **نعم** — `taskRepository.getById(taskId)` (سطر `168`) | **نعم** — `!TaskReminderRules.isTaskActive(existing.status)` يعيد `Failure(InvalidStatusTransition)` | **نعم** — الرفض في `171–180` قبل `upsertTaskRecurrence` في `198` | `Unit4A2D3DBatch1HardeningTest.def01_completedTask_rejectsAllRecurrenceAndReminderMutations` (**PASSED**)، `def01_cancelledTask_rejectsAllRecurrenceAndReminderMutations` (**PASSED**)، `Unit4A2D3TaskDetailRecurrenceTest.taskDetailViewModel_rejectsRecurrenceMutationsOnCompletedAndCancelledTasks` (**PASSED**) |
| 2 | `TaskUseCases.removeTaskRecurrence()` | `TaskUseCases.kt:205–222` (جلب: `207`، حراسة: `210–219`، حذف: `221`) | **نعم** — `taskRepository.getById(taskId)` (سطر `207`) | **نعم** — `!TaskReminderRules.isTaskActive(existing.status)` يعيد `Failure(InvalidStatusTransition)` | **نعم** — الرفض في `210–219` قبل `deleteTaskRecurrence` في `221` | `Unit4A2D3DBatch1HardeningTest.def01_completedTask_rejectsAllRecurrenceAndReminderMutations` (**PASSED**)، `def01_cancelledTask_rejectsAllRecurrenceAndReminderMutations` (**PASSED**)، `Unit4A2D3TaskDetailRecurrenceTest.taskDetailViewModel_rejectsRecurrenceMutationsOnCompletedAndCancelledTasks` (**PASSED**) |
| 3 | `TaskReminderUseCases.createRelativeReminder()` | `TaskReminderUseCases.kt:23–47` (جلب: `28`، تفويض: `32`، إنشاء: `38`) + `TaskReminderRules.kt:42–48` | **نعم** — `taskRepository.getById(taskId)` (سطر `28`) | **نعم** — `validateAndResolveRelativeReminder` يفحص `!isTaskActive(task.status)` (`TaskReminderRules.kt:46–48`) ويعيد `Failure(TaskNotActive)` | **نعم** — الرفض في `32–35` قبل `createTaskReminder` (DB + Outbox) في `38` | `Unit4A2D3DBatch1HardeningTest.def01_completedTask_rejectsAllRecurrenceAndReminderMutations` (**PASSED**)، `def01_cancelledTask_rejectsAllRecurrenceAndReminderMutations` (**PASSED**)، `Unit4A2D3TaskDetailRemindersTest.taskDetailViewModel_rejectsReminderMutationsOnTerminalTask` (**PASSED**) |
| 4 | `TaskReminderUseCases.createExplicitReminder()` | `TaskReminderUseCases.kt:49–81` (جلب: `55`، تفويض: `61`، إنشاء: `72`) + `TaskReminderRules.kt:74–81` | **نعم** — `taskRepository.getById(taskId)` (سطر `55`) | **نعم** — `validateAndResolveExplicitReminder` يفحص `!isTaskActive(task.status)` (`TaskReminderRules.kt:79–81`) ويعيد `Failure(TaskNotActive)` | **نعم** — الرفض في `58–69` قبل `createTaskReminder` (DB + Outbox) في `72` | `Unit4A2D3DBatch1HardeningTest.def01_completedTask_rejectsAllRecurrenceAndReminderMutations` (**PASSED**)، `def01_cancelledTask_rejectsAllRecurrenceAndReminderMutations` (**PASSED**)، `Unit4ATaskSyncAndRulesTest.untimedAndTerminalTasks_rejectRelativeOrSynthesizedDefaults_whileExplicitUntimedPostponesCleanly` (**PASSED**) |
| 5 | `TaskReminderUseCases.setReminderEnabled()` | `TaskReminderUseCases.kt:83–139` (جلب: `89`، حراسة: `95–97`، تعديل: `101`, `130`) | **نعم** — `taskRepository.getById(taskId)` (سطر `89`) | **نعم** — `!TaskReminderRules.isTaskActive(task.status)` (أسطر `95–97`) **قبل** فرع `if (!enabled)` في سطر `99` | **نعم** — الرفض في `95–97` قبل `updateTaskReminderSchedule` في `101` و`130` (يمنع خفض `CANCELLED` إلى `PAUSED` وإيداع Outbox) | `Unit4A2D3DBatch1HardeningTest.def01_completedTask_rejectsAllRecurrenceAndReminderMutations` (اختبر `enabled=true` و`enabled=false` — **PASSED**)، `def01_cancelledTask_rejectsAllRecurrenceAndReminderMutations` (`enabled=false` — **PASSED**)، `Unit4A2D3TaskDetailRemindersTest.taskDetailViewModel_rejectsReminderMutationsOnTerminalTask` (`CANCELLED` مع `enabled=true` — **PASSED**) |
| 6 | `TaskReminderUseCases.updateReminderOffset()` | `TaskReminderUseCases.kt:141–169` (جلب: `147`، تفويض: `154`، تعديل: `160`) + `TaskReminderRules.kt:42–48` | **نعم** — `taskRepository.getById(taskId)` (سطر `147`) | **نعم** — `validateAndResolveRelativeReminder` يفحص `!isTaskActive(task.status)` (`TaskReminderRules.kt:46–48`) ويعيد `Failure(TaskNotActive)` | **نعم** — الرفض في `154–157` قبل `updateTaskReminderSchedule` (DB + Outbox) في `160` | `Unit4A2D3DBatch1HardeningTest.def01_completedTask_rejectsAllRecurrenceAndReminderMutations` (**PASSED**)، `def01_cancelledTask_rejectsAllRecurrenceAndReminderMutations` (**PASSED**) |
| 7 | `TaskReminderUseCases.updateExplicitReminderTime()` | `TaskReminderUseCases.kt:171–207` (جلب: `178`، تفويض: `187`، تعديل: `198`) + `TaskReminderRules.kt:74–81` | **نعم** — `taskRepository.getById(taskId)` (سطر `178`) | **نعم** — `validateAndResolveExplicitReminder` يفحص `!isTaskActive(task.status)` (`TaskReminderRules.kt:79–81`) ويعيد `Failure(TaskNotActive)` | **نعم** — الرفض في `184–195` قبل `updateTaskReminderSchedule` (DB + Outbox) في `198` | `Unit4A2D3DBatch1HardeningTest.def01_completedTask_rejectsAllRecurrenceAndReminderMutations` (**PASSED**)، `def01_cancelledTask_rejectsAllRecurrenceAndReminderMutations` (**PASSED**) |
| 8 | `TaskReminderUseCases.snoozeReminder()` | `TaskReminderUseCases.kt:213–245` (جلب: `222`، حراسة: `224–226`، غفوة: `238`) | **نعم** — `taskRepository.getById(taskId)` (سطر `222`) | **نعم** — `!TaskReminderRules.isTaskActive(task.status)` في الأسطر `224–226` | **نعم** — الرفض في `224–226` (وقبله تحقق `snoozeMinutes < 1` في `218`) قبل `snoozeTaskReminder` في `238` | `Unit4A2D3DBatch1HardeningTest.def01_completedTask_rejectsAllRecurrenceAndReminderMutations` (**PASSED**)، `def01_cancelledTask_rejectsAllRecurrenceAndReminderMutations` (**PASSED**)، `Unit4A2D3TaskDetailRemindersTest.taskDetailViewModel_rejectsReminderMutationsOnTerminalTask` (**PASSED**) |
| 9 | `TaskReminderUseCases.deleteReminder()` | `TaskReminderUseCases.kt:250–267` (جلب التذكير: `252`، جلب المهمة: `255`، حراسة: `257–259`، حذف: `261`) | **نعم** — يجلب `reminder` via `getTaskReminderById` (`252`) ثم `taskRepository.getById(reminder.taskId)` (`255`) | **نعم** — `!TaskReminderRules.isTaskActive(task.status)` في الأسطر `257–259` | **نعم** — الرفض في `257–259` قبل `deleteTaskReminder` (DB + Outbox) في `261` | `Unit4A2D3DBatch1HardeningTest.def01_completedTask_rejectsAllRecurrenceAndReminderMutations` (مع التحقق من ثبات عدد عناصر Outbox — **PASSED**)، `def01_cancelledTask_rejectsAllRecurrenceAndReminderMutations` (مع ثبات Outbox — **PASSED**) |

**نتيجة فحص `MISSING GUARD`:** **لا توجد أي عملية بدون حراسة (`0 MISSING GUARD`)** عبر العمليات التسع جميعها. كما توجد حراسات مكمّلة (Defense-in-Depth) في `TaskEditorSheet.kt:491` وفي `TaskDetailRemindersCard.kt` و`TaskDetailRecurrenceCard.kt` و`TaskEditorExtrasSections.kt`.

---

## B. DEF-02 — مراجعة خوارزمية الصفر (Zero-Offset Heuristic & Schema v1 Boundaries)

تم فحص المسارات الفعلية في:
- `TaskReminderRules.resolveSyncActionOnTaskScheduleChange` (`TaskReminderRules.kt:108–203`)، المستدعاة داخل معاملة `RoomTaskRepository.update` (`RoomTaskRepository.kt:71–76`) عند `TaskUseCases.updateTask()` و`TaskUseCases.postponeTask()`.
- `TaskReminderRules.resolveNextOccurrenceReminderTrigger` (`TaskReminderRules.kt:209–245`)، المستدعاة داخل معاملة `RoomTaskRepository.completeTaskWithRecurrence` (`RoomTaskRepository.kt:247–252`) مع تمرير `previousTaskStartTime = completedTask.startTime`.

تُحسب علامة الربط في `resolveSyncActionOnTaskScheduleChange` كالتالي (`133–139`):
```kotlin
val previousDateMatchesReminder = previousTask.scheduledDate == reminder.triggerDate
val isAnchoredAtStartZeroOffset =
  reminder.offsetMinutes == 0 &&
    previousTask.startTime != null &&
    previousTask.scheduledDate != null &&
    reminder.triggerTime == previousTask.startTime &&
    previousDateMatchesReminder
```
وفي `resolveNextOccurrenceReminderTrigger` (`227–230`):
```kotlin
val isAnchoredAtStartZeroOffset =
  nextStartTime != null &&
    previousTaskStartTime != null &&
    previousReminder.triggerTime == previousTaskStartTime
```

### شرح الحالات الأربع والأدلة الاختبارية

1. **تذكير صريح لا يساوي موعد بدء المهمة السابق** (`offsetMinutes == 0` مع `previousTask.startTime == null` أو `reminder.triggerTime != previousTask.startTime` أو `reminder.triggerDate != previousTask.scheduledDate`):
   - **السلوك في `resolveSyncActionOnTaskScheduleChange`:** تكون `isAnchoredAtStartZeroOffset = false`.
     - عند تعديل `startTime` فقط (أو إضافته لمهمة بتاريخ دون وقت): `scheduledDate` لم يتغير ← يعيد `Unchanged` (لا يبدّل `triggerTime` ولا يصدر Outbox).
     - عند تعديل `scheduledDate` (سواء عبر `updateTask` أو `postponeTask`) أو مسح `startTime` (`updatedTime == null` مع الإبقاء على التاريخ): إذا تغيّر `scheduledDate` وطابق `reminder.triggerDate == previousTask.scheduledDate` ينتقل `triggerDate` إلى `updatedDate` مع **الحفاظ على `reminder.triggerTime` الصريح** دون مساس؛ وإذا لم يتغير التاريخ يبقى التذكير مفعلاً (`Unchanged`) ولا يتوقف. أما إذا مُسح `scheduledDate` كلياً (`updatedDate == null`) فيتوقف (`PauseMissingReference`).
   - **السلوك في `resolveNextOccurrenceReminderTrigger`:** تكون `isAnchoredAtStartZeroOffset = false` ← ينشئ التذكير على `nextTask.scheduledDate` محتفظاً بـ `previousReminder.triggerTime` و`offsetMinutes = 0` (`239–244`).
   - **الأدلة الاختبارية (PASSED):**
     - `Unit4A2D3DBatch1HardeningTest.def02_explicitReminderOnTimedTask_survivesTimeDateAndClearEdits_whileRelativeRecalculates` (تعديل الوقت `10:00→11:30` يحفظ `08:00` بلا Outbox؛ تعديل التاريخ+الوقت ينقله إلى `2026-11-29 08:00`؛ مسح `startTime` يبقيه مفعّلاً على `08:00`).
     - `Unit4A2D3DBatch1HardeningTest.def02_postponeAndUntimedToTimedTask_preserveExplicitTime_andZeroOffsetStartUpdate` (إضافة `startTime=10:00` لمهمة غير محددة الوقت تحفظ `08:00`؛ التأجيل مع/بدون `clearTimeWindow` يحفظ `08:00`؛ الإكمال المتكرر ينسخ `"08:00"`).
     - `Unit4A2D3TaskDetailRemindersTest.taskDetailReminders_zeroOffsetAmbiguity_doesNotInferTypeAndTestsScheduleChangePauseResume` (`08:15` على مهمة `10:00` ينتقل إلى `2026-11-26 08:15` عند تغيير المهمة إلى `14:00`).
     - `Unit4ATaskSyncAndRulesTest.untimedAndTerminalTasks_rejectRelativeOrSynthesizedDefaults_whileExplicitUntimedPostponesCleanly` (تأجيل مهمة غير محددة الوقت يحفظ `"18:15"`).

2. **تذكير يساوي موعد البدء السابق** (`offsetMinutes == 0` و`previousTask.startTime != null` و`reminder.triggerTime == previousTask.startTime` و`reminder.triggerDate == previousTask.scheduledDate`):
   - **السلوك في `resolveSyncActionOnTaskScheduleChange`:** تكون `isAnchoredAtStartZeroOffset = true`.
     - إذا أُزيل وقت البدء (`updatedTime == null`): يعيد `PauseMissingReference` (`147–148`).
     - إذا بقي/تغيّر وقت البدء (`updatedTime != null`): يعيد جدولة التذكير إلى `(updatedDate, updatedTime)` (`181–191`).
   - **السلوك في `resolveNextOccurrenceReminderTrigger`:** إذا وُجد `nextStartTime` وطابق `previousReminder.triggerTime == previousTaskStartTime` يعيد `(nextDate, nextStartTime, 0)` (`227–237`).
   - **الأدلة الاختبارية (PASSED):**
     - `Unit4A2D3DBatch1HardeningTest.def02_postponeAndUntimedToTimedTask_preserveExplicitTime_andZeroOffsetStartUpdate`: القسم B (تذكير `0m` عند `10:00` ينتقل عند `postponeTask` إلى `2026-12-01 10:00` ويتوقف `PAUSED` عند `clearTimeWindow = true`)، والقسم D (تذكير `0m` عند `12:00` ينسخ `"2026-11-29 12:00"` عند إكمال المهمة المتكررة).
     - *ملاحظة أمانة اختبارية:* في `RoomTaskRepository.completeTaskWithRecurrence` (سطر `217`) تُنسخ قيمة `completedTask.startTime` حرفياً إلى `nextTask.startTime`، ولذلك في القسم D تتطابق القيمتان (`"12:00"`)؛ أما اتباع `updatedTask.startTime` المختلف عن `previousTask.startTime` في `resolveSyncActionOnTaskScheduleChange` فمُثبت اختبارياً في القسم B (وتبديل `startTime = null`)، بينما في `resolveNextOccurrenceReminderTrigger` يتبع `nextTask.startTime` نصياً في السطر `234`.

3. **تذكير نسبي بإزاحة موجبة (`offsetMinutes > 0`):**
   - **السلوك:** يتحقق مباشرة من `reminder.offsetMinutes > 0` (`161–174` و`217–225`).
     - يتطلب وجود `scheduledDate` و`startTime` معاً؛ وعند مسح أي منهما يعود `PauseMissingReference` (`116, 121–123`) في `resolveSyncActionOnTaskScheduleChange` أو `null` (`218`) في `resolveNextOccurrenceReminderTrigger`.
     - عند توفرهما يحسب `LocalDateTime.of(date, startTime).minusMinutes(offsetMinutes)` بدقة بما فيها العبور العكسي لمنتصف الليل.
   - **الأدلة الاختبارية (PASSED):**
     - `Unit4A2D3DBatch1HardeningTest.def02_explicitReminderOnTimedTask_survivesTimeDateAndClearEdits_whileRelativeRecalculates` (`15m`: `10:00→11:30` يصبح `11:15` + إبطال `PENDING` إلى `STALE` + `SCHEDULE_ALARM rev=2`؛ و`14:00` يصبح `13:45`؛ ومسح `startTime` يوقفه `PAUSED`).
     - `Unit4ATaskSyncAndRulesTest.editOrPostponeTimedTask_recalculatesReminders_marksPendingDeliveriesStale_andStagesSchedule` (`15m` عبر منتصف الليل `00:10 → 23:55` اليوم السابق + التأجيل).
     - `Unit4ATaskSyncAndRulesTest.clearingTaskDateOrStartTime_pausesEnabledReminders_andStagesCancels` (`30m` ← `PAUSED`).
     - `Unit4ATaskSyncAndRulesTest.completingRecurringTask_keepsCompletedHistory_createsNextTaskRecurrenceReminderAndGoalLinks` (`15m` على `11:00` ← `10:45` للنسخة التالية).

4. **حالة التذكير صفر دقيقة التي لا يمكن حسم نوعها من Schema v1:**
   - **الحد البنيوي الصريح:** لا يتضمن جدول `task_reminders` في Schema v1 (`1.json`) أي عمود لنوع التذكير (`isRelative` / `reminderType`)؛ إذ يُخزّن كل من «عند وقت البدء (`offsetMinutes = 0`)» و«الوقت الصريح (`createExplicitReminder`)» بالقيمة نفسها `offsetMinutes = 0`.
   - بناءً عليه، إذا أنشأ المستخدم **تذكيراً صريحاً** (`createExplicitReminder`) وحدد له وقتاً يساوي بالضبط `previousTask.startTime` على `previousTask.scheduledDate` نفسه، تصبح سجلات قاعدة البيانات متطابقة حرفياً مع تذكير «عند وقت البدء»، وتفشل الخوارزمية حتماً في التمييز فتعامله كـ `isAnchoredAtStartZeroOffset = true` (يتحرك مع `startTime` أو يتوقف عند مسحه). وبالمقابل، إذا غُيّر وقت تذكير «عند وقت البدء» بالـ Snooze إلى وقت مختلف عن `previousTask.startTime` فإن `isAnchoredAtStartZeroOffset` يصبح `false` ويعامل كوقت مستقل غير مربوط بـ `startTime`.
   - **التغطية والصدق الهندسي:** الخوارزمية **لا تدّعي ضمان التمييز** في هذه الحالة المستحيلة بياناتياً على Schema v1، ويؤكد اختبار `Unit4A2D3TaskDetailRemindersTest.taskDetailReminders_zeroOffsetAmbiguity_doesNotInferTypeAndTestsScheduleChangePauseResume` (`PASSED`) أيضاً أن واجهة التعديل لا تخمن النوع من `offsetMinutes == 0`.

---

## C. DEF-03 — مراجعة الحالة الموقوفة (`PAUSED`) في `TaskEditorSheet`

جميع البنود الخمسة مثبتة باختبارات Compose + Room + Outbox الفعلية في `Unit4A2D3DBatch1HardeningTest`:

| الشرط المطلوب إثباته | الاختبار المحدد والنتيجة (XML) | الأسطر والتحقق الدقيق |
|---|---|---|
| **1. تغيير التاريخ فقط مع استمرار `PAUSED`** | `def03_taskEditorSheet_pausedReminderStaysPausedAcrossScheduleEdits_untilExplicitReEnable_enLtr` (**PASSED**) + `def03_taskEditorSheet_pausedReminderVisibilityAndExplicitReEnable_arabicRtl` (**PASSED**) | EN (`829–840`): اختيار `task_date_tomorrow` ← حفظ (`saveCount == 1`) ← تحقق من `isEnabled == false` و`scheduleState == ReminderScheduleState.PAUSED`. AR (`965–970`): اختيار `task_date_tomorrow` ← الشارة تبقى `"متوقف مؤقتاً"`. |
| **2. تغيير وقت البداية فقط مع استمرار `PAUSED`** | `def03_taskEditorSheet_pausedReminderStaysPausedAcrossScheduleEdits_untilExplicitReEnable_enLtr` (**PASSED**) | EN (`842–855`): تغيير `task_start_time_btn` إلى `14:00` دون ضغط `task_reminder_reenable_0` ← الشارة تبقى `"Paused"` ← حفظ (`saveCount == 2`) ← `isEnabled == false` و`scheduleState == PAUSED`. |
| **3. تغيير التاريخ ووقت البداية معاً مع استمرار `PAUSED`** | `def03_taskEditorSheet_pausedReminderStaysPausedAcrossScheduleEdits_untilExplicitReEnable_enLtr` (**PASSED**) | EN (`857–870`): اختيار `task_date_today` + `16:00` دون ضغط Re-enable ← الشارة تبقى `"Paused"` ← حفظ (`saveCount == 3`) ← `isEnabled == false` و`scheduleState == PAUSED`. |
| **4. عدم إصدار `SCHEDULE_ALARM` تلقائياً** | `def03_taskEditorSheet_pausedReminderStaysPausedAcrossScheduleEdits_untilExplicitReEnable_enLtr` (**PASSED**) | EN (`838–839`, `853–854`, `868–869`): التحقق بعد كل حفظ من الحفظات الثلاث عبر `assertFalse(addedOutbox.any { it.action == OutboxAction.SCHEDULE_ALARM })`. |
| **5. إعادة التفعيل فقط بعد فعل صريح من المستخدم** | `def03_taskEditorSheet_pausedReminderStaysPausedAcrossScheduleEdits_untilExplicitReEnable_enLtr` (**PASSED**) + `def03_taskEditorSheet_pausedReminderVisibilityAndExplicitReEnable_arabicRtl` (**PASSED**) | EN (`872–891`): النقر الصريح على `task_reminder_reenable_0` ← الشارة تتغير إلى `"Will re-enable"` ← حفظ (`saveCount == 4`) ← `isEnabled == true`, `PENDING_SYNC`, `15:30`, وإيداع `SCHEDULE_ALARM`. AR (`971–983`): نقر `task_reminder_reenable_0` ← `"سيُعاد تفعيله"` ← حفظ ← استعادة `"08:30"` على `"2026-11-29"`. |

---

## D. تدقيق أدلة الاختبارات التسعة في `Unit4A2D3DBatch1HardeningTest`

من ملف XML الفعلي `TEST-com.example.Unit4A2D3DBatch1HardeningTest.xml` (`timestamp = 2026-11-28T08:30:24.389Z`, `tests = 9`, `failures = 0`, `errors = 0`, `skipped = 0`):

| # | اسم الاختبار الكامل | الزمن (s) | النتيجة | العيب | السيناريو المغطى بدقة |
|---:|---|---:|---|---|---|
| 1 | `def01_completedTask_rejectsAllRecurrenceAndReminderMutations` | 0.209 | **PASSED** | `DEF-01` | رفض جميع عمليات التكرار والتذكير العشر (بما فيها `setReminderEnabled(true)` و`setReminderEnabled(false)` و`deleteReminder` و`snoozeReminder`) على مهمة `COMPLETED`؛ عدم خفض `CANCELLED` إلى `PAUSED` وثبات Outbox. |
| 2 | `def01_cancelledTask_rejectsAllRecurrenceAndReminderMutations` | 0.428 | **PASSED** | `DEF-01` | رفض تعديلات التكرار والتذكيرات (`saveTaskRecurrence`, `removeTaskRecurrence`, `createRelativeReminder`, `createExplicitReminder`, `setReminderEnabled(false)`, `update*`, `snoozeReminder`, `deleteReminder`) على مهمة `CANCELLED` وبقاء الحالة `CANCELLED` وثبات Outbox. |
| 3 | `def01_pendingAndInProgressTasks_preserveNormalRecurrenceAndReminderMutations` | 0.271 | **PASSED** | `DEF-01` | اختبار الحماية الموجب: التأكد من أن المهام النشطة (`PENDING` و`IN_PROGRESS`) ما زالت تسمح بإنشاء/تعديل/حذف التكرار والتذكيرات وتعديل المهمة طبيعياً. |
| 4 | `def01_taskDetailAndEditorUi_lockRecurrenceAndRemindersOnTerminalTasks_enAndAr` | 0.836 | **PASSED** | `DEF-01` | التحقق من تعريب خطأ `InvalidStatusTransition` (`task_error_terminal_locked`) في EN/AR وتعطيل أزرار حذف التذكير والتكرار داخل `TaskDetailScreen` لمهمة `COMPLETED`. |
| 5 | `def01_taskEditorSheet_locksRecurrenceAndRemindersOnCancelledTask_arabicRtl` | 1.988 | **PASSED** | `DEF-01` | فتح `TaskEditorSheet` بالعربية RTL لمهمة `CANCELLED`: ظهور تلميحات القفل وتعطيل عناصر التكرار والتذكيرات، مع السماح بتعديل العنوان وحفظه دون لمس سجل التكرار أو التذكير الملغى. |
| 6 | `def02_explicitReminderOnTimedTask_survivesTimeDateAndClearEdits_whileRelativeRecalculates` | 0.270 | **PASSED** | `DEF-02` | بقاء التذكير الصريح `08:00` على مهمة `10:00` دون استبدال ولا Outbox زائد عند تعديل وقت البدء (`11:30`) أو التاريخ والوقت (`2026-11-29 14:00`) أو مسح وقت البدء، مع إعادة حساب التذكير النسبي `15m` وإبطال تسليم `PENDING` إلى `STALE` ثم إيقافه عند مسح `startTime`. |
| 7 | `def02_postponeAndUntimedToTimedTask_preserveExplicitTime_andZeroOffsetStartUpdate` | 0.518 | **PASSED** | `DEF-02` | أربعة مسارات: (A) إضافة `startTime` لمهمة غير محددة الوقت يحفظ الصريح `08:00`؛ (B) التأجيل يحفظ `08:00` ويحرك المربوط `0m` و`15m`؛ (C) التأجيل مع `clearTimeWindow = true` يبقي `08:00` مفعّلاً ويوقف `0m` و`15m`؛ (D) إكمال مهمة متكررة يحفظ `08:00` والـ `0m` عند `12:00` في النسخة التالية. |
| 8 | `def03_taskEditorSheet_pausedReminderStaysPausedAcrossScheduleEdits_untilExplicitReEnable_enLtr` | 2.401 | **PASSED** | `DEF-03` | اختبار UI + Room + Outbox كامل بالإنجليزية LTR: إظهار ملخص وشارة `Paused` وتلميح استعادة الوقت، وثبات `PAUSED` بلا `SCHEDULE_ALARM` عبر تعديل التاريخ فقط ثم الوقت فقط ثم كليهما، ثم إعادة التفعيل وإيداع `SCHEDULE_ALARM` حصراً بعد النقر الصريح على `task_reminder_reenable_0`. |
| 9 | `def03_taskEditorSheet_pausedReminderVisibilityAndExplicitReEnable_arabicRtl` | 13.612 | **PASSED** | `DEF-03` | اختبار UI + Room بالعربية RTL: ظهور `"متوقف مؤقتاً"` وتلميح استعادة التاريخ لتذكير صريح موقوف على مهمة بلا تاريخ، وبقاؤه متوقفاً بعد استعادة التاريخ حتى النقر الصريح على زر إعادة التفعيل (`"سيُعاد تفعيله"`) وحفظه كـ `PENDING_SYNC`. |

---

## E. النتيجة النهائية والتصنيف (Final Classification)

| العيب | التصنيف النهائي | الأساس الفعلي للتصنيف |
|---|---|---|
| **`DEF-01`** (حماية المهام المكتملة والملغاة في طبقة المجال والواجهة) | **`VERIFIED`** | جميع الدوال التسع في `TaskUseCases` و`TaskReminderUseCases` تجلب الحالة الموثوقة من `TaskRepository.getById` وترفض `COMPLETED` و`CANCELLED` قبل أي كتابة في Room أو Outbox، ومثبتة باختبارات مباشرة (`PASSED`). |
| **`DEF-02`** (تمييز `offsetMinutes == 0` للتذكير الصريح في Schema v1) | **`PARTIALLY VERIFIED`** | السلوك منفذ ومُثبت لجميع الحالات القابلة للتمييز في Schema v1 (صريح ≠ `startTime`، مهمة بلا `startTime`، نسبي > 0، و`0m` عند `startTime`)؛ لكن **لا يمكن بياناتياً** التمييز بين تذكير صريح يساوي تماماً `previousTask.startTime` على `previousTask.scheduledDate` وبين تذكير «عند وقت البدء» على Schema v1 الحالي، كما أن اختبار الجيل التالي المتكرر غطّى تساوي `nextTask.startTime == completedTask.startTime` فقط. |
| **`DEF-03`** (منع إعادة التفعيل الصامتة وإظهار حالة `PAUSED`) | **`VERIFIED`** | ثبات `PAUSED` وغياب `SCHEDULE_ALARM` عبر تعديلات التاريخ فقط والوقت فقط وكليهما، واشتراط الفعل الصريح لإعادة التفعيل، مثبتة بالكامل بالعربية RTL والإنجليزية LTR (`PASSED`). |

### نطاق الإصلاح المقترح للجزء غير المكتمل بياناتياً (للتوثيق فقط — غير منفّذ)
- **لنقطة `DEF-02` المحدودة بـ Schema v1:** إذا رُئي مستقبلاً إزالة الغموض كلياً في حالة تطابق التذكير الصريح مع `previousTask.startTime`، يتطلب ذلك في **مرحلة هجرة Schema v2 لاحقة (خارج Phase 4A.2D وبإذن مستقل)** إضافة عمود صريح في `TaskReminderEntity` (مثل `reminderType: ReminderType`) مع هجرة SQL غير تدميرية؛ أما على **Schema v1 الحالي (`identityHash = "37c351a60c04e881c6bd50156833f191"`)** فالحل الحالي هو الحد الأقصى الممكن دون كسر المخطط.
- *(توضيح ثانوي: جميع الملاحظات المتعلقة بـ `DEF-04 .. DEF-08` و`Unit4BCalendarTest` خارج نطاق Batch 1 ولم يُشرَع بها.)*
