"""Rebuild the SQLite catalog from the checked-in MVP recipes and translations."""

import json
import sqlite3
from contextlib import closing
from pathlib import Path
from tempfile import NamedTemporaryFile

ROOT = Path(__file__).resolve().parents[1]
CATALOG_DIR = ROOT / "catalog"
DATABASE = CATALOG_DIR / "recipes.sqlite"


def seed_catalog(database=DATABASE):
    recipes = json.loads((ROOT / "recipes.json").read_text(encoding="utf-8"))
    localization = json.loads((CATALOG_DIR / "seed_localization.json").read_text(encoding="utf-8"))
    if {recipe["id"] for recipe in recipes} != localization["recipes"].keys():
        raise ValueError("English translations must match the IDs in recipes.json.")

    with NamedTemporaryFile(suffix=".sqlite", dir=database.parent, delete=False) as temporary:
        candidate = Path(temporary.name)
    try:
        with closing(sqlite3.connect(candidate)) as connection:
            connection.execute("PRAGMA foreign_keys = ON")
            connection.executescript((CATALOG_DIR / "schema.sql").read_text(encoding="utf-8"))
            with connection:
                insert_catalog(connection, recipes, localization)
        candidate.replace(database)
    finally:
        candidate.unlink(missing_ok=True)


def insert_catalog(connection, recipes, localization):
    source_id = "mvp"
    connection.execute(
        "INSERT INTO source (id, name) VALUES (?, ?)",
        (source_id, "Исходный каталог проекта"),
    )
    families = localization["families"]
    ingredients = localization["ingredients"]
    units = localization["units"]
    connection.executemany(
        "INSERT INTO family (id, name_ru, name_en) VALUES (?, ?, ?)",
        ((data["id"], name, data["en"]) for name, data in families.items()),
    )
    connection.executemany(
        "INSERT INTO ingredient (id, name_ru, name_en, is_basic) VALUES (?, ?, ?, ?)",
        ((data["id"], name, data["en"], data.get("basic", False))
         for name, data in ingredients.items()),
    )
    connection.executemany(
        "INSERT INTO ingredient_alias (language, alias, ingredient_id) VALUES (?, ?, ?)",
        ((language, alias, data["id"])
         for data in ingredients.values()
         for language, aliases in data.get("aliases", {}).items()
         for alias in aliases),
    )
    connection.executemany(
        "INSERT INTO unit (id, label_ru, label_en) VALUES (?, ?, ?)",
        ((data["id"], label, data["en"]) for label, data in units.items()),
    )

    for recipe in recipes:
        recipe_id = recipe["id"]
        translation = localization["recipes"][recipe_id]
        connection.execute(
            """INSERT INTO recipe
               (id, family_id, source_id, source_key, original_language,
                title_ru, title_en, minutes, servings)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (recipe_id, families[recipe["family"]]["id"], source_id, recipe_id,
             "ru", recipe["title"], translation["title"], recipe["minutes"], 1),
        )
        connection.executemany(
            """INSERT INTO recipe_ingredient
               (recipe_id, position, ingredient_id, amount, unit_id) VALUES (?, ?, ?, ?, ?)""",
            ((recipe_id, position, ingredients[name]["id"], amount, units[unit]["id"])
             for position, (name, (amount, unit)) in enumerate(recipe["ingredients"].items(), 1)),
        )
        connection.executemany(
            "INSERT INTO recipe_step (recipe_id, position, text_ru, text_en) VALUES (?, ?, ?, ?)",
            ((recipe_id, position, text_ru, text_en)
             for position, (text_ru, text_en) in enumerate(
                 zip(recipe["steps"], translation["steps"], strict=True), 1)),
        )


if __name__ == "__main__":
    seed_catalog()
    print(DATABASE)
