# James OS handover

## Project

- Repository: https://github.com/traynor1987/JamesOS
- Branch and release source: `main` only
- Android app: `app`
- Wear OS companion: `wear`
- Validation workflow: https://github.com/traynor1987/JamesOS/actions/workflows/android.yml
- Signed release workflow: https://github.com/traynor1987/JamesOS/actions/workflows/release.yml
- Releases: https://github.com/traynor1987/JamesOS/releases

## Current release

- Phone: 0.3.213
- Wear: 0.2.2
- James Calibration Engine: 1.0.3
- Release: https://github.com/traynor1987/JamesOS/releases/tag/v0.3.213

## Product and architecture audit — 2026-09-15

The source-first, documentation-only audit is in [JAMES_OS_PRODUCT_AUDIT.md](JAMES_OS_PRODUCT_AUDIT.md). It classifies current user journeys rather than relying on prior handover claims, and records the proposed 1.0 boundary, scope rejects and prioritised roadmap.

The audit's original highest-priority findings are now remediated and tracked with evidence in [JAMES_OS_PRODUCT_AUDIT_REMEDIATION.md](JAMES_OS_PRODUCT_AUDIT_REMEDIATION.md): Health Connect capability/permission parity; non-destructive Visit corrections; incremental legacy Visit reconstruction; Visit-linked semantic periods; and James-Day Timeline filtering. The audit remains a historical snapshot; the remediation matrix and the current code are authoritative for present status. Continue to treat data correctness, provenance, bounded processing and coherent day semantics as prerequisites before adding providers, continuous Wear sampling, AI, prediction or another dashboard.

## Today 2.0 / Compact Today

Compact Today is the default Today presentation. Settings → Appearance → Today Layout exposes a live `Compact Today` switch; disabling it selects the preserved original `Classic Today` dashboard. The choice is a device-local DataStore presentation preference (`compact-today`, default `true`) and survives process recreation, reboot and app updates. It has no database, health, sync, James Day, algorithm or calibration semantics.

Both layouts consume the same immutable `TodayPrepared` state. The shared preparation stage runs on `Dispatchers.Default` and resolves James Day, Body Battery/WHOOP overview, Live Energy, Sleepiness, Sustainability/Crash Risk, Time Pressure, Mental Wellbeing, current Context, Life Balance, nutrition, calibrated Wear signals and Timeline once per authoritative state/clock update. Compact and Classic never calculate separate scores, and only the selected presentation is composed.

Compact Today has a stable information hierarchy:

1. **Right Now** — equal 2×2 Body Battery, Live Energy, Sleepiness and Mental Reserve cards, one optional deterministic contradiction summary, a compact Energy Sustainability row and elevated Crash Risk only when meaningful.
2. **Mental & Context** — compact James Stress, Anxiety, longitudinal Low-Mood and Time Pressure tiles; current Context appears only when active, Difficult retains its direct action, and Life Balance shows trend plus at most one helping/hurting factor.
3. **Today So Far** — compact nutrition, activity, personal/obligation time, places, last sleep/Recovery and routines rows. Missing values remain unknown/not logged rather than zero. Frequent actions live in one expandable Quick Actions surface.
4. **Timeline** — a lazy, bounded set of meaningful James-Day events followed by the existing full Timeline destination. Passive samples, Energy recalculations and one-point technical noise are excluded from this summary but remain stored and reachable elsewhere.

Presentation priority is deterministic and stable. Crash Risk ≥50 and Time Pressure ≥80 can be promoted, Difficult Context is promoted within its current-context row, and no more than two secondary promotion candidates are selected. Calm states stay compact. Major section order never changes.

The outer Compact Today surface is a `LazyColumn`; it does not invisibly compose Classic Today. Four primary cards use fixed equal heights and two responsive rows, avoiding Fold outer-display clipping while constraining content to 840dp on wide/inner displays. Cards and rows are whole accessible tap targets with score/state text and semantic descriptions; state is not conveyed by colour alone.

The earlier Health Monitor → Right Now hitch remains fixed by off-main preparation and reusable Classic dashboard slots. Compact Today additionally bounds Timeline to 12 meaningful events and prepares nutrition/activity/time/place/sleep/routine strings before composition. Scrolling only renders stable UI state: it never runs Body Battery, Sleepiness, Mental Wellbeing, Context or Calibration analysis. Classic Today remains fully functional and continues to receive normal shared-state updates, but its presentation is not composed while Compact is active.

Tests cover Compact-default persistence and explicit Classic persistence, contradictory-state summaries, deterministic/max-two promotion rules, quiet states, disabled metrics and the primary metric Compose presentation. Existing Today performance, source, algorithm and Calibration tests continue to protect the shared pipeline. Known limitation: section reordering/show-hide customisation is deliberately deferred; only the required Compact/Classic preference exists.

## James Sleepiness / Sleep Pressure

Sleepiness Algorithm v1.0.0 with Calibration v1.0.0 is a first-class experimental 0–100 metric where 0 means no meaningful Sleepiness and 100 means extreme Sleepiness/struggling to stay awake. It estimates current propensity or drive to sleep; it does not claim to measure adenosine, hypersomnolence or impairment and is not a diagnosis.

Sleepiness remains explicitly separate from Body Battery (underlying physical capacity), Live Energy (current energetic/alert state) and Mental Reserve (available mental capacity). High Energy with high Sleepiness, or low Body Battery with low Sleepiness, are valid states. Body Battery is not inverted or tuned to make the scores agree.

The deterministic v1 model uses the authoritative accepted-main-sleep James Day and actual wake timestamp. It combines a bounded nonlinear wake-pressure curve, current sleep shortfall against James's rolling main-sleep median when enough history exists (otherwise a conservative 7-hour learning reference), a capped exponentially decaying five-sleep shortfall contribution, a small conservative local-time circadian component and bounded WHOOP sleep-performance context where present. It makes no conventional bedtime/wake-time assumption and never resets at midnight.

Accepted naps temporarily reduce expressed Sleepiness with bounded decay but do not reset James Day or erase recent shortfall. Supported structured caffeine records may temporarily reduce expressed Sleepiness using a conservative bounded decay; underlying pressure remains unchanged. Missing caffeine is unknown, not zero. Food, hydration, activity, Stress and context never provide a fixed Sleepiness bonus or prove James is not sleepy.

WHOOP and Health Connect sleep records use the existing source authority and deduplication rules; WHOOP wins equivalent duplicate episodes. A detected but unscored new WHOOP main sleep retains the prior authoritative James Day and lowers confidence with `NEW MAIN SLEEP PROCESSING` rather than inventing a reset. Corrections recalculate from source timestamps without rewriting old snapshots.

Today shows Sleepiness as a compact tile inside Right Now. The same off-main-thread immutable `TodayPrepared` state feeds Health Monitor and Right Now, avoiding the former cross-card lag. The tile opens Algorithm Details, whose diagnostics expose underlying/expressed Sleepiness, wake time, time awake, sleep/baseline source, recent shortfall, restorative sleep, nap, circadian and caffeine components, source freshness, confidence and exact algorithm/calibration provenance. Right Now persists bounded two-hour/meaningful-change snapshots rather than minute-level history.

The existing 15-minute Health WorkManager job now also reconstructs Right Now from persisted authoritative inputs even when no Health Connect permission is available. Foreground state uses the existing lifecycle-aware minute clock. No new sensor loop, foreground service, aggressive WHOOP polling or minute-by-minute background worker was added; elapsed time is calculated directly after process recreation/reboot.

Sleepiness is registered in James Calibration Engine 1.0.3 with structured comparative feedback (`PREDICTION TOO HIGH`, `ABOUT RIGHT`, `PREDICTION TOO LOW`) and direct ordinal ground truth (`NONE` through `STRUGGLING TO STAY AWAKE`). Calibration snapshots retain prediction, Sleepiness algorithm/calibration/set identity, James Day, main sleep/wake, recent history, underlying pressure, Body Battery, Live Energy, Mental Reserve, Stress, activity/context, nutrition/hydration/caffeine and source provenance where available. One event never changes production. Candidate generation, holdout back-test, James approval, atomic activation, persistence and rollback use the existing audited Calibration Engine unchanged in authority.

Safe Sleepiness schema parameters cover output bias, late-wake threshold/acceleration, short-sleep sensitivity, recent-shortfall weight, nap magnitude/decay, circadian amplitude/phase and temporary caffeine magnitude/decay. All are finite and bounded. Automatic v1 candidates conservatively tune only the identifiable output bias; unsupported contextual parameters remain unchanged until evidence and engine support justify them. Parameter-only activation needs no APK.

Live Energy v1.2.0 consumes Sleepiness as a small bounded alertness context without becoming `100 - Sleepiness`. Energy Sustainability v1.1.0 consumes Sleepiness once and removes the previous direct sleep-duration/extended-wake charges to avoid double counting. Crash Risk v1.1.0 inherits that reconciled evidence through Sustainability. Mental Reserve v1.6.0 adds at most two points of Sleepiness cost and halves it when low Body Battery already carries overlapping evidence. Existing active output-bias calibrations remain compatible and immutable; no James-specific calibration was changed or activated.

No Room schema change was needed. Sleepiness current/history/provenance use the existing versioned `metadata` and `personalRecords` paths, so current backup/import, stable IDs and migration 2→3 preservation continue to apply. Wear remains 0.2.4 and Calibration Lab is phone-only.

## Today scrolling performance

The Health Monitor → Right Now hitch was traced to two concrete implementation issues: Today still prepared Timeline, James Day, Live Energy, Context Load and Life Balance synchronously during composition, and every lazy-grid position had been assigned a unique content type, disabling Compose reuse.

Today now prepares one immutable `TodayPrepared` model on `Dispatchers.Default`, including Health Monitor, Right Now, James Day, nutrition, Timeline, Context Load, Life Balance and Wear grouping. Cards use stable semantic keys (`health-monitor`, `right-now`, etc.) and a reusable dashboard content type. Conditional cards therefore cannot move remembered state, while normal lazy-slot reuse remains enabled. Score semantics and visible content are unchanged.

## Calibration architecture

James remains ground truth. Raw source records remain immutable. Production scores remain deterministic and bounded. Calibration observations measure prediction agreement; they never directly rewrite source records or historical score records.

The central implementation is `uk.co.james.calibration.JamesCalibrationEngine`.

- `CalibrationEvent`: immutable prediction/feedback observation with algorithm version, calibration version, calibration-set ID, James Day, input snapshot schema, evidence confidence/source and optional note.
- `CalibrationInputSnapshot`: schema `calibration-input-v2`; records only available HR/HRV/RHR/EDA, Recovery, Strain, sleep/debt/wake time, source-specific provenance, Body Battery, Mental Reserve, Anxiety, Low-Mood, Live Energy, Time Pressure, Life Balance, activity/context, personal time and James-Day nutrition/hydration/caffeine context.
- `CalibrationRegistration`: declares support, feedback target, minimum evidence, automatic-candidate capability and safe parameter definitions.
- `CalibrationProfile`: immutable parameter-set identity/hash, complete parameter set, schema/algorithm compatibility, engine/creator provenance, version, activation history and active flag. Legacy v1 partial profiles remain readable after their stored hash is verified and omitted unchanged values are filled from defaults.
- `CalibrationCandidate`: DRAFT/TESTED/APPROVED/REJECTED/ACTIVE/ROLLED_BACK/STALE lifecycle. Candidate identity binds the full evidence dataset, base calibration-set ID, dependency calibration versions, algorithm version and Calibration Engine version. Imported or Nova-labelled proposals receive no bypass.
- `CalibrationBacktest`: stable candidate and evidence-dataset identity, base/dependency versions, train/validation counts, train and validation MAE, current/candidate bias, improvement, score-range/context regressions and replay method.
- `AlgorithmRunner`: deterministic snapshot + parameter execution contract. The initial runner replays the stored base prediction and a bounded output correction.
- Calibration Engine candidate generation deliberately tunes one identifiable bounded output-bias parameter by at most four points per candidate. Body Battery also exposes its existing 1.65/hour awake-drain coefficient as explicit compatible calibration data without changing the default output.
- Default parameter sets reproduce current behaviour. No personal adjustment exists until James explicitly activates a tested candidate.
- Activation, rollback and restore-default are repository-owned atomic Room transactions. They re-read and validate the candidate, evidence dataset, back-test, base calibration and dependency versions inside the transaction. Startup creates explicit default parameter profiles without changing scores and repairs duplicate/invalid active pointers. `stateInputs` always includes long-lived `CalibrationProfile` rows, so an active calibration cannot disappear when it ages beyond the bounded health-history window. No app restart or APK is required.
- Calibration feedback uses a stable submission ID, so double taps/retries cannot duplicate an observation. New, edited, ignored or deleted evidence marks dependent candidates `STALE`; stale activation is blocked.
- Analysis status is persisted per algorithm. Interrupted work is recovered as a retryable failure, while candidate and back-test rows commit together only if their analysis snapshot is still current.
- Incompatible, non-finite, wrong-schema, out-of-range, missing-required or hash-mismatched parameter sets fall back to the compatible built-in default. Rollback selects only a compatible, validated previous set.
- Editing, ignoring or deleting evidence atomically rejects pending/tested candidates so stale analysis cannot remain eligible. Ignored evidence remains visible and can be included again.
- Candidate generation requires coverage in more than one score band, is deterministic for a fixed dataset/clock, stays within a four-point correction step, evaluates a holdout, and reports meaningful context regressions.
- The explicit acyclic dependency graph documents upstream/downstream score relationships; derived scores remain supporting context and are never treated as James ground truth.
- Historical outputs retain their original algorithm/calibration fields. Body Battery persistence now retains both base and final score to prevent a personal output correction feeding back into the next calculation.

## Statistics and safety

Implemented offline, deterministic analysis:

- count, mean error/bias, MAE, median absolute error and bounded recency-weighted MAE
- prediction-range coverage (0–19, 20–49, 50–79, 80–100)
- context slices including short sleep, Recovery bands, context type, meal, hydration and caffeine
- quality states: NOT ENOUGH DATA, EARLY, DEVELOPING, GOOD, STRONG
- deterministic 80/20 train/validation split
- per-range regression guards
- minimum per-algorithm evidence thresholds and small-change preference
- future-dated, ignored, invalid, incompatible, NaN and out-of-bound data rejection

A single observation never creates or activates a candidate. Automatic activation is off. James approval is required. Nova is limited to future structured proposal/research/explanation work and cannot control production scoring.

## Supported calibration targets

- Body Battery — perceived remaining physical capacity; sleepiness is secondary diagnostic feedback
- Live Energy — current energetic/alert state
- Mental Reserve — available mental capacity, distinct from Live Energy
- Anxiety Load — current reported anxiety-associated experience; non-diagnostic
- James Stress — subjective stress is calibration evidence but remains distinct from physiology
- Time Pressure — perceived shortage of usable time for James
- Context Load — current mental demand
- Life Balance — retrospective life/personal-time balance
- Low-Mood Load — periodic longitudinal mood/life pattern; non-diagnostic
- Energy Sustainability and Crash Risk are registered for future suitable evidence
- Legacy Rut is explicitly not calibratable

## UI

- Settings → Algorithms → Calibration Lab
- Algorithm detail → Personal calibration → Calibrate / Open Lab
- Calibration Lab overview shows evidence, quality, error and candidate availability.
- Per-algorithm detail shows current algorithm/calibration/engine versions, current prediction, quick structured feedback, optional notes, Body Battery capacity/sleepiness fields, accuracy, coverage, context slices, safe parameters, candidates, activation, rollback, restore default and observation history.
- Observations can be edited, ignored or deleted. These actions never delete underlying health, nutrition, context or sensor data.
- Calibration does not add large controls to Today or Wear OS.

## Existing-check-in bootstrap

Only structured Energy check-ins with a captured Live Energy prediction are bootstrapped. They are linked with `evidenceSource=HISTORICAL_CHECK_IN` and `sourceEventId`, and duplicate links are skipped. Chat, Nova text, notes and vague Timeline events are never inferred as ground truth.

## Room and backup

Database version 3 adds nullable indexed record metadata:

- `algorithmId + timestamp`
- `algorithmId + calibrationVersion + timestamp`
- `candidateStatus + timestamp`
- `jamesDayId + algorithmId + timestamp`

`MIGRATION_2_3` uses additive columns/indexes only. It does not rebuild or delete the records table. Calibration records use the existing `personalRecords` backup/import path, retaining stable IDs and idempotent restore behaviour. Imported calibration rows are treated as untrusted structured data: algorithm/schema compatibility, finite bounded parameters, hashes and back-test identity are validated. An imported active profile cannot replace a newer local active calibration implicitly.

The Section 62–159 continuation audit is documented in `JAMES_OS_CALIBRATION_POST_IMPLEMENTATION_AUDIT.md`. It added real Room activation/restart/rollback and stale-candidate integration tests; no real James candidate was activated during the audit.

## Privacy

Calibration stays local-first. Raw calibration observations are not logged, uploaded to GitHub, bundled in APKs or automatically sent to Nova/AI. Existing release artifacts contain code only.

## Release procedure

1. Push the completed change set to `main`.
2. Wait for both validation jobs (unit/lint/debug/release/Wear and instrumentation) to pass.
3. Run `Publish James release` once with the next phone version and unchanged Wear version unless Wear code changed.
4. Verify the signed APK/certificate, release tag/assets and update metadata.
5. Confirm the updater policy recognises the published semantic version.


## Health Connect nutrition diagnosis and sync

- Root causes fixed on 2026-09-13: the user-facing **Sync now** and foreground refresh paths called only the generic Health Connect reader. They could report success without running the dedicated `NutritionHealthSource`; meanwhile the legacy generic reader was separately gated by the default-off Live Energy nutrition preference. This made valid Health Connect food appear as **Not logged** until a later background run, and could double-count a meal when both paths ran. A second presentation hand-off was then found with real MyNetDiary records: the Today aggregation re-filtered persisted Health Connect rows by raw timestamp rather than their ingestion-time James Day owner. `NutritionEvent`/ `HydrationEvent` now use their resolved `jamesDayId` as the authoritative daily owner, with timestamp filtering retained only for legacy rows.
- Nutrition and hydration now have one idempotent Health Connect ingestion path: `NutritionHealthSource`. It reads `NutritionRecord` and `HydrationRecord` when their individual read permissions are granted, preserves nullable nutrients, uses Health Connect record ID stable keys, and retains actual data-origin provenance. The generic reader no longer imports either type.
- `Connections → Health Connect → Sync now` and the bounded foreground refresh both run nutrition ingestion and refresh Today without a restart. The existing 15-minute worker continues to ingest it without aggressive polling. Nutrition permission is available in Connections independently of the optional Live Energy nutrition-context preference.
- The Nutrition connection card reports permission, last query, records returned and accepted, plus actual provider labels. `NutritionEvent` and legacy `Nutrition` rows both feed the shared James-Day nutrition state for Compact Today, Classic Today, My Day, Timeline and conservative Live Energy context.
- Food energy uses the typed Health Connect `NutritionRecord.energy.inKilocalories` API, with typed Mass/Volume unit APIs for nutrients and hydration; it remains separate from total calories burned. A reflection-based reader could silently persist records with every nutrition value null even when Health Connect had returned valid records—the remaining real-device defect found after 0.3.206. Valid energy-only meals remain valid; absent macros/caffeine remain unknown. Hydration and caffeine remain separate and are never inferred from targets or meal names.
- Connections now also reports the current authoritative James Day’s meal count and energy (or “energy unavailable”), separating “records received” from mapped daily nutrition during diagnosis. Re-syncing overwrites prior rows by their stable Health Connect IDs, so no app-data reset is required after the typed-mapper fix.
- `READ_HYDRATION` is declared alongside `READ_NUTRITION`. Records are owned by the accepted-main-sleep James Day using Instant/zone-consistent day windows, not midnight.
- Added regression coverage for multiple partial meals totalling 1,240 kcal, with missing macros kept null rather than zero.


## Shift Tracker → James OS work context (Part 1)

Part 1 is implemented as a local-first read-only receiver foundation. The v0.3.208 recovery release contained none of this code. The previous attempt failed because minified WorkContext Kotlin had a missing closing parenthesis (`Expecting ')'` at line 17 column 1686), not because of Room or Android platform limits.

Canonical WorkEvent records live in the existing Room-backed personalRecords store with stable external IDs, revisions, deletion tombstones, source, receipt time and James Day ID. Migration 3→4 only adds indexes. Work sessions/current state are derived from authoritative timestamps; they do not split at midnight and stale active shifts become reconciliation-required after 18 hours.

The exported ShiftTrackerWorkReceiver only accepts the versioned v1 payload behind Android signature permission uk.co.james.permission.SHIFT_TRACKER_WORK_CONTEXT. Package strings are not authentication. Payloads are size/ID/timestamp/revision/type validated. Reconciliation is bounded to 200 events/page and 40 initial-history days. Until Shift Tracker Part 2 exists, Connections truthfully says receiver ready / sender waiting and creates no fake shifts.

Timeline, Compact/Classic Today, My Day and calibration snapshots receive factual work context. Work is not an automatic stress, obligation, difficult-context or energy penalty. Customer/order/location/payment data is forbidden. See SHIFT_TRACKER_JAMES_OS_CONTRACT.md for the Part 2 sender contract.

- Shift Tracker Part 1 Room migration uses explicit `MIGRATION_3_4`; the emulator migration regression test registers the full 1→2→3→4 path so existing data is not destructively reset.


## Public repository migration

- **Private historical archive:** `traynor1987/Jamssos` — remains private. Never import its Git history.
- **Public active source and future releases:** `traynor1987/JamesOS`.
- JamesOS starts with a new clean root commit and receives file content only; it has no Jamssos commits, tags, branches or ancestry.
- The Android application ID remains `uk.co.james.personal`; data/database compatibility and the existing signing identity remain required for updates.
- The public release workflow uses `james-release` environment secrets `JAMES_SIGNING_KEYSTORE_BASE64` and `JAMES_SIGNING_PASSWORD`, reconstructing the key only in runner temporary storage. It verifies the known certificate fingerprint both before and after signing and fails closed.
- Samsung Health Sensor support remains available through the separately obtained, ignored AAR described in `wear/README.md`. Trusted full Wear release builds may use the separate `SAMSUNG_HEALTH_SENSOR_AAR_BASE64` environment secret. Public/fork PRs receive neither it nor any signing secret.
- The updater default now targets `traynor1987/JamesOS`. Existing installed builds that target the private archive require one final same-package, same-signer bridge APK from Jamssos before public-only releases can update them.
- Public validation has no `pull_request_target`, no secrets and least-privilege `contents: read`.
- **Never import old Jamssos Git history into JamesOS. Never commit release signing key material. Never commit the Samsung standalone AAR unless explicit redistribution rights are established.**


## Deliberate public signing generation (0.3.208 onward)

- JamesOS deliberately begins a new permanent Android signing generation at phone **0.3.208** and Wear **0.2.2**. It is not compatible with the private Jamssos signer used by 0.3.207 and earlier.
- The new permanent public certificate SHA-256 is `176980d12255938bf049b274992a696978417a052b9492cf87a5048e0496f6f2`. It is committed in `signing/certificate.sha256`; the release workflow verifies the protected key before build and both APK signers after build.
- The protected `james-release` environment is authoritative for the matching keystore and password. Never regenerate or commit that private material casually.
- Migration from legacy builds is **export → uninstall → install → restore**. Wear legacy builds may need manual uninstall/reinstall.
- Existing Import Centre backup is the migration format: `JamesAndroid` JSON schema 1 exports all persisted stores—settings, event templates, logged events, daily notes, milestones, metadata, personal records, and preserved imported archives. This includes James-only manual records, calibration/learned calibration records, context definitions/corrections, James Day metadata/history and local nutrition records when stored locally. WHOOP, Health Connect, Samsung and other provider-source measurements can be resynced after restoring.
- Import validates every required store and row, uses additive merge (existing conflicts retained), preserves the original import, and writes a pre-import snapshot. Export the file to a user-chosen location such as **Downloads**; app-private daily snapshots are deleted by Android uninstall and are not the migration backup.

## Mature-database performance guardrail (2026-09-15)

The mature-import lag was a real whole-table reactive-state problem, not an Import Centre problem. The former root DAO `observe()` selected every `records` row and the root Compose tree collected and passed that complete history to every route. One write consequently re-emitted all history and caused repeated filtering, sorting, grouping and `StoredRecord.raw()/data()` JSON parsing in Today, Timeline, wellbeing, calibration, nutrition and context consumers.

Implemented guardrails:

- The UI no longer collects the whole-table DAO Flow. `JamesRepository.records` is a bounded 40-day scoring/state input flow; full `dao.all()` remains suspend-only for explicit backup/import transactions.
- `JamesViewModel.screenRecords` is route/date scoped. Today uses its bounded 40-day semantic scoring window; Timeline observes only its selected calendar-day interval plus durable configuration; Insights uses eight days and Me uses 90 days. Hidden routes no longer receive ongoing full-history emissions.
- `StoredRecord` now lazily prepares immutable `raw` and `data` JSON exactly once per emitted Room row. No global cache is used, so records cannot become stale across Room snapshots.
- Existing targeted indexes (`timestamp`, `kind+timestamp`, `source+kind+timestamp`, `store+timestamp`, James Day and calibration indexes) support the scoped query patterns; no speculative index was added.
- Existing persistence equality/fingerprint guards for Body Battery, Right Now and wellbeing remain required. Do not reintroduce timestamp-only derived writes or a broad reactive `SELECT *`.
- Empty history now stays `Life Balance: LEARNING/UNKNOWN` without claiming **Hurting: Low personal time**. The warning requires recorded context evidence. It does not alter populated-data scoring.

Regression coverage includes parse-once identity and empty-history Life Balance assertions. The public static gates (`public_repo_gate.py`, `audit_native.py`) passed locally. GitHub Actions runs **#17** (`34947687540`) and the post-merge `main` run **#19** (`34952660311`) both passed the complete public unit/lint/phone-build validation and targeted emulator instrumentation on 2026-09-15. Run #19 validates merged `main` commit `60a18e4776cf70b2388a1a9eecb36968c28cbb7e`. This Work environment has no Gradle binary, wrapper or Android SDK, so GitHub Actions remains the authoritative Android execution evidence.

The prior regular validation failure was repository workflow setup, before tests: both jobs failed at the pinned `android-actions/setup-android` step. The successful signed release used the runner's existing `$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager`; public validation now uses that same explicit license/platform setup. Tests remain enabled and unchanged.

## Places/Visits real-device correction (post 0.3.211)

0.3.211 real-device acceptance exposed two genuine gaps: its Places UI showed only new `PlaceVisit` rows, so pre-existing bounded `ContextPeriod` evidence was invisible as recent visits; and map centring relied on its first marker rather than explicitly framing the latest device anchor. Saved `Place` definitions, legacy context evidence and new Visit rows are separate datasets and must stay separate.

- `reconstructLegacyVisits()` performs an idempotent, specific-kind compatibility pass for bounded legacy `ContextPeriod`/location evidence. It creates deterministic `legacy-visit:*` Visit IDs only when start, end and at least five minutes of duration already exist; raw legacy evidence is retained. Reconstructed rows carry `LEGACY_RECONSTRUCTION` provenance and do not fabricate coordinates when old evidence had none.
- The active `LocationAnchor` is now explicit `CONFIRMING` then `ACTIVE` after the first start plus five-minute stable sample. A service start immediately uses the latest valid device fix for the physical current-position anchor; that alone never creates a completed visit. Current coordinates remain visible when no Place matches.
- The map is bounded-range (Today, Yesterday, seven days), visit/pin based and intentionally has no breadcrumb/route line. It frames current location plus the selected visits, keeps known-place definitions visually distinct from actual visits, exposes a compact legend, and opens Visit details from visit pins. Do not reintroduce raw route rendering.
- Places and Map use the same scoped `PlaceVisit` truth. Current anchors and saved places are small scoped reads; no whole-history Flow or historical visit replay runs on a live GPS fix.

## Unified context and time ownership model

The prior UI mixed legacy `ContextPeriod` values (PERSONAL/NEUTRAL/OBLIGATION), activity counts, and Visit ownership, producing contradictory totals. The current authority is deliberately separated: **Visit = when/where; Context = situation; Activity = what James did; Time Ownership = who controlled the time; Interruption = what changed it; Life Balance = a cautious interpretation.**

- `OwnershipPeriod` is the explicit semantic segment model. It can live within a physical Visit, so a Home visit is never split into fake location visits merely because ownership/activity changes. Explicit James-confirmed segments win over the enclosing Visit for the affected interval; edge-based interval resolution prevents double-counting.
- `ownershipIntervals` / `ownershipSummary` are the sole ownership aggregation path. Compact Today and Life Balance consume it. Context, place category and logged/chosen activities do not create Personal minutes. `UNKNOWN` remains first class.
- Life Balance is evidence-gated: fewer than two hours of confirmed classification is `LEARNING`, not “low personal time”. Activity count is retained only as secondary factual context. **UNKNOWN != ZERO; no evidence != negative evidence.**
- Compact Today labels the shared row **TIME OWNERSHIP** and honestly says “No time ownership confirmed yet” when appropriate. Its Quick Actions separate Set Context, Log Activity, Check-in and one-tap current Time Ownership (My time / Obligation / Constrained / Work).
- Definitions that must not regress: FREE != PERSONAL; HOME != PERSONAL; NOT WORKING != PERSONAL; ACTIVITY != OWNERSHIP; LOCATION != OWNERSHIP. Personal means James had deliberate, reasonably uninterrupted control; Constrained means apparently available time was not reliably his.

The immediate priority remains proving this on a mature synthetic Room dataset/device and keeping all semantic windows explicit. Do not start Shift Tracker Part 2, maps, timers, settings redesign, Wear sampling, stress calibration UI, Time Ownership, new wellbeing features or unrelated polish before that validation is complete.

### Ownership-ledger continuation (2026-09-15)

- The ownership ledger now also persists and aggregates `VisitInterruption` evidence. Interruptions are not a competing ownership clock: they record their own provenance and duration, split autonomous continuity, and therefore produce interruption count, interruption duration and longest uninterrupted Personal block without counting a minute twice.
- `INTERRUPTED` closes an explicitly current Personal segment, records a James-confirmed interruption and opens a committed segment. `RESUME MY TIME` closes both the interruption and its current segment, then records a new Personal segment linked to the interruption. This preserves the relationship rather than flattening it into a generic activity.
- Existing `ContextPeriod` values are backward-compatible historical situation records. New situation choices use contextual labels such as Home, Work and Resting; neither legacy `NEUTRAL` nor any Place category is ownership. New work must preserve this distinction.
- Rolling Life Balance history is anchored to the current James Day start (accepted main-sleep end), not local midnight. Export/import already serialises all `personalRecords`; regression coverage now explicitly round-trips ownership, context, activity and interruption records through the existing Import Centre schema without a second backup format.
- Timeline now emits concise linked `OwnershipPeriod` and `VisitInterruption` moments (for example, “Time ownership: Autonomous” and “Interrupted · Phone call”) rather than hiding the semantic period behind unrelated context rows. Visit detail edits preserve `ContextCorrection` provenance for changed place, context, activity and ownership fields; they do not rewrite the original GPS evidence.
- Places diagnostics now use the same scoped ownership ledger as Today/Life Balance, including current explicit ownership and bounded James-Day interruption/longest-block information. The location route observes only places, current anchor and the current bounded context/ownership/activity kinds; it must not be changed back to a full historical records observer.
- Legacy context labels are displayed as **legacy context** rather than being silently presented as ownership. New situation labels include Home, Work and Resting. This is compatibility presentation, not a destructive migration of historical records.
- GitHub Actions run **#33** (`34980179077`) validated the restored complete `main` tree at commit `a41c9d1634d2dadab13c3f078a72acf1a2e939be`: public unit tests, lint and phone compilation and targeted emulator instrumentation all passed on 2026-09-15. The source tree on that commit is the complete local implementation through `e716bd5`; do not reintroduce a partial-tree transport commit when updating GitHub.


## Settings organisation (2026-09-15)

Settings is a directory, not a second dashboard. The Settings home uses concise current-state summaries and routes to: **Display & Today**, **Connections & Data**, **Watch & Sensors**, **Places & Context**, **James OS & Calibration**, **Data, Backup & Import**, **App & Updates**, and **Advanced & Diagnostics**.

- Existing controls were moved, not recreated: Display owns theme/Compact Today; Connections remains the single authoritative provider, permission, nutrition, WHOOP and Shift Tracker receiver manager; Watch owns Wear companion, sensor choices, sync and Wear updates; Places links to the existing location/context page; James OS & Calibration contains algorithms, energy/time and wellbeing preference keys; backup/import opens the established Import Centre; App & Updates retains the existing signed updater; technical data-source status is under Advanced & Diagnostics.
- All existing DataStore/Room-backed control implementations and confirmation flows remain unchanged. The duplicate non-functional Shift Tracker placeholder was removed; its working receiver status and reconciliation action remain in Connections.
- Settings detail pages use the existing responsive adaptive 320dp card grid, so folded/narrow screens stay single-column while unfolded Fold layouts gain a comfortable second column rather than stretched controls. Back from every Settings detail route returns to Settings.
- The Settings landing page observes only theme/Today preferences plus Health Connect, WHOOP configuration and Wear status. It does not collect records or reintroduce the broad history Flow. Connections and Diagnostics retain their existing bounded state when explicitly opened.
- SettingsNavigationTest protects category coverage, authoritative Connections/Import Centre ownership and Settings-detail back routing. Settings search is deliberately deferred: the bounded directory and synonym-bearing navigation contract are enough now; no separate search subsystem was added.
- PR #2 run #21 (34956578949) passed public unit/lint/phone compilation and emulator instrumentation before merge.

## Product audit remediation (2026-09-15)

`JAMES_OS_PRODUCT_AUDIT.md` remains the historical source-first audit snapshot. Its implementation-status companion is **`JAMES_OS_PRODUCT_AUDIT_REMEDIATION.md`**; read both before changing semantic/day architecture.

- Health Connect capability declarations, runtime requests and source reads now share `HealthCapabilities`; HRV, oxygen saturation and respiratory-rate parity is test-protected.
- Visit correction is non-destructive: merge/split preserve immutable raw Visit evidence snapshots and reversible correction records, and superseded rows are excluded only from the current interpreted Visit presentation.
- Legacy Visit reconstruction is versioned and watermark-based. First run or an import reconciliation can inspect legacy evidence; ordinary startup uses only newer evidence. Do not restore a full historical scan on every launch.
- New Context and Activity actions write their own canonical semantic records and attach the current anchor/context/James Day where available. Context means situation; Activity means what James did; neither writes Personal ownership. The primary Nova tab was removed because no provider is configured.
- Ownership coverage is explicit alongside the canonical ownership ledger. Life Balance and Today use that shared ledger and must retain `UNKNOWN != zero` / `no evidence != low Personal time` behaviour.
- Current deferred work remains: adaptive Wear detailed sampling, Calendar/weather/screen time, Time Ownership trends/correlations, predictive alerts, Nova AI and Shift Tracker sender Part 2. None are authorised as a substitute for validating this foundation on a populated device.

### Narrative and freshness continuation

- Timeline is a bounded **James Day** presentation. A completed Visit now narrates linked `ContextPeriod`, `LifeFactActivity`, `OwnershipPeriod` and `VisitInterruption` evidence inside its visit card, while the stored records remain independently editable and exported. Do not delete raw semantic rows merely to keep Timeline quiet.
- Provider connection and evidence freshness are distinct. Connections/Data status now explicitly says `NOT SYNCED`, `FRESH`, `AGING` or `STALE` for the latest successful Health Connect/WHOOP sync; a connected provider is not automatically current evidence. Metric-level physiology freshness remains the stronger algorithm input policy.
## Product audit remediation continuation (2026-09-15)

The P0/P1/P2 remediation now uses a stricter semantic contract. New Context, Activity, OwnershipPeriod and VisitInterruption records attach to the live `LocationAnchor`; a completed `PlaceVisit` retains that `anchorId`, so Timeline joins a Visit through evidence rather than merely coincident timestamps. Unlinked legacy rows retain a bounded compatibility join and are never rewritten as new subjective truth.

Visit merge/split is non-destructive: snapshots and corrections remain backed up, and the Visit editor can revert the latest merge/split correction by restoring its evidence rather than deleting rows. Import validation now checks semantic interval/enumeration shapes; populated semantic export → clean import → repeat import is regression-tested for idempotence.

Timeline now calls one pure James-Day filter with an overnight test. Provider display selection now calls `SourcePolicy.rank`, keeping WHOOP/Health Connect/Wear fallback order consistent while raw provider records remain preserved. Do not reintroduce a full-history reactive query, startup-wide legacy reconstruction, or a location breadcrumb trail.
