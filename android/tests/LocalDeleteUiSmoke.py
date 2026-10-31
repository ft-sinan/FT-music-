#!/usr/bin/env python3
"""Exercise one-file local deletion and cancellation on an emulator, using generated audio only."""
import os
from pathlib import Path
import re
import time
import traceback

from UiSmoke import PACKAGE, OUT, adb, capture, dump, labels, tap, wait_for

SOURCE = Path(__file__).resolve().parents[1] / "app/src/androidTest/assets/test-tone.wav"
TARGET = "/sdcard/Music/FT-Delete-Smoke.wav"
OTHER = "/sdcard/Music/FT-Keep-Smoke.wav"
TARGET_TITLE = "FT-Delete-Smoke"
OTHER_TITLE = "FT-Keep-Smoke"


def shell(*args, check=True):
    return adb("shell", *args, check=check).stdout.decode("utf-8", "replace")


def exists(path):
    return adb("shell", "test", "-f", path, check=False).returncode == 0


def screen_size():
    found = re.search(r"(\d+)x(\d+)", shell("wm", "size"))
    if not found:
        raise AssertionError("Unable to determine emulator screen size")
    return int(found.group(1)), int(found.group(2))


def node_bounds(tree, label):
    for node in tree.iter("node"):
        if label in (node.get("text", ""), node.get("content-desc", "")):
            bounds = [int(n) for n in re.findall(r"\d+", node.get("bounds", ""))]
            if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                return bounds
    return None


def tap_if_visible(label):
    tree = dump()
    bounds = node_bounds(tree, label)
    if not bounds:
        return False
    shell("input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
    return True


def ensure_media_indexed(name):
    end = time.monotonic() + 45
    while time.monotonic() < end:
        result = shell("content", "query", "--uri", "content://media/external/audio/media",
                       "--projection", "_id:_display_name", check=False)
        if name in result:
            return
        time.sleep(1)
    raise AssertionError(f"MediaStore did not index {name}")


def open_local():
    tap("Library")
    wait_for("Playlists", timeout=90)
    width, height = screen_size()
    for _ in range(6):
        tree = dump()
        if node_bounds(tree, "Local"):
            tap("Local")
            return
        anchor = node_bounds(tree, "Artists") or node_bounds(tree, "Albums") or node_bounds(tree, "Playlists")
        y = (anchor[1] + anchor[3]) // 2 if anchor else int(height * 0.18)
        shell("input", "swipe", str(int(width * 0.85)), str(y), str(int(width * 0.2)), str(y), "400")
    raise AssertionError(f"Local library filter not visible: {labels(dump())[:80]}")


def open_song_menu():
    tree = wait_for(TARGET_TITLE, timeout=90)
    bounds = node_bounds(tree, TARGET_TITLE)
    if not bounds:
        raise AssertionError("Song has no visible bounds")
    width, height = screen_size()
    shell("input", "tap", str(int(width * 0.92)), str((bounds[1] + bounds[3]) // 2))
    for _ in range(4):
        tree = dump()
        if node_bounds(tree, "Delete from device"):
            return
        shell("input", "swipe", str(width // 2), str(int(height * 0.86)), str(width // 2), str(int(height * 0.42)), "400")
    raise AssertionError(f"Device-delete option missing: {labels(dump())[:80]}")


def approve_android_if_needed():
    end = time.monotonic() + 25
    while time.monotonic() < end and exists(TARGET):
        tree = dump()
        # Only tap a button belonging to Android, never the app's own confirmation twice.
        for node in tree.iter("node"):
            if node.get("package") == PACKAGE or node.get("enabled") == "false":
                continue
            if node.get("text") not in ("Allow", "Delete", "Move to trash", "While using the app"):
                continue
            bounds = [int(n) for n in re.findall(r"\d+", node.get("bounds", ""))]
            if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                shell("input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
                break
        time.sleep(0.5)
    if exists(TARGET):
        raise AssertionError(f"The selected file still exists; labels: {labels(dump())[:80]}")


def main():
    api = int(shell("getprop", "ro.build.version.sdk").strip())
    shell("pm", "clear", PACKAGE)
    audio_permission = "android.permission.READ_MEDIA_AUDIO" if api >= 33 else "android.permission.READ_EXTERNAL_STORAGE"
    shell("pm", "grant", PACKAGE, audio_permission)
    if api >= 33:
        shell("pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS", check=False)
    for path in (TARGET, OTHER):
        adb("push", str(SOURCE), path)
        shell("am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file://" + path)
        ensure_media_indexed(Path(path).name)
    try:
        shell("am", "start", "-W", "-n", PACKAGE + "/echo.music.iad1tya.MainActivity")
        wait_for("Continue", timeout=90)
        tap("Continue")
        open_local()
        wait_for("Scan device", timeout=60)
        tap("Scan device")
        wait_for(TARGET_TITLE, timeout=90)
        wait_for(OTHER_TITLE, timeout=90)
        open_song_menu()
        tap("Delete from device")
        confirmation = wait_for("Delete this song?", timeout=30)
        if not any("cannot be undone" in label for label in labels(confirmation)):
            raise AssertionError(f"The physical-deletion warning was not visible: {labels(confirmation)}")
        # The confirmation is owned by the activity, not the transient song menu.
        # Recreate the activity as a rotation would, then verify cancellation still works.
        shell("settings", "put", "system", "accelerometer_rotation", "0")
        shell("settings", "put", "system", "user_rotation", "1")
        time.sleep(3)
        wait_for("Delete this song?", timeout=30)
        shell("settings", "put", "system", "user_rotation", "0")
        time.sleep(3)
        wait_for("Delete this song?", timeout=30)
        tap("Cancel")
        if not exists(TARGET) or not exists(OTHER):
            raise AssertionError("Cancel unexpectedly removed an audio file")
        open_song_menu()
        tap("Delete from device")
        wait_for("Delete this song?", timeout=30)
        tap("Delete")
        approve_android_if_needed()
        end = time.monotonic() + 30
        while time.monotonic() < end:
            visible = labels(dump())
            if OTHER_TITLE in visible and TARGET_TITLE not in visible:
                break
            time.sleep(0.5)
        else:
            raise AssertionError(f"Local listing did not update after deletion: {visible[:80]}")
        if not exists(OTHER):
            raise AssertionError("An unrelated song was deleted")
        capture("local-delete-success")
        print(f"Local delete smoke passed on API {api}: cancellation kept both files; confirmation deleted only the selected file")
    except Exception:
        try:
            capture("local-delete-failure")
        except Exception:
            pass
        message = traceback.format_exc()[:3500].replace("%", "%25").replace("\n", "%0A").replace("\r", "%0D")
        print("::error title=Local audio deletion smoke::" + message, flush=True)
        raise
    finally:
        shell("settings", "put", "system", "user_rotation", "0", check=False)
        shell("settings", "put", "system", "accelerometer_rotation", "1", check=False)
        for path in (TARGET, OTHER):
            shell("rm", "-f", path, check=False)


if __name__ == "__main__":
    main()
