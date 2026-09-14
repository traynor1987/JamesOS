# James OS Wear handover

## Architecture

`:app` remains the authoritative database, Body Battery engine, WHOOP/Health Connect client and GitHub update controller. `:wear` is a native Compose for Wear OS app and passive sensor node. Both APKs use `uk.co.james.personal` and the same signing certificate because Google Play services Data Layer only pairs matching trusted app identities; they have independent module namespaces and version codes and install on different device classes.

The watch persists the last phone snapshot plus an offline observation queue in Room. A disconnected watch remains glanceable and continues passive collection. It never receives WHOOP credentials, OAuth tokens, GitHub tokens, private notes or raw location history.

## Versioned Data Layer protocol

The existing additive v1 snapshot is retained:

| Path | Direction | Purpose |
|---|---|---|
| `/james/v1/snapshot` | phone → watch DataItem | Cached reserve, health, routine and timeline summary |
| `/james/v1/status` | watch → phone Message | Device/version/sensor/queue status |
| `/james/v1/observations` | watch → phone Message | At most 50 stable-ID sensor observations |
| `/james/v1/observations/ack` | phone → watch Message | IDs safely persisted by the phone |
| `/james/v1/update/control` | phone → watch Message | Version, byte length and SHA-256 before transfer |
| `/james/v1/update/apk` | phone → watch Channel | Wear APK byte stream |
| `/james/v1/update/status` | watch → phone Message | Transfer/verification/install-ready state |
| `/james/v1/update/open-ready` | phone → watch Message | Reopens a verified cached update; never retransfers bytes |

Unknown additive JSON fields are ignored. `schemaVersion=1` is checked and retained in every payload. New incompatible formats require `/james/v2/...`; do not mutate v1 meanings.

The watch advertises the `james_wear_v1` capability. Phone connection and update targeting use that reachable capability, rather than assuming that any paired Wear node has James OS installed.

## Sensor and battery strategy

`HealthServicesProvider` capability-checks and registers `PassiveMonitoringClient` only for reported heart-rate, daily-step, calorie and distance types. `JamesPassiveDataService` writes timestamped observations and triggers a small batched upload. It does not hold a high-frequency listener, poll GPS or contact GitHub. The queue retains seven days and uploads 50 records per batch. Every successful acknowledgement immediately drains the next batch until the queue is empty, while a process-wide mutex prevents overlapping sends. The phone deduplicates using stable external IDs before acknowledgement.

Wear `0.1.4` bundles Samsung Health Sensor API `1.4.1` for this private, developer-mode-enabled Galaxy Watch deployment. `SamsungHealthSensorProvider` connects to Health Sensor Service and records the tracker types the connected watch actually reports (including supported heart-rate, PPG, EDA, temperature, SpO₂, ECG, motion or BIA types). It does not claim values for absent trackers and does not start high-rate Samsung streams in the background. Health Services passive monitoring remains the reliable, battery-efficient fallback and source of ordinary HR/steps observations. The SDK has no additional runtime permission of its own; James requests only Body Sensors and Activity Recognition for the passive features it enables.

Wear `0.1.3` keeps heart rate and James Stress as first-class monitor cards beneath the reserve/recovery/sleep/strain summary. Heart values distinguish recent watch observations from older last-measured values. Stress only uses heart readings from the latest 30 minutes and recent movement context, so an old reading cannot masquerade as a current stress estimate. Detail, activity, sleep and settings screens use the same charcoal-card visual system. The update action is a full-width James control rather than a stock round Wear button. Android back returns a detail screen to watch Home and is consumed on Home rather than unexpectedly closing James OS. Wear `0.1.4` upgrades Settings diagnostics from a placeholder to the live Samsung Sensor Service connection state. Wear `0.1.5` turns the raw tracker dump into concise truthful sensor groups and makes a verified update persist on the watch until Android reports replacement. Wear `0.1.6` adds an explicit 45-second Samsung sensor check: only derived heart rate, RMSSD HRV, skin conductance, skin temperature and the experimental James Stress result are stored and synchronized. It selects only reported HR/EDA/skin-temperature trackers, stops every listener after the check, and leaves Health Services as the 24/7 passive source. Raw PPG, ECG, location and credentials never leave the watch. Wear `0.1.7` fixes a race between receiving a verified APK and rendering the update overlay: READY now always exposes INSTALL UPDATE and rechecks the saved APK state after every update transition.

## Update protocol

The phone reads the latest release, downloads `james-wear.apk`, validates `james-wear.apk.sha256`, package ID, version code and signing certificate, then caches it. It sends the small control message before opening a Channel. The watch streams to `incoming.part` with a 100 MB limit, checks exact byte count and SHA-256, atomically renames to `james-wear.apk`, and exposes Install. Android's package installer always requires the supported user confirmation; James never claims installation succeeded merely because transfer completed.

Interrupted transfers delete the partial file. Retry reuses the verified phone cache. Abandoned watch partials are removed on reboot/package replacement and replaced at the next transfer. Once the receiving watch verifies the checksum, it saves the SHA-256 and APK locally until package replacement. James OS automatically opens on the watch when the phone begins a transfer, keeps the receiving overlay visible, and exposes **Try install again** after any missed Android installer prompt. Phone Settings also has **Install saved update v…**, which merely reopens that locally verified watch package—no download or retransmission. The watch reports `AWAITING_CONFIRMATION` only after it launches Android's installer; after a successful package replacement the new app process reports `UPDATED` to the phone. The watch never accesses GitHub.

## Release/signing

GitHub Actions builds and tests both modules. Permanent releases produce:

- `james.apk`, `james.aab`, `james.apk.sha256`
- `james-wear.apk`, `james-wear.apk.sha256`
- `james-version.json`, `james-wear-version.json`
- `signature.txt`, `wear-signature.txt`

Both certificate digests are compared with `signing/certificate.sha256`. The first release was Wear `0.1.0`; the reliability pass was `0.1.1`; the unified monitor UI was `0.1.2`; the updater and diagnostics polish is `0.1.3`. Increment `JAMES_WEAR_VERSION_NAME` for every feature release. Never replace the keystore or users cannot update either installed app.

## Diagnostics and limitations

Watch Settings reports version, schema, phone age, queue count, passive state, Health Services capabilities, compact Samsung Sensor Service connection/version/capability groups and last transfer result. Phone Settings reports the last real status message, update state and offers the saved-update install handoff.

Known v1 limitations:

- The phone remains authoritative for Body Battery and sleep; no custom sleep-stage classifier exists.
- Health Services delivery cadence and supported metrics vary by watch/OEM and Android may batch them.
- Samsung Sensor Service needs Developer Mode / the Samsung service on the paired watch. Its enhanced trackers are capability-detected only in 0.1.4; future explicit sessions may opt into reading supported IBI/PPG/EDA/temperature data.
- Stress is a conservative experimental estimate, not Samsung Stress and not a diagnosis.
- The system package installer controls confirmation. James reports `UPDATED` only after Android broadcasts that its package was replaced; cancelling the system confirmation leaves the honest `awaiting confirmation` state.
- Home explicitly labels snapshots older than two hours as stale. The complication marks stale data with a dot; Tile and complication refresh cadence is intentionally battery-limited. Tapping either opens James OS.

## Next milestone

Run the device matrix on a physical Galaxy Watch: permissions denied/granted, Samsung service disconnected/connected, capability detection after a reboot, 24-hour passive delivery, phone disconnect/reconnect, duplicate ACK, interrupted Channel transfer and installer confirmation. Next, add an explicit, user-enabled Samsung high-detail collection session only for tracker types the Diagnostics screen confirms.
