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
        source = ROOT / "android/app/src/main/java/app/dinnerpicker/RecipeDiscovery.java"
        subprocess.run([javac, "-encoding", "UTF-8", "--release", "17", "-d", cls.directory, source, probe], check=True)

    def setUp(self):
        self.database = sqlite3.connect(":memory:")
        self.addCleanup(self.database.close)
        self.database.executescript("""
            CREATE TABLE recipe (id TEXT, family_id TEXT, minutes INTEGER);
            CREATE TABLE offline_recipe (id TEXT, family_id TEXT, minutes INTEGER);
            CREATE TABLE recipe_tag (recipe_id TEXT, axis_id TEXT, tag_id TEXT);
            INSERT INTO recipe VALUES ('omelet-a', 'omelet', 10), ('omelet-b', 'omelet', 15), ('toast', 'toast', 12);
            INSERT INTO offline_recipe VALUES ('chili', NULL, NULL), ('stew', NULL, 50);
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
        self.assertEqual([row[:4] for row in rows], [
            ("omelet-a", "seed:omelet", 0, 3), ("omelet-b", "seed:omelet", 0, 3),
        ])

    def test_partial_match_keeps_time_limit(self):
        rows = self.search(("meal", "dinner"), ("mood", "cozy"), minutes=12)
        self.assertEqual(rows, [("omelet-a", "seed:omelet", 0, 1, "mood")])
        self.assertEqual(self.search(("meal", "dinner"), minutes=12), [])

    def test_unselected_category_and_unknown_time(self):
        self.assertEqual(self.search(("mood", "spicy")), [("chili", "chili", 1, 1, "mood")])
        self.assertEqual(self.search(("mood", "spicy"), minutes=60), [])

    def test_no_filters_can_browse_an_older_catalog(self):
        self.database.execute("DROP TABLE recipe_tag")
        self.assertEqual([row[0] for row in self.search()], ["omelet-a", "toast", "omelet-b", "stew", "chili"])
