#!/usr/bin/env python3
"""Check that an APK contains exactly the intended Android native architectures.

Use --reference on a single-ABI APK to require the same native library names
as the corresponding architecture in the universal APK. This checks the
package, not runtime behavior; real-device testing is still required.
"""
import argparse
import struct
import zipfile
from pathlib import Path

# ELF class, ELF e_machine, all little-endian on Android.
ARCHITECTURES = {
    "arm64-v8a": (2, 183),
    "armeabi-v7a": (1, 40),
    "x86": (1, 3),
    "x86_64": (2, 62),
}


def inspect_apk(path):
    """Return a mapping of ABI -> set of native library names; reject bad ELF files."""
    libraries = {}
    with zipfile.ZipFile(path) as apk:
        seen = set()
        for info in apk.infolist():
            name = info.filename
            if name in seen:
                raise ValueError(f"Duplicate APK entry: {name}")
            seen.add(name)
            parts = name.split("/")
            if len(parts) != 3 or parts[0] != "lib" or not parts[2].endswith(".so"):
                continue
            abi, library = parts[1], parts[2]
            if abi not in ARCHITECTURES:
                raise ValueError(f"Unexpected native architecture: {abi}")
            with apk.open(info) as item:
                header = item.read(20)
            if len(header) < 20 or header[:4] != b"\x7fELF" or header[5] != 1:
                raise ValueError(f"Invalid or non-little-endian ELF file: {name}")
            elf_class, machine = ARCHITECTURES[abi]
            if header[4] != elf_class or struct.unpack_from("<H", header, 18)[0] != machine:
                raise ValueError(f"ELF architecture does not match its APK directory: {name}")
            libraries.setdefault(abi, set()).add(library)
    if not libraries:
        raise ValueError("APK has no native libraries")
    return libraries


def verify_apk(path, abi, reference=None):
    if abi != "universal" and abi not in ARCHITECTURES:
        raise ValueError(f"Unsupported architecture: {abi}")
    libraries = inspect_apk(path)
    expected = set(ARCHITECTURES) if abi == "universal" else {abi}
    if set(libraries) != expected:
        raise ValueError(f"Expected {sorted(expected)}, found {sorted(libraries)} in {path}")
    if reference is not None:
        if abi == "universal":
            raise ValueError("--reference is only for an architecture-specific APK")
        universal = inspect_apk(reference)
        if set(universal) != set(ARCHITECTURES):
            raise ValueError("Reference APK does not contain all supported architectures")
        missing = universal[abi] - libraries[abi]
        extra = libraries[abi] - universal[abi]
        if missing or extra:
            raise ValueError(f"Native library names differ from universal for {abi}: missing={sorted(missing)}, extra={sorted(extra)}")
    return libraries


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("abi", choices=["universal", *ARCHITECTURES])
    parser.add_argument("--reference", type=Path)
    args = parser.parse_args()
    try:
        libraries = verify_apk(args.apk, args.abi, args.reference)
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        raise SystemExit(str(error)) from error
    summary = ", ".join(f"{abi}: {len(items)} native libraries" for abi, items in sorted(libraries.items()))
    print(f"Verified {args.apk.name}: {summary}")


if __name__ == "__main__":
    main()
