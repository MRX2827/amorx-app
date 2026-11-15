#!/usr/bin/env python3
"""Prepare the VLC runtime for the native AmorX packages."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

VERSION = "3.0.24"
BASE = f"https://download.videolan.org/pub/vlc/{VERSION}"
WIN_ARCHIVE = f"vlc-{VERSION}-win64.zip"
WIN_SHA256 = "fcf30850371ad10c9373cc4f0f4501e7dee49e3e9ae9f20c72fb2661a1ca6323"


def prepare_windows(out, temp):
    archive = temp / WIN_ARCHIVE
    urllib.request.urlretrieve(f"{BASE}/win64/{WIN_ARCHIVE}", archive)
    with archive.open("rb") as source:
        if hashlib.file_digest(source, "sha256").hexdigest() != WIN_SHA256:
            raise ValueError("VLC Windows ZIP does not match the official checksum")
    with zipfile.ZipFile(archive) as z:
        z.extractall(temp / "unpacked")
    source = temp / "unpacked" / f"vlc-{VERSION}"
    shutil.copytree(source / "plugins", out / "plugins")
    for name in ["libvlc.dll", "libvlccore.dll", "COPYING.txt", "THANKS.txt"]:
        shutil.copy2(source / name, out)


def prepare_macos(out, source):
    source = source / "Contents" / "MacOS"
    libraries = out / "lib"
    libraries.mkdir()
    for real, alias in [("libvlc.5.dylib", "libvlc.dylib"), ("libvlccore.9.dylib", "libvlccore.dylib")]:
        shutil.copy2(source / "lib" / real, libraries / alias)
    plugins = out / "plugins"
    shutil.copytree(source / "plugins", plugins, ignore=shutil.ignore_patterns("plugins.dat"))
    for binary in [*libraries.glob("*.dylib"), *plugins.glob("*.dylib")]:
        dependencies = subprocess.check_output(["otool", "-L", binary], text=True)
        if "@rpath/libvlccore.dylib (" in dependencies:
            relative = "@loader_path/libvlccore.dylib" if binary.parent == libraries else "@loader_path/../lib/libvlccore.dylib"
            subprocess.run(["install_name_tool", "-change", "@rpath/libvlccore.dylib", relative, binary], check=True)
    for binary in [*libraries.glob("*.dylib"), *plugins.glob("*.dylib")]:
        subprocess.run(["codesign", "--force", "--sign", "-", binary], check=True, capture_output=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--vlc-app", type=Path, default=Path("/Applications/VLC.app"))
    args = parser.parse_args()
    target = Path(__file__).resolve().parent.parent / "build/desktop-resources/common/vlc"
    if target.exists() and (target / "SOURCE.txt").exists():
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=target.parent) as scratch:
        temp = Path(scratch)
        out = temp / "vlc"
        out.mkdir()
        if os.name == "nt":
            prepare_windows(out, temp)
        elif sys.platform == "darwin":
            prepare_macos(out, args.vlc_app)
        else:
            parser.error("the portable build targets Windows and Intel macOS")
        (out / "SOURCE.txt").write_text(
            f"VLC {VERSION} by VideoLAN\nSource: {BASE}/vlc-{VERSION}.tar.xz\n"
            "Licenses: https://www.videolan.org/legal.html\n", encoding="utf-8"
        )
        out.rename(target)
    print(target)


if __name__ == "__main__":
    main()
