import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import Mock

spec = importlib.util.spec_from_file_location("smoke_release", Path(__file__).resolve().parents[2] / "scripts" / "smoke_release.py")
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)
PACKAGE = "com.prokrastinaciya.zaprethub"
RESUMED = "topResumedActivity=ActivityRecord{abc u0 com.prokrastinaciya.zaprethub/com.amurcanov.tgwsproxy.MainActivity t9}"
JAVA_CRASH = "FATAL EXCEPTION: main\nProcess: com.prokrastinaciya.zaprethub, PID: 123\njava.lang.RuntimeException: Unable to get provider androidx.startup.InitializationProvider\nCaused by: java.lang.NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init> []"


class ReleaseSmokeTest(unittest.TestCase):
    def test_log_window_starts_at_device_time_when_buffer_clear_is_denied(self):
        adb = Mock(side_effect=[
            subprocess.CompletedProcess([], 1, "failed to clear the main log"),
            subprocess.CompletedProcess([], 0, "11-20 23:45:00.000\n"),
        ])
        self.assertEqual(smoke.startup_log_window(adb), "11-20 23:45:00.000")

    def test_log_window_stays_empty_after_successful_buffer_clear(self):
        adb = Mock(return_value=subprocess.CompletedProcess([], 0, ""))
        self.assertIsNone(smoke.startup_log_window(adb))
        self.assertEqual(adb.call_count, 1)

    def test_old_logd_can_return_stale_logs_without_hiding_a_fresh_crash(self):
        stale = f"11-20 23:44:59.900 123 123 E AndroidRuntime: Process: {PACKAGE}, PID: 123"
        fresh = f"11-20 23:45:00.010 456 456 E AndroidRuntime: Process: {PACKAGE}, PID: 456"
        raw = f"{stale}\nstale details\n{fresh}\nfresh details\n"
        self.assertEqual(
            smoke.filter_startup_logs(raw, "11-20 23:45:00.000"),
            f"{fresh}\nfresh details",
        )

    def test_real_release_startup_crash_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "crashed"):
            smoke.validate_runtime([frozenset({123})], RESUMED, JAVA_CRASH, "")

    def test_foreign_google_play_crash_does_not_fail_healthy_app(self):
        foreign = "FATAL EXCEPTION: main\nProcess: com.google.android.gms.persistent, PID: 222\nCloneNotSupportedException\nCalling application com.prokrastinaciya.zaprethub"
        smoke.validate_runtime([frozenset({123}), frozenset({123})], RESUMED, foreign, "")

    def test_native_crash_and_anr_belonging_to_app_are_rejected(self):
        for text in (f"pid:123 >>> {PACKAGE} <<<", f"Cmdline: {PACKAGE}", f"ANR in {PACKAGE}\nReason: timeout"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                smoke.validate_runtime([frozenset({123})], RESUMED, text, "")

    def test_launcher_does_not_start_the_owned_vpn(self):
        foreign = "  * ServiceRecord{123 u0 org.example/org.example.DpiVpnService}"
        own = f"  * ServiceRecord{{456 u0 {PACKAGE}/com.amurcanov.tgwsproxy.dpi.DpiVpnService}}"
        smoke.validate_no_vpn_started(foreign, PACKAGE)
        with self.assertRaisesRegex(ValueError, "VPN started without"):
            smoke.validate_no_vpn_started(f"{foreign}\n{own}", PACKAGE)

    def test_dead_or_restarted_process_cannot_pass(self):
        for observations in ([], [frozenset()], [frozenset({123}), frozenset()], [frozenset({123}), frozenset({124})]):
            with self.subTest(observations=observations), self.assertRaises(ValueError):
                smoke.validate_runtime(observations, RESUMED, "", "")

    def test_background_or_foreign_activity_is_rejected(self):
        for activities in ("", "mResumedActivity: com.android.permissioncontroller/.GrantPermissionsActivity", "mResumedActivity: com.prokrastinaciya.zaprethub.test/.Activity"):
            with self.subTest(activities=activities), self.assertRaises(ValueError):
                smoke.validate_runtime([frozenset({123})], activities, "", "")

    def test_shell_zero_exit_without_success_is_not_accepted(self):
        for output in ("Error: Activity class does not exist", "Starting: Intent { cmp=app/.Main }", "Status: timeout"):
            with self.subTest(output=output), self.assertRaises(ValueError):
                smoke.validate_launch(output)
        smoke.validate_launch("Starting: Intent {}\nStatus: ok\nActivity: app/.Main\n")

    def test_debug_apk_is_not_a_release_smoke_candidate(self):
        for flags in ("  pkgFlags=[ HAS_CODE DEBUGGABLE ALLOW_BACKUP ]", "    flags=[ DEBUGGABLE HAS_CODE ]"):
            with self.subTest(flags=flags), self.assertRaisesRegex(ValueError, "debuggable"):
                smoke.validate_package(flags)
        smoke.validate_package("  pkgFlags=[ HAS_CODE ALLOW_BACKUP ]")

    def test_invalid_pid_output_is_not_a_live_process(self):
        self.assertEqual(smoke.parse_pids("123 456\n"), frozenset({123, 456}))
        self.assertFalse(smoke.parse_pids("pidof: not found"))
        self.assertFalse(smoke.parse_pids(""))


if __name__ == "__main__":
    unittest.main()
