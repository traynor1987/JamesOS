# WHOOP direct connection

The Android app uses a dedicated HTTPS connection service on the James OS privacy Site. It does not host or download the Android UI.

## Owner setup
- WHOOP Client ID: 2fd5d390-0bfd-43a4-b87b-23c6eb0a32c4 (public identifier).
- Registered callback: https://james-os-privacy.traynor1987.chatgpt.site/whoop/callback/
- Privacy policy: https://james-os-privacy.traynor1987.chatgpt.site/
- Client secret and token encryption key exist only in Sites secret settings. Never put them in the APK or this repository.
- Setup uses platform ChatGPT sign-in and a server-side owner account check, followed by WHOOP consent. The registered owner must be the same ChatGPT account used for the Site.

## On the phone
Update James OS, then Settings → Connections → WHOOP API → Connect WHOOP. Sign in with the owner ChatGPT account and authorise WHOOP. Return to James OS and tap Finish connection & sync.

The first sync reads up to 28 days of sleep, recovery, cycles and workouts, paging through all results. Existing records retain provider IDs and updatedAt; repeat reads update only the same authoritative WHOOP record. The untouched provider payload is retained separately. Missing/unscored values are not turned into zeros. Sleep duration sums asleep stages rather than time in bed. Direct WHOOP sleep and resting HR take dashboard priority; Health Connect originals remain intact.

After successful sync, WorkManager schedules refresh every six hours with network/battery constraints and exponential retries. Android may delay jobs. Device keys persist encrypted in Android Keystore-protected app-private files excluded from exports. Server connections expire after 90 days; reconnect if needed. Disconnect revokes WHOOP access and removes server credentials; imported phone history is retained.

The public WHOOP API has no live Stress Monitor score. Recovery/Strain are source measurements, not emotional mood or stress diagnoses. The existing James State heuristics are still separate; personal reports continue to take priority.

## Security / verification
WHOOP state is single-use and browser-cookie-bound, the callback exchanges codes only on the server, tokens use AES-GCM at rest, refresh operations hold a database lease, and both rotating tokens are saved together. Ambiguous refresh failure requires reconnection rather than replaying a potentially spent token. Health API paths are allowlisted, response sizes/page counts bounded, credential-bearing redirects disabled. Server stores no health response history. All data endpoints require the device bearer key.

Unit tests cover unscored/missing metrics, calibration, stable identities and asleep duration. Live account authorisation still needs the owner to complete WHOOP consent; automated tests do not claim an account connection.

Official API: https://developer.whoop.com/api/
OAuth: https://developer.whoop.com/docs/developing/oauth/
