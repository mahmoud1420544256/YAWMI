# YAWMI — Unit 4A.2D.3C Read-Only Architecture Audit & Implementation Plan

**Unit:** Unit 4A.2D.3C — Task Detail Recurrence Management UI  
**Audit Date:** 2026-11-27 (`2026-11-28T04:55Z`)  
**Mode:** Read-Only Audit & Planning (no source, test, or schema files modified)  
**Recommendation:** **`READY FOR IMPLEMENTATION`** (pending user approval of proposed design and decisions in Section 7)

---

## 1. الملفات التي فُحصت (Inspected Files)

| # | المسار الفعلي | المسؤولية الأساسية |
|---|---|---|
| 1 | `app/src/main/java/com/example/data/local/entity/YawmiEnums.kt` | تعريف `RecurrenceFrequency` (`DAILY`, `WEEKLY`, `MONTHLY`, `YEARLY`, `CUSTOM_DAYS`) و`TaskStatus` و`OutboxAction` و`ReminderScheduleState`. |
| 2 | `app/src/main/java/com/example/data/local/entity/RecurrenceEntities.kt` | تعريف `TaskRecurrenceEntity` لجدول `task_recurrences` (علاقة `1:1` فريدة على `taskId` مع `ForeignKey.CASCADE` عند حذف المهمة). |
| 3 | `app/src/main/java/com/example/data/local/dao/RecurrenceDao.kt` | عمليات Room لجدول `task_recurrences`: `observeTaskRecurrence`, `getTaskRecurrenceByTaskId`, `insertTaskRecurrence`, `updateTaskRecurrence`, `deleteTaskRecurrenceByTaskId`. |
| 4 | `app/src/main/java/com/example/domain/task/TaskDomainModels.kt` | نماذج `TaskRecurrenceInput`, `TaskCompletionResult`, وأخطاء التحقق `TaskValidationError` الخاصة بالتكرار. |
| 5 | `app/src/main/java/com/example/domain/task/TaskRecurrenceRules.kt` | قواعد التحقق (`validateInput`, `buildRecurrenceEntity`)، ترميز أيام الأسبوع (`dayBit`, `maskFromDays`, `daysFromMask`)، وحساب الموعد التالي (`computeNextOccurrenceDate`, `computeNextOccurrenceDateForTask`). |
| 6 | `app/src/main/java/com/example/domain/task/TaskReminderRules.kt` | دالة `resolveNextOccurrenceReminderTrigger` لنقل التذكيرات إلى الـ occurrence التالي دون اختلاق وقت بداية. |
| 7 | `app/src/main/java/com/example/domain/task/TaskRules.kt` | آلة حالات المهمة (`allowedNextStatuses`, `canReopen`, `applyStatusTransition`, `reopenTask`, `applyEdit`, `applyPostpone`). |
| 8 | `app/src/main/java/com/example/domain/repository/TaskRepository.kt` | واجهة المستودع التي تعرّف `observeTaskRecurrence`, `getTaskRecurrence`, `upsertTaskRecurrence`, `deleteTaskRecurrence`, و`completeTaskWithRecurrence`. |
| 9 | `app/src/main/java/com/example/domain/task/TaskUseCases.kt` | حالات الاستخدام: `saveTaskRecurrence`, `getTaskRecurrence`, `removeTaskRecurrence`, `completeTaskWithNextOccurrence`, `completeTask`, `cancelTask`, `reopenTask`. *(ملاحظة: لا يوجد صنف منفصل باسم `TaskRecurrenceUseCases`؛ كل هذه العمليات موجودة في `TaskUseCases`)*. |
| 10 | `app/src/main/java/com/example/data/repository/RoomTaskRepository.kt` | التنفيذ الفعلي داخل معاملات Room (`withTransaction`) لـ `completeTaskWithRecurrence`، و`upsertTaskRecurrence`، و`deleteTaskRecurrence`، و`updateStatus`، و`update`، و`deleteById`. |
| 11 | `app/src/main/java/com/example/data/repository/ReminderOutboxCoordinator.kt` | تخصيص `requestCode` الفريد عالمياً (`allocateNextRequestCodeLocked`) وجدولة أوامر Outbox (`SCHEDULE_ALARM`, `CANCEL_ALARM`, `CANCEL_NOTIFICATION`). |
| 12 | `app/src/main/java/com/example/feature/tasks/detail/TaskDetailViewModel.kt` | حالة الشاشة `TaskDetailUiState`، الأحداث `TaskDetailUiEvent`، ومغلفات التنفيذ (`completeTask`, `cancelTask`, `reopenTask`, `deleteTask`). |
| 13 | `app/src/main/java/com/example/feature/tasks/detail/TaskDetailScreen.kt` | هيكل شاشة التفاصيل الحالي (`Header`, `TaskScheduleCard`, `TaskDetailRemindersCard`, `Details`, `Timestamps`, `Actions`) وربط `TaskEditorSheet` و`Snackbar`. |
| 14 | `app/src/main/java/com/example/feature/tasks/detail/TaskDetailRemindersCard.kt` | المرجع البصري والتفاعلي المعتمد لبطاقات الإدارة داخل `TaskDetailScreen` (M3 Cards, AlertDialogs, `minimumInteractiveComponentSize()`, `testTag`, RTL/LTR). |
| 15 | `app/src/main/java/com/example/feature/tasks/components/TaskEditorExtrasSections.kt` و`TaskEditorSheet.kt` | محرر التكرار المدمج داخل ورقة التعديل (`TaskEditorRecurrenceSection`, `DraftRecurrenceState`, `DatePickerDialog` لتاريخ الانتهاء). |
| 16 | `app/src/main/java/com/example/feature/tasks/components/TaskValidationUiMapper.kt` | ترجمة أخطاء `TaskValidationError` الخمسة الخاصة بالتكرار إلى رسائل EN/AR. |
| 17 | `app/src/main/res/values/strings.xml` و`values-ar/strings.xml` | النصوص الحالية للتكرار (`task_recurrence_*`) وأخطائه (`task_error_recurrence_*`). |
| 18 | الاختبارات القائمة | `Phase2PersistenceTest.kt`, `Unit3B3TaskDetailTest.kt`, `Unit4ATaskSyncAndRulesTest.kt`, `Unit4A2BNotificationDeliveryTest.kt`, `Unit4A2DTimePickerAndEditorTest.kt`. |

---

## 2. البنية الحالية (Current Architecture)

- **طبقة البيانات (`RecurrenceEntities.kt` / `RecurrenceDao.kt`):**
  - جدول `task_recurrences` يخزن قاعدة واحدة كحد أقصى لكل مهمة (`Index(value = ["taskId"], unique = true)`) مع حذف تلقائي `CASCADE` عند حذف المهمة.
  - الحقول المدعومة في Schema v1: `id`, `taskId`, `frequency`, `interval`, `daysOfWeekMask`, `endDate`, `updatedAtEpochMs`.
  - لا توجد أعمدة `parentSeriesId` أو `generatedNextTaskId` أو `endAfterCount` أو جدول استثناءات (`exceptions`).
- **طبقة المستودع (`TaskRepository.kt` / `RoomTaskRepository.kt`):**
  - يوفّر المستودع تدفقاً تفاعلياً جاهزاً `observeTaskRecurrence(taskId): Flow<TaskRecurrenceEntity?>` لم يُربط بعد في `TaskDetailViewModel`.
  - `upsertTaskRecurrence` يتحقق داخل `withTransaction` من وجود صف لـ `taskId` فيستدعي `insertTaskRecurrence` أو `updateTaskRecurrence`.
  - `completeTaskWithRecurrence` ينفذ الإكمال وإنتاج الـ occurrence التالي وتذكيراته وأوامر Outbox داخل معاملة Room ذرية واحدة.
- **طبقة المجال (`TaskUseCases.kt` / `TaskRecurrenceRules.kt`):**
  - لا يوجد صنف `TaskRecurrenceUseCases`؛ بل تتولى `TaskUseCases` عمليات `saveTaskRecurrence` و`getTaskRecurrence` و`removeTaskRecurrence` و`completeTaskWithNextOccurrence`.
  - `TaskUseCases` لا تعرّف حالياً دالة `observeTaskRecurrence(taskId)` تمريرية، لكن `TaskDetailViewModel` يمتلك أصلاً مرجع `taskRepository: TaskRepository` في منشئه، مما يسمح بالمراقبة التفاعلية دون تغيير توقيع `TaskDetailViewModel` أو كسر أي اختبار قائم.
- **طبقة العرض (`TaskDetailViewModel.kt` / `TaskDetailScreen.kt`):**
  - `TaskDetailScreen` لا تعرض حالياً أي بطاقة للتكرار؛ الطريقة الوحيدة الموجودة حالياً لإدارة تكرار مهمة من التفاصيل هي فتح `TaskEditorSheet` من زر التعديل العلوي.
  - `TaskDetailViewModel.completeTask()` يستدعي حالياً `taskUseCases.completeTask(taskId)` الذي يستدعي داخلياً `completeTaskWithNextOccurrence(taskId)` **لكنه يتجاهل `TaskCompletionResult.generatedNextTask`** ويعرض دائماً رسالة نجاح الإكمال العامة فقط (`task_detail_msg_completed`).

---

## 3. القدرات الموجودة والقيود (Existing Capabilities, Lifecycle & Limitations)

### أ. القدرات المدعومة فعلياً في الكود
| القدرة / الحقل | الحالة الفعلية في الكود | التفاصيل الدقيقة |
|---|---|---|
| `DAILY` | مدعوم | `baseDate.plusDays(interval.toLong())`. يتجاهل `daysOfWeekMask`. |
| `WEEKLY` | مدعوم | إذا كان `daysOfWeekMask and 0b1111111 == 0`: `baseDate.plusWeeks(interval.toLong())`. إذا كان `mask != 0`: يستدعي `nextMaskedWeekdayDate(baseDate, intervalWeeks = interval, mask)`. |
| `MONTHLY` | مدعوم | `baseDate.plusMonths(interval.toLong())` (مثال: `2026-01-31` -> `2026-02-28`). |
| `YEARLY` | مدعوم | `baseDate.plusYears(interval.toLong())` (مثال: `2024-02-29` -> `2025-02-28`). |
| `CUSTOM_DAYS` | مدعوم (بدلالة أسابيع + قناع أيام) | يتطلب `validateInput` وجود بت واحد على الأقل في `daysOfWeekMask` (`RecurrenceRequiresDaysMask`). ويستخدم `computeNextOccurrenceDate` دالة `nextMaskedWeekdayDate(baseDate, intervalWeeks = interval, mask)` — أي تكرار أيام محددة من الأسبوع كل `interval` أسبوع (وليس كل `interval` يوم!). |
| `interval` | مدعوم | يجب أن يكون `>= 1` وإلا يُرجع `InvalidRecurrenceInterval(interval)`. |
| `daysOfWeekMask` | مدعوم | قناع 7 بتات: `1 shl (day.value - 1)` وفق `java.time.DayOfWeek` (`MONDAY=bit 0` .. `SUNDAY=bit 6`) مقنّعاً بـ `0b1111111`. |
| `endDate` | مدعوم | اختياري؛ إن وُجد فيجب أن يكون تاريخ ISO-8601 صارماً وألا يسبق `task.scheduledDate` (`RecurrenceEndDateBeforeScheduledDate`). المساواة مع `scheduledDate` مسموحة في الحفظ لكن لا تُنتج occurrence بعده. |
| Add / Update / Delete | مدعوم | `TaskUseCases.saveTaskRecurrence` تحافظ على `existingRecurrence?.id` عند التحديث وتستدعي `upsertTaskRecurrence`. و`removeTaskRecurrence` تستدعي `deleteTaskRecurrence` (تُزيل صف `task_recurrences` فقط دون حذف المهمة). |

### ب. دورة حياة التكرار الفعلية (`Recurrence Lifecycle`)
1. **الحفظ والتعديل (`saveTaskRecurrence`):** يتحقق من وجود المهمة ومن أن `scheduledDate` صالح، يبني `TaskRecurrenceEntity` (محتفظاً بنفس `id` إن وجدت قاعدة سابقة على `taskId`)، ثم ينفذ `insert` أو `update` داخل معاملة Room. **ملاحظة حرجة:** `TaskUseCases.saveTaskRecurrence` لا تفحص `TaskStatus` (تسمح نظرياً بالحفظ على مهمة مكتملة/ملغاة إذا استُدعيت مباشرة)، لذا يجب فرض المنع في `TaskDetailViewModel` والواجهة.
2. **الحذف (`removeTaskRecurrence`):** يحذف الصف من `task_recurrences` عبر `taskId` ويعيد `Boolean`؛ تبقى `TaskEntity` وتذكيراتها كما هي دون مساس.
3. **عند إكمال المهمة (`RoomTaskRepository.completeTaskWithRecurrence`):**
   - داخل `database.withTransaction`: يتحقق من انتقال الحالة عبر `TaskRules.applyStatusTransition(..., COMPLETED)` (يرفض الإكمال المكرر لمهمة مكتملة أو ملغاة).
   - يلتقط `enabledRemindersBeforeCompletion` **قبل** إلغاء تذكيرات المهمة الحالية.
   - يحدّث حالة المهمة الأصلية إلى `COMPLETED` ويسجّل `completedAtEpochMs`، ويلغي جميع تذكيرات المهمة الأصلية عبر `cancelTaskRemindersLocked` (يضبط `isEnabled = false`, `scheduleState = CANCELLED`, يرفع `scheduleRevision`, يعلّم `PENDING` deliveries بـ `STALE`, ويضع `CANCEL_ALARM` + `CANCEL_NOTIFICATION` في Outbox).
   - يقرأ `TaskRecurrenceEntity` للمهمة المكتملة ويتركه **سليماً كما هو** على المهمة المكتملة كسجل تاريخي.
   - يحسب `nextDate` عبر `computeNextOccurrenceDateForTask` (يستخدم `completedTask.scheduledDate` أو `localClock.currentDate()`). إذا كانت النتيجة `null` (لا توجد قاعدة أو `nextDate > endDate`) ينتهي الإكمال دون إنشاء مهمة جديدة.
   - إذا وُجد `nextDate`:
     - يُنشئ `TaskEntity` جديدة مستقلة بحالة `PENDING` و`completedAtEpochMs = null` مع نسخ `title`, `description`, `startTime`, `endTime`, `priority`, `categoryId`, `notes`, ووضع `scheduledDate = nextDate`.
     - يُنشئ `TaskRecurrenceEntity` جديدة بـ `id` جديد مرتبطة بـ `nextTask.id` مع نسخ `frequency`, `interval`, `daysOfWeekMask`, `endDate`.
     - ينسخ روابط الأهداف `GoalTaskCrossRef`.
     - لكل تذكير كان مفعلاً قبل الإكمال: يحسب الموعد التالي عبر `TaskReminderRules.resolveNextOccurrenceReminderTrigger` (يحافظ على `offsetMinutes` للمهام ذات الوقت أو على `triggerTime` للتذكيرات الصريحة `offsetMinutes == 0`)، ويخصص `requestCode` عالمي جديد عبر `outboxCoordinator.allocateNextRequestCodeLocked()` (المحمي بجدول `app_metadata` + SQLite triggers)، وينشئ `TaskReminderEntity` جديدة (`scheduleRevision = 1L`, `PENDING_SYNC`, `isEnabled = true`)، ويُدرج `OutboxAction.SCHEDULE_ALARM`.
     - بعد نجاح المعاملة يستدعي `onOutboxCommitted()` لجدولة عامل Outbox.
4. **عند إلغاء المهمة (`cancelTask`) أو إعادة فتحها (`reopenTask`):**
   - **الإلغاء:** ينقل الحالة إلى `CANCELLED` ويلغي التذكيرات؛ لا يُنشئ occurrence جديداً ولا يحذف صف `task_recurrences` (يبقى محفوظاً مع المهمة الملغاة).
   - **إعادة الفتح:** ينقل `COMPLETED` أو `CANCELLED` إلى `PENDING` ويمسح `completedAtEpochMs`. **قيد مهم ناتج عن Schema v1:** إعادة فتح مهمة مكتملة كانت قد ولّدت occurrence تالياً لا تحذف الـ occurrence المُنشأ مسبقاً ولا تمنع توليد occurrence آخر إذا أُكملت المهمة المعاد فتحها مجدداً.
5. **عند مسح تاريخ المهمة بعد حفظ التكرار (`RoomTaskRepository.update`):**
   - `RoomTaskRepository.update` يزامن التذكيرات لكنه **لا يحذف ولا يعطّل `TaskRecurrenceEntity`** إذا حُذف `scheduledDate` لاحقاً أو أصبح `scheduledDate > endDate`. (عند الإكمال حينها يستخدم `computeNextOccurrenceDateForTask` تاريخ اليوم `localClock.currentDate()` كبديل، أو يتوقف إذا تجاوز `endDate`).

---

## 4. التصميم المقترح للواجهة (UI Implementation Proposal)

### أ. بطاقة `TaskDetailRecurrenceCard` داخل `TaskDetailScreen`
- **الموقع:** بطاقة Material 3 مستقلة في `TaskDetailScreen` مباشرة بعد `TaskDetailRemindersCard` (أو قبل `TaskDetailsContentCard`)، متناسقة تماماً مع `TaskDetailRemindersCard` من حيث الحدود (`semantic.borderSubtle`), الأشكال (`MaterialTheme.shapes.large`), الهوامش (`16.dp`), وتوافق اللمس (`minimumInteractiveComponentSize()` >= `48.dp`).
- **حالات البطاقة:**
  1. **Loading:** أثناء `TaskDetailUiState.Loading` تبقى شاشة التفاصيل في حالة التحميل الموحدة الحالية.
  2. **Empty (لا يوجد تكرار):** تعرض نصاً واضحاً (`"No recurrence set"` / `"لا يوجد تكرار محدد"`) مع زر `OutlinedButton` ("Add recurrence" / "إضافة تكرار") مفعّل فقط عندما `isTaskActive && hasScheduledDate && !isMutating`. إذا كانت المهمة بلا تاريخ مجدول (وهي نشطة) يظهر تلميح بأن التكرار يتطلب تاريخاً مجدولاً.
  3. **Loaded (توجد قاعدة تكرار):**
     - شارة/عنوان التكرار الرئيسي بلغة واضحة ومناسبة لـ RTL/LTR (مثال: `كل يوم` / `Every day`، `كل أسبوعين · الإثنين، الأربعاء` / `Every 2 weeks · Mon, Wed`).
     - سطر فرعي بنمط `JetBrainsMonoFontFamily` أو نص ثانوي يعرض `endDate` (إن وُجد) أو `بلا تاريخ انتهاء` / `No end date`.
     - **تحذير تشخيصي غير متطفل (Diagnostic Callout):** إذا وُجدت قاعدة تكرار ولكن `task.scheduledDate == null` أو كان `task.scheduledDate > recurrence.endDate`، تظهر بطاقة تنبيه واضحة تشرح أن القاعدة تحتاج تاريخاً متوافقاً قبل التعديل أو التوليد.
     - **حماية المهام المكتملة والملغاة:** عند `COMPLETED` أو `CANCELLED`:
       - إظهار شريط توضيحي هادئ (`task_detail_recurrence_terminal_hint`): لا يمكن تعديل أو حذف التكرار في هذه الحالة، مع الإشارة في `COMPLETED` إلى أن القاعدة محفوظة كسجل تاريخي.
       - تعطيل أزرار `Add` و`Edit` و`Delete` (`enabled = false`).
     - أزرار الإجراءات في أسفل البطاقة (متطابقة مع نمط `TaskDetailRemindersCard`): `Edit` (`TextButton`) و`Delete` (`IconButton` أحمر يفتح حوار تأكيد).
  4. **Error:**
     - أخطاء التحقق الفورية أثناء الحفظ تُعرض داخل محرر التكرار قبل إغلاقه، بينما أخطاء الـ ViewModel غير المتوقعة (مثل `TaskNotActive` أو `TaskNotFound`) تُرسل عبر `TaskDetailUiEvent.ActionFailed` إلى Snackbar باستخدام `TaskValidationUiMapper`.

### ب. محرر إضافة/تعديل التكرار (`TaskRecurrenceEditorDialog`) واختيار `CUSTOM_DAYS`
- **النمط المقترح:** `AlertDialog` قابل للتمرير عمودياً (`verticalScroll(rememberScrollState())`) ومتوافق مع الشاشات الصغيرة (`320dp`+)، متناسق مع حوارات `TaskDetailRemindersCard`:
  1. **اختيار النمط (`Frequency`):** `FlowRow` من `FilterChip` لـ 5 خيارات واضحة: `يومي` / `أسبوعي` / `شهري` / `سنوي` / `أيام محددة` (`CUSTOM_DAYS`).
  2. **الفاصل الزمني (`interval`):** عنصر تحكم بسيط بالزيادة والنقصان (`-` / `1` / `+`) مع تعطيل `-` عند `1`.
     - **منع إرباك المستخدم في `CUSTOM_DAYS`:** عندما يكون النوع `CUSTOM_DAYS` أو (`WEEKLY` مع أيام مختارة)، يُعرض وصف الوحدة كـ **"كل N أسبوع"** / **"Every N week(s)"** لأن `TaskRecurrenceRules` يضاعف بالأسابيع (`intervalWeeks`) وليس بالأيام! أما في `DAILY` فيُعرض "يوم"، وفي `MONTHLY` "شهر"، وفي `YEARLY` "سنة".
  3. **أيام الأسبوع (`daysOfWeekMask`) لـ `WEEKLY` و`CUSTOM_DAYS`:**
     - تظهر مجموعة `FlowRow` من 7 `FilterChip` مصفوفة بوضوح (مثال: `Mon..Sun` / `الإثنين..الأحد` أو حسب التقويم المحلي — انظر نقطة القرار في القسم 7) مع استجابة لمسية >= `48.dp`.
     - عند اختيار `CUSTOM_DAYS` ولم يُختر أي يوم بعد: يظهر تنبيه مساعد فوري بلون `error` أو `onSurfaceVariant` يوضح وجوب اختيار يوم واحد على الأقل، ويُحفظ هذا الشرط عند الضغط على `Save`.
     - عند اختيار `WEEKLY` دون أيام محددة: يظهر تلميح توضيحي بسيط بأن التكرار سيتم في نفس يوم الأسبوع لتاريخ المهمة.
  4. **تاريخ الانتهاء (`endDate`):** بطاقة اختيار قابلة للنقر تفتح `DatePickerDialog` الموجود (`DatePickerLocalDateMapper`) مع زر مسح (`Clear`)، ومحاذاة `ProvideYawmiLocale` في RTL/LTR.
  5. **تأكيد الحذف:** عند الضغط على أيقونة الحذف يُفتح `AlertDialog` تأكيدي يوضح أن **الحذف يزيل قاعدة التكرار فقط ويبقي المهمة الحالية كما هي**.

### ج. تحسين إشعار `completeTask` في `TaskDetailViewModel` دون كسر التوافق
- في `TaskDetailViewModel.completeTask()`:
  - استبدال `taskUseCases.completeTask(taskId)` بـ `taskUseCases.completeTaskWithNextOccurrence(taskId)`.
  - إذا كان `result.value.generatedNextTask == null`: يُطلق الحدث القائم `TaskDetailUiEvent.ActionSucceeded(R.string.task_detail_msg_completed)` كما هو تماماً (يحافظ على توافق `Unit3B3TaskDetailTest` بنسبة 100%).
  - إذا كان `result.value.generatedNextTask != null`: يُطلق حدث جديد `TaskDetailUiEvent.RecurringTaskCompleted(nextScheduledDate = ...)` يعرض رسالة Snackbar واضحة مثل: `"Task completed. Next occurrence scheduled for 2026-11-26."` / `"تم إكمال المهمة، وأُنشئ التكرار التالي بتاريخ 2026-11-26."`.

---

## 5. قائمة الملفات المتوقع تعديلها في التنفيذ (Expected Scope for Unit 4A.2D.3C)

1. `app/src/main/java/com/example/feature/tasks/detail/TaskDetailViewModel.kt` — إضافة `recurrence: TaskRecurrenceEntity? = null` إلى `TaskDetailUiState.Loaded` (بقيمة افتراضية تحفظ التوافق)، دمج `taskRepository.observeTaskRecurrence(taskId)` في `uiState`، إضافة `saveRecurrence(...)` و`removeRecurrence()` بحراسة الحالة النشطة و`mutatingState`، وتحديث `completeTask()` لإطلاق `RecurringTaskCompleted` عند توليد مهمة تالية.
2. `app/src/main/java/com/example/feature/tasks/detail/TaskDetailRecurrenceCard.kt` *(ملف جديد)* — بطاقة عرض وإدارة التكرار + حوار التحرير (`TaskRecurrenceEditorDialog`) + حوار تأكيد الحذف.
3. `app/src/main/java/com/example/feature/tasks/detail/TaskDetailScreen.kt` — ربط `TaskDetailRecurrenceCard` ومعالجة حدث `RecurringTaskCompleted` في `LaunchedEffect`.
4. `app/src/main/res/values/strings.xml` — إضافة النصوص الإنجليزية الخاصة ببطاقة التكرار، التلخيص، وحدات الفاصل، حوار التأكيد، ورسائل النجاح.
5. `app/src/main/res/values-ar/strings.xml` — إضافة الترجمات العربية المكافئة المتوافقة مع RTL.
6. `app/src/test/java/com/example/Unit4A2D3TaskDetailRecurrenceTest.kt` *(ملف اختبار جديد)* — تغطية شاملة لاختبارات الواجهة وViewModel وتكامل Room/Outbox.

*(ملاحظة: لا حاجة لتعديل `YawmiDatabase.kt` أو `RoomTaskRepository.kt` أو `TaskUseCases.kt` أو `TaskRecurrenceRules.kt` أو `YawmiShell.kt` أو `TaskDetailViewModel` constructor، مما يحافظ على التوافق الكامل مع جميع الاختبارات القائمة.)*

---

## 6. خطة الاختبارات المقترحة (Test Plan)

سيتم بناء حزمة اختبارات `Unit4A2D3TaskDetailRecurrenceTest` (مع إعادة تشغيل الحزمة الانحدارية غير الخاصة بالتقويم) لتغطية:
1. **عرض قاعدة تكرار موجودة:** قراءة قاعدة مسبقة من Room وعرضها تفاعلياً في العربية RTL والإنجليزية LTR (بما في ذلك التحديث الحي عند تغير Room).
2. **إضافة قاعدة تكرار جديدة:** إضافة قاعدة من `TaskDetailRecurrenceCard` التحريرية على مهمة مؤرخة والتحقق من حفظها في Room.
3. **تعديل جميع الحقول المدعومة:** تعديل `frequency` (`DAILY` -> `WEEKLY` -> `MONTHLY` -> `YEARLY` -> `CUSTOM_DAYS`)، و`interval`، و`daysOfWeekMask`، و`endDate` (تعيين ومسح) والتحقق من استمرار حفظها وإعادة قراءتها من Room دون تغيير `TaskEntity`.
4. **التحقق من حدود `interval` و`daysOfWeekMask` و`endDate`:**
   - منع إنقاص `interval` دون `1`.
   - رفض `CUSTOM_DAYS` بدون أيام محددة (`RecurrenceRequiresDaysMask`).
   - رفض `endDate` سابقة لـ `scheduledDate` (`RecurrenceEndDateBeforeScheduledDate`).
   - تعطيل الإضافة عندما تكون `scheduledDate == null`.
5. **حذف قاعدة التكرار مع التأكيد:** فتح حوار تأكيد الحذف، إلغاء الحذف (التحقق من بقاء القاعدة)، ثم التأكيد (التحقق من حذف `TaskRecurrenceEntity` وبقاء `TaskEntity` والتذكيرات سليمة).
6. **إكمال المهمة المتكررة وتوليد الـ occurrence التالي:**
   - الضغط على زر `Complete` من `TaskDetailScreen`.
   - التحقق من بقاء المهمة الأصلية `COMPLETED` مع `completedAtEpochMs` وبقاء `TaskRecurrenceEntity` الأصلية محفوظة كسجل تاريخي.
   - التحقق من إنشاء `TaskEntity` جديدة بحالة `PENDING` وتاريخ متوقع، ونسخ `TaskRecurrenceEntity` الجديدة، ورسالة Snackbar الدالة على إنشاء التكرار التالي.
7. **سلامة التذكيرات وOutbox للـ occurrence الجديد:**
   - وجود تذكير مفعل على المهمة الأصلية -> إلغاء تذكير المهمة الأصلية (`CANCELLED` + `CANCEL_ALARM` + `CANCEL_NOTIFICATION`)، وإنشاء تذكير جديد للمهمة الجديدة بـ `requestCode` مختلف وفريد + أمر `OutboxAction.SCHEDULE_ALARM` جديد بحالة `PENDING`.
8. **حماية حالات المهمة غير النشطة:**
   - تعطيل أزرار Add/Edit/Delete على مهام `COMPLETED` و`CANCELLED`.
   - اختبار `TaskDetailViewModel` مباشرة للتأكد من رفض `saveRecurrence` و`removeRecurrence` على مهمة `COMPLETED`/`CANCELLED` دون تعديل Room.
   - التحقق من استعادة إمكانية إدارة التكرار بعد `Reopen`.

---

## 7. المخاطر والقرارات التي تحتاج موافقة المستخدم (Risks & Decisions Requiring Approval)

1. **فرض حراسة الحالة (`COMPLETED`/`CANCELLED`) في `TaskDetailViewModel` مقابل `TaskUseCases`:**
   - حالياً `TaskUseCases.saveTaskRecurrence` و`removeTaskRecurrence` لا ترفضان المهام المكتملة أو الملغاة.
   - **الاقتراح:** فرض التحقق في `TaskDetailViewModel` (رفض التعديل/الحذف إذا لم تكن المهمة `PENDING` أو `IN_PROGRESS` وإطلاق `ActionFailed(InvalidStatusTransition)`) مع تعطيل الأزرار في `TaskDetailRecurrenceCard`، دون تعديل `TaskUseCases` أو `TaskEditorSheet` لتجنب أي تغيير سلوكي غير مقصود خارج Unit 3C. *هل توافق على حصر الحراسة في `TaskDetailViewModel` و`TaskDetailRecurrenceCard`؟*
2. **وحدة `interval` في `CUSTOM_DAYS`:**
   - في `TaskRecurrenceRules.kt` الفعلي، `CUSTOM_DAYS` يقفز `interval` **أسابيع** (`intervalWeeks = interval`) عبر أيام الأسبوع المحددة، وليس `interval` أيام.
   - **الاقتراح:** عرض وصف "كل N أسبوع" / "Every N week(s)" عند اختيار `CUSTOM_DAYS` (وليس "أيام") ليعكس السلوك الفعلي لمحرك الحساب بدقة. *هل توافق على هذا التوضيح النصي في الواجهة؟*
3. **ترتيب شرائح أيام الأسبوع (`DayOfWeek`):**
   - `TaskEditorExtrasSections.kt` يعرض الأيام بترتيب `DayOfWeek.entries` (`الإثنين..الأحد` / `Mon..Sun`).
   - **الاقتراح:** الحفاظ على نفس ترتيب `DayOfWeek.entries` لتطابق تجربة المستخدم بين `TaskEditorSheet` و`TaskDetailRecurrenceCard`، أم تفضل ترتيباً يبدأ من السبت/الأحد في العربية؟
4. **تنبيه إعادة فتح مهمة متكررة مكتملة (`Reopen`):**
   - بسبب قيود Schema v1 (غياب `parentSeriesId` و`generatedNextTaskId`)، فإن `reopenTask` لا تحذف الـ occurrence التالي الذي سبق توليده، وإعادة إكمال المهمة قد تولّد occurrence إضافياً.
   - **الاقتراح:** توثيق ذلك وإظهار تلميح إرشادي واضح في حالة `COMPLETED` (أن القاعدة محفوظة كسجل تاريخي وأن الـ occurrence التالي مستقل).
5. **سلوك `RecurringTaskCompleted` Snackbar:**
   - استخدام توقيع `onShowSnackbar: ((String) -> Unit)?` القائم دون تغييره، بحيث يظهر نص واضح بتاريخ الموعد التالي عند الإكمال. (إذا رُغب مستقبلاً في زر إجراء "فتح المهمة التالية" داخل Snackbar، فسيحتاج ذلك تعديل `onShowSnackbar` في `YawmiShell.kt` وهو خارج النطاق حالياً).

---

## 8. التوصية النهائية (Final Recommendation)

**التوصية:** **`READY FOR IMPLEMENTATION`**

البنية التحتية للتكرار (`TaskRecurrenceEntity`, `RecurrenceDao`, `RoomTaskRepository`, `TaskUseCases`, `TaskRecurrenceRules`, `TaskValidationUiMapper`) مكتملة ومختبرة في المشروع، ولا توجد موانع معمارية أو تغييرات مطلوبة على Room Schema v1 أو Outbox Dispatcher. بمجرد اعتمادك للقرارات الواردة في القسم (7)، يمكن البدء مباشرة في تنفيذ Unit 4A.2D.3C.
