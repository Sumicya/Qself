#!/usr/bin/env python3
"""Audit the actual APK in CI; never inspect signing private keys or account/device data."""
import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from pathlib import Path


def run(*command):
    return subprocess.run(command, check=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, text=True, timeout=90).stdout


def validate_package(badging):
    package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    if not package or package[1] != "io.github.qauxv" or not package[2].isdigit():
        raise RuntimeError("Unexpected package or invalid versionCode")
    # Keep the repository's ordinary commit-count rule. A deliberate lower versionCode
    # than the old branch is allowed; never invent an upgrade offset on the user's behalf.
    revision = re.search(r"\.r([0-9]+)\.", package[3])
    if not revision or int(package[2]) <= 0 or int(package[2]) != int(revision[1]):
        raise RuntimeError("versionCode must equal the commit count in versionName")
    return package


def audit(apk, build_tools):
    signature = run(str(build_tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk))
    badging = run(str(build_tools / "aapt2"), "dump", "badging", str(apk))
    package = validate_package(badging)
    if "application-debuggable" in badging:
        raise RuntimeError("Distribution APK must not be debuggable")
    with zipfile.ZipFile(apk) as archive:
        prop = archive.read("META-INF/xposed/module.prop").decode()
        if "autoHotReload=false" not in prop or "targetApiVersion=102" not in prop:
            raise RuntimeError("Unexpected Xposed lifecycle metadata")
        if "lib/arm64-v8a/libqauxv-core0.so" not in archive.namelist():
            raise RuntimeError("arm64 native loader missing")
        dex = b"".join(archive.read(name) for name in archive.namelist()
                       if re.fullmatch(r"classes\d*\.dex", name))
        for descriptor in (b"Lsumicya/qself/diagnostics/ReportDiagnostics;",
                           b"Lsumicya/qself/glass/LiquidGlassInstaller;",
                           b"Lsumicya/qself/ui/SettingsHomeView;",
                           b"Lsumicya/qself/ui/SettingsAppearanceItem;"):
            if descriptor not in dex:
                raise RuntimeError("Diagnostics, glass or native settings implementation missing")
        if b"Lcom/microsoft/appcenter/" in dex:
            raise RuntimeError("AppCenter SDK descriptors remain in the APK")
    certificate = re.search(r"certificate SHA-256 digest: ([a-fA-F0-9]+)", signature)
    if not certificate:
        raise RuntimeError("Signing certificate digest missing from verification output")
    return {
        "file": apk.name, "bytes": apk.stat().st_size,
        "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "package": package[1], "versionCode": int(package[2]), "versionName": package[3],
        "signerSha256": certificate[1], "debuggable": False,
        "targetApiVersion": 102, "autoHotReload": False,
        "arm64NativeLoader": True, "diagnosticsAndGlassPresent": True, "nativeSettingsPresent": True, "appCenterAbsent": True,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--build-tools", required=True)
    parser.add_argument("--apk-dir", type=Path, required=True)
    args = parser.parse_args()
    files = sorted(args.apk_dir.glob("*.apk"))
    if not files:
        raise RuntimeError("No APK produced")
    reports = [audit(apk, args.sdk / "build-tools" / args.build_tools) for apk in files]
    (args.apk_dir / "diagnostic-apk-report.json").write_text(json.dumps(reports, ensure_ascii=False, indent=2))
    for report in reports:
        text = json.dumps(report, ensure_ascii=False)
        text = text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print("::notice title=Qself APK verified::" + text)


if __name__ == "__main__":
    main()
