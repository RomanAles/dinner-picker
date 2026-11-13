import base64
import os
import shutil
import sqlite3
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parent
LOCAL_JDK = ROOT / ".venv" / "android-toolchain" / "jdk"
PROBE = """
package app.dinnerpicker;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class DiscoveryProbe {
    public static void main(String[] args) {
        if (args[0].equals("title")) {
            System.out.println(RecipeDiscovery.matchesTitle(RecipeDiscovery.key(args[1]), args[2], args[3]));
            return;
        }
        if (args[0].equals("title-rank")) {
            String query = RecipeDiscovery.key(args[1]);
            for (int i = 2; i < args.length; i += 2) {
                System.out.println(RecipeDiscovery.titleMatch(query, args[i], args[i + 1]));
            }
            return;
        }
        if (args[0].equals("units")) {
            RecipeUnits.System system = RecipeUnits.System.valueOf(args[1]);
            for (int i = 2; i < args.length; i++) {
                String source = new String(Base64.getDecoder().decode(args[i]), StandardCharsets.UTF_8);
                System.out.println(Base64.getEncoder().encodeToString(
                        RecipeUnits.formatText(source, system).getBytes(StandardCharsets.UTF_8)));
            }
            return;
        }
        if (args[0].equals("servings")) {
            Recipe seed = new Recipe("seed", "Omelet", 10, 1);
            seed.ingredients.add(new Recipe.Ingredient("cheese", 30, "g", "g"));
            seed.ingredients.add(new Recipe.Ingredient("flour", 1, "cup", "cup(s)"));
            Recipe imported = new Recipe("imported", "Stew", 40, 4);
            imported.revisionUrl = "source";
            imported.sourceIngredients.add("1 lb potatoes");
            String result = String.join("\\n", seed.ingredientLines(2, RecipeUnits.System.IMPERIAL))
                    + "\\n" + imported.servings(2) + "\\n"
                    + String.join("\\n", imported.ingredientLines(2, RecipeUnits.System.METRIC));
            System.out.println(Base64.getEncoder().encodeToString(result.getBytes(StandardCharsets.UTF_8)));
            return;
        }
        Map<String, Set<String>> filters = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            filters.computeIfAbsent(args[i], unused -> new LinkedHashSet<>()).add(args[i + 1]);
        }
        RecipeDiscovery query = new RecipeDiscovery(filters, args[0].isEmpty() ? null : Integer.valueOf(args[0]));
        System.out.println(Base64.getEncoder().encodeToString(query.sql.getBytes(StandardCharsets.UTF_8)));
        for (String argument : query.arguments) {
            System.out.println(argument);
        }
    }
}
"""


class RecipeDiscoveryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        javac_name = "javac.exe" if os.name == "nt" else "javac"
        javac = next(LOCAL_JDK.glob(f"*/bin/{javac_name}"), shutil.which(javac_name))
        if javac is None:
            raise unittest.SkipTest("JDK 17 is needed to run the native search query tests.")
        cls.java = Path(javac).with_name("java.exe" if os.name == "nt" else "java")
        scratch = tempfile.TemporaryDirectory(dir=ROOT)
        cls.addClassCleanup(scratch.cleanup)
        cls.directory = Path(scratch.name)
        probe = cls.directory / "DiscoveryProbe.java"
        probe.write_text(PROBE, encoding="utf-8")
        source = ROOT / "android/app/src/main/java/app/dinnerpicker"
        subprocess.run([javac, "-encoding", "UTF-8", "--release", "17", "-d", cls.directory,
                        source / "RecipeDiscovery.java", source / "RecipeUnits.java", source / "Recipe.java", probe],
                       check=True)

    def setUp(self):
        self.database = sqlite3.connect(":memory:")
        self.addCleanup(self.database.close)
        self.database.executescript("""
            CREATE TABLE recipe (id TEXT, family_id TEXT, minutes INTEGER, title_ru TEXT, title_en TEXT);
            CREATE TABLE offline_recipe (id TEXT, family_id TEXT, minutes INTEGER, title_ru TEXT, title_en TEXT);
            CREATE TABLE recipe_tag (recipe_id TEXT, axis_id TEXT, tag_id TEXT);
            INSERT INTO recipe VALUES ('omelet-a', 'omelet', 10, 'Омлет', 'Omelet'),
                ('omelet-b', 'omelet', 15, 'Омлет с сыром', 'Cheese omelet'), ('toast', 'toast', 12, 'Тост', 'Toast');
            INSERT INTO offline_recipe VALUES ('chili', NULL, NULL, 'Чили', 'Chili'),
                ('stew', NULL, 50, 'Рагу', 'Stew');
            INSERT INTO recipe_tag VALUES
                ('omelet-a', 'meal', 'breakfast'), ('omelet-a', 'mood', 'cozy'), ('omelet-a', 'difficulty', 'easy'),
                ('omelet-b', 'meal', 'breakfast'), ('omelet-b', 'meal', 'dinner'), ('omelet-b', 'mood', 'cozy'),
                ('omelet-b', 'difficulty', 'easy'), ('toast', 'meal', 'breakfast'), ('toast', 'difficulty', 'easy'),
                ('chili', 'mood', 'spicy'), ('stew', 'meal', 'dinner'), ('stew', 'mood', 'cozy');
        """)

    def search(self, *filters, minutes=None):
        command = [self.java, "-cp", self.directory, "app.dinnerpicker.DiscoveryProbe",
                   "" if minutes is None else str(minutes)]
        for axis_id, tag_id in filters:
            command.extend((axis_id, tag_id))
        sql, *arguments = subprocess.check_output(command, text=True).splitlines()
        return self.database.execute(base64.b64decode(sql).decode(), arguments).fetchall()

    def test_or_within_category_and_across_categories(self):
        rows = self.search(("meal", "breakfast"), ("meal", "dinner"), ("mood", "cozy"), ("difficulty", "easy"))
        self.assertEqual([row[:4] for row in rows if row[3] == 3], [
            ("omelet-a", "seed:omelet", 0, 3), ("omelet-b", "seed:omelet", 0, 3),
        ])
        self.assertEqual([row[0] for row in rows if row[3] == 2], ["toast", "stew"])

    def test_partial_match_keeps_time_limit(self):
        rows = self.search(("meal", "dinner"), ("mood", "cozy"), minutes=12)
        self.assertEqual([row[:5] for row in rows], [("omelet-a", "seed:omelet", 0, 1, "mood")])
        self.assertEqual(self.search(("meal", "dinner"), minutes=12), [])

    def test_unselected_category_and_unknown_time(self):
        self.assertEqual([row[:5] for row in self.search(("mood", "spicy"))], [("chili", "chili", 1, 1, "mood")])
        self.assertEqual(self.search(("mood", "spicy"), minutes=60), [])

    def test_no_filters_can_browse_an_older_catalog(self):
        self.database.execute("DROP TABLE recipe_tag")
        self.assertEqual([row[0] for row in self.search()], ["omelet-a", "toast", "omelet-b", "stew", "chili"])

    def test_normalizes_both_title_languages(self):
        command = [self.java, "-cp", self.directory, "app.dinnerpicker.DiscoveryProbe", "title"]
        self.assertEqual(subprocess.check_output([*command, "  СВЕКЛА ", "Запечённая свёкла", "Baked beets"],
                                                 text=True).strip(), "true")
        self.assertEqual(subprocess.check_output([*command, "BAKED    BEETS", "Запечённая свёкла", "Baked beets"],
                                                 text=True).strip(), "true")

    def test_title_rank_uses_normalized_titles_in_both_languages(self):
        command = [self.java, "-cp", self.directory, "app.dinnerpicker.DiscoveryProbe", "title-rank"]
        matches = subprocess.check_output([*command, "  CHOCOLATE    MOUSSE ",
                                           "Шоколадный мусс", "Chocolate Mousse",
                                           "Шоколадный торт", "Chocolate Mousse Cake",
                                           "Мусс с тофу", "Tofu Chocolate Mousse",
                                           "Пудинг", "Pudding"], text=True).splitlines()
        self.assertEqual(matches, ["EXACT", "PREFIX", "SUBSTRING", "NONE"])
        matches = subprocess.check_output([*command, "СВЕКЛА", "Свёкла", "Baked beets",
                                           "Свёкла запечённая", "Baked beets"], text=True).splitlines()
        self.assertEqual(matches, ["EXACT", "PREFIX"])

    def render_units(self, system, *lines):
        encoded = [base64.b64encode(line.encode()).decode() for line in lines]
        command = [self.java, "-cp", self.directory, "app.dinnerpicker.DiscoveryProbe", "units", system, *encoded]
        return [base64.b64decode(line).decode() for line in subprocess.check_output(command, text=True).splitlines()]

    def test_converts_ingredient_fractions_ranges_and_qualified_volumes(self):
        self.assertEqual(self.render_units("METRIC", "1½ lb beef", "4-5 pounds potatoes", "1 / 2 lb butter",
                                          "1 UK pint milk", "1 US pint milk", "2 tsp salt", "1 cup flour",
                                          "1 pint water", "3 large eggs", "1 cup (240 mL) cream"),
                         ["680.39 g beef", "1.81–2.27 kg potatoes", "226.8 g butter", "568.26 mL milk",
                          "473.18 mL milk", "10 mL salt", "1 cup flour", "1 pint water", "3 large eggs", "240 mL cream"])
        self.assertEqual(self.render_units("IMPERIAL", "100 g cheese", "250 mL (1 cup) water", "250 mL water",
                                          "150 g (6 oz) margarine", "1 tbsp olive oil"),
                         ["3.53 oz cheese", "8.8 UK fl oz water", "8.8 UK fl oz water", "150 g (6 oz) margarine",
                          "1 tbsp olive oil"])

    def test_converts_steps_without_changing_times_or_unrelated_numbers(self):
        self.assertEqual(self.render_units("METRIC", "Bake at 350°F for 25 minutes.", "Bake at 350°F (180°C).",
                                          "Use a 9 x 13 x 2-inch pan.", "Put 2 in each bowl."),
                         ["Bake at 176.7 °C for 25 minutes.", "Bake at 180°C.",
                          "Use a 22.86 × 33.02 × 5.08 cm pan.", "Put 2 in each bowl."])
        self.assertEqual(self.render_units("IMPERIAL", "Chill from −18 to −10 °C.", "Cut a 2 cm border."),
                         ["Chill from -0.4–14 °F.", "Cut a 0.79 in border."])

    def test_scales_structured_quantities_but_keeps_imported_servings(self):
        command = [self.java, "-cp", self.directory, "app.dinnerpicker.DiscoveryProbe", "servings"]
        rendered = base64.b64decode(subprocess.check_output(command, text=True)).decode().splitlines()
        self.assertEqual(rendered, ["cheese · 2.12 oz", "flour · 2 cup(s)", "4", "453.59 g potatoes"])
