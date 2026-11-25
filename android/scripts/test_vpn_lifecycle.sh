#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
adb install app/build/outputs/apk/emulator/debug/app-emulator-debug.apk
adb install app/build/outputs/apk/androidTest/emulator/debug/app-emulator-debug-androidTest.apk
result="$(adb shell am instrument -w \
  -e class com.amurcanov.tgwsproxy.DpiLifecycleInstrumentedTest \
  com.prokrastinaciya.zaprethub.test/androidx.test.runner.AndroidJUnitRunner)"
printf '%s\n' "$result"
grep -Eq '^OK \(3 tests\)' <<< "$result"
