# James OS Mental Wellbeing

## Purpose and safety

Mental Wellbeing is an optional, local-first experimental system. It describes changes against James's own baseline; it does not diagnose depression, anxiety disorders, suicide risk, or any mental illness. It never creates emergency conclusions or notifications from sensors.

The phone is authoritative. No raw health, location, WHOOP credentials, OAuth secrets, private notes, or historical location trail is sent to Wear OS. The watch receives only the small computed summary it needs to display.

## Outputs

- **Anxiety Load** (0–100): a fast physiological/context load estimate. It considers James Stress, resting heart rate, HRV, sleep and recovery relative to personal baselines. When meaningful movement/exercise is present, elevated physiology is explicitly treated as exercise context rather than psychological anxiety.
- **Low-Mood Load** (0–100): a slow moving, multi-day pattern estimate. It uses bounded Life Balance context, sleep/recovery/HRV trends, activity, personal time, activity diversity, and optional self reports. It does not consume the legacy RUT ledger. A single poor sleep or one rest day cannot dominate the result.
- **Mental Reserve** (0–100): contextual capacity, not a duplicate of Body Battery. Physical reserve is one contributor alongside Anxiety Load and longer-term context.

Scores use neutral language and include an experimental/not-a-diagnosis notice.

## Baselines, confidence and contributions

The engine uses up to 28 previous days for per-signal median baselines; at least three samples are required for a signal. Confidence is Learning, Low, Moderate, or Good based on history and usable signals. Missing data contributes no penalty and lowers confidence.

Every calculated contributor records source, current value, baseline, direction, weight, confidence, contribution, and an explanation. The UI uses those records for **Why James thinks this**. Life Balance is bounded contextual evidence; it is not a direct mood trend or a replacement name for legacy RUT.

## Persistence and versioning

A daily metadata summary is saved with key `mental-wellbeing:<date>:wellbeing-v1`. It contains scores, confidence, calibration counters, and serialized contributions. Existing health, Rut, timeline, WHOOP, location and check-in records are never migrated or duplicated. Reset baseline deletes only these generated daily summaries; it leaves all source records intact.

Future algorithm releases can recalculate historical summaries from source records and save a new algorithm version alongside the old one.

## Settings and privacy

Settings → Mental wellbeing lets James turn the feature, check-ins, each output, and Life Balance context/exercise/diversity/personal-time/physiology/WHOOP/Health Connect/Wear/Samsung inputs on or off. Disabled sources are excluded; absence of a source is not interpreted negatively.

Check-ins are voluntary. Phone check-ins may include mood, energy and anxiety-now; Wear sends only the selected five-level mood option over the existing Data Layer.

## Wear OS

Wear gets a compact screen with Mental Reserve, Anxiety, trend/confidence and optional five-level mood check-in. It performs no historical scoring. The phone computes and persists the result, then sends a minimal snapshot. When disconnected, the Watch does not invent a result.

## Limitations and next calibration milestone

This first transparent model is deliberately statistical rather than opaque ML. Place labels and timeline categories may be incomplete; social context is not inferred from raw GPS; WHOOP and Health Connect fields vary by provider. The next milestone is a guarded correlation view that requires adequate sample counts before saying signals are *associated with* reported mood. Never claim that one signal causes a mental-health outcome.
