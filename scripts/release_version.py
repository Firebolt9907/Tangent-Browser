#!/usr/bin/env python3
"""Generate release metadata with a versionCode shared by the stable and beta feeds."""

import argparse
import re

MAX_VERSION_CODE = 2_100_000_000


def release_metadata(run_number: int, version: str = "", base: int = 1_000_000) -> dict:
    if run_number < 1 or base < 0 or not 0 < base + run_number <= MAX_VERSION_CODE:
        raise ValueError("The release versionCode must be between 1 and 2100000000")
    if version:
        match = re.fullmatch(r"v?((0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*))", version)
        if not match:
            raise ValueError("Stable versions must be MAJOR.MINOR.PATCH (optional leading v)")
        name = match[1]
        tag = f"v{name}"
    else:
        name = tag = f"beta-{run_number}"
    return {
        "tag": tag,
        "version_name": name,
        "version_code": base + run_number,
        "prerelease": str(not bool(version)).lower(),
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-number", required=True, type=int)
    parser.add_argument("--version", default="")
    parser.add_argument("--base", type=int, default=1_000_000)
    args = parser.parse_args()
    try:
        metadata = release_metadata(args.run_number, args.version, args.base)
    except ValueError as error:
        parser.error(str(error))
    for key, value in metadata.items():
        print(f"{key}={value}")
