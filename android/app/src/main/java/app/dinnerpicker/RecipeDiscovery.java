package app.dinnerpicker;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class RecipeDiscovery {
    enum TitleMatch { EXACT, PREFIX, SUBSTRING, NONE }

    private static final String CANDIDATES = """
            WITH candidates(id, card_id, minutes, imported, title_ru, title_en) AS (
                SELECT id, 'seed:' || family_id, minutes, 0, title_ru, title_en FROM recipe
                UNION ALL
                SELECT id, COALESCE('offline:' || family_id, id), minutes, 1, title_ru, title_en FROM offline_recipe
            )
            """;

    final List<String> axes = new ArrayList<>();
    final String sql;
    final String[] arguments;

    RecipeDiscovery(Map<String, Set<String>> filters, Integer maxMinutes) {
        if (maxMinutes != null && maxMinutes < 1) {
            throw new IllegalArgumentException("Maximum cooking time must be positive.");
        }
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, Set<String>> filter : filters.entrySet()) {
            if (!filter.getValue().isEmpty()) {
                axes.add(filter.getKey());
                for (String tag : filter.getValue()) {
                    values.add(filter.getKey());
                    values.add(tag);
                }
            }
        }
        StringBuilder query = new StringBuilder(CANDIDATES);
        if (axes.isEmpty()) {
            query.append("SELECT id, card_id, imported, 0 AS hits, NULL, title_ru, title_en, minutes"
                    + " FROM candidates c WHERE 1=1");
        } else {
            query.append(", wanted(axis_id, tag_id) AS (VALUES ")
                    .append(String.join(", ", Collections.nCopies(values.size() / 2, "(?, ?)")))
                    .append("""
                            ), matches AS (
                                SELECT r.recipe_id, COUNT(DISTINCT r.axis_id) AS hits,
                                       GROUP_CONCAT(DISTINCT r.axis_id) AS matched_axes
                                FROM wanted w JOIN recipe_tag r ON r.axis_id=w.axis_id AND r.tag_id=w.tag_id
                                GROUP BY r.recipe_id
                            )
                            SELECT c.id, c.card_id, c.imported, m.hits, m.matched_axes,
                                   c.title_ru, c.title_en, c.minutes
                            FROM candidates c JOIN matches m ON m.recipe_id=c.id
                            WHERE m.hits >= CAST(? AS INTEGER)
                            """);
            values.add(Integer.toString(Math.max(1, axes.size() - 1)));
        }
        if (maxMinutes != null) {
            query.append(" AND c.minutes > 0 AND c.minutes <= CAST(? AS INTEGER)");
            values.add(maxMinutes.toString());
        }
        query.append(" ORDER BY hits DESC, CASE WHEN c.minutes > 0 THEN c.minutes ELSE 2147483647 END, c.id");
        sql = query.toString();
        arguments = values.toArray(new String[0]);
    }

    static String key(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT)
                .replace('ё', 'е').trim().replaceAll("[\\s\\p{Z}]+", " ");
    }

    static boolean matchesTitle(String normalizedQuery, String titleRu, String titleEn) {
        return titleMatch(normalizedQuery, titleRu, titleEn) != TitleMatch.NONE;
    }

    static TitleMatch titleMatch(String normalizedQuery, String titleRu, String titleEn) {
        if (normalizedQuery.isEmpty()) {
            return TitleMatch.EXACT;
        }
        TitleMatch russian = titleMatch(normalizedQuery, key(titleRu));
        TitleMatch english = titleMatch(normalizedQuery, key(titleEn));
        return russian.compareTo(english) <= 0 ? russian : english;
    }

    private static TitleMatch titleMatch(String query, String title) {
        if (title.equals(query)) {
            return TitleMatch.EXACT;
        }
        if (title.startsWith(query)) {
            return TitleMatch.PREFIX;
        }
        return title.contains(query) ? TitleMatch.SUBSTRING : TitleMatch.NONE;
    }

    List<String> missingAxes(String matched) {
        if (axes.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> missing = new ArrayList<>(axes);
        missing.removeAll(Arrays.asList(matched.split(",")));
        return missing;
    }
}
