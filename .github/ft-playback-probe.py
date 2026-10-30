#!/usr/bin/env python3
# Temporary, non-gating emulator probe using a generated local tone; no account needed.
import math
import re
import struct
import sys
import time
import traceback
import wave
from pathlib import Path

sys.path.insert(0, "android/tests")
from UiSmoke import OUT, PACKAGE, adb, capture, tap, wait_for


def sessions(name):
    text = adb("shell", "dumpsys", "media_session").stdout.decode("utf-8", "replace")
    (OUT / (name + "-sessions.txt")).write_text(text, encoding="utf-8")
    return text


def run():
    # The main smoke ended at the login page. Use its navigation arrow, not Back
    # (Back may navigate within the Google WebView's own history).
    adb("shell", "input", "tap", "70", "150")
    wait_for("Account", timeout=45)
    adb("shell", "pm", "grant", PACKAGE, "android.permission.READ_MEDIA_AUDIO")
    tone = OUT / "FT_Emulator_Tone.wav"
    with wave.open(str(tone), "wb") as stream:
        stream.setnchannels(1)
        stream.setsampwidth(2)
        stream.setframerate(8000)
        one_second = b"".join(struct.pack("<h", int(4000 * math.sin(2 * math.pi * 440 * i / 8000))) for i in range(8000))
        for _ in range(90):
            stream.writeframesraw(one_second)
    adb("push", str(tone), "/sdcard/Music/FT_Emulator_Tone.wav")
    adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file:///sdcard/Music/FT_Emulator_Tone.wav", check=False)
    time.sleep(4)
    tap("Library")
    try:
        wait_for("Local", timeout=12)
    except AssertionError:
        adb("shell", "input", "swipe", "900", "300", "250", "300", "500")
    tap("Local")
    tap("Scan device")
    tree = wait_for("FT_Emulator_Tone", timeout=90)
    tap("FT_Emulator_Tone")
    time.sleep(8)
    playing = sessions("playing")
    if PACKAGE not in playing or not re.search(r"state\s*=\s*3\b", playing):
        raise AssertionError("The local track did not enter Android's PLAYING media-session state")
    notification = adb("shell", "dumpsys", "notification", "--noredact").stdout.decode("utf-8", "replace")
    (OUT / "notifications.txt").write_text(notification, encoding="utf-8")
    if PACKAGE not in notification:
        raise AssertionError("No FT Music notification appeared for local playback")
    capture("playing")
    adb("shell", "input", "keyevent", "KEYCODE_MEDIA_PAUSE")
    time.sleep(3)
    paused = sessions("paused")
    if PACKAGE not in paused or not re.search(r"state\s*=\s*2\b", paused):
        raise AssertionError("The system media-pause key did not pause the session")
    adb("shell", "input", "keyevent", "KEYCODE_MEDIA_PLAY")
    time.sleep(3)
    resumed = sessions("resumed")
    if PACKAGE not in resumed or not re.search(r"state\s*=\s*3\b", resumed):
        raise AssertionError("The system media-play key did not resume the session")
    print("::notice title=FT_PHONE_MEDIA_PROBE::Local tone playback, media notification and system pause/play passed")


try:
    run()
except Exception:
    try:
        capture("playback-probe-failure")
    except Exception:
        pass
    message = traceback.format_exc()[:3000].replace("%", "%25").replace("\n", "%0A").replace("\r", "%0D")
    print("::warning title=FT_PHONE_MEDIA_PROBE::" + message, flush=True)
