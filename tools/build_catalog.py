"""Build a normalized, offline SQLite catalog from local Wikibooks snapshots."""

import argparse
import json
import re
import sqlite3
import unicodedata
from collections import Counter
from contextlib import closing
from fractions import Fraction
from hashlib import sha256
from math import ceil
from pathlib import Path
from tempfile import TemporaryDirectory

from tools.build_pilot import SOURCES, parse_page, write_json

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "catalog"
SCHEMA = CATALOG / "build_schema.sql"
RULES = CATALOG / "normalization.json"
SEED_NAMES = CATALOG / "seed_localization.json"
DEFAULT_SNAPSHOT = CATALOG / "pilot" / "snapshot.json"
OUTPUT = ROOT / "dist-data"
CHARACTERS = str.maketrans({
    **{char: " " + unicodedata.normalize("NFKC", char).replace("⁄", "/")
       for char in "¼½¾⅐⅑⅒⅓⅔⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞"},
    "⁄": "/", "–": "-", "—": "-", "−": "-",
})
NUMBER = r"(?:(?:\d+\s+)?\d+\s*/\s*\d+|\d+(?:[.,]\d+)?)"
QUANTITY = rf"(?P<low>{NUMBER})(?:\s*-\s*(?P<high>{NUMBER}))?(?!\d|[.,/]\d)"
LEADING_AMOUNT = re.compile(QUANTITY)
TRAILING_AMOUNT = re.compile(rf"(?P<name>.+?)(?:\s*[:=]\s*|\s+-\s+|\s+){QUANTITY}(?P<tail>.*)$")
TIME_PART = re.compile(
    r"(\d+(?:[.,]\d+)?)\s*(h|hrs?\.?|hours?|ч\.?|час(?:а|ов)?|m|mins?\.?|minutes?|мин\.?|минут(?:а|ы)?)?",
    re.IGNORECASE,
)
TASTE = re.compile(r"(?:^|\s+|[,;]\s*)(?:or\s+)?(?:to taste|по вкусу)[. ]*$", re.IGNORECASE)
OPTIONAL = re.compile(r"\s*\((?:optional|по желанию|необязательно)\)", re.IGNORECASE)
PREPARATION = re.compile(
    r",\s*(?:finely |coarsely )?(?:chopped|minced|diced|grated|sliced|beaten|crushed)$",
    re.IGNORECASE,
)
AMBIGUOUS = re.compile(r"\b(?:and|or|и|или|либо|plus|плюс)\b|[+/%]", re.IGNORECASE)
COMPOUND_PAGE = {"multiple_ingredients_sections", "multiple_steps_sections",
                 "unstructured_ingredients", "unexpanded_template"}


def text_key(text, language):
    value = " ".join(unicodedata.normalize("NFC", text).casefold().split())
    return value.replace("ё", "е") if language == "ru" else value


def parse_number(value):
    parts = re.sub(r"\s*/\s*", "/", value).replace(",", ".").split()
    return sum((Fraction(part) for part in parts), Fraction())


def read_rules(rules, seed_names):
    aliases = {}
    for name, info in seed_names["ingredients"].items():
        for language, canonical in (("ru", name), ("en", info["en"])):
            for alias in [canonical, *info.get("aliases", {}).get(language, [])]:
                aliases[language, text_key(alias, language)] = info["id"]
    for ingredient_id, translations in rules["ingredients"].items():
        for language, names in translations.items():
            for name in names:
                alias = (language, text_key(name, language))
                if aliases.setdefault(alias, ingredient_id) != ingredient_id:
                    raise ValueError(f"Conflicting ingredient alias: {name}")

    units = {name.casefold(): unit for unit, names in rules["units"].items() for name in names}
    options = "|".join(re.escape(name) for name in sorted(units, key=lambda name: (-len(name), name)))
    return aliases, units, re.compile(rf"({options})(?=$|[\s(,.;])", re.IGNORECASE)


def normalize_ingredient(raw, language, rules):
    aliases, units, unit_pattern = rules
    text = " ".join(raw.translate(CHARACTERS).split()).strip(" ,;.")
    text = TASTE.sub("", OPTIONAL.sub("", text)).strip(" ,;.")
    amount = LEADING_AMOUNT.match(text)
    leading = amount is not None
    if leading:
        name, tail = "", text[amount.end():].strip()
    else:
        amount = TRAILING_AMOUNT.fullmatch(text)
        name, tail = (amount["name"].strip(), amount["tail"].strip()) if amount else (text, "")

    low = high = unit = None
    if amount:
        unit_match = unit_pattern.match(tail)
        if unit_match:
            rest = tail[unit_match.end():].strip()
            # If an apparent unit is the entire name (e.g. "4 cloves"), keep the name.
            if leading and not rest:
                unit_match = None
        if unit_match:
            unit = units[unit_match[1].casefold()]
            tail = rest
        else:
            unit = "piece"
        if leading:
            equivalent = re.match(r"^\(([^()]*)\)\s*", tail)
            if equivalent:
                conversion = LEADING_AMOUNT.match(equivalent[1])
                if conversion and unit_pattern.fullmatch(equivalent[1][conversion.end():].strip()):
                    tail = tail[equivalent.end():]
            name = tail
        elif tail and not re.fullmatch(r"\([^()]+\)", tail):
            return (None, None, None, None, None, "needs_review")
        try:
            low = parse_number(amount["low"])
            high = parse_number(amount["high"] or amount["low"])
        except (ValueError, ZeroDivisionError):
            return (None, None, None, None, None, "needs_review")
        if not 0 < low <= high:
            return (None, None, None, None, None, "needs_review")
        if unit in {"kg", "l"}:
            unit, low, high = {"kg": "g", "l": "ml"}[unit], low * 1000, high * 1000

    name = PREPARATION.sub("", name.strip(" ,;:.-"))
    if not name or re.search(r"\d", name) or AMBIGUOUS.search(name):
        return (None, None, None, None, None, "needs_review")
    name_key = text_key(name, language)
    ingredient_id = aliases.get((language, name_key))
    return (ingredient_id, name_key, float(low) if low else None,
            float(high) if high else None, unit, "mapped" if ingredient_id else "unmapped")


def duration_range(text, exact):
    if exact is not None:
        return exact, exact
    parts = text.translate(CHARACTERS).split("-")
    if len(parts) != 2:
        return None, None
    start, end = (TIME_PART.fullmatch(part.strip()) for part in parts)
    if not start or not end or not end[2]:
        return None, None

    def minutes(match, unit):
        hours = unit.casefold().startswith(("h", "ч"))
        return Fraction(match[1].replace(",", ".")) * (60 if hours else 1)

    low, high = minutes(start, start[2] or end[2]), minutes(end, end[2])
    return (ceil(low), ceil(high)) if 0 < low <= high else (None, None)


def read_snapshots(paths):
    pages = {}
    hashes = set()
    for path in paths:
        content = path.read_bytes()
        hashes.add(sha256(content).hexdigest())
        snapshot = json.loads(content)
        if snapshot.get("version") != 1 or not snapshot["sources"].keys() <= SOURCES.keys():
            raise ValueError(f"Unsupported snapshot: {path}")
        for language, source in snapshot["sources"].items():
            license_info = source["site_license"]
            if not license_info["text"] or not license_info["url"]:
                raise ValueError(f"Missing source license: {path}, {language}")
            for page in source["pages"]:
                identity = (language, page["page_id"])
                previous = pages.get(identity)
                if previous and previous[0]["revision"] == page["revision"] and previous != (page, license_info):
                    raise ValueError(f"Conflicting data for {identity} revision {page['revision']}")
                if not previous or page["revision"] > previous[0]["revision"]:
                    pages[identity] = (page, license_info)
    if not pages:
        raise ValueError("No pages in the supplied snapshots.")
    return pages, sorted(hashes)


def revision_url(language, revision):
    return f"https://{language}.wikibooks.org/w/index.php?oldid={revision}"


def fingerprint(language, parts, time_text, servings_text):
    # Include section boundaries and item order so near matches stay separate.
    def exact_text(text):
        return " ".join(unicodedata.normalize("NFC", text).split())

    sections = [
        [[exact_text(block["heading"]), block["level"],
          [exact_text(item) for item in block["items"]]] for block in parts[kind]]
        for kind in ("ingredients", "steps")
    ]
    data = (language, sections, exact_text(time_text), exact_text(servings_text))
    return sha256(json.dumps(data, ensure_ascii=False).encode("utf-8")).digest()


def import_pages(connection, pages, rules):
    counts = Counter()
    ingredient_statuses = Counter()
    missing_time = missing_servings = source_reviews = 0
    rejections = []
    duplicates = []
    fingerprints = {}
    for (language, page_id), (page, license_info) in sorted(pages.items()):
        parts, time_text, exact, servings_text, servings, issues = parse_page(page)
        _, _, title = page["title"].partition(":")
        title = title.strip()
        url = revision_url(language, page["revision"])
        reasons = set(issues) & (COMPOUND_PAGE | {"missing_ingredients", "missing_steps"})
        if not title:
            reasons.add("missing_title")
        if reasons:
            rejections.append({"language": language, "page_id": page_id, "title": page["title"],
                               "revision_url": url, "reasons": sorted(reasons)})
            continue

        signature = fingerprint(language, parts, time_text, servings_text)
        recipe_id = fingerprints.get(signature)
        if recipe_id is None:
            recipe_id = f"wikibooks:{language}:{page_id}"
            fingerprints[signature] = recipe_id
            low, high = duration_range(time_text, exact)
            missing_time += high is None
            missing_servings += servings is None
            connection.execute("INSERT INTO recipe VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                               (recipe_id, language, title, low, high, time_text, servings, servings_text))
            ingredient_lines = [(block["heading"], item) for block in parts["ingredients"] for item in block["items"]]
            for position, (section, item) in enumerate(ingredient_lines, 1):
                values = normalize_ingredient(item, language, rules)
                connection.execute("INSERT INTO recipe_ingredient VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                   (recipe_id, position, section, item, *values))
                ingredient_statuses[values[-1]] += 1
            steps = [(block["heading"], item) for block in parts["steps"] for item in block["items"]]
            connection.executemany("INSERT INTO recipe_step VALUES (?, ?, ?, ?)",
                                   ((recipe_id, position, section, item)
                                    for position, (section, item) in enumerate(steps, 1)))
            counts[language] += 1
        else:
            duplicates.append({"revision_url": url, "recipe_id": recipe_id})

        review_source = "references_require_review" in issues
        source_reviews += review_source
        connection.execute("INSERT INTO recipe_origin VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                           (recipe_id, language, page_id, page["title"], page["revision"],
                            page["timestamp"], url, license_info["text"], license_info["url"], review_source))

    return {
        "pages": len(pages),
        "recipes_by_language": dict(sorted(counts.items())),
        "recipes_without_duration": missing_time,
        "recipes_without_servings": missing_servings,
        "origins_flagged_for_review": source_reviews,
        "ingredient_statuses": dict(sorted(ingredient_statuses.items())),
        "rejection_reasons": dict(sorted(Counter(
            reason for row in rejections for reason in row["reasons"]
        ).items())),
        "rejected": rejections,
        "duplicates": duplicates,
    }


def build_catalog(paths, output):
    output = output.resolve()
    if (output / "recipes.sqlite").resolve() == (CATALOG / "recipes.sqlite").resolve():
        raise ValueError("Choose a separate output directory to preserve the MVP database.")

    pages, snapshot_hashes = read_snapshots(paths)
    schema, rules_data, seed_data = (path.read_bytes() for path in (SCHEMA, RULES, SEED_NAMES))
    rules = read_rules(json.loads(rules_data), json.loads(seed_data))
    output.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(dir=output) as temp:
        staging = Path(temp)
        database = staging / "recipes.sqlite"
        with closing(sqlite3.connect(database)) as connection:
            connection.execute("PRAGMA foreign_keys = ON")
            connection.executescript(schema.decode("utf-8"))
            with connection:
                report = import_pages(connection, pages, rules)
            if connection.execute("PRAGMA integrity_check").fetchone() != ("ok",):
                raise ValueError("SQLite integrity check failed.")
            version = connection.execute("PRAGMA user_version").fetchone()[0]

        write_json(staging / "report.json", report)
        database_hash = sha256(database.read_bytes()).hexdigest()
        write_json(staging / "manifest.json", {
            "kind": "catalog-preparation",
            "schema_version": version,
            "data_version": database_hash[:16],
            "snapshot_sha256": snapshot_hashes,
            "schema_sha256": sha256(schema).hexdigest(),
            "normalization_sha256": sha256(rules_data).hexdigest(),
            "seed_localization_sha256": sha256(seed_data).hexdigest(),
            "files": {
                "recipes.sqlite": database_hash,
                "report.json": sha256((staging / "report.json").read_bytes()).hexdigest(),
            },
        })
        for name in ("recipes.sqlite", "report.json", "manifest.json"):
            (staging / name).replace(output / name)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", type=Path, action="append", help="local snapshot.json (repeatable)")
    parser.add_argument("--output-dir", type=Path, default=OUTPUT)
    args = parser.parse_args()
    try:
        report = build_catalog(args.snapshot or [DEFAULT_SNAPSHOT], args.output_dir)
    except (OSError, ValueError, KeyError, sqlite3.Error) as error:
        parser.exit(1, f"{error}\n")
    count = sum(report["recipes_by_language"].values())
    print(f"{count}/{report['pages']} recipes, {len(report['rejected'])} for review, "
          f"{len(report['duplicates'])} duplicates. {args.output_dir.resolve()}")


if __name__ == "__main__":
    main()
