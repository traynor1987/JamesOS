# James OS RUT to Life Balance Succession Audit

Audit date: 2026-09-16. Scope: current main source, retained RUT/import/backup, Time Ownership, Visits, Activity, interruptions, derived-score registry and tests. **Life Balance v2 is not implemented by this audit.**

## Executive summary

**YES, partially:** Life Balance should become the modern conceptual successor to RUT, but not as a numeric rename or point-led clone. RUT was an event-led cumulative ledger for agency, independence, personal time, boundaries, disrupted plans and recovery. Modern James OS has better first-class facts: Time Ownership, Visits, Activity and interruptions. The successor must calculate from those facts, not from a legacy RUT total.

Visits/place (where/when) → Activity (what) → Time Ownership (who controlled it) → Interruption (continuity) → Life Balance v2.

Balance remains separate from mood, health, Recovery, Time Pressure, productivity and whether life is good.

## Historical RUT contract

RUT is a validated logged-events ledger. An initial event supplies the start; each positive, negative or recovery event has signed integer points; Ledger.score is the unbounded signed-Long sum. A recovery is positive but may link to one prior negative Rut Pull only. RUT has no passive provider/GPS input, duration model, rolling window, decay, forecast/possible-score model or calibration.

Templates show the real intent: chosen activity/downtime, independence, boundaries, development, self-directed work, avoidance, disruption, household demands and recovery. Routines, day reviews and notes do not automatically alter the total.

| Score | Stage |
|---:|---|
| ≤ −751 | Deep Rut |
| −750…−501 | Stuck |
| −500…−251 | Fighting It |
| −250…−1 | Climbing Out |
| 0…249 | Breaking Free |
| 250…499 | Momentum |
| 500…749 | Building My Life |
| 750…999 | Thriving |
| ≥1000 | Out of the Rut |

The source does not clamp at ±1000; zero begins Breaking Free, not a calibrated neutral Balance point.

### Data and current −870

RUT v1/v2 import preserves settings, eventTemplates, loggedEvents, dailyNotes, milestones and metadata. It validates IDs, dates, templates, the ledger and recovery linkage; merge is idempotent, never overwrites conflict/current history or creates a second start; normal backup preserves it. No separate RUT prediction/forecast/readings model exists in inspected source.

No private James database is in this Work repository, so component timestamps for −870 cannot be claimed. If displayed, it is the **current sum of retained loggedEvents**, hence Deep Rut. It changes only if the ledger changes. It does **not** feed Life Balance, Low Mood, Mental Reserve, Time Pressure or any other current derived score. Existing tests prove a legacy RUT event does not create/rewrite Life Balance.

## Current Life Balance v1.0.1

Life Balance is separate: rolling 0–100 scores for 7/14/28 days, anchored to the accepted James-Day/main-sleep boundary. It requires 120 classified ownership minutes or returns LEARNING. Current arithmetic is base 50 plus Autonomous time, minus Committed/Constrained time and difficult Context; active output-bias calibration may apply. Work, Unknown, Activity and interruption facts are retained but not currently scored.

Ownership gives explicit OwnershipPeriod priority over enclosing Visit, resolves interval edges without double count, and preserves AUTONOMOUS, COMMITTED, CONSTRAINED, WORK and UNKNOWN. Unknown is missing evidence, never a negative conclusion. Interruptions split autonomous continuity and retain count/duration/longest block without a second ownership clock.

| Concept | RUT | Future Balance |
|---|---|---|
| Autonomy/personal time | Event points | First-class ownership; core |
| Work/obligation | Mixed point templates | Factual ownership; no automatic punishment |
| Constrained-but-not-busy | Event pressures | First-class Constrained, distinct from Time Pressure |
| Interrupted plans | Negative/recovery pairs | Continuity facts |
| Activity/hobbies/rest | Intrinsic points | Context only; rest needs no activity |
| Places/GPS | Not scored | Where/when only |
| Mood/health | Incidental template context | Explicit non-inputs |
| Unknown | Blank days/review | Coverage/confidence only |

## Low Mood Rut label

Low Mood code consumes life_balance, not the RUT ledger: (50 − currentLifeBalance)/3 bounded to ±10. The registry graph is low_mood_load → life_balance. Rut in the current input list/settings/documentation was stale migration metadata, not a hidden RUT feed. This audit corrects that display to Life Balance while retaining the preference key for upgrade compatibility. No coefficient, record or calibration changes.

## Dependency boundary

Life Balance feeds Low Mood and Mental Reserve. Body Battery, James Stress/Anxiety, Sleepiness, Live Energy, Energy Sustainability and Crash Risk form the other declared acyclic paths. A richer Balance must not absorb sleep, HRV, Recovery, mood, generic activity goodness or health data, which would double count downstream. A future −1000…+1000 number must never enter today’s 0–100 Low Mood transform.

## Proposed Balance v2 contract

“Over a sufficiently covered recent period, how aligned was James’s observed waking time with time he chose and values, versus time that was committed, constrained or fragmented?”

Time Ownership is primary. Visits establish where/when; Activity establishes what; neither determines ownership or earns intrinsic points. Manual ownership corrections win. Unknown reduces coverage/confidence, not Balance. Interruptions explain autonomous-time fragmentation; production weights need validation. Work/Committed time is normal and must be understood by proportion, opportunity and fragmentation rather than minute-for-point punishment. Shift Tracker, WHOOP, Health Connect and a future PC companion may supply factual Work/Activity evidence only.

Balance must be able to disagree with Low Mood, Recovery and Time Pressure. No deadline can coexist with constrained ownership; home can be deliberate Autonomous rest; cinema is not automatically Personal; gym/work/social activity/self-care/place diversity are not moral points.

### Scale and horizons

**MODIFY** the suggested −1000…+1000 scale: first use bounded internal components for ownership distribution, coverage and continuity. Use a rounded/banded display only after usability validation. Zero must be defined ownership/alignment reference, not “life is good” or a normalised constrained baseline.

Use observed waking time and separately show classified ownership coverage. Today is factual; 7 days provisional; 28 days primary; 90 days trajectory only with enough coverage. Omit trend/precise number where coverage is insufficient.

## Synthetic examples and anti-examples

| Case | Correct outcome |
|---|---|
| Mostly Autonomous | Strong alignment evidence, not proof of happy mood |
| Work-heavy with protected own blocks | Can be balanced |
| Heavily Constrained | Lower alignment only with adequate coverage |
| High Unknown | Insufficient confidence, not negative |
| Autonomous but interrupted | Fragmentation visible; no double count |
| Restful at home | Valid Autonomous time |
| Busy chosen day | Activity contextual only |
| No deadline, anticipatory constraint | Constrained; Time Pressure may remain low |

Gym ≠ good Balance; home ≠ bad; work ≠ bad; cinema ≠ Personal; no activity ≠ bad; lots of activity ≠ good; Low Mood ≠ bad Balance; Recovery ≠ Balance.

## Continuity, migration and plan

Keep **Legacy RUT era** and **James OS Balance era** as separate labelled series. Preserve RUT IDs, score semantics, recovery links and provenance. Do not draw one seamless numerical line or convert RUT −870 into Balance −870.

Future stages: define versioned v2 output/trace; aggregate ownership/visit/interruption by James Day with coverage; build explanation/confidence before a new Today score; retain legacy import/backup; validate synthetically and on device; only then consider a separately versioned bounded Low Mood integration if calibration proves value. Migration must be idempotent/non-destructive from modern facts only, preserving v1 and all historical records.

## Decisions

| Question | Answer |
|---|---|
| Life Balance succeeds RUT? | **YES, partially** — purpose, not numeric equivalence |
| −1000…+1000? | **MODIFY** — optional display after validation |
| Legacy RUT direct input? | **NO** |
| Preserve Legacy RUT history? | **YES** |
| Retire Rut from current algorithm UI? | **YES; retain Legacy RUT in history** |
| Low Mood use Balance? | **BOUNDED / future-versioned** |
| GPS direct score? | **NO** |
| Activity direct score? | **CONTEXTUAL only** |
| Health metrics score? | **NO** |

**Recommendation:** review this architecture before implementation. Do not implement Balance v2 in this task.
