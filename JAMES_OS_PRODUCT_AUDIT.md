# James OS product, feature and architecture audit

**Audit date:** 2026-09-15  
**Repository audited:** `traynor1987/JamesOS`, `main`  
**Method:** source-first trace of Android/Wear routes, ViewModels, Room/backup, providers, services/workers, workflows and tests. This is an audit only; no product behaviour was changed.

## Executive summary

James OS is a substantial, local-first personal state application, not a shell. Its strongest completed areas are the bounded performance pipeline, deterministic health/wellbeing presentation, calibration controls, backup/import, release integrity, and a deliberately battery-conscious Wear companion. Its weakest area is **semantic coherence**: there are now good foundations for Places, Visits and Time Ownership, but the user journey still consists of several partially linked record systems rather than one reliable account of a day.

The product is **pre-1.0, with strong subsystems but not yet a coherent whole**. The next work should make existing data truthful, connected, editable and explainable before adding sensors, AI, predictions or another provider.

### Highest-priority findings

1. **P1 correctness — Health Connect declaration mismatch.** `HealthSource` reads HRV, oxygen saturation and respiratory rate, but `AndroidManifest.xml` does not declare the corresponding Health Connect read permissions. Those metrics cannot be reliably authorised as implemented.
2. **P1 correctness/provenance — visit merge deletes one Visit record.** `JamesViewModel.mergeVisitWithPrevious` writes a correction and then deletes the merged Visit. That is incompatible with the stated rule to preserve observed evidence and makes historical auditability fragile.
3. **P1 semantic coherence — Time Ownership is only partly unified.** The canonical `TimeOwnership` aggregator exists and Life Balance uses it, but Context, Activity, `TimeBlock`, legacy Personal/Neutral/Obligation records and visit editing are not a single linked semantic-period system. Quick Actions do not reliably create the model their labels promise.
4. **P1 performance/reliability — legacy visit reconstruction scans all legacy context/location rows at every application start.** It is idempotent, but has no completed-migration watermark or bounded incremental cursor. That is an avoidable mature-history startup cost.
5. **P1 James Day consistency — Timeline is queried by local calendar date.** Several newer summaries use James Day, while the Timeline route selects one `localDate`; this needs an explicit semantic decision and tests around sleep/new-day boundaries.

## Current product map

| Domain | What exists | Honest maturity |
|---|---|---|
| Today | Compact default and Classic presentation, prepared immutable state, health/context/time/nutrition/timeline cards | **Partial** — strong rendering/performance; some inputs remain semantically disconnected |
| Health and wellbeing | Body Battery, Live Energy, Sleepiness, Mental Reserve, Stress, Anxiety, Low Mood, Sustainability, Crash Risk, Time Pressure | **Partial** — deterministic, calibrated and explained; evidence/default/freshness consistency still needs audit-driven hardening |
| Calibration | Versioned profiles, evidence, candidates, back-tests, activation/rollback | **Complete for the current bounded calibration engine** |
| Health Connect | Historical/periodic read sync, source provenance and batching | **Broken/partial** — three code-read types lack manifest declarations |
| WHOOP | OAuth/proxy connection, Recovery, Sleep, Cycle/Strain, Workouts, HRV/RHR/SpO2/temp/respiration mapping | **Partial** — no live Stress Monitor API mapping and no WHOOP Age integration |
| Wear | Passive Health Services intake, user-triggered 45-second Samsung detailed check, phone sync, tile/complication | **Partial but intentionally conservative** — no autonomous 5-minute/30-second detailed sampling |
| Nutrition | Health Connect nutrition/hydration/caffeine ingest, James-Day UI/context | **Partial** — provider data works through supported record types; no rich meal workflow/correlation layer |
| Places and location | saved places, current matching, geofences, background service, diagnostics, map | **Foundation/partial** — suitable foundations; real-world acceptance exposed continuity and map/visit issues |
| Visits | 5-minute dwell, 10-minute exit grace, unknown default, legacy reconstruction, map pins, corrections | **Partial** — no robust dedicated detail/review workflow; merge provenance is unsafe; no pin clustering |
| Context / activity / ownership | taxonomies, manual records, ownership periods, interruptions/resume, aggregator | **Foundation only** — concepts exist but are not consistently joined to the same period |
| Life Balance | evidence-gated use of canonical ownership summary, activity as secondary context | **Partial** — much improved; coverage/unknown semantics and user-visible linkage remain incomplete |
| Timeline | chronological bounded moments including health, visits and ownership records | **Partial** — raw chronology, not yet a coherent joined day narrative |
| Insights | week view, wellbeing, RUT events, routines, journal reviews, basic health rows | **Partial** — dashboard of records, not evidence-backed cross-domain insight |
| Nova | visible tab, prompt box and export | **Placeholder** — no model/provider/data access |
| Routines / Journal / check-ins | local creation/storage, routines schedules/completions, journal/check-ins on relevant screens | **Partial** — useful simple tools; weak cross-feature linking and no reminder system |
| Settings / Connections / Import | organised directory, provider status, import/export/retained backups | **Complete for current scope** — no actual Settings search UI |
| Shift Tracker | signature-protected receiver and Contract v1 | **Foundation only** — receiver exists; sender Part 2/end-to-end upstream source does not |
| Updates/releases | signed GitHub releases, signer equality, checksum verification, Wear/phone updater | **Complete for current public signing generation** |

## Feature inventory and end-to-end classification

### Complete / meaningfully usable

- **Public release path:** main-driven signed releases, APK checksum and installed-signer equality validation; current generation begins Phone 0.3.208/Wear 0.2.2. Legacy signer compatibility is intentionally absent.
- **Import Centre / backups:** generic, additive JSON backup/import; retained daily/pre-import backups; size/schema guards. New place/visit/ownership records are included because they live in backed-up stores, though semantic validation is shallow.
- **Calibration Engine:** persisted immutable profiles, feedback, deterministic analysis, candidate lifecycle and rollback are real, not a mockup.
- **Performance architecture:** root state is bounded (`observeStateInputs`), route windows are scoped and prepared off-main. `all()` is reserved for backup/import/archive-style operations.
- **Compact Today preference:** default, persistent, responsive presentation switch; Classic uses the same prepared state rather than recalculating another model.
- **WHOOP core sync:** connection state, OAuth/proxy flow, stable external IDs, raw-plus-mapped records and periodic worker are implemented.
- **Basic routines:** create/edit/archive, weekday schedules, completions and streaks work locally.

### Partial / materially useful but incomplete

- **Today and wellbeing:** the scores and their contributors are real, but the surface still combines current state, history and priors in ways that need a uniform freshness/evidence policy. It is not a diagnosis system.
- **Health Connect:** code reads steps, sleep, exercise, HR, resting HR, distance, total calories and weight; nutrition/hydration are handled separately. HRV, SpO2 and respiration are also coded but blocked by missing manifest permissions.
- **WHOOP:** Recovery/Sleep/Strain/Workout and health-monitor fields map into provenance-preserving records. WHOOP live Stress is explicitly unavailable through the used API; no WHOOP Age support exists.
- **Nutrition:** structured Health Connect nutrition/hydration/caffeine is integrated into Today/James Day. Manual meal capture, confidence reconciliation and meaningful insight use remain limited.
- **Wear:** passive signals plus an on-demand Samsung detailed sensor window are real. Its automatic stress is derived at most once per 20 minutes from passively supplied readings; it is not high-frequency physiology measurement.
- **Places / map:** current coordinate is separate from place matching; map is pin-based and range-bounded. It does not draw a route trail. It still lacks grouped same-place pins and a dedicated selected-visit detail surface.
- **Visits:** dwell/exit hysteresis, active current anchor and legacy reconstruction exist. Recovery/merge/split/historical correction UX and preservation guarantees are not sufficiently mature.
- **Life Balance:** consumes the canonical ownership summary and evidence-gates conclusions below two classified hours. It no longer needs activity count as primary time evidence, but ownership coverage and temporal links are incomplete.
- **Timeline:** route-scoped and chronological, but presents related Context/Activity/Ownership/Interruption items as separate moments rather than a unified period.
- **Insights:** produces a useful weekly record review, not statistical insight or correlation analysis.
- **Connections / Settings:** clear directory and mostly one management surface per domain; connection state depends on stored sync state and can still need more freshness/recovery wording.

### Foundation only, hidden, placeholder or obsolete/duplicated

- **Time Ownership foundation:** `OwnershipPeriod`, explicit/manual provenance, interruptions, resume IDs and a no-double-count aggregator exist. Missing: a complete period editor, visit/activity/context links, unknown-period review and trusted coverage model.
- **Shift Tracker:** receiver Contract v1 only. Without sender Part 2 it is not an end-to-end work-context product.
- **Nova:** a main-tab placeholder that always reports no AI provider; it should not be treated as a delivered AI feature.
- **Settings search:** category keywords/synonyms exist in navigation data, but no search affordance is exposed.
- **Legacy RUT:** routes, RUT Insights, event history, recovery space and day-review calendar remain live. They preserve history and are not dead code, but overlap modern Timeline/Insights/Life Balance vocabulary and should be explicitly scoped as legacy/history rather than competing current truth.
- **Activity modelling:** `LifeFactActivity`, generic `TimeBlock`, provider exercise and chosen activities overlap. The current Log Activity quick action writes a generic time block, not a reliably linked Activity/Visit/Ownership segment.
- **Context taxonomy:** current situation types are sensible, but legacy `PERSONAL`, `OBLIGATION` and `NEUTRAL` values remain alongside the ownership model. They are labelled legacy in places, not fully migrated/rationalised.

## Architecture and data model

### What is sound

- Room has practical indexes for time, kind, source/external ID and James Day. Scoped DAO flows and parse-once `StoredRecord` JSON are a real improvement over the old whole-table flow.
- Provider records retain raw/external provenance before mapped health records. `SourcePolicy` declares per-metric preference for several important duplicate classes.
- Backup/import works through a single system; no parallel backup architecture has been introduced.
- Ownership aggregation splits competing ownership edges and selects one winner per instant. Context/Activity can overlap ownership, but ownership totals cannot double-count the same minute.

### Architecture risks and debt

1. **Generic JSON record bag remains the dominant persistence model.** It made additive delivery fast, but weakly validates Visit/Place/Ownership/Corrrection schemas and makes domain-specific migrations, joins and integrity constraints difficult.
2. **No fully authoritative semantic-period relation.** A Visit is spatial; Context, Activity, `TimeBlock`, ownership and interruption records can be separate, partially linked records. The product needs one editable temporal relationship layer, not another dashboard.
3. **Legacy visit reconstruction is application-start global work.** It should become an idempotent versioned migration/worker with a completion watermark or cursor, preserving raw evidence.
4. **Visit merge deletes the merged Visit row.** Correction provenance is written, but the source Visit is removed; this should be changed before relying on historical edits.
5. **James Day is not universal.** Health/Today and newer aggregates use James Day; the Timeline screen filters `localDate` calendar dates. This makes cross-midnight narratives potentially disagree.
6. **Provider policy is incomplete.** `SourcePolicy` has priorities for a subset of metrics, but there is no single visible reconciliation explanation across WHOOP, Health Connect and Wear/Samsung.

## Evidence quality, freshness and defaults

James OS does better than most personal trackers at retaining source fields and showing wellbeing contributors. It should standardise every visible output around: **measured/provider-supplied/inferred/James-confirmed/migrated/default-or-prior/unknown**, plus timestamp/freshness where it changes the meaning.

- Wellbeing cards expose contributor freshness and confidence, a strong model to reuse.
- Current location diagnostics expose age, accuracy, tracking and visit state; map coordinates rightly remain independent of an Unknown place classification.
- WHOOP/Health workers persist source status, but a stale successful sync can still look like a connection unless the consuming surface shows freshness.
- Calibration defaults are explicit parameter profiles and do not claim they are personal calibration. Algorithm cold-start values still need a systematic audit: any UI number shown before adequate evidence must be labelled learning/prior rather than measured state and must not become historical evidence merely because it was rendered.
- `UNKNOWN` in ownership is a valid first-class answer. The current aggregate counts explicit unknown intervals, but it does **not** model all unobserved waking time as unknown coverage. Therefore “zero personal” must never be inferred from an absent interval.

## Cross-feature contradictions and misleading surfaces

| Risk | Evidence | Recommendation | Priority |
|---|---|---|---|
| Health types advertised in code but not requestable | HRV/SpO2/respiration reads without manifest permissions | Align declared permissions, requested permissions and UI capability state | P1 |
| Ownership is not the same model everywhere | legacy Context values, generic TimeBlock/Activity and ownership intervals coexist | complete one semantic-period mapping and retire competing totals | P1 |
| Timeline day can differ from James Day | Timeline route uses calendar `localDate` | define/display intended day semantics and test boundary cases | P1 |
| Historical Visit correction can erase evidence | merge deletes merged Visit | make edits non-destructive; use supersession/correction records | P1 |
| Map pin stack | one marker per completed Visit, no clustering/grouping | cluster repeated-place visits and provide a list/detail drill-down | P2 |
| “Set Context” has hidden meaning | quick action starts `RESTING` rather than selecting a situation | make it an honest context chooser; keep ownership separate | P1 |
| “Log Activity” is not the activity model | current action opens generic TimeBlock | route it through linked Activity semantics or relabel until then | P1 |
| Nova looks like a feature | visible tab always says provider not connected | hide behind an experimental/disabled entry or defer entirely | P2 |
| RUT and modern views overlap | legacy RUT routes, timeline/insights/life balance coexist | preserve data, but explicitly separate historical RUT from current source-of-truth surfaces | P2 |

## Background reliability and battery

| System | Actual behaviour | Risk / conclusion |
|---|---|---|
| Phone location | `START_STICKY` foreground service, balanced accuracy every five minutes, geofence/activity restore on boot | Reasonable low-power design, but Android battery optimisation/foreground-service policy can still delay or prevent it; clearly surface degraded permission/state |
| Visit detector | 5-minute dwell, 10-minute exit grace, rejects fixes >200m | avoids obvious jitter, but 200m same-place threshold is broad and sampling limits arrival/departure precision |
| Health Connect | periodic WorkManager minimum 15 minutes, battery-not-low | inexact by Android design; must be presented as periodic/historical, not live |
| WHOOP | periodic 30-minute network/battery-not-low worker | token/API failure state exists; recovery guidance and age/freshness messaging need audit-driven polish |
| Wear | passive platform delivery; automatic stress at most 20 minutes; detailed Samsung session only explicitly requested for ~45 seconds | intentionally battery-conscious; it does **not** provide the discussed 5-minute/30-second automatic physiology windows |
| Notifications | required location foreground and temporary Wear detailed-check notifications | no evidence of general reminder/notification spam; retain this restraint |

## Privacy and security

- Local Room storage and generic export are appropriate for highly private health/location data; `allowBackup=false` avoids uncontrolled Android cloud backup.
- The public repository contains source/tests only; tests use synthetic data. The existing public-repository gate should remain mandatory.
- Location is local, but osmdroid Mapnik tiles require network map tiles. The application should disclose that viewing maps necessarily shares tile requests/IP-level metadata with the tile service; it must not be presented as completely offline.
- WHOOP uses a project privacy proxy and local credentials. Its exact server retention/processing policy is outside this repository audit and should be documented independently before expanding AI/cloud features.
- Shift Tracker IPC is signature-protected, and release updater verifies HTTPS, checksum, package/version and installed signer. These are strong safeguards.

## Test coverage assessment

The project has meaningful pure Kotlin coverage for algorithms, James Day, performance, migration, storage preparation, calibration, WHOOP mapping, ownership and visit state. This is stronger than a superficial UI-only suite.

Missing or weak end-to-end coverage:

- manifest permission parity against every Health Connect type read;
- live OAuth/token-expiry/provider failure recovery;
- real Compose navigation and map marker/list consistency;
- process death/background restriction behaviour on real devices;
- non-destructive visit merge/split/correction integrity;
- backup round-trip validation for every new semantic record shape, not merely store inclusion;
- a complete Context/Activity/Ownership/Interruption linked-period acceptance test;
- explicit Timeline James Day versus calendar-day boundary test;
- actual Wear hardware/Samsung SDK and battery cadence validation;
- updater install/rollback/on-device release validation.

## Missing capabilities worth considering

| Capability | Problem it solves | Why existing code cannot solve it | Dependency | Value | Effort | Risk | Recommendation |
|---|---|---|---|---|---|---|---|
| Linked semantic periods and editor | makes a Visit tell one coherent story of context/activity/ownership/interruptions | records are separate and only partly linked | non-destructive Visit corrections; ownership model | Very High | Large | Medium | **Build before 1.0 (P1/P2)** |
| Unknown-period review | collects subjective truth without constant logging | no focused unresolved-ownership/unknown-visit queue | stable Visit/period model | High | Medium | Low | **Build before 1.0 (P2)** |
| Deterministic weekly review | turns data into a comprehensible weekly account | Insights is currently lists/cards, not joined evidence | reliable ownership coverage, timeline and freshness | High | Medium | Medium | **After core repairs; before/at 1.0 (P3)** |
| Shift Tracker sender Part 2 | supplies authoritative work boundaries | receiver alone receives nothing unless another app sends it | stable ownership/context mapping | High for work context | Medium | Medium | **Build before 1.0 only if Shift Tracker is actively used (P2)** |
| Time Ownership Insights | tests autonomy/fragmentation hypothesis honestly | no sufficient coverage/history or review loop yet | linked periods and multiple weeks of confirmed data | High | Medium | High inference risk | **Defer until evidence exists (P3)** |
| Calendar evidence | gives planned appointment/event context | no device calendar integration | permission/privacy design, semantic period model | Medium | Medium | Medium | **Defer (P3)** |
| Sleep/recovery trend explanation | explains changes using existing data | current Insights only gives latest values | freshness/provenance policy | Medium | Medium | Medium | **After 1.0 (P3)** |
| Phone digital context | may distinguish deliberate activity from passive waiting | Android usage is weak subjective evidence and privacy-sensitive | explicit purpose/consent, period model | Low–Medium | Large | High | **Defer/reject for now (P4)** |

### Specific candidate decisions

| Candidate | Decision | Reason |
|---|---|---|
| Adaptive Wear ~5-minute / 30-second sessions | **DEFER** | not implemented; passive/on-demand design is adequate until a data-value and battery study proves otherwise |
| Richer automatic context | **DEFER** | current context/ownership linkage is incomplete; more inference would multiply false certainty |
| Shift Tracker sender Part 2 | **BUILD conditionally** | high value only if James uses it; first stabilise the receiver’s semantic destination |
| Gig-work integration | **DEFER** | a specialised duplicate of work context until Shift Tracker path is proven |
| Calendar | **DEFER** | useful objective context, never ownership; privacy/permission cost needs a clear user decision |
| Weather/environment | **REJECT for now** | decoration unless a specific decision/explanation need is demonstrated |
| Automatic interruption detection | **REJECT as subjective classification** | sensors cannot know why James stopped; retain cheap manual correction |
| Richer subjective check-ins | **REJECT for now** | increases manual burden before existing check-ins and calibration are fully used |
| Time Ownership Insights | **DEFER then BUILD** | valuable hypothesis test, but only after trusted coverage and weeks of confirmed data |
| WHOOP Age | **DEFER** | proprietary composite duplicates existing source metrics and invites overinterpretation |
| anomaly detection | **DEFER** | needs stable baselines, confidence and restrained language |
| correlation explorer | **DEFER** | easy to create noise/causal stories without adequate data quality |
| predictive warnings | **REJECT for 1.0** | current scores are explanatory/experimental, not validated predictors |
| Nova summaries | **DEFER** | no provider, privacy/data-scope and hallucination design unresolved; deterministic summary comes first |
| notification intelligence | **REJECT for now** | James OS must not add interruption burden without demonstrated actionable value |

## What to merge, simplify, hide or keep

- **Merge:** all Personal/Obligation totals into the canonical `TimeOwnership` aggregation; link Context/Activity/Interruption to the same semantic period.
- **Simplify:** Quick Actions into distinct Context, Activity, Ownership, Interruption/Resume and Check-in actions. Do not let one silently create another dimension.
- **Hide/re-scope:** Nova until it has a provider and explicit privacy contract; legacy RUT should remain accessible as historical material but not compete as current insight truth.
- **Keep:** Compact Today/Classic preference, deterministic calibration, source provenance, bounded route flows, Import Centre, conservative Wear sensor policy and visit-pin—not breadcrumb—map design.
- **Do not remove yet:** legacy data models/routes. First create compatibility mappings and verify old backups/history remain readable.

## A realistic James OS 1.0

James OS 1.0 is not every proposed integration. It is a reliable, local-first daily reconstruction system that can truthfully answer:

1. how James slept/recovered and what current body/mental estimates are, with source/freshness/confidence;
2. where he spent meaningful time, including Unknown when it does not know;
3. what situation/activity/ownership applied to a period, with cheap correction;
4. how much time was Personal, Work, Obligation, Constrained and Unknown, without double-counting;
5. whether Personal time was interrupted/fragmented;
6. what evidence supports a Life Balance interpretation, without mistaking no evidence for zero;
7. what needs correction, and whether providers/tracking are currently trustworthy.

**Must be solid before 1.0:** Health Connect permission parity; non-destructive Visit editing and migration; unified semantic periods; one James Day story across Timeline/Today; unknown review; backup/migration round trips; provider freshness/recovery; on-device acceptance of Places/Visits; maintained mature-database performance.

**Nice after 1.0:** deterministic weekly review, carefully evidence-gated Time Ownership trends, calendar evidence, optional sleep/recovery explanations.

**Experimental after 1.0:** Wear adaptive sampling, anomaly detection, correlations, predictive warnings and Nova. These need measured benefit and explicit privacy boundaries.

## Prioritised roadmap

### P0 — data loss, security and public safety

- Keep public-repository gate/signing/backup safeguards intact; do not add cloud/AI data access without a disclosed contract.
- Add targeted backup round-trip tests before evolving semantic records further.

### P1 — correctness and incomplete existing work

1. Make Health Connect read declarations, runtime permissions, source status and UI capability list agree.
2. Make Visit merge/split/correction non-destructive and preserve original evidence/provenance.
3. Replace application-start full legacy reconstruction with an idempotent incremental/versioned process.
4. Finish the one semantic-period model and make Quick Actions create exactly the represented dimension.
5. Resolve Timeline calendar-day versus James-Day behaviour, including cross-midnight tests.

### P2 — product foundations

6. Ship an Unknown visit/ownership review and useful Visit detail editor; map/list/detail must share one Visit truth.
7. Surface ownership coverage, unknown time and provider freshness consistently across Today, Timeline, Places and Life Balance.
8. Decide whether Shift Tracker sender Part 2 is required for James’s real work context, then complete it only against the stable semantic model.

### P3 — valuable enhancements

9. Build a deterministic weekly review from trusted existing evidence.
10. Add Time Ownership/fragmentation trend analysis only after enough manually confirmed, linked history exists.

### P4/P5 — defer or reject

- defer Calendar, screen time, adaptive Wear sampling, anomalies and correlations pending evidence;
- reject continuous GPS breadcrumbs, automatic subjective interruption reasons, predictive health/mood claims, notification spam, duplicate provider dashboards and Nova without an explicit privacy/product decision.

## Manual-burden audit

| Input | Appropriate burden | Current assessment |
|---|---|---|
| Calibration/check-in | occasional subjective ground truth | appropriate; keep optional and calibrated |
| Context | one/two taps when sensors cannot know situation | current shortcut is misleading because it silently chooses Resting |
| Activity | brief, attached to current period where useful | current generic TimeBlock path is too disconnected |
| Ownership | a single explicit correction when uncertain | correct principle; needs clearer current/review UI |
| Interruption/resume | one tap plus optional reason | good principle; links and history presentation need completion |
| Routines/journal | voluntary reflection | keep optional; do not turn into required daily administration |

The design rule should remain: providers gather objective facts; James supplies subjective truth and corrections. No feature should require reconstructing every day manually.

## Top 10 things James OS needs next

1. Fix Health Connect permission/type parity.
2. Make visit edits non-destructive and auditable.
3. Replace startup-wide legacy Visit reconstruction with incremental migration.
4. Complete one linked Context/Activity/Ownership/Interruption period model.
5. Make Timeline use an explicit, consistent James Day policy.
6. Repair Quick Action semantics and remove silent category creation.
7. Add an Unknown review + real Visit detail workflow.
8. Standardise freshness, provenance and no-evidence versus zero across Today/Life Balance/provider status.
9. Build backup/migration/process-death acceptance tests around semantic data.
10. Only then decide Shift Tracker sender Part 2 and a deterministic weekly review.

## Five genuinely missing features that would improve James OS

1. **Linked semantic-period editing** — the missing connective tissue between place, activity, ownership and interruption.
2. **Focused unresolved-period review** — the lowest-burden way to turn uncertain inference into useful personal truth.
3. **A deterministic weekly review** — turns separate cards into an understandable account of the week.
4. **End-to-end Shift Tracker work feed (if used)** — makes Work an objective fact rather than another manual context guess.
5. **Evidence-gated Time Ownership trends** — only after enough confirmed history, this directly tests the project’s central autonomy/fragmentation hypothesis without claiming causation.

## Five things not worth building now

1. Continuous route/breadcrumb GPS history — invasive, battery-heavy and not the product question.
2. Automatic “someone needed me” interruption classification — unknowable from sensors and misleading.
3. Near-continuous Wear detailed sensor sampling — no proven marginal value over passive data/on-demand checks.
4. WHOOP Age dashboard — proprietary composite duplication with high overinterpretation risk.
5. Nova chat/predictions/notification intelligence — no privacy contract, evidence base or decisive use case yet.

## Final assessment

James OS already has the beginnings of an unusually thoughtful personal system: it protects privacy, keeps raw provenance, avoids simplistic “Home equals Personal” logic, and has made real progress against mature-database lag. But its current limitation is not lack of data sources. It is the gap between stored facts and a single honest, editable daily narrative. Finish that foundation; do not add a fifth source of partially overlapping truth.
