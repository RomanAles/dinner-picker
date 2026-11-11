import sqlite3
import unittest
from contextlib import closing

from tools.infer_recipe_tags import infer_tags
from tools.tag_catalog import apply_tags


class TagCatalogTests(unittest.TestCase):
    def test_applies_optional_bilingual_tags_to_available_recipes(self):
        with closing(sqlite3.connect(":memory:")) as database:
            database.executescript("""
                CREATE TABLE recipe (id TEXT PRIMARY KEY);
                CREATE TABLE offline_recipe (id TEXT PRIMARY KEY, title_en TEXT, ingredients_en TEXT, steps_en TEXT,
                                             title_ru TEXT, ingredients_ru TEXT, steps_ru TEXT);
                INSERT INTO recipe VALUES ('omelet-cheese');
                INSERT INTO offline_recipe (id) VALUES ('wikibooks:en:127669'), ('unannotated');
                INSERT INTO offline_recipe VALUES ('wikibooks:en:000001', 'Tomato Soup', '["Tomatoes"]',
                    '["Simmer the tomatoes."]', 'Томатный салат', '["Помидоры"]', '["Нарежьте помидоры."]');
            """)
            stats = apply_tags(database)
            self.assertEqual(stats, apply_tags(database))
            self.assertEqual(stats["tagged_recipes"], 3)
            self.assertEqual(stats["manually_tagged_recipes"], 2)
            self.assertEqual(stats["automatically_tagged_recipes"], 1)
            self.assertEqual(stats["untagged_recipes"], 1)
            self.assertEqual(stats["coverage"]["meal"], 2)
            self.assertEqual(database.execute("""
                SELECT DISTINCT recipe_id FROM recipe_tag ORDER BY recipe_id
            """).fetchall(), [("omelet-cheese",), ("wikibooks:en:000001",), ("wikibooks:en:127669",)])
            self.assertEqual(database.execute("""
                SELECT recipe_id, tag_id, name_ru, name_en FROM recipe_tag
                JOIN tag ON (tag.axis_id, tag.id) = (recipe_tag.axis_id, recipe_tag.tag_id)
                WHERE recipe_tag.axis_id = 'meal' AND recipe_id = 'omelet-cheese' ORDER BY tag.position
            """).fetchall(), [
                ("omelet-cheese", "breakfast", "Завтрак", "Breakfast"),
                ("omelet-cheese", "dinner", "Ужин", "Dinner"),
            ])
            self.assertEqual(database.execute("""
                SELECT tag_id, note_ru, note_en FROM recipe_tag
                WHERE recipe_id = 'wikibooks:en:127669' AND axis_id = 'difficulty'
            """).fetchone(), ("easy", "Достаточно смешать готовые продукты.", "Simply mix the ingredients."))
            self.assertFalse(database.execute("""
                SELECT 1 FROM recipe_tag WHERE recipe_id = 'wikibooks:en:127669' AND axis_id = 'satiety'
            """).fetchall())
            self.assertEqual(database.execute("PRAGMA foreign_key_check").fetchall(), [])
            self.assertEqual(database.execute("""
                SELECT tag_id FROM recipe_tag WHERE recipe_id = 'wikibooks:en:000001' AND axis_id = 'dish_type'
            """).fetchall(), [("soup",)])

    def test_ambiguous_and_component_titles_do_not_get_misleading_tags(self):
        sauce = infer_tags("en", "Pizza Sauce", ["Tomatoes"], ["Mix the ingredients."])
        self.assertEqual(sauce["dish_type"], ["sauce"])
        self.assertEqual(sauce["technique"], ["no_heat"])
        self.assertNotIn("meal", sauce)
        fish = infer_tags("en", "Fish Cakes", ["Fish"], ["Heat oil in a skillet."])
        self.assertNotIn("dish_type", fish)
        self.assertNotIn("mood", fish)
        self.assertNotIn("occasion", fish)
        cookies = infer_tags("en", "Oatmeal Chocolate Chip Cookies", ["Oatmeal"], ["Bake until golden."])
        self.assertEqual(cookies["dish_type"], ["baking", "dessert"])
        self.assertNotIn("meal", cookies)
        self.assertNotIn("dish_type", infer_tags("en", "Tofu in Spiced Coconut Sauce", [], ["Mix."]))
        self.assertEqual(infer_tags("ru", "Супер-рецепт", [], ["Подать к столу."]), {})
