# Scheduled Commitments v1

## Contract

Scheduled Commitments are private planned evidence: they describe what is expected next, not what occurred. `OwnershipPeriod` remains actual semantic evidence, and Life Balance v2 consumes actual ownership only.

## Calendar and classification

Android Calendar is read-only (`READ_CALENDAR`). James selects contributing calendars. The bounded reader uses event instances, never recurrence masters, and stores no descriptions, attendees or notes. Timed BUSY instances may identify a fixed planned constraint; FREE and all-day entries do not.

Unknown planned ownership is valid. Manual event classification survives refresh/restart; a transparent local calendar-and-exact-title rule can classify similar future entries. A manual event correction wins. The optional Schedule screen is deliberately user-opened rather than an interruption loop: it supports event-level classification, a two-week “not now” cooldown and editable local rules. Classification explains ownership but does not decide whether an event contributes Time Pressure: a Personal cinema can still be approaching. It never pre-scores Life Balance.

`CalendarPromptPolicy` nominates only the nearest imminent timed BUSY unknown event, excludes all-day/FREE/answered/dismissed events, and permits one candidate at once. Existing Visit/Time Ownership controls are separate and unchanged.

## Shift Tracker

Shift Tracker is the Work authority. Rota is planned Work evidence only when Shift Tracker supplies explicit start/end shift fields; reminder rows are not invented into rota. Actual clock-in/out becomes actual Work ownership. Break and delivery transitions create concise factual activity context inside that Work period; they do not end Work or create Balance points. The explicit signature-protected v2 contract remains backwards-compatible with v1, retains a bounded local replay ledger and accepts out-of-order/idempotent events. James OS and Shift Tracker both continue independently if the other app is absent.

### Receiver discovery correction (2026-09-17)

James OS discovers only the stable Shift Tracker package and queries its protected reconciliation receiver without `MATCH_DEFAULT_ONLY`; broadcast receivers do not need the activity-launcher `CATEGORY_DEFAULT`. Android package visibility now declares the stable package explicitly. The Connections UI reports **Contract v2** and gives an install/receiver-availability diagnosis rather than claiming that the sender is "waiting for Part 2". Shift Tracker targets the actual stable James OS package (`uk.co.james.personal`) when publishing rota and factual work events; it must not target the Kotlin namespace (`uk.co.james`).

## Time Pressure, reconciliation and backup

Time Pressure v1.1.0 accepts eligible timed Scheduled Commitments as its next known fixed constraint. Only explicit preparation buffers reduce usable time; travel is never guessed. A schedule never writes actual ownership. Matching Visit/activity/manual evidence can support future reconciliation; missing GPS is not non-attendance.

Scheduled commitments, selection, classification and rule records use the ordinary backup records; provider schedule can be refreshed while confirmed actual facts remain preserved.
