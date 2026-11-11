package app.dinnerpicker;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RecipeCatalog {
    static final class Ingredient {
        final String name;
        final double amount;
        final String unit;

        Ingredient(String name, double amount, String unit) {
            this.name = name;
            this.amount = amount;
            this.unit = unit;
        }
    }

    static final class Recipe {
        final String id;
        final String title;
        final int minutes;
        final int baseServings;
        final List<Ingredient> ingredients = new ArrayList<>();
        final List<String> steps = new ArrayList<>();
        final List<String> sourceIngredients = new ArrayList<>();
        String revisionUrl;
        String licenseUrl;
        List<Tag> tags = Collections.emptyList();
        List<String> missingTagAxes = Collections.emptyList();

        Recipe(String id, String title, int minutes, int baseServings) {
            this.id = id;
            this.title = title;
            this.minutes = minutes;
            this.baseServings = baseServings;
        }
    }

    static final class Card {
        final String title;
        final List<Recipe> variants = new ArrayList<>();
        String summary;

        Card(String title) {
            this.title = title;
        }

        String label() {
            return variants.size() == 1 ? variants.get(0).title : title;
        }
    }

    static final class CatalogPage {
        final List<Card> cards;
        final int total;
        final boolean partial;
        final List<String> titleHints;
        final List<Refinement> refinements;

        CatalogPage(List<Card> cards, int total, boolean partial, List<String> titleHints,
                    List<Refinement> refinements) {
            this.cards = cards;
            this.total = total;
            this.partial = partial;
            this.titleHints = titleHints;
            this.refinements = refinements;
        }
    }

    enum Change { TIME, CATEGORY, TITLE }

    record Refinement(Change change, String axis, Integer minutes, int total) {}

    static final class TagAxis {
        final String id;
        final String label;
        final boolean multiple;
        final List<Tag> tags = new ArrayList<>();

        TagAxis(String id, String label, boolean multiple) {
            this.id = id;
            this.label = label;
            this.multiple = multiple;
        }
    }

    static final class Tag {
        final String axisId;
        final String id;
        final String label;
        final String alternateLabel;
        final String note;

        Tag(String axisId, String id, String label, String alternateLabel, String note) {
            this.axisId = axisId;
            this.id = id;
            this.label = label;
            this.alternateLabel = alternateLabel;
            this.note = note;
        }
    }

    private record Match(String id, boolean imported, String titleRu, String titleEn, int minutes,
                         List<String> missingAxes) {}

    private RecipeCatalog() {}

    private static File installedCatalog(Context context) throws IOException {
        long updateTime;
        try {
            updateTime = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException error) {
            throw new IOException("Cannot identify app version", error);
        }
        File directory = context.getCacheDir();
        File target = new File(directory, "recipes-" + updateTime + ".sqlite");
        if (!target.isFile()) {
            File temporary = File.createTempFile("recipes-", ".tmp", directory);
            try (InputStream asset = context.getAssets().open("recipes.sqlite")) {
                Files.copy(asset, temporary.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary.toPath());
            }
        }
        return target;
    }

    static String key(String value) {
        return RecipeDiscovery.key(value);
    }

    static List<Card> find(Context context, String text, int minutes, boolean includeBasics, boolean russian)
            throws IOException {
        String lang = russian ? "ru" : "en";
        File file = installedCatalog(context);
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            Set<String> available = pantry(db, text, includeBasics, russian);
            Map<String, Card> groups = new LinkedHashMap<>();
            try (Cursor rows = db.rawQuery(
                    "SELECT r.id, r.family_id, f.name_" + lang + ", r.title_" + lang +
                    ", r.minutes, r.servings FROM recipe r JOIN family f ON f.id=r.family_id" +
                    " WHERE r.minutes<=? ORDER BY r.minutes, r.id",
                    new String[] {Integer.toString(minutes)})) {
                while (rows.moveToNext()) {
                    String id = rows.getString(0);
                    Recipe recipe = new Recipe(id, rows.getString(3), rows.getInt(4), rows.getInt(5));
                    if (!readIngredients(db, id, lang, available, recipe)) {
                        continue;
                    }
                    readSteps(db, id, lang, recipe);
                    String family = rows.getString(2);
                    groups.computeIfAbsent(rows.getString(1), unused -> new Card(family)).variants.add(recipe);
                }
            }
            try (Cursor rows = db.rawQuery(externalRows(lang) + " WHERE r.strict_ready=1 AND r.minutes<=?",
                    new String[] {Integer.toString(minutes)})) {
                while (rows.moveToNext()) {
                    if (availableExternal(db, rows.getString(0), available)) {
                        addExternal(rows, groups);
                    }
                }
            }
            List<Card> cards = new ArrayList<>(groups.values());
            cards.sort(Comparator.comparingInt((Card card) -> card.variants.get(0).minutes)
                    .thenComparing(Card::label));
            return cards;
        }
    }

    static List<TagAxis> discoveryAxes(Context context, boolean russian) throws IOException {
        String lang = russian ? "ru" : "en";
        String other = russian ? "en" : "ru";
        Map<String, TagAxis> axes = new LinkedHashMap<>();
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(installedCatalog(context).getPath(),
                null, SQLiteDatabase.OPEN_READONLY)) {
            if (!hasTags(db)) {
                return Collections.emptyList();
            }
            try (Cursor rows = db.rawQuery(
                    "SELECT a.id, a.name_" + lang + ", a.multiple, t.id, t.name_" + lang + ", t.name_" + other +
                    " FROM tag_axis a JOIN tag t ON t.axis_id=a.id ORDER BY a.position, t.position", null)) {
                while (rows.moveToNext()) {
                    String axisId = rows.getString(0);
                    TagAxis axis = axes.computeIfAbsent(axisId,
                            id -> new TagAxis(id, rows.getString(1), rows.getInt(2) != 0));
                    axis.tags.add(new Tag(axisId, rows.getString(3), rows.getString(4), rows.getString(5), null));
                }
            }
        }
        return new ArrayList<>(axes.values());
    }

    static CatalogPage discover(Context context, String title, Map<String, Set<String>> filters, Integer maxMinutes,
                                int offset, int limit, boolean russian) throws IOException {
        if (offset < 0 || limit < 1) {
            throw new IllegalArgumentException("Invalid catalog page.");
        }
        RecipeDiscovery query = new RecipeDiscovery(filters, maxMinutes);
        String normalizedTitle = key(title);
        String lang = russian ? "ru" : "en";
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(installedCatalog(context).getPath(),
                null, SQLiteDatabase.OPEN_READONLY)) {
            boolean taggedCatalog = hasTags(db);
            if (!taggedCatalog && !query.axes.isEmpty()) {
                return new CatalogPage(Collections.emptyList(), 0, false, Collections.emptyList(), Collections.emptyList());
            }
            Map<String, List<Match>> matches = searchMatches(db, query, normalizedTitle, false);
            boolean partial = !matches.isEmpty() && !matches.values().iterator().next().get(0).missingAxes().isEmpty();
            List<String> titleHints = normalizedTitle.isEmpty() || partial ? Collections.emptyList()
                    : matches.values().stream().limit(4).map(family -> family.get(0))
                            .map(match -> russian ? match.titleRu() : match.titleEn())
                            .collect(java.util.stream.Collectors.toList());
            List<Refinement> refinements = matches.isEmpty() || partial
                    ? refinements(db, normalizedTitle, filters, maxMinutes) : Collections.emptyList();

            // Choose the page after grouping so a family cannot span multiple pages.
            Map<String, Card> groups = new LinkedHashMap<>();
            int index = 0;
            for (List<Match> family : matches.values()) {
                if (index++ < offset) {
                    continue;
                }
                if (groups.size() == limit) {
                    break;
                }
                for (Match match : family) {
                    Recipe recipe = addRecipe(db, match.id(), match.imported(), lang, groups);
                    if (taggedCatalog && recipe != null) {
                        recipe.tags = readTags(db, match.id(), lang);
                        recipe.missingTagAxes = match.missingAxes();
                    }
                }
            }
            return new CatalogPage(new ArrayList<>(groups.values()), matches.size(), partial, titleHints, refinements);
        }
    }

    private static Map<String, List<Match>> searchMatches(SQLiteDatabase db, RecipeDiscovery query,
                                                         String title, boolean exactOnly) {
        Map<String, List<Match>> matches = new LinkedHashMap<>();
        int best = exactOnly ? query.axes.size() : -1;
        try (Cursor rows = db.rawQuery(query.sql, query.arguments)) {
            while (rows.moveToNext()) {
                int hits = rows.getInt(3);
                if (hits < best) {
                    break;
                }
                if (!RecipeDiscovery.matchesTitle(title, rows.getString(5), rows.getString(6))) {
                    continue;
                }
                best = hits;
                matches.computeIfAbsent(rows.getString(1), ignored -> new ArrayList<>()).add(new Match(
                        rows.getString(0), rows.getInt(2) != 0, rows.getString(5), rows.getString(6),
                        rows.getInt(7), query.missingAxes(rows.getString(4))));
            }
        }
        return matches;
    }

    private static List<Refinement> refinements(SQLiteDatabase db, String title,
                                                Map<String, Set<String>> filters, Integer maxMinutes) {
        List<Refinement> options = new ArrayList<>();
        if (maxMinutes != null) {
            Map<String, List<Match>> withoutTime = searchMatches(db, new RecipeDiscovery(filters, null), title, true);
            if (!withoutTime.isEmpty()) {
                int shortest = withoutTime.values().iterator().next().get(0).minutes();
                Integer extended = shortest > 0 ? (int) Math.min(Integer.MAX_VALUE, ((long) shortest + 14) / 15 * 15) : null;
                int total = extended == null ? withoutTime.size() : searchMatches(
                        db, new RecipeDiscovery(filters, extended), title, true).size();
                options.add(new Refinement(Change.TIME, null, extended, total));
            }
        }
        for (String axis : filters.keySet()) {
            Map<String, Set<String>> relaxed = new LinkedHashMap<>(filters);
            relaxed.remove(axis);
            int total = searchMatches(db, new RecipeDiscovery(relaxed, maxMinutes), title, true).size();
            if (total > 0) {
                options.add(new Refinement(Change.CATEGORY, axis, null, total));
            }
        }
        if (!title.isEmpty()) {
            int total = searchMatches(db, new RecipeDiscovery(filters, maxMinutes), "", true).size();
            if (total > 0) {
                options.add(new Refinement(Change.TITLE, null, null, total));
            }
        }
        return options;
    }

    static Recipe recipe(Context context, String id, boolean russian) throws IOException {
        String lang = russian ? "ru" : "en";
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(installedCatalog(context).getPath(),
                null, SQLiteDatabase.OPEN_READONLY)) {
            Map<String, Card> group = new LinkedHashMap<>();
            Recipe recipe = addRecipe(db, id, false, lang, group);
            if (recipe == null) {
                recipe = addRecipe(db, id, true, lang, group);
            }
            if (recipe != null && hasTags(db)) {
                recipe.tags = readTags(db, id, lang);
            }
            return recipe;
        }
    }

    private static boolean hasTags(SQLiteDatabase db) {
        try (Cursor rows = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='recipe_tag'", null)) {
            return rows.moveToFirst();
        }
    }

    private static List<Tag> readTags(SQLiteDatabase db, String id, String lang) {
        List<Tag> tags = new ArrayList<>();
        try (Cursor rows = db.rawQuery("SELECT t.axis_id, t.id, t.name_" + lang + ", r.note_" + lang +
                " FROM recipe_tag r JOIN tag t ON t.axis_id=r.axis_id AND t.id=r.tag_id" +
                " JOIN tag_axis a ON a.id=t.axis_id WHERE r.recipe_id=? ORDER BY a.position, t.position",
                new String[] {id})) {
            while (rows.moveToNext()) {
                tags.add(new Tag(rows.getString(0), rows.getString(1), rows.getString(2), null, rows.getString(3)));
            }
        }
        return tags;
    }

    private static Recipe addRecipe(SQLiteDatabase db, String id, boolean imported, String lang,
                                    Map<String, Card> groups) throws IOException {
        String[] argument = {id};
        if (imported) {
            try (Cursor rows = db.rawQuery(externalRows(lang) + " WHERE r.id=?", argument)) {
                return rows.moveToFirst() ? addExternal(rows, groups) : null;
            }
        }
        try (Cursor row = db.rawQuery(
                "SELECT r.family_id, f.name_" + lang + ", r.title_" + lang + ", r.minutes, r.servings FROM recipe r" +
                " JOIN family f ON f.id=r.family_id WHERE r.id=?", argument)) {
            if (!row.moveToFirst()) {
                return null;
            }
            Recipe recipe = new Recipe(id, row.getString(2), row.getInt(3), row.getInt(4));
            readIngredients(db, id, lang, null, recipe);
            readSteps(db, id, lang, recipe);
            String family = row.getString(1);
            groups.computeIfAbsent(row.getString(0), ignored -> new Card(family)).variants.add(recipe);
            return recipe;
        }
    }

    private static String externalRows(String lang) {
        return "SELECT r.id, r.family_id, r.title_" + lang + ", r.ingredients_" + lang + ", r.steps_" + lang +
                ", r.minutes, r.servings, r.revision_url, r.license_url, f.title_" + lang +
                ", f.summary_" + lang + " FROM offline_recipe r LEFT JOIN offline_family f ON f.id=r.family_id";
    }

    private static boolean availableExternal(SQLiteDatabase db, String id, Set<String> available) {
        try (Cursor rows = db.rawQuery("SELECT ingredient_key FROM offline_ingredient WHERE recipe_id=?",
                new String[] {id})) {
            if (!rows.moveToFirst()) {
                return false;
            }
            do {
                if (!available.contains(rows.getString(0))) {
                    return false;
                }
            } while (rows.moveToNext());
            return true;
        }
    }

    private static Recipe addExternal(Cursor row, Map<String, Card> groups) throws IOException {
        String title = row.getString(2);
        Recipe recipe = new Recipe(row.getString(0), title, row.getInt(5), row.getInt(6));
        recipe.revisionUrl = row.getString(7);
        recipe.licenseUrl = row.getString(8);
        readJsonLines(row.getString(3), recipe.sourceIngredients);
        readJsonLines(row.getString(4), recipe.steps);
        String family = row.getString(1);
        Card card = groups.computeIfAbsent(family == null ? row.getString(0) : "offline:" + family,
                ignored -> new Card(family == null ? title : row.getString(9)));
        card.summary = row.getString(10);
        card.variants.add(recipe);
        return recipe;
    }

    private static void readJsonLines(String data, List<String> lines) throws IOException {
        try {
            JSONArray values = new JSONArray(data);
            for (int i = 0; i < values.length(); i++) {
                lines.add(values.getString(i));
            }
        } catch (JSONException error) {
            throw new IOException("Invalid recipe data", error);
        }
    }

    private static Set<String> pantry(SQLiteDatabase db, String text, boolean includeBasics, boolean russian) {
        Map<String, String> names = new HashMap<>();
        Set<String> available = new HashSet<>();
        try (Cursor rows = db.rawQuery("SELECT id, name_ru, name_en, is_basic FROM ingredient", null)) {
            while (rows.moveToNext()) {
                String id = rows.getString(0);
                names.put(key(rows.getString(1)), id);
                names.put(key(rows.getString(2)), id);
                if (includeBasics && rows.getInt(3) != 0) {
                    available.add(id);
                }
            }
        }
        try (Cursor rows = db.rawQuery(
                "SELECT alias, ingredient_id FROM ingredient_alias ORDER BY (language=?)",
                new String[] {russian ? "ru" : "en"})) {
            while (rows.moveToNext()) {
                names.put(key(rows.getString(0)), rows.getString(1));
            }
        }
        try (Cursor rows = db.rawQuery(
                "SELECT name, ingredient_key FROM offline_alias ORDER BY (language=?) DESC",
                new String[] {russian ? "ru" : "en"})) {
            while (rows.moveToNext()) {
                names.putIfAbsent(key(rows.getString(0)), rows.getString(1));
            }
        }
        for (String item : text.split("[,;\\r\\n]+")) {
            String id = names.get(key(item));
            if (id != null) {
                available.add(id);
            }
        }
        return available;
    }

    private static boolean readIngredients(SQLiteDatabase db, String id, String lang, Set<String> available,
                                           Recipe recipe) {
        try (Cursor rows = db.rawQuery(
                "SELECT i.id, i.name_" + lang + ", ri.amount, u.label_" + lang +
                " FROM recipe_ingredient ri JOIN ingredient i ON i.id=ri.ingredient_id" +
                " JOIN unit u ON u.id=ri.unit_id WHERE ri.recipe_id=? ORDER BY ri.position",
                new String[] {id})) {
            while (rows.moveToNext()) {
                if (available != null && !available.contains(rows.getString(0))) {
                    return false;
                }
                recipe.ingredients.add(new Ingredient(rows.getString(1), rows.getDouble(2), rows.getString(3)));
            }
        }
        return !recipe.ingredients.isEmpty();
    }

    private static void readSteps(SQLiteDatabase db, String id, String lang, Recipe recipe) {
        try (Cursor steps = db.rawQuery(
                "SELECT text_" + lang + " FROM recipe_step WHERE recipe_id=? ORDER BY position",
                new String[] {id})) {
            while (steps.moveToNext()) {
                recipe.steps.add(steps.getString(0));
            }
        }
    }
}
