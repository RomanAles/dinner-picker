"""Read the bundled SQLite catalog for the dinner picker."""

import sqlite3
from contextlib import closing
from pathlib import Path

DATABASE = Path(__file__).resolve().parent / "catalog" / "recipes.sqlite"


def load_catalog(database=DATABASE, *, language="ru"):
    if language not in {"ru", "en"}:
        raise ValueError(f"Unsupported catalog language: {language}")

    # Column suffixes are limited to the two catalog languages above.
    with closing(sqlite3.connect(database.resolve().as_uri() + "?mode=ro", uri=True)) as connection:
        recipes = {
            recipe_id: {
                "id": recipe_id,
                "family": family,
                "title": title,
                "minutes": minutes,
                "servings": servings,
                "ingredients": {},
                "steps": [],
            }
            for recipe_id, family, title, minutes, servings in connection.execute(f"""
                SELECT r.id, f.name_{language}, r.title_{language}, r.minutes, r.servings
                FROM recipe AS r JOIN family AS f ON f.id = r.family_id
                ORDER BY r.id
            """)
        }
        for recipe_id, name, amount, unit in connection.execute(f"""
            SELECT ri.recipe_id, i.name_{language}, ri.amount, u.label_{language}
            FROM recipe_ingredient AS ri
            JOIN ingredient AS i ON i.id = ri.ingredient_id
            JOIN unit AS u ON u.id = ri.unit_id
            ORDER BY ri.recipe_id, ri.position
        """):
            recipes[recipe_id]["ingredients"][name] = (amount, unit)

        for recipe_id, step in connection.execute(f"""
            SELECT recipe_id, text_{language} FROM recipe_step ORDER BY recipe_id, position
        """):
            recipes[recipe_id]["steps"].append(step)

        aliases = dict(connection.execute(f"""
            SELECT a.alias, i.name_{language}
            FROM ingredient_alias AS a JOIN ingredient AS i ON i.id = a.ingredient_id
            WHERE a.language = ?
        """, (language,)))
        basics = {name for (name,) in connection.execute(f"""
            SELECT name_{language} FROM ingredient WHERE is_basic = 1
        """)}
    return list(recipes.values()), aliases, basics
