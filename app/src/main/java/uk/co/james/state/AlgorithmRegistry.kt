package uk.co.james.state

import java.time.LocalDate

/** Central, versioned registry for every James-authored derived-score engine. */
enum class AlgorithmStatus { LEARNING, EXPERIMENTAL, CALIBRATING, ACTIVE, DEPRECATED }

data class AlgorithmChange(
    val version:String,
    val date:String,
    val title:String,
    val details:List<String>
)

data class AlgorithmDefinition(
    val id:String,
    val displayName:String,
    val algorithmVersion:String,
    val calibrationVersion:String,
    val status:AlgorithmStatus,
    val description:String,
    val inputSchemaVersion:String,
    val outputSchemaVersion:String,
    val updated:String,
    val supportsHistoricalRecalculation:Boolean,
    val inputs:List<String>,
    val changes:List<AlgorithmChange>,
    val calibrationSchemaVersion:String="calibration-schema-v1",
    val calibrationCompatibleAlgorithmVersions:Set<String> = setOf(algorithmVersion)
)

object JamesAlgorithmRegistry {
    const val BODY_BATTERY_VERSION="2.0.7"
    const val BODY_BATTERY_CALIBRATION="1.0.0"
    const val STRESS_VERSION="1.0.0"
    const val WELLBEING_VERSION="1.6.0"
    const val ANXIETY_VERSION="1.3.0"
    const val LOW_MOOD_VERSION="2.0.0"
    const val SLEEPINESS_VERSION="1.0.0"
    const val SLEEPINESS_CALIBRATION="1.0.0"
    const val LIVE_ENERGY_VERSION="1.2.0"
    const val SUSTAINABILITY_VERSION="1.1.0"
    const val CRASH_RISK_VERSION="1.1.0"
    const val TIME_PRESSURE_VERSION="1.0.0"
    const val CONTEXT_LOAD_VERSION="1.0.0"
    const val LIFE_BALANCE_VERSION="1.0.1"
    const val CALIBRATION_ENGINE_VERSION="1.0.3"

    /** Explicit derived-score dependency graph used by calibration back-tests. */
    val dependencies:Map<String,Set<String>> = mapOf(
        "mental_reserve" to setOf("body_battery","james_stress","anxiety_load","context_load","life_balance","sleepiness"),
        "anxiety_load" to setOf("james_stress"),
        "low_mood_load" to setOf("life_balance"),
        "live_energy" to setOf("body_battery","mental_reserve","james_stress","sleepiness"),
        "energy_sustainability" to setOf("live_energy","body_battery","mental_reserve","sleepiness"),
        "crash_risk" to setOf("energy_sustainability","live_energy","body_battery","mental_reserve")
    )

    fun dependencyCycle():List<String>? {
        val visiting=mutableSetOf<String>();val visited=mutableSetOf<String>();val path=mutableListOf<String>()
        fun visit(id:String):List<String>? {
            if(id in visiting)return (path.dropWhile {it!=id}+id)
            if(!visited.add(id))return null
            visiting+=id;path+=id
            dependencies[id].orEmpty().forEach {dependency->visit(dependency)?.let {return it}}
            path.removeAt(path.lastIndex);visiting-=id
            return null
        }
        return dependencies.keys.firstNotNullOfOrNull(::visit)
    }

    val entries:List<AlgorithmDefinition> = listOf(
        AlgorithmDefinition(
            id="body_battery", displayName="James Body Battery",
            algorithmVersion=BODY_BATTERY_VERSION, calibrationVersion=BODY_BATTERY_CALIBRATION,
            status=AlgorithmStatus.ACTIVE,
            description="Estimates James's remaining physical and physiological Reserve during a James Day. WHOOP Day Strain uses a nonlinear exertion curve, with recovery-aware but bounded adjustment.",
            inputSchemaVersion="body-inputs-v1", outputSchemaVersion="body-battery-v2",
            updated="2026-09-12", supportsHistoricalRecalculation=true,
            inputs=listOf("WHOOP Recovery","WHOOP Sleep","WHOOP Day Strain","HRV","Resting heart rate","James Stress","Activity","Rest"),
            changes=listOf(
                AlgorithmChange(
                    BODY_BATTERY_VERSION,"2026-09-12","P0 James Day and persistence correctness",
                    listOf("Scoped repository Strain monotonicity to one immutable James Day and WHOOP cycle.","Added idempotent reconciliation for poisoned legacy current-day Strain without rewriting history.","Applied one completed-main-sleep James Day window to steps, exercise and time context.","Made context time units explicit as seconds with one conversion to hours. Calibration is unchanged.")
                ),
                AlgorithmChange(
                    "2.0.6","2026-09-12","Accept WHOOP open cycle across the sleep transition",
                    listOf("Fixed current WHOOP Strain remaining pending when the open cycle starts inside the accepted main-sleep interval.","Closed previous cycles remain rejected; only an open cycle spanning the exact accepted sleep transition may use this rule.","The 3.3 current-cycle case is covered by regression testing. Calibration is unchanged.")
                ),
                AlgorithmChange(
                    "2.0.5","2026-09-12","Scope persisted Strain to the current James Day and WHOOP cycle",
                    listOf("Fixed persisted previous-cycle WHOOP Strain leaking across James Day boundaries.","Legacy/unscoped current-day Strain cache is invalidated idempotently while historical records remain untouched.","Monotonic protection now compares values only inside the same completed-sleep boundary and WHOOP cycle.","WHOOP sync now distinguishes API receipt, current-cycle acceptance and Body Battery consumption. Calibration is unchanged.")
                ),
                AlgorithmChange(
                    "2.0.4","2026-09-12","Require post-sleep WHOOP cycle start",
                    listOf("A recovery or sleep cycle ID no longer makes a pre-sleep Strain record current by itself.","WHOOP Strain must start on or after the completed main-sleep boundary before it can appear or debit Reserve.","The observed matching-cycle 6.7 carryover is covered by regression testing. Calibration is unchanged.")
                ),
                AlgorithmChange(
                    "2.0.3","2026-09-12","Strict current-day WHOOP Strain ownership",
                    listOf("The dashboard now displays only Body Battery's validated current-day WHOOP Strain, never a generic latest record.","Legacy WHOOP Strain rows without cycle ownership must start after the accepted main-sleep boundary; otherwise Strain remains pending.","Foreground sync migrates unowned WHOOP Strain rows before they can be displayed. Calibration is unchanged.")
                ),
                AlgorithmChange(
                    "2.0.2","2026-09-12","WHOOP cycle and James Day ownership",
                    listOf("Previous-day WHOOP Strain is now rejected after a completed main sleep establishes a new James Day.","WHOOP metrics retain cycle IDs, source start/end, source update and ingestion timestamps.","Current-day Strain stays pending at zero until WHOOP supplies the active cycle.","WHOOP sync now records domain watermarks and runs on foreground staleness plus a 30-minute best-effort background cadence. Calibration is unchanged.")
                ),
                AlgorithmChange(
                    "2.0.1","2026-09-12","Intraday Reserve state reconciliation",
                    listOf("Locked the first valid morning Reserve for each James Day so later source refreshes cannot recharge it.","Prevented non-restorative Reserve increases from stale snapshots, observer races and source reordering.","Awake-time depletion now continues deterministically across refreshes, backgrounding and process recreation.","Added persisted calculation traces and diagnostic detail. Calibration is unchanged.")
                ),
                AlgorithmChange(
                    "2.0.0","2026-09-11","Nonlinear WHOOP Day Strain",
                    listOf("Preserved the initial anchor: 6.7 Strain is about 7 Reserve points.","Higher Strain now drains Reserve progressively faster.","Added incremental accounting, stale-value protection and source reconciliation.","Added bounded recovery readiness adjustment and diagnostics.")
                )
            )
        ),
        AlgorithmDefinition("james_stress","James Stress",STRESS_VERSION,"1.0.0",AlgorithmStatus.EXPERIMENTAL,
            "A short, opt-in physiological wellbeing estimate from supported watch readings. Not Samsung Stress or a diagnosis.",
            "stress-inputs-v1","stress-output-v1","2026-09-10",true,
            listOf("Heart rate","HRV","EDA","Skin temperature","Activity context"),
            listOf(AlgorithmChange(STRESS_VERSION,"2026-09-10","Initial transparent model",listOf("Manual 45-second watch checks and passive context.")))
        ),
        AlgorithmDefinition("mental_reserve","Mental Reserve",WELLBEING_VERSION,"1.0.0",AlgorithmStatus.LEARNING,
            "A personal wellbeing capacity estimate. It is not a mental-health diagnosis.",
            "wellbeing-inputs-v1","wellbeing-output-v1","2026-09-11",true,
            listOf("Body Battery","James Stress","Sleep","Recovery","Context Load","Life Balance","Activity","Optional check-ins","Observed nutrition/hydration response"),
            listOf(
                AlgorithmChange(WELLBEING_VERSION,"2026-09-13","Bounded Sleepiness context",listOf("Adds at most two points of reconciled Sleepiness cost to Mental Reserve.","Reduces that contribution when Body Battery already carries the same sleep evidence.","Existing calibration schema remains compatible.")),
                AlgorithmChange("1.5.0","2026-09-13","Observed nutrition response",listOf("A meal, hydration or caffeine event alone has no Mental Reserve effect.","A separately recorded improved Energy response can add only +1 (possible) or +2 (repeated) experimental context.","This is an association, not causation; Body Battery, Anxiety and James Stress remain unchanged.")),
                AlgorithmChange("1.3.0","2026-09-12","Physiology freshness semantics",listOf("Fast and daily physiology now loses confidence and influence according to source-specific freshness windows.","Implausible future observations are excluded rather than becoming permanently latest.","Calibration values are unchanged.")),
                AlgorithmChange("1.2.1","2026-09-12","Contemporaneous Anxiety context",listOf("Mental Reserve now consumes Anxiety v1.2.1, whose exertion suppression is tied to the physiological reading. Low-Mood longitudinal exercise behaviour and all calibration values are unchanged.")),
                AlgorithmChange("1.2.0","2026-09-11","Corrected contribution direction",listOf("Fixed shared load-score sign handling: helpful HRV, Recovery and adequate sleep no longer raise Low-Mood Load.","Low-Mood physiological inputs now use a short rolling window rather than a single fresh reading.","Added direct optional energy context for Mental Reserve.")),
                AlgorithmChange("1.0.0","2026-09-11","Initial personal baseline",listOf("Learns from James's own history."))
            ),
            calibrationCompatibleAlgorithmVersions=setOf("1.5.0",WELLBEING_VERSION)
        ),
        AlgorithmDefinition("anxiety_load","Anxiety Load",ANXIETY_VERSION,"1.0.0",AlgorithmStatus.LEARNING,
            "A fast-moving personal physiological/contextual load estimate. Not an anxiety diagnosis.",
            "wellbeing-inputs-v1","wellbeing-output-v1","2026-09-11",true,
            listOf("James Stress","Heart rate","HRV","Sleep","Recovery","Activity context"),
            listOf(
                AlgorithmChange(ANXIETY_VERSION,"2026-09-12","Source-specific freshness",listOf("James Stress and spot physiology decay after their live window and become unavailable when stale.","Daily WHOOP Sleep, Recovery, HRV and RHR use a separate daily cadence.","Missing, stale and implausibly future inputs never add Anxiety load; traces expose age and multiplier. Calibration is unchanged.")),
                AlgorithmChange("1.2.1","2026-09-12","Contemporaneous exertion suppression",listOf("Replaced the previous any-exercise-in-14-days suppression with activity overlapping the reading or a bounded 2-hour post-exercise window.","Step movement context is limited to the 30 minutes around the physiological reading.","Longitudinal exercise benefits remain unchanged. Calibration is unchanged.")),
                AlgorithmChange("1.2.0","2026-09-11","Bounded physiological evidence",listOf("Added tanh diminishing returns and contributor-specific caps so one unusual physiological measurement cannot decide Anxiety alone.","HRV baselines are now source/context aware: WHOOP overnight and Samsung sensor-check HRV are not mixed.","Diagnostics now retain source, context, raw contribution and post-cap contribution.")),
                AlgorithmChange("1.1.0","2026-09-11","Corrected contribution direction",listOf("Fixed shared load-score sign handling: higher HRV/Recovery now reduces Anxiety Load while elevated Stress/RHR increases it.","Sleep is now shortfall/context based; longer sleep alone does not add anxiety.","Exercise context suppresses exertion-like Stress, HRV and heart-rate effects.","Optional current anxiety reports are included as bounded calibration context.")),
                AlgorithmChange("1.0.0","2026-09-11","Initial personal baseline",listOf("Exercise context is protected from being treated as anxiety."))
            )
        ),
        AlgorithmDefinition("low_mood_load","Low Mood",LOW_MOOD_VERSION,"1.0.0",AlgorithmStatus.LEARNING,
            "A non-diagnostic, slow-moving estimate of evidence that James's mood is low or flat. Direct James feedback is the ground truth; physiology and context are supporting evidence, not a diagnosis.",
            "wellbeing-inputs-v1","wellbeing-output-v1","2026-09-11",true,
            listOf("Rut","Sleep","Recovery","HRV","Activity","Personal time","Optional check-ins"),
            listOf(
                AlgorithmChange(LOW_MOOD_VERSION,"2026-09-16","Evidence-first mood explanation",listOf("The v1.3.1 score calculation and historical outputs remain preserved.","The current estimate now makes missing direct mood evidence, prior contribution, calibration effect and confidence limitations explicit.","Life Balance direction is no longer presented as a Low Mood improvement claim; direct low/flat feedback uses an unambiguous higher-means-more-low/flat scale.")),
                AlgorithmChange("1.3.1","2026-09-16","Corrected Life Balance trend arithmetic",listOf("A parenthesisation error made a stable 7-day and 14-day Life Balance score look like improvement. The correction changes only the descriptive Life Balance-derived trend label; Low-Mood Load score inputs, score, calibration and historical evidence are unchanged.")),
                AlgorithmChange("1.2.0","2026-09-11","Corrected contribution direction",listOf("Fixed shared load-score sign handling for HRV and Recovery.","Sleep is shortfall based: longer sleep alone cannot worsen Low-Mood Load.","Physiological trend inputs now use a rolling window; one fresh reading cannot cause a large Low-Mood movement.","Rut movement toward neutral remains an improvement.")),
                AlgorithmChange("1.0.0","2026-09-11","Initial personal baseline",listOf("One bad day and healthy rest are deliberately not treated as a trend."))
            ),
            calibrationCompatibleAlgorithmVersions=setOf("1.3.0","1.3.1",LOW_MOOD_VERSION)
        ),
        AlgorithmDefinition("sleepiness","James Sleepiness",SLEEPINESS_VERSION,SLEEPINESS_CALIBRATION,AlgorithmStatus.LEARNING,
            "An experimental personal estimate of James's current propensity or drive to sleep. Lower is better; it is not a diagnosis.",
            "sleepiness-inputs-v1","sleepiness-output-v1","2026-09-13",true,
            listOf("Accepted main sleep","Recent sleep history","Time awake","Naps","Local time","Structured caffeine context","James Sleepiness feedback"),
            listOf(AlgorithmChange(SLEEPINESS_VERSION,"2026-09-13","Initial conservative sleep-pressure model",listOf("Separates bounded underlying sleep pressure from temporary nap/caffeine modifiers.","Uses the accepted-main-sleep James Day rather than midnight.","Supports versioned traces, bounded calibration and historical replay.")))
        ),
        AlgorithmDefinition("live_energy","Live Energy",LIVE_ENERGY_VERSION,"1.0.0",AlgorithmStatus.LEARNING,
            "A fast-moving estimate of how energetic, alert or switched-on James appears right now. It is subjective wellbeing estimation, not metabolic or medical measurement.",
            "right-now-inputs-v1","right-now-output-v1","2026-09-13",true,
            listOf("Energy check-in","Body Battery","Mental Reserve","Sleepiness","Recovery","Fresh James Stress","Movement","Nutrition/hydration/caffeine context"),
            listOf(AlgorithmChange(LIVE_ENERGY_VERSION,"2026-09-13","Sleepiness integration",listOf("Adds bounded Sleepiness context without forcing Live Energy to be its inverse.","High current Energy and high Sleepiness remain valid together.","Existing calibration schema remains compatible.")),AlgorithmChange("1.1.0","2026-09-13","Nutrition context foundation",listOf("Meals, hydration and caffeine are neutral contextual evidence until James’s own check-ins show a response.","Makes James’s own Energy check-in the strongest calibration signal.","Does not recharge Body Battery or claim glucose/metabolic measurement."))),
            calibrationCompatibleAlgorithmVersions=setOf("1.1.0",LIVE_ENERGY_VERSION)
        ),
        AlgorithmDefinition("energy_sustainability","Energy Sustainability",SUSTAINABILITY_VERSION,"1.0.0",AlgorithmStatus.EXPERIMENTAL,
            "Estimates how well current Live Energy is supported by physical Reserve, Mental Reserve, Sleepiness and recovery.",
            "right-now-inputs-v1","right-now-output-v1","2026-09-13",true,
            listOf("Live Energy","Body Battery","Mental Reserve","Sleepiness","Recovery","Temporary boost context"),
            listOf(AlgorithmChange(SUSTAINABILITY_VERSION,"2026-09-13","Sleepiness-supported sustainability",listOf("Uses Sleepiness once as a bounded support input and removes duplicate direct sleep/wake charges.")),AlgorithmChange("1.0.0","2026-09-13","Initial support model",listOf("Keeps temporary energy separate from underlying support.","Uses bounded penalties when Live Energy substantially exceeds Reserve."))),
            calibrationCompatibleAlgorithmVersions=setOf("1.0.0",SUSTAINABILITY_VERSION)
        ),
        AlgorithmDefinition("crash_risk","Crash Risk",CRASH_RISK_VERSION,"1.0.0",AlgorithmStatus.EXPERIMENTAL,
            "An experimental estimate that current energy may be less sustainable. It never claims that an energy crash will occur.",
            "right-now-inputs-v1","right-now-output-v1","2026-09-13",true,
            listOf("Energy Sustainability","Live Energy–Reserve gap","Reconciled Sleepiness context","Temporary boost context"),
            listOf(AlgorithmChange(CRASH_RISK_VERSION,"2026-09-13","Reconciled Sleepiness input",listOf("Sleepiness influences Crash Risk through Sustainability so short sleep is not counted twice.")),AlgorithmChange("1.0.0","2026-09-13","Initial bounded estimate",listOf("Expresses possibility rather than certainty.","Missing inputs lower confidence instead of adding risk."))),
            calibrationCompatibleAlgorithmVersions=setOf("1.0.0",CRASH_RISK_VERSION)
        ),
        AlgorithmDefinition("time_pressure","Time Pressure",TIME_PRESSURE_VERSION,"1.0.0",AlgorithmStatus.LEARNING,
            "Estimates pressure from usable personal time running out before the next known meaningful constraint. It is separate from Anxiety Load.",
            "time-pressure-inputs-v1","right-now-output-v1","2026-09-13",true,
            listOf("Next fixed constraint","Known preparation/travel buffer","Usable personal window","Time That Was Mine","Optional time-pressure check-in"),
            listOf(AlgorithmChange(TIME_PRESSURE_VERSION,"2026-09-13","Initial James-specific time model",listOf("Uses the accepted-main-sleep James Day window.","Only known buffers reduce usable time; travel is never invented.","Does not feed Anxiety and leaves Mental Reserve semantics unchanged in this initial learning release.")))
        ),
        AlgorithmDefinition("context_load","Context Load",CONTEXT_LOAD_VERSION,"1.0.0",AlgorithmStatus.EXPERIMENTAL,
            "An acute, private estimate of how mentally demanding the currently recorded context may be. It is not a diagnosis and never treats a location as wellbeing.",
            "context-periods-v1","context-load-v1","2026-09-13",true,
            listOf("Personal/Neutral/Obligation","Difficult intervals","Factual interactions","Duration"),
            listOf(AlgorithmChange(CONTEXT_LOAD_VERSION,"2026-09-13","Initial bounded context model",listOf("Obligation alone is limited and does not mean misery.","Difficult intervals are preserved separately; multiple intervals do not flatten a whole visit.","Unknown context has no penalty.")))
        ),
        AlgorithmDefinition("life_balance","Life Balance",LIFE_BALANCE_VERSION,"1.0.0",AlgorithmStatus.LEARNING,
            "A rolling 7-, 14- and 28-day personal-time and lived-life trend. It evolves Rut without rewriting historic point records.",
            "life-balance-facts-v1","life-balance-v1","2026-09-13",true,
            listOf("Personal time","Obligation time","Difficult context","Chosen activities"),
            listOf(
                AlgorithmChange(LIFE_BALANCE_VERSION,"2026-09-16","Corrected 7/14-day trend arithmetic",listOf("A stable 7-day and 14-day score is now reported as stable rather than improvement. The rolling score and its underlying ownership evidence are unchanged.")),
                AlgorithmChange("1.0.0","2026-09-13","Rolling factual foundation",listOf("Uses bounded rolling patterns, not lifetime point accumulation.","Recent improvement progressively displaces older difficult periods.","Historical Rut remains a separate preserved ledger."))
            ),
            calibrationCompatibleAlgorithmVersions=setOf("1.0.0",LIFE_BALANCE_VERSION)
        ),
        AlgorithmDefinition("calibration_engine","James Calibration Engine",CALIBRATION_ENGINE_VERSION,"1.0.0",AlgorithmStatus.ACTIVE,
            "Shared local-first infrastructure that measures deterministic score agreement, generates bounded candidates and preserves James approval.",
            "calibration-input-v1","calibration-analysis-v1","2026-09-13",true,
            listOf("Explicit structured feedback","Versioned input snapshots","Algorithm parameter schemas","Historical back-tests"),
            listOf(
                AlgorithmChange(CALIBRATION_ENGINE_VERSION,"2026-09-13","Sleepiness registration",listOf("Registers structured Sleepiness feedback, bounded parameters and versioned snapshots in the existing audited engine.","Retains manual activation, back-test and rollback safety; no score changes from one observation.")),
                AlgorithmChange("1.0.2","2026-09-13","Atomic persistence and stale-candidate audit",listOf("Binds candidates and back-tests to an immutable evidence dataset, base calibration, dependency versions and Calibration Engine version.","Makes activation, rollback, default restore and first default-profile preservation transactional in the repository.","Adds idempotent feedback submission, interrupted-analysis recovery and strict calibration backup/import validation.")),
                AlgorithmChange("1.0.1","2026-09-13","Post-implementation safety audit",listOf("Validates calibration identities, schemas, finite values and compatible algorithm versions before use.","Preserves long-lived active profiles in bounded production state and invalidates candidates when evidence changes.","Adds deterministic candidate identity, context regression guards and complete v2 evidence snapshots.")),
                AlgorithmChange("1.0.0","2026-09-13","Initial calibration platform",listOf("Collects direct and eligible structured check-in evidence.","Evaluates MAE, bias, coverage and context slices.","Requires bounded validation and manual activation; no LLM controls production scoring."))
            )
        ),
        AlgorithmDefinition("rut","Rut","1.0.0","1.0.0",AlgorithmStatus.ACTIVE,
            "James's existing event-led Rut score and history.", "rut-inputs-v1","rut-output-v1","2026-09-01",true,
            listOf("Recorded life events","Recoveries","Day reviews"),
            listOf(AlgorithmChange("1.0.0","2026-09-01","Registry adoption",listOf("Existing Rut history is preserved.")))
        )
    )

    fun get(id:String)=entries.firstOrNull { it.id==id }
}
