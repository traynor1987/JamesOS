# James — native Android migration

James now has a Kotlin/Jetpack Compose Android application at the project root. The Android build has no JavaScript runtime, WebView, browser routing, web manifest, service worker or Chrome dependency. The debug APK has been compiled in GitHub Actions, with native unit tests and Android lint passing. Device/Fold testing remains outstanding.

## Build

Open this directory in Android Studio with Java 17, Android SDK 36 and Gradle 8.11.1. AGP is 8.10.1. This environment could not download the Gradle distribution and has no Android SDK. A Gradle wrapper binary has therefore not been fabricated; generate the standard wrapper using an installed Gradle 8.11.1 (`gradle wrapper --gradle-version 8.11.1`), then commit it after reviewing the generated files.

Commands with Gradle installed:

```
gradle testDebugUnitTest assembleDebug lintDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Debug installs separately as `uk.co.james.personal.debug`. Release: `uk.co.james.personal`. Do not uninstall an installed release to update it: use the same signing key, package ID and increasing version code.

## GitHub release builds

Public validation runs tests, lint and unsigned phone builds without secrets. Signed releases are manual and main-only: the protected `james-release` environment reconstructs the existing signing identity only in runner temporary storage, verifies the expected certificate before signing and verifies the resulting APK afterwards.

This public repository contains no signing keystore. Before a signed release, add `JAMES_SIGNING_KEYSTORE_BASE64` and `JAMES_SIGNING_PASSWORD` as `james-release` environment secrets. Do not place their values in source, issues, workflow logs or chat. The Samsung Wear AAR is also external: see [Wear setup](wear/README.md).

Release assets: `james.apk`, `james.aab`, `james.apk.sha256`, `james-version.json`, `signature.txt`. Version codes increase with the Android workflow run number; preserve that sequence.

Settings → Update App uses public GitHub Releases without sign-in. The updater retains HTTPS, checksum, package, version and installed-signer checks before install.

## Preserve your history

The old web implementation remains in the original James source repository under `legacy-web/` and tag `james-web-before-native`. This dedicated GitHub repository contains the native project. No web assets are required by its build. Existing RUT/browser data was not erased, reset, opened or modified by this migration.

Android cannot read another application's or browser's private database. Export a full backup from existing James/RUT, then use native Settings → Data & Sync → Import Centre. Keep the original copy until you've verified the imported counts and dates.

The native importer accepts RUT v1/v2, James web v1 and JamesAndroid v1 JSON. It validates the ledger before preview, retains original unknown fields, displays counts and date range, checks a database fingerprint before committing, and inserts atomically. Identical rows are skipped; conflicting rows and a second starting point are retained unchanged in James and reported. All original input bytes are saved app-privately, with a pre-import snapshot. No destructive Room migration is enabled.

James web archived-backup rows remain in the shared record store. Native originals are included in portable full exports and individually accessible from import history. Export has a 40 MB restore-size limit; oversized exports fail before writing rather than produce an unusable backup. Daily snapshots are local recovery copies, not protection against device loss or uninstalling; export independent copies regularly.

## Structure

`app/src/main/java/uk/co/james/` separates `core`, `data`, `database`, `imports`, `routines`, `timeline`, `time`, `state`, `health`, `location`, `sync`, `updates`, `settings`, and `ui`. These are feature packages in one Gradle application module, not separate feature databases. Room holds indexed record envelopes and lossless JSON payloads plus archive/import tables. Repositories own transactions; ViewModels expose state flows; SavedStateHandle preserves navigation, date and editor drafts. DataStore holds native appearance and permission-related switches.

Compose reproduces the charcoal/purple palette, rounded cards, typography hierarchy, five bottom tabs, and responsive card grids. Standard Android activity recreation is enabled; layouts adapt to available width. Actual Fold, font-scale, rotation and process-death checks remain required.

Health Connect reads only the user-selected grants, retains data-origin packages, and uses provider aggregation for steps. Other metrics show latest measurements without summing overlaps. No health data is seeded. Health reads are foreground-only in this checkpoint. Native geofences and activity transitions have separate explicit permissions and controls; no continuous high-frequency GPS collection is used. WorkManager schedules local snapshots and restores place registrations after reboot. WHOOP, Shift and Gig have read-only adapter boundaries, not fake successful connections. Nova has no connected model and sends no data to AI.

See `NATIVE_MIGRATION_AUDIT.md` for verification evidence, limitations and the remaining acceptance gates.
