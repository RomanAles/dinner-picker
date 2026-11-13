package app.dinnerpicker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class Recipe {
    record Ingredient(String name, double amount, String unitId, String unitLabel) {}

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

    final String id;
    final String title;
    final int minutes;
    final int baseServings;
    final List<Ingredient> ingredients = new ArrayList<>();
    final List<String> steps = new ArrayList<>();
    final List<String> sourceIngredients = new ArrayList<>();
    String revisionUrl;
    String licenseUrl;
    String notes;
    List<Tag> tags = Collections.emptyList();
    List<String> missingTagAxes = Collections.emptyList();

    Recipe(String id, String title, int minutes, int baseServings) {
        this.id = id;
        this.title = title;
        this.minutes = minutes;
        this.baseServings = baseServings;
    }

    boolean isImported() {
        return revisionUrl != null;
    }

    int servings(int requested) {
        return requested > 0 && !isImported() ? requested : baseServings;
    }

    List<String> ingredientLines(int requested, RecipeUnits.System system) {
        List<String> lines = new ArrayList<>();
        if (isImported()) {
            for (String line : sourceIngredients) {
                lines.add(RecipeUnits.formatText(line, system));
            }
        } else {
            double scale = (double) servings(requested) / baseServings;
            for (Ingredient ingredient : ingredients) {
                lines.add(ingredient.name() + " · " + RecipeUnits.formatAmount(
                        ingredient.amount() * scale, ingredient.unitId(), ingredient.unitLabel(), system));
            }
        }
        return lines;
    }
}
