#!/usr/bin/env python3
"""Offline tests for the emulator UI hierarchy capture helper."""
import subprocess
import unittest
from unittest.mock import patch

import UiSmoke


class UiHierarchyCaptureTest(unittest.TestCase):
    def test_retries_when_uiautomator_returns_success_but_file_is_missing(self):
        attempts = 0

        def adb(*args, check=True):
            nonlocal attempts
            if args[:3] == ("shell", "uiautomator", "dump"):
                attempts += 1
                return subprocess.CompletedProcess(args, 0, b"UI hierarchy dumped", b"")
            if args[:2] == ("shell", "cat"):
                if attempts == 1:
                    return subprocess.CompletedProcess(args, 1, b"", b"No such file")
                return subprocess.CompletedProcess(args, 0, b'<?xml version="1.0"?><hierarchy><node text="FT Music"/></hierarchy>', b"")
            return subprocess.CompletedProcess(args, 0, b"", b"")

        with patch.object(UiSmoke, "adb", side_effect=adb), patch.object(UiSmoke.time, "sleep") as sleep:
            tree = UiSmoke.dump()
        self.assertEqual(attempts, 2)
        self.assertEqual(UiSmoke.labels(tree), ["FT Music"])
        sleep.assert_called_once_with(0.5)

    def test_persistent_missing_file_fails_after_three_attempts(self):
        attempts = 0

        def adb(*args, check=True):
            nonlocal attempts
            if args[:3] == ("shell", "uiautomator", "dump"):
                attempts += 1
            if args[:2] == ("shell", "cat"):
                return subprocess.CompletedProcess(args, 1, b"", b"No such file")
            return subprocess.CompletedProcess(args, 0, b"", b"")

        with patch.object(UiSmoke, "adb", side_effect=adb), patch.object(UiSmoke.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "after 3 attempts: No such file"):
                UiSmoke.dump()
        self.assertEqual(attempts, 3)

    def test_dump_error_and_malformed_xml_are_retried(self):
        attempts = 0

        def adb(*args, check=True):
            nonlocal attempts
            if args[:3] == ("shell", "uiautomator", "dump"):
                attempts += 1
                if attempts == 1:
                    return subprocess.CompletedProcess(args, 0, b"ERROR: device busy", b"")
            if args[:2] == ("shell", "cat"):
                body = b'<?xml version="1.0"?><hierarchy>' if attempts == 2 else b'<?xml version="1.0"?><hierarchy/>'
                return subprocess.CompletedProcess(args, 0, body, b"")
            return subprocess.CompletedProcess(args, 0, b"", b"")

        with patch.object(UiSmoke, "adb", side_effect=adb), patch.object(UiSmoke.time, "sleep"):
            tree = UiSmoke.dump()
        self.assertEqual(tree.tag, "hierarchy")
        self.assertEqual(attempts, 3)

    def test_persistently_malformed_xml_fails(self):
        def adb(*args, check=True):
            body = b'<?xml version="1.0"?><hierarchy>' if args[:2] == ("shell", "cat") else b""
            return subprocess.CompletedProcess(args, 0, body, b"")

        with patch.object(UiSmoke, "adb", side_effect=adb), patch.object(UiSmoke.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "UI hierarchy XML was invalid"):
                UiSmoke.dump()


if __name__ == "__main__":
    unittest.main()
