"""Add the bilingual discovery tags to an existing Android catalog offline."""

import argparse
import json
import sqlite3
from collections import Counter
from contextlib import closing
from hashlib import sha256
from pathlib import Path
from tempfile import TemporaryDirectory

from tools.infer_recipe_tags import RULE_VERSION, infer_tags


ROOT = Path(__file__).resolve().parents[1]
ANDROID_DATA = ROOT / "dist-data" / "android"
PILOT_TAGS = ROOT / "catalog" / "pilot_tags.json"
SCHEMA = """
CREATE TABLE IF NOT EXISTS tag_axis (
    id TEXT PRIMARY KEY,
    name_ru TEXT NOT NULL,
    name_en TEXT NOT NULL,
    multiple INTEGER NOT NULL CHECK (multiple IN (0, 1)),
    position INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS tag (
    axis_id TEXT NOT NULL REFERENCES tag_axis(id),
    id TEXT NOT NULL,
    name_ru TEXT NOT NULL,
    name_en TEXT NOT NULL,
    position INTEGER NOT NULL,
    PRIMARY KEY (axis_id, id)
);
CREATE TABLE IF NOT EXISTS recipe_tag (
    recipe_id TEXT NOT NULL,
    axis_id TEXT NOT NULL,
    tag_id TEXT NOT NULL,
    note_ru TEXT,
    note_en TEXT,
    PRIMARY KEY (recipe_id, axis_id, tag_id),
    FOREIGN KEY (axis_id, tag_id) REFERENCES tag(axis_id, id),
    CHECK ((axis_id = 'difficulty' AND note_ru IS NOT NULL AND note_en IS NOT NULL)
        OR (axis_id <> 'difficulty' AND note_ru IS NULL AND note_en IS NULL))
);
CREATE INDEX IF NOT EXISTS recipe_tag_by_tag ON recipe_tag (axis_id, tag_id, recipe_id);
"""


def label_pair(value):
    if (not isinstance(value, list) or len(value) != 2
            or any(not isinstance(label, str) or not label.strip() for label in value)):
        raise ValueError(f"Expected two nonempty localized strings, got {value!r}.")
    return value


def inferred_assignments(connection, manual):
    for recipe_id, title_en, ingredients_en, steps_en, title_ru, ingredients_ru, steps_ru in connection.execute("""
        SELECT id, title_en, ingredients_en, steps_en, title_ru, ingredients_ru, steps_ru
        FROM offline_recipe WHERE id GLOB 'wikibooks:*' ORDER BY id
    """):
        if recipe_id in manual:
            continue
        language = recipe_id.split(":")[1]
        if language not in {"ru", "en"}:
            continue
        title, ingredients, steps = ((title_ru, ingredients_ru, steps_ru) if language == "ru"
                                     else (title_en, ingredients_en, steps_en))
        yield recipe_id, infer_tags(language, title, json.loads(ingredients), json.loads(steps))


def apply_tags(connection):
    taxonomy = json.loads(PILOT_TAGS.read_bytes())
    axes = taxonomy["axes"]
    axis_rows = []
    tag_rows = []
    for position, (axis_id, axis) in enumerate(axes.items()):
        if not isinstance(axis["multiple"], bool):
            raise ValueError(f"Invalid selection mode for {axis_id}.")
        axis_rows.append((axis_id, *label_pair(axis["labels"]), axis["multiple"], position))
        for tag_position, (tag_id, labels) in enumerate(axis["tags"].items()):
            tag_rows.append((axis_id, tag_id, *label_pair(labels), tag_position))

    # Starter and imported recipes use the same IDs as their bilingual source records.
    present = {row[0] for row in connection.execute("SELECT id FROM recipe UNION SELECT id FROM offline_recipe")}
    assignments = []
    tagged = set()
    coverage = Counter()
    automatic_coverage = Counter()
    manual = taxonomy["recipes"]
    recipes = {**manual, **dict(inferred_assignments(connection, manual))}
    for recipe_id, recipe_tags in recipes.items():
        selected = {axis_id: tags for axis_id, tags in recipe_tags.items() if axis_id != "difficulty_note"}
        note_id = recipe_tags.get("difficulty_note")
        if ("difficulty" in selected) != (note_id is not None):
            raise ValueError(f"Difficulty and its note must appear together for {recipe_id}.")
        note = label_pair(taxonomy["difficulty_notes"][note_id]) if note_id is not None else None
        for axis_id, tags in selected.items():
            axis = axes[axis_id]
            if (not isinstance(tags, list) or not tags or any(tag not in axis["tags"] for tag in tags)
                    or len(tags) != len(set(tags)) or (not axis["multiple"] and len(tags) != 1)):
                raise ValueError(f"Invalid {axis_id} tags for {recipe_id}: {tags!r}.")
            if recipe_id in present:
                note_ru, note_en = note if axis_id == "difficulty" else (None, None)
                assignments.extend((recipe_id, axis_id, tag, note_ru, note_en) for tag in tags)
                tagged.add(recipe_id)
                coverage[axis_id] += 1
                if recipe_id not in manual:
                    automatic_coverage[axis_id] += 1

    connection.execute("PRAGMA foreign_keys = ON")
    connection.executescript(SCHEMA)
    with connection:
        connection.execute("DELETE FROM recipe_tag")
        connection.execute("DELETE FROM tag")
        connection.execute("DELETE FROM tag_axis")
        connection.executemany("INSERT INTO tag_axis VALUES (?, ?, ?, ?, ?)", axis_rows)
        connection.executemany("INSERT INTO tag VALUES (?, ?, ?, ?, ?)", tag_rows)
        connection.executemany("INSERT INTO recipe_tag VALUES (?, ?, ?, ?, ?)", assignments)

    return {
        "version": taxonomy["version"],
        "rule_version": RULE_VERSION,
        "axes": len(axes),
        "tags": len(tag_rows),
        "tagged_recipes": len(tagged),
        "manually_tagged_recipes": len(tagged.intersection(manual)),
        "automatically_tagged_recipes": len(tagged.difference(manual)),
        "untagged_recipes": len(present - tagged),
        "assignments": len(assignments),
        "coverage": {axis_id: coverage[axis_id] for axis_id in axes},
        "automatic_coverage": {axis_id: automatic_coverage[axis_id] for axis_id in axes},
    }


def retag_catalog(output):
    output = output.resolve()
    database = output / "recipes.sqlite"
    manifest_path = output / "manifest.json"
    previous_manifest = manifest_path.read_bytes()
    manifest = json.loads(previous_manifest)
    original_digest = manifest["sqlite_sha256"]
    original = database.read_bytes()
    if sha256(original).hexdigest() != original_digest:
        raise ValueError("Android catalog does not match its manifest.")

    with TemporaryDirectory(dir=output) as temp:
        staging = Path(temp)
        asset = staging / database.name
        with closing(sqlite3.connect(asset)) as target:
            with closing(sqlite3.connect(database.as_uri() + "?mode=ro", uri=True)) as source:
                source.backup(target)
            manifest["tags"] = apply_tags(target)
            if (target.execute("PRAGMA integrity_check").fetchone() != ("ok",)
                    or target.execute("PRAGMA foreign_key_check").fetchone() is not None):
                raise ValueError("Android catalog integrity check failed.")

        manifest["sqlite_sha256"] = sha256(asset.read_bytes()).hexdigest()
        staged_manifest = staging / manifest_path.name
        staged_manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        rollback = staging / "previous.sqlite"
        rollback.write_bytes(original)
        if (manifest_path.read_bytes() != previous_manifest
                or sha256(database.read_bytes()).hexdigest() != original_digest):
            raise ValueError("Android catalog changed during tagging. Run tagging again.")
        asset.replace(database)
        try:
            staged_manifest.replace(manifest_path)
        except OSError:
            rollback.replace(database)
            raise
    return manifest["tags"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog-dir", type=Path, default=ANDROID_DATA)
    args = parser.parse_args()
    try:
        stats = retag_catalog(args.catalog_dir)
    except (OSError, ValueError, KeyError, sqlite3.Error) as error:
        parser.exit(1, f"{error}\n")
    print(f"{stats['tagged_recipes']} recipes tagged, {stats['untagged_recipes']} untagged.")


if __name__ == "__main__":
    main()
