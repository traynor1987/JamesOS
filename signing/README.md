# Permanent release signing

## Security invariant

**Never commit release signing key material.** The Android release identity must remain the same so updates continue to install over existing James OS releases, but the PKCS#12 keystore must exist only in protected secret storage and temporary protected release-runner storage.

`signing/certificate.sha256` is the expected public certificate fingerprint. It is verification material, not private-key material.

## Protected GitHub environment

Create/maintain the GitHub Actions environment `james-release`, restricted to `main` with an approval reviewer. Store these **environment secrets** there:

- `JAMES_SIGNING_KEYSTORE_BASE64`: the base64 encoding of the existing `james-release.p12` file, created locally without putting its value in GitHub issues, commits, workflow logs, or chat.
- `JAMES_SIGNING_PASSWORD`: the current password for that same PKCS#12 identity.

The release workflow is manual-dispatch-only and main-only. It reconstructs the keystore at `$RUNNER_TEMP/james-release.p12`, checks its certificate against `certificate.sha256`, signs phone and Wear assets, verifies each APK signer, and deletes the temporary file at job end. It fails closed when either secret is unavailable or the certificate does not match.

## Required one-time migration

1. Keep two offline encrypted backups of the existing PKCS#12 and an independent password-manager copy of its password. Verify each backup with `certificate.sha256`.
2. Add `JAMES_SIGNING_KEYSTORE_BASE64` and verify it using a trusted manual release workflow run after the repository file is removed from the checked-out ref.
3. Do **not** change the signing certificate. Password re-encryption is a separate, deliberate operation: re-encrypt the same key, verify the certificate fingerprint before and after, then update both protected secrets.
4. Only after the protected copy has been verified may the tracked keystore be removed and Git history rewritten. Removing the file in a new commit is not sufficient for public visibility.
5. Keep release secrets only in `james-release`; ordinary push and pull-request validation must never have access to them.

## Required GitHub account controls

- Restrict `james-release` deployments to `main`.
- Require an approval reviewer for `james-release`.
- Protect `main`: require pull requests and the Android validation checks; restrict direct pushes and workflow-file changes.
- Keep release secrets in the environment, not repository-wide, and enable secret scanning/push protection where available.

The release package `uk.co.james.personal` installs alongside debug `uk.co.james.personal.debug`. Future releases must retain package ID, an increasing version code, and the certificate recorded by `certificate.sha256`.
