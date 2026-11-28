package com.example.data.local.db

object YawmiMetadataKeys {
  const val SCHEMA_INITIALIZED = "schema_initialized"
  const val OUTBOX_SEQ = "outbox_seq"
  const val NEXT_REMINDER_REQUEST_CODE = "next_reminder_request_code"
  const val DISPATCHER_LEASE_OWNER = "dispatcherLeaseOwner"
  const val DISPATCHER_LEASE_UNTIL_EPOCH_MS = "dispatcherLeaseUntilEpochMs"
  const val DISPATCHER_LEASE_GENERATION = "dispatcherLeaseGeneration"
  const val LAST_EXACT_ALARM_CAPABILITY = "last_exact_alarm_capability"
  const val LAST_RECONCILED_ZONE_ID = "last_reconciled_zone_id"
  const val RECONCILE_FORCE_REQUEST_SEQ = "reconcile_force_request_seq"
  const val RECONCILE_FORCE_HANDLED_SEQ = "reconcile_force_handled_seq"

  const val MIN_REMINDER_REQUEST_CODE = 1000

  val INTERNAL_NON_IMPORTABLE_KEYS: Set<String> =
    setOf(
      SCHEMA_INITIALIZED,
      OUTBOX_SEQ,
      NEXT_REMINDER_REQUEST_CODE,
      DISPATCHER_LEASE_OWNER,
      DISPATCHER_LEASE_UNTIL_EPOCH_MS,
      DISPATCHER_LEASE_GENERATION,
      LAST_EXACT_ALARM_CAPABILITY,
      LAST_RECONCILED_ZONE_ID,
      RECONCILE_FORCE_REQUEST_SEQ,
      RECONCILE_FORCE_HANDLED_SEQ,
    )
}
