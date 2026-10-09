#!/usr/bin/env python3
"""License / premium gate for the bundled renderer.

ProudVocab Android ships with every feature unlocked, so the license client,
the Lemon Squeezy checkout, the Google Drive sync module and the premium UI were
removed from the renderer under app/src/main/assets/web. That removal was done
once with AST-based edits against the upstream renderer and the result is
committed as-is. Re-running those transforms is therefore not needed.

This script is the guard that keeps the removal from regressing. It:

  1. greps the renderer for license / premium / account-sync identifiers;
  2. runs `node --check` on every JavaScript file in the renderer.

It exits non-zero on any finding. CI runs it before the Gradle build.

Usage:  python3 scripts/strip-license.py [--root PATH]
"""

import argparse
import pathlib
import re
import subprocess
import sys

DEFAULT_ROOT = pathlib.Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "web"

# Identifiers and strings that only existed for the license / account / sync features.
FORBIDDEN = re.compile(
    r"licen[cs]e"                       # licenseType, checkLicense, license server …
    r"|premium"                         # PREMIUM / Premium UI and gates
    r"|PV_ApiClient|apiClient"
    r"|LS_URLS|lemonsqueezy|checkout"
    r"|PREMIUM_REQUIRED"
    r"|googleSync|performGoogleDriveSync|google_refresh"
    r"|chrome\.identity|getGoogleAuthToken|clearGoogleAuthToken|connectGoogleAccount"
    r"|profile-(?:buy|manage|login|logout|sync|overlay|close|btn)"
    r"|mobile-(?:login|sync|logout)",
    re.IGNORECASE,
)

# Files that are not license code and therefore excluded from the grep.
SKIP_FILES = {
    # CEFR word-level data: contains the word "license" as a vocabulary entry.
    "ext/content/cefr_data.js",
}
SKIP_DIRS = {"langs"}  # dictionaries: "licenziare" (Italian "to lay off") etc.

# Known false positive: the voice picker recognises the platform's "(Premium)"
# TTS voice name. That is a speech-engine label, not a product tier.
ALLOWED_LINES = {
    ("ext/sidepanel/panel_subtitles.js", 'e.name.includes("Premium") ||'),
}


def scan_text(root: pathlib.Path) -> list[str]:
    findings: list[str] = []
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix not in {".js", ".html", ".css", ".json"}:
            continue
        rel = path.relative_to(root).as_posix()
        if rel in SKIP_FILES or any(part in SKIP_DIRS for part in path.relative_to(root).parts):
            continue
        for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if FORBIDDEN.search(line) and (rel, line.strip()) not in ALLOWED_LINES:
                findings.append(f"{rel}:{lineno}: {line.strip()[:120]}")
    return findings


def check_syntax(root: pathlib.Path) -> list[str]:
    errors: list[str] = []
    for path in sorted(root.rglob("*.js")):
        if path.relative_to(root).parts[:1] == ("langs",):
            continue
        result = subprocess.run(["node", "--check", str(path)], capture_output=True, text=True)
        if result.returncode != 0:
            errors.append(f"{path.relative_to(root).as_posix()}: {result.stderr.strip().splitlines()[-1]}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=pathlib.Path, default=DEFAULT_ROOT, help="renderer root")
    args = parser.parse_args()
    root: pathlib.Path = args.root
    if not root.is_dir():
        print(f"renderer root not found: {root}", file=sys.stderr)
        return 2

    findings = scan_text(root)
    syntax = check_syntax(root)

    if findings:
        print("License / premium references still present:")
        for f in findings:
            print("  " + f)
    if syntax:
        print("Syntax errors:")
        for e in syntax:
            print("  " + e)
    if findings or syntax:
        return 1
    print(f"OK: renderer at {root} is license-free and every JS file parses.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
