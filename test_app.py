import io
import json
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.error import URLError

from app import make_selection
from recipe_catalog import load_catalog


class SQLiteCatalogTests(unittest.TestCase):
    def test_import_preserves_original_recipes(self):
        seed = Path(__file__).with_name("recipes.json")
        original = {recipe["id"]: recipe for recipe in json.loads(seed.read_text(encoding="utf-8"))}
        recipes, _, _ = load_catalog()
        self.assertEqual({recipe["id"] for recipe in recipes}, original.keys())
        for recipe in recipes:
            with self.subTest(recipe=recipe["id"]):
                expected = original[recipe["id"]]
                self.assertEqual(
                    (recipe["family"], recipe["title"], recipe["minutes"], recipe["servings"], recipe["steps"]),
                    (expected["family"], expected["title"], expected["minutes"], 1, expected["steps"]),
                )
                self.assertEqual(
                    list(recipe["ingredients"].items()),
                    [(name, tuple(quantity)) for name, quantity in expected["ingredients"].items()],
                )

    def test_english_uses_the_same_recipe_ids_and_amounts(self):
        russian, _, _ = load_catalog()
        english, aliases, basics = load_catalog(language="en")
        for original, translated in zip(russian, english, strict=True):
            with self.subTest(recipe=original["id"]):
                self.assertEqual(translated["id"], original["id"])
                self.assertNotEqual(translated["title"], original["title"])
                self.assertEqual(len(translated["steps"]), len(original["steps"]))
                self.assertEqual(
                    [amount for amount, _ in translated["ingredients"].values()],
                    [amount for amount, _ in original["ingredients"].values()],
                )
        self.assertEqual(aliases["egg"], "eggs")
        self.assertEqual(basics, {"water", "salt", "vegetable oil"})


class DinnerSelectionTests(unittest.TestCase):
    def setUp(self):
        self.preferences = {
            "ingredients": "яйцо, томаты\nсыр, огурец, шампиньоны",
            "minutes": 12,
            "servings": 2,
            "basics": True,
            "use_ai": False,
        }

    def test_filters_then_groups_and_keeps_single_recipes(self):
        selection = make_selection(self.preferences)
        salad, omelet = selection["cards"]
        self.assertEqual(salad["source"], "recipe")
        self.assertEqual(omelet["source"], "group")
        self.assertEqual(omelet["common"], ["яйца"])
        self.assertEqual([recipe["id"] for recipe in omelet["variants"]],
                         ["omelet-cheese", "omelet-tomato"])
        self.assertEqual(omelet["variants"][0]["ingredients"][0]["amount"], "4")

    def test_no_assumed_pantry_when_unchecked(self):
        self.preferences["basics"] = False
        self.assertEqual(make_selection(self.preferences)["cards"], [])

    def test_invalid_portions(self):
        self.preferences["servings"] = True
        with self.assertRaisesRegex(ValueError, "порций"):
            make_selection(self.preferences)

    @patch("app.urlopen")
    def test_ai_request_and_validated_summary(self, urlopen):
        self.preferences["use_ai"] = True
        summary = "Яйца готовят на сковороде с сыром или помидорами."
        self.mock_response(urlopen, "omelet-tomato", summary)
        selection = make_selection(self.preferences)
        omelet = selection["cards"][1]
        self.assertEqual(selection["ai_status"], "used")
        self.assertEqual((omelet["source"], omelet["base_id"], omelet["summary"]),
                         ("ai", "omelet-tomato", summary))
        payload = json.loads(urlopen.call_args.args[0].data)
        choices = json.loads(payload["messages"][1]["content"])
        self.assertEqual([choice["family"] for choice in choices], ["Омлет"])
        self.assertEqual([recipe["id"] for recipe in choices[0]["recipes"]],
                         ["omelet-cheese", "omelet-tomato"])

    @patch("app.urlopen")
    def test_rejects_out_of_budget_recipe_from_model(self, urlopen):
        self.preferences["use_ai"] = True
        self.mock_response(urlopen, "omelet-mushroom", "Общий способ приготовления омлета.")
        selection = make_selection(self.preferences)
        self.assertEqual(selection["ai_status"], "invalid")
        self.assertEqual(selection["cards"][1]["source"], "group")
        self.assertEqual(selection["cards"][1]["base_id"], "omelet-cheese")

    @patch("app.urlopen", side_effect=URLError("Ollama unavailable"))
    def test_offline_still_groups_recipes(self, urlopen):
        self.preferences["use_ai"] = True
        selection = make_selection(self.preferences)
        self.assertEqual(selection["ai_status"], "unavailable")
        self.assertEqual(len(selection["cards"]), 2)
        self.assertEqual(selection["cards"][1]["source"], "group")
        urlopen.assert_called_once()

    @patch("app.urlopen")
    def test_single_recipe_does_not_call_model(self, urlopen):
        self.preferences.update(ingredients="хлеб, сыр", use_ai=True)
        selection = make_selection(self.preferences)
        self.assertEqual(selection["ai_status"], "not_needed")
        self.assertEqual(len(selection["cards"]), 1)
        self.assertEqual(selection["cards"][0]["source"], "recipe")
        urlopen.assert_not_called()

    def mock_response(self, urlopen, base_id, summary):
        groups = [{"family": "Омлет", "base_id": base_id, "summary": summary}]
        response = {"message": {"content": json.dumps({"groups": groups})}}
        urlopen.return_value = io.BytesIO(json.dumps(response).encode("utf-8"))


if __name__ == "__main__":
    unittest.main()
