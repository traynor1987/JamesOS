# Native migration audit — 9 September 2026

Status: debug APK built successfully in GitHub Actions run 34406810944, commit 305b87eb99e1d81aa611159bedc16db380735b08. Native unit tests, assembleDebug and lintDebug passed. No device installation or signed production AAB was tested.

## Audit of the previous application

| Existing element | Native treatment |
| --- | --- |
| RUT palette, cards, spacing, theme and James tabs | Recreated in Compose; no HTML rendered |
| Six RUT stores and shared James record envelopes | Indexed Room records retaining raw fields |
| RUT ledger, points, recovery links, stages | Kotlin validation and ledger functions |
| 72 existing event definitions | Exact copied JSON asset; seeded only when deliberately starting a new journey |
| Habits, schedules, completions, streaks | Kotlin functions, native edit dialogs and Room transactions |
| Backup validation, additive merges, conflict checks | Kotlin codec, Android document picker and transactional repository |
| Browser archives | Preserved as portable archived record rows |
| IndexedDB, browser permissions and lifecycle | Replaced by Room, Android permissions, ViewModel and WorkManager |
| PWA installation/update/service worker | Excluded with all legacy web source; replaced by Android build/installer |
| WHOOP, Shift/Gig and Nova | Explicit unconnected foundations; no fabricated integrations |

## Checks executed here

- Static architecture gate passed: active native source contains no WebView, browser storage, service worker, web manifest or destructive Room fallback.
- All Kotlin/Kotlin-DSL files parsed with tree-sitter Kotlin with no syntax errors. Parsing does **not** prove type resolution, Android API compatibility or Compose compiler acceptance.
- Android XML resources parsed successfully.
- GitHub workflow YAML parsed successfully.
- The asset contains all 72 source RUT definitions.
- Source audit corrected incorrect double conversion of Health Connect permission identifiers; Android 9-incompatible file reading; missing recovery-definition snapshots; empty heart-rate sample handling; weekly-reflection payload shape; lost browser archive rows; and activity-enabled state being set before registration succeeded.
- Eleven native JVM tests were added covering round trips, unknown fields, duplicates, conflict retention, second initial events, invalid backups/recoveries, archived payloads, scheduled streaks and overlap-safe time allocation. They passed through `testDebugUnitTest` in GitHub Actions.

## Data safety assessment

No user's RUT history was opened, imported or modified during implementation. Its original browser/app sandbox remains authoritative until the user explicitly imports a backup into the installed native app. Original web files are retained unchanged in the original source repository under `legacy-web/`; the Android build does not include them.

Imports validate before committing, preserve original input and a before-import snapshot, recheck the preview fingerprint, and apply additions with Room transactions. Existing same-ID conflicts are never silently overwritten. The same external ID is namespaced by source/type in the source policy. Health steps use native aggregate data; other health readings are not summed across overlap. Future Shift/Gig adapters must use the same IDs for cloud and backup records and require contract tests against real sample exports before being enabled.

Unknown backup fields and legacy archive payloads remain exportable. Android original-file archives are retained in full exports. Browser settings are retained; theme is applied. Other old preferences remain present but not every old browser-only preference has a native behavioural equivalent.

## Remaining acceptance gates

| Area | Remaining verification or implementation |
| --- | --- |
| Build | Passed on GitHub: native unit tests, debug APK assembly and Android lint. Health Connect compile requirement fixed by using API 36 / AGP 8.10.1 |
| Release | Configure stable signing secrets, build/install signed APK and validate an in-place upgrade |
| RUT parity | Compare native flows with original RUT on device; exact advanced charts, event reordering and all legacy preference behaviour are not yet complete |
| Real history | Import a copy of the user's backup; compare IDs/counts, scores, links, notes, date ranges and exports; test rejection leaves data unchanged |
| Fold/UI | Folded/unfolded widths, font scaling, rotation, process death, scroll state and dialogs require emulator/device checks |
| Background | Verify geofences/activity transitions while locked, Doze, reboot, permission revocation and Samsung battery restrictions |
| Persistence | Test Room transactions under failure, exported restore and uninstall warning; no destructive migration path exists |
| File picker | Device document providers, cancellation, 40 MB boundary, disk-full errors and process recreation |
| Health Connect | Device permission flow, denial/revocation, installed-provider states, original source and aggregation accuracy |
| Offline | Routine flows are local; test airplane mode and network failure. Health/release failure is surfaced; no polling retry loop exists |
| Sync | WorkManager/backoff and read-only interfaces exist. WHOOP OAuth, Shift cloud, Gig adapters, source reconciliation and authenticated secrets remain future work |
| Notifications/sensors | Not implemented; unnecessary permissions are not requested |
| State/insights/Nova | Reports and correction persistence exist; inference training, clinical claims and fake answers are absent |

This checkpoint must not be represented as a completed, tested Android migration until these release gates are addressed. The existing web deployment was not replaced or republished as the Android app.
