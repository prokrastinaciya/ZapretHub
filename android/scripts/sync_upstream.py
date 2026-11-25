"""Vendor a pinned Flowseal core and apply its Android AES library selection."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import tempfile
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "Flowseal/tg-ws-proxy"
LOCK = ROOT / "upstream/flowseal.lock.json"
VENDOR = ROOT / "upstream/flowseal"
AES_SOURCE = "proxy/_aes.py"
AES_LOADER = b'    def _load_libcrypto():\n        name = ctypes.util.find_library("crypto")'
ANDROID_AES_LOADER = (
    b'    import sys\n\n'
    b'    def _load_libcrypto():\n'
    b'        if hasattr(sys, "getandroidapilevel"):\n'
    b'            # Chaquopy ships this OpenSSL library alongside its Python runtime.\n'
    b'            return ctypes.CDLL("libcrypto_python.so")\n\n'
    b'        name = ctypes.util.find_library("crypto")'
)


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


def selected(path: str) -> bool:
    parts = PurePosixPath(path).parts
    if not parts or any(part in (".", "..", "") for part in parts) or "\\" in path or PurePosixPath(path).as_posix() != path:
        return False
    return path == "LICENSE" or (len(parts) > 1 and parts[0] in {"proxy", "tests"})


def extract_sources(archive: bytes) -> dict[str, bytes]:
    files: dict[str, bytes] = {}
    with zipfile.ZipFile(io.BytesIO(archive)) as source:
        for item in source.infolist():
            if item.is_dir():
                continue
            # A source archive has exactly one repository-prefix component.
            path = item.filename.partition("/")[2]
            if not selected(path):
                continue
            if (item.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError(f"Symlink in upstream source: {path}")
            if path in files:
                raise ValueError(f"Duplicate upstream path: {path}")
            files[path] = source.read(item)
    if not {"LICENSE", "proxy/__init__.py", "proxy/tg_ws_proxy.py"} <= files.keys():
        raise ValueError("Archive lacks the Flowseal runtime or license")
    if not any(path.startswith("tests/test_") for path in files):
        raise ValueError("Archive lacks upstream tests")
    return files


def patch_android_aes(source: bytes) -> bytes:
    needle = b"\n" + AES_LOADER
    if source.count(needle) != 1:
        raise ValueError("Review the Android AES loader against the new Flowseal source")
    return source.replace(needle, ANDROID_AES_LOADER, 1)


def validate(vendor: Path, lock: dict) -> None:
    if lock.get("repository") != REPOSITORY or not re.fullmatch(r"[0-9a-f]{40}", lock.get("commit", "")):
        raise ValueError("Invalid upstream provenance")
    expected = lock.get("files", {})
    if not expected or any(not selected(path) for path in expected):
        raise ValueError("Invalid upstream file inventory")
    actual = {path.relative_to(vendor).as_posix(): sha256(path.read_bytes())
              for path in vendor.rglob("*") if path.is_file() and "__pycache__" not in path.parts and path.suffix != ".pyc"}
    if actual != expected:
        added = sorted(actual.keys() - expected.keys())
        missing = sorted(expected.keys() - actual.keys())
        modified = sorted(path for path in actual.keys() & expected.keys() if actual[path] != expected[path])
        raise ValueError(f"Upstream integrity failed: added={added}, missing={missing}, modified={modified}")


def sync(ref: str | None = None) -> dict:
    previous = json.loads(LOCK.read_text(encoding="utf-8")) if LOCK.exists() else None
    if ref is None:
        release = json.loads(fetch(f"https://api.github.com/repos/{REPOSITORY}/releases/latest"))
        if release.get("draft") or release.get("prerelease"):
            raise ValueError("Upstream release must be stable and published")
        ref = release["tag_name"]
    if not re.fullmatch(r"v?\d+\.\d+\.\d+(?:\.\d+)?", ref):
        raise ValueError(f"Unsupported stable release tag: {ref!r}")
    commit = json.loads(fetch(f"https://api.github.com/repos/{REPOSITORY}/commits/{urllib.parse.quote(ref, safe='')}"))["sha"]
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Invalid upstream commit SHA")
    if previous and previous.get("tag") == ref and previous.get("commit") != commit:
        raise ValueError(f"Locked Flowseal tag {ref} moved to a different commit")
    if previous and previous["commit"] == commit:
        validate(VENDOR, previous)
        return {"changed": False, "tag": previous["tag"], "commit": commit, "core_version": previous["core_version"]}
    files = extract_sources(fetch(f"https://codeload.github.com/{REPOSITORY}/zip/{commit}"))
    original_aes_hash = sha256(files[AES_SOURCE])
    files[AES_SOURCE] = patch_android_aes(files[AES_SOURCE])
    version_match = re.search(rb'__version__\s*=\s*[\"\']([^\"\']+)', files["proxy/__init__.py"])
    if not version_match:
        raise ValueError("Unable to read upstream core version")
    core_version = version_match[1].decode("ascii")
    if core_version != ref.removeprefix("v"):
        raise ValueError("Release tag and core version disagree")
    lock = {"repository": REPOSITORY, "tag": ref, "commit": commit, "core_version": core_version,
            "android_patch_base": {AES_SOURCE: original_aes_hash},
            "files": {path: sha256(data) for path, data in sorted(files.items())}}
    VENDOR.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="flowseal-", dir=VENDOR.parent) as temporary:
        staged = Path(temporary) / "flowseal"
        for path, data in files.items():
            target = staged / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        validate(staged, lock)
        # Only our dedicated vendor folder can be replaced, never a computed shell path.
        if VENDOR.resolve() != (ROOT.resolve() / "upstream" / "flowseal"):
            raise ValueError("Refusing to replace a vendor directory outside the workspace")
        if VENDOR.exists():
            shutil.rmtree(VENDOR)
        shutil.move(str(staged), str(VENDOR))
        LOCK.write_text(json.dumps(lock, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return {"changed": True, "tag": ref, "commit": commit, "core_version": core_version}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", help="Exact stable tag; omitted means latest stable release")
    parser.add_argument("--check", action="store_true", help="Verify local lock and bytes without network or changes")
    args = parser.parse_args()
    if args.check:
        lock = json.loads(LOCK.read_text(encoding="utf-8"))
        validate(VENDOR, lock)
        result = {"changed": False, "tag": lock["tag"], "commit": lock["commit"], "core_version": lock["core_version"]}
    else:
        result = sync(args.ref)
    print(json.dumps(result))
    if output := os.environ.get("GITHUB_OUTPUT"):
        with open(output, "a", encoding="utf-8") as stream:
            for key, value in result.items():
                stream.write(f"{key}={str(value).lower() if isinstance(value, bool) else value}\n")


if __name__ == "__main__":
    main()
