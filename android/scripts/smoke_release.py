"""Launch a minified release on an emulator before debug instrumentation.

CI signs the prebuilt release with its temporary debug fixture key. This script
never receives release signing keys. --skip-install inspects an already-installed
release and never installs/uninstalls it; useful for reproducing a fixed build.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess
import time

DEFAULT_PACKAGE = "com.prokrastinaciya.zaprethub"
DEFAULT_ACTIVITY = "com.amurcanov.tgwsproxy.MainActivity"


def validate_launch(output: str) -> None:
    # am start, like am instrument, can return shell status zero on failure.
    if re.search(r"(?im)^\s*(?:Error:|Exception|java\.lang\.)", output):
        raise ValueError("Android reported a release launch error")
    if not re.search(r"(?im)^\s*Status:\s*ok\s*$", output):
        raise ValueError("Release launch did not report Status: ok")


def parse_pids(output: str) -> frozenset[int]:
    value = output.strip()
    if not re.fullmatch(r"\d+(?:\s+\d+)*", value):
        return frozenset()
    return frozenset(int(item) for item in value.split() if int(item) > 0)


def validate_package(output: str) -> None:
    if re.search(r"(?im)^\s*(?:pkgFlags|flags)\s*=\s*\[[^\]]*\bDEBUGGABLE\b", output):
        raise ValueError("Smoke gate was given a debuggable APK instead of a release")


def owned_failure(log: str, package: str) -> bool:
    name = re.escape(package)
    # Ignore unrelated system/Google Play crashes on the same emulator. Match
    # explicit process identities, not an arbitrary occurrence in a stack trace.
    return bool(re.search(
        rf"\bProcess:\s*{name}(?=[:,\s]|$)|\bCmdline:\s*{name}(?=[:\s]|$)|"
        rf">>>\s*{name}(?::[^\s<]+)?\s*<<<|\bANR in\s+{name}(?=[:\s]|$)|"
        rf"\bam_anr\b[^\n]*[ ,\[]{name}(?=[,\]\s]|$)", log))


def validate_runtime(observations: list[frozenset[int]], activities: str,
                     crash: str, logcat: str, package: str = DEFAULT_PACKAGE) -> None:
    if owned_failure(crash, package) or owned_failure(logcat, package):
        raise ValueError("Release crashed or became unresponsive during startup")
    if not observations or any(not value for value in observations):
        raise ValueError("Release process is not alive throughout the startup window")
    if any(value != observations[0] for value in observations):
        raise ValueError("Release process restarted during the startup window")
    if not has_resumed_activity(activities, package):
        raise ValueError("Release launcher activity did not remain resumed")


def validate_no_vpn_started(services: str, package: str) -> None:
    component = re.escape(package) + r"/(?:com\.amurcanov\.tgwsproxy)?\.dpi\.DpiVpnService"
    if re.search(rf"(?m)^\s*\*\s+ServiceRecord\{{[^\n]*?\s{component}(?=[\s}}])", services):
        raise ValueError("ByeByeDPI VPN started without an explicit user request")


def has_resumed_activity(activities: str, package: str) -> bool:
    return any(re.search("resumedactivity", line, re.IGNORECASE) and
               re.search(re.escape(package) + r"/", line) for line in activities.splitlines())


def startup_log_window(adb) -> str | None:
    # Some emulator images allow reading logs but deny clearing their buffers.
    clear = adb("logcat", "-b", "main", "-b", "system", "-b", "crash", "-b", "events", "-c", check=False)
    if clear.returncode == 0:
        return None
    device_time = adb("shell", "date '+%m-%d %H:%M:%S.000'").stdout.strip()
    if not re.fullmatch(r"\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.000", device_time):
        raise ValueError("Could not determine the Android startup log window")
    return device_time


def filter_startup_logs(output: str, since: str | None) -> str:
    if since is None:
        return output
    # Older logd implementations can return entries before a requested timestamp.
    # Filter them here and retain continuation lines for entries in the window.
    selected = []
    recent = False
    for line in output.splitlines():
        stamp = re.match(r"^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}", line)
        if stamp:
            timestamp = stamp.group()
            recent = timestamp >= since or (since.startswith("12-31 ") and timestamp.startswith("01-01 "))
        if recent:
            selected.append(line)
    return "\n".join(selected)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--activity", default=DEFAULT_ACTIVITY)
    parser.add_argument("--serial")
    parser.add_argument("--settle-seconds", type=float, default=5)
    parser.add_argument("--startup-timeout", type=float, default=60)
    parser.add_argument("--skip-install", action="store_true")
    parser.add_argument("--keep-installed", action="store_true")
    args = parser.parse_args()
    if not args.skip_install and (args.apk is None or not args.apk.is_file()):
        parser.error("--apk must name the signed minified release unless --skip-install is used")
    if args.settle_seconds < 2 or args.startup_timeout < 5:
        parser.error("--settle-seconds must be at least 2 and --startup-timeout at least 5")
    for value in (args.package, args.activity):
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.]*", value):
            parser.error("Invalid package or activity name")
    adb_command = ["adb", *(["-s", args.serial] if args.serial else [])]

    def adb(*command: str, check: bool = True, timeout: int = 60) -> subprocess.CompletedProcess:
        return subprocess.run([*adb_command, *command], check=check, timeout=timeout,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, encoding="utf-8", errors="replace")

    # Never remove an app from an accidentally connected physical phone.
    if adb("shell", "getprop", "ro.kernel.qemu").stdout.strip() != "1":
        raise RuntimeError("Release smoke installation is restricted to emulators")
    args.report.parent.mkdir(parents=True, exist_ok=True)
    installed_by_smoke = False
    observations: list[frozenset[int]] = []
    launch_output = ""
    package_output = ""
    activities = ""
    diagnostics: dict[str, str] = {}
    failure: Exception | None = None
    log_window: str | None = None
    try:
        if not args.skip_install:
            if "package:" in adb("shell", "pm", "path", args.package, check=False).stdout:
                adb("uninstall", args.package)
            result = adb("install", str(args.apk), timeout=180)
            if not re.search(r"(?m)^Success\s*$", result.stdout):
                raise ValueError("Release APK installation did not report success")
            installed_by_smoke = True
        package_output = adb("shell", "dumpsys", "package", args.package).stdout
        if f"Package [{args.package}]" not in package_output:
            raise ValueError("Expected release package is not installed")
        validate_package(package_output)
        sdk = int(adb("shell", "getprop", "ro.build.version.sdk").stdout.strip())
        # A fresh notification permission dialog legitimately pauses MainActivity.
        # Grant the declared permission so this test measures release startup/UI.
        if sdk >= 33:
            adb("shell", "pm", "grant", args.package, "android.permission.POST_NOTIFICATIONS")
        adb("shell", "am", "force-stop", args.package)
        log_window = startup_log_window(adb)
        started = time.monotonic()
        launch_output = adb("shell", "am", "start", "-W", "-n", f"{args.package}/{args.activity}",
                            check=False, timeout=int(args.startup_timeout)).stdout
        validate_launch(launch_output)

        def sample_runtime():
            crash = adb("logcat", "-d", "-b", "crash", "-v", "threadtime", timeout=15).stdout
            errors = adb("logcat", "-d", "-b", "main", "-b", "system", "-v", "threadtime",
                         "AndroidRuntime:E", "ActivityManager:E", "*:S", timeout=15).stdout
            if (owned_failure(filter_startup_logs(crash, log_window), args.package)
                    or owned_failure(filter_startup_logs(errors, log_window), args.package)):
                raise ValueError("Release crashed or became unresponsive during startup")
            pids = parse_pids(adb("shell", "pidof", args.package, check=False, timeout=15).stdout)
            activity_state = adb("shell", "dumpsys", "activity", "activities", timeout=15).stdout
            services = adb("shell", "dumpsys", "activity", "services", args.package, timeout=15).stdout
            validate_no_vpn_started(services, args.package)
            return pids, activity_state

        # Startup may be slow on loaded CI hosts. Do not mistake a pending first
        # resume for a crash, but reject any real app failure immediately.
        while True:
            pids, activities = sample_runtime()
            if pids and has_resumed_activity(activities, args.package):
                observations.append(pids)
                break
            if time.monotonic() - started >= args.startup_timeout:
                raise ValueError("Release did not become alive and resumed before the startup deadline")
            time.sleep(0.5)

        stable_since = time.monotonic()
        while time.monotonic() - stable_since < args.settle_seconds:
            time.sleep(0.5)
            pids, activities = sample_runtime()
            observations.append(pids)
            validate_runtime(observations, activities, "", "", args.package)
    except Exception as error:
        failure = error
    finally:
        for name, command in {
            "crash": ("logcat", "-d", "-b", "crash", "-v", "threadtime"),
            "logcat": ("logcat", "-d", "-b", "main", "-b", "system", "-b", "events", "-v", "threadtime", "AndroidRuntime:E", "ActivityManager:E", "am_anr:I", "*:S"),
        }.items():
            try:
                diagnostics[name] = adb(*command, check=False, timeout=30).stdout
            except Exception:
                diagnostics[name] = ""
        if failure is None:
            try:
                validate_runtime(
                    observations, activities,
                    filter_startup_logs(diagnostics.get("crash", ""), log_window),
                    filter_startup_logs(diagnostics.get("logcat", ""), log_window),
                    args.package,
                )
            except Exception as error:
                failure = error
        status = "PASSED" if failure is None else f"FAILED ({type(failure).__name__})"
        args.report.write_text(
            f"Release smoke {status}\nPackage: {args.package}\n"
            f"Process samples: {len(observations)}\nRaw diagnostics omitted for privacy\n",
            encoding="utf-8")
        print(f"Release smoke {status}")
        if installed_by_smoke:
            adb("shell", "am", "force-stop", args.package, check=False)
            if not args.keep_installed:
                adb("uninstall", args.package)
    if failure is not None:
        raise RuntimeError("Release smoke failed; raw diagnostics omitted") from None


if __name__ == "__main__":
    main()
