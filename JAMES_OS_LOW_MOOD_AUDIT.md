# James OS Low Mood Accuracy / Semantics Audit

Audit date: 2026-09-16  
Scope: current `main` implementation and the real-device observations supplied by James. This is not a clinical assessment and does not diagnose mood or depression.

## Executive finding

`LOW-MOOD 27 · LOW · IMPROVING ↘` does **not** mean James OS has evidence that James feels happy, motivated, interested, engaged, or is maintaining self-care. It means the current **Low-Mood Load v1.3** formula produced 27 from a generic base plus the limited records it knows how to use.

The model has no direct input for motivation, enjoyment, inspiration, behavioural activation, or routine self-care. Therefore James's report of feeling flat, unmotivated and disengaged is a real blind spot in the present model, not a contradiction that can be resolved by reinterpreting low Stress/Anxiety/Time Pressure as good mood.

The displayed `IMPROVING ↘` was a real implementation defect. It was a Life Balance-derived label, not a Low Mood score trend, and a parenthesisation error made equal 7-day and 14-day Life Balance scores look like improvement. The defect is corrected in Low-Mood Load v1.3.1 / Life Balance v1.0.1. It changes the descriptive trend label only; it does not change the Low-Mood score, calibration, evidence, or any wellbeing coefficient.

## Current contract and scale

Registry contract (`low_mood_load`, v1.3.0 before the trend-label fix): “a slow-moving trend in low-mood-associated patterns relative to James's own baseline. Not a diagnosis.”

This is a **load** scale, not a direct positive-mood scale:

| Score | Current label | Actual intended meaning |
|---:|---|---|
| 0–19 | VERY LOW | Very little modelled low-mood-associated load; **not** proof of happiness. |
| 20–39 | LOW | Low modelled load. This includes the reported 27. |
| 40–59 | MODERATE | Moderate modelled load. |
| 60–79 | HIGH | High modelled load. |
| 80–100 | VERY HIGH | Very high modelled load. |

There is no separately specified “50 means …” anchor beyond the arithmetic base of 30 plus contributions. In particular, `30` is a mathematical starting prior, not an observed emotional state. The title `LOW-MOOD` and calibration choices such as `VERY LOW`, `LOW`, `OKAY`, `GOOD`, `VERY GOOD` make the direction unnecessarily easy to misread: the calibration engine reverses those mood-state answers to fit a *low-mood load* score.

## Exact current implementation

`mentalWellbeing()` starts Low-Mood Load at **30**, sums only the contributors below, rounds, constrains the result to 0–100, then applies an active bounded output-bias calibration if one exists.

| Input | Window / condition | Contribution | Notes |
|---|---|---:|---|
| Life Balance | Current rolling 7-day factual ownership score present | `(50 - score) / 3`, bounded −10…+10 | Derived `life_balance` output; no score if ownership evidence is insufficient. |
| Sleep | 7-day average below own pre-today 28-day baseline | 0…+12 | Shortfall only; longer sleep does not lower the score. |
| Recovery | 7-day average vs own baseline | −10…+10 | Higher than baseline helps; lower adds load. |
| HRV | 7-day average vs compatible own baseline | −10…+10 | Same directional rule; measurement context is retained. |
| Exercise | Distinct recorded exercise days in last 14 days | −3 per day, max −12 | Supporting context, not a requirement. |
| “Time that was mine” | Keyword-detected TimeBlock/Event in last 7 days | −1 per 30 minutes, max −10 | This is not the authoritative Time Ownership ledger. |
| Activity diversity | More than one distinct PlaceVisit in last 14 days | −2 | Weak supporting context. |
| Latest optional mood report | Latest MoodEntry/WellbeingCheckIn in last 28 days | difficult/low +12; okay 0; good/great −10 | It has an abrupt 28-day expiry, not gradual decay. |

Missing inputs normally add **nothing**. They are not fabricated as positive evidence. However the fixed base 30 remains, and the confidence presented is based on broad health-record coverage rather than direct mood/engagement evidence.

## 49 → 27 evidence trace

### What is persisted

Current calculations are saved as `metadata/mental-wellbeing:<date>:<wellbeing-version>`. The stored Low Mood object retains score, label, trend, confidence, algorithm/calibration identifiers and every contributor’s value, baseline, contribution, provenance and freshness fields. It does **not** retain a separate immutable calculation history or a full input fingerprint for Low Mood (the dedicated input diagnostic exists only for Anxiety). Same-date recomputation replaces that metadata value.

The Work environment intentionally has no copy of James's private database, so it cannot read the two private contributor lists and falsely claim an exact personal arithmetic comparison. The real-device observations establish approximately 49 previously and 27 now; the source code establishes exactly how those values must be decomposed in the stored records. The correct private comparison is the two saved `lowMood.contributors` arrays, their algorithm/calibration versions, and the active calibration profile—not a guess from screenshots.

### What can already be concluded

* The score was not lowered by direct consumption of `Stress`, `Anxiety`, `Time Pressure`, `Body Battery`, `Mental Reserve`, `Sleepiness`, `Live Energy`, or their output scores: each has **0 direct points** in Low Mood.
* Low Stress/Anxiety/Time Pressure therefore do **not** directly mean “good mood” in this formula.
* A lower score can come from the base 30 plus helpful Recovery/HRV, recorded exercise, keyword-detected personal activity, place diversity, a `GOOD`/`GREAT` mood report, a favourable Life Balance score, or an active negative calibration bias. Missing negative evidence alone cannot subtract points.
* A historical check-in can retain its full ±10/12 contribution for up to 28 days; a seven-day physiology average can change when records enter/leave its hard window. These are plausible sources of a day-to-day reduction, but the private persisted contributor arrays are required to apportion the actual 49→27 change.

### Required local explanation

The existing stored contributor fields can support a factual “Why 27?” view:

* base: `30`;
* each included contributor and signed points;
* absent categories explicitly marked **not observed / not used**;
* current confidence and the evidence it is based on;
* current algorithm version, calibration set and latest direct mood-report timestamp;
* trend wording separated from the score.

Do not fabricate a precise 49→27 decomposition when the device records are not present.

## Cross-algorithm coupling

| Candidate input/output | Direct Low-Mood input? | Finding |
|---|---|---|
| James Stress | No | Used by Anxiety and other models, never directly by Low Mood. |
| Anxiety Load output | No | No feedback chain into Low Mood. |
| Time Pressure output | No | No feedback chain into Low Mood. |
| Mental Reserve / Body Battery / Sleepiness / Live Energy outputs | No | Not consumed by Low Mood. Low Mood is instead used as contextual evidence by Mental Reserve. |
| Life Balance output | Yes | The sole derived-score dependency. Its factual ownership inputs are appropriate context, but its trend must not be presented as direct mood improvement. |
| Sleep / Recovery / HRV raw evidence | Yes | Used directly as slow physiological context, not through other score outputs. |

This rules out the proposed “low distress = good mood” coupling as the direct cause of 27. It does not remove the model’s broader blind spot: it lacks direct evidence of positive affect and engagement.

## Blind spots and missing-data semantics

The model has no dedicated evidence for enjoyment, interest, inspiration, motivation, meaningful chosen activity, behavioural activation, or self-care. It must not infer a diagnosis from missed tooth-brushing, watching YouTube, or an empty timeline. Those observations could only become weak, explicitly-designed, repeatedly-validated context in a future version.

Absence handling is mixed:

* no missing input is converted into a helping point;
* a lack of factual Life Balance evidence is explicitly neutral;
* the fixed base 30 can nevertheless look like a precise current judgement once there is unrelated health evidence;
* the Low Mood confidence is broad physiology coverage, not proof that mood is observed;
* `recentMood` is binary-windowed: full influence until day 28, then none. There is no gradual time decay.

This is sufficient to call the present score a low-confidence *proxy load*, not an adequate estimate of James’s reported flatness.

## Calibration audit

* Algorithm: Low-Mood Load v1.3.0 before this audit’s trend correction; v1.3.1 after it.
* Calibration schema: v1.0.0.
* Active parameter: bounded `outputBias` only (−12…+12).
* Automatic candidates: disabled.
* Minimum evidence: 10 suitable observations; longitudinal feedback limited to one observation every six days.
* Available statistics in the app: observation count, MAE, median/recency-weighted MAE, bias, score-range coverage, context coverage and newest observation.

No private calibration records are available in this Work environment, so count, MAE, bias, coverage and active set cannot truthfully be filled with invented values. The calibration UI is also semantically weak: its generic “How does this compare?” dialog exposes mood-state choices while the engine reverses them for a load scale. A future UX-only correction should ask a direct weekly question with unmistakable direction, for example whether the **past week felt low/flat** rather than asking the user to reverse-engineer the score.

## Trend-label defect fixed

Before the fix both Life Balance and Low Mood used `days7 - days14 / 2`. With equal scores of 50 this becomes 25, incorrectly exceeding the `>3` “improving” threshold. The corrected calculation is `(days7 - days14) / 2`.

The Low Mood trend is therefore **not** a numerical Low Mood score decrease. It is only a Life Balance-derived contextual direction and should not be read as “James’s mood is improving.” Regression tests now prove that stable Life Balance produces `STEADY` both in Life Balance and Low Mood.

## Time Pressure companion audit

Time Pressure is a separate real-time model. It begins at **5** when no next known fixed constraint exists, or at 15/32/52/68/82 according to usable minutes before a known fixed constraint; it subtracts recorded personal-time relief and adds a fresh (eight-hour) direct Time Pressure check-in. The final value is clamped to 0–100 and can be shifted by an active bounded calibration bias.

Consequences for a persistent device value of `0`:

* it is **not** the no-evidence default (that is 5, labelled VERY LOW, with LIMITED confidence);
* no constraint plus a fresh `NOT AT ALL` check-in yields `5 − 15`, clamped to **0**;
* an active calibration bias of −5 or lower can also move the no-constraint base to 0;
* without the private current `right-now:<james-day>` metadata and/or `EnergySnapshot` records, the exact one of those explanations cannot be asserted.

Time Pressure persists its score, base score, confidence and contributors in current-day metadata and writes periodic meaningful `EnergySnapshot` history. It has no dedicated immutable input fingerprint. Existing UI exposes its contributors under `WHY? / DETAILS`, but the compact tile can show a numeric score whenever unrelated health evidence satisfies the broad right-now gate. Thus `0` can appear precise despite `LIMITED` evidence.

Time Pressure does **not** consume the authoritative `OwnershipPeriod` ledger. It only uses recognised personal TimeBlock/PlaceVisit/Event labels for relief, plus next fixed constraints and direct check-ins. This means constrained/free-but-not-autonomous time is not incorrectly forced into Time Pressure—which is correct—but it also means Time Ownership coverage cannot currently strengthen or explain a Time Pressure result.

## Failure classification

| Code | Finding |
|---|---|
| B — calibration insufficient / unclear | Calibration exists but device-specific evidence is unavailable here; its weekly question is directionally ambiguous. |
| C — missing input / blind spot | No motivation, enjoyment, interest, engagement or self-care evidence. |
| E — misleading metric name | “Low-Mood” is displayed beside a load scale and mood-state calibration choices. |
| F — misleading trend label | `IMPROVING` was Life Balance-derived, not mood-score trend. |
| G — prior/default visibility | Base 30 and Time Pressure base 5 can be displayed as precise-looking values under broad readiness gates. |
| H — time-window problem | Seven-day rectangular averaging and 28-day all-or-nothing mood-report expiry. |
| I — limited derived coupling | Life Balance is a direct dependency; other negative-state outputs are not. |
| J — implementation bug | Parenthesisation error in Life Balance / Low Mood trend arithmetic; fixed. |

`D — low-distress = good-mood confusion` is **not** the direct implementation cause: Stress, Anxiety and Time Pressure are zero-point inputs to Low Mood. The product still needs clearer language so a low proxy load is never heard as “you are happy.”

## Bounded recommendation

1. Ship the trend-label arithmetic correction only; do not tune Low Mood coefficients or change Sleepiness/Body Battery/Recovery/other wellbeing algorithms.
2. Add a later, separately reviewed **explanation and calibration UX** change: expose the stored Low Mood contributor trace, direct confidence/missing categories, algorithm/calibration version, and a clearly worded weekly “how low/flat did the last week feel?” observation.
3. Collect at least ten well-worded, versioned weekly observations before testing a new model. Preserve v1 outputs and calibration observations; any altered interpretation must be Low-Mood v2 with explicit compatibility rules.
4. Audit whether a separate, user-reported engagement/motivation dimension has enough value before adding it. Do not infer it from self-care or passive behaviour.
5. Separately improve Time Pressure evidence-state presentation: when there is no upcoming constraint or fresh check-in, present **limited / no known constraint**, not a confident-looking zero. Keep Time Ownership separate from Time Pressure unless a future product contract explicitly connects them.

No WHOOP Part 3 work, coefficient change, threshold change, diagnosis, or new wellbeing model is included in this audit.

## Remediation status — 2026-09-16

Implemented on the versioned **Low Mood v2.0.0 presentation contract** without changing the v1.3.1 coefficient calculation. Historical v1.3/v1.3.1 outputs and calibration observations remain retained; new current metadata carries its Low Mood algorithm version rather than overwriting the earlier output key.

* Low Mood is now presented as a non-diagnostic estimate of evidence that mood is **low or flat**, not as proof of happiness when the number is low.
* Detail view exposes the persisted score contributors, base/prior (30), calibration effect, input coverage, direct mood-evidence state and missing categories. It explicitly says that missing direct evidence is not positive mood evidence.
* The Life Balance-derived direction is no longer displayed as a Low Mood improvement claim. Until direct longitudinal mood trend evidence exists, the presentation is `INSUFFICIENT TREND EVIDENCE`.
* The weekly calibration question now asks **how low or flat the past week felt**. `0 = not low or flat`; `100 = extremely low or flat`; higher always means more low/flat mood. Legacy v1 wording still normalises with its historical reversed mapping.
* The calibration snapshot retains only structured contributor categories/effects and evidence state—never journal prose, provider payloads or location coordinates.
* Evidence hierarchy for future v2 research: direct clearly-worded James observations are strongest; repeated longitudinal observations are strong; sleep, Recovery, HRV, Life Balance, activity and chosen context are supporting; passive behaviour/place diversity is weak explanatory context; missed self-care, staying home, passive media, and low Stress/Anxiety/Time Pressure are not Low Mood evidence by themselves.
* Motivation, enjoyment, interest, inspiration and engagement remain an explicit research question—not a newly invented score or passive diagnosis.

Time Pressure now persists and presents an evidence state: `DIRECT`, `INFERRED`, or `NO_KNOWN_CONSTRAINT`. With no known fixed constraint and no fresh direct check-in, compact Today says **NO KNOWN PRESSURE · LIMITED EVIDENCE** rather than displaying the base 5 (or a calibration-adjusted 0) as a measured absence. A fresh direct `NOT AT ALL` may still show zero with its provenance. Time Ownership remains separate: constrained and unknown ownership are neither automatically Time Pressure nor automatically Personal Time.
