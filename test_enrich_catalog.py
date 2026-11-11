import unittest

from tools.enrich_catalog import (
    explicit_ingredient_additions,
    quantities,
    recover_source_ingredients,
    recover_source_steps,
    validate_additions,
    validate_translation,
)
from tools.export_android import equivalent_units, ingredient_keys


class RecipeEnrichmentTests(unittest.TestCase):
    def setUp(self):
        self.recipe = {
            "title": "Toast", "language": "en", "ingredients": ["1½ slices of bread"],
            "steps": ["Toast the bread and brush it with olive oil."],
            "raw": "",
        }
        self.translation = {
            "title": "Тосты", "ingredients": ["1 1/2 ломтика хлеба"],
            "steps": ["Поджарьте хлеб и смажьте его оливковым маслом."],
            "ingredient_additions": [],
            "step_additions": [],
        }

    def test_accepts_equivalent_fractions_and_detects_ingredient_substitutions(self):
        self.assertEqual(quantities("1½"), quantities("1 1/2"))
        self.assertEqual(validate_translation(self.translation, self.recipe), [])
        self.recipe["ingredients"] = ["2 teaspoons rosemary"]
        self.translation["ingredients"] = ["3 чайные ложки мяты"]
        self.assertEqual(validate_translation(self.translation, self.recipe),
                         ["ingredients:1:quantity", "ingredients:1:ingredient"])

    def test_proposals_keep_provenance_and_cannot_replace_source_steps(self):
        self.translation["ingredient_additions"] = [
            {"source": "olive oil", "target": "оливковое масло", "evidence": self.recipe["steps"][0]},
            {"source": "Salt", "target": "Соль", "evidence": "A fake quotation not present in the source."},
        ]
        self.translation["step_additions"] = [
            {"source": "Serve on a plate", "target": "Подайте на тарелке", "evidence": "", "after_step": 4},
        ]
        proposals = validate_additions(self.translation, self.recipe)
        self.assertEqual([entry[4] for entry in proposals], ["source_excerpt", "ai_suggestion"])
        self.assertEqual(self.recipe["ingredients"], ["1½ slices of bread"])

    def test_recovers_steps_from_lists_and_gallery_captions(self):
        self.assertEqual(recover_source_steps(
            "== Рецепт ==\n# Нарежьте хлеб.\n# Поджарьте.\n== Источники ==\n* Ссылка"
        ), ["Нарежьте хлеб.", "Поджарьте."])
        self.assertEqual(recover_source_steps(
            "== Рецепт ==\n<gallery>\nbread.jpg|Подсушите [[Хлеб|хлеб]].\n</gallery>"
        ), ["Подсушите хлеб."])

    def test_recovers_prose_ingredients_and_detects_missing_explicit_product(self):
        self.assertEqual(recover_source_ingredients(
            "== Состав ==\nНа 2 порции взять для теста — 1 стакан муки, 2 яйца, 1 стакан воды.",
            "ru",
        ), ["1 стакан муки", "2 яйца", "1 стакан воды"])
        self.recipe["steps"] = ["Add water and stir."]
        proposal = explicit_ingredient_additions(self.recipe)
        self.assertEqual(proposal, [{"source": "water", "target": "вода", "evidence": "Add water and stir."}])

    def test_rejects_changed_cooking_method(self):
        self.recipe["steps"] = ["Steam for 15 minutes."]
        self.translation["steps"] = ["Варите в воде 15 минут."]
        self.assertEqual(validate_translation(self.translation, self.recipe), ["steps:1:method"])

    def test_export_does_not_guess_alternative_or_conflicting_ingredient(self):
        from tools.build_catalog import RULES, SEED_NAMES, read_rules
        import json

        rules = read_rules(json.loads(RULES.read_bytes()), json.loads(SEED_NAMES.read_bytes()))
        self.assertEqual(ingredient_keys(["2 яйца"], ["2 eggs"], rules)[0], {"egg"})
        self.assertEqual(ingredient_keys(["2 яйца"], ["2 potatoes"], rules), (set(), set()))
        self.assertEqual(ingredient_keys(["сыр или яйца"], ["cheese or eggs"], rules), (set(), set()))
        self.assertFalse(equivalent_units(["2 чайные ложки соли"], ["2 tablespoons salt"], rules))
        self.assertTrue(equivalent_units(["2 столовые ложки соли"], ["2 tablespoons salt"], rules))
