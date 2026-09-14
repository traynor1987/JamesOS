# Shift Tracker ↔ James OS Work Context Contract

## Contract
`JAMES_WORK_CONTEXT_V1`, version `1`. This is local Android IPC. Shift Tracker owns the authoritative work history; James OS is a read-only consumer of minimal work context. The payload **must never contain** customer names, phone numbers, addresses, postcodes, order IDs, payment data, instructions, or customer destination coordinates.

## James OS receiver
- Package: the installed James OS application ID.
- Explicit receiver component: `uk.co.james.work.ShiftTrackerWorkReceiver`.
- Live action: `uk.co.james.action.SHIFT_TRACKER_WORK_EVENT`.
- JSON extra: `uk.co.james.extra.SHIFT_TRACKER_WORK_PAYLOAD`, UTF-8 and at most 24 KiB.
- Security: the receiver requires `uk.co.james.permission.SHIFT_TRACKER_WORK_CONTEXT` with Android `signature` protection. Shift Tracker must be signed by the same trusted signing identity, declare the uses-permission, and target the receiver explicitly. A package name in JSON is never authentication.

## Event JSON
```json
{"contractVersion":1,"eventId":"evt-001","shiftId":"shift-2026-09-14-a","eventType":"DELIVERY_STARTED","occurredAt":"2026-09-14T12:14:00Z","revision":1,"deleted":false,"deliveryType":"SINGLE","deliveryId":"delivery-018"}
```

Required: `contractVersion`, `eventId` (1–128), `shiftId` (1–128), `eventType`, ISO-8601 Instant `occurredAt`, and integer `revision` (0–1,000,000). Optional bounded fields: `deleted`, `deliveryType` (`SINGLE` or `DOUBLE`), `deliveryId`, `breakId`, `taskId`, `taskType`. Unknown fields are ignored. Unsupported versions/types, missing IDs/timestamps, timestamps before 2000 or more than 24 hours in the future, and oversize payloads are rejected.

Supported event types: `SHIFT_STARTED`, `SHIFT_ENDED`, `BREAK_STARTED`, `BREAK_ENDED`, `DELIVERY_STARTED`, `DELIVERY_COMPLETED`, `RETURNED_TO_STORE`, `TASK_STARTED`, `TASK_ENDED`, `WORK_STATE_CORRECTION`.

## Revisions, deletion and ordering
Stable `eventId` is idempotency identity. A higher revision replaces the current canonical event; equal/lower revisions never roll it back. `deleted:true` is an authoritative tombstone: provenance remains but current state/session influence is removed. Events may arrive out of order and are ordered by authoritative timestamps when sessions are derived.

## Reconciliation
James OS requests bounded history using explicit action `uk.co.james.action.SHIFT_TRACKER_RECONCILE` with the same JSON extra:
```json
{"contractVersion":1,"cursor":"","maxPageSize":200,"initialHistoryDays":40}
```
Part 2 must expose a discoverable local receiver for that action, return no more than 200 events per page through the protected live-event receiver, and include a current-state response where supported. Cursor/revision replay is safe and persists in James OS integration state. The initial import is limited to the last 40 days; no lifetime-history Binder payload is allowed.

## James OS behaviour
James OS persists canonical `WorkEvent` records in its existing Room-backed records store, preserving external IDs, revision, source, receipt time and James Day ownership. It derives `WorkSession` and `CurrentWorkState`: `OFF_WORK`, `WORKING`, `AT_STORE`, `DELIVERY`, `BREAK`, `TASK`, `RECONCILIATION_REQUIRED`, or `UNKNOWN`. Shift Tracker explicit state beats weak location/activity inference. Local manual context corrections are separate records and never mutate Shift Tracker truth. A shift without an end is marked stale/reconciliation-required after 18 hours; James OS never invents an end.