# Product audit remediation status

**Audit snapshot preserved:** `JAMES_OS_PRODUCT_AUDIT.md` (2026-09-15)  
**Scope:** correctness, coherence and reliability remediation; not new data sources or predictive features.

## Status matrix

| Finding | Before | After | Status / evidence |
|---|---|---|---|
| Health Connect parity | HRV, oxygen saturation and respiratory-rate reads could not all be declared/requested | Single `HealthCapabilities` mapping supplies reads, runtime permissions and manifest declarations | Resolved; static parity test |
| Backup safety | Generic backup included rows but semantic coverage was weakly tested | Round-trip fixtures include visits, correction evidence, places, ownership, context, activity, interruption, routines, journal, check-in, settings and calibration | Resolved for persisted rows; Import Centre remains the only format |
| Visit merge evidence | Merge deleted the source Visit | Source is superseded, raw snapshots and reversible correction records retained | Resolved; correction/backup test |
| Legacy visit migration | Full legacy scan at every startup | Versioned watermark: first run/import reconciliation scans; normal startup requests only later evidence | Resolved; deterministic policy test |
| Semantic records | Quick actions created disconnected/ambiguous records | Context, activity, ownership and interruption retain anchor/James-Day/context links while staying separate dimensions | Partially resolved; historical legacy rows remain compatibility data |
| Ownership truth | No-obligation/activity/place could be misread as Personal | Explicit `OwnershipPeriod` ledger remains authoritative; Unknown is first class and coverage is calculated | Resolved for current semantic records |
| Life Balance | Could confuse no evidence with zero Personal time | Uses ownership ledger, evidence gate and coverage language | Resolved for current-day output |
| Timeline day model | Calendar date route could diverge from James Day | Timeline uses selected James-Day bounds with bounded neighbouring sleep context | Resolved; needs real-device cross-midnight confirmation |
| Unknown review | Unclear how to correct uncertain places/time | Places offers bounded unresolved Visit ownership review with one-tap classifications | Resolved for ownership; broader retrospective review is deferred |
| Map repeated pins | Repeated visits stacked on a point | Co-located Visit pins group into a review entry; map remains pin-based, no route trail | Resolved |
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

## Remaining acceptance focus

The semantic foundation is implemented, but it still needs James's populated-device acceptance for cross-midnight Timeline grouping, correction ergonomics and provider freshness wording. Those are validation/targeted follow-up risks, not permission to reintroduce broad record observation or fabricate ownership evidence.
