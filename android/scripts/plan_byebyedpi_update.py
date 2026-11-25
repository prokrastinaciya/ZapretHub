"""Plan a ByeByeDPI update without modifying Git or contacting the network."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re


TAG = re.compile(r"v\.(\d+)\.(\d+)\.(\d+)(?:\.(\d+))?", re.ASCII)
SHA = re.compile(r"[0-9a-f]{40}")


def version(tag: str) -> tuple[int, ...]:
    match = TAG.fullmatch(tag)
    if not match:
        raise ValueError(f"Invalid ByeByeDPI release tag: {tag!r}")
    return tuple(int(part or 0) for part in match.groups())


def plan_update(lock: dict, live_locked_commit: str, release: dict, existing_branches=()) -> str | None:
    pin = lock["commit"]
    if not SHA.fullmatch(pin) or not SHA.fullmatch(live_locked_commit):
        raise ValueError("Invalid ByeByeDPI commit SHA")
    if pin != live_locked_commit:
        raise ValueError(f"Pinned ByeByeDPI tag {lock['tag']!r} moved")
    if release.get("draft") is not False or release.get("prerelease") is not False:
        return None
    tag = release["tag_name"]
    if version(tag) <= version(lock["tag"]):
        return None
    branch = f"bot/byebyedpi-{tag}"
    return None if branch in existing_branches else branch


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, default=Path("upstream/byebyedpi.lock.json"))
    parser.add_argument("--release", type=Path, required=True)
    parser.add_argument("--live-lock-sha", required=True)
    parser.add_argument("--existing-branch", action="append", default=[])
    args = parser.parse_args()
    branch = plan_update(
        json.loads(args.lock.read_text(encoding="utf-8")), args.live_lock_sha,
        json.loads(args.release.read_text(encoding="utf-8")), args.existing_branch,
    )
    if branch is not None:
        print(branch)


if __name__ == "__main__":
    main()
