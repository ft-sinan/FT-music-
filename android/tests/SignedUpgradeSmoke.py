#!/usr/bin/env python3
"""Exercise a signed v0.5 -> v0.6 update and switch APK architecture on an emulator.

Only synthetic emulator data is used. No Google credentials are entered.
FT_OLD_APK, FT_FIRST_APK and FT_SECOND_APK must name already verified APKs.
"""
import os
from pathlib import Path
import re
import traceback

from UiSmoke import OUT, PACKAGE, adb, capture, tap, wait_for

OLD = Path(os.environ["FT_OLD_APK"])
FIRST = Path(os.environ["FT_FIRST_APK"])
SECOND = Path(os.environ["FT_SECOND_APK"])
MARKER_PATH = f"/data/user/0/{PACKAGE}/files/ft-synthetic-upgrade-marker"
MARKER = b"FT-Music-Synthetic-Upgrade"


def launch():
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/echo.music.iad1tya.MainActivity")


def assert_version(expected):
    info = adb("shell", "dumpsys", "package", PACKAGE).stdout.decode("utf-8", "replace")
    if not re.search(rf"versionCode={expected}\b", info):
        raise AssertionError(f"Installed app does not have version code {expected}")


def assert_marker():
    if adb("shell", "cat", MARKER_PATH).stdout.strip() != MARKER:
        raise AssertionError("Synthetic private app data was lost during the update")


def main():
    for apk in (OLD, FIRST, SECOND):
        if not apk.is_file():
            raise SystemExit(f"Missing signed APK: {apk}")
    try:
        adb("install", str(OLD))
        adb("shell", "pm", "clear", PACKAGE)
        api = int(adb("shell", "getprop", "ro.build.version.sdk").stdout.strip())
        permission = "android.permission.READ_MEDIA_AUDIO" if api >= 33 else "android.permission.READ_EXTERNAL_STORAGE"
        adb("shell", "pm", "grant", PACKAGE, permission)
        if api >= 33:
            adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS", check=False)
        assert_version(5000)
        launch()
        wait_for("Continue", timeout=120)
        tap("Continue")
        wait_for("Account", timeout=120)
        adb("shell", "am", "force-stop", PACKAGE)
        adb("root")
        adb("wait-for-device")
        if adb("shell", "id", "-u").stdout.strip() != b"0":
            raise AssertionError("The emulator must permit checking synthetic private data as root")
        adb("shell", f"echo {MARKER.decode()} > {MARKER_PATH}")
        assert_marker()
        for apk in (FIRST, SECOND):
            adb("install", "-r", str(apk))
            assert_version(6000)
            assert_marker()
            launch()
            wait_for("Account", timeout=120)
            adb("shell", "am", "force-stop", PACKAGE)
        capture("signed-upgrade")
        print(f"Signed upgrade passed on API {api}: v0.5 -> {FIRST.name} -> {SECOND.name}; synthetic private data preserved")
    except Exception:
        try:
            capture("signed-upgrade-failure")
        except Exception:
            pass
        (OUT / "signed-upgrade-logcat.txt").write_bytes(adb("logcat", "-d", "-s", "AndroidRuntime:E", check=False).stdout)
        message = traceback.format_exc()[:3500].replace("%", "%25").replace("\n", "%0A").replace("\r", "%0D")
        print("::error title=Signed upgrade smoke::" + message, flush=True)
        raise


if __name__ == "__main__":
    main()
