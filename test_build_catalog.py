import json
import sqlite3
import unittest
from contextlib import closing
from hashlib import sha256
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

from tools.build_catalog import (
    RULES,
    SEED_NAMES,
    build_catalog,
    duration_range,
    normalize_ingredient,
    read_rules,
)


class BuildCatalogTests(unittest.TestCase):
    def test_normalizes_quantities_and_keeps_ambiguous_ingredients_for_review(self):
        rules = read_rules(json.loads(RULES.read_bytes()), json.loads(SEED_NAMES.read_bytes()))
        self.assertEqual(normalize_ingredient("лук: 0,25 кг", "ru", rules),
                         ("onion", "лук", 250, 250, "g", "mapped"))
        self.assertEqual(normalize_ingredient("1½–2 cups (300 ml) sugar", "en", rules),
                         ("sugar", "sugar", 1.5, 2, "cup", "mapped"))
        self.assertEqual(normalize_ingredient("2 eggs, beaten", "en", rules),
                         ("egg", "eggs", 2, 2, "piece", "mapped"))
        self.assertEqual(normalize_ingredient("Salt to taste", "en", rules),
                         ("salt", "salt", None, None, None, "mapped"))
        self.assertEqual(normalize_ingredient("2 cloves garlic or onion", "en", rules),
                         (None, None, None, None, None, "needs_review"))

    def test_duration_ranges_leave_compound_times_unknown(self):
        self.assertEqual(duration_range("40 минут — 1 час", None), (40, 60))
        self.assertEqual(duration_range("5-6 часов", None), (300, 360))
        self.assertEqual(duration_range("15 minutes + refrigeration", None), (None, None))

    def test_build_merges_exact_duplicates_and_preserves_origins(self):
        wikitext = ("{{recipesummary|time=10–15 minutes|servings=2}}\n"
                    "== Ingredients ==\n* 2 eggs\n== Procedure ==\n# Cook the eggs.")
        pages = [
            {"page_id": page_id, "revision": page_id + 10, "title": f"Cookbook:{title}",
             "timestamp": "2026-01-01T00:00:00Z", "wikitext": text}
            for page_id, title, text in [
                (1, "Eggs", wikitext),
                (2, "Cooked eggs", wikitext),
                (3, "Unstructured page", "No recipe sections."),
                (4, "More eggs", wikitext.replace("* 2 eggs", "* 3 eggs")),
            ]
        ]
        data = {"version": 1, "sources": {"en": {
            "site_license": {"text": "CC BY-SA 4.0", "url": "https://creativecommons.org/licenses/by-sa/4.0/"},
            "pages": pages,
        }}}
        with TemporaryDirectory(dir=Path(__file__).resolve().parent, prefix=".catalog-test-") as temp:
            directory = Path(temp)
            snapshot = directory / "snapshot.json"
            snapshot.write_text(json.dumps(data), encoding="utf-8")
            with patch("tools.build_pilot.urlopen", side_effect=AssertionError("network access")):
                report = build_catalog([snapshot], directory)
                first_manifest = (directory / "manifest.json").read_bytes()
                self.assertEqual(build_catalog([snapshot, snapshot], directory), report)
            self.assertEqual((directory / "manifest.json").read_bytes(), first_manifest)
            database = directory / "recipes.sqlite"
            self.assertEqual(json.loads(first_manifest)["files"]["recipes.sqlite"],
                             sha256(database.read_bytes()).hexdigest())
            self.assertEqual(report["recipes_by_language"], {"en": 2})
            self.assertEqual(report["rejected"][0]["page_id"], 3)
            self.assertEqual(len(report["duplicates"]), 1)
            with closing(sqlite3.connect(database)) as connection:
                origins = connection.execute(
                    "SELECT recipe_id, page_id, source_title FROM recipe_origin ORDER BY page_id"
                ).fetchall()
                self.assertEqual(origins, [
                    ("wikibooks:en:1", 1, "Cookbook:Eggs"),
                    ("wikibooks:en:1", 2, "Cookbook:Cooked eggs"),
                    ("wikibooks:en:4", 4, "Cookbook:More eggs"),
                ])
                self.assertEqual(connection.execute("PRAGMA foreign_key_check").fetchall(), [])
