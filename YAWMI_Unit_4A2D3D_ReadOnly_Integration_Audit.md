# YAWMI — Unit 4A.2D.3D: Read-Only Integration & Regression Audit

**الحالة:** تدقيق قراءة فقط (Read-Only Audit) — لم يتم تعديل أو إنشاء أو حذف أي ملف مصدري أو اختباري أو مورد أو مخطط قاعدة بيانات باستثناء هذا التقرير.  
**النطاق:** المرحلة النهائية من **Phase 4A.2D** (التحقق من التكامل الشامل، سيناريوهات دورة الحياة الحرجة، مصفوفة الانحدار، وسلامة قاعدة البيانات والهندسة).

---

## 1. ملخص التكامل

يعتمد تطبيق YAWMI على تدفق معماري موحّد يربط واجهات Jetpack Compose وحالات الاستخدام (Domain UseCases) ومستودعات Room بنواة التذكيرات والجدولة غير المتزامنة (Outbox → AlarmManager → NotificationDelivery → SystemReconciler):

1. **طبقة العرض وإدارة الحالة (UI & ViewModels):**
   - **`TodayScreen.kt` / `TodayViewModel.kt`**: تراقب `taskRepository.observeAllTasks()` و`categoryUseCases.observeActiveCategories()` و`timeContext` (عبر `ObserveTodayTimeAndLifecycle`) لعرض لوحة اليوم؛ الإنشاء السريع يفتح `TaskEditorSheet` مع تمرير `taskReminderUseCases`، بينما إكمال/إعادة فتح المهمة يمر عبر `taskUseCases.completeTask` / `reopenTask`.
   - **`TasksScreen.kt` / `TasksViewModel.kt` / `TaskListItemCard.kt`**: تعرض جميع المهام مع البحث والفرز والتصفية وإجراءات الإكمال والتأجيل والحذف وتمرير فتح التفاصيل (`onOpenTaskDetail`) أو `TaskEditorSheet`.
   - **`TaskDetailScreen.kt` / `TaskDetailViewModel.kt`**: نقطة إدارة دورة الحياة الكاملة لمهمة واحدة؛ تدمج `observeTaskById(taskId)` و`observeTaskRecurrence(taskId)` و`observeTaskReminders(taskId)` و`observeTaskDeliveries(reminderId)` داخل `TaskDetailUiState.Loaded`، وتعرض بطاقتي `TaskDetailRemindersCard.kt` و`TaskDetailRecurrenceCard.kt` مع ربط `TaskEditorSheet` لوضع التعديل.
   - **`TaskEditorSheet.kt` / `TaskEditorScheduleSection.kt` / `TaskEditorExtrasSections.kt`**: تدير إنشاء وتعديل المهمة (`createTask`/`updateTask`)، وجدولة التاريخ والوقت والامتداد لليوم التالي (`spansNextDay`)، ومسودة التكرار (`saveTaskRecurrence`/`removeTaskRecurrence`)، ومسودة التذكيرات (`createRelativeReminder`/`createExplicitReminder`/`deleteReminder`).
   - **`MainActivity.kt` / `YawmiShell.kt`**: تستقبل `EXTRA_OPEN_TASK_ID` من نقر الإشعارات (`onCreate` و`onNewIntent`) وتوجّه الملاحة مباشرة إلى `TaskDetailRoute(taskId)`.

2. **طبقة المنطق والمستودعات (Domain & Repositories):**
   - **`TaskUseCases.kt` & `TaskRules.kt` & `TaskRecurrenceRules.kt`**: التحقق من صحة الحقول، انتقالات الحالة، التأجيل، حساب التكرار التالي (`DAILY`, `WEEKLY`, `MONTHLY`, `YEARLY`, `CUSTOM_DAYS`).
   - **`TaskReminderUseCases.kt` & `TaskReminderRules.kt` & `ReminderTriggerCalculator.kt`**: التحقق من أهلية المهمة (`isTaskActive`: `PENDING` أو `IN_PROGRESS` فقط)، حساب أوقات التذكيرات النسبية والصريحة والغفوة (`snooze`)، وحساب وقت التشغيل الفعلي وفق المنطقة الزمنية والتوقيت الصيفي (DST).
   - **`RoomTaskRepository.kt`**: ينسّق كل تعديل/تأجيل/تغيير حالة/حذف/إكمال متكرر داخل معاملات Room (`withTransaction`) ويستدعي `ReminderOutboxCoordinator` لتحديث التذكيرات وإبطال التسليمات المعلقة (`STALE`) وجدولة أوامر Outbox، ثم يُطلق `onOutboxCommitted()` لتشغيل `WorkManagerOutboxTrigger`.
   - **`RoomReminderSyncRepository.kt`**: ينفذ إنشاء وتحديث وغفوة وحذف التذكيرات وربطها ذرياً بـ `NEXT_REMINDER_REQUEST_CODE` في `app_metadata` وإيداع أوامر `SCHEDULE_ALARM` / `CANCEL_ALARM_AND_NOTIFICATION` في `alarm_sync_outbox`.

3. **نواة المزامنة والإشعارات والمصالحة (Outbox / Delivery / Reconciliation):**
   - **`AlarmOutboxDispatcher.kt` + `DispatcherLeaseManager.kt` + `AlarmScheduler.kt` + `PendingIntentFactory.kt`**: تستهلك أوامر Outbox محمية بعقد إيجار (`lease_owner` + `lease_generation` + CAS revision check)، وتدمج الأوامر المتلاحقة لنفس التذكير، وتضبط المنبهات عبر `AndroidAlarmManagerScheduler` أو تلغي المنبه والإشعار معاً.
   - **`AlarmReceiver.kt` → `AlarmFireHandler.kt` → `NotificationDeliveryEngine.kt`**: عند رنين المنبه، تعود لـ Room كمصدر حقيقة وحيد، تتحقق من صلاحية المهمة والتذكير والمراجعة وفترة السماح (15 دقيقة) والقنوات وصلاحية `POST_NOTIFICATIONS`، ثم تنشر الإشعار عبر `NotificationChannelManager` و`NotificationPendingIntentFactory` (أزرار `ACTION_COMPLETE_TASK` و`ACTION_SNOOZE_TASK` وفتح `TaskDetailRoute`).
   - **`NotificationActionReceiver.kt` → `NotificationActionHandler.kt`**: يعالج إكمال المهمة أو غفوتها مباشرة من الإشعار ويلغي الإشعار المنشور.
   - **`SystemReconcileReceiver.kt` → `SystemReconciliationWorker.kt` → `ReminderReconciler.kt`**: يعيد بناء ومزامنة المنبهات والتسليمات عبر `WorkManager` عند `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED`, `DATE_CHANGED`, `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` وعند عودة التطبيق للواجهة (`YawmiApplication.onActivityResumed`).

**النتيجة المعمارية العامة:** لا توجد محركات موازية للتكرار أو الجدولة أو الإشعارات داخل `feature/` أو `presentation/` (تم التحقق عبر `grep`). جميع العمليات تمر عبر المستودعات وحالات الاستخدام المعتمدة، مع وجود ثغرات محددة ومحصورة في حدود الحالة المنتهية في `TaskEditorSheet`/`TaskUseCases`، وتصادم دلالة `offsetMinutes == 0` في Schema v1، وبعض تفاصيل تجربة المستخدم والاختبارات الموضحة أدناه.

---

## 2. مصفوفة السيناريوهات

| # | السيناريو الحرج | مسار التنفيذ والتحقق الكودي | الحالة | التغطية الاختبارية الحالية والفجوة |
|---|---|---|---|---|
| **1** | **إنشاء مهمة مجدولة وإضافة تذكير وتكرار** | `TodayScreen`/`TasksScreen` ← `TaskEditorSheet.handleSave` (`TaskEditorSheet.kt:384-497`) ← `TaskUseCases.createTask` + `saveTaskRecurrence` + `TaskReminderUseCases.createRelativeReminder`/`createExplicitReminder` ← `RoomReminderSyncRepository` (`scheduleRevision=1`) + Outbox `SCHEDULE_ALARM` ← `onOutboxCommitted()` ← `AlarmOutboxDispatcher`؛ أو من `TaskDetailScreen` (`TaskDetailRemindersCard` + `TaskDetailRecurrenceCard`). | **PASS مع ملاحظة (`MINOR` DEF-06)** | مغطى جزئياً في `Unit4A2DTimePickerAndEditorTest` (بدون تصريف Outbox) وفي `Unit4A2D3TaskDetailRemindersTest` / `Unit4A2D3TaskDetailRecurrenceTest`. **فجوة:** لا يوجد اختبار E2E يربط `TodayScreen`/`TasksScreen` ← `TaskEditorSheet` ← `AlarmOutboxDispatcher.drain()`. |
| **2** | **تعديل تاريخ ووقت المهمة مع وجود تذكيرات** | `TaskEditorSheet`/`TaskDetailViewModel.postponeToDate` ← `TaskUseCases.updateTask`/`postponeTask` ← `RoomTaskRepository.update` (`RoomTaskRepository.kt:54-127`) ← `TaskReminderRules.resolveSyncActionOnTaskScheduleChange` (`TaskReminderRules.kt:108-155`) ← ترقية `scheduleRevision` + `PENDING_SYNC` + `markPendingTaskDeliveriesStale` + إيداع `SCHEDULE_ALARM`. | **PARTIAL (`MAJOR` DEF-02)** | مغطى في `Unit4ATaskSyncAndRulesTest` عبر `TaskUseCases`. **خلل:** أي تذكير صريح (`Explicit`) مخزّن بـ `offsetMinutes == 0` على مهمة لها `startTime` يُستبدل قسراً بـ `task.startTime` عند تعديل التاريخ/التأجيل (`TaskReminderRules.kt:149-154`)، و`TaskEditorSheet` يعرض التذكيرات عند `0` كـ `explicitTime` (`TaskEditorSheet.kt:183-185`). |
| **3** | **تعديل قاعدة التكرار دون التأثير على المهمة الحالية** | `TaskDetailRecurrenceCard` ← `TaskDetailViewModel.saveRecurrence` (`TaskDetailViewModel.kt:371-410`) أو `TaskEditorSheet` (`TaskEditorSheet.kt:419-437`) ← `TaskUseCases.saveTaskRecurrence` (`TaskUseCases.kt:163-189`) ← `RoomTaskRepository.upsertTaskRecurrence` (جدول `task_recurrences` فقط دون لمس `tasks` أو `task_reminders`). | **PASS** | مغطى في `Unit4A2D3TaskDetailRecurrenceTest` من جانب تحديث/حفظ التكرار. **فجوة طفيفة:** الاختبار لا يتحقق صراحةً بالـ assertions من ثبات حقول `TaskEntity` و`TaskReminderEntity` بعد التعديل (تم التحقق منه بالكود). |
| **4** | **إكمال مهمة متكررة وإنشاء occurrence جديد** | `TaskDetailViewModel.completeTask` (`289-312`) / `TodayViewModel.toggleTaskCompletion` (`202-220`) / `TasksViewModel.completeOrReopenTask` (`225-237`) ← `TaskUseCases.completeTaskWithNextOccurrence` ← `RoomTaskRepository.completeTaskWithRecurrence` (`151-291`) داخل `withTransaction`: تحويل الحالية إلى `COMPLETED` + إلغاء تذكيراتها + إنشاء `nextTask` (`PENDING`) + `nextRecurrence` + روابط الأهداف (`GoalTaskCrossRef`) مع احترام `endDate`. | **PASS مع ملاحظة (`MINOR` DEF-07)** | مغطى بقوة في `Unit4ATaskSyncAndRulesTest` و`Unit4A2D3TaskDetailRecurrenceTest` (بما فيه rollback عند الفشل وSnackbar تاريخ النسخة التالية في `TaskDetailScreen`). **فجوة:** `TodayViewModel` يعرض رسالة إكمال عامة دون تاريخ النسخة القادمة. |
| **5** | **نسخ التذكيرات وإنشاء `requestCode`s جديدة وأوامر Outbox صحيحة** | `RoomTaskRepository.completeTaskWithRecurrence` (`179-190, 245-276`): لقطة `enabledRemindersBeforeCompletion` قبل إلغاء تذكيرات المهمة المكتملة (`CANCEL_ALARM_AND_NOTIFICATION`)، ثم لكل تذكير مفعّل: `resolveNextOccurrenceReminderTrigger` + `allocateNextRequestCodeLocked()` + إدراج `TaskReminderEntity` (`scheduleRevision=1L`) + `stageScheduleAlarmLocked`. | **PASS** | مغطى بدقة في `Unit4ATaskSyncAndRulesTest` و`Unit4A2D3TaskDetailRecurrenceTest` و`Phase2PersistenceTest` (تفرد `requestCode` عبر الجداول). |
| **6** | **مسح تاريخ أو وقت البداية لمهمة مع وجود تذكيرات** | `TaskEditorSheet.selectDate("")` / `clearStartTime()` / `postponeTask(..., clearTimeWindow = true)` ← `RoomTaskRepository.update` ← `TaskReminderRules.resolveSyncActionOnTaskScheduleChange` (`115-147`) ← `PauseMissingReference` (`isEnabled = false`, `scheduleState = PAUSED`, `markPendingTaskDeliveriesStale`, `CANCEL_ALARM_AND_NOTIFICATION`). | **PARTIAL (`MAJOR` DEF-03)** | مغطى في `Unit4ATaskSyncAndRulesTest`. **خلل UX:** عند إعادة تعيين التاريخ/الوقت لاحقاً في `TaskEditorSheet` تبقى التذكيرات `PAUSED` بصمت دون إعادة تفعيل ودون شارة توضح حالتها المتوقفة في `TaskEditorRemindersSection`. |
| **7** | **إلغاء مهمة مع وجود تذكيرات وتكرار** | من `TaskDetailScreen`: زر الإلغاء ← تأكيد الحوار ← `TaskUseCases.cancelTask` ← `RoomTaskRepository.updateStatus` (`129-149`) ← `cancelTaskRemindersLocked` (إلغاء التذكيرات + `STALE` + Outbox `CANCEL_ALARM_AND_NOTIFICATION`)، مع بقاء `TaskRecurrenceEntity` دون توليد occurrence جديد، وبطاقات التفاصيل تقفل الإجراءات (`isTaskActive=false`). | **DEFECT (`CRITICAL` DEF-01)** | حراسة `TaskDetailViewModel`/`TaskDetailRemindersCard`/`TaskDetailRecurrenceCard` مغطاة. **خلل حرج:** عبر زر التعديل (`TaskDetailScreen:361` أو `TasksScreen`) يفتح `TaskEditorSheet` ويتيح تعديل/حذف التكرار والتذكيرات على مهمة `CANCELLED` لأن `TaskUseCases.saveTaskRecurrence`/`removeTaskRecurrence` و`TaskEditorSheet` لا تتحقق من `isTaskActive`. |
| **8** | **إعادة فتح مهمة متكررة مكتملة (أو ملغاة)** | `TaskDetailViewModel.reopenTask` (`320-339`) ← `TaskUseCases.reopenTask` (`114-134`) ← حالة `PENDING`، التذكيرات تبقى معطّلة إلى أن يفعلها المستخدم يدوياً (`TaskDetailRemindersCard`)، وبطاقة التكرار تعرض تحذير `showReopenRecurrenceWarning`. | **DEFECT (`CRITICAL` DEF-01 + `MINOR` DEF-04)** | مغطى للمهمة المكتملة في `Unit4A2D3TaskDetailRemindersTest` و`Unit4A2D3TaskDetailRecurrenceTest`. **خللان:** (1) `TaskUseCases` + `TaskEditorSheet` لا تحمي الحالة المنتهية أثناء بقاء المهمة `COMPLETED`؛ (2) `TaskDetailViewModel.reopenTask:325-328` يفعّل `reopenedRecurringState = true` حتى عند إعادة فتح مهمة `CANCELLED` متكررة رغم عدم وجود occurrence مولّد! |
| **9** | **حذف مهمة مع وجود تذكيرات وتكرار وتسليمات** | `TaskDetailViewModel.deleteTask` / `TasksViewModel.deleteTask` ← `TaskUseCases.deleteTask` ← `RoomTaskRepository.deleteById` (`293-316`): يضع `STALE` للتسليمات ويودع `CANCEL_ALARM_AND_NOTIFICATION` لكل تذكير **قبل** `taskDao.deleteById(id)`، ثم `ON DELETE CASCADE` يحذف `task_recurrences` و`task_reminders` و`task_reminder_deliveries` و`goal_task_cross_ref`. | **PASS** | مغطى بقوة في `Phase2PersistenceTest` و`Unit3B3TaskDetailTest` و`Unit4ATaskSyncAndRulesTest` (بما فيها استخدام `localClock`). |
| **10** | **تأجيل التذكير (`Snooze`) دون تغيير جدول المهمة** | من `TaskDetailRemindersCard` أو من زر الإشعار (`ACTION_SNOOZE_TASK` → `NotificationActionHandler.handleIntent:122-148`) ← `TaskReminderUseCases.snoozeReminder` (`209-241`) ← `RoomReminderSyncRepository.snoozeTaskReminder` (تحديث `triggerDate`/`triggerTime` للتذكير و`scheduleRevision++` و`SCHEDULE_ALARM` + إبطال التسليم السابق دون لمس `tasks`). | **PASS مع فجوة E2E** | مغطى في `Unit4ATaskSyncAndRulesTest` و`Unit4A2BNotificationDeliveryTest` و`Unit4A2D3TaskDetailRemindersTest`. **فجوة:** لا يوجد اختبار متسلسل يتحقق من إقلاع المنبه المؤجَّل (Snoozed) عند `rev=2` وإصدار إشعاره الثاني. |
| **11** | **إكمال المهمة من إجراء الإشعار (`Notification Action`)** | `NotificationActionReceiver` (`155-187`) ← `NotificationActionHandler.handleIntent` (`43-152`): يتحقق من تطابق `task`/`reminder`/`isTaskActive`/`isEnabled`/`expectedRevision`/حالة `DeliveryState` ← `taskUseCases.completeTask(task.id)` (التي تفوّض إلى `completeTaskWithNextOccurrence`) ← `notificationCanceller.cancelNotification`. نقر جسم الإشعار يمرر `EXTRA_OPEN_TASK_ID` إلى `MainActivity` → `YawmiShell:97-105`. | **PASS مع فجوة UI** | `ACTION_COMPLETE_TASK` وتكراره مغطى بالكامل في `Unit4A2BNotificationDeliveryTest`. **فجوة:** فتح `TaskDetailRoute` من `MainActivity`/`YawmiShell` عند تمرير `pendingOpenTaskId` لم يُختبر كاختبار Compose UI متكامل. |
| **12** | **إعادة تشغيل الجهاز وتغيير المنطقة الزمنية والوقت والتاريخ** | في الخلفية/النظام: `SystemReconcileReceiver` (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED`, `DATE_CHANGED`, `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`) ← `SystemReconciliationWorker` ← `ReminderReconciler` + `YawmiApplication.onActivityResumed`. داخل الواجهة: `ObserveTodayTimeAndLifecycle` في `TodayScreen` و`TasksScreen` و`TaskDetailScreen`. | **PASS مع ملاحظة (`MINOR` DEF-05)** | مغطى بشمولية في `Unit4A2CSystemReconciliationTest` و`Unit3B4TodayLifecycleAndTimeTest` و`Unit4ATimeAndDstTest`. **خلل طفيف:** `TaskEditorScheduleSection.kt:77-82` يجمّد `todayStr`/`tomorrowStr` عبر `remember(localClock)` إذا بقيت ورقة المحرر مفتوحة عبر منتصف الليل أو تغيير المنطقة الزمنية. |
| **13** | **التبديل بين العربية RTL والإنجليزية LTR والوضع الداكن والفاتح** | `ProvideYawmiLocale` يضبط `Locale` و`LayoutDirection` (`Rtl`/`Ltr`)؛ `YawmiTheme` يضبط `ColorScheme` الداكن والفاتح (`MaterialTheme.colorScheme` + `LocalYawmiSemanticColors`) عبر `TodayScreen`, `TasksScreen`, `TaskDetailScreen`, `TaskDetailRemindersCard`, `TaskDetailRecurrenceCard`, `TaskEditorSheet`. | **PASS مع فجوة تغطية Light** | مغطى في `Phase1FoundationTest`, `Unit3B2TasksScreenTest`, `Unit3B3TaskDetailTest`, `Unit3B4TodayScreenTest`, `Unit4A2DTimePickerAndEditorTest`, `Unit4A2D3TaskDetailRemindersTest`, `Unit4A2D3TaskDetailRecurrenceTest`. **فجوة:** `Unit4A2D3TaskDetailRecurrenceTest` و`YawmiTimePickerDialog` لم تُختبر في الوضع الفاتح (`Light`). |

---

## 3. مصفوفة الانحدار

تم التحقق الفعلي من تقارير نتائج الاختبارات XML الموجودة في `app/build/test-results/testDebugUnitTest/` ومراجعة الشيفرة المصدرية الكاملة لكل ملفات الاختبار الـ 22 (إجمالي **150 اختباراً، 0 إخفاق، 0 أخطاء، 0 تجاوز**، بتاريخ تنفيذ `2026-11-28T07:28:39Z` – `2026-11-28T07:32:44Z`):

| المرحلة / الوحدة | ملف الاختبار (`TEST-*.xml`) | العدد / النتيجة | وقت التنفيذ (`ts`) | تقييم عمق التأكيدات (Assertion Depth) |
|---|---|---|---|---|
| **Baseline** | `ExampleUnitTest` / `ExampleRobolectricTest` | 1 + 1 (`PASS`) | `07:28:39Z` – `07:28:47Z` | اختباران تأسيسيان (حسابي + Room/Robolectric sanity). |
| **Phase 1** | `Phase1FoundationTest` | 6 / 6 (`PASS`) | `07:28:48Z` | تأكيد حقيقي لـ `MainActivity`، تخطيط RTL، تبديل AR/EN وDark/Light/System وحفظ DataStore وترويسة التاريخ المترجمة. |
| **Phase 2** | `Phase2PersistenceTest` | 12 / 12 (`PASS`) | `07:29:06Z` | عمق عالٍ: مطابقة `1.json` (18 جدولاً)، FKs/CASCADE/SET NULL، رفض الأيتام، الفهارس الفريدة، التراجع عن المعاملة، Outbox seq/coalesce، triggers تفرد `requestCode`، ومنع الهجرة التدميرية. |
| **Unit 3A** | `Unit3ATaskPersistenceTest`, `Unit3ATaskRulesTest` | 1 + 6 (`PASS`) | `07:29:16Z` – `07:29:25Z` | فحص بقاء البيانات بعد إغلاق/إعادة فتح Room، وقواعد الآلة الحالتية والتحقق والتطبيع والتأجيل. |
| **Unit 3B.1** | `Unit3B1EditorAndCategoryTest` | 4 / 4 (`PASS`) | `07:29:26Z` | CRUD التصنيفات، `CategoryManagerSheet`، وإنشاء/تعديل المهمة عبر `TaskEditorSheet` على Room حقيقي. |
| **Unit 3B.2** | `Unit3B2TaskQueryRulesTest`, `Unit3B2TasksScreenTest` | 3 + 3 (`PASS`) | `07:29:42Z` – `07:29:43Z` | بحث وتصفية متعددة المعايير وفرز، مع اختبارات RTL/LTR كاملة وتأكيد الثبات عبر إعادة إنشاء الـ Activity. |
| **Unit 3B.3** | `Unit3B3TaskDetailTest` | 5 / 5 (`PASS`) | `07:29:59Z` | دورة الحياة الكاملة في `TaskDetailScreen` (Start/Postpone/Complete/Reopen/Cancel/Edit/Delete cascade/`NotFound`) وتدفق `YawmiRootApp`. |
| **Unit 3B.4** | `Unit3B4TodayDashboardRulesTest`, `Unit3B4TodayLifecycleAndTimeTest`, `Unit3B4TodayScreenTest` | 3 + 7 + 4 (`PASS`) | `07:30:16Z` – `07:30:31Z` | عدم ازدواج العدّ، حدود المنطقة الزمنية ومنتصف الليل وDST، عودة الواجهة (<5ث و>5ث)، عدم تسرب المؤقتات، وتزامن `Today ↔ Detail ↔ Tasks`. |
| **Unit 4A.1** | `Unit4ATaskSyncAndRulesTest`, `Unit4ATimeAndDstTest` | 10 + 5 (`PASS`) | `07:32:33Z` – `07:32:44Z` | مزامنة Room للتذكيرات والتكرار والأهداف، Rollback ذري عند فشل `completeTaskWithRecurrence`، فجوات وتداخل DST، وفترة سماح 15د. |
| **Unit 4A.2A** | `Unit4A2AOutboxDispatcherTest` | 20 / 20 (`PASS`) | `07:30:49Z` | عقد الإيجار (`Lease fencing`)، استعادة الأعطال، CAS revision rollback، دمج الأوامر، حدود 5 محاولات، عزل Task-only، و`FLAG_NO_CREATE`. |
| **Unit 4A.2B** | `Unit4A2BNotificationDeliveryTest` | 14 / 14 (`PASS`) | `07:31:01Z` | مقاومة التزوير (`AlarmReceiver`)، تعافي `activeNotifications` بعد التعطل، حالات الصلاحيات والقنوات، `ACTION_COMPLETE_TASK`/`ACTION_SNOOZE_TASK`، وتوطين AR/EN. |
| **Unit 4A.2C** | `Unit4A2CSystemReconciliationTest` | 16 / 16 (`PASS`) | `07:31:13Z` | `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`, `DATE_CHANGED`, سحب/استعادة إذن المنبه الدقيق، قفل التخزين (`directBootAware=false`)، وتأكيدات `AndroidManifest.xml`. |
| **Unit 4A.2D** | `Unit4A2DScheduleAndDurationTest`, `Unit4A2DTimePickerAndEditorTest` | 10 + 6 (`PASS`) | `07:32:02Z` – `07:32:13Z` | نوافذ اليوم التالي (21:00→07:00)، حساب المدة مع DST، التحقق من `identityHash` لـ Schema v1، منتقي الوقت، والإنقاذ الجزئي (`partialSaveWarning`). |
| **Unit 4A.2D.3A/B** | `Unit4A2D3TaskDetailRemindersTest` | 8 / 8 (`PASS`) | `07:31:44Z` | إدارة التذكيرات في `TaskDetailScreen` (النسبية والصريحة والغفوة والتفعيل/الإيقاف والحذف وحالة `offsetMinutes==0` وحالات Terminal). |
| **Unit 4A.2D.3C** | `Unit4A2D3TaskDetailRecurrenceTest` | 5 / 5 (`PASS`) | `07:31:26Z` | إدارة التكرار في `TaskDetailRecurrenceCard` (`CUSTOM_DAYS` "كل ٢ أسبوع"/"Every 2 weeks"، الإكمال وتوليد النسخة القادمة ونسخ التذكير، تحذير إعادة الفتح، وحراسة Terminal). |

> **ملاحظة انحدار مهمة:** يوجد ملف `Unit4BCalendarTest.kt` ضمن حزمة الاختبارات لكنه يتبع **Phase 4B** (خارج نطاق تدقيق Phase 4A.2D وغير مدرج في ملفات نتائج XML الـ 22)، ولم يتم لمسه أو تشغيله أثناء هذا التدقيق.

---

## 4. سلامة قاعدة البيانات والهندسة

1. **ثبات Schema v1 وقيمة `identityHash`:**
   - قاعدة البيانات `YawmiDatabase` على الإصدار `1` (`YawmiDatabase.kt:59, 86`) مع `exportSchema = true`.
   - تم التحقق من ملف المخطط `/app/schemas/com.example.data.local.db.YawmiDatabase/1.json:4-5`: `"version": 1` و`"identityHash": "37c351a60c04e881c6bd50156833f191"` — سليم ومتطابق بنسبة 100% دون أي تعديل.
2. **سياسة الهجرة غير التدميرية (`YawmiMigrations`):**
   - `YawmiMigrations.ALL_MIGRATIONS = emptyArray()` (`YawmiMigrations.kt:11`)، ولا يوجد أي استدعاء لـ `fallbackToDestructiveMigration()` في `YawmiDatabase.kt:88-100`.
3. **المفاتيح الأجنبية والقيود والذرية (`Foreign Keys & Transactions`):**
   - `PRAGMA foreign_keys = ON` مفعل في `IntegrityCallback.onOpen` (`YawmiDatabase.kt:111`).
   - عمليات `update` و`updateStatus` و`completeTaskWithRecurrence` و`deleteById` و`upsertTaskRecurrence` وعمليات `RoomReminderSyncRepository` كلها محاطة بـ `database.withTransaction`.
   - في `deleteById` (`RoomTaskRepository.kt:296-311`), يتم تسليم أوامر إلغاء المنبهات وإبطال التسليمات **قبل** تنفيذ `taskDao.deleteById` لضمان بقاء معرفات التذكيرات و`requestCode` متاحة في Outbox قبل الحذف التتابعي (`CASCADE`).
4. **منع المحركات الموازية:**
   - تم فحص مجلد `app/src/main/java/com/example/feature/` بالكامل عبر `grep`: **0** استخدامات مباشرة لـ `AlarmManager` أو `NotificationManager` أو `AlarmOutboxDispatcher` أو `NotificationDeliveryEngine` أو `ReminderReconciler` أو `computeNextOccurrenceDate` داخل طبقات العرض.
5. **أمن Outbox و`PendingIntent` وتفرد `requestCode`:**
   - يتم تخصيص `requestCode` عبر `NEXT_REMINDER_REQUEST_CODE` في `app_metadata` مع حماية SQLite Triggers (`trg_*_global_reqcode_insert` / `update` في `YawmiDatabase.kt:133-166`) تمنع أي تكرار عبر جداول المهام والعادات والأهداف.
   - معرفات `PendingIntent` و`scheduleRevision` تحمي من تكرار الإنذارات القديمة أو الإجراءات المتأخرة (`NotificationActionHandler` + `AlarmFireHandler`).
6. **Local-First وRTL/LTR:**
   - التطبيق يعمل محلياً بالكامل (Room + DataStore + AlarmManager + WorkManager) دون أي اعتماد شبكي؛ ويدعم `supportsRtl="true"` (`AndroidManifest.xml:17`) و`ProvideYawmiLocale` للغتين العربية RTL والإنجليزية LTR.

---

## 5. قائمة العيوب المصنفة

### `BLOCKER`
- **لا يوجد** أي عيب مصنّف `BLOCKER` على مستوى البنية التحتية أو قاعدة البيانات أو نواة Outbox/Notifications/Reconciler.

---

### `CRITICAL`

1. **`DEF-01` — ثغرة الحراسة على المهام المنتهية (`COMPLETED` / `CANCELLED`) في `TaskUseCases` و`TaskEditorSheet`**
   - **السيناريوهات المتأثرة:** السيناريو 7 (إلغاء مهمة) والسيناريو 8 (إعادة فتح/إدارة مهمة مكتملة أو ملغاة).
   - **الموقع:**
     - `app/src/main/java/com/example/domain/task/TaskUseCases.kt` (الأسطر `163–195`: `saveTaskRecurrence`, `removeTaskRecurrence`)
     - `app/src/main/java/com/example/feature/tasks/components/TaskEditorSheet.kt` (الأسطر `419–497` و`992–1012`)
     - `app/src/main/java/com/example/feature/tasks/detail/TaskDetailScreen.kt` (الأسطر `360–366`) و`TasksScreen.kt` (زر التعديل عبر `TaskListItemCard`).
   - **الدليل:** في حين يحظر `TaskDetailViewModel.saveRecurrence`/`removeRecurrence` (`TaskDetailViewModel.kt:381-391, 422-432`) تعديل التكرار عندما لا تكون المهمة `PENDING` أو `IN_PROGRESS`، لا يحتوي `TaskUseCases.saveTaskRecurrence` ولا `removeTaskRecurrence` على هذا التحقق، كما يتيح `TaskEditorSheet` فتح وتعديل التكرار وإزالة التذكيرات حتى لمهام `COMPLETED` أو `CANCELLED`. النتيجة: يمكن للمستخدم فتح تعديل مهمة مكتملة أو ملغاة وتغيير/حذف سجل `task_recurrences` التاريخي أو إضافة تكرار لمهمة ملغاة أو حذف تذكيراتها المعطّلة، بينما محاولة إضافة تذكير جديد في نفس الشاشة تفشل جزئياً بـ `partialSaveWarning` (`TaskNotActive`) بعد حفظ المهمة.
   - **الإصلاح المستهدف المحدود:**
     1. إضافة حراسة `!TaskReminderRules.isTaskActive(existing.status)` داخل `TaskUseCases.saveTaskRecurrence` و`TaskUseCases.removeTaskRecurrence` (بإرجاع `TaskDomainResult.Failure(InvalidStatusTransition)` أو منعه صراحةً).
     2. في `TaskEditorSheet`، عند كون `persistedTask?.status` هي `COMPLETED` أو `CANCELLED`، تعطيل/قفل قسمي `TaskEditorRecurrenceSection` و`TaskEditorRemindersSection` مع تلميح واضح وتخطي مزامنة التكرار والتذكيرات عند الحفظ.

---

### `MAJOR`

2. **`DEF-02` — التصادم الدلالي لـ `offsetMinutes == 0` في Schema v1 يؤدي لاستبدال التذكير الصريح (`Explicit Reminder`) بوقت بداية المهمة عند تعديل/تأجيل الجدول**
   - **السيناريوهات المتأثرة:** السيناريو 2 (تعديل تاريخ/وقت المهمة)، والسيناريو 6، والسيناريو 10/11 (بعد الاستئناف).
   - **الموقع:**
     - `app/src/main/java/com/example/domain/task/TaskReminderRules.kt` (الأسطر `99` و`149–154`)
     - `app/src/main/java/com/example/domain/task/TaskReminderUseCases.kt` (الأسطر `77` و`108–117`)
     - `app/src/main/java/com/example/feature/tasks/components/TaskEditorSheet.kt` (الأسطر `183–185`)
   - **الدليل:** يُخزَّن كل من التذكير النسبي «عند وقت البداية» (`offsetMinutes = 0`) والتذكير بوقت صريح (`createExplicitReminder`) بقيمة `offsetMinutes = 0` لعدم وجود عمود نوع التذكير في Schema v1. إذا أنشأ المستخدم مهمة الساعة `10:00` وأضاف تذكيراً صريحاً الساعة `08:00` ثم عدّل التاريخ أو أجّل المهمة للغد (مع بقاء `startTime = 10:00`)، فإن `resolveSyncActionOnTaskScheduleChange` (`TaskReminderRules.kt:149-154`) يحسب `10:00 - 0m = 10:00` فيستبدل وقت التذكير الصريح `08:00` صمتاً بـ `10:00`! كما أن `TaskEditorSheet.loadExistingExtras` (`183-185`) يفسّر كل `offsetMinutes == 0` كوقت صريح `explicitTime = rem.triggerTime` مما يسبب تبايناً بين معاينة المحرر ونتيجة `RoomTaskRepository.update` عند تغيير `startTime`.
   - **الإصلاح المستهدف المحدود (دون المساس بـ Schema v1):**
     - في `resolveSyncActionOnTaskScheduleChange`: عندما يكون `reminder.offsetMinutes == 0` و`previousTask.startTime != null` و`updatedTime != null` ولكن `reminder.triggerTime != previousTask.startTime` (وهي دلالة قاطعة على أن التذكير صريح وليس «عند وقت البداية»؛ ما عدا حالة الغفوة المؤقتة التي يظل فيها التذكير مستقل الوقت حتى الإطلاق)، يُحافظ على `reminder.triggerTime` عند تغيير التاريخ فقط أو يُعالج دون استبدال غير مقصود، مع توضيح الحالة في `TaskEditorSheet` دون إضافة أي عمود في Room.

3. **`DEF-03` — بقاء التذكيرات معلّقة (`PAUSED`) بصمت بعد مسح التاريخ/وقت البداية ثم استعادتهما عبر `TaskEditorSheet`**
   - **السيناريو المتأثر:** السيناريو 6 (مسح تاريخ أو وقت بداية مهمة ثم استعادته).
   - **الموقع:**
     - `app/src/main/java/com/example/data/repository/RoomTaskRepository.kt` (الأسطر `99–118`)
     - `app/src/main/java/com/example/domain/task/TaskReminderRules.kt` (الأسطر `113`)
     - `app/src/main/java/com/example/feature/tasks/components/TaskEditorSheet.kt` (الأسطر `175–187`) و`TaskEditorExtrasSections.kt` (الأسطر `469–501`).
   - **الدليل:** مسح `scheduledDate` أو `startTime` يحوّل التذكيرات المعتمدة عليه إلى `isEnabled = false, scheduleState = PAUSED`. عند فتح `TaskEditorSheet` وإعادة تحديد تاريخ ووقت للمهمة، تعيد `resolveSyncActionOnTaskScheduleChange` القيمة `Unchanged` لأن `!reminder.isEnabled`، كما تعرض `TaskEditorRemindersSection` عناصر `reminderDrafts` دون تمييز التذكيرات الموقوفة (`PAUSED`) عن المفعّلة، مما يوهم المستخدم أن التذكير سيعمل تلقائياً.
   - **الإصلاح المستهدف المحدود:** عرض شارة/مؤشر حالة (`PAUSED`) على التذكيرات المعطّلة داخل `DraftReminderItem` في `TaskEditorExtrasSections.kt` (أو إتاحة خيار إعادة التفعيل عند استعادة الجدول في `TaskEditorSheet`).

---

### `MINOR`

4. **`DEF-04` — ظهور تحذير «يوجد تكرار قادم منشأ مسبقاً» عند إعادة فتح مهمة متكررة ملغاة (`CANCELLED`)**
   - **السيناريو المتأثر:** السيناريو 8.
   - **الموقع:** `app/src/main/java/com/example/feature/tasks/detail/TaskDetailViewModel.kt` (الأسطر `320–338` و`168` و`200–202`).
   - **الدليل:** الدالة `reopenTask()` تتحقق فقط من `val hadRecurrence = taskRepository.getTaskRecurrence(taskId) != null` دون التأكد من أن الحالة السابقة كانت `TaskStatus.COMPLETED`. لأن إلغاء مهمة متكررة (`cancelTask`) لا يولّد occurrence تالياً، فإن إعادة فتحها تعرض للمستخدم تحذيراً مضللاً (`task_detail_recurrence_reopen_hint`).
   - **الإصلاح المستهدف المحدود:** قصر تعيين `reopenedRecurringState.value = true` على الحالة التي كانت فيها المهمة `TaskStatus.COMPLETED` قبل إعادة الفتح.

5. **`DEF-05` — ثبات خيارات «اليوم» و«غداً» في `TaskEditorScheduleSection` عبر منتصف الليل أو تغيير المنطقة الزمنية**
   - **السيناريو المتأثر:** السيناريو 12.
   - **الموقع:** `app/src/main/java/com/example/feature/tasks/components/TaskEditorScheduleSection.kt` (الأسطر `77–82`).
   - **الدليل:** `todayStr` و`tomorrowStr` تُحسب عبر `remember(localClock)` بدلاً من الاعتماد على تاريخ تفاعلي متجدد مثل `ObserveTodayTimeAndLifecycle`، فإذا بقيت ورقة المحرر مفتوحة عند منتصف الليل أو تغيّر المنطقة الزمنية تظل الشريحتان مربوطتين بالتاريخ القديم.
   - **الإصلاح المستهدف المحدود:** تمرير `todayDate: LocalDate` التفاعلي من الشاشة الأب (أو تحديثه عبر `ObserveTodayTimeAndLifecycle`) إلى `TaskEditorSheet` و`TaskEditorScheduleSection`.

6. **`DEF-06` — عدم استدعاء `onSaved` عند الإغلاق بعد `partialSaveWarning` + احتمال سباق خفيف في `loadExistingExtras`**
   - **السيناريوهات المتأثرة:** السيناريو 1 و2.
   - **الموقع:** `app/src/main/java/com/example/feature/tasks/components/TaskEditorSheet.kt` (الأسطر `162–188`, `499–505`, `642–645`, `1051–1061`).
   - **الدليل:** عند إنشاء مهمة بنجاح وفشل حفظ التكرار/التذكير (`partialSaveWarning != null`) ثم ضغط «إغلاق»، يُنفّذ `onDismiss` دون `onSaved` (التحديث يظهر عبر Room Flow لكن تغذية `Snackbar` للإنشاء لا تصل)، كما لا ينتظر `handleSave` اكتمال `loadExistingExtras` إذا ضُغط الحفظ فور فتح التعديل.
   - **الإصلاح المستهدف المحدود:** إضافة حالة `extrasLoaded` لتأمين وضع التعديل، وتتبع `hasSavedTask` لتبليغ الوالد عند الإغلاق بعد الحفظ الجزئي.

7. **`DEF-07` — غياب تاريخ النسخة القادمة في Snackbar عند إكمال مهمة متكررة من `TodayScreen` / `TasksScreen`**
   - **السيناريو المتأثر:** السيناريو 4.
   - **الموقع:** `app/src/main/java/com/example/feature/dashboard/TodayViewModel.kt` (`202–220`) و`app/src/main/java/com/example/feature/tasks/TasksViewModel.kt` (`225–237`).
   - **الدليل:** `TodayViewModel` يستدعي `completeTask` (الذي يولّد النسخة التالية فعلاً) لكنه يعرض رسالة إكمال عامة فقط، بينما `TaskDetailViewModel` يعرض التاريخ الجديد للنسخة المولدة.
   - **الإصلاح المستهدف المحدود:** استخدام `completeTaskWithNextOccurrence` في `TodayViewModel` (و`TasksViewModel` عند الحاجة) لعرض رسالة متناسقة تتضمن `nextScheduledDate`.

8. **`DEF-08` — فجوات تغطية اختبارات التكامل الشامل (E2E) وبعض أوضاع العرض**
   - **السيناريوهات المتأثرة:** 1، 2، 3، 10، 11، 13.
   - **التفاصيل:**
     - غياب اختبار UI يفتح `YawmiRootApp`/`MainActivity` عبر `EXTRA_OPEN_TASK_ID` ويتحقق من الانتقال الفعلي إلى `TaskDetailScreen` (السيناريو 11).
     - غياب اختبار E2E يربط `TaskEditorSheet` (إنشاء مع تكرار+تذكير، وتعديل التاريخ/الوقت مع تذكير) بتصريف `AlarmOutboxDispatcher` (السيناريوهان 1 و2).
     - غياب تحقق متسلسل لإطلاق المنبه الثاني بعد `Snooze` عند `scheduleRevision = 2` (السيناريو 10).
     - غياب assertion صريح لعدم تعديل `TaskEntity`/`TaskReminderEntity` في `Unit4A2D3TaskDetailRecurrenceTest` (السيناريو 3).
     - غياب اختبار الوضع الفاتح (`Light`) لـ `TaskDetailRecurrenceCard` و`YawmiTimePickerDialog` (السيناريو 13).

---

### `INFORMATIONAL`

- **`INFO-01`:** Schema v1 (`identityHash = "37c351a60c04e881c6bd50156833f191"`) محمي بالكامل دون أي هجرة تدميرية.
- **`INFO-02`:** `SystemReconcileReceiver` مضبوط عن قصد بـ `directBootAware = false` (`AndroidManifest.xml:50-71`) وفق قرار الوحدة 4A.2C؛ عند إعادة التشغيل يبدأ التصالح بمجرد فك قفل الجهاز ووصول `BOOT_COMPLETED` أو فتح التطبيق (`YawmiApplication.onActivityResumed`).
- **`INFO-03`:** ملف الاختبار `Unit4BCalendarTest.kt` خاص بالمرحلة 4B وغير مشمول في نتائج الـ 22 حزمة المعتمدة لـ Phase 4A.2D، ولم يُعدّل أو يُشغّل أثناء هذا التدقيق.

---

## 6. الملفات التي قد تحتاج تعديلاً

عند الإذن بتنفيذ حزمة التحصين النهائية (Hardening Pass)، تنحصر التعديلات المحتملة في قائمة ضيقة دون المساس بـ Schema v1 أو `1.json` أو محركات Outbox/Notification/Reconciler:

1. `app/src/main/java/com/example/domain/task/TaskUseCases.kt` — حراسة `isTaskActive` في `saveTaskRecurrence` و`removeTaskRecurrence`.
2. `app/src/main/java/com/example/feature/tasks/components/TaskEditorSheet.kt` — قفل تعديل التكرار والتذكيرات للمهام المنتهية (`COMPLETED`/`CANCELLED`)، ومزامنة `extrasLoaded` و`todayDate`.
3. `app/src/main/java/com/example/feature/tasks/components/TaskEditorExtrasSections.kt` — دعم حالة القراءة فقط (`enabled`) وإظهار شارة `PAUSED` على التذكيرات الموقوفة.
4. `app/src/main/java/com/example/feature/tasks/components/TaskEditorScheduleSection.kt` — استقبال `todayDate` التفاعلي بدلاً من `remember(localClock)`.
5. `app/src/main/java/com/example/domain/task/TaskReminderRules.kt` — معالجة Schema-v1-safe للتفريق في `resolveSyncActionOnTaskScheduleChange` بين التذكير الصريح وتذكير «عند وقت البداية» عند `offsetMinutes == 0`.
6. `app/src/main/java/com/example/feature/tasks/detail/TaskDetailViewModel.kt` — قصر `reopenedRecurringState` على المهام المعاد فتحها من `COMPLETED` فقط.
7. `app/src/main/java/com/example/feature/dashboard/TodayViewModel.kt` (اختياري) — إظهار تاريخ النسخة القادمة في Snackbar عند إكمال مهمة متكررة من `TodayScreen`.
8. حزمة اختبارات التكامل النهائية في `app/src/test/java/com/example/` (إضافة `Unit4A2D3DIntegrationRegressionTest.kt` أو توسيع `Unit4A2D3*Test.kt` و`Unit4A2DTimePickerAndEditorTest.kt`).

---

## 7. خطة الاختبارات النهائية

بعد تنفيذ التعديلات المستهدفة في القسم 6 (بإذن لاحق)، يجب تشغيل الاختبارات التالية:

1. **اختبارات التكامل الجديدة المقترحة (لسد الفجوات في القسم 2 و5):**
   - محاولة تعديل/حذف التكرار وإضافة/حذف التذكيرات لمهام `COMPLETED` و`CANCELLED` عبر `TaskUseCases` و`TaskEditorSheet` (تثبت المنع وعدم تغيير قاعدة البيانات).
   - تعديل/تأجيل تاريخ مهمة مجدولة لها `startTime = 10:00` وتذكير صريح `08:00` (`offsetMinutes = 0`) والتأكد من بقاء `08:00` دون استبدال، مقابل تحرّك تذكير «عند البداية» (`10:00`) مع الوقت الجديد.
   - مسح تاريخ/وقت البداية ثم استعادتهما في `TaskEditorSheet` مع التحقق من ظهور حالة `PAUSED` بوضوح.
   - إعادة فتح مهمة متكررة `CANCELLED` والتحقق من عدم ظهور `task_detail_recurrence_reopen_hint`.
   - تدفق E2E كامل: إنشاء مهمة مجدولة من `TodayScreen`/`TasksScreen` عبر `TaskEditorSheet` بتكرار وتذكير ← تصريف `AlarmOutboxDispatcher` ← غفوة التذكير (`Snooze`) ← إطلاق المنبه الثاني (`rev=2`) ← فتح `YawmiRootApp` عبر `EXTRA_OPEN_TASK_ID` إلى `TaskDetailScreen`.
   - اختبار `TaskDetailRecurrenceCard` و`YawmiTimePickerDialog` بالوضع الفاتح (`Light`) في العربية RTL والإنجليزية LTR.

2. **مصفوفة اختبارات الانحدار الـ 22 المعتمدة (تُشغّل عبر فلاتر `--tests` مع استثناء `Unit4BCalendarTest`):**
   - `Phase1FoundationTest`, `Phase2PersistenceTest`
   - `Unit3ATaskPersistenceTest`, `Unit3ATaskRulesTest`
   - `Unit3B1EditorAndCategoryTest`, `Unit3B2TaskQueryRulesTest`, `Unit3B2TasksScreenTest`, `Unit3B3TaskDetailTest`, `Unit3B4TodayDashboardRulesTest`, `Unit3B4TodayLifecycleAndTimeTest`, `Unit3B4TodayScreenTest`
   - `Unit4ATaskSyncAndRulesTest`, `Unit4ATimeAndDstTest`, `Unit4A2AOutboxDispatcherTest`, `Unit4A2BNotificationDeliveryTest`, `Unit4A2CSystemReconciliationTest`, `Unit4A2DScheduleAndDurationTest`, `Unit4A2DTimePickerAndEditorTest`, `Unit4A2D3TaskDetailRemindersTest`, `Unit4A2D3TaskDetailRecurrenceTest`, `ExampleUnitTest`, `ExampleRobolectricTest`
   - بالإضافة إلى تشغيل `compile_applet` للتحقق من نجاح بناء التطبيق الكامل وتطابق `identityHash = "37c351a60c04e881c6bd50156833f191"`.

---

## 8. التوصية النهائية

الهندسة المعمارية الأساسية للتخزين والمزامنة والتذكيرات والإشعارات والمصالحة سليمة بالكامل (**0 BLOCKER**، Schema v1 سليم بنسبة 100% بـ `identityHash = "37c351a60c04e881c6bd50156833f191"`، ونجاح **150/150** اختباراً عبر 22 ملف XML).  
مع ذلك، تبرز **ملاحظة حاسمة قبل إغلاق Phase 4A.2D أو بدء Unit 3D**: وجود عيب `CRITICAL` (`DEF-01`: غياب حراسة الحالة المنتهية في `TaskUseCases.saveTaskRecurrence`/`removeTaskRecurrence` و`TaskEditorSheet`) وعيبين `MAJOR` (`DEF-02` و`DEF-03`: تصادم `offsetMinutes == 0` للتذكير الصريح ووضوح حالة `PAUSED` في المحرر) إضافةً إلى العيوب الثانوية والفجوات الاختبارية في القسم 5، وكلها قابلة للمعالجة بإصلاح ضيّق ومحصور دون المساس بـ Schema v1 أو الوحدات المغلقة.

**READY FOR FINAL IMPLEMENTATION**
