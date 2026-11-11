"""Package the reviewed subset of the bilingual catalog as an Android SQLite asset."""

import argparse
import json
import sqlite3
from collections import defaultdict
from contextlib import closing
from hashlib import sha256
from pathlib import Path
from tempfile import TemporaryDirectory

from recipe_catalog import DATABASE
from tools.build_catalog import OUTPUT, ROOT, RULES, SEED_NAMES, normalize_ingredient, read_rules
from tools.build_pilot import write_json
from tools.enrich_catalog import DESTINATION, validate_translation
from tools.tag_catalog import ANDROID_DATA, apply_tags

REVIEWED_REPAIRS = ROOT / "catalog" / "reviewed_repairs.json"
SCHEMA = """
CREATE TABLE offline_family (
    id TEXT PRIMARY KEY, title_ru TEXT NOT NULL, title_en TEXT NOT NULL,
    summary_ru TEXT NOT NULL, summary_en TEXT NOT NULL
);
CREATE TABLE offline_recipe (
    id TEXT PRIMARY KEY, family_id TEXT REFERENCES offline_family(id),
    title_ru TEXT NOT NULL, title_en TEXT NOT NULL,
    ingredients_ru TEXT NOT NULL, ingredients_en TEXT NOT NULL,
    steps_ru TEXT NOT NULL, steps_en TEXT NOT NULL,
    minutes INTEGER, servings INTEGER, strict_ready INTEGER NOT NULL,
    revision_url TEXT NOT NULL, license_url TEXT NOT NULL
);
CREATE TABLE offline_ingredient (
    recipe_id TEXT NOT NULL REFERENCES offline_recipe(id), ingredient_key TEXT NOT NULL,
    PRIMARY KEY (recipe_id, ingredient_key)
);
CREATE TABLE offline_alias (
    language TEXT NOT NULL, name TEXT NOT NULL, ingredient_key TEXT NOT NULL,
    PRIMARY KEY (language, name)
);
CREATE INDEX offline_recipe_minutes ON offline_recipe(strict_ready, minutes);
"""


def ingredient_keys(ru, en, rules):
    keys = set()
    aliases = set()
    for russian, english in zip(ru, en, strict=True):
        ru_id, ru_name, *_, ru_status = normalize_ingredient(russian, "ru", rules)
        en_id, en_name, *_, en_status = normalize_ingredient(english, "en", rules)
        if (ru_status == "needs_review" or en_status == "needs_review"
                or (ru_id and en_id and ru_id != en_id)):
            return set(), set()
        key = ru_id or en_id or "custom:" + sha256(en_name.encode()).hexdigest()[:20]
        keys.add(key)
        aliases.update((("ru", ru_name, key), ("en", en_name, key)))
    return keys, aliases


def equivalent_units(ru, en, rules):
    for russian, english in zip(ru, en, strict=True):
        left = normalize_ingredient(russian, "ru", rules)
        right = normalize_ingredient(english, "en", rules)
        measured = all(value[2] is not None and (value[4] != "piece" or value[-1] == "mapped")
                       for value in (left, right))
        if measured and left[2:5] != right[2:5]:
            return False
    return True


def import_release(target, source, rules):
    target.executemany("INSERT INTO offline_family VALUES (?, ?, ?, ?, ?)",
                       source.execute("SELECT * FROM recipe_family ORDER BY id"))
    aliases = defaultdict(set)
    unit_reviews = 0
    translation_reviews = 0
    for row in source.execute("""
        SELECT r.id, f.family_id, ru.title, en.title, ru.ingredients_json, en.ingredients_json,
               ru.steps_json, en.steps_json, r.minutes_max, r.servings,
               e.revision_url, e.license_url, e.source_language,
               EXISTS (SELECT 1 FROM completion_proposal p WHERE p.recipe_id=r.id)
        FROM recipe r
        JOIN enrichment_recipe e ON e.id=r.id
        JOIN recipe_localization ru ON ru.recipe_id=r.id AND ru.language='ru'
        JOIN recipe_localization en ON en.recipe_id=r.id AND en.language='en'
        LEFT JOIN family_member f ON f.recipe_id=r.id
        WHERE e.translation_issues_json='[]' AND e.source_gap=0
          AND e.license_url LIKE 'https://creativecommons.org/licenses/by-sa/4.0/%'
          AND NOT EXISTS (SELECT 1 FROM recipe_origin o WHERE o.recipe_id=r.id AND o.requires_review=1)
        ORDER BY r.id
    """):
        recipe_id, family, title_ru, title_en, ing_ru, ing_en, steps_ru, steps_en, minutes, servings, url, license_url, language, has_draft = row
        ingredients = json.loads(ing_ru), json.loads(ing_en)
        russian = {"title": title_ru, "ingredients": ingredients[0], "steps": json.loads(steps_ru)}
        english = {"title": title_en, "ingredients": ingredients[1], "steps": json.loads(steps_en)}
        original, translated = (russian, english) if language == "ru" else (english, russian)
        if validate_translation(
            {**translated, "ingredient_additions": [], "step_additions": []},
            {**original, "language": language},
        ):
            translation_reviews += 1
            continue
        if not equivalent_units(*ingredients, rules):
            unit_reviews += 1
            continue
        keys, names = ingredient_keys(*ingredients, rules)
        strict_ready = bool(minutes is not None and keys and not has_draft)
        target.execute("INSERT INTO offline_recipe VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                       (recipe_id, family, title_ru, title_en, ing_ru, ing_en, steps_ru, steps_en,
                        minutes, servings, strict_ready, url, license_url))
        if strict_ready:
            target.executemany("INSERT INTO offline_ingredient VALUES (?, ?)",
                               ((recipe_id, key) for key in sorted(keys)))
            for lang, name, key in names:
                aliases[lang, name].add(key)
    target.executemany("INSERT INTO offline_alias VALUES (?, ?, ?)",
                       ((lang, name, next(iter(keys))) for (lang, name), keys in sorted(aliases.items())
                        if len(keys) == 1))
    return unit_reviews, translation_reviews


def import_reviewed_repairs(target, source):
    included = 0
    for recipe_id, review in json.loads(REVIEWED_REPAIRS.read_bytes()).items():
        row = source.execute("""
            SELECT ru.title, ru.ingredients_json, ru.steps_json, e.revision_url, e.license_url
            FROM enrichment_recipe e JOIN recipe_localization ru ON ru.recipe_id=e.id AND ru.language='ru'
            WHERE e.source_gap=1 AND e.source_language='ru' AND e.id=? AND e.revision_url=?
              AND e.license_url LIKE 'https://creativecommons.org/licenses/by-sa/4.0/%'
        """, (recipe_id, review["revision_url"])).fetchone()
        if row is None:
            continue
        title_ru, ingredients_ru, steps_ru, url, license_url = row
        original = {"language": "ru", "ingredients": json.loads(ingredients_ru), "steps": json.loads(steps_ru)}
        translated = {"title": review["title_en"], "ingredients": review["ingredients_en"], "steps": review["steps_en"],
                      "ingredient_additions": [], "step_additions": []}
        if validate_translation(translated, original):
            raise ValueError(f"Reviewed repair no longer matches the source: {recipe_id}")
        target.execute("INSERT INTO offline_recipe VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                       (recipe_id, None, title_ru, review["title_en"],
                        ingredients_ru, json.dumps(review["ingredients_en"], ensure_ascii=False),
                        steps_ru, json.dumps(review["steps_en"], ensure_ascii=False),
                        None, review["servings"], False, url, license_url))
        included += 1
    return included


def export_android(enriched, output):
    enriched, output = enriched.resolve(), output.resolve()
    if output in {enriched, OUTPUT.resolve(), DATABASE.parent.resolve()}:
        raise ValueError("Choose a separate Android export directory.")
    database = enriched / "recipes.sqlite"
    report = json.loads((enriched / "report.json").read_bytes())
    digest = sha256(database.read_bytes()).hexdigest()
    if report["sqlite_sha256"] != digest:
        raise ValueError("Stage 4 database does not match its report.")
    rules = read_rules(json.loads(RULES.read_bytes()), json.loads(SEED_NAMES.read_bytes()))
    output.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(dir=output) as temp:
        staging = Path(temp)
        asset = staging / "recipes.sqlite"
        with closing(sqlite3.connect(asset)) as target:
            with closing(sqlite3.connect(DATABASE.as_uri() + "?mode=ro", uri=True)) as seed:
                seed.backup(target)
            target.execute("PRAGMA foreign_keys = ON")
            target.executescript(SCHEMA)
            with closing(sqlite3.connect(database.as_uri() + "?mode=ro", uri=True)) as source, target:
                unit_reviews, translation_reviews = import_release(target, source, rules)
                repaired = import_reviewed_repairs(target, source)
            tags = apply_tags(target)
            if (target.execute("PRAGMA integrity_check").fetchone() != ("ok",)
                    or target.execute("PRAGMA foreign_key_check").fetchone() is not None):
                raise ValueError("Android catalog integrity check failed.")
            seed_count = target.execute("SELECT count(*) FROM recipe").fetchone()[0]
            imported, strict = target.execute(
                "SELECT count(*), count(*) FILTER (WHERE strict_ready=1) FROM offline_recipe"
            ).fetchone()
        manifest = {
            "enrichment_sha256": digest,
            "sqlite_sha256": sha256(asset.read_bytes()).hexdigest(),
            "starter_recipes": seed_count,
            "wikibooks_recipes": imported,
            "wikibooks_with_strict_search": strict,
            "unit_translation_reviews": unit_reviews,
            "translation_rechecks": translation_reviews,
            "reviewed_repairs": repaired,
            "total_recipes": seed_count + imported,
            "tags": tags,
        }
        write_json(staging / "manifest.json", manifest)
        asset.replace(output / asset.name)
        (staging / "manifest.json").replace(output / "manifest.json")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--enriched", type=Path, default=DESTINATION)
    parser.add_argument("--output-dir", type=Path, default=ANDROID_DATA)
    args = parser.parse_args()
    try:
        manifest = export_android(args.enriched, args.output_dir)
    except (OSError, ValueError, KeyError, sqlite3.Error) as error:
        parser.exit(1, f"{error}\n")
    print(f"{manifest['total_recipes']} recipes exported for Android.")


if __name__ == "__main__":
    main()
