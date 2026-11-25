"""Create update metadata from the already signed ARM64 release artifact."""
import argparse
import hashlib
import json
from pathlib import Path


def write_metadata(apk: Path, version_code: int, version_name: str) -> dict:
    if version_code <= 0 or apk.suffix != ".apk" or not apk.is_file():
        raise ValueError("An existing APK and a positive versionCode are required")
    result = {"versionCode": version_code, "versionName": version_name, "apk": apk.name,
              "sha256": hashlib.sha256(apk.read_bytes()).hexdigest()}
    (apk.parent / "update.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    (apk.parent / "SHA256SUMS").write_text(f"{result['sha256']}  {apk.name}\n", encoding="utf-8")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--version-name", required=True)
    args = parser.parse_args()
    print(json.dumps(write_metadata(args.apk, args.version_code, args.version_name)))
