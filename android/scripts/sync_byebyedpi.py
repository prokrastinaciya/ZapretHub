"""Vendor the pinned ByeByeDPI reference and verify its snapshot integrity.

The upstream Android application ``romanvht/ByeByeDPI`` is the parity baseline
for the merged TG-WS application.  This script records an immutable, recursive
snapshot:

* the repository URL and the tag -> commit pin;
* every recursive native dependency (git submodules, to arbitrary depth) by
  URL + commit, with each dependency's own file hashes and license files;
* the SHA-256 of every vendored file, parent and dependencies alike;
* license files and their detected SPDX identifiers at every level.

Fetch endpoints are restricted to explicit GitHub HTTPS origins: the submodule
URL read from each ``.gitmodules`` must parse as ``https://github.com/<owner>/<repo>[.git]``
before any tree/blob/zip is requested, so no arbitrary URL is ever fetched.

Two distinct checks protect the snapshot:

* ``--check``      offline: recomputes every vendored file hash (parent and each
                   nested dependency) and the submodule pins declared in each
                   vendored ``.gitmodules`` against the lock, no network;
* ``--ref <tag>``  online: resolves the tag and the full recursive gitlink tree
                   and, when the same tag is already pinned, rejects a moved tag
                   or a changed submodule commit before it can silently re-pin.

Lock schema is versioned (``schema: 2``).  A v1 lock (dependencies pinned but not
vendored) is migrated in one explicit, safe step: its recorded top-level pins are
still checked against the fresh resolution, and the snapshot is re-pinned with the
dependency sources included.  Local adaptations live in the TG-WS source, never in
the snapshot, which preserves exact upstream bytes and licenses.

Note on committing a (re)generated snapshot: some vendored upstream repositories
ship their own ``.gitignore`` whose rules (e.g. ``bin`` in hev-socks5-tunnel) would
hide upstream-checked-in blobs such as ``third-part/wintun/bin/wintun.dll``.  Stage
the snapshot with ``git add -f upstream/byebyedpi`` so those bytes stay tracked.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import posixpath
import re
from pathlib import Path, PurePosixPath
import shutil
import sys
import tempfile
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "romanvht/ByeByeDPI"
URL = f"https://github.com/{REPOSITORY}"
LOCK = ROOT / "upstream/byebyedpi.lock.json"
VENDOR = ROOT / "upstream/byebyedpi"
SCHEMA = 2

LICENSE_PATTERN = re.compile(r"^(license|copying|notice|copyright)(\..*)?$", re.IGNORECASE)
GITHUB_ORIGIN = re.compile(r"^https://github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?$")


def fetch(url: str) -> bytes:
    headers = {
        "User-Agent": "Derigava-TgWsProxy-sync",
        "Accept": "application/vnd.github+json",
    }
    token = os.environ.get("GITHUB_TOKEN")
    if token and urllib.parse.urlparse(url).hostname == "api.github.com":
        headers["Authorization"] = f"Bearer {token}"

    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def is_sha(value: object) -> bool:
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{40}", value) is not None


def github_repo(url: str) -> str:
    """Return ``owner/repo`` only for an explicit GitHub HTTPS origin, else fail."""
    match = GITHUB_ORIGIN.fullmatch(url or "")
    if not match:
        raise ValueError(f"Submodule URL is not an allowed GitHub HTTPS origin: {url!r}")
    owner, repo = match.group(1), match.group(2)
    if owner.startswith(".") or repo.startswith(".") or repo in (".", ".."):
        raise ValueError(f"Submodule URL resolves to an unsafe repository path: {url!r}")
    return f"{owner}/{repo}"


def parse_gitmodules(text: str) -> dict[str, str]:
    """Return ``{path: url}`` for every submodule declared in ``.gitmodules``."""
    subs: dict[str, dict[str, str]] = {}
    current: str | None = None
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", ";")):
            continue
        if line.startswith("[") and line.endswith("]"):
            section = line[1:-1].strip()
            if section.startswith('submodule "') and section.endswith('"'):
                current = section[len('submodule "'):-1]
                subs.setdefault(current, {})
            else:
                current = None
            continue
        if current is not None and "=" in line:
            key, _, value = line.partition("=")
            key, value = key.strip(), value.strip()
            if key in ("path", "url"):
                subs[current][key] = value
    return {entry["path"]: entry["url"] for entry in subs.values() if entry.get("path") and entry.get("url")}


def detect_spdx(data: bytes) -> str:
    text = data.decode("utf-8", "replace")
    if "GNU GENERAL PUBLIC LICENSE" in text and "Version 3" in text:
        return "GPL-3.0-only"
    if "Apache License" in text and "Version 2.0" in text:
        return "Apache-2.0"
    if "Prebuilt Binaries License" in text and "WireGuard LLC" in text:
        return "LicenseRef-WireGuard-Wintun-Prebuilt"
    if "Redistribution and use in source and binary forms" in text and "name of the author may not be used" in text:
        return "BSD-3-Clause"
    if "Permission is hereby granted, free of charge" in text and "The above copyright notice and this permission notice" in text:
        return "MIT"
    return "UNKNOWN"


def is_license(path: str) -> bool:
    return LICENSE_PATTERN.match(Path(path).name) is not None


def extract_sources(archive: bytes, require: set[str] | None = None) -> tuple[dict[str, bytes], dict[str, str]]:
    files: dict[str, bytes] = {}
    links: dict[str, str] = {}
    with zipfile.ZipFile(io.BytesIO(archive)) as source:
        for item in source.infolist():
            if item.is_dir():
                continue
            # A source archive has exactly one repository-prefix component.
            path = item.filename.partition("/")[2]
            parts = PurePosixPath(path).parts
            if not parts or any(part in (".", "..", "") for part in parts) or "\\" in path or PurePosixPath(path).as_posix() != path:
                continue
            if path in files or path in links:
                raise ValueError(f"Duplicate upstream path: {path}")
            if (item.external_attr >> 16) & 0o170000 == 0o120000:
                links[path] = source.read(item).decode("utf-8")
            else:
                files[path] = source.read(item)
    # Git stores symlinks as a blob holding the target path.  On Windows a
    # symlink would not survive a checkout, so materialize it as a byte copy of
    # its target and record the mapping so the discrepancy stays visible.
    for path, target in links.items():
        resolved = posixpath.normpath(posixpath.join(posixpath.dirname(path), target))
        if resolved.startswith("..") or resolved not in files:
            raise ValueError(f"Symlink target missing in upstream source: {path} -> {target}")
        links[path] = resolved
        files[path] = files[resolved]
    if not files:
        raise ValueError("Archive is empty")
    if require and not require <= files.keys():
        raise ValueError(f"Archive lacks required files: {sorted(require - files.keys())}")
    return files, links


def collect_files(root: Path, exclude: list[str]) -> dict[str, str]:
    result: dict[str, str] = {}
    for path in root.rglob("*"):
        if not path.is_file() or "__pycache__" in path.parts or path.suffix == ".pyc":
            continue
        rel = path.relative_to(root).as_posix()
        if any(rel == prefix or rel.startswith(prefix + "/") for prefix in exclude):
            continue
        result[rel] = sha256(path.read_bytes())
    return result


def validate_repo(root: Path, spec: dict) -> None:
    submodules = spec.get("submodules") or {}
    sub_paths = list(submodules)
    licenses = spec.get("licenses") or {}
    files = spec.get("files") or {}
    if not licenses:
        raise ValueError(f"Missing licenses for {root.name!r}")
    if not files:
        raise ValueError(f"Missing file inventory for {root.name!r}")
    for path, meta in licenses.items():
        if not isinstance(meta, dict) or not meta.get("spdx"):
            raise ValueError(f"Invalid license record: {path}")
        if files.get(path) != meta.get("sha256"):
            raise ValueError(f"License hash disagrees with inventory: {path}")
    actual = collect_files(root, sub_paths)
    if actual != files:
        added = sorted(actual.keys() - files.keys())
        missing = sorted(files.keys() - actual.keys())
        modified = sorted(path for path in actual.keys() & files.keys() if actual[path] != files[path])
        raise ValueError(f"Integrity failed in {root.name!r}: added={added}, missing={missing}, modified={modified}")
    for path, target in (spec.get("symlinks") or {}).items():
        if files.get(path) != files.get(target):
            raise ValueError(f"Symlink materialization disagrees with target in {root.name!r}: {path} -> {target}")
    gitmodules = root / ".gitmodules"
    declared = parse_gitmodules(gitmodules.read_text(encoding="utf-8")) if gitmodules.is_file() else {}
    pinned = {path: entry["url"] for path, entry in submodules.items()}
    if declared != pinned:
        raise ValueError(f"Submodule pins disagree with .gitmodules in {root.name!r}: declared={declared}, pinned={pinned}")
    for path, entry in submodules.items():
        if not isinstance(entry, dict) or not entry.get("url") or not is_sha(entry.get("commit")):
            raise ValueError(f"Invalid submodule pin: {path}")
        validate_repo(root / path, entry)


def validate(vendor: Path, lock: dict) -> None:
    if lock.get("schema") != SCHEMA:
        raise ValueError(f"Lock schema {lock.get('schema')!r} requires regeneration (expected {SCHEMA})")
    if lock.get("repository") != REPOSITORY:
        raise ValueError("Invalid upstream repository")
    if lock.get("url") != URL:
        raise ValueError("Invalid upstream URL")
    if not re.fullmatch(r"v\.\d+\.\d+\.\d+(?:\.\d+)?", lock.get("tag", "")):
        raise ValueError("Invalid upstream tag")
    if not is_sha(lock.get("commit")):
        raise ValueError("Invalid upstream commit SHA")
    validate_repo(vendor, lock)


def fetch_tree(owner_repo: str, commit: str) -> list[dict]:
    data = json.loads(fetch(f"https://api.github.com/repos/{owner_repo}/git/trees/{commit}?recursive=1"))
    if data.get("truncated"):
        raise ValueError(f"Tree listing truncated for {owner_repo}@{commit}")
    return data.get("tree", [])


def resolve_commit(ref: str) -> str:
    data = json.loads(fetch(f"https://api.github.com/repos/{REPOSITORY}/commits/{urllib.parse.quote(ref, safe='')}"))
    commit = data.get("sha", "")
    if not is_sha(commit):
        raise ValueError("Invalid upstream commit SHA from API")
    return commit


def resolve_submodules(owner_repo: str, commit: str) -> dict:
    tree = fetch_tree(owner_repo, commit)
    blobs = {entry["path"] for entry in tree if entry.get("type") == "blob"}
    gitlinks = {entry["path"]: entry["sha"] for entry in tree if entry.get("mode") == "160000"}
    urls: dict[str, str] = {}
    if ".gitmodules" in blobs:
        urls = parse_gitmodules(fetch(f"https://raw.githubusercontent.com/{owner_repo}/{commit}/.gitmodules").decode("utf-8"))
    if set(urls) != set(gitlinks):
        raise ValueError(f"Submodule paths disagree between .gitmodules and tree gitlinks in {owner_repo}@{commit}")
    result: dict = {}
    for path in sorted(gitlinks):
        github_repo(urls[path])  # validate the origin before any deeper fetch
        entry = {"url": urls[path], "commit": gitlinks[path]}
        nested = resolve_submodules(github_repo(urls[path]), gitlinks[path])
        if nested:
            entry["submodules"] = nested
        result[path] = entry
    return result


def materialize(root: Path, owner_repo: str, commit: str, require: set[str] | None = None) -> tuple[dict, dict, dict]:
    content, symlinks = extract_sources(fetch(f"https://codeload.github.com/{owner_repo}/zip/{commit}"), require)
    for path, data in content.items():
        target = root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    files = {path: sha256(data) for path, data in content.items()}
    licenses = {path: {"spdx": detect_spdx(data), "sha256": sha256(data)}
                for path, data in content.items() if is_license(path)}
    return files, licenses, symlinks


def materialize_submodules(root: Path, submodules: dict) -> dict:
    lock_entries: dict = {}
    for path, entry in submodules.items():
        owner_repo = github_repo(entry["url"])
        files, licenses, symlinks = materialize(root / path, owner_repo, entry["commit"])
        lock_entry = {"url": entry["url"], "commit": entry["commit"], "licenses": licenses, "files": files}
        if symlinks:
            lock_entry["symlinks"] = symlinks
        if "submodules" in entry:
            lock_entry["submodules"] = materialize_submodules(root / path, entry["submodules"])
        lock_entries[path] = lock_entry
    return lock_entries


def flatten_pins(submodules: dict | None, prefix: str = "") -> dict:
    result: dict = {}
    for path, entry in (submodules or {}).items():
        full = f"{prefix}{path}"
        result[full] = (entry.get("url"), entry.get("commit"))
        result.update(flatten_pins(entry.get("submodules"), full + "/"))
    return result


def pins_changed(previous: dict | None, resolved: dict) -> bool:
    for path, pin in flatten_pins(previous).items():
        if flatten_pins(resolved).get(path) != pin:
            return True
    return False


def sync(ref: str | None = None) -> dict:
    previous = json.loads(LOCK.read_text(encoding="utf-8")) if LOCK.exists() else None
    if ref is None:
        release = json.loads(fetch(f"https://api.github.com/repos/{REPOSITORY}/releases/latest"))
        if release.get("draft") or release.get("prerelease"):
            raise ValueError("Upstream release must be stable and published")
        ref = release["tag_name"]
    if not re.fullmatch(r"v\.\d+\.\d+\.\d+(?:\.\d+)?", ref):
        raise ValueError(f"Unsupported stable release tag: {ref!r}")
    commit = resolve_commit(ref)
    if previous and previous.get("tag") == ref and previous.get("commit") != commit:
        raise ValueError(f"Tag {ref!r} moved: pinned {previous.get('commit')}, now {commit}")
    submodules = resolve_submodules(REPOSITORY, commit)
    if previous and previous.get("tag") == ref and pins_changed(previous.get("submodules"), submodules):
        raise ValueError(f"Submodules changed under tag {ref!r}")
    if previous and previous.get("commit") == commit and previous.get("schema") == SCHEMA:
        validate(VENDOR, previous)
        return {"changed": False, "tag": ref, "commit": commit}
    VENDOR.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="byebyedpi-", dir=VENDOR.parent) as temporary:
        staged = Path(temporary) / "byebyedpi"
        parent_files, parent_licenses, parent_symlinks = materialize(staged, REPOSITORY, commit, require={"LICENSE", "app/src/main/AndroidManifest.xml"})
        lock_submodules = materialize_submodules(staged, submodules)
        lock = {"schema": SCHEMA, "repository": REPOSITORY, "url": URL, "tag": ref, "commit": commit,
                "licenses": parent_licenses, "files": parent_files, "submodules": lock_submodules}
        if parent_symlinks:
            lock["symlinks"] = parent_symlinks
        validate(staged, lock)
        # Only our dedicated vendor folder can be replaced, never a computed shell path.
        if VENDOR.resolve() != (ROOT.resolve() / "upstream" / "byebyedpi"):
            raise ValueError("Refusing to replace a vendor directory outside the workspace")
        if VENDOR.exists():
            shutil.rmtree(VENDOR)
        shutil.move(str(staged), str(VENDOR))
        LOCK.write_text(json.dumps(lock, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return {"changed": True, "tag": ref, "commit": commit}


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", help="Exact stable tag; omitted means latest stable release")
    parser.add_argument("--check", action="store_true", help="Verify local lock and bytes without network or changes")
    args = parser.parse_args(argv)
    try:
        if args.check:
            lock = json.loads(LOCK.read_text(encoding="utf-8"))
            validate(VENDOR, lock)
            result = {"changed": False, "tag": lock["tag"], "commit": lock["commit"]}
        else:
            result = sync(args.ref)
    except ValueError as error:
        print(f"byebyedpi sync: {error}", file=sys.stderr)
        raise SystemExit(1)
    print(json.dumps(result))
    if output := os.environ.get("GITHUB_OUTPUT"):
        with open(output, "a", encoding="utf-8") as stream:
            for key, value in result.items():
                if isinstance(value, (dict, list)):
                    continue
                stream.write(f"{key}={str(value).lower() if isinstance(value, bool) else value}\n")


if __name__ == "__main__":
    main()
