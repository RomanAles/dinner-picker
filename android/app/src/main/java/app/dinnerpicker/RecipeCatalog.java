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
    private static final String SEED_ROWS = """
            SELECT r.id, r.family_id, f.name_en, r.title_en, r.minutes, r.servings
            FROM recipe r JOIN family f ON f.id=r.family_id
            """;
    private static final int MAX_PANTRY_SUGGESTIONS = 3;

    record PantryResult(List<Card> cards, List<PantrySuggestion> suggestions) {}

    record PantrySuggestion(Recipe recipe, String missingIngredient) {}

    private record Pantry(Set<String> available, Set<String> entered, Map<String, String> labels) {}

    private record PantryMatch(String missingKey, boolean usesEnteredIngredient) {}

    private record PantryCandidate(String id, boolean imported, String title, int minutes,
                                   String missingIngredient) {}

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
        final List<Recipe.Tag> tags = new ArrayList<>();

        TagAxis(String id, String label, boolean multiple) {
            this.id = id;
            this.label = label;
            this.multiple = multiple;
        }
    }

    private record Match(String id, String cardId, boolean imported, String titleEn, int minutes,
                         List<String> missingAxes, RecipeDiscovery.TitleMatch titleMatch) {}

    private record CatalogRecipe(Recipe recipe, String cardId, String cardTitle, String cardSummary) {}

    private RecipeCatalog() {}

    private static synchronized File installedCatalog(Context context) throws IOException {
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
            try {
                try (InputStream asset = context.getAssets().open("recipes.sqlite")) {
                    Files.copy(asset, temporary.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary.toPath());
            }
        }
        return target;
    }

    private static SQLiteDatabase openCatalog(Context context) throws IOException {
        return SQLiteDatabase.openDatabase(installedCatalog(context).getPath(), null, SQLiteDatabase.OPEN_READONLY);
    }

    static String key(String value) {
        return RecipeDiscovery.key(value);
    }

    static PantryResult findPantry(Context context, String text, int minutes, boolean includeBasics)
            throws IOException {
        try (SQLiteDatabase db = openCatalog(context)) {
            Pantry pantry = pantry(db, text, includeBasics);
            Map<String, Card> groups = new LinkedHashMap<>();
            List<PantryCandidate> candidates = new ArrayList<>();
            String[] timeLimit = {Integer.toString(minutes)};
            try (Cursor rows = db.rawQuery(SEED_ROWS + " WHERE r.minutes<=? ORDER BY r.minutes, r.id", timeLimit)) {
                collectPantryMatches(db, rows, false, pantry, groups, candidates);
            }
            String importedRows = externalRows(db);
            try (Cursor rows = db.rawQuery(importedRows +
                    " WHERE r.strict_ready=1 AND r.minutes>0 AND r.minutes<=? ORDER BY r.minutes, r.id", timeLimit)) {
                collectPantryMatches(db, rows, true, pantry, groups, candidates);
            }
            boolean taggedCatalog = hasTable(db, "recipe_tag");
            if (groups.isEmpty()) {
                List<PantrySuggestion> suggestions = pantrySuggestions(db, candidates, importedRows, taggedCatalog);
                return new PantryResult(Collections.emptyList(), suggestions);
            }
            List<Card> cards = new ArrayList<>(groups.values());
            if (taggedCatalog) {
                for (Card card : cards) {
                    for (Recipe recipe : card.variants) {
                        recipe.tags = readTags(db, recipe.id);
                    }
                }
            }
            cards.sort(Comparator.comparingInt((Card card) -> card.variants.get(0).minutes)
                    .thenComparing(Card::label));
            return new PantryResult(cards, Collections.emptyList());
        }
    }

    private static void collectPantryMatches(SQLiteDatabase db, Cursor rows, boolean imported, Pantry pantry,
                                             Map<String, Card> groups, List<PantryCandidate> candidates)
            throws IOException {
        String requirements = imported ? "SELECT ingredient_key FROM offline_ingredient WHERE recipe_id=?"
                : "SELECT ingredient_id FROM recipe_ingredient WHERE recipe_id=?";
        while (rows.moveToNext()) {
            String id = rows.getString(0);
            PantryMatch match;
            try (Cursor ingredients = db.rawQuery(requirements, new String[] {id})) {
                match = matchPantryIngredients(ingredients, pantry);
            }
            if (match == null) {
                continue;
            }
            if (match.missingKey() == null) {
                addToCards(groups, imported ? readExternal(rows) : readSeed(db, rows));
            } else if (groups.isEmpty() && match.usesEnteredIngredient()) {
                String label = pantry.labels().get(match.missingKey());
                if (label != null) {
                    candidates.add(new PantryCandidate(id, imported, rows.getString(imported ? 2 : 3),
                            rows.getInt(imported ? 5 : 4), label));
                }
            }
        }
    }

    private static PantryMatch matchPantryIngredients(Cursor ingredients, Pantry pantry) {
        if (!ingredients.moveToFirst()) {
            return null;
        }
        String missing = null;
        boolean usesEnteredIngredient = false;
        do {
            String key = ingredients.getString(0);
            if (pantry.entered().contains(key)) {
                usesEnteredIngredient = true;
            } else if (!pantry.available().contains(key)) {
                if (missing != null && !missing.equals(key)) {
                    return null;
                }
                missing = key;
            }
        } while (ingredients.moveToNext());
        return new PantryMatch(missing, usesEnteredIngredient);
    }

    private static List<PantrySuggestion> pantrySuggestions(SQLiteDatabase db, List<PantryCandidate> candidates,
                                                           String importedRows, boolean taggedCatalog)
            throws IOException {
        candidates.sort(Comparator.comparingInt(PantryCandidate::minutes)
                .thenComparing(PantryCandidate::title).thenComparing(PantryCandidate::id));
        Set<String> seenCards = new HashSet<>();
        List<PantrySuggestion> suggestions = new ArrayList<>();
        for (PantryCandidate candidate : candidates) {
            CatalogRecipe entry = readRecipe(db, candidate.id(), candidate.imported(), importedRows);
            if (entry == null || !seenCards.add(entry.cardId())) {
                continue;
            }
            Recipe recipe = entry.recipe();
            if (taggedCatalog) {
                recipe.tags = readTags(db, recipe.id);
            }
            suggestions.add(new PantrySuggestion(recipe, candidate.missingIngredient()));
            if (suggestions.size() == MAX_PANTRY_SUGGESTIONS) {
                break;
            }
        }
        return suggestions;
    }

    static List<TagAxis> discoveryAxes(Context context) throws IOException {
        Map<String, TagAxis> axes = new LinkedHashMap<>();
        try (SQLiteDatabase db = openCatalog(context)) {
            if (!hasTable(db, "recipe_tag")) {
                return Collections.emptyList();
            }
            try (Cursor rows = db.rawQuery(
                    "SELECT a.id, a.name_en, a.multiple, t.id, t.name_en, t.name_ru" +
                    " FROM tag_axis a JOIN tag t ON t.axis_id=a.id ORDER BY a.position, t.position", null)) {
                while (rows.moveToNext()) {
                    String axisId = rows.getString(0);
                    TagAxis axis = axes.computeIfAbsent(axisId,
                            id -> new TagAxis(id, rows.getString(1), rows.getInt(2) != 0));
                    axis.tags.add(new Recipe.Tag(axisId, rows.getString(3), rows.getString(4), rows.getString(5), null));
                }
            }
        }
        return new ArrayList<>(axes.values());
    }

    static CatalogPage discover(Context context, String title, Map<String, Set<String>> filters, Integer maxMinutes,
                                int offset, int limit) throws IOException {
        if (offset < 0 || limit < 1) {
            throw new IllegalArgumentException("Invalid catalog page.");
        }
        RecipeDiscovery query = new RecipeDiscovery(filters, maxMinutes);
        String normalizedTitle = key(title);
        try (SQLiteDatabase db = openCatalog(context)) {
            boolean taggedCatalog = hasTable(db, "recipe_tag");
            if (!taggedCatalog && !query.axes.isEmpty()) {
                return new CatalogPage(Collections.emptyList(), 0, false, Collections.emptyList(), Collections.emptyList());
            }
            Map<String, List<Match>> matches = searchMatches(db, query, normalizedTitle, false);
            boolean partial = !matches.isEmpty() && !matches.values().iterator().next().get(0).missingAxes().isEmpty();
            List<String> titleHints = normalizedTitle.isEmpty() || partial ? Collections.emptyList()
                    : matches.values().stream().limit(4).map(family -> family.get(0))
                            .map(Match::titleEn)
                            .collect(java.util.stream.Collectors.toList());
            List<Refinement> refinements = matches.isEmpty() || partial
                    ? refinements(db, normalizedTitle, filters, maxMinutes) : Collections.emptyList();

            // Choose the page after grouping so a family cannot span multiple pages.
            Map<String, Card> groups = new LinkedHashMap<>();
            String externalRows = externalRows(db);
            int index = 0;
            for (List<Match> family : matches.values()) {
                if (index++ < offset) {
                    continue;
                }
                if (groups.size() == limit) {
                    break;
                }
                for (Match match : family) {
                    CatalogRecipe entry = readRecipe(db, match.id(), match.imported(), externalRows);
                    if (entry == null) {
                        continue;
                    }
                    Recipe recipe = entry.recipe();
                    if (taggedCatalog) {
                        recipe.tags = readTags(db, match.id());
                        recipe.missingTagAxes = match.missingAxes();
                    }
                    addToCards(groups, entry);
                }
            }
            return new CatalogPage(new ArrayList<>(groups.values()), matches.size(), partial, titleHints, refinements);
        }
    }

    private static Map<String, List<Match>> searchMatches(SQLiteDatabase db, RecipeDiscovery query,
                                                         String title, boolean exactOnly) {
        List<Match> matches = new ArrayList<>();
        int best = exactOnly ? query.axes.size() : -1;
        try (Cursor rows = db.rawQuery(query.sql, query.arguments)) {
            while (rows.moveToNext()) {
                int hits = rows.getInt(3);
                if (hits < best) {
                    break;
                }
                String titleEn = rows.getString(6);
                RecipeDiscovery.TitleMatch titleMatch = RecipeDiscovery.titleMatch(title, rows.getString(5), titleEn);
                if (titleMatch == RecipeDiscovery.TitleMatch.NONE) {
                    continue;
                }
                best = hits;
                matches.add(new Match(rows.getString(0), rows.getString(1), rows.getInt(2) != 0, titleEn,
                        rows.getInt(7), query.missingAxes(rows.getString(4)), titleMatch));
            }
        }
        if (!title.isEmpty()) {
            // A stable sort preserves the cooking time and ID order within each title rank.
            matches.sort(Comparator.comparing(Match::titleMatch));
        }
        Map<String, List<Match>> groups = new LinkedHashMap<>();
        for (Match match : matches) {
            groups.computeIfAbsent(match.cardId(), ignored -> new ArrayList<>()).add(match);
        }
        return groups;
    }

    private static List<Refinement> refinements(SQLiteDatabase db, String title,
                                                Map<String, Set<String>> filters, Integer maxMinutes) {
        List<Refinement> options = new ArrayList<>();
        if (maxMinutes != null) {
            Map<String, List<Match>> withoutTime = searchMatches(db, new RecipeDiscovery(filters, null), title, true);
            if (!withoutTime.isEmpty()) {
                int shortest = withoutTime.values().stream().flatMap(List::stream).mapToInt(Match::minutes)
                        .filter(minutes -> minutes > 0).min().orElse(0);
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

    static Recipe recipe(Context context, String id) throws IOException {
        try (SQLiteDatabase db = openCatalog(context)) {
            return readRecipeById(db, id, externalRows(db), hasTable(db, "recipe_tag"));
        }
    }

    static List<Recipe> savedRecipes(Context context, Set<String> ids) throws IOException {
        List<Recipe> recipes = new ArrayList<>(ids.size());
        try (SQLiteDatabase db = openCatalog(context)) {
            String externalRows = externalRows(db);
            boolean tagged = hasTable(db, "recipe_tag");
            for (String id : ids) {
                Recipe recipe = readRecipeById(db, id, externalRows, tagged);
                if (recipe != null) {
                    recipes.add(recipe);
                }
            }
        }
        recipes.sort(Comparator.comparing((Recipe recipe) -> recipe.title, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(recipe -> recipe.id));
        return recipes;
    }

    private static Recipe readRecipeById(SQLiteDatabase db, String id, String externalRows, boolean tagged)
            throws IOException {
        CatalogRecipe entry = readRecipe(db, id, false, externalRows);
        if (entry == null) {
            entry = readRecipe(db, id, true, externalRows);
        }
        if (entry == null) {
            return null;
        }
        Recipe recipe = entry.recipe();
        if (tagged) {
            recipe.tags = readTags(db, id);
        }
        return recipe;
    }

    private static boolean hasTable(SQLiteDatabase db, String name) {
        try (Cursor rows = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", new String[] {name})) {
            return rows.moveToFirst();
        }
    }

    private static List<Recipe.Tag> readTags(SQLiteDatabase db, String id) {
        List<Recipe.Tag> tags = new ArrayList<>();
        try (Cursor rows = db.rawQuery("SELECT t.axis_id, t.id, t.name_en, r.note_en" +
                " FROM recipe_tag r JOIN tag t ON t.axis_id=r.axis_id AND t.id=r.tag_id" +
                " JOIN tag_axis a ON a.id=t.axis_id WHERE r.recipe_id=? ORDER BY a.position, t.position",
                new String[] {id})) {
            while (rows.moveToNext()) {
                tags.add(new Recipe.Tag(rows.getString(0), rows.getString(1), rows.getString(2), null, rows.getString(3)));
            }
        }
        return tags;
    }

    private static CatalogRecipe readRecipe(SQLiteDatabase db, String id, boolean imported, String externalRows)
            throws IOException {
        String rows = imported ? externalRows : SEED_ROWS;
        try (Cursor row = db.rawQuery(rows + " WHERE r.id=?", new String[] {id})) {
            if (!row.moveToFirst()) {
                return null;
            }
            return imported ? readExternal(row) : readSeed(db, row);
        }
    }

    private static CatalogRecipe readSeed(SQLiteDatabase db, Cursor row) {
        String id = row.getString(0);
        Recipe recipe = new Recipe(id, row.getString(3), row.getInt(4), row.getInt(5));
        readIngredients(db, id, recipe);
        readSteps(db, id, recipe);
        return new CatalogRecipe(recipe, "seed:" + row.getString(1), row.getString(2), null);
    }

    private static String externalRows(SQLiteDatabase db) {
        boolean hasNotes = hasTable(db, "offline_note");
        return "SELECT r.id, r.family_id, r.title_en, r.ingredients_en, r.steps_en," +
                " r.minutes, r.servings, r.revision_url, r.license_url, f.title_en, f.summary_en," +
                (hasNotes ? " n.text_en" : " NULL") +
                " FROM offline_recipe r LEFT JOIN offline_family f ON f.id=r.family_id" +
                (hasNotes ? " LEFT JOIN offline_note n ON n.recipe_id=r.id" : "");
    }

    private static CatalogRecipe readExternal(Cursor row) throws IOException {
        String title = row.getString(2);
        Recipe recipe = new Recipe(row.getString(0), title, row.getInt(5), row.getInt(6));
        recipe.revisionUrl = row.getString(7);
        recipe.licenseUrl = row.getString(8);
        recipe.notes = row.getString(11);
        readJsonLines(row.getString(3), recipe.sourceIngredients);
        readJsonLines(row.getString(4), recipe.steps);
        String family = row.getString(1);
        String cardId = family == null ? recipe.id : "offline:" + family;
        String cardTitle = family == null ? title : row.getString(9);
        return new CatalogRecipe(recipe, cardId, cardTitle, row.getString(10));
    }

    private static void addToCards(Map<String, Card> groups, CatalogRecipe entry) {
        Card card = groups.computeIfAbsent(entry.cardId(), ignored -> new Card(entry.cardTitle()));
        card.summary = entry.cardSummary();
        card.variants.add(entry.recipe());
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

    private static Pantry pantry(SQLiteDatabase db, String text, boolean includeBasics) {
        Map<String, String> names = new HashMap<>();
        Map<String, String> labels = new HashMap<>();
        Set<String> available = new HashSet<>();
        try (Cursor rows = db.rawQuery("SELECT id, name_ru, name_en, is_basic FROM ingredient", null)) {
            while (rows.moveToNext()) {
                String id = rows.getString(0);
                names.put(key(rows.getString(1)), id);
                names.put(key(rows.getString(2)), id);
                labels.put(id, rows.getString(2));
                if (includeBasics && rows.getInt(3) != 0) {
                    available.add(id);
                }
            }
        }
        try (Cursor rows = db.rawQuery(
                "SELECT alias, ingredient_id FROM ingredient_alias ORDER BY (language='en')", null)) {
            while (rows.moveToNext()) {
                names.put(key(rows.getString(0)), rows.getString(1));
            }
        }
        try (Cursor rows = db.rawQuery("""
                SELECT name, ingredient_key, language FROM offline_alias
                ORDER BY (language='en') DESC, length(name), name
                """, null)) {
            while (rows.moveToNext()) {
                String name = rows.getString(0);
                String id = rows.getString(1);
                names.putIfAbsent(key(name), id);
                if ("en".equals(rows.getString(2)) && !name.trim().isEmpty()) {
                    labels.putIfAbsent(id, name);
                }
            }
        }
        Set<String> entered = new HashSet<>();
        for (String item : text.split("[,;\\r\\n]+")) {
            String id = names.get(key(item));
            if (id != null) {
                entered.add(id);
            }
        }
        available.addAll(entered);
        return new Pantry(available, entered, labels);
    }

    private static void readIngredients(SQLiteDatabase db, String id, Recipe recipe) {
        try (Cursor rows = db.rawQuery(
                "SELECT i.name_en, ri.amount, u.id, u.label_en" +
                " FROM recipe_ingredient ri JOIN ingredient i ON i.id=ri.ingredient_id" +
                " JOIN unit u ON u.id=ri.unit_id WHERE ri.recipe_id=? ORDER BY ri.position",
                new String[] {id})) {
            while (rows.moveToNext()) {
                recipe.ingredients.add(new Recipe.Ingredient(rows.getString(0), rows.getDouble(1),
                        rows.getString(2), rows.getString(3)));
            }
        }
    }

    private static void readSteps(SQLiteDatabase db, String id, Recipe recipe) {
        try (Cursor steps = db.rawQuery(
                "SELECT text_en FROM recipe_step WHERE recipe_id=? ORDER BY position",
                new String[] {id})) {
            while (steps.moveToNext()) {
                recipe.steps.add(steps.getString(0));
            }
        }
    }
}
