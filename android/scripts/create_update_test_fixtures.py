#!/usr/bin/env python3
"""Create APKs signed by test keys for package and signer checks.

Run after assembling the debug target so its debug key exists. The Android
test runner checks that the OS rejects wrong-key.apk as an update.
"""
import argparse
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ANDROID = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID)


def tool(directory, name):
    path = directory / (name + (".bat" if name == "apksigner" else ".exe") if os.name == "nt" else name)
    if not path.is_file():
        raise RuntimeError(f"Required tool missing: {path}")
    return path


def run(arguments):
    result = subprocess.run([str(x) for x in arguments], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(f"{Path(arguments[0]).name} failed: {result.stderr.strip() or result.stdout.strip()}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"), required=not (os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")))
    parser.add_argument("--output", default="build/tgws-updater-fixtures")
    parser.add_argument("--keystore", default=str(Path.home() / ".android" / "debug.keystore"))
    parser.add_argument("--storepass", default="android")
    parser.add_argument("--alias", default="androiddebugkey")
    parser.add_argument("--keypass", default=None)
    parser.add_argument("--version-code", type=int, default=1000, help="Installed target app's versionCode")
    parser.add_argument("--package", default="com.prokrastinaciya.zaprethub")
    args = parser.parse_args()
    sdk = Path(args.sdk).resolve()
    output = Path(args.output).resolve()
    keystore = Path(args.keystore).expanduser().resolve()
    if not keystore.is_file():
        raise RuntimeError("Debug keystore is missing. Assemble the target debug APK before generating fixtures.")
    if not (0 < args.version_code < 2_000_000_000):
        raise RuntimeError("Invalid target version code")
    build_tools = max((sdk / "build-tools").iterdir(), key=lambda item: tuple(int(x) for x in item.name.split('.') if x.isdigit()))
    aapt2, zipalign, apksigner = (tool(build_tools, name) for name in ("aapt2", "zipalign", "apksigner"))
    android_jar = sdk / "platforms" / "android-35" / "android.jar"
    if not android_jar.is_file():
        raise RuntimeError("Android SDK platform 35 is required")
    java_home = os.environ.get("JAVA_HOME")
    java = shutil.which("java")
    keytool = Path(java_home) / "bin" / ("keytool.exe" if os.name == "nt" else "keytool") if java_home else Path(java).resolve().parent / ("keytool.exe" if os.name == "nt" else "keytool") if java else None
    if keytool is None or not keytool.is_file():
        found = shutil.which("keytool")
        keytool = Path(found) if found else None
    if keytool is None:
        raise RuntimeError("Set JAVA_HOME to a JDK containing keytool")
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="fixture-work-", dir=output.parent) as directory:
        work = Path(directory).resolve()
        assert work.is_relative_to(output.parent.resolve())
        foreign_key = work / "foreign-test-key.jks"
        run([keytool, "-genkeypair", "-noprompt", "-keystore", foreign_key,
             "-storepass", "android", "-keypass", "android", "-alias", "foreign",
             "-dname", "CN=Updater Test Foreign Key", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-storetype", "JKS"])
        for name, package, version, foreign in (
            ("valid", args.package, args.version_code + 1, False),
            ("same-version", args.package, args.version_code, False),
            ("wrong-package", args.package + ".foreign", args.version_code + 1, False),
            ("wrong-key", args.package, args.version_code + 1, True),
        ):
            manifest = ET.Element("manifest", {"package": package, f"{{{ANDROID}}}versionCode": str(version), f"{{{ANDROID}}}versionName": f"fixture-{version}"})
            ET.SubElement(manifest, "uses-sdk", {f"{{{ANDROID}}}minSdkVersion": "24", f"{{{ANDROID}}}targetSdkVersion": "35"})
            ET.SubElement(manifest, "application", {f"{{{ANDROID}}}hasCode": "false", f"{{{ANDROID}}}label": "Updater verification fixture"})
            manifest_path = work / (name + ".xml")
            ET.ElementTree(manifest).write(manifest_path, encoding="utf-8", xml_declaration=True)
            unsigned, aligned = work / (name + "-unsigned.apk"), work / (name + "-aligned.apk")
            run([aapt2, "link", "--manifest", manifest_path, "-I", android_jar, "-o", unsigned])
            run([zipalign, "-P", "16", "-f", "4", unsigned, aligned])
            destination = output / (name + ".apk")
            run([apksigner, "sign", "--ks", foreign_key if foreign else keystore,
                 "--ks-key-alias", "foreign" if foreign else args.alias,
                 "--ks-pass", "pass:android" if foreign else "pass:" + args.storepass,
                 "--key-pass", "pass:android" if foreign else "pass:" + (args.keypass or args.storepass),
                 "--min-sdk-version", "24", "--out", destination, aligned])
            run([apksigner, "verify", "--min-sdk-version", "24", destination])
            print(f"Created {name}.apk (package={package}, versionCode={version}, foreignSigner={foreign})")
    print(f"Fixture directory: {output}")


if __name__ == "__main__":
    main()
