#!/usr/bin/env python3
"""Basic Android emulator smoke test. Requires adb and a built debug APK.

Run from the repository root with an unlocked emulator: python3 android/tests/UiSmoke.py
Screenshots go to $ANDROID_UI_OUTPUT or a temporary directory, never into the repo.
"""
import os
import pathlib
import re
import subprocess
import tempfile
import time
import traceback
import xml.etree.ElementTree as ET

APK = pathlib.Path("android/app/build/outputs/apk/debug/app-debug.apk")
PACKAGE = "com.ftmusic.app"
OUTPUT = pathlib.Path(os.environ.get("ANDROID_UI_OUTPUT") or tempfile.mkdtemp(prefix="ft-music-ui-"))
OUTPUT.mkdir(parents=True, exist_ok=True)


def adb(*args, check=True):
    return subprocess.run(["adb", *args], check=check, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/ft-window.xml")
    return adb("shell", "cat", "/sdcard/ft-window.xml").stdout.decode("utf-8", "replace")


def wait_for(needle, timeout=25):
    deadline = time.monotonic() + timeout
    last = ""
    while time.monotonic() < deadline:
        last = dump()
        if needle in last:
            return last
        time.sleep(0.5)
    raise AssertionError(f"Could not find {needle!r} on screen. UI: {last[:8000]}")


def tap(label, attribute="content-desc"):
    xml = wait_for(label)
    root = ET.fromstring(xml[xml.find("<?xml"):])
    for node in root.iter("node"):
        if node.get(attribute) != label:
            continue
        numbers = [int(n) for n in re.findall(r"\d+", node.get("bounds", ""))]
        if len(numbers) != 4 or numbers[2] <= numbers[0] or numbers[3] <= numbers[1]:
            continue
        adb("shell", "input", "tap", str((numbers[0] + numbers[2]) // 2), str((numbers[1] + numbers[3]) // 2))
        return
    raise AssertionError(f"No visible {attribute}={label!r} in UI")


def screenshot(name):
    result = adb("exec-out", "screencap", "-p")
    (OUTPUT / f"{name}.png").write_bytes(result.stdout)


def main():
    if not APK.exists():
        raise SystemExit(f"Build a debug APK first: {APK}")
    adb("install", "-r", str(APK))
    adb("shell", "pm", "clear", PACKAGE)
    adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS", check=False)
    # This is an isolated emulator. Testing offline also exercises a useful empty/error state.
    adb("shell", "svc", "wifi", "disable", check=False)
    adb("shell", "svc", "data", "disable", check=False)
    try:
        adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
        home = wait_for("Listen Now")
        if "Blue Skies" in home or "HoliznaCC0" in home or "Open now playing" in home:
            raise AssertionError("Fresh Home must not display a preset demo song or mini-player")
        screenshot("home")

        tap("Explore")
        wait_for("Chill")
        screenshot("explore")
        tap("Search")
        search = wait_for("Find your next favorite")
        if "Blue Skies" in search:
            raise AssertionError("Empty search must not seed sample songs")
        screenshot("search")

        tap("Library")
        wait_for("Check for updates")
        screenshot("library")
        tap("Home")
        tap("Open queue")
        wait_for("Your queue is empty")

        # The samples remain accessible deliberately; a song appears only after explicit selection.
        tap("Search")
        tap("Song, artist, or video link", attribute="text")
        adb("shell", "input", "text", "Blue%sSkies")
        wait_for("Play Blue Skies")
        adb("shell", "input", "keyevent", "4")  # hide keyboard
        tap("Play Blue Skies")
        wait_for("Open now playing")
        tap("Home")
        current_home = wait_for("Listen Now")
        if 'content-desc="Play Blue Skies"' in current_home:
            raise AssertionError("Bundled sample must not be promoted in Home recents")
        tap("Open now playing")
        wait_for("Close now playing")
        screenshot("player")
        if not adb("shell", "pidof", PACKAGE, check=False).stdout.strip():
            raise AssertionError("FT Music process is not running")
        print("Android UI smoke: Home, Explore, Search, Library, empty queue and explicit playback passed")
        print("Screenshots:", OUTPUT)
    except Exception:
        try:
            screenshot("failure")
            (OUTPUT / "failure.xml").write_text(dump(), encoding="utf-8")
            (OUTPUT / "logcat.txt").write_bytes(adb("logcat", "-d", "-s", "AndroidRuntime:E", check=False).stdout)
        except Exception:
            pass
        message = traceback.format_exc()[:3500].replace("%", "%25").replace("\n", "%0A").replace("\r", "%0D")
        print("::error title=Android UI smoke::" + message, flush=True)
        raise
    finally:
        adb("shell", "svc", "wifi", "enable", check=False)
        adb("shell", "svc", "data", "enable", check=False)


if __name__ == "__main__":
    main()
