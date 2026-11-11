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
        self.nodes = []

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
        self.nodes = list(ET.fromstring(self.run("exec-out", "cat", path)).iter("node"))
        return self.nodes

    def matches(self, node, labels, *, exact=False):
        values = [node.get(attribute, "").casefold() for attribute in ("text", "content-desc")]
        return any(label.casefold() in values if exact else any(label.casefold() in value for value in values)
                   for label in labels)

    def find(self, *labels, scroll=False, clickable=False, exact=False, widget=None):
        for attempt in range(8):
            for node in self.dump():
                can_tap = node.get("clickable") == "true" or node.get("class") == "android.widget.CheckedTextView"
                if ((widget is None or node.get("class") == widget) and (not clickable or can_tap)
                        and self.matches(node, labels, exact=exact)):
                    return node
            if scroll:
                self.scroll(down=True)
            else:
                time.sleep(0.5)
        raise RuntimeError(f"UI element missing: {labels}")

    def tap(self, *labels, scroll=False, exact=False, widget=None):
        node = self.find(*labels, scroll=scroll, clickable=True, exact=exact, widget=widget)
        match = BOUNDS.fullmatch(node.get("bounds", ""))
        if not match:
            raise RuntimeError(f"Element has no screen bounds: {labels}")
        x1, y1, x2, y2 = map(int, match.groups())
        self.shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        return node

    def scroll(self, *, down):
        for node in self.nodes:
            if node.get("class") in {"android.widget.ScrollView", "android.widget.ListView"} and node.get("scrollable") == "true":
                left, top, right, bottom = map(int, BOUNDS.fullmatch(node.get("bounds")).groups())
                margin = (bottom - top) // 6
                top, bottom = top + margin, bottom - margin
                center = (left + right) // 2
                break
        else:
            size = self.shell("wm", "size").splitlines()[-1]
            width, height = map(int, re.search(r"(\d+)x(\d+)", size).groups())
            top, bottom, center = int(height * 0.36), int(height * 0.82), width // 2
        y1, y2 = (bottom, top) if down else (top, bottom)
        self.shell("input", "swipe", str(center), str(y1), str(center), str(y2), "390")

    def to_top(self):
        self.dump()
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


def verify_unified_search(device, *, english_only=False):
    device.tap("Search recipes and tags", exact=True)
    device.shell("input", "text", "soup")
    device.find("Recipe ·", clickable=True)
    device.tap("Tag · Soups", exact=True)
    device.tap("Meal", exact=True)
    device.tap("Dinner", exact=True)
    device.tap("Done", exact=True)
    device.tap("Time", exact=True)
    device.tap("Up to 30 min", exact=True)
    filtered = device.find("Recipes found")
    device.find("Up to 30 min ×", exact=True)
    device.find("Dinner ×", exact=True)

    recipe = device.tap("Easy Wedding Soup", scroll=True)
    device.find("Back to results")
    device.find("Method", scroll=True)
    device.shell("input", "keyevent", "KEYCODE_BACK")
    if recipe.get("bounds") != device.find("Easy Wedding Soup", clickable=True).get("bounds"):
        raise RuntimeError("Returning from the recipe moved the search results.")
    device.find("Up to 30 min ×", exact=True)
    device.tap("Up to 30 min ×", exact=True)
    expanded = device.find("Recipes found")
    if int(expanded.get("text").split("·")[-1]) <= int(filtered.get("text").split("·")[-1]):
        raise RuntimeError("Removing the time limit did not expand the results.")

    if not english_only:
        device.tap("Русский", exact=True)
        device.find("Супы ×", exact=True)
        device.find("Ужин ×", exact=True)
        device.tap("English", exact=True)

    device.tap("Time", exact=True)
    device.tap("Up to 15 min", exact=True)
    device.tap("Search recipes and tags", exact=True)
    device.shell("input", "text", "Easy%sWedding%sSoup")
    device.shell("input", "keyevent", "KEYCODE_BACK")
    device.find("No exact matches", exact=True)
    suggestion = device.tap("Increase time to 30 min")
    total = int(suggestion.get("text").split("·")[-1])
    device.find(f"Recipes found · {total}", exact=True)
    device.find("Up to 30 min ×", exact=True)

    device.tap("Ideas", exact=True)
    device.tap("Easy breakfast", scroll=True, exact=True)
    device.tap("Dish type", exact=True)
    device.tap("Soups", exact=True)
    device.tap("Done", exact=True)
    device.find("No exact matches", exact=True)
    device.find("Similar recipes", scroll=True)
    device.find("Did not match", scroll=True)
    device.to_top()
    suggestion = device.tap("Remove “Soups”", scroll=True)
    total = int(suggestion.get("text").split("·")[-1])
    device.find(f"Recipes found · {total}", exact=True)


def switch_measurements(device, current, new):
    device.tap(f"Measurement system · {current}", exact=True)
    device.find("Measurement system", exact=True)
    device.tap(new + " (")
    device.find(f"Measurement system · {new}", exact=True)


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
        nodes = device.dump()
        unified = any(node.get("content-desc") == "Search recipes and tags" for node in nodes)
        english_only = any(node.get("content-desc", "").startswith("Measurement system · ") for node in nodes)
        if english_only and any(node.get("content-desc") == "Measurement system · Imperial" for node in nodes):
            switch_measurements(device, "Imperial", "Metric")
        discovery = any(node.get("text") == "Ideas for today" for node in nodes)
        if unified:
            verify_unified_search(device, english_only=english_only)
            checks.extend(("live_search_tag_and_title_suggestions", "filter_chips_and_recipe_back_position",
                           "time_and_category_refinements_with_exact_counts"))
            if english_only:
                device.find("What’s for dinner?", exact=True)
                if any(n.get("text") in {"Русский", "English"} for n in device.dump()):
                    raise RuntimeError("A language switch remains in the English interface.")
                checks.append("english_interface_and_catalog")
            else:
                checks.append("language_switch_retains_tags")
            device.tap("Ideas", exact=True)
            device.tap("Search by ingredients", scroll=True, exact=True)
        elif discovery:
            verify_discovery(device)
            checks.append("discovery_presets_filters_partial_results_and_language_offline")
            device.tap("Search by ingredients", scroll=True)
        device.tap("Use an example", scroll=True)
        device.tap("Find dinner", scroll=True)
        device.find("Recipe cards:", scroll=True)
        device.tap("Cheese omelet", scroll=True)
        device.find("eggs · 4 pc(s)", scroll=True)
        checks.append("pantry_time_group_and_portions_en")

        if english_only:
            device.find("cheese · 60 g", scroll=True)
            device.find("vegetable oil · 10 mL", scroll=True)
            switch_measurements(device, "Metric", "Imperial")
            device.find("cheese · 2.12 oz", scroll=True)
            device.find("vegetable oil · 2 tsp", scroll=True)
            device.find("eggs · 4 pc(s)", scroll=True)
            device.tap("Back to results")
            device.find("Measurement system · Imperial", exact=True)
            checks.append("measurement_switch_updates_open_recipe_and_preserves_scaled_servings")
            device.tap("Ideas", exact=True)
            device.tap("Browse all recipes", scroll=True, exact=True)
            heading = "Recipes found" if args.cards is None else f"Recipes found · {args.cards}"
            device.find(heading, exact=args.cards is not None)
        else:
            if unified:
                device.tap("Back to results")
            device.to_top()
            device.tap("Русский")
            device.find("Что на ужин?")
            device.tap("Заполнить пример", scroll=True)
            device.tap("Подобрать ужин", scroll=True)
            device.find("Карточек:", scroll=True)
            device.tap("Омлет с сыром", scroll=True)
            device.find("яйца · 4 шт.", scroll=True)
            checks.append("language_switch_and_pantry_ru")
            if unified:
                device.tap("К результатам")
                device.tap("Идеи", exact=True)
                device.tap("Весь каталог", scroll=True, exact=True)
                heading = "Найдено блюд" if args.cards is None else f"Найдено блюд · {args.cards}"
                device.find(heading, exact=args.cards is not None)
            else:
                device.to_top()
                device.tap("Поиск по названию" if discovery else "Весь каталог", scroll=True)
                device.tap("Все рецепты")
                device.find(f"Каталог · найдено: {args.recipes}", scroll=True)
            device.to_top()
            device.tap("English")
        checks.append("all_bundled_recipes_available_offline")

        if unified:
            device.tap("Search recipes and tags", exact=True)
            device.shell("input", "text", args.query)
            device.tap(f"Recipe · {args.recipe_title}")
        else:
            device.tap("Search by name" if discovery else "Browse catalog", scroll=True)
            device.find("Recipe name or part of it")
            device.shell("input", "text", args.query)
            device.tap("Search")
        device.tap(args.recipe_title, scroll=True, widget="android.widget.Button")
        device.find("Source and authors · Wikibooks", scroll=True)
        checks.append("title_search_and_attribution_offline")
        if english_only:
            if args.recipe_title.casefold().startswith("polvorones"):
                device.to_top()
                device.find("350°F", scroll=True)
                switch_measurements(device, "Imperial", "Metric")
                device.find("180°C", scroll=True)
                switch_measurements(device, "Metric", "Imperial")
                checks.append("imported_recipe_uses_source_oven_temperature_in_each_system")
            device.shell("am", "force-stop", PACKAGE)
            device.shell("am", "start", "-W", "-n", ACTIVITY)
            device.find("Measurement system · Imperial", exact=True)
            checks.append("measurement_preference_survives_restart")
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
    parser.add_argument("--cards", type=int, help="Expected card count in the unified catalog")
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
