"""Fail closed when tracked source includes private key material or common credentials.

This is a lightweight repository gate, not a replacement for GitHub secret
scanning or an all-object history scan performed during a public-release review.
It deliberately prints only paths/rule names, never matching values.
"""
from __future__ import annotations

import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BANNED_SUFFIXES = (".p12", ".pfx", ".jks", ".keystore", ".pem", ".db", ".sqlite", ".sqlite3", ".hprof", ".perfetto-trace")
BANNED_PATHS = {"wear/libs/samsung-health-sensor-api-1.4.1.aar"}
TEXT_SUFFIXES = {".kt", ".kts", ".java", ".xml", ".json", ".yml", ".yaml", ".md", ".txt", ".properties", ".py", ".sh", ".gradle"}
PATTERNS = {
    "private-key-marker": re.compile(r"-----BEGIN " + r"[A-Z ]*PRIVATE KEY-----"),
    "github-token": re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})\b"),
    "aws-access-key": re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
    "google-api-key": re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b"),
    "bearer-token": re.compile(r"(?i)bearer[ \t]+[A-Za-z0-9._~+/=-]{20,}"),
}


def tracked_files() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "-z"], cwd=ROOT, check=True, capture_output=True
    )
    return [ROOT / item for item in result.stdout.decode().split("\0") if item]


def fail(rule: str, path: Path) -> None:
    raise AssertionError(f"public repository gate: {rule} in {path.relative_to(ROOT)}")


for path in tracked_files():
    if path.relative_to(ROOT).as_posix() in BANNED_PATHS:
        fail("tracked-proprietary-samsung-sdk", path)
    if path.suffix.lower() in BANNED_SUFFIXES:
        fail("tracked-private-or-local-artifact", path)
    if path.suffix.lower() not in TEXT_SUFFIXES or path.stat().st_size > 2_000_000:
        continue
    text = path.read_text(encoding="utf-8", errors="replace")
    for name, pattern in PATTERNS.items():
        if pattern.search(text):
            fail(name, path)

release = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
assert "JAMES_SIGNING_KEYSTORE_BASE64" in release, "release signing secret is required"
assert "signing/james-release.p12" not in release, "release workflow must not read a tracked keystore"
assert "github.ref == 'refs/heads/main'" in release, "release must be main-only"
assert "SAMSUNG_HEALTH_SENSOR_AAR_BASE64" in release, "trusted release must receive the Samsung AAR separately"
assert "JAMES_SAMSUNG_SENSOR_AAR_PATH" in release, "trusted release must use the temporary Samsung AAR"
assert "samsung-health-sensor-api-1.4.1.aar" in release and "rm -f" in release, "temporary Samsung AAR cleanup is required"
wear_build = (ROOT / "wear/build.gradle.kts").read_text(encoding="utf-8")
assert "JAMES_SAMSUNG_SENSOR_AAR_PATH" in wear_build, "Wear must support an external Samsung AAR path"
assert 'implementation(files("libs/samsung-health-sensor-api-1.4.1.aar"))' not in wear_build, "Wear must not require a tracked Samsung AAR path"
ignore = (ROOT / ".gitignore").read_text(encoding="utf-8")
assert "wear/libs/samsung-health-sensor-api-1.4.1.aar" in ignore, "Samsung AAR must be ignored"
validation = (ROOT / ".github/workflows/android.yml").read_text(encoding="utf-8")
assert "pull_request_target" not in validation, "untrusted PR workflow is forbidden"
assert "secrets." not in validation, "validation workflow must not access secrets"
print("Public repository gate: PASS")
