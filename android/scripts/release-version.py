#!/usr/bin/env python3
"""Validate the shared Android version formula and optional existing release tags."""
import argparse
import re
from pathlib import Path

VERSION = re.compile(r"(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\Z")


def parse_version(version):
    match = VERSION.fullmatch(version)
    if not match:
        raise ValueError("Version must be numeric major.minor.patch without leading zeroes")
    major, minor, patch = map(int, match.groups())
    if major > 2099 or minor > 999 or patch > 999:
        raise ValueError("Version exceeds Android versionCode limits")
    code = major * 1_000_000 + minor * 1_000 + patch
    if code <= 0:
        raise ValueError("versionCode must be positive")
    return code


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--properties", default=str(Path(__file__).resolve().parents[1] / "version.properties"))
    parser.add_argument("--existing-tags", help="File with one published/draft release tag per line")
    parser.add_argument("--code-only", action="store_true")
    args = parser.parse_args()
    lines = Path(args.properties).read_text(encoding="utf-8").splitlines()
    versions = [line.split("=", 1)[1].strip() for line in lines if line.startswith("versionName=")]
    if len(versions) != 1:
        raise SystemExit("Expected exactly one versionName in version.properties")
    version = versions[0]
    try:
        code = parse_version(version)
    except ValueError as error:
        raise SystemExit(str(error))
    if args.existing_tags:
        for tag in Path(args.existing_tags).read_text(encoding="utf-8").splitlines():
            if not tag.startswith("v"):
                continue
            try:
                old_code = parse_version(tag[1:])
            except ValueError:
                continue
            if old_code >= code:
                raise SystemExit(f"Version {version} is not newer than existing release {tag}")
    print(code if args.code_only else version)


if __name__ == "__main__":
    main()
