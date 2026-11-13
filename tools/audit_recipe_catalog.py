"""Check recipe structure and report which catalog data still needs review."""

import argparse
import json
import math
import re
import sqlite3
from collections import Counter
from contextlib import closing
from hashlib import sha256
from pathlib import Path
from urllib.parse import urlparse

from tools.build_catalog import RULES, SEED_NAMES, normalize_ingredient, read_rules
from tools.build_pilot import write_json
from tools.tag_catalog import ANDROID_DATA

MARKUP = re.compile(r"\[\[|\{\{|</?[a-z]+[^>]*>", re.I)
EXTERNAL_STEP = re.compile(r"\b(?:see (?:the )?(?:previous recipe|above|below)|as above)\b", re.I)


def text_list(raw):
    try:
        value = json.loads(raw)
    except (TypeError, ValueError):
        return None
    if not isinstance(value, list) or any(not isinstance(line, str) or not line.strip() for line in value):
        return None
    return value


def valid_revision(source):
    url = urlparse(source)
    return url.scheme == "https" and url.hostname in {"en.wikibooks.org", "ru.wikibooks.org"} and bool(
        re.fullmatch(r"oldid=\d+", url.query)
    )


def audit(database):
    rules = read_rules(json.loads(RULES.read_bytes()), json.loads(SEED_NAMES.read_bytes()))
    counts = Counter()
    review = []
    errors = []
    with closing(sqlite3.connect(database.resolve().as_uri() + "?mode=ro", uri=True)) as db:
        if db.execute("PRAGMA integrity_check").fetchall() != [("ok",)]:
            errors.append("SQLite integrity check failed.")
        if db.execute("PRAGMA foreign_key_check").fetchone() is not None:
            errors.append("SQLite foreign key check failed.")
        for rid, title, minutes, servings in db.execute("SELECT id,title_en,minutes,servings FROM recipe ORDER BY id"):
            counts["starter_recipes"] += 1
            ingredients = db.execute(
                "SELECT position,amount FROM recipe_ingredient WHERE recipe_id=? ORDER BY position", (rid,)
            ).fetchall()
            steps = db.execute(
                "SELECT position,text_en FROM recipe_step WHERE recipe_id=? ORDER BY position", (rid,)
            ).fetchall()
            if (not title.strip() or minutes <= 0 or servings <= 0 or not ingredients or not steps
                    or any(not math.isfinite(amount) or amount <= 0 for _, amount in ingredients)
                    or any(not step.strip() for _, step in steps)):
                errors.append(f"{rid}: incomplete structured starter recipe")
            for lines in (ingredients, steps):
                positions = [index for index, _ in lines]
                if positions and positions != list(range(positions[0], positions[0] + len(positions))):
                    errors.append(f"{rid}: missing or duplicate positions")
        for row in db.execute("""
            SELECT id, title_en, ingredients_en, steps_en, minutes, servings, strict_ready, revision_url, license_url
            FROM offline_recipe ORDER BY id
        """):
            rid, title, ingredient_json, step_json, minutes, servings, strict, revision, license_url = row
            counts["imported_recipes"] += 1
            ingredients, steps = text_list(ingredient_json), text_list(step_json)
            if not title.strip() or not ingredients or not steps:
                errors.append(f"{rid}: empty or malformed title, ingredients or method")
                continue
            if not valid_revision(revision) or not license_url.startswith("https://creativecommons.org/"):
                errors.append(f"{rid}: invalid source or license")
            if any(MARKUP.search(line) for line in [*ingredients, *steps]):
                errors.append(f"{rid}: unrendered markup in recipe")
            if any(value is not None and value <= 0 for value in (minutes, servings)):
                errors.append(f"{rid}: invalid time or servings")
            if strict and (minutes is None or not db.execute(
                    "SELECT 1 FROM offline_ingredient WHERE recipe_id=? LIMIT 1", (rid,)
            ).fetchone()):
                errors.append(f"{rid}: incomplete ingredient matching data")
            flags = []
            if minutes is None:
                flags.append("missing_time")
            if servings is None:
                flags.append("missing_servings")
            quantities = sum(normalize_ingredient(line, "en", rules)[2] is not None for line in ingredients)
            if quantities == 0:
                flags.append("no_parseable_ingredient_quantities")
            elif quantities < len(ingredients):
                flags.append("some_unparsed_or_unmeasured_ingredients")
            if any(EXTERNAL_STEP.search(step) for step in steps):
                flags.append("reference_in_method")
            if len(steps) == 1:
                flags.append("one_step")
            if flags:
                counts.update(flags)
                review.append({"id": rid, "title": title, "source": revision, "flags": flags})
    return {
        "catalog_sha256": sha256(database.read_bytes()).hexdigest(),
        "total_recipes": counts["starter_recipes"] + counts["imported_recipes"],
        "counts": dict(sorted(counts.items())),
        "errors": errors,
        "review": review,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=ANDROID_DATA / "recipes.sqlite")
    parser.add_argument("--report", type=Path, default=ANDROID_DATA / "recipe-audit.json")
    args = parser.parse_args()
    report = audit(args.catalog)
    write_json(args.report, report)
    print(json.dumps({"total_recipes": report["total_recipes"], "counts": report["counts"],
                      "errors": report["errors"]}, indent=2))
    if report["errors"]:
        parser.exit(1)


if __name__ == "__main__":
    main()
