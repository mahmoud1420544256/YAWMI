# YAWMI — Unit 4A.2D.3C Final Engineering Report

**Unit:** Unit 4A.2D.3C — Task Detail Recurrence Management UI  
**Final Status:** `READY FOR USER ACCEPTANCE`  
**Verification Completed At:** `2026-11-28T07:35:00Z` (`2026-11-27T23:35:00-08:00`)

---

## 1. الملفات المعدلة وأسباب تعديل كل ملف (Modified Files & Rationale)

### أ. ملفات نطاق Unit 4A.2D.3C المعتمدة

| # | المسار الفعلي | الإجراء | سبب التعديل ودوره في الوحدة |
|---|---|---|---|
| 1 | `app/src/main/java/com/example/feature/tasks/detail/TaskDetailRecurrenceCard.kt` | Created | إنشاء بطاقة Material 3 مستقلة لإدارة التكرار داخل شاشة التفاصيل، متضمنة عرض القاعدة، وحوار الإضافة/التعديل (`TaskRecurrenceEditorDialog`)، وحوار تأكيد الحذف، وحالات التحميل/الفراغ/الخطأ، والتوافق الكامل مع RTL/LTR وAccessibility و`testTag`s. |
| 2 | `app/src/main/java/com/example/feature/tasks/detail/TaskDetailViewModel.kt` | Modified | ربط `taskRepository.observeTaskRecurrence(taskId)` في `uiState` (`recurrence` و`showReopenRecurrenceWarning`)، وإضافة `saveRecurrence` و`removeRecurrence` مع حراسة الحالة (`PENDING` / `IN_PROGRESS` فقط وإطلاق `ActionFailed(InvalidStatusTransition)` عند الرفض)، وتحديث `completeTask()` لاستخدام `taskUseCases.completeTaskWithNextOccurrence(taskId)` وإطلاق `RecurringTaskCompleted(nextDate)` مع الحفاظ على `ActionSucceeded` عند عدم توليد مهمة تالية. |
| 3 | `app/src/main/java/com/example/feature/tasks/detail/TaskDetailScreen.kt` | Modified | تمرير حالة التكرار والمراقبة إلى `TaskDetailRecurrenceCard` أسفل `TaskDetailRemindersCard`، ومعالجة حدث `TaskDetailUiEvent.RecurringTaskCompleted` داخل `LaunchedEffect` باستخدام `onShowSnackbar` القائم (`((String) -> Unit)?`). |
| 4 | `app/src/main/res/values/strings.xml` | Modified | إضافة النصوص الإنجليزية (LTR) الخاصة ببطاقة التكرار، والأنماط، ووحدة `Every N weeks`، وتحذيرات الحالة وإعادة الفتح، وحوار التأكيد، ورسالة إكمال المهمة المتكررة. |
| 5 | `app/src/main/res/values-ar/strings.xml` | Modified | إضافة الترجمات العربية المكافئة (RTL) بما في ذلك `كل %1$d أسبوع`، وتسميات الأيام، ورسائل التحذير والتأكيد والنجاح. |
| 6 | `app/src/test/java/com/example/Unit4A2D3TaskDetailRecurrenceTest.kt` | Created | حزمة اختبارات Robolectric/Compose وViewModel من 5 اختبارات تغطي العرض في RTL/LTR، والإضافة والتعديل والحذف، والأنماط الخمسة، والتحقق من القيود، وحراسة `COMPLETED`/`CANCELLED`، و`completeTaskWithNextOccurrence` و`Reopen`. |

### ب. ملاحظة شفافة حول إصلاح سابق منفصل في الجلسة (خارج Unit 4A.2D.3C)
قبل بدء التحقق من Unit 4A.2D.3C، وُضع إصلاح للبلاغ المنفصل للمستخدم («زر التبديل اليوم التالي لا يعمل» للمهام غير المجدولة زمنياً):
- `app/src/main/java/com/example/feature/tasks/components/TaskEditorScheduleSection.kt` (تفعيل `task_next_day_switch` بمجرد توفر `startTime` و`endTime`، وإزالة `.clickable` المدمج من عمود التسمية للحفاظ على شجرة الدلالات).
- `app/src/main/java/com/example/feature/tasks/components/TaskEditorSheet.kt` (تعبئة `scheduledDate` تلقائياً بتاريخ اليوم المحلي عند تفعيل التبديل لمهمة غير مجدولة).
- `app/src/test/java/com/example/Unit4A2DTimePickerAndEditorTest.kt` (إضافة اختبار `unscheduledTimesEnteredFirst_nextDaySwitchIsEnabledAutoSelectsTodayAndSaves`).

**تأكيد:** لم يُعدَّل `TaskEditorSheet.kt` لأي غرض يخص **Unit 4A.2D.3C**، وتُرك دون تغيير في مسار التكرار.

---

## 2. تفاصيل تنفيذ البطاقة والمحرر (Card & Editor Implementation Details)

### أ. بطاقة `TaskDetailRecurrenceCard` (`TaskDetailRecurrenceCard.kt`)
- **التصميم والتناسق:** تستخدم `Card` بحدود `semantic.borderSubtle` وشكل `MaterialTheme.shapes.large` وتباعد `16.dp`، بما يطابق تماماً نمط `TaskDetailRemindersCard`.
- **الحالات المدعومة:**
  - **Loading / Mutating:** تعرض `TaskDetailScreen` مؤشر `task_detail_loading` أثناء `TaskDetailUiState.Loading`، وتعطّل البطاقة أزرار الإضافة والتعديل والحذف عند `isMutating == true`.
  - **Empty:** عند `recurrence == null` تعرض `task_detail_recurrence_empty` وزر `OutlinedButton` (`task_detail_add_recurrence_btn`) المفعّل فقط عند `isTaskActive && hasScheduledDate && !isMutating`. وإذا لم يكن للمهمة النشطة `scheduledDate` يظهر تنبيه `task_detail_recurrence_requires_date_hint`.
  - **Error / Diagnostics:**
    - إذا تعارضت قاعدة التكرار المخزنة مع تاريخ المهمة الحالي (مثل مسح `scheduledDate` لاحقاً أو كون `scheduledDate > endDate`) يظهر تنبيه `task_detail_recurrence_invalid_schedule_hint` بلون `errorContainer`.
    - أخطاء التحقق داخل المحرر تظهر فورياً أسفل النموذج (`task_detail_recurrence_editor_error`) عبر `TaskRecurrenceRules.validateInput` و`TaskValidationUiMapper`.
    - أخطاء `TaskDetailViewModel` تُرسل عبر `TaskDetailUiEvent.ActionFailed` إلى Snackbar.
  - **Loaded:** تعرض شارة النمط (`task_detail_recurrence_frequency_badge`)، وملخص الوتيرة (`task_detail_recurrence_cadence_text`)، وملخص الأيام عند `WEEKLY` أو `CUSTOM_DAYS` (`task_detail_recurrence_days_text`)، وتاريخ الانتهاء بخط `JetBrainsMonoFontFamily` أو `"No end date"` / `"بلا تاريخ انتهاء"`، مع زر `Edit` (`task_detail_recurrence_edit_btn`) وزر `Delete` (`task_detail_recurrence_delete_btn`).

### ب. محرر التكرار (`TaskRecurrenceEditorDialog`) وحوار الحذف
- **الأنماط الخمسة:** `FlowRow` من `FilterChip` لجميع قيم `RecurrenceFrequency`: `DAILY`, `WEEKLY`, `MONTHLY`, `YEARLY`, `CUSTOM_DAYS`.
- **الفاصل الزمني (`interval`):** أزرار `-` (`task_detail_recurrence_interval_dec`) و`+` (`task_detail_recurrence_interval_inc`) مع تعطيل `-` عند `1`.
- **دلالات `CUSTOM_DAYS`:** يعرض النص `"كل %1$d أسبوع"` / `"كل 1 أسبوع"` بالعربية و`"Every %1$d weeks"` / `"Every 1 week"` بالإنجليزية، مع تلميح `task_detail_recurrence_custom_days_hint` لتوضيح أن التكرار يتم كل `N` أسابيع عبر أيام الأسبوع المختارة.
- **ترتيب أيام الأسبوع (`daysOfWeekMask`):** يحافظ على ترتيب `DayOfWeek.entries` من الإثنين (`MONDAY`) إلى الأحد (`SUNDAY`) في العرض والملخص.
- **تاريخ الانتهاء (`endDate`):** يستخدم `DatePickerDialog` عبر `DatePickerLocalDateMapper` و`ProvideYawmiLocale` مع زر مسح (`task_detail_recurrence_end_date_clear`).
- **تأكيد الحذف:** يفتح `AlertDialog` (`task_detail_recurrence_delete_dialog`) يؤكد أن إزالة القاعدة تبقي المهمة الحالية وتذكيراتها دون تغيير.
- **أهداف اللمس والوصول:** جميع عناصر التفاعل مزوّدة بـ `Modifier.minimumInteractiveComponentSize()` (>= `48.dp`)، وأوصاف `contentDescription`، ومعرّفات `testTag` بصيغة `snake_case`.

### ج. حراسة الحالة وإكمال المهمة وإعادة الفتح (`TaskDetailViewModel.kt`)
- **المراقبة التفاعلية:** يدمج `extrasFlow` تدفق `taskRepository.observeTaskRecurrence(taskId)` دون تعديل `TaskUseCases` أو إنشاء `TaskRecurrenceUseCases`.
- **حراسة الحالة:** تتحقق `saveRecurrence` و`removeRecurrence` من كون `existing.status` هو `TaskStatus.PENDING` أو `TaskStatus.IN_PROGRESS`. عند `COMPLETED` أو `CANCELLED` ترفض العملية وتطلق `TaskDetailUiEvent.ActionFailed(TaskValidationError.InvalidStatusTransition(existing.status, existing.status))`.
- **إكمال المهمة المتكررة:** تستدعي `completeTask()` الدالة `taskUseCases.completeTaskWithNextOccurrence(taskId)`:
  - إذا وُجدت `generatedNextTask?.scheduledDate`: تطلق `TaskDetailUiEvent.RecurringTaskCompleted(nextDate)` لعرض Snackbar يحتوي على تاريخ الموعد التالي.
  - إذا لم تُولَّد مهمة تالية: تطلق `TaskDetailUiEvent.ActionSucceeded(R.string.task_detail_msg_completed)` كما كان سابقاً.
- **تحذير إعادة الفتح:** عند `COMPLETED` أو بعد `reopenTask()` لمهمة لها قاعدة تكرار (`showReopenRecurrenceWarning`)، يظهر تلميح `task_detail_recurrence_reopen_hint` موضحاً أن الموعد التالي المُنشأ مستقل وأن إعادة إكمال المهمة قد تنشئ تكراراً إضافياً، دون حذف أو تعديل أي مهمة تالية.

---

## 3. مصفوفة التغطية الوظيفية (Functional Coverage Matrix)

| المتطلب | حالة التنفيذ | دليل التحقق (الكود والاختبارات) |
|---|---|---|
| إعادة استخدام `TaskUseCases` و`observeTaskRecurrence` دون `TaskRecurrenceUseCases` أو محرك جديد | **VERIFIED** | `TaskDetailViewModel.kt` سطر 162، 294، 393، 433؛ `grep` أثبت 0 نتائج لـ `TaskRecurrenceUseCases`. |
| عدم تكرار منطق `TaskRecurrenceRules` | **VERIFIED** | `TaskDetailRecurrenceCard.kt` يستدعي `TaskRecurrenceRules.validateInput`, `dayBit`, `daysFromMask` مباشرة. |
| عرض قاعدة التكرار تفاعلياً (RTL وLTR) | **VERIFIED** | `Unit4A2D3TaskDetailRecurrenceTest.taskDetailRecurrence_displaysExistingCustomDaysRuleInArabicRtlAndEnglishLtr`. |
| إضافة وتعديل جميع الأنماط الخمسة (`DAILY`, `WEEKLY`, `MONTHLY`, `YEARLY`, `CUSTOM_DAYS`) | **VERIFIED** | `Unit4A2D3TaskDetailRecurrenceTest.taskDetailRecurrence_addEditAllFrequenciesValidationEndDateAndDelete`. |
| دعم `interval` و`daysOfWeekMask` و`endDate` والتحقق من الحدود | **VERIFIED** | اختبار قيود `interval >= 1`، وقناع أيام `CUSTOM_DAYS`، و`endDate < scheduledDate`، ومسح `endDate` في `Unit4A2D3TaskDetailRecurrenceTest`. |
| `CUSTOM_DAYS` كل `N` أسابيع + ترتيب `DayOfWeek.entries` (الإثنين -> الأحد) | **VERIFIED** | التحقق من `"كل ٢ أسبوع"` و`"Every 2 weeks"` ومواضع الشرائح (`monBounds.left < tueBounds.left`) و`"Mon, Wed, Fri"`. |
| حذف القاعدة مع تأكيد والحفاظ على المهمة وتذكيراتها | **VERIFIED** | إلغاء الحوار ثم تأكيده في `taskDetailRecurrence_addEditAllFrequenciesValidationEndDateAndDelete` والتحقق من بقاء المهمة. |
| حراسة `COMPLETED` و`CANCELLED` + `ActionFailed(InvalidStatusTransition)` | **VERIFIED** | `taskDetailRecurrence_unscheduledAndCancelledUiGuardsDisableActions` + `taskDetailViewModel_rejectsRecurrenceMutationsOnCompletedAndCancelledTasks`. |
| `completeTaskWithNextOccurrence` + Snackbar بالتاريخ التالي + `ActionSucceeded` عند عدم التوليد | **VERIFIED** | `taskDetailRecurrence_completeGeneratesNextOccurrenceAndReopenPreservesItWithWarning` (مع تذكير وOutbox) + `Unit3B3TaskDetailTest` للمهام غير المتكررة. |
| تحذير `Reopen` وعدم حذف الـ occurrence التالي | **VERIFIED** | استمرار بقاء مهمة `2026-11-26` بعد `Reopen` وظهور `task_detail_recurrence_reopen_hint`. |
| إصلاح زر التبديل «اليوم التالي» للمهام غير المجدولة (خارج 4A.2D.3C) | **VERIFIED** | `Unit4A2DTimePickerAndEditorTest.unscheduledTimesEnteredFirst_nextDaySwitchIsEnabledAutoSelectsTodayAndSaves`. |

---

## 4. نتائج الاختبارات مستخرجة من XML الفعلية (Test Results from Actual XML Reports)

نُفذت جميع حزم الاختبار الـ 22 (باستثناء `Unit4BCalendarTest`) عبر أمر `gradle :app:testDebugUnitTest --tests ... --console=plain` بالتتابع. القيم التالية مستخرجة حرفياً من ملفات XML الناتجة في `app/build/test-results/testDebugUnitTest/`:

| Suite | Tests | Skipped | Failures | Errors | Time (s) | Timestamp (UTC) |
|---|---:|---:|---:|---:|---:|---|
| `ExampleRobolectricTest` | 1 | 0 | 0 | 0 | 7.654 | `2026-11-28T07:28:39.157Z` |
| `ExampleUnitTest` | 1 | 0 | 0 | 0 | 0.008 | `2026-11-28T07:28:47.656Z` |
| `Phase1FoundationTest` | 6 | 0 | 0 | 0 | 16.252 | `2026-11-28T07:28:48.730Z` |
| `Phase2PersistenceTest` | 12 | 0 | 0 | 0 | 9.915 | `2026-11-28T07:29:06.026Z` |
| `Unit3ATaskPersistenceTest` | 1 | 0 | 0 | 0 | 7.984 | `2026-11-28T07:29:16.929Z` |
| `Unit3ATaskRulesTest` | 6 | 0 | 0 | 0 | 0.081 | `2026-11-28T07:29:25.715Z` |
| `Unit3B1EditorAndCategoryTest` | 4 | 0 | 0 | 0 | 15.000 | `2026-11-28T07:29:26.785Z` |
| `Unit3B2TaskQueryRulesTest` | 3 | 0 | 0 | 0 | 0.071 | `2026-11-28T07:29:42.647Z` |
| `Unit3B2TasksScreenTest` | 3 | 0 | 0 | 0 | 14.996 | `2026-11-28T07:29:43.724Z` |
| `Unit3B3TaskDetailTest` | 5 | 0 | 0 | 0 | 15.496 | `2026-11-28T07:29:59.840Z` |
| `Unit3B4TodayDashboardRulesTest` | 3 | 0 | 0 | 0 | 0.074 | `2026-11-28T07:30:16.146Z` |
| `Unit3B4TodayLifecycleAndTimeTest` | 7 | 0 | 0 | 0 | 13.652 | `2026-11-28T07:30:17.256Z` |
| `Unit3B4TodayScreenTest` | 4 | 0 | 0 | 0 | 16.776 | `2026-11-28T07:30:31.990Z` |
| `Unit4A2AOutboxDispatcherTest` | 20 | 0 | 0 | 0 | 10.557 | `2026-11-28T07:30:49.840Z` |
| `Unit4A2BNotificationDeliveryTest` | 14 | 0 | 0 | 0 | 10.733 | `2026-11-28T07:31:01.435Z` |
| `Unit4A2CSystemReconciliationTest` | 16 | 0 | 0 | 0 | 11.578 | `2026-11-28T07:31:13.356Z` |
| `Unit4A2D3TaskDetailRecurrenceTest` | 5 | 0 | 0 | 0 | 16.537 | `2026-11-28T07:31:26.457Z` |
| `Unit4A2D3TaskDetailRemindersTest` | 8 | 0 | 0 | 0 | 17.356 | `2026-11-28T07:31:44.193Z` |
| `Unit4A2DScheduleAndDurationTest` | 10 | 0 | 0 | 0 | 9.432 | `2026-11-28T07:32:02.612Z` |
| `Unit4A2DTimePickerAndEditorTest` | 6 | 0 | 0 | 0 | 19.087 | `2026-11-28T07:32:13.164Z` |
| `Unit4ATaskSyncAndRulesTest` | 10 | 0 | 0 | 0 | 9.943 | `2026-11-28T07:32:33.464Z` |
| `Unit4ATimeAndDstTest` | 5 | 0 | 0 | 0 | 12.436 | `2026-11-28T07:32:44.569Z` |
| **Total (22 Suites)** | **150** | **0** | **0** | **0** | — | اكتملت الساعة `2026-11-28T07:32:44Z` |

- **المستبعد مؤقتاً:** `Unit4BCalendarTest` (لم يُعدَّل ولم يُنفَّذ وفق التوجيه).

---

## 5. نتيجة `assembleDebug` ووقت APK وحجمه (Build Verification)

- **الأمر المنفذ:** `gradle :app:assembleDebug --console=plain`
- **النتيجة:** **`BUILD SUCCESSFUL`** (39 actionable tasks: 39 up-to-date، في `2026-11-28T07:34:57Z` / `2026-11-27T23:34:57-08:00`)
- **مسار الـ APK:** `app/build/outputs/apk/debug/app-debug.apk`
- **حجم الـ APK:** `20,686,327` بايت (`20686327 bytes`)
- **وقت تعديل الـ APK:** `2026-11-28 07:15:47.166329024 +0000` (`2026-11-27T23:15:47-08:00`) — أحدث من جميع ملفات المصدر (`2026-11-28 07:11:51 UTC`) ومتحقق من حداثته عبر `:app:assembleDebug`.

---

## 6. تأكيد Schema v1 و`identityHash` (Database Integrity)

- **`CURRENT_DB_VERSION`:** `1` (`YawmiDatabase.kt` سطر 59 و86)
- **`identityHash`:** `"37c351a60c04e881c6bd50156833f191"` (`app/schemas/com.example.data.local.db.YawmiDatabase/1.json` سطر 5)
- **الهجرات (`YawmiMigrations.kt`):** `ALL_MIGRATIONS: Array<Migration> = emptyArray()` — لم تُضف أي هجرات أو تعديلات على المخطط.
- **التحقق بالاختبار:** اجتياز `Unit4A2DScheduleAndDurationTest.roomSchemaV1AndIdentityHash_remainCompletelyUnchanged`.

---

## 7. مراجعة القيود والمخاطر المتبقية (Limitations & Remaining Risks)

1. **نطاق حراسة الحالة (`COMPLETED` / `CANCELLED`):**
   - الحراسة التي تمنع إضافة/تعديل/حذف قاعدة التكرار للمهام المكتملة أو الملغاة (`ActionFailed(InvalidStatusTransition)` وتعطيل الأزرار) **محصورة في مسار `TaskDetailViewModel` و`TaskDetailRecurrenceCard` فقط**، وليست ضماناً عاماً على مستوى كل استدعاءات `TaskUseCases.saveTaskRecurrence` أو `removeTaskRecurrence` إذا استُدعيت مباشرة من مسار آخر.
2. **قيود Schema v1 عند إعادة الفتح (`Reopen`):**
   - لا يحتوي جدول المهام أو `task_recurrences` على `parentSeriesId` أو `generatedNextTaskId`؛ لذا فإن `reopenTask` لا تحذف الـ occurrence التالي المستقل، وإعادة إكمال المهمة المعاد فتحها قد تنشئ occurrence إضافياً. جرت معالجة ذلك بوضوح عبر تلميح `task_detail_recurrence_reopen_hint`.
3. **مسح تاريخ المهمة بعد حفظ التكرار:**
   - إذا حُذف `scheduledDate` لاحقاً أو أصبح بعد `endDate` عبر تعديل المهمة، فإن قاعدة التكرار لا تُحذف تلقائياً في المستودع؛ تعرض البطاقة تنبيه `task_detail_recurrence_invalid_schedule_hint` للمستخدم.
4. **حدود الفاصل والتكرار:**
   - نمط `CUSTOM_DAYS` يقفز بمقدار `interval` **أسابيع** عبر أيام الأسبوع المختارة في `TaskRecurrenceRules` (وليس كل N أيام)، وقد عُكس ذلك نصياً بدقة في الواجهة العربية والإنجليزية.

---

## 8. ما لم يتم التحقق منه (Unverified Items)

1. **`Unit4BCalendarTest`:** استُبعدت مؤقتاً ولم تُشغَّل بسبب مشكلة التعليق السابقة، ولم يُمسّ ملفها.
2. **اختبارات الجهاز الفعلي / المحاكي (`androidTest` / `adb`):** بيئة التنفيذ لا تحتوي على Android Emulator أو ADB؛ لذا لم يُختبر التفاعل اللمسي الفعلي على جهاز حي أو إطلاق إشعارات `AlarmManager` على مستوى النظام في نهاية التدفق.
3. **تحذير مترجم Kotlin غير المؤثر في `TaskEditorSheet.kt`:** لا علاقة له بـ Unit 4A.2D.3C ولم يُمسّ الملف ضمن هذه الوحدة.

---

## 9. تأكيد عدم تعديل الملفات المحظورة (Forbidden Scope Confirmation)

- **لم تُعدَّل أي من الملفات أو الأنظمة المحظورة ضمن Unit 4A.2D.3C:**
  - `YawmiDatabase.kt`, `YawmiMigrations.kt`, و`app/schemas/.../YawmiDatabase/1.json` (ثابتة على v1 و`37c351a60c04e881c6bd50156833f191`).
  - `TaskRecurrenceRules.kt` و`TaskUseCases.kt` (لم يُعدّلا؛ ولم يُنشأ `TaskRecurrenceUseCases`).
  - `YawmiShell.kt` (`app/src/main/java/com/example/presentation/navigation/YawmiShell.kt` لم يُعدَّل؛ وبقي توقيع `onShowSnackbar: ((String) -> Unit)?` كما هو).
  - `CalendarScreen.kt`, `CalendarViewModel.kt`, و`Unit4BCalendarTest.kt` (لم تُعدَّل).
  - أنظمة Outbox Dispatcher وNotification Delivery Pipeline (`app/src/main/java/com/example/reminder/*`) (لم تُعدَّل).
  - `TaskEditorSheet.kt` (لم يُعدَّل ضمن Unit 4A.2D.3C؛ والتعديل الوحيد السابق له في الجلسة كان حصراً لإصلاح زر التبديل «اليوم التالي» للمهام غير المجدولة).
  - **Unit 3D:** لم يبدأ ولم تُضف له أي ملفات.

---

## 10. التوصية النهائية (Final Recommendation)

**التوصية:** **`READY FOR USER ACCEPTANCE`**

### مبررات الاعتماد
1. مطابقة كاملة لمتطلبات **Unit 4A.2D.3C** وإعادة استخدام البنية المعتمدة (`TaskUseCases`, `TaskRepository.observeTaskRecurrence`, `TaskRecurrenceRules`) دون تكرار أو تغيير في المخطط.
2. نجاح **جميع الحزم الـ 22 المنفذة (`150 / 150` اختبار PASS، `0` فشل، `0` أخطاء، `0` تخطٍ)** استناداً إلى تقارير XML الفعلية، متضمنةً اختبارات `Unit4A2D3TaskDetailRecurrenceTest` (5/5) وإصلاح التبديل «اليوم التالي» في `Unit4A2DTimePickerAndEditorTest` (6/6).
3. نجاح `:app:assembleDebug` وإصدار `app-debug.apk` بحجم `20,686,327` بايت مع بقاء Room Schema v1 (`identityHash = "37c351a60c04e881c6bd50156833f191"`) سليماً بالكامل.

---

**ملاحظة التوقف:** اكتمل تنفيذ والتحقق من **Unit 4A.2D.3C** (مع التحقق الكامل لإصلاح زر التبديل «اليوم التالي»). لم يبدأ العمل على **Unit 3D** أو أي مرحلة تالية، بانتظار مراجعتك واعتمادك.
