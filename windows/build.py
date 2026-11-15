#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later

"""Build AmorX Windows x64 with a verified GithubRelease APK embedded."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


INITIAL_VERSION = "5.5.499.1318"
INITIAL_VERSION_CODE = 554991318
INITIAL_APK_SHA256 = "1ab3e20108663071ec7274e714210a5806c28c6c07da7efbebaca32cff6877d8"
RELEASES = "https://github.com/MRX2827/amorxsyte/releases/download"


def sha256(file):
    with file.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def android_release(args, parser):
    if args.android_manifest is None:
        return INITIAL_VERSION, INITIAL_APK_SHA256
    try:
        manifest = json.loads(args.android_manifest.read_text(encoding="utf-8"))
        version = manifest["version"]
        digest = manifest["apk"]["x86_64"]["sha256"].lower()
        if not re.fullmatch(r"(?:0|[1-9][0-9]{0,9})(?:\.(?:0|[1-9][0-9]{0,9})){3}", version):
            raise ValueError("invalid version")
        if any(int(part) > 2**32 - 1 for part in version.split(".")):
            raise ValueError("invalid version")
        if not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise ValueError("invalid APK SHA-256")
        return version, digest
    except (OSError, ValueError, TypeError, KeyError, AttributeError) as error:
        parser.error(f"invalid Android update.json: {error}")


def android_version_code(args, version, parser):
    if args.version_code is not None:
        if 0 < args.version_code <= 2_100_000_000:
            return args.version_code
        parser.error("invalid Android version code")
    if version == INITIAL_VERSION:
        return INITIAL_VERSION_CODE
    metadata_file = args.apk.with_name("output-metadata.json")
    if metadata_file.is_file():
        data = json.loads(metadata_file.read_text(encoding="utf-8"))
        for element in data["elements"]:
            if element["outputFile"] == args.apk.name:
                return int(element["versionCode"])
    parser.error("use --version-code when the APK is outside its original Gradle outputs folder")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="AmorX GithubRelease x86_64 APK")
    parser.add_argument("exe", type=Path, help="destination for the Windows x64 EXE")
    parser.add_argument("--android-manifest", type=Path, help="update.json for the Android release")
    parser.add_argument("--version-code", type=int, help="Android versionCode if Gradle metadata is unavailable")
    parser.add_argument("--windows-manifest", type=Path, help="write windows-update.json for the site")
    parser.add_argument("--notes-file", type=Path, help="UTF-8 Windows release notes")
    args = parser.parse_args()
    version, expected_hash = android_release(args, parser)
    version_code = android_version_code(args, version, parser)
    source = Path(__file__).resolve().parent
    target = args.exe.resolve()
    if args.windows_manifest and target.name != f"AmorX-{version}-Windows-x64.exe":
        parser.error(f"use AmorX-{version}-Windows-x64.exe as the release asset name")
    notes = args.notes_file.read_text(encoding="utf-8").strip() if args.notes_file else ""
    target.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix=".amorx-build-", dir=target.parent) as temp:
        stage = Path(temp)
        payload = stage / "payload.apk"
        shutil.copyfile(args.apk, payload)
        if sha256(payload) != expected_hash:
            parser.error(f"the APK does not match AmorX {version} GithubRelease x86_64")

        for file in [*source.glob("*.go"), source / "go.mod", source / "amorx.ico"]:
            shutil.copyfile(file, stage / file.name)

        subprocess.run(
            ["go", "run", "github.com/akavel/rsrc@v0.10.2",
             "-arch", "amd64", "-ico", "amorx.ico", "-o", "amorx_windows_amd64.syso"],
            cwd=stage, check=True,
        )
        env = os.environ | {"GOOS": "windows", "GOARCH": "amd64", "CGO_ENABLED": "0"}
        subprocess.run(["go", "vet", "./..."], cwd=stage, env=env, check=True)
        binary = stage / target.name
        linker_flags = (
            f"-H=windowsgui -s -w -X main.launcherVersion={version} "
            f"-X main.apkVersionCode={version_code}"
        )
        subprocess.run(
            ["go", "build", "-trimpath", "-buildvcs=false", f"-ldflags={linker_flags}", "-o", str(binary), "."],
            cwd=stage, env=env, check=True,
        )
        binary.replace(target)

    if args.windows_manifest:
        manifest = {
            "version": version,
            "notes": notes,
            "exe": {
                "url": f"{RELEASES}/{version}/{target.name}",
                "size": target.stat().st_size,
                "sha256": sha256(target),
            },
        }
        args.windows_manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(args.windows_manifest.resolve())
    print(target)


if __name__ == "__main__":
    main()
