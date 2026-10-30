"""Strict Android release versions; no dependencies or network required."""
from dataclasses import dataclass
from pathlib import Path
import argparse
import json
import re

@dataclass(frozen=True)
class AndroidVersion:
    name: str
    code: int


def parse_tag(tag: str) -> AndroidVersion:
    match = re.fullmatch(r"android-v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", tag)
    if not match:
        raise ValueError("Expected an exact android-vMAJOR.MINOR.PATCH tag")
    major, minor, patch = map(int, match.groups())
    if minor > 99 or patch > 99:
        raise ValueError("MINOR and PATCH must be in 0..99 to avoid versionCode collisions")
    code = major * 10000 + minor * 100 + patch
    if not 0 < code <= 2_100_000_000:
        raise ValueError("versionCode outside the positive Android range")
    return AndroidVersion(f"{major}.{minor}.{patch}", code)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("tag")
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    try:
        version = parse_tag(args.tag)
    except ValueError as exc:
        parser.error(str(exc))
    if args.github_output:
        with args.github_output.open("a") as out:
            out.write(f"name={version.name}\ncode={version.code}\n")
    else:
        print(json.dumps({"versionName": version.name, "versionCode": version.code}))

if __name__ == "__main__":
    main()
