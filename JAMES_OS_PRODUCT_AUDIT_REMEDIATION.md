# Product audit remediation status

**Audit snapshot preserved:** `JAMES_OS_PRODUCT_AUDIT.md` (2026-09-15)  
**Scope:** correctness, coherence and reliability remediation; not new data sources or predictive features.

## Status matrix

| Finding | Before | After | Status / evidence |
|---|---|---|---|
| Health Connect parity | HRV, oxygen saturation and respiratory-rate reads could not all be declared/requested | Single `HealthCapabilities` mapping supplies reads, runtime permissions and manifest declarations | Resolved; static parity test |
| Backup safety | Generic backup included rows but semantic coverage was weakly tested | Semantic interval shapes are validated; a populated semantic export imports into a clean state and a repeat import is idempotent | Resolved for persisted rows; Import Centre remains the only format |
| Visit merge evidence | Merge deleted the source Visit | Source is superseded, raw snapshots and reversible correction records retained; merge/split corrections can be reverted without deleting evidence | Resolved; correction/backup test |
| Legacy visit migration | Full legacy scan at every startup | Versioned watermark: first run/import reconciliation scans; normal startup requests only later evidence | Resolved; deterministic policy test |
| Semantic records | Quick actions created disconnected/ambiguous records | New Context, Activity, Ownership and Interruption rows attach to the live location anchor; the completed Visit retains that anchor ID. Legacy rows use a bounded compatibility join only | Resolved for current writes; historical legacy rows remain compatibility data |
| Ownership truth | No-obligation/activity/place could be misread as Personal | Explicit `OwnershipPeriod` ledger remains authoritative; Unknown is first class and coverage is calculated | Resolved for current semantic records |
| Life Balance | Could confuse no evidence with zero Personal time | Uses ownership ledger, evidence gate and coverage language | Resolved for current-day output |
| Timeline day model | Calendar date route could diverge from James Day; linked rows were separate moments | Timeline uses one pure James-Day filter and narrates explicitly linked Context/Activity/Ownership/Interruption evidence inside the Visit | Resolved; cross-midnight regression test |
| Unknown review | Unclear how to correct uncertain places/time | Places offers bounded unresolved Visit ownership review with one-tap classifications | Resolved for ownership; broader retrospective review is deferred |
| Map repeated pins | Repeated visits stacked on a point | Co-located Visit pins group into a review entry; map remains pin-based, no route trail | Resolved |
| Save current Place calibration | A low-power/balanced fused fix up to ±200m could fail with the misleading instruction to “try outdoors” | Existing timeline anchors are reused only when fresh (≤1 minute) and accurate (≤±30m); otherwise one explicit high-accuracy Fused fix is requested, then accepted/rejected with the actual age/accuracy/provider reason | Resolved in code; deterministic calibration-policy tests cover stale, inaccurate, timeout, provider failure and fresh-anchor cases |
| Provider freshness/reconciliation | Connected status could be interpreted as fresh evidence and source ranking was repeated | Connections and diagnostics distinguish not-synced, fresh, aging and stale; `SourcePolicy.rank` now supplies one display priority for equivalent provider metrics | Resolved; source metrics retain their own freshness policy |
| Nova | Prominent but no provider existed | Removed from primary navigation; implementation retained but not presented as working | Resolved |

## Authoritative semantic policy

- **Visit:** when and where.
- **Context:** situation.
- **Activity:** what James did.
- **Ownership:** who controlled the time (`PERSONAL`, `OBLIGATION`, `CONSTRAINED`, `WORK`, `UNKNOWN`).
- **Interruption:** a separately preserved disruption/resumption relationship.
- **Life Balance:** cautious interpretation of the ownership evidence, never a competing clock.

`HOME`, no scheduled obligation, a leisure place and an activity never create Personal time. `UNKNOWN` is not zero and no evidence is not bad evidence. Corrections preserve raw/provenance evidence and export with the existing Import Centre.

## Deliberately deferred

- Adaptive Wear 5-minute/30-second sampling.
- Calendar, weather, phone-usage context, WHOOP Age.
- Time-ownership correlations/trends, weekly review, predictions/anomaly alerts.
- Nova/provider integration and notification intelligence.
- Shift Tracker sender Part 2.

## Acceptance focus

The remaining work is real-device confirmation rather than another code path: exercise correction reversal, an overnight James Day and provider freshness wording on a populated installation. These are acceptance checks, not permission to reintroduce broad record observation or fabricate ownership evidence.
