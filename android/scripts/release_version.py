"""Release version and notes.

VERSION holds the release line (MAJOR.MINOR). Each published build takes the
next free patch on that line: the first is MAJOR.MINOR.0. The in-app updater
compares versionCode, which is derived from the version name
(major*10000 + minor*100 + patch): every build stays above the ones before it,
so the name and the code never disagree. CHANGELOG.md sections are headed
"## <version>"; the section matching the build goes at the top of the release
notes.
"""
from __future__ import annotations

import argparse
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LINE = re.compile(r"^(\d+)\.(\d+)$")
VERSION_NAME = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)$")


def release_line(path: Path = ROOT / "VERSION") -> str:
    value = path.read_text(encoding="utf-8").strip()
    if not LINE.fullmatch(value):
        raise ValueError(f"VERSION must be MAJOR.MINOR, got {value!r}")
    return value


def next_version(line: str, tags: list[str]) -> str:
    """First patch on the line above every patch already tagged."""
    pattern = re.compile(rf"^v?{re.escape(line)}\.(\d+)$")
    patches = [int(m.group(1)) for tag in tags if (m := pattern.match(tag.strip()))]
    return f"{line}.{max(patches) + 1 if patches else 0}"


def version_code(name: str) -> int:
    """versionCode for a MAJOR.MINOR.PATCH name: major*10000 + minor*100 + patch."""
    match = VERSION_NAME.fullmatch(name.strip())
    if not match:
        raise ValueError(f"versionName must be MAJOR.MINOR.PATCH, got {name!r}")
    major, minor, patch = (int(value) for value in match.groups())
    if minor > 99 or patch > 99:
        raise ValueError(f"minor and patch must stay below 100, got {name!r}")
    return major * 10_000 + minor * 100 + patch


def changelog_section(version: str, path: Path = ROOT / "CHANGELOG.md") -> str:
    if not path.is_file():
        return ""
    lines = path.read_text(encoding="utf-8").splitlines()
    out: list[str] = []
    inside = False
    for line in lines:
        if line.startswith("## "):
            if inside:
                break
            inside = line[3:].strip() == version
            continue
        if inside:
            out.append(line)
    return "\n".join(out).strip()


def release_notes(version: str, version_code: int, core_version: str,
                  changelog: Path = ROOT / "CHANGELOG.md") -> str:
    section = changelog_section(version, changelog)
    footer = (
        f"Ядро Flowseal: {core_version}\n"
        f"Android versionCode: {version_code}\n\n"
        "Собрано из зафиксированных исходников после проверок Python, JVM, Android 16 КБ и бинарников ARM64.\n"
        "Скачай APK ниже и установи поверх текущей версии. Контрольная сумма лежит в SHA256SUMS.\n\n"
        f"Flowseal core: {core_version}. Download the APK below and install it over the current version.\n"
    )
    return f"{section}\n\n---\n\n{footer}" if section else footer


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    version = sub.add_parser("version", help="print the next version; tags are read from stdin")
    version.add_argument("--line", default=None)
    code = sub.add_parser("code", help="print the versionCode for a MAJOR.MINOR.PATCH name")
    code.add_argument("name")
    notes = sub.add_parser("notes", help="print release notes")
    notes.add_argument("--version", required=True)
    notes.add_argument("--version-code", type=int, required=True)
    notes.add_argument("--core-version", required=True)
    args = parser.parse_args()
    if args.command == "version":
        import sys
        print(next_version(args.line or release_line(), sys.stdin.read().split()))
    elif args.command == "code":
        print(version_code(args.name))
    else:
        print(release_notes(args.version, args.version_code, args.core_version), end="")


if __name__ == "__main__":
    main()
