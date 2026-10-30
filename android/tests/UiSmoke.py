#!/usr/bin/env python3
"""Smoke test a separately signed FT Music APK on an unlocked emulator.

Set FT_TEST_APK to the APK path and ANDROID_UI_OUTPUT to an output directory.
This test does not enter credentials or verify YouTube account synchronization.
"""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import traceback
import xml.etree.ElementTree as ET

APK = Path(os.environ.get("FT_TEST_APK", ""))
PACKAGE = "com.ftmusic.app"
OUT = Path(os.environ.get("ANDROID_UI_OUTPUT") or tempfile.mkdtemp(prefix="ft-ui-"))
OUT.mkdir(parents=True, exist_ok=True)


def adb(*args, check=True):
    return subprocess.run(["adb", *args], check=check, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def dump():
    adb("shell", "rm", "-f", "/sdcard/ft-window.xml", check=False)
    result = adb("shell", "uiautomator", "dump", "/sdcard/ft-window.xml", check=False)
    if result.returncode or b"ERROR" in result.stdout:
        raise RuntimeError(result.stdout.decode("utf-8", "replace"))
    text = adb("shell", "cat", "/sdcard/ft-window.xml").stdout.decode("utf-8", "replace")
    return ET.fromstring(text[text.find("<?xml"):])


def labels(tree):
    return [value for node in tree.iter("node") for value in (node.get("text", ""), node.get("content-desc", "")) if value]


def wait_for(label, timeout=120):
    end = time.monotonic() + timeout
    last = []
    while time.monotonic() < end:
        try:
            tree = dump()
            last = labels(tree)
            if label in last:
                return tree
        except (RuntimeError, subprocess.CalledProcessError, ET.ParseError):
            pass
        time.sleep(1)
    raise AssertionError(f"Could not find {label!r}; visible labels: {last[:80]}")


def tap(label):
    tree = wait_for(label)
    for node in tree.iter("node"):
        if label not in (node.get("text", ""), node.get("content-desc", "")):
            continue
        bounds = [int(n) for n in re.findall(r"\d+", node.get("bounds", ""))]
        if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
            adb("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
            return
    raise AssertionError(f"No tappable bounds for {label!r}")


def capture(name):
    (OUT / f"{name}.png").write_bytes(adb("exec-out", "screencap", "-p").stdout)
    (OUT / f"{name}.xml").write_text(ET.tostring(dump(), encoding="unicode"), encoding="utf-8")


def main():
    if not APK.is_file():
        raise SystemExit("Set FT_TEST_APK to a signed APK built from this source")
    adb("logcat", "-c", check=False)
    adb("install", "-r", str(APK))
    adb("shell", "pm", "clear", PACKAGE)
    adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS", check=False)
    try:
        adb("shell", "am", "start", "-W", "-n", PACKAGE + "/echo.music.iad1tya.MainActivity")
        welcome = wait_for("Continue")
        if "FT Music" not in labels(welcome):
            raise AssertionError(f"FT Music branding not visible: {labels(welcome)}")
        capture("welcome")
        tap("Continue")
        wait_for("Account")
        capture("home")
        tap("Account")
        wait_for("Anonymous")
        tap("Anonymous")
        wait_for("Login")
        # This checks that the provider's page loaded, without entering credentials.
        wait_for("Sign in", timeout=60)
        capture("login")
        if not adb("shell", "pidof", PACKAGE, check=False).stdout.strip():
            raise AssertionError("FT Music process exited unexpectedly")
        print("FT Music smoke: install, launch, branding, home and YouTube login entry passed")
        print("No Google credentials were entered; account sync was not tested.")
    except Exception:
        try:
            capture("failure")
        except Exception:
            pass
        (OUT / "logcat.txt").write_bytes(adb("logcat", "-d", "-s", "AndroidRuntime:E", check=False).stdout)
        message = traceback.format_exc()[:3500].replace("%", "%25").replace("\n", "%0A").replace("\r", "%0D")
        print("::error title=FT Music emulator smoke::" + message, flush=True)
        raise


if __name__ == "__main__":
    main()
