# James OS WHOOP Field Utilisation Audit — Part 1

**Audit date:** 2026-09-15  
**Repository baseline:** `main` at `a6cc43c`  
**Scope:** evidence audit only. No provider scopes, sync behaviour, algorithms, UI, or database behaviour were changed.

## Executive summary

James OS already has a capable WHOOP integration. It retrieves **sleep, recovery, physiological cycles and workouts**, preserves each returned payload as a `whoop:raw:*` `ExternalRecord`, maps the highest-value daily readings, and keeps provenance on every mapped record. WHOOP is correctly authoritative for Recovery, overnight HRV, Sleep quality, sleep when directly available, resting HR, and accumulated day Strain.

The important finding is not that James OS needs more sensors. It is that several useful, already-downloaded fields are being left in the raw payload:

1. **Sleep Need components** — `baseline_milli`, `need_from_sleep_debt_milli`, `need_from_recent_strain_milli`, and `need_from_recent_nap_milli`.
2. **Sleep quality explanation** — efficiency, consistency, awake/no-data time, disturbances, cycle count and stage durations.
3. **Workout objective activity evidence** — sport, strain, HR, energy, distance, elevation, recording coverage and all six HR zones.
4. **Cycle energy/heart-rate context** — kilojoules, average HR and max HR.

Those fields need no new historic WHOOP fetch if the retained raw records cover the relevant period; a future bounded mapper can reconstruct James OS records from `ExternalRecord.data.original`. They should be considered one at a time, not dumped into Body Battery or Sleepiness while calibration is running.

## Evidence and API status

The official WHOOP Developer Platform API reference was checked on **2026-09-15**:

- [WHOOP API reference](https://developer.whoop.com/api/) — current v2 paths/scopes and schemas.
- [OAuth 2.0](https://developer.whoop.com/docs/developing/oauth/) — OAuth flow and `offline` refresh scope.
- [WHOOP pagination](https://developer.whoop.com/docs/developing/pagination/) — token pagination.

The reference lists these public user-data scopes: `read:recovery`, `read:cycles`, `read:workout`, `read:sleep`, `read:profile`, and `read:body_measurement`. It documents no public WHOOP Age/Pace of Aging or consumer Stress Monitor (0–3) resource.

## Current WHOOP architecture

`WhoopSource` talks only to the James OS privacy proxy; the Android app holds an encrypted device bearer key, not a WHOOP OAuth token or client secret. The proxy endpoint is called with a type of `sleep`, `recovery`, `cycle`, or `workout`; the application therefore cannot prove the server-side consent scope string from this repository alone.

For each returned row `WhoopMapper.records()` creates:

- a durable `ExternalRecord` (`whoop:raw:<type>:<external-id>`) containing the untouched provider object in `data.original`;
- zero or more provider-owned `HealthMetric` records, only when `score_state == SCORED`;
- stable `externalId`, source update timestamp, WHOOP type/id/cycle id, original start/end and ingest time metadata.

`JamesRepository.externalBatch()` deduplicates by record ID and accepts only a same-source row with `updatedAt >=` the saved row. The `source, externalId` index supports identity lookup. Parse-once `StoredRecord` caching remains in place, so this retention does not cause repeated JSON parsing in Compose.

### Sync/backfill

| Aspect | Current behaviour |
|---|---|
| Initial window | 28 days for each of sleep, recovery, cycle and workout |
| Subsequent window | trailing 2 days, intentionally allowing provider revisions |
| Pagination | `next_token`; loop detection; 100-page hard limit |
| Identity | WHOOP record `id`, except recovery uses `cycle_id` (its documented stable parent identity) |
| Revision | `updated_at` wins; raw record and mapped metric IDs remain stable |
| Background | WorkManager periodic sync every 30 minutes with network + battery-not-low constraints |
| Foreground | quiet sync no more often than 30 minutes; 10 minutes while a main sleep is processing |
| Documentation drift | `WHOOP_SETUP.md` says six hours, but source code schedules 30 minutes. The code is authoritative; the setup text should be corrected in a future documentation-only maintenance change. |

No body/profile endpoint is called. No massive backfill was triggered for this audit.

## OAuth scopes

| Scope | Purpose / enabled endpoint family | Used by James OS | Required for current implementation | Audit result |
|---|---|---:|---:|---|
| `read:recovery` | `/v2/recovery` | Yes | Yes | Keep |
| `read:cycles` | `/v2/cycle` | Yes | Yes | Keep |
| `read:sleep` | `/v2/activity/sleep` | Yes | Yes | Keep |
| `read:workout` | `/v2/activity/workout` | Yes | Yes | Keep |
| `read:profile` | `/v2/user/profile/basic` | No endpoint call | No | Do not request for this product |
| `read:body_measurement` | `/v2/user/measurement/body` | No endpoint call | No | Defer; only consider max HR after a separate authority decision |
| `offline` | refresh-token grant (official OAuth feature) | Server implementation is outside this repository | Cannot verify | Operationally likely required for unattended proxy refresh; verify server consent configuration before altering it |

**Important limitation:** `main` contains no OAuth authorize URL or scope list because consent happens on the privacy Site. The four data scopes above are demonstrated as required by the app’s actual endpoint families; the exact consent string cannot be honestly asserted from this repository. No evidence shows profile/body scopes are needed.

## Recovery

Official model: `cycle_id`, `sleep_id`, `user_id`, `created_at`, `updated_at`, `score_state`; score has `user_calibrating`, `recovery_score`, `resting_heart_rate`, `hrv_rmssd_milli`, `spo2_percentage`, `skin_temp_celsius`.

| Field | Parsed / raw | Mapped/stored | Current utilisation | Recommendation |
|---|---|---|---|---|
| IDs/timestamps/score state | Raw; identity/update parsed | raw record + mapped metric metadata | cycle ownership, revision, pending/scored safety | Keep |
| `user_calibrating` | Parsed and raw | prevents Recovery metric | protects against presenting an uncalibrated score | Keep |
| `recovery_score` | Parsed/raw | `Recovery` % | Today ring, Timeline, automatic signals, Body Battery morning capacity/Strain modifier, Right Now; not calibration input | Keep as is |
| `resting_heart_rate` | Parsed/raw | `Resting heart rate` bpm | Today overnight monitor; wellbeing/baseline evidence; WHOOP priority | Keep as is |
| `hrv_rmssd_milli` | Parsed/raw | `HRV` ms | Today; wellbeing baseline/physiology evidence; WHOOP overnight context | Keep as is |
| `spo2_percentage` | Parsed/raw | `Blood oxygen` % | Today/Timestamped timeline evidence; no algorithm input traced | Keep; low-priority future explanatory use only |
| `skin_temp_celsius` | Parsed/raw | `Skin temperature` °C | Today overnight monitor; retained as physiology evidence | Keep; do not overinterpret |
| `sleep_id`, `user_id` | Raw only | raw provider evidence | `sleep_id` supports future joins; `user_id` has no product use | retain in raw, never map/display `user_id` |

## Sleep and Sleep Need

Official sleep model: id/cycle/user/v1 IDs, created/updated timestamps, start/end, timezone offset, `nap`, `score_state`, stage summary, sleep-needed components, respiratory rate, Performance, Consistency and Efficiency.

| Field | Parsed / raw | Mapped/stored | Current utilisation | Recommendation / value |
|---|---|---|---|---|
| id, cycle_id, start/end, `nap`, score state | Parsed/raw | raw and metric provenance; sleep duration has start/end/nap | James Day boundary; pending-sleep gate; sleep dedupe; Timeline | Keep |
| light + SWS + REM stage milliseconds | Parsed/raw | summed as `Sleep` minutes only | Sleepiness duration/baseline, Body Battery sleep capacity, wellbeing/history | Keep total; **map stage detail from raw later** (high explanation value) |
| `total_in_bed_time_milli` | Raw only | Raw only | none | Medium; useful denominator/context, not primary sleep duration |
| `total_awake_time_milli` | Raw only | Raw only | none | High for sleep explanation/future sleepiness evidence, not Sleepiness v1 |
| `total_no_data_time_milli` | Raw only | Raw only | none | Low-medium data-quality explanation; do not score directly |
| `sleep_cycle_count` | Raw only | Raw only | none | Low-medium explanation, not a wellbeing proxy alone |
| `disturbance_count` | Raw only | Raw only | none | Medium explanation; avoid a naïve score without calibration |
| `sleep_needed.baseline_milli` | Raw only | Raw only | none | **Very high** future sleep-need baseline |
| `need_from_sleep_debt_milli` | Raw only | Raw only | none | **Very high** future shortfall explanation |
| `need_from_recent_strain_milli` | Raw only | Raw only | none | **High** load/sustainability explanation |
| `need_from_recent_nap_milli` | Raw only | Raw only | none | High context that prevents double-crediting naps |
| resulting total sleep need | Not explicitly returned as a field; derivable by summing four documented components | Raw components only | none | Derive only in a future mapped view; label it as derived from WHOOP components |
| `respiratory_rate` | Parsed/raw | `Respiratory rate` rpm | Today overnight monitor, Timeline; no algorithm input traced | Keep as is |
| `sleep_performance_percentage` | Parsed/raw | `Sleep quality` % | Today sleep ring; Body Battery fallback/capacity; Sleepiness small restorative modifier; 28-day state evidence | Keep as is |
| `sleep_consistency_percentage` | Raw only | Raw only | none | High explanation/future schedule regularity evidence |
| `sleep_efficiency_percentage` | Raw only | Raw only | none | High explanation/future quality evidence |

James OS is **not** merely “duration + Recovery”: it also maps Sleep Performance as `Sleep quality`, uses it in Body Battery and a bounded Sleepiness restorative contribution, preserves nap/main-sleep distinction, and waits for final WHOOP scoring before resetting the James Day. However, it presently throws the other sleep-quality dimensions into raw storage only.

## Cycles

Official cycle score fields: `strain`, `kilojoule`, `average_heart_rate`, `max_heart_rate`; records also have id/user/created/updated/start/end/timezone/score state.

| Field | Parsed / raw | Mapped/stored | Current utilisation | Recommendation |
|---|---|---|---|---|
| id, start/end, updated, score state | Parsed/raw | raw + provenance | current-cycle selection/revision/James-Day ownership | Keep |
| `strain` | Parsed/raw | `Strain` `/21` | Today ring; Body Battery’s primary accumulated load (cycle-bound/monotonic); automatic signals and Right Now | Keep as is |
| `kilojoule` | Raw only | Raw only | none | Medium; likely duplicate of calories/energy, defer until a no-double-count model exists |
| `average_heart_rate` | Raw only | Raw only | none | Low-medium daily context; not a substitute for fresh Wear HR |
| `max_heart_rate` | Raw only | Raw only | none | Low outside a workout/activity context |
| timezone/user metadata | Raw only | Raw only | none | retain evidence; no separate mapping/display |

## Workouts and HR zones

Official workout fields: id/v1/user/created/updated/start/end/timezone, `sport_name`, `sport_id`, score state; score has strain, average/max HR, kilojoules, percent recorded, distance, altitude gain/change, and zone durations.

The app currently maps only scored workout duration to `Exercise` minutes. SourcePolicy gives Health Connect Exercise priority over WHOOP, which avoids most double counting but does not perform explicit cross-provider session matching.

| Field | Parsed / raw | Mapped/stored | Current utilisation | Recommendation |
|---|---|---|---|---|
| id/times/score state | Parsed/raw (times also mapped to Exercise) | raw + Exercise provenance | Timeline can show Exercise; wellbeing activity windows | Keep |
| duration | Parsed from start/end | `Exercise` minutes | Timeline/wellbeing; Health Connect ranks first | Keep |
| sport name/id | Raw only | Raw only | none | **High** future objective activity label; never infer time ownership |
| workout strain | Raw only | Raw only | none (cycle Strain remains primary) | Medium; keep separate from day Strain, do not double-charge Body Battery |
| avg/max HR | Raw only | Raw only | none | Medium workout context; do not treat as current HR |
| kilojoules | Raw only | Raw only | none | Medium, duplication risk with Health Connect calories |
| percent recorded | Raw only | Raw only | none | Medium quality flag; map only alongside workout summary |
| distance | Raw only | Raw only | none | Medium; duplicate risk with Health Connect/Samsung distance |
| altitude gain/change | Raw only | Raw only | none | Low for James OS’s current wellbeing purpose |
| `zone_zero_milli` … `zone_five_milli` | Raw only | Raw only | none | **High** activity/weekly pattern value; store as one structured workout detail rather than six generic HealthMetric rows |

## Body measurements and profile

The official body endpoint returns `height_meter`, `weight_kilogram`, and `max_heart_rate`; profile returns `user_id`, email, first and last name. James OS calls neither endpoint and maps none of these fields.

- **Height/weight:** Health Connect/manual records are the better product fit. WHOOP would be a fallback only after explicit source-policy design.
- **Max HR:** potentially useful only for interpreting workout zones; a future design must choose an authority and avoid silently changing existing health interpretation.
- **Profile/email/name:** no current product value. Do not request/store them.

## Provenance, Timeline, calibration and algorithms

Mapped WHOOP metrics retain `source=whoop`, stable `externalId`, source update time, WHOOP type/id/cycle id, source start/end and local ingestion timestamp. The immutable raw provider object remains in its companion `ExternalRecord`. This is sufficient to answer source, external record, provider timestamp, sync/ingest timestamp and original provider evidence for mapped fields.

Current callsites:

- **Today:** Recovery/Sleep quality/Strain rings; HRV/RHR/respiratory/SpO2/skin temperature overnight monitor; Body Battery diagnostics.
- **Timeline:** all mapped `HealthMetric`s can appear; passive generic metrics are compacted. Raw `ExternalRecord`s are not displayed.
- **Insights/Right Now:** Recovery and Strain feed automatic physical-load/energy support; sleep and sleep quality feed State/Sleepiness. This does **not** make WHOOP Stress.
- **Body Battery:** Recovery, sleep/Performance, cycle Strain, HRV/RHR fallbacks and naps; WHOOP is primary for accumulated strain.
- **Calibration:** no WHOOP field is currently a direct calibration observation. WHOOP inputs are preserved in snapshots/evidence for existing algorithms but are not used to retune them here.
- **Nutrition/context:** WHOOP is not used to identify meals, places, ownership, work or obligation. Workout activity must never imply `PERSONAL`, `WORK`, `OBLIGATION`, or `CONSTRAINED` time.

## Health Connect and Samsung/Wear overlap

| Measurement | WHOOP current role | Health Connect / Samsung/Wear role | Current policy / risk |
|---|---|---|---|
| Main sleep | Direct WHOOP priority; raw/mapped | Health Connect fallback | sleep dedupe prefers WHOOP; preserves both sources |
| Sleep Performance | WHOOP-only mapped quality | no equivalent selected | WHOOP authoritative |
| Recovery / day Strain | WHOOP-only semantic scores | Samsung Energy is a small independent Body Battery contribution | not duplicated as identical metric |
| HRV/RHR | WHOOP overnight priority | Health Connect/Wear alternative; fresh Wear is a distinct context | compare only compatible provenance contexts |
| SpO2/respiratory/skin temperature | overnight WHOOP readings | Health Connect/Samsung may overlap | shown with provider provenance; no combined algorithm input traced |
| Exercise/distance/calories | WHOOP duration mapped; richer raw details unused | Health Connect priority | source rank prevents simple selection duplication but no explicit session matcher; future mapping needs one |
| Current heart rate/stress physiology | WHOOP has daily/historical data, not live stress | Wear supplies fresh readings/sensor checks | complementary, not interchangeable |

## Field utilisation matrix

Legend: **R** raw preserved, **M** mapped/stored, **D** displayed, **A** algorithm input, **C** calibration input, **T** Timeline, **I** Insights/Right Now.

| Endpoint | Field group | API / scope | Current classification | Priority / recommendation |
|---|---|---|---|---|
| Recovery | score, HRV, RHR | yes / `read:recovery` | R M D A T I; C no | Keep as is |
| Recovery | SpO2, skin temperature | yes / `read:recovery` | R M D T | Keep; no speculative algorithm use |
| Recovery | IDs, calibrating/state/timestamps | yes / `read:recovery` | R; state/IDs operationally parsed | Keep provenance |
| Sleep | total asleep + start/end/nap | yes / `read:sleep` | R M D A T I | Keep as is |
| Sleep | Performance | yes / `read:sleep` | R M D A T I | Keep as is |
| Sleep | respiratory rate | yes / `read:sleep` | R M D T | Keep |
| Sleep | Need components | yes / `read:sleep` | R only | **Use better from existing raw** |
| Sleep | efficiency/consistency | yes / `read:sleep` | R only | **Use better from existing raw** |
| Sleep | awake/no-data/disturbance/cycles/stages | yes / `read:sleep` | R only (stage sum parsed) | Map explanation selectively |
| Cycle | Strain | yes / `read:cycles` | R M D A T I | Keep as is |
| Cycle | kJ, avg/max HR | yes / `read:cycles` | R only | Defer; duplication/low freshness |
| Workout | duration | yes / `read:workout` | R M T; limited A | Keep |
| Workout | sport, strain, HR, kJ, distance, coverage | yes / `read:workout` | R only | Map one structured activity summary later |
| Workout | zones 0–5 | yes / `read:workout` | R only | High-value future activity/weekly pattern summary |
| Body measurement | height, weight, max HR | yes / `read:body_measurement` | not requested/downloaded | Defer; SourcePolicy decision first |
| Profile | name/email/user profile | yes / `read:profile` | not requested/downloaded | Low value; do not collect |
| WHOOP Age / Pace of Aging | no documented public endpoint | n/a | API NOT AVAILABLE | Do not scrape |
| WHOOP Stress Monitor 0–3 | no documented public endpoint | n/a | API NOT AVAILABLE | Keep James Stress distinct |

## Valuable WHOOP data currently wasted

1. **Sleep Need components — very high value, low complexity.** Already raw-preserved on scored sleep records; no extra API calls. They directly explain why a long sleep may still not meet need, and distinguish debt, strain and nap adjustment. They should first be surfaced as transparent evidence, not immediately change Sleepiness or Body Battery.
2. **Sleep efficiency/consistency and awake/disturbance detail — high value, low complexity.** Already raw-preserved; better sleep explanation and future longitudinal evidence. Stage distribution is useful as description, not as an instant diagnosis.
3. **Structured workouts and HR zones — high value, medium complexity.** Already raw-preserved; objective activity and weekly patterns. Requires a provider/session dedupe rule with Health Connect and strict separation from time ownership.
4. **Workout coverage + sport label — high value, low-medium complexity.** Makes activity evidence interpretable and avoids pretending an incomplete workout is definitive.
5. **Cycle kJ/average/max HR — medium value, medium duplication risk.** Useful only after a clear energy/heart-rate source policy; do not create duplicate calorie/HR streams.

## Data already used well

- Recovery, HRV and resting HR are mapped with exact provenance and never fabricated from missing values.
- Sleep is summed from asleep stages rather than incorrectly treating time in bed as sleep.
- Pending/unscored sleep remains visible raw evidence and stops premature new-day calculations.
- Current WHOOP cycle Strain is revision-aware, ownership-bound and primary for accumulated Body Battery load; workouts/steps are deliberately not charged again.
- Sleep Performance is already a bounded Sleepiness and Body Battery input rather than a decorative number.
- Direct WHOOP metrics coexist with Health Connect/Samsung evidence instead of overwriting it.

## Low-value data to ignore or defer

- Profile name/email: unnecessary personal data.
- Raw `user_id`: retain inside provider evidence only; no display/mapping.
- Cycle/workout altitude change and elevation: no clear James OS wellbeing value today.
- A second generic calories/distance/HR stream from WHOOP: defer until exact Health Connect session reconciliation exists.
- WHOOP Age, Pace of Aging, and consumer Stress Monitor: API not available in the supported documented public API; no scraping/private endpoints.

## Performance, storage, privacy and rate cost

WHOOP raw history is bounded to 28 days on first connection and a two-day revision window later; UI semantic state is bounded to 40 days with indexes on timestamp, kind/timestamp and source/kind/timestamp. `StoredRecord` parses each row once per emitted snapshot. Future mapping must read `ExternalRecord.data.original` once in a repository/migration job—not repeatedly in Timeline/Compose—and must avoid six separate zone rows when one workout-detail record will do.

Mapping sleep detail or workouts from existing raw data adds modest local storage only. HR-zone detail is the largest candidate but remains tiny as one structured record per workout. It needs **no Wear sensor battery** and, for retained history, **no WHOOP network call**. WHOOP API work costs network/sync only; it is not a five-minute active Wear sensor loop.

No real WHOOP payloads or health values appear in this audit or tests. Synthetic fixtures remain required for any later mapper tests.

## Historical opportunity

For raw WHOOP rows already within the local retention window, a future migration can safely do:

`ExternalRecord(type=sleep/workout) → parse data.original once → versioned mapped James OS evidence`

No re-download is necessary for those rows. Records beyond the original 28-day first-sync depth are not locally available unless a future, explicitly approved historic backfill fetches them. Existing two-day incremental sync cannot recover older history by itself.

## Recommended Part 2 (proposal only)

Keep Part 2 bounded to **sleep explanation evidence from existing raw scored Sleep records**:

1. Map the four official Sleep Need components plus an explicitly derived total need.
2. Map Sleep Efficiency, Sleep Consistency, awake duration and disturbance count as one structured sleep-detail record.
3. Backfill only existing local raw WHOOP sleep records using stable IDs; no API call, no new scope, no algorithm changes.
4. Add synthetic raw-payload and dedupe/provenance tests.
5. Initially display/inspect the data as explanation only. Do **not** alter Sleepiness v1, Body Battery, Mental Reserve, Recovery, Life Balance or calibration.

Workouts/HR zones are a strong **Part 3 candidate**, after a separate design for Health Connect session dedupe and activity-to-visit presentation. WHOOP activity must never infer who owned the time.

## Top opportunities

| Field(s) | Current status | Value | Extra API cost | Effort / duplication | Recommendation |
|---|---|---|---|---|---|
| Sleep Need components | raw only | Very high | None | Low / low | Part 2 explanation-only mapping |
| Efficiency, consistency, awake, disturbances | raw only | High | None | Low / low | Part 2 structured sleep detail |
| Workout sport + zones + coverage | raw only | High | None for retained history | Medium / high with Health Connect | Defer to Part 3 |
| Workout distance/energy/HR | raw only | Medium | None | Medium / high | Defer pending authority/dedupe design |
| Cycle kJ/HR | raw only | Medium | None | Medium / medium | Defer |

## Audit test coverage

Existing `WhoopMapperTest` covers unscored records, missing values, stable identity, asleep-stage duration, and calibration suppression. It does **not** cover raw preservation/mapping expectations for sleep need, efficiency/consistency, stage detail, cycle non-Strain fields, workout metadata, HR zones or body/profile non-collection. That is expected because none is currently mapped. Any Part 2 must add synthetic tests before adding a mapper.

## Conclusion

James OS is not starved of WHOOP data. It already has the four right data families and preserves the richest unused fields locally. The next sensible move is to use the sleep evidence it already has—carefully and transparently—rather than build more Wear polling or chase undocumented WHOOP consumer features.
