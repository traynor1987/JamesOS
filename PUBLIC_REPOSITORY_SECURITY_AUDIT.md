# JamesOS public-repository security audit

**Repository:** `traynor1987/JamesOS`  
**Audit date:** 2026-09-14  
**Decision:** **SAFE AFTER SPECIFIC OWNER STEPS**

## Clean-history migration

This repository was created from a sanitised file snapshot of the private historical archive `traynor1987/Jamssos`. It intentionally starts with a new root commit and has no imported Jamssos commits, branches, tags, or ancestry.

The snapshot excludes release signing material, the Samsung Health Sensor standalone AAR, local credentials, databases, backups, profiler output, and personal-data exports. The included `tools/public_repo_gate.py` is the repeatable public-source guard.

## Required owner controls before a signed public release

Create GitHub environment `james-release` in this repository, restrict it to protected `main`, and require approval. Add these environment secrets without exposing their values:

- `JAMES_SIGNING_KEYSTORE_BASE64`
- `JAMES_SIGNING_PASSWORD`
- `SAMSUNG_HEALTH_SENSOR_AAR_BASE64` only for trusted full Wear release builds.

The release workflow is manual and main-only. Public/fork validation has no secrets and no `pull_request_target`.

## Permanent rules

- Never import Jamssos Git history into JamesOS.
- Never commit release signing key material.
- Never commit the Samsung Health Sensor standalone AAR unless explicit redistribution rights are established.
