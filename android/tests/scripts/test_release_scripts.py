import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import struct
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / f"{name}.py")
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


sync = module("sync_upstream")
apk_check = module("check_apk")
metadata = module("release_metadata")
permissions = module("check_permissions")


def elf(alignment=16384):
    value = bytearray(120)
    value[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<H", value, 18, 183)
    struct.pack_into("<Q", value, 32, 64)
    struct.pack_into("<HH", value, 54, 56, 1)
    struct.pack_into("<I", value, 64, 1)
    struct.pack_into("<Q", value, 112, alignment)
    return bytes(value)


class IntegrityTests(unittest.TestCase):
    def test_archive_never_extracts_traversal_or_unrelated_files(self):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            for name in ("LICENSE", "proxy/__init__.py", "proxy/tg_ws_proxy.py", "tests/test_core.py", "../../escape", "proxy/../../escape", "ui/main.py"):
                archive.writestr("repo/" + name, name.encode())
        files = sync.extract_sources(stream.getvalue())
        self.assertEqual(set(files), {"LICENSE", "proxy/__init__.py", "proxy/tg_ws_proxy.py", "tests/test_core.py"})

    def test_archive_requires_full_runtime_and_tests(self):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            archive.writestr("repo/LICENSE", b"MIT")
        with self.assertRaises(ValueError):
            sync.extract_sources(stream.getvalue())

    def test_modified_missing_and_added_vendor_files_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary)
            (path / "LICENSE").write_bytes(b"MIT")
            lock = {"repository": sync.REPOSITORY, "commit": "a" * 40, "files": {"LICENSE": hashlib.sha256(b"MIT").hexdigest()}}
            sync.validate(path, lock)
            (path / "LICENSE").write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "modified"):
                sync.validate(path, lock)
            (path / "LICENSE").unlink()
            with self.assertRaisesRegex(ValueError, "missing"):
                sync.validate(path, lock)
            (path / "LICENSE").write_bytes(b"MIT")
            (path / "extra.txt").write_bytes(b"extra")
            with self.assertRaisesRegex(ValueError, "added"):
                sync.validate(path, lock)


class ApkTests(unittest.TestCase):
    def test_both_dpi_libraries_required_for_selected_abi(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "app.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("lib/x86_64/libbyedpi.so", elf())
                archive.writestr("lib/arm64-v8a/libhev-socks5-tunnel.so", elf())
            with self.assertRaisesRegex(ValueError, "libhev-socks5-tunnel"):
                apk_check.check_dpi_libraries(apk, "x86_64")
            with zipfile.ZipFile(apk, "a") as archive:
                archive.writestr("lib/x86_64/libhev-socks5-tunnel.so", elf())
            self.assertEqual(apk_check.check_dpi_libraries(apk, "x86_64"), 2)

    def test_old_4k_native_binary_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "16 KB"):
            apk_check.load_alignments(elf(4096))

    def test_16k_native_binary_passes(self):
        self.assertEqual(apk_check.load_alignments(elf()), [16384])

    def test_malformed_elf_is_rejected(self):
        for data in (b"invalid", elf()[:80]):
            with self.assertRaises(ValueError):
                apk_check.load_alignments(data)

    def test_unexpected_abi_and_legacy_core_fail(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "app.apk"
            with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("lib/x86_64/libpython.so", elf())
            with self.assertRaisesRegex(ValueError, "ABIs"):
                apk_check.check(apk, "arm64-v8a")
            with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("lib/arm64-v8a/libtgwsproxy.so", elf())
            with self.assertRaisesRegex(ValueError, "Legacy"):
                apk_check.check(apk, "arm64-v8a")

    def test_metadata_checksum_is_for_signed_artifact_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "app-arm64.apk"
            apk.write_bytes(b"signed-apk")
            result = metadata.write_metadata(apk, 1001, "1.0.1")
            self.assertEqual(result["sha256"], hashlib.sha256(b"signed-apk").hexdigest())
            self.assertEqual(json.loads((apk.parent / "update.json").read_text()), result)
            self.assertEqual((apk.parent / "SHA256SUMS").read_text(), f"{result['sha256']}  {apk.name}\n")

    def test_4k_python_extension_inside_imy_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "app.apk"
            nested = io.BytesIO()
            with zipfile.ZipFile(nested, "w") as archive:
                archive.writestr("cryptography/hazmat/bindings/_rust.so", elf(4096))
            with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("lib/arm64-v8a/libpython.so", elf())
                archive.writestr("assets/chaquopy/requirements-arm64-v8a.imy", nested.getvalue())
            with self.assertRaisesRegex(ValueError, "_rust.so"):
                apk_check.check(apk, "arm64-v8a")

    def test_mismatched_nested_python_abi_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "app.apk"
            nested = io.BytesIO()
            data = bytearray(elf())
            struct.pack_into("<H", data, 18, 62)
            with zipfile.ZipFile(nested, "w") as archive:
                archive.writestr("_cffi_backend.so", data)
            with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("lib/arm64-v8a/libpython.so", elf())
                archive.writestr("assets/chaquopy/requirements-arm64-v8a.imy", nested.getvalue())
            with self.assertRaisesRegex(ValueError, "architecture"):
                apk_check.check(apk, "arm64-v8a")

    def test_dex_with_package_installer_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "app.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("classes.dex", b"dex\n035\x00application/vnd.android.package-archive\x00")
            with self.assertRaisesRegex(ValueError, "installer"):
                apk_check.check_no_package_installer(apk)

    def test_dex_with_install_permission_string_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "app.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("classes.dex", b"dex\n035\x00REQUEST_INSTALL_PACKAGES\x00")
            with self.assertRaisesRegex(ValueError, "install-packages"):
                apk_check.check_no_package_installer(apk)

    def test_clean_dex_passes_installer_check(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "app.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("classes.dex", b"dex\n035\x00proxy-engine\x00")
            self.assertEqual(apk_check.check_no_package_installer(apk), 1)

    def test_apk_without_dex_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "app.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("AndroidManifest.xml", b"manifest")
            with self.assertRaisesRegex(ValueError, "no DEX"):
                apk_check.check_no_package_installer(apk)


class PermissionTests(unittest.TestCase):
    badging = (
        "package: name='com.prokrastinaciya.zaprethub' versionCode='1051' versionName='1.1.1'\n"
        "uses-permission: name='android.permission.INTERNET'\n"
        "uses-permission: name='android.permission.ACCESS_NETWORK_STATE'\n"
        "uses-permission: name='com.prokrastinaciya.zaprethub.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'\n"
    )

    def test_badging_parser_reads_package_and_permissions(self):
        self.assertEqual(permissions.parse_package(self.badging), "com.prokrastinaciya.zaprethub")
        self.assertEqual(permissions.parse_permissions(self.badging), {
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "com.prokrastinaciya.zaprethub.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        })

    def test_vpn_service_requires_protection_and_legacy_actions(self):
        tree = '''
E: manifest
  E: application
    E: service
      A: android:name="com.amurcanov.tgwsproxy.dpi.DpiVpnService"
      A: android:permission="android.permission.BIND_VPN_SERVICE"
      A: android:exported=false
      E: intent-filter
        E: action
          A: android:name="android.net.VpnService"
    E: activity
      A: android:name="com.amurcanov.tgwsproxy.ProxyShortcutActivity"
      E: intent-filter
        E: action
          A: android:name="com.prokrastinaciya.zaprethub.action.START"
        E: action
          A: android:name="com.prokrastinaciya.zaprethub.action.STOP"
        E: action
          A: android:name="com.prokrastinaciya.zaprethub.action.TOGGLE"
    E: activity
      A: android:name="com.amurcanov.tgwsproxy.DpiShortcutActivity"
      E: intent-filter
        E: action
          A: android:name="com.prokrastinaciya.zaprethub.action.dpi.START"
        E: action
          A: android:name="com.prokrastinaciya.zaprethub.action.dpi.STOP"
        E: action
          A: android:name="com.prokrastinaciya.zaprethub.action.dpi.TOGGLE"
'''
        permissions.check_components(tree, "com.prokrastinaciya.zaprethub")
        with self.assertRaisesRegex(ValueError, "BIND_VPN_SERVICE"):
            permissions.check_components(tree.replace("BIND_VPN_SERVICE", "INTERNET"), "com.prokrastinaciya.zaprethub")
        with self.assertRaisesRegex(ValueError, "TG actions"):
            permissions.check_components(tree.replace("action.TOGGLE", "action.CHANGED"), "com.prokrastinaciya.zaprethub")
        with self.assertRaisesRegex(ValueError, "ByeByeDPI actions"):
            permissions.check_components(tree.replace("action.dpi.TOGGLE", "action.dpi.CHANGED"), "com.prokrastinaciya.zaprethub")

    def test_expected_permissions_pass(self):
        permissions.check(set(permissions.ALLOWED) | {"com.prokrastinaciya.zaprethub.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"}, "com.prokrastinaciya.zaprethub")

    def test_install_permission_is_named_in_the_error(self):
        declared = permissions.parse_permissions(self.badging) | {"android.permission.REQUEST_INSTALL_PACKAGES"}
        with self.assertRaisesRegex(ValueError, "REQUEST_INSTALL_PACKAGES"):
            permissions.check(declared, "com.prokrastinaciya.zaprethub")

    def test_unexpected_permission_is_rejected(self):
        declared = permissions.parse_permissions(self.badging) | {"android.permission.READ_CONTACTS"}
        with self.assertRaisesRegex(ValueError, "Unexpected permission"):
            permissions.check(declared, "com.prokrastinaciya.zaprethub")


class ManifestTests(unittest.TestCase):
    def test_source_manifest_has_no_forbidden_permissions(self):
        manifest = (ROOT / "app" / "src" / "main" / "AndroidManifest.xml").read_text(encoding="utf-8")
        declared = set(re.findall(r'<uses-permission\s+android:name="([^"]+)"', manifest))
        self.assertFalse(declared & permissions.FORBIDDEN)
        self.assertLessEqual(declared, permissions.ALLOWED)


if __name__ == "__main__":
    unittest.main()


release = module("release_version")


class ReleaseVersionTests(unittest.TestCase):
    def test_first_build_on_a_line_is_patch_zero(self):
        self.assertEqual(release.next_version("1.1", ["v1.0.48", "v1.0.41"]), "1.1.0")

    def test_next_patch_follows_the_highest_tag_on_the_line(self):
        self.assertEqual(release.next_version("1.1", ["v1.1.0", "v1.1.10", "v1.1.2", "v1.10.5"]), "1.1.11")

    def test_version_code_is_derived_from_the_name(self):
        self.assertEqual(release.version_code("1.2.0"), 10200)
        self.assertEqual(release.version_code("1.2.1"), 10201)
        self.assertEqual(release.version_code("1.3.0"), 10300)
        self.assertEqual(release.version_code("v2.0.0"), 20000)

    def test_version_code_stays_above_the_published_builds(self):
        # Published builds used 1000 + CI run number; the 1.1.4 release was 1069.
        self.assertGreater(release.version_code("1.2.0"), 1069)
        self.assertGreater(release.version_code("1.2.1"), release.version_code("1.2.0"))

    def test_version_code_rejects_a_bad_name(self):
        with self.assertRaises(ValueError):
            release.version_code("1.2")
        with self.assertRaises(ValueError):
            release.version_code("1.100.0")

    def test_version_file_must_be_major_minor(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "VERSION"
            path.write_text("1.1\n", encoding="utf-8")
            self.assertEqual(release.release_line(path), "1.1")
            path.write_text("1.1.0", encoding="utf-8")
            with self.assertRaises(ValueError):
                release.release_line(path)

    def test_notes_start_with_the_matching_changelog_section(self):
        with tempfile.TemporaryDirectory() as folder:
            log = Path(folder) / "CHANGELOG.md"
            log.write_text("# Log\n\n## 1.1.1\n\nnext\n\n## 1.1.0\n\n- worker\n- journal\n\n## 1.0.48\n\nold\n",
                           encoding="utf-8")
            notes = release.release_notes("1.1.0", 1050, "1.11.4", log)
            self.assertTrue(notes.startswith("- worker\n- journal\n\n---\n"))
            self.assertNotIn("old", notes)
            self.assertNotIn("next", notes)
            self.assertIn("Android versionCode: 1050", notes)
            self.assertTrue(release.release_notes("1.1.3", 1, "1.11.4", log).startswith("Ядро Flowseal"))
