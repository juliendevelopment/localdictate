"""Inspect an actual signed arm64 Android delivery APK (not a device test)."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
import zipfile


def parse_badging(text):
    lines = text.splitlines()
    package = next((line for line in lines if line.startswith("package:")), "")
    values = dict(re.findall(r"(name|versionCode|versionName)='([^']*)'", package))
    def sdk(prefix):
        line = next((line for line in lines if line.startswith(prefix)), "")
        match = re.fullmatch(re.escape(prefix) + r"'([0-9]+)'", line)
        if not match: raise ValueError("Missing numeric " + prefix)
        return int(match.group(1))
    if set(values) != {"name", "versionCode", "versionName"}:
        raise ValueError("Incomplete APK package metadata")
    return {"package": values["name"], "versionName": values["versionName"],
            "versionCode": int(values["versionCode"]), "minSdk": sdk("sdkVersion:"),
            "targetSdk": sdk("targetSdkVersion:"), "debuggable": "application-debuggable" in lines}


def signer_digest(text):
    matches = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([a-f0-9]{64})$", text, re.M)
    if len(matches) != 1: raise ValueError("Expected exactly one verified APK signing certificate")
    return matches[0]


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""): digest.update(block)
    return digest.hexdigest()


def inspect(apk, sdk, expected_package, expected_version, expected_cert, scratch, expected_sha=None):
    if not re.fullmatch(r"[a-f0-9]{64}", expected_cert):
        raise ValueError("Certificate must be an exact lowercase SHA-256 digest")
    actual_sha = sha256(apk)
    if expected_sha is not None and expected_sha != actual_sha:
        raise ValueError("APK SHA-256 differs from the expected delivery digest")
    tools = sdk / "build-tools/35.0.0"
    signing = subprocess.check_output([str(tools / "apksigner"), "verify", "--print-certs", str(apk)], text=True)
    certificate = signer_digest(signing)
    if certificate != expected_cert: raise ValueError("Wrong app signing certificate")
    metadata = parse_badging(subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(apk)], text=True))
    if metadata["package"] != expected_package or metadata["versionName"] != expected_version:
        raise ValueError("Wrong package or version")
    if metadata["versionCode"] <= 0 or metadata["minSdk"] != 29 or metadata["targetSdk"] != 35 or metadata["debuggable"]:
        raise ValueError("Unexpected SDK, versionCode or debug flag in release APK")
    subprocess.run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(apk)], check=True, capture_output=True)
    native = []
    scratch.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory(prefix="apk-audit-", dir=scratch) as tmp:
        names = archive.namelist()
        models = [name for name in names if name.lower().endswith((".onnx", ".gguf", ".nemo", ".tflite"))]
        if models: raise ValueError("Model weights unexpectedly bundled in APK")
        for name in names:
            if not name.startswith("lib/") or not name.endswith(".so"): continue
            if not name.startswith("lib/arm64-v8a/"): raise ValueError("Unexpected native ABI")
            dest = Path(tmp) / Path(name).name
            dest.write_bytes(archive.read(name))
            elf = subprocess.check_output(["readelf", "-lW", str(dest)], text=True)
            alignments = [int(line.split()[-1], 16) for line in elf.splitlines() if line.strip().startswith("LOAD ")]
            if not alignments or any(value < 16_384 for value in alignments):
                raise ValueError("Native ELF not aligned for 16 KiB pages: " + name)
            native.append(name)
    if not any("sherpa" in name for name in native): raise ValueError("Missing native recognizer runtime")
    return {**metadata, "sha256": actual_sha, "signingCertificateSha256": certificate,
            "bytes": apk.stat().st_size, "nativeLibraries": native, "zipAndElf16KiB": True,
            "modelWeightsBundled": False, "scope": "Static signed-APK inspection; not phone inference or UI verification"}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--certificate-file", type=Path, required=True)
    parser.add_argument("--scratch", type=Path, required=True)
    parser.add_argument("--expected-sha256")
    args = parser.parse_args()
    result = inspect(args.apk, args.sdk, args.package, args.version,
                     args.certificate_file.read_text().strip(), args.scratch, args.expected_sha256)
    print(json.dumps(result, indent=2))

if __name__ == "__main__": main()
