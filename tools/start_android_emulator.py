"""Launch the project emulator with all Android storage under the local toolchain."""

import argparse
import os
import subprocess
from pathlib import Path

DEFAULT_TOOLCHAIN = Path(__file__).resolve().parents[1] / ".venv" / "android-toolchain"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--toolchain", type=Path, default=DEFAULT_TOOLCHAIN)
    parser.add_argument("--avd", default="DinnerApi35")
    parser.add_argument("--headless", action="store_true")
    parser.add_argument("--list", action="store_true", help="list registered virtual devices")
    args = parser.parse_args()

    root = args.toolchain.resolve()
    sdk = root / "sdk"
    android_user = root / "android-user"
    temporary = root / "temp"
    emulator = sdk / "emulator" / "emulator.exe"
    if not emulator.is_file():
        parser.error(f"Android Emulator is missing at {emulator}")
    temporary.mkdir(parents=True, exist_ok=True)

    environment = os.environ.copy()
    environment.pop("ANDROID_SDK_HOME", None)
    environment.update({
        "ANDROID_HOME": str(sdk),
        "ANDROID_SDK_ROOT": str(sdk),
        "ANDROID_USER_HOME": str(android_user),
        "ANDROID_EMULATOR_HOME": str(android_user),
        "ANDROID_AVD_HOME": str(android_user / "avd"),
        "TEMP": str(temporary),
        "TMP": str(temporary),
    })
    key = android_user / "adbkey"
    if key.is_file():
        environment["ADB_VENDOR_KEYS"] = str(key)

    arguments = ["-list-avds"] if args.list else [
        "-avd", args.avd, "-no-snapshot", "-no-metrics",
    ]
    if args.headless and not args.list:
        arguments += ["-no-window", "-no-audio"]
    return subprocess.run([str(emulator), *arguments], cwd=root, env=environment, check=False).returncode


if __name__ == "__main__":
    raise SystemExit(main())
