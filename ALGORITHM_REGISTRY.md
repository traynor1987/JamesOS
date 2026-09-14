# James OS Algorithm Registry

James OS keeps its custom derived-score engines versioned independently from the app release.
The registry is shown in **Settings → Algorithms** and is the single source for stable IDs,
algorithm versions, calibration versions, status, inputs, changes and historical-recalculation
capability.

## Versioning

- **Algorithm version** changes when the calculation logic changes.
- **Calibration version** changes when James-specific weights or parameters change.
- Original persisted outputs retain the version that produced them. A future recalculation must
  create a comparison, never silently overwrite original history.

## Body Battery v2.0.0

Body Battery is an estimate of James's remaining physical/physiological Reserve (0–100). It is
not a WHOOP score or a medical measurement.

### WHOOP Day Strain conversion

WHOOP's 0–21 Day Strain is treated as nonlinear. James OS uses a transparent
piecewise-smooth monotone spline-like curve: between each pair of calibration anchors it applies
`smoothstep(t) = t²(3 − 2t)`. This avoids opaque buckets and guarantees a monotonic total
cost.

Initial anchors:

| Strain | Reserve cost |
|---:|---:|
| 4 | 2.5 |
| 5 | 4.5 |
| 6 | 5.5 |
| 6.7 | 7.0 |
| 8 | 8.5 |
| 10 | 11.5 |
| 13 | 18.0 |
| 16 | 28.0 |
| 18 | 36.5 |
| 20 | 49.5 |
| 21 | 57.5 |

The deliberate calibration anchor is **6.7 Strain ≈ 7 Reserve points**. Higher strain becomes
progressively more costly.

### Accounting and reconciliation

- A James Day follows the completed-main-sleep boundary already used by Body Battery.
- A metadata record keyed by James Day persists the high-water raw strain, transformed total
  cost, latest incremental debit, timestamp, algorithm and calibration versions.
- A later equal WHOOP refresh creates an incremental debit of zero.
- A lower/stale refresh never refunds Reserve; it is held behind the daily high-water mark.
- When reliable WHOOP Strain is present, it is the primary accumulated exertion input. Steps,
  workouts and heart-rate activity only add tiny context so the same gym session is not charged
  several times.
- Morning recovery applies a bounded adjustment of -10% to +12%; it is surfaced in diagnostics
  when material.

## Diagnostics

Open **Settings → Algorithms → James Body Battery** to see raw strain, category, transformed
cost, previously accounted cost, latest increment, recovery adjustment, source reconciliation,
WHOOP timestamp, James Day ID and versions.

## Calibration roadmap

James OS stores enough structured data to later compare strain, sleep, Recovery, HRV, RHR,
James Stress, activity, end-of-day Reserve and optional self-report. Future personal calibration
changes the calibration version; it does not need to change the core algorithm version.
