"""Reject tampering of the pinned ByeByeDPI reference: files, nested sources,
dependency licensing, submodules and tag.

The vendored snapshot is the immutability baseline for the later native
integration and update CI.  Five independent failure modes must each end in a
non-zero exit from the sync script:

* a changed vendored parent file               -> offline ``--check`` fails;
* a changed nested dependency source           -> offline ``--check`` fails;
* a tampered dependency license inventory      -> offline ``--check`` fails;
* a changed submodule commit                   -> online ``--ref`` resolution fails;
* a moved tag                                  -> online ``--ref`` resolution fails.
"""
from __future__ import annotations

import contextlib
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]


def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / f"{name}.py")
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


sync = module("sync_byebyedpi")

TAG = "v.1.8.2"
COMMIT = "96a3c1ff555043d915af814aa4f0e9a572c89aba"
SUB_URL = "https://github.com/example/sub.git"
NESTED_URL = "https://github.com/example/nested.git"
GITMODULES = '[submodule "sub"]\n\tpath = sub\n\turl = https://github.com/example/sub.git\n'
NESTED_GITMODULES = '[submodule "nested"]\n\tpath = nested\n\turl = https://github.com/example/nested.git\n'

GPL = b"GPL-3.0-only license text\n"
MIT = b"MIT license text\n"
BSD = b"BSD-3-Clause license text\n"
MAIN_KT = b"package demo\n"
ENGINE_C = b"int main(void){return 0;}\n"
CORE_C = b"void core(void){}\n"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def build_fixture(directory: Path):
    """Write a minimal, internally consistent v2 vendor tree and lock file.

    The fixture has two nesting levels: parent -> ``sub`` -> ``nested``, so the
    offline checks exercise recursive file hashing and per-dependency licenses.
    """
    vendor = directory / "byebyedpi"
    (vendor / "app").mkdir(parents=True)
    (vendor / "sub" / "src").mkdir(parents=True)
    (vendor / "sub" / "nested" / "core").mkdir(parents=True)
    (vendor / "LICENSE").write_bytes(GPL)
    (vendor / ".gitmodules").write_bytes(GITMODULES.encode())
    (vendor / "app" / "Main.kt").write_bytes(MAIN_KT)
    (vendor / "sub" / "LICENSE").write_bytes(MIT)
    (vendor / "sub" / ".gitmodules").write_bytes(NESTED_GITMODULES.encode())
    (vendor / "sub" / "src" / "engine.c").write_bytes(ENGINE_C)
    (vendor / "sub" / "nested" / "LICENSE").write_bytes(BSD)
    (vendor / "sub" / "nested" / "core" / "core.c").write_bytes(CORE_C)
    lock = {
        "schema": 2,
        "repository": sync.REPOSITORY,
        "url": sync.URL,
        "tag": TAG,
        "commit": COMMIT,
        "licenses": {"LICENSE": {"spdx": "GPL-3.0-only", "sha256": sha(GPL)}},
        "files": {
            ".gitmodules": sha(GITMODULES.encode()),
            "LICENSE": sha(GPL),
            "app/Main.kt": sha(MAIN_KT),
        },
        "submodules": {
            "sub": {
                "url": SUB_URL,
                "commit": "a" * 40,
                "licenses": {"LICENSE": {"spdx": "MIT", "sha256": sha(MIT)}},
                "files": {
                    ".gitmodules": sha(NESTED_GITMODULES.encode()),
                    "LICENSE": sha(MIT),
                    "src/engine.c": sha(ENGINE_C),
                },
                "submodules": {
                    "nested": {
                        "url": NESTED_URL,
                        "commit": "b" * 40,
                        "licenses": {"LICENSE": {"spdx": "BSD-3-Clause", "sha256": sha(BSD)}},
                        "files": {
                            "LICENSE": sha(BSD),
                            "core/core.c": sha(CORE_C),
                        },
                    }
                },
            }
        },
    }
    lock_path = directory / "byebyedpi.lock.json"
    lock_path.write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")
    return vendor, lock_path, lock


def patch_paths(lock_path, vendor):
    stack = contextlib.ExitStack()
    stack.enter_context(mock.patch.object(sync, "LOCK", lock_path))
    stack.enter_context(mock.patch.object(sync, "VENDOR", vendor))
    return stack


class IntegrityTests(unittest.TestCase):
    def test_changed_file_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            vendor, lock_path, _ = build_fixture(Path(temporary))
            (vendor / "app" / "Main.kt").write_bytes(b"package demo CHANGED\n")
            with patch_paths(lock_path, vendor):
                with self.assertRaises(SystemExit) as raised:
                    sync.main(["--check"])
                self.assertEqual(raised.exception.code, 1)

    def test_nested_source_tamper_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            vendor, _, lock = build_fixture(Path(temporary))
            (vendor / "sub" / "nested" / "core" / "core.c").write_bytes(b"void core(void){ int x; }\n")
            with self.assertRaisesRegex(ValueError, "modified.*core\\.c"):
                sync.validate(vendor, lock)

    def test_dependency_license_inventory_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            vendor, _, lock = build_fixture(Path(temporary))
            del lock["submodules"]["sub"]["licenses"]
            with self.assertRaisesRegex(ValueError, "Missing licenses"):
                sync.validate(vendor, lock)

    def test_changed_submodule_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            vendor, lock_path, _ = build_fixture(Path(temporary))
            moved = {"sub": {"url": SUB_URL, "commit": "1" * 40}}
            with patch_paths(lock_path, vendor), \
                    mock.patch.object(sync, "resolve_commit", return_value=COMMIT), \
                    mock.patch.object(sync, "resolve_submodules", return_value=moved):
                with self.assertRaises(SystemExit) as raised:
                    sync.main(["--ref", TAG])
                self.assertEqual(raised.exception.code, 1)

    def test_tag_moved_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            vendor, lock_path, _ = build_fixture(Path(temporary))
            with patch_paths(lock_path, vendor), \
                    mock.patch.object(sync, "resolve_commit", return_value="2" * 40):
                with self.assertRaises(SystemExit) as raised:
                    sync.main(["--ref", TAG])
                self.assertEqual(raised.exception.code, 1)

    def test_clean_checkout_passes_offline(self):
        with tempfile.TemporaryDirectory() as temporary:
            vendor, lock_path, _ = build_fixture(Path(temporary))
            with patch_paths(lock_path, vendor):
                sync.main(["--check"])  # must not raise


if __name__ == "__main__":
    unittest.main()
