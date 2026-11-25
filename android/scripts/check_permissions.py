"""Fail when a release APK requests permissions beyond the published allowlist.

Play Protect reacted to the 1.1.0 self-updater (REQUEST_INSTALL_PACKAGES) and to
the battery-optimization exemption request. The published app needs only the
permissions below, so any addition is a deliberate decision that must update
this list, the README and the Play Protect story together.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ALLOWED = frozenset({
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
    "android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED",
    "android.permission.INTERNET",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.WAKE_LOCK",
})

# Explicitly called out so the failure names the Play Protect capability rather
# than only saying "not in the allowlist".
FORBIDDEN = frozenset({
    "android.permission.INSTALL_PACKAGES",
    "android.permission.REQUEST_DELETE_PACKAGES",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.PACKAGE_USAGE_STATS",
    "android.permission.QUERY_ALL_PACKAGES",
    "android.permission.READ_SMS",
    "android.permission.RECEIVE_SMS",
    "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.WRITE_SETTINGS",
    "android.permission.BIND_ACCESSIBILITY_SERVICE",
    "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
})

PERMISSION_RE = re.compile(r"uses-permission(?:-sdk-\d+)?: name=['\"]([^'\"]+)['\"]")
PACKAGE_RE = re.compile(r"^package: name=['\"]([^'\"]+)['\"]", re.MULTILINE)


def parse_package(badging: str) -> str:
    match = PACKAGE_RE.search(badging)
    if not match:
        raise ValueError("aapt2 output has no package name")
    return match.group(1)


def parse_permissions(badging: str) -> set[str]:
    result = set()
    for line in badging.splitlines():
        if line.lstrip().startswith("uses-permission"):
            match = PERMISSION_RE.fullmatch(line.strip())
            if not match:
                raise ValueError("Unrecognized uses-permission output")
            result.add(match.group(1))
    return result


def check(permissions: set[str], package: str) -> None:
    forbidden = sorted(permissions & FORBIDDEN)
    if forbidden:
        raise ValueError("Forbidden Play Protect capability: " + ", ".join(forbidden))
    allowed = ALLOWED | {f"{package}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"}
    unexpected = sorted(permissions - allowed)
    if unexpected:
        raise ValueError(
            "Unexpected permission(s): " + ", ".join(unexpected) +
            "; update scripts/check_permissions.py, README.md and docs/PLAY_PROTECT.md intentionally"
        )
    missing = sorted(allowed - permissions)
    if missing:
        raise ValueError("Missing mandatory permission(s): " + ", ".join(missing))


def check_components(tree: str, package: str) -> None:
    if package != "com.prokrastinaciya.zaprethub":
        raise ValueError(f"Unexpected application ID: {package}")
    components = []
    stack = []
    for line in tree.splitlines():
        indent = len(line) - len(line.lstrip())
        if element := re.match(r"\s*E: ([\w-]+)", line):
            while stack and stack[-1][0] >= indent:
                stack.pop()
            kind = element.group(1)
            attributes, actions = {}, []
            stack.append((indent, kind, attributes, actions))
            if kind in {"service", "activity", "activity-alias", "receiver", "provider"}:
                components.append((kind, attributes, actions))
            if kind == "intent-filter":
                for _, parent, attrs, _ in reversed(stack[:-1]):
                    if parent in {"service", "activity", "activity-alias", "receiver", "provider"}:
                        attrs["_intent_filter"] = "true"
                        break
            continue
        attr = re.match(r'\s*A: (?:http://schemas\.android\.com/apk/res/)?android:(\w+)(?:\(0x[\da-f]+\))?=(?:"([^"]*)"|(\S+))', line)
        if not attr or not stack:
            if re.match(r'\s*A: (?:http://schemas\.android\.com/apk/res/)?android:(?:name|exported|permission)\b', line):
                raise ValueError("Unrecognized security-relevant manifest attribute")
            continue
        _, kind, attributes, _ = stack[-1]
        value = attr.group(2) if attr.group(2) is not None else attr.group(3)
        attributes[attr.group(1)] = value
        if kind == "action" and attr.group(1) == "name":
            for _, parent, _, actions in reversed(stack[:-1]):
                if parent in {"service", "activity", "activity-alias", "receiver"}:
                    actions.append(value)
                    break

    exported_policy = {
        ("activity", "com.amurcanov.tgwsproxy.MainActivity"): None,
        ("activity", "com.amurcanov.tgwsproxy.ProxyShortcutActivity"): None,
        ("activity", "com.amurcanov.tgwsproxy.DpiShortcutActivity"): None,
        ("activity", "com.amurcanov.tgwsproxy.ProxyTilePreferencesActivity"): "android.permission.BIND_QUICK_SETTINGS_TILE",
        ("service", "com.amurcanov.tgwsproxy.ProxyTileService"): "android.permission.BIND_QUICK_SETTINGS_TILE",
        ("service", "androidx.work.impl.background.systemjob.SystemJobService"): "android.permission.BIND_JOB_SERVICE",
        ("receiver", "androidx.work.impl.diagnostics.DiagnosticsReceiver"): "android.permission.DUMP",
        ("receiver", "androidx.profileinstaller.ProfileInstallReceiver"): "android.permission.DUMP",
    }
    for kind, attrs, actions in components:
        name = attrs.get("name")
        if not name:
            raise ValueError("Component missing name in manifest output")
        exported = attrs.get("exported")
        if exported not in (None, "true", "false"):
            raise ValueError("Unrecognized exported value")
        # Before Android 12, an intent filter makes omitted exported true.
        effective = exported == "true" or (exported is None and attrs.get("_intent_filter") == "true")
        if effective:
            key = (kind, name)
            if key not in exported_policy or attrs.get("permission") != exported_policy[key]:
                raise ValueError(f"Unexpected exported component or protection: {kind} {name}")

    vpn_action = "android.net.VpnService"
    protection = "android.permission.BIND_VPN_SERVICE"
    vpn = [(attrs, actions) for kind, attrs, actions in components
           if kind == "service" and (vpn_action in actions or attrs.get("permission") == protection)]
    if len(vpn) != 1 or vpn[0][0].get("name") != "com.amurcanov.tgwsproxy.dpi.DpiVpnService":
        raise ValueError("Expected exactly one ByeByeDPI VPN service")
    attrs, actions = vpn[0]
    if attrs.get("permission") != protection or attrs.get("exported") != "false" or vpn_action not in actions:
        raise ValueError("VPN service must be non-exported and protected by BIND_VPN_SERVICE")
    legacy = {f"{package}.action.{action}" for action in ("START", "STOP", "TOGGLE")}
    target = "com.amurcanov.tgwsproxy.ProxyShortcutActivity"
    for kind, attrs, actions in components:
        if kind == "activity" and attrs.get("name") == target and legacy <= set(actions):
            break
    else:
        raise ValueError("Legacy TG actions must remain on ProxyShortcutActivity")
    if any(legacy.intersection(actions) for _, attrs, actions in components if attrs.get("name") != target):
        raise ValueError("Legacy TG actions must not target any other component")

    dpi_legacy = {f"{package}.action.dpi.{action}" for action in ("START", "STOP", "TOGGLE")}
    dpi_target = "com.amurcanov.tgwsproxy.DpiShortcutActivity"
    for kind, attrs, actions in components:
        if kind == "activity" and attrs.get("name") == dpi_target and dpi_legacy <= set(actions):
            break
    else:
        raise ValueError("ByeByeDPI actions must remain on DpiShortcutActivity")
    if any(dpi_legacy.intersection(actions) for _, attrs, actions in components if attrs.get("name") != dpi_target):
        raise ValueError("ByeByeDPI actions must not target any other component")


def find_aapt2(explicit: str | None = None) -> str:
    candidates: list[str] = []
    if explicit:
        candidates.append(explicit)
    if os.environ.get("AAPT2"):
        candidates.append(os.environ["AAPT2"])
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        build_tools = Path(sdk) / "build-tools"
        if build_tools.is_dir():
            candidates.extend(str(path) for path in sorted(build_tools.glob("*/aapt2"), reverse=True))
    which = shutil.which("aapt2")
    if which:
        candidates.append(which)
    for candidate in candidates:
        path = Path(candidate)
        if path.is_file():
            return str(path)
    raise ValueError("aapt2 not found; pass --aapt2 or set AAPT2/ANDROID_HOME")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--aapt2", help="path to aapt2 (default: AAPT2/ANDROID_HOME/PATH)")
    options = parser.parse_args(argv)
    try:
        aapt2 = find_aapt2(options.aapt2)
        result = subprocess.run(
            [aapt2, "dump", "badging", str(options.apk)],
            capture_output=True, text=True, check=False,
        )
        if result.returncode != 0:
            raise ValueError(f"aapt2 failed: {result.stderr.strip() or result.stdout.strip()}")
        package = parse_package(result.stdout)
        permissions = parse_permissions(result.stdout)
        check(permissions, package)
        manifest = subprocess.run(
            [aapt2, "dump", "xmltree", str(options.apk), "--file", "AndroidManifest.xml"],
            capture_output=True, text=True, check=False,
        )
        if manifest.returncode != 0:
            raise ValueError(f"aapt2 manifest dump failed: {manifest.stderr.strip()}")
        check_components(manifest.stdout, package)
    except ValueError as error:
        print(f"Permission check failed: {error}", file=sys.stderr)
        return 1
    print(f"Permission check: {package} requests {len(permissions)} expected permission(s)")
    for permission in sorted(permissions):
        print(f"  {permission}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
