#!/usr/bin/env python3
"""Package the Windows app image as a portable EXE with the JVM and VLC included."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", type=Path)
    parser.add_argument("exe", type=Path)
    args = parser.parse_args()
    app, exe = args.app.resolve(), args.exe.resolve()
    if not (app / "AmorX.exe").is_file():
        parser.error("expected the AmorX app image from createDistributable")
    exe.parent.mkdir(parents=True, exist_ok=True)
    packaging = Path(__file__).resolve().parent
    with tempfile.TemporaryDirectory(dir=exe.parent) as directory:
        stage = Path(directory)
        with zipfile.ZipFile(stage / "image.zip", "w", zipfile.ZIP_DEFLATED, compresslevel=4) as archive:
            for file in sorted(app.rglob("*")):
                if file.is_file():
                    archive.write(file, file.relative_to(app).as_posix())
        for name in ["AmorX.ico", "portable_windows.go"]:
            shutil.copy2(packaging / name, stage)
        subprocess.run(["go", "run", "github.com/akavel/rsrc@v0.10.2", "-arch", "amd64",
                        "-ico", "AmorX.ico", "-o", "amorx_windows_amd64.syso"], cwd=stage, check=True)
        (stage / "go.mod").write_text("module amorx/portable\n\ngo 1.25\n", encoding="ascii")
        binary = stage / exe.name
        env = os.environ | {"GOOS": "windows", "GOARCH": "amd64", "CGO_ENABLED": "0"}
        subprocess.run(["go", "build", "-buildvcs=false", "-trimpath", "-ldflags=-H=windowsgui -s -w",
                        "-o", str(binary), "."], cwd=stage, env=env, check=True)
        binary.replace(exe)


if __name__ == "__main__":
    main()
