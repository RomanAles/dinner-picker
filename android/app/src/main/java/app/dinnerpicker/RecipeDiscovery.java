package app.dinnerpicker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RecipeDiscovery {
    private static final String CANDIDATES = """
            WITH candidates(id, card_id, minutes, imported) AS (
                SELECT id, 'seed:' || family_id, minutes, 0 FROM recipe
                UNION ALL
                SELECT id, COALESCE('offline:' || family_id, id), minutes, 1 FROM offline_recipe
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
            query.append("SELECT id, card_id, imported, 0 AS hits, NULL AS matched_axes FROM candidates c WHERE 1=1");
        } else {
            query.append(", wanted(axis_id, tag_id) AS (VALUES ")
                    .append(String.join(", ", Collections.nCopies(values.size() / 2, "(?, ?)")))
                    .append("""
                            ), matches AS (
                                SELECT r.recipe_id, COUNT(DISTINCT r.axis_id) AS hits,
                                       GROUP_CONCAT(DISTINCT r.axis_id) AS matched_axes
                                FROM wanted w JOIN recipe_tag r ON r.axis_id=w.axis_id AND r.tag_id=w.tag_id
                                GROUP BY r.recipe_id
                            ), ranked AS (
                                SELECT c.id, c.card_id, c.imported, m.hits, m.matched_axes, c.minutes
                                FROM candidates c JOIN matches m ON m.recipe_id=c.id
                                WHERE m.hits >= CAST(? AS INTEGER)
                            """);
            values.add(Integer.toString(Math.max(1, axes.size() - 1)));
        }
        if (maxMinutes != null) {
            query.append(" AND c.minutes > 0 AND c.minutes <= CAST(? AS INTEGER)");
            values.add(maxMinutes.toString());
        }
        if (!axes.isEmpty()) {
            query.append("""
                    ) SELECT id, card_id, imported, hits, matched_axes
                      FROM ranked c WHERE c.hits = (SELECT MAX(hits) FROM ranked)
                    """);
        }
        query.append(" ORDER BY hits DESC, CASE WHEN c.minutes > 0 THEN c.minutes ELSE 2147483647 END, c.id");
        sql = query.toString();
        arguments = values.toArray(new String[0]);
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
