#!/usr/bin/env python3
"""Compile packages/gen5w.autolock/app_src into app/Gen5WAutoLock.apk (API 19, v1 signed)."""
from __future__ import annotations

import os
import shutil
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PKG_DIR = ROOT / "packages" / "gen5w.autolock"
APP_SRC = PKG_DIR / "app_src"
BUILD_DIR = ROOT / "build" / "gen5w_autolock"
OUT_APK = PKG_DIR / "app" / "Gen5WAutoLock.apk"

SDK = Path(os.environ.get("ANDROID_HOME") or os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk"))
BT = SDK / "build-tools" / "36.0.0"
ANDROID_JAR = SDK / "platforms" / "android-36" / "android.jar"


def run(cmd: list[str]) -> None:
    print("+", " ".join(cmd))
    subprocess.run(cmd, check=True)


def main() -> None:
    if not ANDROID_JAR.is_file():
        raise SystemExit(f"Missing android.jar at {ANDROID_JAR}")

    if BUILD_DIR.exists():
        shutil.rmtree(BUILD_DIR)
    (BUILD_DIR / "gen").mkdir(parents=True)
    (BUILD_DIR / "classes").mkdir(parents=True)
    (BUILD_DIR / "dex").mkdir(parents=True)
    OUT_APK.parent.mkdir(parents=True, exist_ok=True)

    res_zip = BUILD_DIR / "res.zip"
    base_apk = BUILD_DIR / "base.apk"
    unsigned_apk = BUILD_DIR / "unsigned.apk"
    aligned_apk = BUILD_DIR / "aligned.apk"

    # 1. Compile resources & link base APK
    run([str(BT / "aapt2.exe"), "compile", "--dir", str(APP_SRC / "res"), "-o", str(res_zip)])
    run([
        str(BT / "aapt2.exe"), "link",
        "-I", str(ANDROID_JAR),
        "--manifest", str(APP_SRC / "AndroidManifest.xml"),
        "--min-sdk-version", "19",
        "--target-sdk-version", "19",
        "--java", str(BUILD_DIR / "gen"),
        "-o", str(base_apk),
        str(res_zip),
    ])

    # 2. Compile Java sources
    java_files = [str(p) for p in (APP_SRC / "java").rglob("*.java")]
    java_files += [str(p) for p in (BUILD_DIR / "gen").rglob("*.java")]
    run([
        "javac", "--release", "8", "-Xlint:-options", "-nowarn",
        "-cp", str(ANDROID_JAR),
        "-d", str(BUILD_DIR / "classes"),
        *java_files,
    ])

    # 3. Dex for API 19 (KitKat)
    class_files = [str(p) for p in (BUILD_DIR / "classes").rglob("*.class")]
    run([
        str(BT / "d8.bat"), "--release", "--min-api", "19",
        "--lib", str(ANDROID_JAR),
        "--output", str(BUILD_DIR / "dex"),
        *class_files,
    ])

    # 4. Merge classes.dex into APK
    shutil.copy2(base_apk, unsigned_apk)
    with zipfile.ZipFile(unsigned_apk, "a", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.write(BUILD_DIR / "dex" / "classes.dex", "classes.dex")

    # 5. Zipalign
    run([str(BT / "zipalign.exe"), "-f", "4", str(unsigned_apk), str(aligned_apk)])

    # 6. Sign with v1 (JAR) signature for Android 4.4 KitKat
    keystore = ROOT / "build" / "debug.p12"
    if not keystore.is_file():
        run([
            "keytool", "-genkeypair",
            "-keystore", str(keystore),
            "-storetype", "pkcs12",
            "-storepass", "android",
            "-keypass", "android",
            "-alias", "gen5w",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "10000",
            "-dname", "CN=Gen5W AutoLock",
        ])

    run([
        str(BT / "apksigner.bat"), "sign",
        "--ks", str(keystore),
        "--ks-pass", "pass:android",
        "--min-sdk-version", "19",
        "--v2-signing-enabled", "false",
        "--v3-signing-enabled", "false",
        "--out", str(OUT_APK),
        str(aligned_apk),
    ])
    print(f"Built {OUT_APK} ({OUT_APK.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
