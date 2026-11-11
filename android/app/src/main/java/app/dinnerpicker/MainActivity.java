package app.dinnerpicker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

public final class MainActivity extends Activity {
    private static final int PAPER = Color.rgb(248, 244, 233);
    private static final int INK = Color.rgb(39, 44, 34);
    private static final int GREEN = Color.rgb(54, 83, 59);
    private static final int PAGE_SIZE = 24;

    private enum Screen { HOME, DISCOVERY, PANTRY, CATALOG }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences preferences;
    private boolean russian;
    private Screen screen = Screen.HOME;
    private boolean pantryOpen;
    private String catalogQuery = "";
    private int pageOffset;
    private Map<String, Set<String>> selectedTags = new LinkedHashMap<>();
    private Integer discoveryTime;
    private List<RecipeCatalog.TagAxis> tagAxes = Collections.emptyList();
    private int requestNumber;
    private ScrollView scroll;
    private TextView selection;
    private Button filters;
    private Button clear;
    private EditText pantry;
    private EditText minutes;
    private EditText servings;
    private CheckBox basics;
    private TextView status;
    private LinearLayout results;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("settings", MODE_PRIVATE);
        russian = preferences.getBoolean("russian", Locale.getDefault().getLanguage().equals("ru"));
        if (state != null) {
            screen = Screen.valueOf(state.getString("screen", Screen.HOME.name()));
            pantryOpen = state.getBoolean("pantryOpen");
            catalogQuery = state.getString("catalogQuery", "");
            pageOffset = state.getInt("pageOffset");
            discoveryTime = state.containsKey("discoveryTime") ? state.getInt("discoveryTime") : null;
            Bundle savedTags = state.getBundle("tags");
            if (savedTags != null) {
                for (String axis : savedTags.keySet()) {
                    selectedTags.put(axis, new LinkedHashSet<>(savedTags.getStringArrayList(axis)));
                }
            }
        }
        render(state == null ? "" : state.getString("pantry", ""),
                state == null ? "20" : state.getString("minutes", "20"),
                state == null ? "2" : state.getString("servings", "2"),
                state == null || state.getBoolean("basics", true));
        restoreResults();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("pantry", value(pantry));
        state.putString("minutes", value(minutes));
        state.putString("servings", value(servings));
        state.putBoolean("basics", basics.isChecked());
        state.putString("screen", screen.name());
        state.putBoolean("pantryOpen", pantryOpen);
        state.putString("catalogQuery", catalogQuery);
        state.putInt("pageOffset", pageOffset);
        if (discoveryTime != null) {
            state.putInt("discoveryTime", discoveryTime);
        }
        Bundle savedTags = new Bundle();
        selectedTags.forEach((axis, tags) -> savedTags.putStringArrayList(axis, new ArrayList<>(tags)));
        state.putBundle("tags", savedTags);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private String t(String ru, String en) {
        return russian ? ru : en;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private GradientDrawable surface(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private TextView text(String content, int size, boolean bold) {
        TextView label = new TextView(this);
        label.setText(content);
        label.setTextColor(INK);
        label.setTextSize(size);
        if (bold) {
            label.setTypeface(null, Typeface.BOLD);
        }
        label.setPadding(0, dp(4), 0, dp(4));
        return label;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        if (primary) {
            button.setTextColor(Color.WHITE);
            button.setBackground(surface(GREEN, 12));
            button.setPadding(dp(16), dp(10), dp(16), dp(10));
        } else {
            button.setTextColor(GREEN);
        }
        return button;
    }

    private EditText field(String content, int inputType) {
        EditText field = new EditText(this);
        field.setTextColor(INK);
        field.setTextSize(16);
        field.setInputType(inputType);
        field.setText(content);
        return field;
    }

    private void render(String products, String time, String portions, boolean includeBasics) {
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAPER);
        LinearLayout page = column();
        page.setPadding(dp(20), dp(18), dp(20), dp(32));
        if (Build.VERSION.SDK_INT >= 35) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        scroll.addView(page);
        setContentView(scroll);
        page.setFocusableInTouchMode(true);
        page.requestFocus();

        Button language = button(t("English", "Русский"), false);
        language.setOnClickListener(view -> {
            russian = !russian;
            preferences.edit().putBoolean("russian", russian).apply();
            requestNumber++;
            render(value(pantry), value(minutes), value(servings), basics.isChecked());
            restoreResults();
        });
        page.addView(language);
        page.addView(text(t("Что на ужин?", "What’s for dinner?"), 34, true));
        page.addView(text(t("Выбери, чего хочется сегодня.", "Find something you feel like cooking."), 16, false));
        addDiscoveryControls(page);
        addPantryControls(page, products, time, portions, includeBasics);
        Button catalog = button(t("Поиск по названию", "Search by name"), false);
        catalog.setOnClickListener(view -> askForTitle());
        page.addView(catalog);
        status = text("", 15, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        page.addView(status);
        results = column();
        page.addView(results);
    }

    private void restoreResults() {
        switch (screen) {
            case DISCOVERY -> discover(pageOffset, false);
            case PANTRY -> search();
            case CATALOG -> browse(catalogQuery, pageOffset);
            case HOME -> { }
        }
    }

    private void addDiscoveryControls(LinearLayout page) {
        page.addView(text(t("Идеи на сегодня", "Ideas for today"), 19, true));
        addChoices(page, Arrays.asList(
                preset(t("Уютный ужин", "Cozy dinner"), null, "mood", "cozy", "meal", "dinner"),
                preset(t("Простой завтрак", "Easy breakfast"), null, "meal", "breakfast", "difficulty", "easy"),
                preset(t("Выпечка к чаю", "Baking for tea"), null, "dish_type", "baking", "occasion", "tea"),
                preset(t("Быстрый обед", "Quick lunch"), 20, "meal", "lunch"),
                preset(t("Для гостей", "For guests"), null, "occasion", "guests"),
                preset(t("Новый вкус", "Something new"), null, "novelty", "discovery")));

        selection = text("", 15, false);
        page.addView(selection);
        filters = button(t("Выбрать теги", "Choose tags"), true);
        filters.setEnabled(false);
        filters.setOnClickListener(view -> editFilters());
        page.addView(filters);
        clear = button(t("Сбросить фильтры", "Clear filters"), false);
        clear.setOnClickListener(view -> applyFilters(Collections.emptyMap(), null));
        page.addView(clear);
        tagAxes = Collections.emptyList();
        updateSelection();

        Button filterButton = filters;
        boolean language = russian;
        worker.execute(() -> {
            try {
                List<RecipeCatalog.TagAxis> axes = RecipeCatalog.discoveryAxes(getApplicationContext(), language);
                runOnUiThread(() -> {
                    if (!isDestroyed() && filters == filterButton) {
                        tagAxes = axes;
                        filters.setEnabled(true);
                        updateSelection();
                    }
                });
            } catch (IOException | android.database.SQLException error) {
                runOnUiThread(() -> {
                    if (!isDestroyed() && filters == filterButton) {
                        selection.setText(t("Не удалось загрузить теги.", "Could not load tags."));
                        selection.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private void addChoices(LinearLayout parent, List<? extends View> choices) {
        boolean wide = getResources().getConfiguration().screenWidthDp >= 350
                && getResources().getConfiguration().fontScale <= 1.2f;
        int columns = wide ? 2 : 1;
        for (int start = 0; start < choices.size(); start += columns) {
            LinearLayout row = new LinearLayout(this);
            for (int column = 0; column < columns; column++) {
                int index = start + column;
                View choice = index < choices.size() ? choices.get(index) : new View(this);
                LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(0, -2, 1);
                spacing.setMargins(dp(2), 0, dp(2), 0);
                row.addView(choice, spacing);
            }
            parent.addView(row);
        }
    }

    private Button preset(String label, Integer time, String... tags) {
        Button idea = button(label, false);
        idea.setOnClickListener(view -> {
            Map<String, Set<String>> picks = new LinkedHashMap<>();
            for (int i = 0; i < tags.length; i += 2) {
                picks.put(tags[i], Collections.singleton(tags[i + 1]));
            }
            applyFilters(picks, time);
        });
        return idea;
    }

    private void updateSelection() {
        String summary = filterSummary();
        selection.setText(summary);
        selection.setVisibility(summary.isEmpty() ? View.GONE : View.VISIBLE);
        clear.setVisibility(selectedTags.isEmpty() && discoveryTime == null ? View.GONE : View.VISIBLE);
    }

    private String filterSummary() {
        List<String> labels = new ArrayList<>();
        for (RecipeCatalog.TagAxis axis : tagAxes) {
            Set<String> chosen = selectedTags.getOrDefault(axis.id, Collections.emptySet());
            for (RecipeCatalog.Tag tag : axis.tags) {
                if (chosen.contains(tag.id)) {
                    labels.add(tag.label);
                }
            }
        }
        if (discoveryTime != null) {
            labels.add(t("До ", "Up to ") + discoveryTime + t(" мин", " min"));
        }
        return String.join(" · ", labels);
    }

    private void editFilters() {
        Map<String, Set<String>> draft = new LinkedHashMap<>();
        selectedTags.forEach((axis, tags) -> draft.put(axis, new LinkedHashSet<>(tags)));
        ScrollView window = new ScrollView(this);
        LinearLayout form = column();
        form.setPadding(dp(20), dp(4), dp(20), dp(12));
        form.setFocusableInTouchMode(true);
        window.addView(form);

        form.addView(text(t("Максимум минут", "Maximum minutes"), 16, true));
        EditText time = field(discoveryTime == null ? "" : discoveryTime.toString(), InputType.TYPE_CLASS_NUMBER);
        time.setSingleLine(true);
        time.setHint(t("Без ограничения", "No limit"));
        time.setContentDescription(t("Максимум минут для подбора", "Maximum minutes for discovery"));
        form.addView(time);
        form.addView(text(t("Можно пропустить любую категорию.", "You can skip any category."), 15, false));
        for (RecipeCatalog.TagAxis axis : tagAxes) {
            form.addView(text(axis.label, 17, true));
            Set<String> chosen = draft.computeIfAbsent(axis.id, id -> new LinkedHashSet<>());
            List<CheckBox> choices = new ArrayList<>();
            for (RecipeCatalog.Tag tag : axis.tags) {
                CheckBox check = new CheckBox(this);
                check.setText(tag.label);
                check.setTextColor(INK);
                check.setMinHeight(dp(48));
                check.setChecked(chosen.contains(tag.id));
                check.setOnClickListener(view -> {
                    if (!check.isChecked()) {
                        chosen.remove(tag.id);
                        return;
                    }
                    if (!axis.multiple) {
                        chosen.clear();
                        for (CheckBox other : choices) {
                            if (other != check) {
                                other.setChecked(false);
                            }
                        }
                    }
                    chosen.add(tag.id);
                });
                choices.add(check);
            }
            addChoices(form, choices);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(t("Подбор по тегам", "Find by tags"))
                .setView(window)
                .setPositiveButton(t("Показать блюда", "Show recipes"), null)
                .setNegativeButton(t("Отмена", "Cancel"), null)
                .create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
        form.requestFocus();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            Integer limit = null;
            String entered = value(time);
            if (!entered.isEmpty()) {
                try {
                    limit = Integer.valueOf(entered);
                    if (limit < 1) {
                        throw new NumberFormatException();
                    }
                } catch (NumberFormatException error) {
                    time.setError(t("Укажи положительное число минут.", "Enter a positive number of minutes."));
                    time.requestFocus();
                    return;
                }
            }
            draft.values().removeIf(Set::isEmpty);
            dialog.dismiss();
            applyFilters(draft, limit);
        });
    }

    private void applyFilters(Map<String, Set<String>> tags, Integer time) {
        selectedTags = tags;
        discoveryTime = time;
        updateSelection();
        discover(0, true);
    }

    private void discover(int offset, boolean jumpToResults) {
        int id = ++requestNumber;
        screen = Screen.DISCOVERY;
        pageOffset = offset;
        results.removeAllViews();
        status.setText(t("Ищем подходящие блюда…", "Finding matching recipes…"));
        Map<String, Set<String>> tags = selectedTags;
        Integer time = discoveryTime;
        boolean language = russian;
        worker.execute(() -> {
            try {
                RecipeCatalog.CatalogPage page = RecipeCatalog.discover(
                        getApplicationContext(), tags, time, offset, PAGE_SIZE, language);
                runOnUiThread(() -> {
                    if (isDestroyed() || id != requestNumber) {
                        return;
                    }
                    String heading = page.total == 0
                            ? t("Подходящих блюд нет. Измени теги или лимит времени.",
                                "No matching recipes. Change the tags or time limit.")
                            : (page.partial
                                ? t("Точных совпадений нет. Близкие варианты · ", "No exact matches. Similar recipes · ")
                                : t("Найдено блюд · ", "Recipes found · ")) + page.total;
                    String summary = filterSummary();
                    status.setText(summary.isEmpty() ? heading : heading + "\n" + summary);
                    if (filters.isEnabled()) {
                        Button edit = button(t("Изменить фильтры", "Edit filters"), false);
                        edit.setOnClickListener(view -> editFilters());
                        results.addView(edit);
                    }
                    if (page.total > 0) {
                        showCards(page.cards, 0);
                        paginate(offset, page.total, next -> discover(next, true));
                    }
                    if (jumpToResults) {
                        scroll.post(() -> {
                            if (!isDestroyed() && id == requestNumber) {
                                scroll.smoothScrollTo(0, status.getTop());
                            }
                        });
                    }
                });
            } catch (IOException | android.database.SQLException error) {
                reportCatalogError(id);
            }
        });
    }

    private void addPantryControls(LinearLayout page, String products, String time,
                                   String portions, boolean includeBasics) {
        LinearLayout panel = column();
        panel.setVisibility(pantryOpen ? View.VISIBLE : View.GONE);
        Button open = button(t("Подбор по продуктам", "Search by ingredients"), false);
        open.setOnClickListener(view -> {
            pantryOpen = !pantryOpen;
            panel.setVisibility(pantryOpen ? View.VISIBLE : View.GONE);
        });
        page.addView(open);
        page.addView(panel);
        pantry = field(products, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        pantry.setMinLines(2);
        pantry.setHint(t("Яйца, помидоры, сыр…", "Eggs, tomatoes, cheese…"));
        pantry.setContentDescription(t("Доступные продукты", "Available ingredients"));
        panel.addView(pantry);
        Button demo = button(t("Заполнить пример", "Use an example"), false);
        demo.setOnClickListener(view -> {
            pantry.setText(t("яйца, помидоры, сыр, макароны, чеснок, хлеб, огурцы",
                    "eggs, tomatoes, cheese, pasta, garlic, bread, cucumbers"));
            minutes.setText("20");
            servings.setText("2");
            basics.setChecked(true);
        });
        panel.addView(demo);

        LinearLayout fields = new LinearLayout(this);
        LinearLayout timeColumn = column();
        LinearLayout servingsColumn = column();
        timeColumn.setPadding(0, 0, dp(10), 0);
        fields.addView(timeColumn, new LinearLayout.LayoutParams(0, -2, 1));
        fields.addView(servingsColumn, new LinearLayout.LayoutParams(0, -2, 1));
        minutes = field(time, InputType.TYPE_CLASS_NUMBER);
        minutes.setContentDescription(t("Максимум минут", "Maximum minutes"));
        servings = field(portions, InputType.TYPE_CLASS_NUMBER);
        servings.setContentDescription(t("Число порций", "Number of servings"));
        timeColumn.addView(text(t("До, мин", "Up to, min"), 15, true));
        timeColumn.addView(minutes);
        servingsColumn.addView(text(t("Порций", "Servings"), 15, true));
        servingsColumn.addView(servings);
        panel.addView(fields);

        basics = new CheckBox(this);
        basics.setText(t("Есть вода, соль и растительное масло", "Include water, salt and vegetable oil"));
        basics.setChecked(includeBasics);
        panel.addView(basics);
        Button submit = button(t("Подобрать ужин", "Find dinner"), true);
        submit.setOnClickListener(view -> search());
        panel.addView(submit);
    }

    private static String value(EditText field) {
        return field.getText().toString().trim();
    }

    private void search() {
        int id = ++requestNumber;
        screen = Screen.PANTRY;
        pageOffset = 0;
        results.removeAllViews();
        String ingredients = value(pantry);
        if (ingredients.replaceAll("[,;\\s]+", "").isEmpty()) {
            status.setText(t("Добавь хотя бы один продукт.", "Enter at least one ingredient."));
            return;
        }
        int limit;
        int people;
        try {
            limit = Integer.parseInt(value(minutes));
            people = Integer.parseInt(value(servings));
            if (limit < 5 || limit > 120 || people < 1 || people > 6) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException error) {
            status.setText(t("Укажи 5–120 минут и 1–6 порций.", "Enter 5–120 minutes and 1–6 servings."));
            return;
        }
        status.setText(t("Ищем рецепты…", "Finding recipes…"));
        boolean includeBasics = basics.isChecked();
        boolean language = russian;
        worker.execute(() -> {
            try {
                List<RecipeCatalog.Card> cards = RecipeCatalog.find(
                        getApplicationContext(), ingredients, limit, includeBasics, language);
                runOnUiThread(() -> {
                    if (!isDestroyed() && id == requestNumber) {
                        showResults(cards, people);
                    }
                });
            } catch (IOException | android.database.SQLException error) {
                reportCatalogError(id);
            }
        });
    }

    private void askForTitle() {
        EditText title = field(catalogQuery, InputType.TYPE_CLASS_TEXT);
        title.setSingleLine(true);
        title.setHint(t("Название или часть названия", "Recipe name or part of it"));
        LinearLayout layout = column();
        layout.setPadding(dp(20), 0, dp(20), 0);
        layout.addView(title);
        new AlertDialog.Builder(this)
                .setTitle(t("Каталог рецептов", "Recipe catalog"))
                .setView(layout)
                .setPositiveButton(t("Найти", "Search"), (dialog, which) -> browse(value(title), 0))
                .setNeutralButton(t("Все рецепты", "All recipes"), (dialog, which) -> browse("", 0))
                .setNegativeButton(t("Закрыть", "Close"), null)
                .show();
    }

    private void browse(String query, int offset) {
        int id = ++requestNumber;
        screen = Screen.CATALOG;
        catalogQuery = query;
        pageOffset = offset;
        results.removeAllViews();
        status.setText(t("Открываем каталог…", "Opening the catalog…"));
        boolean language = russian;
        worker.execute(() -> {
            try {
                RecipeCatalog.CatalogPage page = RecipeCatalog.browse(
                        getApplicationContext(), query, offset, PAGE_SIZE, language);
                runOnUiThread(() -> {
                    if (isDestroyed() || id != requestNumber) {
                        return;
                    }
                    if (page.total == 0) {
                        status.setText(t("По названию ничего не найдено.", "No recipes match that title."));
                        return;
                    }
                    status.setText(t("Каталог · найдено: ", "Catalog · matches: ") + page.total);
                    showCards(page.cards, 0);
                    paginate(offset, page.total, next -> browse(query, next));
                });
            } catch (IOException | android.database.SQLException error) {
                reportCatalogError(id);
            }
        });
    }

    private void paginate(int offset, int total, IntConsumer open) {
        if (offset > 0) {
            Button previous = button(t("Предыдущая страница", "Previous page"), false);
            previous.setOnClickListener(view -> open.accept(Math.max(0, offset - PAGE_SIZE)));
            results.addView(previous);
        }
        if (offset + PAGE_SIZE < total) {
            Button next = button(t("Следующая страница", "Next page"), false);
            next.setOnClickListener(view -> open.accept(offset + PAGE_SIZE));
            results.addView(next);
        }
    }

    private void reportCatalogError(int id) {
        runOnUiThread(() -> {
            if (!isDestroyed() && id == requestNumber) {
                status.setText(t("Не удалось прочитать локальный каталог.", "Could not read the local catalog."));
            }
        });
    }

    private void showResults(List<RecipeCatalog.Card> cards, int people) {
        if (cards.isEmpty()) {
            status.setText(t("Подходящих блюд нет. Попробуй добавить продукты или увеличить время.",
                    "No matching recipes. Try adding ingredients or increasing the time."));
            return;
        }
        status.setText(t("Карточек: ", "Recipe cards: ") + cards.size());
        showCards(cards, people);
    }

    private void showCards(List<RecipeCatalog.Card> cards, int people) {
        for (RecipeCatalog.Card card : cards) {
            LinearLayout item = column();
            item.setBackground(surface(Color.rgb(255, 252, 244), 14));
            item.setPadding(dp(14), dp(10), dp(14), dp(10));
            LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2);
            spacing.bottomMargin = dp(10);
            results.addView(item, spacing);
            boolean discovery = screen == Screen.DISCOVERY;
            if (!discovery || card.variants.size() > 1) {
                item.addView(text(card.label(), 21, true));
            }
            if (card.variants.size() > 1) {
                item.addView(text(t("Вариантов: ", "Variants: ") + card.variants.size(), 14, false));
                if (card.summary != null && !card.summary.isEmpty()) {
                    item.addView(text(card.summary, 15, false));
                }
            }
            for (RecipeCatalog.Recipe recipe : card.variants) {
                String label = recipe.minutes > 0 ? recipe.title + " · " + recipe.minutes + t(" мин", " min")
                        : recipe.title;
                Button details = button(label, false);
                if (discovery && card.variants.size() == 1) {
                    details.setTextSize(18);
                }
                TextView body = text(recipeText(recipe, people), 15, false);
                body.setTextIsSelectable(true);
                body.setVisibility(View.GONE);
                details.setOnClickListener(view -> body.setVisibility(
                        body.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
                item.addView(details);
                if (discovery) {
                    addVariantTags(item, recipe);
                }
                item.addView(body);
            }
        }
    }

    private void addVariantTags(LinearLayout item, RecipeCatalog.Recipe recipe) {
        Set<String> labels = new LinkedHashSet<>();
        for (RecipeCatalog.Tag tag : recipe.tags) {
            if ("dish_type".equals(tag.axisId) || "difficulty".equals(tag.axisId)
                    || selectedTags.getOrDefault(tag.axisId, Collections.emptySet()).contains(tag.id)) {
                labels.add(tag.label);
            }
        }
        if (!labels.isEmpty()) {
            item.addView(text(String.join(" · ", labels), 14, false));
        }
        if (!recipe.missingTagAxes.isEmpty()) {
            List<String> missing = new ArrayList<>();
            for (RecipeCatalog.TagAxis axis : tagAxes) {
                if (recipe.missingTagAxes.contains(axis.id)) {
                    missing.add(axis.label);
                }
            }
            item.addView(text(t("Не совпало · ", "Did not match · ") + String.join(", ", missing), 14, true));
        }
    }

    private String recipeText(RecipeCatalog.Recipe recipe, int people) {
        boolean external = recipe.revisionUrl != null;
        int portionCount = people > 0 && !external ? people : recipe.baseServings;
        NumberFormat number = NumberFormat.getNumberInstance(Locale.forLanguageTag(t("ru", "en")));
        number.setMaximumFractionDigits(2);
        StringBuilder body = new StringBuilder();
        if (!recipe.tags.isEmpty()) {
            List<String> labels = new ArrayList<>();
            for (RecipeCatalog.Tag tag : recipe.tags) {
                if (tag.note == null) {
                    labels.add(tag.label);
                } else {
                    body.append(tag.label).append(" · ").append(tag.note).append('\n');
                }
            }
            if (!labels.isEmpty()) {
                body.append(String.join(" · ", labels)).append('\n');
            }
            body.append('\n');
        }
        if (external) {
            body.append(t("Количество по источнику", "Original quantities"));
            if (portionCount > 0) {
                body.append(t(" · порций: ", " · servings: ")).append(portionCount);
            }
            body.append('\n');
            for (String ingredient : recipe.sourceIngredients) {
                body.append("• ").append(ingredient).append('\n');
            }
        } else {
            body.append(t("Порций: ", "Servings: ")).append(portionCount).append('\n');
            for (RecipeCatalog.Ingredient ingredient : recipe.ingredients) {
                body.append("• ").append(ingredient.name).append(" · ")
                        .append(number.format(ingredient.amount * portionCount / recipe.baseServings))
                        .append(' ').append(ingredient.unit).append('\n');
            }
        }
        body.append('\n').append(t("Приготовление", "Method")).append('\n');
        for (int i = 0; i < recipe.steps.size(); i++) {
            body.append(i + 1).append(". ").append(recipe.steps.get(i)).append('\n');
        }
        if (external) {
            body.append('\n').append(t("Источник и авторы · Wikibooks", "Source and authors · Wikibooks"))
                    .append('\n').append(recipe.revisionUrl)
                    .append('\n').append(t("Адаптация и перевод · CC BY-SA 4.0", "Adaptation and translation · CC BY-SA 4.0"))
                    .append('\n').append(recipe.licenseUrl);
        }
        return body.toString().trim();
    }
}
