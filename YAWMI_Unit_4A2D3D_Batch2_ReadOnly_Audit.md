# YAWMI — Unit 4A.2D.3D Batch 2 (`DEF-04` – `DEF-08`): Read-Only Engineering Audit

**Unit / Phase:** Phase 4A.2D — Unit 4A.2D.3D (Batch 2: `DEF-04` to `DEF-08`)  
**Checkpoint:** `UNIT_4A_2D_3D_BATCH_2_AUDIT_IN_PROGRESS`  
**Mode:** `STRICT READ-ONLY` (**تدقيق فقط — غير مصرّح بأي تعديل أو تنفيذ برمجي في هذه المرحلة**)  
**Baseline Reference:** `YAWMI_Unit_4A2D3D_ReadOnly_Integration_Audit.md` + `YAWMI_Unit_4A2D3D_Batch1_Final_Report.md` (`23` حزمة اختبار غير متعلقة بالتقويم: `159 / 159` PASS؛ `:app:assembleDebug` سليم؛ Schema v1 `identityHash = "37c351a60c04e881c6bd50156833f191"`).

---

## 1. الملخص التنفيذي (Executive Summary)

تم فحص الشيفرة الفعلية وحزم الاختبار ذات الصلة للبنود الخمسة (`DEF-04` إلى `DEF-08`) قراءةً فقط مع المحافظة التامة على قرارات وقيود **Batch 1 (`DEF-01` / `DEF-02` / `DEF-03`)** دون تعديل أو حذف أو إنشاء أي ملف مصدري أو اختباري أو مورد أو مخطط قاعدة بيانات:

1. **`DEF-04` (دورة حياة المهمة `CANCELLED` عند إعادة الفتح / الإشعارات وتحذير التكرار):**
   - **على مستوى نواة الإنذارات والإشعارات (Room / Outbox / Delivery / Reconciler):** دورة الإلغاء وإعادة الفتح سليمة معمارياً؛ فإلغاء المهمة يضبط `TaskReminderEntity` ذرياً إلى `isEnabled = false` و`scheduleState = ReminderScheduleState.CANCELLED` ويرفع `scheduleRevision` ويبطل التسليمات (`DeliveryState.STALE`) ويودع `CANCEL_ALARM` و`CANCEL_NOTIFICATION`. وعند `reopenTask()` تبقى التذكيرات معطلة (`CANCELLED`) ولا يصدر أي إشعار جديد ما لم يُعد المستخدم تفعيلها صراحةً.
   - **الخلل الوظيفي الفعلي المؤكد (`MINOR`):** `TaskDetailViewModel.reopenTask()` (`TaskDetailViewModel.kt:325-328`) يفعّل `reopenedRecurringState.value = true` لكل مهمة لها `TaskRecurrenceEntity` دون التحقق من أن حالتها السابقة كانت `COMPLETED`، فتظهر رسالة مضللة (`task_detail_recurrence_reopen_hint`) عند إعادة فتح مهمة متكررة `CANCELLED` رغم عدم توليد أي occurrence تالٍ.
   - **فجوة التحقق (`MAJOR` Coverage Gap):** لا يوجد اختبار تكامل فعلي ينفذ تسلسل `cancelTask -> reopenTask` مع سباقات تصريف `AlarmOutboxDispatcher` وتشغيل `AlarmReceiver`/`NotificationDeliveryEngine.recoverPendingDeliveries`/`ReminderReconciler.reconcile` على قاعدة بيانات واحدة.
2. **`DEF-05` (شرائح اليوم/غداً ونافذة الجدولة في `TaskEditorScheduleSection` عبر منتصف الليل وتغيير المنطقة الزمنية):**
   - **الخلل المؤكد (`MINOR`):** `TaskEditorScheduleSection.kt:77-82, 90-97` تجمّد `todayStr` و`tomorrowStr` و`localClock.zoneId()` عبر `remember(localClock)` على الكائن المفرد الثابت `LocalClock`، ولا تستقبل `todayDate`/`zoneId` تفاعلياً ولا ترصد `ObserveTodayTimeAndLifecycle`، مما يسبب ثبات الشرائح والحسابات إذا بقيت ورقة المحرر مفتوحة عبر منتصف الليل أو عند تغير المنطقة/الوقت/التاريخ.
3. **`DEF-06` (تغطية تكامل ملاحة فتح المهمة عبر `Today` و`Tasks` والإشعار وتكرار المهمة):**
   - **التكامل البرمجي للملاحة (`MainActivity` ↔ `YawmiShell` ↔ `TaskDetailRoute` ↔ `NotFound`):** يعمل بشكل صحيح ويمنع التكرار عند إعادة الإنشاء عبر `onConsumeOpenTaskId()` و`launchSingleTop = true`.
   - **فجوة UX (`MINOR`):** إكمال مهمة متكررة في `TaskDetailViewModel` (`84, 297-299`) و`TaskDetailScreen` (`166-169`) يُظهر `Snackbar` بتاريخ النسخة القادمة فقط دون زر إجراء أو تمرير `nextTaskId` لفتح الـ occurrence الجديد مباشرة.
   - **فجوة الاختبارات (`MAJOR`):** غياب اختبارات Compose UI متكاملة لفتح التفاصيل من `MainActivity` / `YawmiRootApp` عبر `EXTRA_OPEN_TASK_ID` (في `onCreate` و`onNewIntent` وإعادة إنشاء الـ Activity وحالة `NotFound` لمهمة محذوفة)، وغياب اختبار فتح التفاصيل من `TasksScreen` ومن تكرار المهمة.
4. **`DEF-07` (تغطية التكامل الكامل `TaskEditorSheet` + Reminders + `AlarmOutboxDispatcher`):**
   - **التدفق البرمجي للخطوات الـ 9:** مترابط بالكامل في الإنتاج، مع بقاء ملاحظة `DEF-06` القديمة الطفيفة في `TaskEditorSheet.kt:166-197, 430-653` (عدم انتظار `loadExistingExtras` قبل الحفظ السريع، وعدم استدعاء `onSaved` عند الإغلاق بعد `partialSaveWarning`) وبقاء قيد `DEF-02` لـ Schema v1 عند `offsetMinutes == 0`.
   - **فجوة الاختبارات (`MAJOR`):** **0** اختبارات E2E حقيقية تربط `TaskEditorSheet` (إنشاء مع تذكير نسبي/صريح + تعديل التاريخ/الوقت + حذف/إلغاء) مع `AlarmOutboxDispatcher.drain()` الفعلي ثم التحقق من `AlarmScheduler` و`AlarmFireHandler` و`DeliveryState`. الاختبارات الحالية معزولة تماماً بين اختبارات UI (تفحص Room/Outbox rows فقط) واختبارات نواة (تبدأ ببيانات مصنّعة دون `TaskEditorSheet`).
5. **`DEF-08` (تغطية فتح والتفاعل مع `TaskDetailRecurrenceCard`):**
   - **البطاقة والحوار (`TaskDetailRecurrenceCard.kt`):** تعرض الحالات المطلوبة وتدعم `CUSTOM_DAYS` ("Every N weeks" / "كل N أسبوع" + تلميح صريح) وتحرس `COMPLETED`/`CANCELLED` وتتحدث لحظياً عبر `observeTaskRecurrence(taskId)` Flow.
   - **فجوات الاختبارات والتحسين (`MINOR`):** لا يوجد اختبار بالوضع الفاتح (`LIGHT`)، ولا اختبار تفاعلي كامل (إضافة/تعديل/تحقق خطأ/حذف) باللغة العربية RTL داخل الحوار، ولا اختبار Compose UI يثبت التحديث اللحظي للبطاقة عبر Room Flow من `TaskEditorSheet` دون إعادة فتح الشاشة، ولا assertion صريح لعدم تعديل `TaskEntity`/`TaskReminderEntity` عند تعديل التكرار.

---

## 2. التدقيق التفصيلي للعيوب (`DEF-04` إلى `DEF-08`)

---

### `DEF-04` — إشعار غير صحيح أو سلوك غير منضبط بعد إعادة فتح مهمة ملغاة (`CANCELLED`)

#### 1) السبب الجذري (Root Cause)
ينقسم التحقيق إلى مسارين:
- **مسار التذكيرات والإنذارات (Outbox / Delivery / Reconciler):**
  عند إلغاء مهمة في `TaskUseCases.cancelTask` ← `RoomTaskRepository.updateStatus` ← `cancelTaskRemindersLocked`، يتم ذرياً داخل معاملة Room واحدة:
  1. إبطال التسليمات المعلقة (`DeliveryState.STALE`).
  2. تعطيل كل تذكير مفعّل (`isEnabled = false`, `scheduleState = CANCELLED`) مع زيادة `scheduleRevision + 1`.
  3. إيداع `CANCEL_ALARM` و`CANCEL_NOTIFICATION` في `alarm_sync_outbox` مع `supersedePendingAlarmByIdentityBeforeSeq` لإلغاء أي `SCHEDULE_ALARM` سابق لم يُصرّف.
  عند إعادة الفتح (`TaskUseCases.reopenTask` ← `RoomTaskRepository.updateStatus`) تتغير حالة المهمة فقط إلى `TaskStatus.PENDING` (`completedAtEpochMs = null`) **دون** إعادة تفعيل التذكيرات أو إيداع `SCHEDULE_ALARM`، وبذلك يبقى التذكير معطّلاً (`isEnabled = false`, `scheduleState = CANCELLED`) حتى يفعّله المستخدم يدوياً عبر `TaskDetailRemindersCard` أو عبر تفعيل «إعادة التفعيل» صراحةً في `TaskEditorSheet`. كما أن `AlarmOutboxDispatcher` و`NotificationDeliveryEngine` و`NotificationActionHandler` و`ReminderReconciler` تفحص جميعها `isEnabled` و`scheduleState` و`scheduleRevision` و`TaskReminderRules.isTaskActive(task.status)`.
  - **ملاحظة سباق دقيقة في `AlarmOutboxDispatcher`:** إذا أعيد فتح المهمة **قبل** تصريف `CANCEL_ALARM`/`CANCEL_NOTIFICATION`، فإن `buildExecutionPlan` (`AlarmOutboxDispatcher.kt:374-396`) يتحقق من `reminder.scheduleRevision != entry.targetScheduleRevision` (وليس من حالة المهمة) فيواصل تنفيذ `CancelAlarm` و`CancelNotification` ويحفظ `CANCELLED`؛ أما إذا قام المستخدم في تلك اللحظة بإعادة تفعيل التذكير يدوياً، فترتفع `scheduleRevision` مجدداً ويدخل `SCHEDULE_ALARM` برقم `seq` أعلى فيلغي `CANCEL_ALARM` القديم عبر `hasNewerCommandForTarget` وفحص المراجعة.
- **مسار واجهة التفاصيل (`TaskDetailViewModel.reopenTask` — سبب جذري لخلل فعلي مرتبط):**
  الدالة `TaskDetailViewModel.reopenTask()` (`TaskDetailViewModel.kt:325-328`) تقرأ فقط وجود `TaskRecurrenceEntity` (`val hadRecurrence = taskRepository.getTaskRecurrence(taskId) != null`) قبل استدعاء `taskUseCases.reopenTask(taskId)` وتضبط `reopenedRecurringState.value = hadRecurrence` دون التحقق من أن حالة المهمة قبل إعادة الفتح كانت `TaskStatus.COMPLETED` وليس `TaskStatus.CANCELLED`. وبما أن إلغاء المهمة (`cancelTask`) لا يولّد نسخة تالية، فإن إعادة فتح مهمة متكررة ملغاة تعرض زيفاً تحذير `task_detail_recurrence_reopen_hint`.

#### 2) الملفات والدوال وأرقام الأسطر الدقيقة
- `app/src/main/java/com/example/domain/task/TaskUseCases.kt`:
  - `cancelTask()` (`111-112`)، `reopenTask()` (`114-134`).
- `app/src/main/java/com/example/data/repository/RoomTaskRepository.kt`:
  - `updateStatus()` (`129-149`)، `cancelTaskRemindersLocked()` (`338-366`)، `update()` (`54-127`).
- `app/src/main/java/com/example/data/repository/ReminderOutboxCoordinator.kt`:
  - `stageScheduleAlarmLocked()` (`61-105`)، `stageCancelAlarmAndNotificationLocked()` (`107-181`).
- `app/src/main/java/com/example/data/repository/RoomReminderSyncRepository.kt`:
  - `updateTaskReminderSchedule()` (`154-203`)، `setReminderEnabled()` عبر `TaskReminderUseCases.kt`.
- `app/src/main/java/com/example/domain/task/TaskReminderUseCases.kt`:
  - `setReminderEnabled()` (`143-173`) — يتحقق من `isTaskActive(task.status)` أولاً، وعند إعادة التفعيل على مهمة أعيد فتحها يحسب وقت التشغيل المستقبلي عبر `ReminderTriggerCalculator` وتزيد `scheduleRevision`.
- `app/src/main/java/com/example/reminder/AlarmOutboxDispatcher.kt`:
  - `buildExecutionPlan()` (`329-398`: فحص `SCHEDULE_ALARM` عند `339-345` و`CANCEL_ALARM`/`CANCEL_NOTIFICATION` عند `374-396`)، `commitSuccessFenced()` (`456-569`: حفظ `CANCELLED`/`PAUSED` عند `530-550`)، `compensateAfterCasFailure()` (`571-588`).
- `app/src/main/java/com/example/reminder/NotificationDeliveryEngine.kt`:
  - `deliverTaskReminderInternal()` (`153-230`: حراسة `isEnabled` و`isReminderStateEligibleForFire` و`expectedRevision` و`isTaskActive` قبل `evaluateAtFire`)، `recoverPendingDeliveriesInternal()` (`239-280`)، `claimOrRecoverDeliveryTx()` (`511-587`)، `commitPostedFenced()` (`640-676`).
- `app/src/main/java/com/example/reminder/NotificationActionReceiver.kt`:
  - `NotificationActionHandler.handleIntent()` (`43-152`: فحص `isTaskActive` و`isEnabled` و`expectedRevision` و`STALE`/`EXPIRED` وإلغاء الإشعار عند `83-107`).
- `app/src/main/java/com/example/reminder/ReminderReconciler.kt`:
  - `reconcile()` (`115, 144-163`: استعلام المفعّلة فقط وحراسة `isActiveScheduleState` و`isTaskActive`).
- `app/src/main/java/com/example/feature/tasks/components/TaskEditorSheet.kt`:
  - `loadExistingExtras()` (`180-196`: تحميل `isEnabled = false`, `requestReEnable = false`)، `handleSave()` (`581-608`: لا يعيد التفعيل إلا إذا كان `draft.requestReEnable == true`).
- `app/src/main/java/com/example/feature/tasks/detail/TaskDetailViewModel.kt`:
  - `reopenTask()` (`320-339`)، `extrasFlow` (`159-170`)، `uiState` (`200-202`).

#### 3) السلوك الحالي مقابل السلوك المتوقع
- **مسار الإشعار والمنبه (عند إلغاء ثم إعادة فتح):**
  - *الحالي:* إلغاء المهمة يعطّل التذكير (`isEnabled = false`, `scheduleState = CANCELLED`)، وإعادة فتحها تترك التذكير معطّلاً ولا تُنتج أي إشعار تلقائي؛ وأي منبه قديم عالق أو إشعار متبقٍ يُلغى عبر `AlarmOutboxDispatcher` أو يُرفض في `NotificationDeliveryEngine` / `NotificationActionHandler` / `ReminderReconciler`. يطابق المتوقع.
  - *ملاحظة حدّية عند إعادة تفعيل المستخدم للتذكير بعد إعادة الفتح:* إذا كان التذكير من النوع ذي وقت البداية الماضي قبل إعادة الفتح بمدة تقل عن `15` دقيقة (`GRACE_WINDOW`) أو في المستقبل، يعود لجدولته وفق `ReminderTriggerCalculator` لأن المستخدم فعّله صراحةً بعد إعادة فتح المهمة؛ أما إذا كان التذكير قديماً خارج نافذة السماح عند محاولة إعادة التفعيل (`evaluateForSchedule` = `EXPIRED_OUTSIDE_GRACE`) أو ناقص التاريخ/الوقت فترفض `TaskReminderUseCases.setReminderEnabled` إعادة التفعيل (`ReminderInPast` / `MissingTaskSchedule`) — لكن لا يوجد اختبار يؤكد إعادة فتح مهمة `CANCELLED` مع تذكير ماضٍ ثم سلوك النظام عند محاولة التصريف/المصالحة/إعادة التفعيل.
- **مسار التحذير في `TaskDetailViewModel.reopenTask`:**
  - *الحالي:* إعادة فتح مهمة متكررة كانت `CANCELLED` يظهر تحذير `task_detail_recurrence_reopen_hint` ("تم إنشاء التكرار التالي مسبقاً...") مع أنه لم يُنشأ أي تكرار تالٍ عند الإلغاء.
  - *المتوقع:* يظهر التحذير **فقط** عند إعادة فتح مهمة كانت `COMPLETED` ولها تكرار، ولا يظهر عند إعادة فتح مهمة `CANCELLED`.

#### 4) الاختبارات الحالية والفصل الصارم بين E2E والمعزولة
- **اختبارات معزولة موجودة (لا تغطي دورة `CANCELLED -> reopenTask -> Outbox/Delivery/Reconciler` كاملة):**
  - `Unit4ATaskSyncAndRulesTest.kt:267`: `terminalTransitions_setCancelledScheduleState_andDeleteUsesInjectedClock` (تفحص حالة Room وOutbox عند الإلغاء والحذف فقط، دون `reopenTask` ودون تشغيل Dispatcher/Delivery).
  - `Unit4A2BNotificationDeliveryTest.kt:315`: `alarmFire_rejectsDisabledReminder_inactiveTask_andMismatchedRevision` (تفحص رفض `AlarmReceiver` لتذكير معطّل أو مهمة غير نشطة بمصنّعات مباشرة دون المرور بـ `cancelTask -> reopenTask`).
  - `Unit4A2CSystemReconciliationTest.kt:641`: `reconciler_excludesConsumedHabitGoalDisabledAndInactiveTasks` (تفحص استثناء المهام غير النشطة والتذكيرات المعطّلة في `ReminderReconciler` مباشرة).
  - `Unit4A2D3TaskDetailRemindersTest.kt:750`: `taskDetailReminders_terminalTasksRestrictAddEditEnableSnooze_andReopenAllowsReEnabling` (واجهة تفاصيل + ViewModel: تغطي الإكمال ← إعادة الفتح ← التفعيل اليدوي، ولا تغطي حالة `CANCELLED` مع وجود سباق Outbox أو `AlarmReceiver` / `NotificationDeliveryEngine`).
  - `Unit4A2D3TaskDetailRecurrenceTest.kt:525`: `taskDetailRecurrence_completeGeneratesNextOccurrenceAndReopenPreservesItWithWarning` (تغطي إعادة الفتح من `COMPLETED` فقط، ولا تختبر إعادة فتح `CANCELLED`).
  - `Unit4A2D3DBatch1HardeningTest.kt:246`: `def01_cancelledTask_rejectsAllRecurrenceAndReminderMutations` (تفحص منع الطفرات على `CANCELLED` قبل إعادة الفتح).
- **اختبارات E2E حقيقية لهذا المسار:** **غير موجودة (0 اختبارات).**

#### 5) الفجوات التي تحتاج اختبارات جديدة
1. **اختبار تكامل E2E لدورة الإلغاء وإعادة الفتح مع السباق (`CANCELLED -> reopenTask`):**
   - إنشاء مهمة مجدولة + تذكير مفعّل وتصريف Outbox (`rev = 1` مجدول في `FakeAlarmScheduler`) ثم نشر تسليم أو وجود نية منبه قديمة (`rev = 1`) ← استدعاء `cancelTask` (`rev = 2`, `CANCELLED`, `CANCEL_ALARM` + `CANCEL_NOTIFICATION` في Outbox).
   - **حالة السباق 1:** استدعاء `reopenTask` **قبل** تصريف `AlarmOutboxDispatcher.drain()` ثم تصريف Outbox والتحقق من أن `CANCEL_ALARM` و`CANCEL_NOTIFICATION` نُفّذا، وأن `FakeAlarmScheduler` خالٍ من أي منبه نشط، وأن التذكير بقي `isEnabled = false, scheduleState = CANCELLED`.
   - **حالة السباق 2:** إرسال `AlarmReceiver` / `AlarmFireHandler.handleIntent` بنية `rev = 1` القديمة بعد `reopenTask`، وتشغيل `NotificationDeliveryEngine.recoverPendingDeliveries()` و`ReminderReconciler.reconcile()`، والتحقق من **عدم** صدور أي إشعار وعدم جدولة أي منبه.
   - **حالة السباق 3:** فتح `TaskEditorSheet` على المهمة المعاد فتحها وتعديل العنوان فقط ثم الحفظ ← التحقق من بقاء التذكير `CANCELLED` وعدم إيداع `SCHEDULE_ALARM`، ثم إعادة تفعيله صراحةً وحفظه ← التحقق من جدولة `rev = 3` فقط.
2. **اختبار `TaskDetailViewModel` / `TaskDetailScreen` لإعادة فتح مهمة متكررة `CANCELLED`:** التأكد من عدم ظهور `task_detail_recurrence_reopen_hint` وعدم توليد occurrence ثانٍ.

#### 6) نطاق التعديل المقترح بأقل تدخل ممكن
- في `app/src/main/java/com/example/feature/tasks/detail/TaskDetailViewModel.kt` (`reopenTask` الأسطر `324-330`):
  قراءة الحالة الحالية للمهمة قبل الاستدعاء (`val currentTask = taskRepository.getById(taskId)`) وحصر `reopenedRecurringState.value = (currentTask?.status == TaskStatus.COMPLETED && hadRecurrence)`.
- عدم المساس بآليات `RoomTaskRepository` أو `AlarmOutboxDispatcher` أو `NotificationDeliveryEngine` في مسار الإلغاء/إعادة الفتح لأنها متوافقة ومنضبطة مسبقاً (ما لم يكشف اختبار E2E الجديد عن حالة حدية غير متوقعة).

#### 7) مخاطر التعديل والاعتماديات
- **منخفضة جداً (Low):** تعديل `reopenTask` في `TaskDetailViewModel.kt` محصور في سطرين فقط، ويعتمد فقط على `taskRepository.getById(taskId)` و`TaskStatus.COMPLETED` ولا يؤثر في Room Schema أو Outbox أو أي شاشة أخرى.

#### 8) تصنيف الخطورة
- **للخلل الفعلي (`reopenTask` على مهمة متكررة `CANCELLED`):** **`MINOR`**
- **لفجوة التحقق E2E (سباق `cancelTask -> reopenTask -> Outbox/AlarmFire/Reconciler`):** **`MAJOR`** (كمخاطرة انحدار غير مختبرة حتى تُغطى باختبار E2E).

---

### `DEF-05` — شرائح «اليوم» و«غداً» ونافذة الجدولة حول منتصف الليل وتغيير المنطقة الزمنية

#### 1) السبب الجذري (Root Cause)
تعتمد شاشة التحرير `TaskEditorScheduleSection.kt` (`77-82, 90-97`) على:
- `val todayStr = remember(localClock) { ... }`
- `val tomorrowStr = remember(localClock) { ... }`
- `val scheduleWindow = remember(scheduledDate, startTime, endTime, spansNextDay, localClock) { ... zoneId = localClock.zoneId() }`

ولأن `localClock` المحقون عبر `DefaultAppContainer` هو كائن وحيد ثابت المرجع (`LocalClock` singleton)، فإن مفتاح `remember(localClock)` لا يتغير إطلاقاً أثناء بقاء `TaskEditorSheet` مفتوحةً:
1. إذا عبرت الساعة منتصف الليل أو استُقبلت إذاعة `ACTION_DATE_CHANGED` / `ACTION_TIME_CHANGED` / `ACTION_TIMEZONE_CHANGED`، تبقى شريحتا «اليوم» (`task_date_today`) و«غداً» (`task_date_tomorrow`) مرتبطتين بتاريخ فتح الورقة القديم.
2. الأسوأ أن تفاعلات أخرى داخل `TaskEditorSheet` (مثل `updateSpansNextDay` عند `331` أو تهيئة منتقي الوقت عند `871, 878`) تستدعي `localClock.currentDate()` و`localClock.currentTime()` مباشرةً في لحظة النقر، مما يخلق تناقضاً فورياً بين تاريخ شريحتي اليوم/غداً وتاريخ الملء التلقائي عند تفعيل «اليوم التالي».
3. عند تغيير المنطقة الزمنية (مثل الانتقال إلى منطقة تعبر توقيتاً صيفياً DST) تبقى `scheduleWindow` (بما فيها `hasDstShift` والمدة) محسوبة بـ `zoneId` القديم لأن `localClock` نفسه لم يتبدل مرجعه.
4. ورقة `TaskEditorSheet` نفسها لا تتلقى `todayDate`/`zoneId` متجدداً من `TodayViewModel`/`TasksViewModel`/`TaskDetailViewModel` (التي تشغّل جميعها `ObserveTodayTimeAndLifecycle` ومؤقت منتصف الليل بنجاح) ولا تشترك بنفسها في تحديثات الوقت.

#### 2) الملفات والدوال وأرقام الأسطر الدقيقة
- `app/src/main/java/com/example/feature/tasks/components/TaskEditorScheduleSection.kt`:
  - `TaskEditorScheduleSection()` (`54-154`: تعريف `todayStr` عند `77-78`، `tomorrowStr` عند `79-82`، `scheduleWindow` عند `89-98`، شريحتا `task_date_today` و`task_date_tomorrow` عند `129-140`).
- `app/src/main/java/com/example/feature/tasks/components/TaskEditorSheet.kt`:
  - `TaskEditorFormState` (`100-150`)، `updateSpansNextDay()` (`328-336`: استدعاء `localClock.currentDate()`)، `TaskEditorSheet()` (`710-897`: لا يقبل `todayDate`/`zoneId` ولا يراقب تغير الوقت).
- `app/src/main/java/com/example/feature/tasks/components/TaskScheduleFormatters.kt`:
  - `formatWindow()`، `formatDate()`، `formatTime()` (`28-150`: تنسيق موحد محايد مع `Locale.US` للأرقام وتوطين عربي/إنجليزي).
- `app/src/main/java/com/example/core/time/LocalClock.kt` (`1-70`) و`app/src/main/java/com/example/core/time/SystemTimeChangeObserver.kt` (`1-105`):
  - يوفران `millisUntilNextMidnight()` المراعي لـ DST و`ObserveTodayTimeAndLifecycle`.
- للمقارنة (النمط الصحيح المطبّق في الشاشات الأب):
  - `TodayScreen.kt:603-605` و`TaskDetailScreen.kt:812-814`: تمريران `todayDate` التفاعلي وتستخدمان `remember(todayDate)`.

#### 3) السلوك الحالي مقابل السلوك المتوقع
- **الحالي:**
  - إذا فُتحت `TaskEditorSheet` الساعة `23:59` ثم انتقلت الساعة إلى `00:01` أو أرسلت إذاعة `ACTION_DATE_CHANGED`/`TIMEZONE_CHANGED`، تظل شريحتا «اليوم» و«غداً» في `TaskEditorScheduleSection` تشيران للتاريخ السابق (مثلاً `2026-11-27` و`2026-11-28` بدلاً من `2026-11-28` و`2026-11-29`) وتظهر شريحة اليوم القديم محددة كـ `اليوم` حتى منتصف اليوم الجديد، كما لا يتحدث `zoneId` في حساب `scheduleWindow`.
  - دعم RTL/LTR في ترتيب الشرائح وتنسيق النصوص سليم (`FlowRow` + `LocalLayoutDirection`).
- **المتوقع:**
  - تتحدث `todayStr` و`tomorrowStr` و`zoneId` في `TaskEditorScheduleSection` فور مرور منتصف الليل أو عند أي بث `ACTION_DATE_CHANGED` / `ACTION_TIME_CHANGED` / `ACTION_TIMEZONE_CHANGED` أو استئناف التطبيق، وتنعكس حالة التحديد (`selected`) للشرائح ومعاينة المدة (`hasDstShift`) فوراً في العربية RTL والإنجليزية LTR دون فقدان ما كتبه المستخدم في الحقول.

#### 4) الاختبارات الحالية والفصل الصارم بين E2E والمعزولة
- **اختبارات موجودة (خارج `TaskEditorSheet` / `TaskEditorScheduleSection`):**
  - `Unit3B4TodayLifecycleAndTimeTest.kt`: 7 اختبارات (`localClock_millisUntilNextMidnight_respectsDstSpringForwardAndFallBack`, `todayViewModel_midnightRollover_updatesDateMetricsAndReschedulesNextMidnightDeterministically`, `todayScreen_midnightRolloverWhileInForeground_updatesHeaderAndMetricsLive`, `todayScreen_systemTimezoneDateAndTimeBroadcasts_updateMetricsAndScheduleLive`, ...).
  - `Unit4ATimeAndDstTest.kt`: `datePickerLocalDateMapper_respectsExistingDateAndLocalClockFallback` (`203`), `tasksViewModel_midnightRolloverAndTimezoneUpdatesOverdueDeterministically` (`378`), `taskDetailScreen_updatesFormattedTimestampsOnTimezoneBroadcastAndMidnightRollover` (`428`).
  - `Unit4A2DTimePickerAndEditorTest.kt`: 6 اختبارات لمنتقي الوقت واليوم التالي وتنسيقات AR/EN — كلها بساعة ثابتة دون عبور منتصف الليل أو تغيير المنطقة الزمنية.
- **اختبارات لـ `TaskEditorSheet` / `TaskEditorScheduleSection` حول منتصف الليل أو تغيير الوقت/المنطقة الزمنية:** **غير موجودة (0 اختبارات).**

#### 5) الفجوات التي تحتاج اختبارات جديدة
1. فتح `TaskEditorSheet` الساعة `23:59:59` (أو قبل بث `ACTION_DATE_CHANGED`/`TIME_CHANGED`) مع تفعيل ساعة اختبارية وتقدم الوقت لما بعد `00:00` ← التحقق من تحديث قيمة اختيار `task_date_today` و`task_date_tomorrow` وحفظ المهمة بالتاريخ الجديد الفعلي عند النقر على «اليوم» أو «غداً» (في العربية RTL والإنجليزية LTR).
2. إرسال `ACTION_TIMEZONE_CHANGED` أثناء فتح `TaskEditorSheet` على مهمة متقاطعة مع DST أو تاريخ حدودي والتأكد من إعادة حساب `scheduleWindow` (`hasDstShift` والمدة) وتاريخ اليوم/غداً دون فقدان مسودة العنوان/الأوقات.
3. التحقق من اتساق شريحة «اليوم» مع الملء التلقائي عند تفعيل `task_next_day_switch` لعدم وجود تباين تاريخي.

#### 6) نطاق التعديل المقترح بأقل تدخل ممكن
- إضافة بارامترين اختياريين بقيم افتراضية آمنة إلى `TaskEditorSheet` و`TaskEditorScheduleSection`:
  `currentDate: LocalDate = localClock.currentDate()` و`currentZoneId: ZoneId = localClock.zoneId()` (أو مراقبة خفيفة عبر `ObserveTodayTimeAndLifecycle` + مؤقت منتصف الليل المستند إلى `localClock` داخل `TaskEditorSheet` عندما تكون الورقة ظاهرة)، واستبدال `remember(localClock)` في `TaskEditorScheduleSection.kt:77-98` بـ `remember(currentDate)` و`remember(..., currentZoneId)`.
- تمرير `uiState.todayDate` / `uiState.zoneId` من `TodayScreen` و`TasksScreen` و`TaskDetailScreen` إلى `TaskEditorSheet` لضمان التوافق التام حتى بدون انتظار إعادة فتح الورقة.

#### 7) مخاطر التعديل والاعتماديات
- **منخفضة (Low):**
  - يجب التأكد من أن إضافة `ObserveTodayTimeAndLifecycle` أو مؤقت منتصف الليل داخل `TaskEditorSheet` لا يترك مؤقتات معلقة عند إغلاق الورقة (`DisposableEffect` / `LaunchedEffect` يلغى تلقائياً عند خروج الـ Composable من التركيب) ولا يسبب إعادة تعيين مسودة `rememberSaveable` داخل `TaskEditorFormState`.
  - إبقاء القيم الافتراضية (`localClock.currentDate()`, `localClock.zoneId()`) يحفظ التوافق المصدري مع جميع النداءات الحالية.

#### 8) تصنيف الخطورة
- **`MINOR`** (خلل فعلي واضح ومؤكد في السطرين `77-82` و`90-97` عند بقاء المحرر مفتوحاً عبر منتصف الليل أو تبدل المنطقة الزمنية، دون تأثير على سلامة Room أو Outbox).

---

### `DEF-06` — تغطية تكامل فتح المهمة (`Today` / `Tasks` / `Notification` / `Recurrence`)

#### 1) السبب الجذري (Root Cause)
تم فحص كل مسارات فتح `TaskDetailRoute(taskId)` في التطبيق:
1. **من `TodayScreen` (`TodayScreen.kt:95, 261, 273`):** النقر يمرر `onOpenTaskDetail(taskId)` ← `YawmiShell.kt:244` يستدعي `navController.navigate(TaskDetailRoute(taskId))`.
2. **من `TasksScreen` (`TasksScreen.kt:104, 571-576, 604-609, 627-632`):** النقر يمرر `onOpenTaskDetail?.invoke(t.id)` ← `YawmiShell.kt:267-269` يستدعي `navController.navigate(TaskDetailRoute(taskId))` (مع بديل فتح المحرر فقط إذا كان `onOpenTaskDetail == null`).
   - *ملاحظة طفيفة:* استدعاءات `navController.navigate(TaskDetailRoute(taskId))` من `TodayScreen` و`TasksScreen` و`CalendarScreen` (`YawmiShell.kt:244, 268, 297`) لا تمرر `launchSingleTop = true` (على عكس مسار الإشعار عند `100-102`)، فالنقر المزدوج السريع قد يدفع نسختين متتاليتين إلى المكدس.
3. **من إجراء فتح الإشعار (`NotificationDeliveryEngine.kt:745-751` ← `NotificationPendingIntentFactory.kt:13-35` ← `MainActivity.kt:27, 44-52` ← `YawmiShell.kt:97-105`):**
   - تُبنى `PendingIntent.getActivity` بأعلام `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_SINGLE_TOP or FLAG_ACTIVITY_CLEAR_TOP` و`EXTRA_OPEN_TASK_ID = taskId` مع `requestCodeFor("open", reminderId, occurrenceKey)`.
   - `MainActivity` تقرأ المعرّف في `onCreate` (`27`) و`onNewIntent` (`47`) إلى `pendingOpenTaskId` (`MutableStateFlow`).
   - `YawmiShell` تنفّذ `LaunchedEffect(pendingOpenTaskId)` لتوجيه الملاحة بـ `launchSingleTop = true` ثم تستدعي `onConsumeOpenTaskId()` لتصفير `pendingOpenTaskId.value = null` فوراً، مما يمنع تكرار الملاحة عند تدوير الشاشة أو إعادة إنشائها.
   - عندما تكون المهمة قد حُذفت قبل النقر على الإشعار، يبعث `TaskDetailViewModel.uiState` (`180-181`) الحالة `TaskDetailUiState.NotFound` وتعرض `TaskDetailScreen` بطاقة `task_detail_not_found` وزر الرجوع `task_detail_not_found_back_btn` (`408-450`).
4. **من التكرار (النسخة القادمة بعد الإكمال):**
   - `TaskDetailViewModel.completeTask()` (`294-303`) يتلقى `TaskCompletionResult` الذي يحوي `generatedNextTask: TaskEntity?` بالكامل (بما فيه `generatedNextTask.id`)، لكن `TaskDetailUiEvent.RecurringTaskCompleted` (`84`) يحمل `nextScheduledDate: String` فقط دون `nextTaskId`، و`TaskDetailScreen.kt:166-169` يعرض `Snackbar` نصياً فقط دون زر إجراء لفتح النسخة القادمة (`onOpenTaskDetail(nextTaskId)`). لذلك لا يملك المستخدم مساراً مباشراً لفتح الـ occurrence الجديد من شاشة التفاصيل ولا توجد أي تغطية اختبارية لذلك.

#### 2) الملفات والدوال وأرقام الأسطر الدقيقة
- `app/src/main/java/com/example/reminder/NotificationPendingIntentFactory.kt`:
  - `createOpenTaskDetailsPendingIntent()` (`13-35`)، `requestCodeFor()` (`93-96`).
- `app/src/main/java/com/example/reminder/NotificationDeliveryEngine.kt`:
  - `buildTaskReminderNotification()` (`745-751, 780`).
- `app/src/main/java/com/example/MainActivity.kt`:
  - `onCreate()` (`24-37`)، `onNewIntent()` (`44-48`)، `extractOpenTaskId()` (`50-52`)، `YawmiRootApp()` (`56-87`).
- `app/src/main/java/com/example/presentation/navigation/YawmiShell.kt`:
  - `LaunchedEffect(pendingOpenTaskId)` (`97-105`)، `TodayRoute` (`236-253`)، `TasksRoute` (`254-271`)، `TaskDetailRoute` (`272-289`).
- `app/src/main/java/com/example/feature/dashboard/TodayScreen.kt`: `261, 273`.
- `app/src/main/java/com/example/feature/tasks/TasksScreen.kt`: `571-576, 604-609, 627-632`.
- `app/src/main/java/com/example/feature/tasks/detail/TaskDetailViewModel.kt`:
  - `TaskDetailUiEvent.RecurringTaskCompleted` (`84`)، `uiState` حالة `NotFound` (`180-181`)، `completeTask()` (`294-303`).
- `app/src/main/java/com/example/feature/tasks/detail/TaskDetailScreen.kt`:
  - `LaunchedEffect(viewModel)` (`160-187`)، `NotFound` UI (`408-450`).

#### 3) السلوك الحالي مقابل السلوك المتوقع
- **فتح المهمة من `Today` و`Tasks`:** يعمل بالكود، باستثناء احتمال دفع شاشة مكررة عند النقر السريع المتتالي لغياب `launchSingleTop = true`.
- **فتح المهمة من الإشعار (`EXTRA_OPEN_TASK_ID`):** يعمل بالكود في الإقلاع البارد (`onCreate`) والساخن (`onNewIntent`) ويعالج المهمة المحذوفة بـ `NotFound` ولا يكرر عند إعادة الإنشاء (`onConsumeOpenTaskId()`). *تنبيه للملاحظة:* إذا كان المستخدم أصلاً على `TaskDetailRoute(taskA)` وأتى `onNewIntent` لفتح `taskB`، فإن `launchSingleTop = true` على مسار `TaskDetailRoute` سيستبدل المدخل الأعلى أو يدفعه وفق سلوك Navigation Compose Type-Safe (`TaskDetailRoute(taskB)` يختلف في الـ args عن `taskA`؛ لم يُختبر هذا السيناريو).
- **فتح النسخة القادمة المتولدة من التكرار:** حالياً لا يوجد رابط/زر إجراء في `Snackbar` لفتح الـ `generatedNextTask.id` مباشرة بعد إكمال مهمة متكررة (المتوقع اختيارياً: توفير `SnackbarResult.ActionPerformed` أو الاعتماد على الملاحة الموثقة واختبار فتح الـ occurrence من `Today`/`Tasks`).

#### 4) الاختبارات الحالية والفصل الصارم بين E2E والمعزولة
- **اختبارات موجودة:**
  - `Unit3B4TodayScreenTest.kt:408`: `yawmiRootApp_navigatesFromTodayToTaskDetail_updatesOnEditCompleteReopenDelete_andNavigatesToTasks` (E2E UI حقيقي من `TodayScreen -> TaskDetailScreen -> TasksScreen` على `YawmiRootApp`، لا يختبر النقر لفتح التفاصيل من `TasksScreen` ولا `EXTRA_OPEN_TASK_ID`).
  - `Unit3B3TaskDetailTest.kt:435`: `yawmiRootApp_navigatesToTaskDetailAndDeletesTaskWithLinkedRecords` (تستدعي `YawmiRootApp` عبر `Today -> TaskDetail -> Delete`).
  - `Unit3B3TaskDetailTest.kt:535`: `taskDetailScreen_handlesMissingTaskGracefullyWithNotFoundState` (معزولة على `TaskDetailScreen` مباشرة).
  - `Unit4A2BNotificationDeliveryTest.kt:259`: `alarmReceiver_receivesFireReminder_readsRoomAsSourceOfTruth_andRecordsPostedToSystem` (تحقق من وجود `EXTRA_OPEN_TASK_ID` داخل `contentIntent` للإشعار فقط، **دون** تشغيل `MainActivity` أو `YawmiRootApp`!).
- **ما لم يُختبر إطلاقاً:**
  - **0** اختبارات تتحقق من `MainActivity` / `YawmiRootApp` / `YawmiShell` عند استلام `ACTION_OPEN_TASK_DETAIL` و`EXTRA_OPEN_TASK_ID` (لا `onCreate` ولا `onNewIntent` ولا `recreate()` ولا مهمة محذوفة `NotFound`).
  - **0** اختبارات UI تفتح `TaskDetailScreen` بالنقر على بطاقة مهمة من `TasksScreen`.
  - **0** اختبارات تفتح الـ occurrence الجديد المتولد من التكرار (سواء من `Today`/`Tasks` عبر `YawmiRootApp` أو عبر زر إجراء).

#### 5) الفجوات التي تحتاج اختبارات جديدة
1. **اختبار `MainActivity` / `YawmiRootApp` لإطلاق الإشعار (`onCreate` + `onNewIntent` + `recreate`):**
   - إطلاق `MainActivity` بـ Intent يحمل `ACTION_OPEN_TASK_DETAIL` و`EXTRA_OPEN_TASK_ID = taskA` ← التأكد من ظهور `TaskDetailScreen` لمهمة `taskA`.
   - استدعاء `recreate()` ← التأكد من عدم تكرار الملاحة في المكدس (`popBackStack` يرجع مباشرة إلى `TodayRoute`).
   - إرسال `onNewIntent` بمعرف مهمة `taskB` ← التأكد من فتح `taskB`؛ وإرسال معرف غير موجود ← التأكد من ظهور `task_detail_not_found`.
2. **اختبار ملاحة `YawmiRootApp` من `TasksScreen -> TaskDetailScreen`:** التبديل إلى `nav_tasks`، النقر على بطاقة مهمة (مجدولة وغير مجدولة) والتحقق من فتح `TaskDetailScreen` ثم العودة النظيفة.
3. **اختبار فتح الـ occurrence التالي بعد إكمال مهمة متكررة** عبر `YawmiRootApp` (من زر إجراء Snackbar إن أضيف، ومن `TodayScreen`/`TasksScreen` بعد الإكمال).

#### 6) نطاق التعديل المقترح بأقل تدخل ممكن
- في `YawmiShell.kt` (`244, 268, 297`): إضافة `launchSingleTop = true` عند `navController.navigate(TaskDetailRoute(taskId))` لمنع دفع شاشات متكررة عند النقر المزدوج السريع.
- (اختياري لتحسين فتح التكرار القادم بأقل تدخل): إضافة `nextTaskId: String` إلى `TaskDetailUiEvent.RecurringTaskCompleted` وتمرير `onOpenTaskDetail` إلى `TaskDetailScreen` لتفعيل زر «عرض» / «View» في الـ `Snackbar` عند إكمال مهمة متكررة (أو توثيق الاكتفاء بملاحة `Today`/`Tasks` إذا لم يُطلب تغيير UX).

#### 7) مخاطر التعديل والاعتماديات
- **منخفضة (Low):**
  - إضافة `launchSingleTop = true` متوافقة بالكامل مع `NavHost` بشرط عدم التأثير على فتح `taskB` عندما يكون `taskA` مفتوحاً (لأن `TaskDetailRoute(taskA) != TaskDetailRoute(taskB)` في `Navigation Compose 2.8+` Type-Safe routes، `launchSingleTop` يمنع تكرار نفس الوجهة بالضبط ويسمح بتبديل المعرّف عند اختلافه). يجب التحقق من ذلك في الاختبار الجديد.

#### 8) تصنيف الخطورة
- **`MAJOR`** (فجوة تغطية تكاملية عالية الأهمية لملاحة الإشعار `MainActivity`/`YawmiShell` و`TasksScreen` وفتح النسخة المكررة، رغم سلامة التوصيل البرمجي الأساسي).
- سلوك فتح النسخة القادمة مباشرة من الـ Snackbar وتعدد النقرات السريعة: **`MINOR`**.

---

### `DEF-07` — تغطية التكامل الشامل بين `TaskEditorSheet` والتذكيرات و`AlarmOutboxDispatcher`

#### 1) السبب الجذري (Root Cause)
تم تتبع السلسلة الكاملة ذات الـ 9 خطوات في الشيفرة:
1. فتح `TaskEditorSheet` وإنشاء مهمة جديدة (`TaskEditorSheet.kt:456`).
2. إضافة تذكير نسبي أو صريح في المسودة وحفظ المهمة (`TaskEditorSheet.kt:545-580` ← `TaskReminderUseCases` ← `RoomReminderSyncRepository.createTaskReminder` عند `48-84`: يفتح معاملة Room، يخصص `requestCode` فريداً، يُدرج `TaskReminderEntity` بـ `scheduleRevision = 1` و`PENDING_SYNC`، ويودع `SCHEDULE_ALARM` ثم يستدعي `onOutboxCommitted()`).
3. تصريف `AlarmOutboxDispatcher.drain()` (`AlarmOutboxDispatcher.kt`) ينفذ `scheduleAlarm` على `AlarmScheduler` ويثبت `SCHEDULED_EXACT`/`SCHEDULED_INEXACT` بـ CAS fenced commit (`456-569`).
4. تعديل تاريخ/وقت المهمة من `TaskEditorSheet` (`TaskEditorSheet.kt:458` ← `TaskUseCases.updateTask` ← `RoomTaskRepository.update` عند `54-127`): يعيد حساب وقت التذكير (مع حفظ التذكير الصريح وفق سياسة `DEF-02` في Schema v1)، يرفع `scheduleRevision = 2`, يبطل التسليمات (`STALE`), ويودع `SCHEDULE_ALARM` جديداً مع `supersedePendingAlarmByIdentityBeforeSeq` (`ReminderOutboxCoordinator.kt:100-103`) لمنع وجود أمري جدولة متنافسين.
5. حذف المهمة (`RoomTaskRepository.deleteById` عند `294-317`) أو إلغاؤها (`RoomTaskRepository.updateStatus` عند `129-149` ← `cancelTaskRemindersLocked` عند `338-366`): يبطل التسليمات، يودع `CANCEL_ALARM` و`CANCEL_NOTIFICATION` **قبل** حذف المهمة، وعند تصريف Outbox يلغي `AlarmScheduler` و`NotificationCancellationExecutor` المنبه والإشعار.

**سبب الخلل في التغطية الاختبارية:** الاختبارات المكتوبة سابقاً جرى فصلها أفقياً حسب الوحدة (UI مع Fake/Un-drained Outbox، مقابل Dispatcher ببيانات محقونة يدوياً، مقابل Delivery ببيانات محقونة يدوياً)، فلم يُكتب أي اختبار تكاملي حقيقي يصل `TaskEditorSheet` بـ `AlarmOutboxDispatcher.drain()` ثم بـ `AlarmFireHandler` / `NotificationDeliveryEngine` على قاعدة بيانات Room واحدة!
*(كما تُسجَّل ملاحظة `TaskEditorSheet` من التقرير السابق `DEF-06`: `handleSave` عند `430-653` لا ينتظر صراحةً انتهاء `loadExistingExtras`، وعند الإغلاق بعد `partialSaveWarning` لا يُبلَّغ `onSaved` رغم تحديث Room Flow).*

#### 2) الملفات والدوال وأرقام الأسطر الدقيقة
- `app/src/main/java/com/example/feature/tasks/components/TaskEditorSheet.kt`:
  - `loadExistingExtras()` (`166-197`)، `handleSave()` (`430-654`).
- `app/src/main/java/com/example/domain/task/TaskReminderUseCases.kt`:
  - `createRelativeReminder()` (`35-83`)، `createExplicitReminder()` (`85-141`)، `setReminderEnabled()` (`143-173`)، `deleteReminder()` (`243-268`).
- `app/src/main/java/com/example/data/repository/RoomReminderSyncRepository.kt`:
  - `createTaskReminder()` (`48-84`)، `updateTaskReminderSchedule()` (`154-203`)، `deleteTaskReminder()` (`440-476`).
- `app/src/main/java/com/example/data/repository/RoomTaskRepository.kt`:
  - `update()` (`54-127`)، `updateStatus()` (`129-149`)، `deleteById()` (`294-317`)، `cancelTaskRemindersLocked()` (`338-366`).
- `app/src/main/java/com/example/data/repository/ReminderOutboxCoordinator.kt`:
  - `stageScheduleAlarmLocked()` (`61-105`)، `stageCancelAlarmAndNotificationLocked()` (`107-181`).
- `app/src/main/java/com/example/reminder/AlarmOutboxDispatcher.kt`:
  - `drain()`, `buildExecutionPlan()` (`329-398`), `executeExternal()` (`408-448`), `commitSuccessFenced()` (`456-569`).
- `app/src/main/java/com/example/reminder/AlarmReceiver.kt` & `NotificationDeliveryEngine.kt`:
  - `AlarmFireHandler.handleIntent()`، `NotificationDeliveryEngine.deliverTaskReminderInternal()` (`153-230`).

#### 3) السلوك الحالي مقابل السلوك المتوقع
- **الحالي بالكود:** المعاملات الذرية وترقيات `scheduleRevision` وإبطال التسليمات وإلغاء المنبهات مترابطة في الإنتاج، باستثناء سباق محتمل نادر في `TaskEditorSheet` إذا ضغط المستخدم «حفظ» قبل انتهاء `loadExistingExtras` في وضع التعديل.
- **الحالي بالاختبارات:** غير مُثبت بسيناريو E2E متصل عبر الطبقات.
- **المتوقع:** وجود اختبار E2E واحد أو أكثر يمر عبر الخطوات الـ 9 كاملةً من واجهة `TaskEditorSheet` ← Domain ← Room ← Outbox ← `AlarmOutboxDispatcher.drain()` ← `FakeAlarmScheduler` / `AlarmFireHandler` / `DeliveryState` دون انقطاع.

#### 4) الاختبارات الحالية والفصل الصارم بين E2E والمعزولة
- **اختبارات معزولة (UI فقط — لا تشغّل `AlarmOutboxDispatcher.drain()` ولا تفحص `AlarmScheduler`):**
  - `Unit4A2DTimePickerAndEditorTest.kt:153, 334, 400`: تختبر إنشاء/تعديل المهمة في `TaskEditorSheet` وتقرأ صفوف Room للتذكيرات فقط.
  - `Unit4A2D3DBatch1HardeningTest.kt:533, 656, 748, 895`: تختبر تعديل الجدول، حالة `PAUSED` وإعادة التفعيل عبر `TaskEditorSheet` و`TaskUseCases`، وتفحص جدول `alarm_sync_outbox` في Room فقط دون تصريفه عبر `AlarmOutboxDispatcher`.
- **اختبارات معزولة (Domain/Repository فقط — لا تستخدم `TaskEditorSheet` ولا `AlarmOutboxDispatcher`):**
  - `Unit4ATaskSyncAndRulesTest.kt:138, 211, 267, 568`: تفحص صفوف `TaskReminderEntity` و`AlarmSyncOutboxEntity` و`DeliveryState` عبر `TaskUseCases` و`RoomTaskRepository`.
- **اختبارات معزولة (Dispatcher/Delivery فقط — لا تستخدم `TaskEditorSheet`):**
  - `Unit4A2AOutboxDispatcherTest.kt` (20 اختباراً، أبرزها `641, 667, 699, 765, 800, 834`): تُدرج صفوفاً اصطناعية مباشرة في `reminderDao` و`outboxDao` ثم تُصرّف `AlarmOutboxDispatcher`.
  - `Unit4A2BNotificationDeliveryTest.kt` (14 اختباراً، أبرزها `259, 315, 712, 804`): تُدرج صفوفاً اصطناعية في Room ثم تشغل `AlarmFireHandler` / `NotificationDeliveryEngine` / `NotificationActionHandler`.
- **اختبارات E2E حقيقية تغطي السلسلة الكاملة `TaskEditorSheet -> Room -> Outbox -> AlarmOutboxDispatcher -> AlarmScheduler/AlarmFireHandler`:** **غير موجودة (0 اختبارات).**

#### 5) الفجوات التي تحتاج اختبارات جديدة
1. **اختبار E2E كامل (9 خطوات) في حلقة متصلة واحدة:**
   - إنشاء مهمة مجدولة عبر `TaskEditorSheet` وإضافة تذكير نسبي (`-15m`) وتذكير صريح (`08:00`) وحفظها ← تصريف `AlarmOutboxDispatcher.drain()` ← التأكد من جدولة منبهين (`rev = 1`) وحالة `SCHEDULED_EXACT`/`SCHEDULED_INEXACT` وعدم وجود أوامر معلقة في Outbox.
   - إعادة فتح `TaskEditorSheet` على نفس المهمة، تعديل التاريخ و`startTime` وحفظ التغييرات ← تصريف `AlarmOutboxDispatcher.drain()` ← التأكد من ترقية `scheduleRevision = 2` (مع بقاء التذكير الصريح `08:00` على `08:00` وتحرك النسبي وفق السياسة المعتمدة)، ونسخ `PendingIntent` بـ `rev = 2` دون فقدان أي أمر جدولة ودون تكرار منبهات قديمة (`rev = 1`).
   - مسار (أ) **إلغاء المهمة** ثم تصريف `AlarmOutboxDispatcher.drain()` + محاولة إطلاق `AlarmFireHandler` بالـ Intent القديم ← التأكد من إلغاء المنبهات، بقاء `DeliveryState` منضبطاً وعدم نشر أي إشعار.
   - مسار (ب) **حذف المهمة** بعد تعديل سريع (قبل أو بعد تصريف `SCHEDULE_ALARM`) ← تصريف `AlarmOutboxDispatcher.drain()` ← التأكد من إلغاء المنبهات في `FakeAlarmScheduler` وخلو جدول التذكيرات والـ Outbox المعلق دون بقاء منبه يتيم (`Orphan Alarm`).
2. **اختبار حالة التحميل السريع في `TaskEditorSheet`:** الضغط السريع على الحفظ أثناء تحميل `loadExistingExtras` للتأكد من عدم فقدان التكرار أو التذكيرات الموجودة.

#### 6) نطاق التعديل المقترح بأقل تدخل ممكن
- إضافة حزمة/ملف اختبار E2E حقيقي مستقل (مثل `Unit4A2D3DEndToEndIntegrationTest.kt`) يربط `TaskEditorSheet` (عبر `ComposeTestRule`) مع `DefaultAppContainer` و`AlarmOutboxDispatcher` و`FakeAlarmScheduler` و`AlarmFireHandler` و`NotificationDeliveryEngine` على قاعدة بيانات Room في الذاكرة.
- (تحسين ضيق في `TaskEditorSheet.kt:166-197, 430-653, 1051-1061`): إضافة علم `extrasLoaded` لتعطيل زر الحفظ مؤقتاً في وضع التعديل حتى تكتمل `loadExistingExtras` (ميلي ثوانٍ معدودة)، وتتبع `hasSavedTask` لإبلاغ `onSaved` عند الإغلاق بعد `partialSaveWarning`.

#### 7) مخاطر التعديل والاعتماديات
- **منخفضة (Low):**
  - إنشاء اختبار E2E لا يمس شيفرة الإنتاج إطلاقاً.
  - إضافة `extrasLoaded` في `TaskEditorSheet` يجب ألّا تعطل أي اختبار حالي يفتح المحرر ويحفظ مباشرة (بمجرد تشغيل `LaunchedEffect(taskKey)` يصبح `extrasLoaded = true` في أقل من إطاري رسم).

#### 8) تصنيف الخطورة
- **`MAJOR`** (غياب تام لاختبار E2E يثبت تكامل `TaskEditorSheet` الفعلي مع `AlarmOutboxDispatcher` و`AlarmScheduler` عبر دورة الإنشاء والتعديل والإلغاء والحذف).

---

### `DEF-08` — تغطية فتح والتفاعل مع بطاقة التكرار (`TaskDetailRecurrenceCard.kt`)

#### 1) السبب الجذري (Root Cause)
تم فحص `TaskDetailRecurrenceCard.kt` و`TaskDetailScreen.kt` و`TaskDetailViewModel.kt` و`Unit4A2D3TaskDetailRecurrenceTest.kt`:
- **التنفيذ الإنتاجي في `TaskDetailRecurrenceCard.kt`:**
  - البطاقة مدمجة دائماً داخل `TaskDetailUiState.Loaded` (`TaskDetailScreen.kt:630-638`).
  - تعرض حالة الفراغ (`task_detail_recurrence_empty` وزر `task_detail_add_recurrence_btn`) عند `recurrence == null`، وصندوق الملخص (`task_detail_recurrence_summary_box`) مع شارة التردد وملخص الوتيرة وأيام الأسبوع وتاريخ الانتهاء وأزرار التعديل والحذف مع تأكيد (`task_detail_recurrence_delete_dialog`) عند وجود قاعدة.
  - تفتح `TaskRecurrenceEditorDialog` (`108-121, 451-788`) لتعديل `frequency` (`DAILY`, `WEEKLY`, `MONTHLY`, `YEARLY`, `CUSTOM_DAYS`) و`interval` و`daysOfWeekMask` و`endDate` والتحقق عبر `TaskRecurrenceRules.validateInput` (`764`).
  - تعامل `CUSTOM_DAYS` بوضوح عبر `formatCadenceSummary` (`806-809`: "Every N weeks" / "كل N أسبوع") مع تلميح صريح (`task_detail_recurrence_custom_days_hint` عند `668-669`) واختيار أيام الأسبوع من الإثنين إلى الأحد (`649-663`).
  - تحرس `COMPLETED` و`CANCELLED` بتعطيل أزرار الإضافة/التعديل/الحذف (`isTaskActive` عند `102, 198, 214-221`) بالتكامل مع حراسة `TaskUseCases` المضافة في Batch 1.
  - تراقب `taskRepository.observeTaskRecurrence(taskId)` (`TaskDetailViewModel.kt:162`) فتتحدث لحظياً عند تعديل التكرار من مصدر خارجي (مثل `TaskEditorSheet`).
- **نقاط الضعف والخلل المحدودة المرصودة:**
  1. **في الكود (`MINOR`):** `TaskRecurrenceEditorDialog` (`464-475`) يستخدم `remember(initialRecurrence)` لتهيئة مسودات `frequency`/`interval`/`daysOfWeekMask`/`endDate`؛ إذا تغير `initialRecurrence` من Flow أثناء بقاء الحوار مفتوحاً تُعاد تهيئة المسودة وقد تفقد تعديلات المستخدم غير المحفوظة (الأصح `remember(initialRecurrence?.id, isEditMode)` أثناء فتح الحوار، أو عزله بقيمة البداية عند الفتح).
  2. **في الكود (`INFORMATIONAL`):** لا توجد حالة تحميل مستقلة داخل البطاقة نفسها لأن `TaskDetailScreen` تعرض `task_detail_loading` لكامل الشاشة حتى تصبح `TaskDetailUiState.Loaded` جاهزة (`399-406`)؛ وعند فشل الحفظ في الـ VM تُرسل `TaskDetailUiEvent.ActionFailed` إلى الـ Snackbar، بينما أخطاء التحقق قبل الإرسال تظهر inline عبر `task_detail_recurrence_editor_error` (`736-743`).
  3. **في التغطية الاختبارية:** اختبارات `Unit4A2D3TaskDetailRecurrenceTest.kt` الخمسة غطت الحالة الأساسية فقط في الوضع الداكن وغالباً بالإنجليزية LTR:
     - غياب كامل لاختبارات **الوضع الفاتح (`AppThemeMode.LIGHT`)**.
     - غياب اختبار **تدفق التفاعل الكامل (فتح الحوار ← إضافة/تعديل ← تحقق خطأ ← حفظ ← حذف مع تأكيد)** باللغة **العربية RTL** (اختبار RTL الحالي `138` يعرض قاعدة موجودة فقط ولا يفتح الحوار لتعديل/حفظ/حذف!).
     - غياب اختبار يثبت **التحديث اللحظي لـ `TaskDetailRecurrenceCard` عبر Room `Flow`** أثناء بقاء `TaskDetailScreen` مفتوحة عند تعديل التكرار من `TaskEditorSheet` (المفتوحة من زر تعديل الشريط العلوي) دون إغلاق وإعادة فتح شاشة التفاصيل.
     - غياب assertion صريح لعدم تغيّر `TaskEntity` و`TaskReminderEntity` عند تعديل أو حذف التكرار (السيناريو 3).

#### 2) الملفات والدوال وأرقام الأسطر الدقيقة
- `app/src/main/java/com/example/feature/tasks/detail/TaskDetailScreen.kt`:
  - `Loading` state (`399-406`)، ربط `TaskDetailRecurrenceCard` (`630-638`)، فتح `TaskEditorSheet` (`198-213`).
- `app/src/main/java/com/example/feature/tasks/detail/TaskDetailViewModel.kt`:
  - `extrasFlow` ومراقبة `observeTaskRecurrence(taskId)` (`159-170`)، `saveRecurrence()` (`371-394`)، `removeRecurrence()` (`396-418`).
- `app/src/main/java/com/example/feature/tasks/detail/TaskDetailRecurrenceCard.kt`:
  - `TaskDetailRecurrenceCard()` (`87-320`)، `TaskRecurrenceEditorDialog()` (`451-788`: ملاحظة `remember(initialRecurrence)` عند `464-475`، أيام الأسبوع و`CUSTOM_DAYS` عند `634-677`، التحقق عند `764-769`)، `formatCadenceSummary()` (`800-816`).
- `app/src/test/java/com/example/Unit4A2D3TaskDetailRecurrenceTest.kt`: الأسطر `137-832` (الاختبارات الخمسة).

#### 3) السلوك الحالي مقابل السلوك المتوقع
- **الحالي:** البطاقة والحوار يعملان بدقة لجميع الترددات و`CUSTOM_DAYS` وحراسة الحالة المنتهية، لكن التغطية الاختبارية تقتصر على الوضع الداكن وتفتقد تفاعل الحوار في العربية RTL واختبار التحديث اللحظي عبر Room Flow دون إعادة فتح الشاشة، كما أن `remember(initialRecurrence)` يعيد تهيئة الحوار لو تغيّرت القاعدة خارجياً أثناء الفتح.
- **المتوقع:** ثبات مسودة الحوار أثناء الفتح، وتغطية اختبارية متكاملة تشمل الوضع الفاتح (`LIGHT`) والعربية RTL تفاعلياً (إضافة/تعديل/خطأ/حذف) وتحديث بطاقة التفاصيل لحظياً من `TaskEditorSheet`/Room Flow مع تأكيد عدم المساس بحقول `TaskEntity` و`TaskReminderEntity`.

#### 4) الاختبارات الحالية والفصل الصارم بين E2E والمعزولة
- **اختبارات Compose UI + Room على `TaskDetailScreen` (`Unit4A2D3TaskDetailRecurrenceTest.kt`):**
  1. `taskDetailRecurrence_displaysExistingCustomDaysRuleInArabicRtlAndEnglishLtr` (`138`) — عرض فقط (Dark theme) لقاعدة جاهزة بالعربية RTL ثم الإنجليزية LTR؛ لا يفتح `TaskRecurrenceEditorDialog`.
  2. `taskDetailRecurrence_addEditAllFrequenciesValidationEndDateAndDelete` (`228`) — إضافة وتعديل وحذف وتواريخ انتهاء وأخطاء تحقق (إنجليزي LTR وDark فقط).
  3. `taskDetailRecurrence_completeGeneratesNextOccurrenceAndReopenPreservesItWithWarning` (`525`) — إكمال وإعادة فتح من `COMPLETED` (إنجليزي LTR وDark فقط).
  4. `taskDetailRecurrence_unscheduledAndCancelledUiGuardsDisableActions` (`663`) — حراسة غير المجدولة والملغاة (إنجليزي LTR وDark فقط).
  5. `taskDetailViewModel_rejectsRecurrenceMutationsOnCompletedAndCancelledTasks` (`744`) — اختبار ViewModel معزول.
- **اختبارات Batch 1 (`Unit4A2D3DBatch1HardeningTest.kt`):**
  6. `def01_taskDetailAndEditorUi_lockRecurrenceAndRemindersOnTerminalTasks_enAndAr` (`369`) — اختبار قفل الواجهة بالعربية والإنجليزية (Dark).

#### 5) الفجوات التي تحتاج اختبارات جديدة
1. اختبار تفاعلي لـ `TaskDetailRecurrenceCard` و`TaskRecurrenceEditorDialog` في **الوضع الفاتح (`AppThemeMode.LIGHT`)** و**العربية RTL** (حالة فراغ ← فتح الحوار ← خطأ تحقق لعدم اختيار أيام في `CUSTOM_DAYS` ← اختيار أيام و`interval = 2` ← تحقق ظهور «كل ٢ أسبوع» ← حفظ ← تعديل ← حذف مع تأكيد).
2. اختبار **تحديث Flow لحظي بدون إعادة فتح `TaskDetailScreen`**: فتح `TaskDetailScreen` على مهمة بلا تكرار ← فتح `TaskEditorSheet` من زر التعديل العلوي ← إضافة/تعديل تكرار ثم الحفظ ← التحقق من ظهور بطاقة التكرار المحدثة مباشرة على `TaskDetailScreen` المفتوحة، وأيضاً عند تعديل قاعدة عبر `taskUseCases.saveTaskRecurrence` في الخلفية مع ثبات `TaskEntity` و`TaskReminderEntity`.
3. فحص صريح داخل اختبار التعديل لبقاء `scheduledDate` و`startTime` و`endTime` و`status` في `TaskEntity` و`TaskReminderEntity` دون أي تغيير.

#### 6) نطاق التعديل المقترح بأقل تدخل ممكن
- في `TaskDetailRecurrenceCard.kt:464-475`: تغيير مفتاح التذكر في `TaskRecurrenceEditorDialog` عند الحاجة إلى `remember(isEditMode)` أو `remember(Unit)` (طالما أن الحوار يُنشأ ويُزال عبر `if (showEditorDialog)` عند كل فتح وإغلاق) حتى لا تضيع مدخلات المستخدم إذا حدث إشعار Room أثناء فتح الحوار.
- توسيع الاختبارات في حزمة Batch 2 الجديدة لتغطية الوضع الفاتح والعربية RTL التفاعلية وتحديث Flow اللحظي وعدم المساس بحقول المهمة والتذكيرات.

#### 7) مخاطر التعديل والاعتماديات
- **منخفضة جداً (Low):** `TaskRecurrenceEditorDialog` مرسوم داخل `if (showEditorDialog)` (`108`) لذا يتركب من الصفر عند كل فتح ويُفكك عند كل إغلاق؛ الاعتماد الوحيد هو استخدام `remember` بدون `initialRecurrence` كمفتاح إعادة ضبط.

#### 8) تصنيف الخطورة
- **`MINOR`** (التكوين الأساسي للبطاقة والحوار يعملان بشكل صحيح، والعيوب محصورة في `remember(initialRecurrence)` وفجوات تغطية الوضع الفاتح وتفاعل العربية RTL وتحديث Flow اللحظي من المحرر).

---

## 3. التحقق من الانحدار والقيود المعمارية (Regression & Architectural Constraints)

| البند المعماري / المرحلة | حالة التحقق في الكود والنتائج المعتمدة | التوافق مع قيود Batch 1 & Phase 4A.2D |
|---|---|---|
| **Phases 1–2 & Units 3A, 3B.1–3B.4** | مغطاة بالكامل في `13` حزمة XML ضمن نتائج Batch 1 (`159/159` PASS). لم ترصد أي تراجعات في `MainActivity` أو `TodayScreen` أو `TasksScreen` أو `TaskDetailScreen`. | **متوافق 100%** |
| **Units 4A.1, 4A.2A–4A.2D, 4A.2D.3A–3C** | مغطاة في `9` حزم XML ضمن نتائج Batch 1؛ نواة Outbox وDelivery وReconciler ونوافذ اليوم التالي ومنتقي الوقت تعمل من دون محركات موازية داخل `feature/`. | **متوافق 100%** |
| **قرارات Batch 1 (`DEF-01`, `DEF-02`, `DEF-03`)** | محفوظة بالكامل: حراسة `COMPLETED`/`CANCELLED` في `TaskUseCases`/`TaskReminderUseCases`/`TaskEditorSheet`، سياسة `DEF-02` لـ `offsetMinutes == 0` مع توثيق القيد البنيوي لـ Schema v1 (عدم التمييز عند تطابق `previousTask.startTime == triggerTime`)، وحفظ حالة `PAUSED` في `TaskEditorSheet`/`TaskEditorExtrasSections`. | **محفوظة بالكامل — ممنوع كسرها في Batch 2** |
| **Room Schema v1 & `identityHash`** | `version = 1` (`YawmiDatabase.kt:59, 86`), `identityHash = "37c351a60c04e881c6bd50156833f191"` (`1.json:4-5`), `YawmiMigrations.ALL_MIGRATIONS = emptyArray()`. | **ثابت دون تغيير** |
| **Outbox Single-Flight / Retry Policy** | `DispatcherLeaseManager` (lease + generation fencing + CAS)، حد 5 محاولات (`MAX_RETRY_ATTEMPTS = 5`)، دمج الأوامر عبر `supersedePendingAlarmByIdentityBeforeSeq` و`hasNewerCommandForTarget`. | **سليم دون تغيير** |
| **`PendingIntent` Identity & `requestCode` Uniqueness** | مخصّص ذري عبر `NEXT_REMINDER_REQUEST_CODE` وTriggers في `YawmiDatabase.kt:133-166`؛ معرّفات `PendingIntent` لإجراءات الإشعار تستخدم `requestCodeFor("open"/"complete"/"snooze", reminderId, occurrenceKey)` مع URI مميز. | **سليم دون تغيير** |
| **Local-First / Offline & AR RTL / EN LTR** | لا اعتماد شبكي؛ `ProvideYawmiLocale` و`supportsRtl="true"` و`MaterialTheme.colorScheme` مطبقة في جميع البطاقات والـ Bottom Sheets. | **متوافق (مع معالجة فجوة `DEF-05` واختبار Light/AR لـ `DEF-08`)** |
| **حصر النطاق بعيداً عن Unit 4B / Calendar** | `Unit4BCalendarTest.kt` والملفات الخاصة بالتقويم لم تُمسّ ولم تُشغّل. | **ملتزم تماماً** |

---

## 4. جدول ملخص تصنيف الخطورة (`DEF-04` – `DEF-08`)

| المعرف | الموضوع | التصنيف الأعلى | تفاصيل التصنيف (كود مقابل تغطية اختبارية) |
|---|---|---|---|
| **`DEF-04`** | إشعار / إعادة فتح مهمة ملغاة (`CANCELLED`) | **`MAJOR`** *(تغطية E2E)* / **`MINOR`** *(كود)* | نواة Outbox/Delivery/Reconciler تحجب الإشعارات فعلياً وتبقي التذكير `CANCELLED`؛ **خلل كود `MINOR`**: `TaskDetailViewModel.reopenTask` يعرض تحذير تكرار مضلل لمهمة `CANCELLED`؛ **فجوة `MAJOR`**: 0 اختبارات E2E لسباق `cancelTask -> reopenTask -> Drain/Fire/Reconcile`. |
| **`DEF-05`** | شرائح اليوم/غداً حول منتصف الليل وتغيير المنطقة | **`MINOR`** | **خلل كود `MINOR` مؤكد**: `TaskEditorScheduleSection.kt:77-82, 90-97` تستخدم `remember(localClock)` على كائن مفرد ثابت؛ وتفتقر لاختبار عبور منتصف الليل وتبدل المنطقة الزمنية. |
| **`DEF-06`** | تكامل ملاحة فتح المهمة (`Today`/`Tasks`/إشعار/تكرار) | **`MAJOR`** *(تغطية UI)* / **`MINOR`** *(UX/كود)* | توصيل `MainActivity`/`YawmiShell` سليم؛ **فجوة `MAJOR`**: 0 اختبارات UI لملاحة `EXTRA_OPEN_TASK_ID` (`onCreate`/`onNewIntent`/`recreate`/`NotFound`) ولفتح التفاصيل من `TasksScreen`؛ **ملاحظة `MINOR`**: غياب `launchSingleTop` في نقر القوائم وغياب إجراء فتح النسخة القادمة من `Snackbar`. |
| **`DEF-07`** | تكامل `TaskEditorSheet` + Reminders + `AlarmOutboxDispatcher` | **`MAJOR`** *(تغطية E2E)* | السلسلة الـ 9 مترابطة في الإنتاج؛ **فجوة `MAJOR`**: 0 اختبارات E2E تربط `TaskEditorSheet` بتصريف `AlarmOutboxDispatcher.drain()` و`AlarmFireHandler` (الاختبارات الحالية معزولة تماماً)، مع ملاحظة سباق `loadExistingExtras` وإغلاق `partialSaveWarning`. |
| **`DEF-08`** | فتح والتفاعل مع `TaskDetailRecurrenceCard` | **`MINOR`** | البطاقة والحوار مكتملان وظيفياً؛ **ملاحظة كود `MINOR`**: `remember(initialRecurrence)` في `TaskRecurrenceEditorDialog`؛ **فجوات اختبار `MINOR`**: الوضع الفاتح (`Light`)، تفاعل الحوار بالعربية RTL، تحديث Flow اللحظي من `TaskEditorSheet`، والتحقق الصريح من ثبات `TaskEntity`/`TaskReminderEntity`. |

---

## 5. التوصية النهائية

الهندسة الأساسية للجدولة والإشعارات والـ Outbox والملاحة سليمة (`0 BLOCKER`، `0 CRITICAL` في دفعة `DEF-04`..`DEF-08`)، وتتركز متطلبات **Batch 2** في:
1. **إصلاحات كود محدودة ومحصورة جداً** دون المساس بـ Schema v1 (`identityHash = "37c351a60c04e881c6bd50156833f191"`) أو قرارات Batch 1 (`DEF-01`..`DEF-03`):
   - `TaskDetailViewModel.kt`: قصر `reopenedRecurringState` على إعادة الفتح من `COMPLETED` فقط (`DEF-04`).
   - `TaskEditorScheduleSection.kt` + `TaskEditorSheet.kt` (+ تمرير من الشاشات الأب): جعل `todayDate` و`zoneId` تفاعليين عبر منتصف الليل وتغيرات الوقت والمنطقة الزمنية (`DEF-05`)، وتحصين `extrasLoaded` / إغلاق `partialSaveWarning` (`DEF-07`).
   - `YawmiShell.kt`: إضافة `launchSingleTop = true` في نداءات `TaskDetailRoute` (واختيارياً دعم فتح الـ occurrence التالي في `TaskDetailScreen`/`TaskDetailViewModel`) (`DEF-06`).
   - `TaskDetailRecurrenceCard.kt`: تثبيت حالة مسودة `TaskRecurrenceEditorDialog` أثناء الفتح (`DEF-08`).
2. **إضافة حزمة اختبارات التكامل الشامل (E2E) والانحدار المستهدفة** لسد جميع الفجوات الموثقة في `DEF-04`..`DEF-08` (مع فصل صارم عن الاختبارات المعزولة وتشغيل الحزم غير المتعلقة بالتقويم فقط دون المساس بـ `Unit4BCalendarTest.kt`).

**الحالة الحالية:** **`AUDIT COMPLETE — AWAITING IMPLEMENTATION AUTHORIZATION FOR BATCH 2`**
