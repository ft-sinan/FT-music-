#!/usr/bin/env python3
"""Offline tests for checking the ABI and ELF architecture of release APKs."""
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "verify-apk-abi.py"
spec = importlib.util.spec_from_file_location("verify_apk_abi", SCRIPT)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


def elf(abi, machine=None):
    elf_class, expected_machine = checker.ARCHITECTURES[abi]
    header = bytearray(20)
    header[:4] = b"\x7fELF"
    header[4] = elf_class
    header[5] = 1
    struct.pack_into("<H", header, 18, expected_machine if machine is None else machine)
    return bytes(header)


def apk(path, entries):
    with zipfile.ZipFile(path, "w") as archive:
        for abi, name, data in entries:
            archive.writestr(f"lib/{abi}/{name}", data)


class ApkArchitectureTest(unittest.TestCase):
    def test_single_and_universal_architectures(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            universal = root / "universal.apk"
            single = root / "arm64.apk"
            apk(universal, [(abi, "libexample.so", elf(abi)) for abi in checker.ARCHITECTURES])
            apk(single, [("arm64-v8a", "libexample.so", elf("arm64-v8a"))])
            checker.verify_apk(universal, "universal")
            checker.verify_apk(single, "arm64-v8a", universal)
            with self.assertRaises(ValueError):
                checker.verify_apk(single, "x86_64")
            with self.assertRaises(ValueError):
                checker.verify_apk(single, "universal")

    def test_rejects_mislabeled_or_missing_libraries(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            broken = root / "broken.apk"
            apk(broken, [("arm64-v8a", "libexample.so", elf("arm64-v8a", machine=62))])
            with self.assertRaisesRegex(ValueError, "ELF architecture"):
                checker.verify_apk(broken, "arm64-v8a")
            apk(broken, [("arm64-v8a", "libexample.so", b"not ELF")])
            with self.assertRaisesRegex(ValueError, "Invalid"):
                checker.verify_apk(broken, "arm64-v8a")
            with zipfile.ZipFile(broken, "w") as archive:
                archive.writestr("classes.dex", b"test")
            with self.assertRaisesRegex(ValueError, "no native"):
                checker.verify_apk(broken, "arm64-v8a")

    def test_rejects_mismatched_library_set_and_extra_abi(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            universal = root / "universal.apk"
            single = root / "single.apk"
            apk(universal, [(abi, "libone.so", elf(abi)) for abi in checker.ARCHITECTURES])
            apk(single, [("x86_64", "libtwo.so", elf("x86_64"))])
            with self.assertRaisesRegex(ValueError, "differ"):
                checker.verify_apk(single, "x86_64", universal)
            apk(single, [("x86_64", "libone.so", elf("x86_64")), ("x86", "libone.so", elf("x86"))])
            with self.assertRaisesRegex(ValueError, "Expected"):
                checker.verify_apk(single, "x86_64")


if __name__ == "__main__":
    unittest.main()
