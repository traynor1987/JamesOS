# James OS audit step 1

Scope: audit findings F01–F06 and F10–F13. Branch targets the authoritative main baseline `9d54d7b308ad301af84bc7546b3af6c454eb061d`. This change is for review; it does not publish a release.

## Behaviour changes

- Visit split, merge and revert run through one ViewModel action. The editor passes its expected revision and closes after success. Unsaved or stale edits receive a useful refusal.
- Each correction commits its original evidence snapshots, factual changes and decision together in a Room transaction. Undo refuses to overwrite subsequent substantive changes, preserves every evidence record, and supports successive corrections undone in order. Older imported corrections without revision hashes require manual review of their retained snapshots.
- Life Balance and Me receive 90 days of source evidence, including intervals crossing the window boundary. A new indexed interval-end column is backfilled without changing raw payloads. Phone Room migration 7→8 is additive and preserves malformed historical evidence.
- Schedule receives seven days of upcoming plans; Today and Right now receive 36 hours. Future physiology is excluded. Current windows refresh every minute and on app resume; historical views keep their selected date. Ongoing commitments remain visible until their end.
- Calendar selections and classification rules are persistent configuration. Repeated toggles use the actual stored revision. Deselecting calendars or losing permission retires their upcoming and ongoing planned constraints without modifying observed Visits or Ownership. A Calendar Provider failure retains existing plans.
- Calibration display, analysis, candidate save and activation use the same latest 5,000 retained observations per algorithm. Both Calibration Lab routes use that dataset. Old feedback can still be edited, ignored and deleted through authoritative queries.
- Calibration predictions have a separately loaded production evidence stream. Live readings, settings and clock updates recompute the displayed target. Opening feedback freezes the displayed prediction and its source snapshot together; later readings cannot silently change that feedback's input evidence.
- Phone ingestion preserves the watch measurement timestamp with named source/timestamp arguments, deterministic record identity and retry metadata. Malformed, non-finite, negative or impossible future observations are rejected and only persisted observations are acknowledged.
- Passive watch points use their Health Services boot-relative measurement time, anchored once per callback. Watch Room migration 1→2 adds delivery state. Acknowledgements remove readings from the retry queue while retaining local history; purging never drops unsent readings.

## Review rulings

The independent whole-branch review identified four Important issues: a separate loaded calibration production stream, the compact Today calibration route alias, undo hashes excluding transport-only `updatedAt`, and live rolling query bounds. Each was accepted and covered by a regression test in one fix pass. No Critical or Minor findings were reported.

Evidence and calibration meaning remain unchanged: plans do not create actual ownership, feedback does not immediately change production, and later edits cannot be overwritten by undo. Stable release signing configuration is untouched.

## Validation

Regression coverage includes transactional rollback, stale revisions, successive undo, 90-day and boundary-spanning ownership, future plan/physiology separation, Calendar retirement, old calibration candidate evidence, cold production loading, live readings, and frozen feedback snapshots.

Run the public checks with `:wear-evidence:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`. Validate phone release compilation with `JAMES_ALLOW_UNSIGNED_RELEASE=true` and `:app:assembleRelease :app:bundleRelease :app:lintRelease`; these artifacts are validation-only. With a separately supplied Samsung SDK, also run `:wear:assembleDebug :wear:testDebugUnitTest :wear:lintDebug`. Native/public repository gates and `git diff --check` are required. Final execution results and CI status are recorded in the pull request.

`wear-evidence` compiles the real Wear database and passive clock source in public CI without proprietary SDK material. It checks delivery/history separation, retention, v1 migration and delayed measurement clocks. The complete Wear build requires the separately supplied Samsung SDK.

Physical phone/watch checks remain necessary before a signed release: delayed sensor delivery, phone reconnect and ACK history, permission revocation, Calendar selection changes and in-place upgrade with mature real data. Performance, broader provider reconciliation, recovery features and new feature work remain later audit milestones.
