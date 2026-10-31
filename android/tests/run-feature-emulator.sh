#!/usr/bin/env bash
set -euo pipefail

mapfile -t APP < <(find "$RUNNER_TEMP/ft-apks" -name '*.apk' ! -iname '*androidtest*' -type f)
mapfile -t TEST < <(find "$RUNNER_TEMP/ft-apks" -iname '*androidtest*.apk' -type f)
[[ ${#APP[@]} == 1 && ${#TEST[@]} == 1 ]]
adb install -r "${APP[0]}"
adb install -r "${TEST[0]}"
# Prove that device/shared playback does not depend on a stream URL.
adb shell svc wifi disable
adb shell svc data disable
adb shell am instrument -w -r com.ftmusic.app.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$RUNNER_TEMP/instrumentation.log"
grep -Eq 'OK \([0-9]+ tests?\)' "$RUNNER_TEMP/instrumentation.log"
FT_TEST_PACKAGE=com.ftmusic.app.debug ANDROID_UI_OUTPUT="$RUNNER_TEMP/ft-local-delete-ui" python3 android/tests/LocalDeleteUiSmoke.py 2>&1 | tee "$RUNNER_TEMP/local-delete-smoke.log"
adb shell svc wifi enable
adb shell svc data enable
if [[ "${FT_TEST_API:?missing API level}" == '35' ]]; then
  FT_TEST_APK="${APP[0]}" FT_TEST_PACKAGE=com.ftmusic.app.debug ANDROID_UI_OUTPUT="$RUNNER_TEMP/ft-ui" python3 android/tests/UiSmoke.py 2>&1 | tee "$RUNNER_TEMP/ui-smoke.log"
fi
