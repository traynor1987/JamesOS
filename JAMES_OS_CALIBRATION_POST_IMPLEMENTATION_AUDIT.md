# James OS Calibration Post-Implementation Audit

Date: 13 September 2026  
Audit scope: continuation Sections 62–159, against released main v0.3.201  
Repaired target: phone v0.3.202; Calibration Engine v1.0.2

## Executive verdict

The continuation audit found real persistence and concurrency defects in the released v1.0.1 Calibration Engine. Candidate activation was assembled from a potentially stale UI snapshot, candidate/back-test rows were not bound to an immutable evidence dataset or dependency versions, new feedback did not invalidate an already-tested candidate, and imported active calibration profiles could coexist with or supersede local active state. Those were P0/P1 safety defects and were repaired.

The repaired engine keeps production scoring deterministic, local and bounded. A feedback event changes evidence only. Candidate generation remains a deterministic, regularized four-point maximum output-bias step. Candidate and back-test identity now binds the algorithm, engine, full evidence dataset, base set and dependency calibration versions. Activation revalidates this identity inside one Room transaction. Rollback/default restore use the same repository-owned transaction path. No real James calibration was activated during this audit.

Final acceptance answer: **YES**, subject to the documented experimental limitations below. James can safely record right/wrong feedback, accumulate evidence, generate and back-test a bounded candidate, approve a parameter-only activation without an APK, retain historical provenance, and roll back.

## Findings and repairs

### P0 — fixed

- **Stale activation race:** activation previously trusted candidate data selected from `StateFlow` and assembled several writes before entering a generic batch transaction. It now re-reads candidate, events, active profile, dependency versions and back-test inside the repository transaction and rejects any mismatch.
- **Unbound back-test evidence:** candidates previously stored only training IDs; a later edit/new event could be evaluated as though it were the original dataset. Candidates and back-tests now store a deterministic full-dataset hash, all evidence IDs, base calibration-set ID, dependency versions and engine version.
- **Imported active-state injection:** generic backup merge could add an older/crafted active profile beside a newer local profile. Calibration imports now validate algorithm/schema compatibility, finite bounded parameters and hashes. Existing local active state wins; a clean restore keeps only the newest valid imported active profile per algorithm.
- **Active profile invariant:** startup now creates explicit default profiles reproducing released behaviour and repairs duplicate/invalid active profiles. Activation, rollback and default restore enforce one coherent active set transactionally.

### P1 — fixed

- Stable calibration submission IDs make double taps, Activity recreation and write retries idempotent.
- New, edited, ignored or deleted observations mark DRAFT/TESTED/APPROVED candidates `STALE`; stale candidates cannot activate.
- Analysis records per-algorithm `ANALYSING`, `COMPLETE` or `FAILED`. Process recreation converts interrupted work into a retryable failure; candidate/back-test persistence remains all-or-nothing.
- The calibration modal now freezes the score/version/set James actually saw when opening it, preventing a recalculation race from silently calibrating a different score.
- Initial default calibration sets are explicit persisted data while preserving the prior score exactly.
- James Stress calibration is now consumed by its deterministic dashboard interpretation and downstream Anxiety/Live Energy context while preserving the immutable raw Wear reading separately.
- Added real Room integration coverage for event idempotency, tested candidate persistence, activation, repository/database recreation, rollback, history retention and stale activation rejection.

### P2 / deferred

- Automatic optimization deliberately tunes only a bounded `outputBias` (maximum four points per candidate). Body Battery's existing awake-drain coefficient is explicit configuration but is not automatically searched until identifiability and richer replay support are available.
- Dependency versions are captured and stale changes are blocked. Full downstream counterfactual replay remains limited where historical runners cannot reconstruct exact upstream inputs; those results continue to declare stored-snapshot replay.
- Pattern mining is limited to declared score bands/context tags. Broad automatic feature mining is intentionally absent to avoid false discovery.
- Calibration Lab displays at most 4,000 parsed observations. Its list is lazy and analysis runs off-main, but a future DAO-paged observation UI would be preferable for substantially larger datasets.
- Nova remains an interface boundary only. There is no live Nova optimizer and the UI does not claim that Nova analysis occurred.

### Not implemented by design

- Automatic candidate activation.
- Arbitrary formulas, executable models or black-box production ML.
- Calibration from chat, Nova notes or unstructured Timeline text.
- Automatic medical or causal claims from nutrition, context, stress, anxiety or low-mood signals.
- Wear OS Calibration Lab.

### Platform / data limitations

- Historical replay uses the stored versioned snapshot when exact raw reconstruction is unavailable and labels the replay method accordingly.
- Calibration quality is limited by James's structured observations, temporal coverage and source availability.
- Android APK downgrade across newer Room schemas is unsupported; backup/export is the safe portability path.

## Architecture verified

- Raw WHOOP, Health Connect, Wear, nutrition, location and context records are never modified by calibration.
- Identical input snapshot + algorithm version + immutable calibration set produces identical bounded output.
- Nova cannot write scores, activate a set, bypass bounds or inject formulas.
- A single observation only changes evidence/statistics and invalidates stale candidates; it never changes active parameters.
- Persisted scores retain the algorithm/calibration/set identity that produced them. Historical scores are not re-labelled through the current active set.
- Candidate activation requires James's explicit action and a TESTED, current, compatible, improving, regression-free back-test.
- Parameter-only activation is read from Room by the normal score pipeline and requires neither restart nor APK update.

## Algorithm support matrix

| Algorithm | Calibration | Primary target | Parameters / bounds | Runner and production path | Back-test | Activation / rollback | Dependencies |
|---|---:|---|---|---|---|---|---|
| Body Battery | Yes | Perceived remaining physical capacity | output bias −12…12; awake drain 0.8…2.4/h | Deterministic Body Battery + active profile | Stored snapshot; range/context guards | Yes / Yes | WHOOP and health inputs; downstream to Mental Reserve/Live Energy |
| James Stress | Yes | Physiological stress interpretation; subjective stress is supporting evidence | output bias −12…12 | Raw Wear record preserved; calibrated interpretation consumed by UI/downstream | Stored snapshot | Yes / Yes | Upstream of Anxiety/Live Energy |
| Anxiety Load | Yes | Current reported anxiety-associated experience | output bias −12…12 | Deterministic wellbeing runner | Stored snapshot; context guards | Yes / Yes | James Stress |
| Mental Reserve | Yes | Available mental capacity | output bias −12…12 | Deterministic wellbeing runner | Stored snapshot; dependency identity | Yes / Yes | Body Battery, Stress, Anxiety, Context, Life Balance |
| Low-Mood Load | Yes | Longitudinal reported mood/life pattern | output bias −12…12 | Slow rolling wellbeing runner | Weekly-aligned evidence | Yes / Yes | Life Balance |
| Live Energy | Yes | Current subjective energetic/alert state | output bias −12…12 | Deterministic Right Now runner | Stored snapshot; context guards | Yes / Yes | Body Battery, Mental Reserve, Stress |
| Energy Sustainability | Yes | Perceived support/sustainability of current energy | output bias −12…12 | Deterministic Right Now runner | Manual evidence only; no auto candidate | Yes / Yes | Live Energy, Body Battery, Mental Reserve |
| Crash Risk | Yes | Perceived likelihood of near-term loss of energy | output bias −12…12 | Deterministic Right Now runner | Manual evidence only; no auto candidate | Yes / Yes | Sustainability, Live Energy, reserves |
| Time Pressure | Yes | Felt shortage of usable personal time | output bias −12…12 | Deterministic Right Now runner | Stored snapshot | Yes / Yes | Recorded time/context |
| Context Load | Yes | Current perceived mental demand | output bias −12…12 | Deterministic context runner | Stored snapshot | Yes / Yes | Recorded visit/difficult intervals |
| Life Balance | Yes | Retrospective life/personal-time balance | output bias −12…12 | Deterministic rolling runner | Longitudinal evidence | Yes / Yes | Personal/obligation/activity facts |
| Context Recovery | Future registration only | Measured/subjective recovery after context | none active | No current registry runner | No | No | Future |
| Autonomy Trend | Future registration only | Retrospective autonomy | none active | No current registry runner | No | No | Future |
| Legacy Rut | No | Historical ledger only | none | Historical deterministic ledger | No | No | None |

## Persistence, backup and migrations

- Room schema remains v3; this repair adds indexed DAO queries and transaction logic without a destructive table change.
- Activation writes the inactive prior profile, complete immutable new profile, candidate status and activation audit row in one transaction.
- On first activation the released default is preserved explicitly as v1.0.0, enabling a real rollback target.
- Backup continues to include Calibration Events, profiles, candidates, back-tests, patterns and activation history through `personalRecords`.
- Restore validates calibration data as untrusted structured input and prevents implicit rollback of a newer local active set.

## Verification coverage

- Deterministic statistics: count, bias, MAE, median absolute error, recency-weighted MAE, score ranges and context slices.
- Small/homogeneous dataset safeguards and deterministic bounded candidate identity.
- Train/validation split, regression guards, corrupted profile fallback and algorithm/dependency compatibility.
- Stable feedback request ID and full dataset/dependency staleness.
- Real Room activation → close/reopen → rollback → close/reopen.
- New evidence converts a tested candidate to STALE and blocks activation.
- Backup round-trip, duplicate prevention, imported-active conflict handling and out-of-bounds import rejection.
- Explicit default profiles preserve pre-calibration outputs.

## Privacy and causality

Calibration observations remain local-first, are not logged as raw payloads, are not included as APK fixtures, and are not sent to GitHub or Nova. Nutrition/hydration/caffeine and location/context findings use association wording. No consumption or place is assigned a causal score effect merely because it was recorded.

## Release verification

- Complete main validation: [James Android validation #34767660331](https://github.com/traynor1987/Jamssos/actions/runs/34767660331) — phone unit tests, lint, debug/release compilation, Wear build and emulator/Room instrumentation all passed.
- Final repaired release: [v0.3.202](https://github.com/traynor1987/Jamssos/releases/tag/v0.3.202), published once through the established signed-release workflow.
- The release workflow verifies the existing signing identity before publication. Its release metadata advertises phone version `0.3.202` / version code `202`, allowing the existing updater's semantic-version policy to discover it from GitHub's latest release endpoint.
- Wear remains `0.2.4`; this repair did not change Wear code.
