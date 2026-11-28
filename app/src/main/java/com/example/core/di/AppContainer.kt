package com.example.core.di

import android.content.Context
import com.example.core.preferences.AppAppearanceRepository
import com.example.core.preferences.DataStoreAppAppearanceRepository
import com.example.core.time.LocalClock
import com.example.core.time.SystemLocalClock
import com.example.data.local.db.YawmiDatabase
import com.example.data.repository.ReminderOutboxCoordinator
import com.example.data.repository.RoomCategoryRepository
import com.example.data.repository.RoomGoalRepository
import com.example.data.repository.RoomHabitRepository
import com.example.data.repository.RoomNoteRepository
import com.example.data.repository.RoomReminderSyncRepository
import com.example.data.repository.RoomTaskRepository
import com.example.domain.category.CategoryUseCases
import com.example.domain.repository.CategoryRepository
import com.example.domain.repository.GoalRepository
import com.example.domain.repository.HabitRepository
import com.example.domain.repository.NoteRepository
import com.example.domain.repository.ReminderSyncRepository
import com.example.domain.repository.TaskRepository
import com.example.domain.task.TaskReminderUseCases
import com.example.domain.task.TaskUseCases
import com.example.reminder.AlarmFireHandler
import com.example.reminder.AlarmOutboxDispatcher
import com.example.reminder.AlarmScheduler
import com.example.reminder.AndroidAlarmManagerScheduler
import com.example.reminder.AndroidNotificationPoster
import com.example.reminder.DispatcherLeaseManager
import com.example.reminder.NotificationActionHandler
import com.example.reminder.NotificationCancellationExecutor
import com.example.reminder.NotificationChannelManager
import com.example.reminder.NotificationDeliveryEngine
import com.example.reminder.NotificationPendingIntentFactory
import com.example.reminder.NotificationPoster
import com.example.reminder.OutboxDrainTrigger
import com.example.reminder.PendingIntentFactory
import com.example.reminder.ReminderReconciler
import com.example.reminder.SystemNotificationCanceller
import com.example.reminder.SystemReconciliationTrigger
import com.example.reminder.WorkManagerOutboxTrigger
import com.example.reminder.WorkManagerSystemReconciliationTrigger
import kotlinx.coroutines.flow.first

interface AppContainer {
  val appearanceRepository: AppAppearanceRepository
  val localClock: LocalClock
  val database: YawmiDatabase
  val categoryRepository: CategoryRepository
  val taskRepository: TaskRepository
  val habitRepository: HabitRepository
  val goalRepository: GoalRepository
  val noteRepository: NoteRepository
  val reminderSyncRepository: ReminderSyncRepository
  val outboxDrainTrigger: OutboxDrainTrigger
    get() = OutboxDrainTrigger.NoOp
  val systemReconciliationTrigger: SystemReconciliationTrigger
    get() = SystemReconciliationTrigger.NoOp
  val taskUseCases: TaskUseCases
  val taskReminderUseCases: TaskReminderUseCases
    get() = TaskReminderUseCases(taskRepository, reminderSyncRepository, localClock)
  val categoryUseCases: CategoryUseCases
}

class DefaultAppContainer(
  context: Context,
  databaseProvider: ((Context) -> YawmiDatabase)? = null,
  localClockProvider: (() -> LocalClock)? = null,
  alarmSchedulerProvider: ((Context, PendingIntentFactory) -> AlarmScheduler)? = null,
  notificationCancellerProvider: ((Context) -> NotificationCancellationExecutor)? = null,
  outboxDrainTriggerProvider: ((Context) -> OutboxDrainTrigger)? = null,
  notificationPosterProvider: ((Context) -> NotificationPoster)? = null,
  systemReconciliationTriggerProvider: ((Context) -> SystemReconciliationTrigger)? = null,
) : AppContainer {
  private val appContext = context.applicationContext

  override val appearanceRepository: AppAppearanceRepository by lazy {
    DataStoreAppAppearanceRepository(appContext)
  }

  override val localClock: LocalClock by lazy {
    localClockProvider?.invoke() ?: SystemLocalClock()
  }

  override val database: YawmiDatabase by lazy {
    databaseProvider?.invoke(appContext) ?: YawmiDatabase.build(appContext)
  }

  val pendingIntentFactory: PendingIntentFactory by lazy {
    PendingIntentFactory(appContext)
  }

  val alarmScheduler: AlarmScheduler by lazy {
    alarmSchedulerProvider?.invoke(appContext, pendingIntentFactory)
      ?: AndroidAlarmManagerScheduler(appContext, pendingIntentFactory)
  }

  val notificationCanceller: NotificationCancellationExecutor by lazy {
    notificationCancellerProvider?.invoke(appContext) ?: SystemNotificationCanceller(appContext)
  }

  val dispatcherLeaseManager: DispatcherLeaseManager by lazy {
    DispatcherLeaseManager(
      database = database,
      appMetadataDao = database.appMetadataDao(),
      alarmSyncOutboxDao = database.alarmSyncOutboxDao(),
      localClock = localClock,
    )
  }

  val alarmOutboxDispatcher: AlarmOutboxDispatcher by lazy {
    AlarmOutboxDispatcher(
      database = database,
      outboxDao = database.alarmSyncOutboxDao(),
      reminderDao = database.reminderDao(),
      leaseManager = dispatcherLeaseManager,
      alarmScheduler = alarmScheduler,
      notificationCanceller = notificationCanceller,
      localClock = localClock,
    )
  }

  override val outboxDrainTrigger: OutboxDrainTrigger by lazy {
    outboxDrainTriggerProvider?.invoke(appContext) ?: WorkManagerOutboxTrigger(appContext)
  }

  private val outboxCoordinator: ReminderOutboxCoordinator by lazy {
    ReminderOutboxCoordinator(
      appPackageName = appContext.packageName,
      appMetadataDao = database.appMetadataDao(),
      reminderDao = database.reminderDao(),
      alarmSyncOutboxDao = database.alarmSyncOutboxDao(),
    )
  }

  override val categoryRepository: CategoryRepository by lazy {
    RoomCategoryRepository(database.categoryDao())
  }

  override val taskRepository: TaskRepository by lazy {
    RoomTaskRepository(
      database = database,
      taskDao = database.taskDao(),
      recurrenceDao = database.recurrenceDao(),
      reminderDao = database.reminderDao(),
      deliveryDao = database.reminderDeliveryDao(),
      outboxCoordinator = outboxCoordinator,
      localClock = localClock,
      goalDao = database.goalDao(),
      onOutboxCommitted = { outboxDrainTrigger.requestDrainNow() },
    )
  }

  override val habitRepository: HabitRepository by lazy {
    RoomHabitRepository(
      database = database,
      habitDao = database.habitDao(),
      recurrenceDao = database.recurrenceDao(),
      reminderDao = database.reminderDao(),
      deliveryDao = database.reminderDeliveryDao(),
      outboxCoordinator = outboxCoordinator,
    )
  }

  override val goalRepository: GoalRepository by lazy {
    RoomGoalRepository(
      database = database,
      goalDao = database.goalDao(),
      reminderDao = database.reminderDao(),
      deliveryDao = database.reminderDeliveryDao(),
      outboxCoordinator = outboxCoordinator,
    )
  }

  override val noteRepository: NoteRepository by lazy {
    RoomNoteRepository(database.noteDao())
  }

  override val reminderSyncRepository: ReminderSyncRepository by lazy {
    RoomReminderSyncRepository(
      database = database,
      reminderDao = database.reminderDao(),
      deliveryDao = database.reminderDeliveryDao(),
      outboxDao = database.alarmSyncOutboxDao(),
      metadataDao = database.appMetadataDao(),
      outboxCoordinator = outboxCoordinator,
      onOutboxCommitted = { outboxDrainTrigger.requestDrainNow() },
    )
  }

  override val taskUseCases: TaskUseCases by lazy {
    TaskUseCases(
      taskRepository = taskRepository,
      categoryRepository = categoryRepository,
      localClock = localClock,
    )
  }

  override val taskReminderUseCases: TaskReminderUseCases by lazy {
    TaskReminderUseCases(
      taskRepository = taskRepository,
      reminderSyncRepository = reminderSyncRepository,
      localClock = localClock,
    )
  }

  override val categoryUseCases: CategoryUseCases by lazy {
    CategoryUseCases(
      categoryRepository = categoryRepository,
      localClock = localClock,
    )
  }

  val notificationChannelManager: NotificationChannelManager by lazy {
    NotificationChannelManager(appContext)
  }

  val notificationPoster: NotificationPoster by lazy {
    notificationPosterProvider?.invoke(appContext) ?: AndroidNotificationPoster(appContext)
  }

  val notificationPendingIntentFactory: NotificationPendingIntentFactory by lazy {
    NotificationPendingIntentFactory(appContext)
  }

  val notificationDeliveryEngine: NotificationDeliveryEngine by lazy {
    NotificationDeliveryEngine(
      context = appContext,
      database = database,
      taskDao = database.taskDao(),
      reminderDao = database.reminderDao(),
      deliveryDao = database.reminderDeliveryDao(),
      channelManager = notificationChannelManager,
      notificationPoster = notificationPoster,
      notificationCanceller = notificationCanceller,
      pendingIntentFactory = notificationPendingIntentFactory,
      localClock = localClock,
      languageProvider = { appearanceRepository.appearanceState.first().language },
    )
  }

  val alarmFireHandler: AlarmFireHandler by lazy {
    AlarmFireHandler(
      deliveryEngine = notificationDeliveryEngine,
      reminderSyncRepository = reminderSyncRepository,
      localClock = localClock,
    )
  }

  val notificationActionHandler: NotificationActionHandler by lazy {
    NotificationActionHandler(
      taskRepository = taskRepository,
      reminderDao = database.reminderDao(),
      deliveryDao = database.reminderDeliveryDao(),
      taskUseCases = taskUseCases,
      taskReminderUseCases = taskReminderUseCases,
      notificationCanceller = notificationCanceller,
    )
  }

  val reminderReconciler: ReminderReconciler by lazy {
    ReminderReconciler(
      context = appContext,
      database = database,
      taskDao = database.taskDao(),
      reminderDao = database.reminderDao(),
      deliveryDao = database.reminderDeliveryDao(),
      outboxDao = database.alarmSyncOutboxDao(),
      appMetadataDao = database.appMetadataDao(),
      outboxCoordinator = outboxCoordinator,
      alarmScheduler = alarmScheduler,
      notificationDeliveryEngine = notificationDeliveryEngine,
      outboxDrainTrigger = outboxDrainTrigger,
      localClock = localClock,
    )
  }

  override val systemReconciliationTrigger: SystemReconciliationTrigger by lazy {
    systemReconciliationTriggerProvider?.invoke(appContext)
      ?: WorkManagerSystemReconciliationTrigger(
        appContext = appContext,
        databaseProvider = { database },
        isReconcileNeededOnResumeProvider = { reminderReconciler.isReconciliationNeededOnResume() },
        hasActiveOutboxQueueProvider = { database.alarmSyncOutboxDao().hasActiveQueue() },
        outboxDrainTriggerProvider = { outboxDrainTrigger },
      )
  }
}
