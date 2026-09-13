#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_HOME:?}"
: "${ANDROID_AVD_HOME:?}"
: "${CI_AVD_NAME:?}"
: "${CI_AVD_PROFILE:?}"
: "${CI_SYSTEM_IMAGE:?}"
: "${CI_EMULATOR_DIAGNOSTICS:?}"

mkdir -p "$ANDROID_AVD_HOME" "$CI_EMULATOR_DIAGNOSTICS"
printf 'no\n' | timeout 120s avdmanager create avd --force \
  --name "$CI_AVD_NAME" --package "$CI_SYSTEM_IMAGE" --device "$CI_AVD_PROFILE"
cat >> "$ANDROID_AVD_HOME/$CI_AVD_NAME.avd/config.ini" <<'CONFIG'
hw.cpu.ncore=2
hw.ramSize=2048
vm.heapSize=512
disk.dataPartition.size=2048M
CONFIG

timeout 15s adb start-server
nohup "$ANDROID_HOME/emulator/emulator" -avd "$CI_AVD_NAME" -port 5554 \
  -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -no-snapshot \
  -camera-back none -partition-size 2048 \
  > "$CI_EMULATOR_DIAGNOSTICS/emulator.log" 2>&1 < /dev/null &
emulator_pid=$!
printf '%s\n' "$emulator_pid" > "$CI_EMULATOR_DIAGNOSTICS/emulator.pid"

# sys.boot_completed can precede Android TV's package/input services and an ADB
# reconnect. Retry only framework setup; Gradle runs once in the next CI step.
boot_deadline=$((SECONDS + 600))
while (( SECONDS < boot_deadline )); do
  if ! kill -0 "$emulator_pid" 2>/dev/null; then
    echo 'Emulator exited before Android became ready.' >&2
    tail -n 100 "$CI_EMULATOR_DIAGNOSTICS/emulator.log" >&2
    exit 1
  fi
  if [[ "$(timeout 10s adb -s emulator-5554 get-state 2>/dev/null || true)" == 'device' ]] &&
     [[ "$(timeout 10s adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)" == '1' ]] &&
     timeout 10s adb -s emulator-5554 shell pm path android > "$CI_EMULATOR_DIAGNOSTICS/package-readiness.log" 2>&1 &&
     grep -q '^package:' "$CI_EMULATOR_DIAGNOSTICS/package-readiness.log" &&
     timeout 15s adb -s emulator-5554 shell input keyevent 82 >> "$CI_EMULATOR_DIAGNOSTICS/input-readiness.log" 2>&1 &&
     timeout 10s adb -s emulator-5554 shell settings put global window_animation_scale 0.0 &&
     timeout 10s adb -s emulator-5554 shell settings put global transition_animation_scale 0.0 &&
     timeout 10s adb -s emulator-5554 shell settings put global animator_duration_scale 0.0; then
    echo 'Android boot, package manager, input service and animation settings are ready.'
    timeout 10s adb -s emulator-5554 shell getprop > "$CI_EMULATOR_DIAGNOSTICS/device-properties.txt"
    exit 0
  fi
  sleep 2
done

echo 'Timed out waiting for Android framework readiness (600 seconds).' >&2
exit 1
