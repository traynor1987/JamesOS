# JamesOS public-repository security audit

**Repository:** `traynor1987/JamesOS`  
**Audit date:** 2026-09-14  
**Decision:** **SAFE AFTER SPECIFIC OWNER STEPS**

## Executive summary

JamesOS is a new public repository populated from a sanitised current file snapshot only. Its root history is independent of the private `traynor1987/Jamssos` archive. The snapshot excludes the Android PKCS#12 release key, Samsung's standalone Health Sensor AAR, local credential files, databases, backups, profiler captures and known personal-data artifacts.

## Verified public snapshot

| Area | Result |
|---|---|
| Git history | New root commit; no Jamssos commit parent, branch or tag imported. |
| Release key material | No `.p12`, `.pfx`, `.jks`, `.keystore` or private PEM file. |
| Samsung SDK binary | `wear/libs/samsung-health-sensor-api-1.4.1.aar` absent and ignored. |
| Credential scan | No private-key, GitHub-token, AWS-key, Google-key or bearer-token pattern match. |
| Personal data | No database/export/backup/profiler artifact is present in the committed snapshot. |
| Package/update identity | Android application ID remains `uk.co.james.personal`; updater target is `traynor1987/JamesOS`. |
| Public PR security | Validation has no secrets and no `pull_request_target`; release remains manual and main-only. |
| Licensing | Apache-2.0 licence and third-party notice included; Samsung SDK explicitly excluded. |

## Signing and release continuity

The public source never contains the signing identity. The release workflow reconstructs it only from protected environment secrets, validates the expected certificate fingerprint before building, validates resulting APK signatures and package identity, then cleans temporary material. It fails closed.

Signing continuity cannot be runtime-verified until the owner adds the protected secrets in this new repository. No signing certificate change is requested or permitted.

## Samsung Health Sensor SDK

Samsung sensor functionality remains supported. Developers obtain the exact AAR directly from Samsung under its terms at the ignored documented path, or provide an explicit local path. Trusted full Wear releases may materialise a separately protected SDK secret in runner temporary storage. Public/fork PRs never receive it.

## Updater migration and bridge release

This snapshot changes future updater defaults to `traynor1987/JamesOS`. Existing installed builds point at the private archive, so one final **same-package, same-signer** bridge APK from Jamssos is required to move those installed builds to JamesOS. Do not issue it until the JamesOS release environment has verified the existing signer.

## Required owner actions

1. **GitHub → traynor1987/JamesOS → Settings → Environments:** create `james-release`; restrict deployments to protected `main` and require approval.
2. In that environment create `JAMES_SIGNING_KEYSTORE_BASE64` and `JAMES_SIGNING_PASSWORD` using the same existing signer. Never paste values into source, issues or chat.
3. If trusted full Wear release builds are needed, add `SAMSUNG_HEALTH_SENSOR_AAR_BASE64` separately.
4. **Settings → Rules / Branches:** protect `main`, require validation and review, and restrict workflow-file changes. Enable secret scanning and push protection.
5. Run the protected manual release verification. It must match `signing/certificate.sha256`, preserve package `uk.co.james.personal`, and produce an APK accepted as an update.
6. Publish the final bridge APK from private Jamssos, then future releases from JamesOS.

## Warning

The initial public root commit uses the GitHub account's current commit email metadata. Review whether that address is intended to be public; enable GitHub's **Keep my email addresses private** before future commits if not.

## Final assessment

**SAFE AFTER SPECIFIC OWNER STEPS.** The source, public history, fork workflow model, Samsung dependency model and updater target are safe to operate publicly. Protected release configuration and a signer-verified bridge release remain owner-controlled requirements.
