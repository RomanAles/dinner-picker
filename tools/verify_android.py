"""Install a DinnerPicker APK and exercise its native UI using adb."""

import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "app.dinnerpicker"
ACTIVITY = PACKAGE + "/.MainActivity"
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


class Android:
    def __init__(self, adb, serial):
        self.adb = [str(adb), "-s", serial]
        self.serial = serial

    def run(self, *args, timeout=60):
        result = subprocess.run([*self.adb, *args], text=True, encoding="utf-8", errors="replace",
                                capture_output=True, check=False, timeout=timeout)
        if result.returncode:
            raise RuntimeError((result.stderr or result.stdout).strip()
                               or f"adb {' '.join(args)} exited with {result.returncode}")
        return result.stdout.strip()

    def shell(self, *args, timeout=60):
        return self.run("shell", *args, timeout=timeout)

    def dump(self):
        path = "/data/local/tmp/dinner-picker-ui.xml"
        self.shell("uiautomator", "dump", path, timeout=20)
        return list(ET.fromstring(self.run("exec-out", "cat", path)).iter("node"))

    def matches(self, node, labels):
        return any(label.casefold() in node.get(attribute, "").casefold()
                   for label in labels for attribute in ("text", "content-desc"))

    def find(self, *labels, scroll=False, clickable=False):
        for attempt in range(8):
            for node in self.dump():
                if self.matches(node, labels) and (not clickable or node.get("clickable") == "true"):
                    return node
            if scroll:
                self.scroll(down=True)
            else:
                time.sleep(0.5)
        raise RuntimeError(f"UI element missing: {labels}")

    def tap(self, *labels, scroll=False):
        node = self.find(*labels, scroll=scroll, clickable=True)
        match = BOUNDS.fullmatch(node.get("bounds", ""))
        if not match:
            raise RuntimeError(f"Element has no screen bounds: {labels}")
        x1, y1, x2, y2 = map(int, match.groups())
        self.shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def scroll(self, *, down):
        size = self.shell("wm", "size").splitlines()[-1]
        width, height = map(int, re.search(r"(\d+)x(\d+)", size).groups())
        top, bottom = int(height * 0.22), int(height * 0.80)
        y1, y2 = (bottom, top) if down else (top, bottom)
        self.shell("input", "swipe", str(width // 2), str(y1), str(width // 2), str(y2), "390")

    def to_top(self):
        for _ in range(4):
            self.scroll(down=False)


def verify_discovery(device):
    device.tap("Easy breakfast", scroll=True)
    device.find("Recipes found", scroll=True)
    device.tap("Cheese omelet", scroll=True)
    device.find("A few simple steps in one pan.", scroll=True)

    device.to_top()
    device.tap("Русский")
    device.find("Легко · Завтрак", scroll=True)
    device.to_top()
    device.tap("English")

    device.tap("Choose tags", scroll=True)
    device.find("Maximum minutes for discovery")
    device.tap("Soups", scroll=True)
    device.tap("Show recipes")
    device.find("No exact matches. Similar recipes", scroll=True)
    device.find("Did not match · Dish type", scroll=True)

    device.to_top()
    device.tap("Clear filters", scroll=True)
    device.find("Recipes found", scroll=True)
    device.to_top()


def verify(args):
    device = Android(args.adb, args.serial)
    emulator = args.serial.startswith("emulator-")
    if not emulator and device.shell("settings", "get", "global", "airplane_mode_on") != "1":
        raise ValueError("Enable airplane mode on the connected phone before testing.")
    prior_airplane = device.shell("settings", "get", "global", "airplane_mode_on")
    prior_wifi = device.shell("settings", "get", "global", "wifi_on")
    checks = []
    try:
        if emulator:
            device.shell("cmd", "connectivity", "airplane-mode", "enable")
            device.shell("svc", "wifi", "disable")
        if device.shell("settings", "get", "global", "airplane_mode_on") != "1":
            raise RuntimeError("Android did not enter airplane mode.")

        if emulator and f"package:{PACKAGE}" in device.shell("pm", "list", "packages", PACKAGE).splitlines():
            device.run("uninstall", PACKAGE)
        device.run("install", "-r", str(args.apk.resolve()), timeout=180)
        device.shell("input", "keyevent", "KEYCODE_WAKEUP")
        device.shell("wm", "dismiss-keyguard")
        device.shell("am", "start", "-W", "-n", ACTIVITY)
        device.find("What’s for dinner?", "Что на ужин?")
        checks.append("install_and_launch_in_airplane_mode")

        if any(node.get("text") == "English" for node in device.dump()):
            device.tap("English")
        discovery = any(node.get("text") == "Ideas for today" for node in device.dump())
        if discovery:
            verify_discovery(device)
            checks.append("discovery_presets_filters_partial_results_and_language_offline")
            device.tap("Search by ingredients", scroll=True)
        device.tap("Use an example", scroll=True)
        device.tap("Find dinner", scroll=True)
        device.find("Recipe cards:", scroll=True)
        device.tap("Cheese omelet", scroll=True)
        device.find("eggs · 4 pc(s)", scroll=True)
        checks.append("pantry_time_group_and_portions_en")

        device.to_top()
        device.tap("Русский")
        device.find("Что на ужин?")
        device.tap("Заполнить пример", scroll=True)
        device.tap("Подобрать ужин", scroll=True)
        device.find("Карточек:", scroll=True)
        device.tap("Омлет с сыром", scroll=True)
        device.find("яйца · 4 шт.", scroll=True)
        checks.append("language_switch_and_pantry_ru")

        device.to_top()
        device.tap("Поиск по названию" if discovery else "Весь каталог", scroll=True)
        device.tap("Все рецепты")
        device.find(f"Каталог · найдено: {args.recipes}", scroll=True)
        checks.append("all_bundled_recipes_available_offline")

        device.to_top()
        device.tap("English")
        device.tap("Search by name" if discovery else "Browse catalog", scroll=True)
        device.find("Recipe name or part of it")
        device.shell("input", "text", args.query)
        device.tap("Search")
        device.tap(args.recipe_title, scroll=True)
        device.find("Source and authors · Wikibooks", scroll=True)
        checks.append("title_search_and_attribution_offline")
    finally:
        if emulator:
            if prior_airplane != "1":
                device.shell("cmd", "connectivity", "airplane-mode", "disable")
            if prior_wifi == "1":
                device.shell("svc", "wifi", "enable")
    return {
        "serial": args.serial,
        "kind": "emulator" if emulator else "physical",
        "android": device.shell("getprop", "ro.build.version.release"),
        "api": device.shell("getprop", "ro.build.version.sdk"),
        "packaged_recipes": args.recipes,
        "checks": checks,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--recipes", type=int, required=True)
    parser.add_argument("--query", required=True, help="ASCII search term for a bundled Wikibooks recipe")
    parser.add_argument("--recipe-title", required=True)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        result = verify(args)
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError, ET.ParseError) as error:
        parser.exit(1, f"{error}\n")
    if args.report:
        args.report.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
