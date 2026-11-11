package app.dinnerpicker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
    private static final String MEASUREMENT_PREFERENCE = "measurement_system";

    private enum Screen { HOME, DISCOVERY, PANTRY }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences preferences;
    private RecipeUnits.System measurementSystem;
    private Button mainUnits;
    private Button detailUnits;
    private Screen screen = Screen.HOME;
    private boolean pantrySearched;
    private String discoveryQuery = "";
    private int pageOffset;
    private Map<String, Set<String>> selectedTags = new LinkedHashMap<>();
    private Integer discoveryTime;
    private List<RecipeCatalog.TagAxis> tagAxes = Collections.emptyList();
    private int requestNumber;
    private FrameLayout root;
    private LinearLayout mainScreen;
    private LinearLayout discoveryControls;
    private LinearLayout home;
    private LinearLayout pantryPanel;
    private ScrollView scroll;
    private EditText queryField;
    private Button clearQuery;
    private HorizontalScrollView hintStrip;
    private HorizontalScrollView selectedStrip;
    private LinearLayout hintRow;
    private LinearLayout selectedRow;
    private List<String> titleHints = Collections.emptyList();
    private boolean editingQuery;
    private final Runnable submitQuery = () -> discover(0, 0);
    private View detailScreen;
    private String openRecipeId;
    private int openRecipeServings;
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
        measurementSystem = RecipeUnits.System.IMPERIAL.name().equals(preferences.getString(MEASUREMENT_PREFERENCE, ""))
                ? RecipeUnits.System.IMPERIAL : RecipeUnits.System.METRIC;
        if (state != null) {
            String savedScreen = state.getString("screen", Screen.HOME.name());
            screen = "CATALOG".equals(savedScreen) ? Screen.DISCOVERY : Screen.valueOf(savedScreen);
            pantrySearched = state.getBoolean("pantrySearched", screen == Screen.PANTRY);
            discoveryQuery = state.getString("discoveryQuery", state.getString("catalogQuery", ""));
            pageOffset = state.getInt("pageOffset");
            openRecipeId = state.getString("openRecipeId");
            openRecipeServings = state.getInt("openRecipeServings");
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
        restoreResults(state == null ? 0 : state.getInt("scrollY"));
        restoreRecipe();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("pantry", value(pantry));
        state.putString("minutes", value(minutes));
        state.putString("servings", value(servings));
        state.putBoolean("basics", basics.isChecked());
        state.putString("screen", screen.name());
        state.putBoolean("pantrySearched", pantrySearched);
        state.putString("discoveryQuery", discoveryQuery);
        state.putInt("pageOffset", pageOffset);
        state.putInt("scrollY", scroll.getScrollY());
        state.putString("openRecipeId", openRecipeId);
        state.putInt("openRecipeServings", openRecipeServings);
        if (discoveryTime != null) {
            state.putInt("discoveryTime", discoveryTime);
        }
        Bundle savedTags = new Bundle();
        selectedTags.forEach((axis, tags) -> savedTags.putStringArrayList(axis, new ArrayList<>(tags)));
        state.putBundle("tags", savedTags);
    }

    @Override
    protected void onDestroy() {
        queryField.removeCallbacks(submitQuery);
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (openRecipeId != null) {
            closeRecipe();
        } else if (screen != Screen.HOME) {
            openHome();
        } else {
            super.onBackPressed();
        }
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
        root = new FrameLayout(this);
        root.setBackgroundColor(PAPER);
        mainScreen = column();
        root.addView(mainScreen, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        mainScreen.setFocusableInTouchMode(true);
        mainScreen.requestFocus();

        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), 0, dp(8), 0);
        header.addView(text("What’s for dinner?", 23, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(chip("Ideas", false, this::openHome));
        mainUnits = unitsChip(null);
        header.addView(mainUnits);
        mainScreen.addView(header);
        addSearchControls(mainScreen);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = column();
        page.setPadding(dp(14), dp(8), dp(14), dp(24));
        scroll.addView(page);
        mainScreen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        home = column();
        page.addView(home);
        addIdeas(home);
        pantryPanel = column();
        page.addView(pantryPanel);
        addPantryControls(pantryPanel, products, time, portions, includeBasics);
        status = text("", 16, true);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        page.addView(status);
        results = column();
        page.addView(results);
        updateScreen();
    }

    private void restoreResults(int position) {
        switch (screen) {
            case DISCOVERY -> discover(pageOffset, position);
            case PANTRY -> {
                if (pantrySearched) {
                    search(position);
                }
            }
            case HOME -> { }
        }
    }

    private void addIdeas(LinearLayout page) {
        page.addView(text("Ideas for today", 19, true));
        addChoices(page, Arrays.asList(
                preset("Cozy dinner", null, "mood", "cozy", "meal", "dinner"),
                preset("Easy breakfast", null, "meal", "breakfast", "difficulty", "easy"),
                preset("Baking for tea", null, "dish_type", "baking", "occasion", "tea"),
                preset("Quick lunch", 20, "meal", "lunch"),
                preset("For guests", null, "occasion", "guests"),
                preset("Something new", null, "novelty", "discovery")));

        Button all = button("Browse all recipes", true);
        all.setOnClickListener(view -> {
            setQuery("");
            applyFilters(Collections.emptyMap(), null);
        });
        page.addView(all);
        Button byIngredients = button("Search by ingredients", false);
        byIngredients.setOnClickListener(view -> {
            openScreen(Screen.PANTRY);
            restoreResults(0);
        });
        page.addView(byIngredients);
    }

    private void addSearchControls(LinearLayout parent) {
        LinearLayout controls = column();
        discoveryControls = controls;
        controls.setPadding(dp(12), 0, dp(12), dp(4));
        parent.addView(controls);
        LinearLayout searchBar = new LinearLayout(this);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        queryField = field(discoveryQuery, InputType.TYPE_CLASS_TEXT);
        queryField.setSingleLine(true);
        queryField.setHint("Recipe name or tag");
        queryField.setContentDescription("Search recipes and tags");
        queryField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchBar.addView(queryField, new LinearLayout.LayoutParams(0, -2, 1));
        clearQuery = chip("×", false, () -> {
            setQuery("");
            dismissKeyboard();
            discover(0, 0);
        });
        clearQuery.setContentDescription("Clear search text");
        searchBar.addView(clearQuery);
        Button allFilters = chip("All filters", false, this::editFilters);
        searchBar.addView(allFilters);
        controls.addView(searchBar);

        hintRow = new LinearLayout(this);
        hintStrip = strip(controls, hintRow);
        LinearLayout shortcuts = new LinearLayout(this);
        strip(controls, shortcuts);
        List<Button> categoryButtons = new ArrayList<>();
        categoryButtons.add(addChip(shortcuts, "Dish type", false, () -> chooseCategory("dish_type")));
        categoryButtons.add(addChip(shortcuts, "Meal", false, () -> chooseCategory("meal")));
        categoryButtons.add(addChip(shortcuts, "Mood", false, () -> chooseCategory("mood")));
        addChip(shortcuts, "Time", false, this::chooseTime);
        categoryButtons.add(allFilters);
        categoryButtons.forEach(button -> button.setEnabled(false));
        selectedRow = new LinearLayout(this);
        selectedStrip = strip(controls, selectedRow);
        tagAxes = Collections.emptyList();
        titleHints = Collections.emptyList();
        updateSelection();

        queryField.setOnFocusChangeListener((view, focused) -> updateHints());
        queryField.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) {
                return false;
            }
            dismissKeyboard();
            discover(0, 0);
            return true;
        });
        queryField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (editingQuery) {
                    return;
                }
                discoveryQuery = text.toString();
                requestNumber++;
                titleHints = Collections.emptyList();
                updateHints();
                queryField.removeCallbacks(submitQuery);
                queryField.postDelayed(submitQuery, 180);
            }
            @Override public void afterTextChanged(Editable text) {}
        });

        EditText input = queryField;
        worker.execute(() -> {
            try {
                List<RecipeCatalog.TagAxis> axes = RecipeCatalog.discoveryAxes(getApplicationContext());
                runOnUiThread(() -> {
                    if (!isDestroyed() && queryField == input) {
                        tagAxes = axes;
                        categoryButtons.forEach(button -> button.setEnabled(!axes.isEmpty()));
                        updateSelection();
                    }
                });
            } catch (IOException | android.database.SQLException error) {
                runOnUiThread(() -> {
                    if (!isDestroyed() && queryField == input) {
                        status.setText("Could not load tags.");
                    }
                });
            }
        });
    }

    private HorizontalScrollView strip(LinearLayout parent, LinearLayout row) {
        HorizontalScrollView strip = new HorizontalScrollView(this);
        strip.setHorizontalScrollBarEnabled(false);
        strip.addView(row);
        parent.addView(strip);
        return strip;
    }

    private Button chip(String label, boolean selected, Runnable action) {
        Button chip = button(label, false);
        chip.setMinHeight(dp(48));
        chip.setMinimumHeight(dp(48));
        chip.setMinWidth(dp(40));
        chip.setMinimumWidth(dp(40));
        chip.setPadding(dp(9), 0, dp(9), 0);
        chip.setTextColor(selected ? Color.WHITE : GREEN);
        chip.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(40, 54, 83, 59)),
                surface(selected ? GREEN : Color.rgb(232, 235, 221), 16), null));
        chip.setOnClickListener(view -> action.run());
        return chip;
    }

    private Button addChip(LinearLayout row, String label, boolean selected, Runnable action) {
        Button chip = chip(label, selected, action);
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-2, -2);
        space.setMargins(0, dp(3), dp(4), 0);
        row.addView(chip, space);
        return chip;
    }

    private void setQuery(String query) {
        editingQuery = true;
        discoveryQuery = query;
        queryField.setText(query);
        queryField.setSelection(query.length());
        editingQuery = false;
        titleHints = Collections.emptyList();
        updateHints();
    }

    private void updateHints() {
        clearQuery.setVisibility(discoveryQuery.isEmpty() ? View.GONE : View.VISIBLE);
        hintRow.removeAllViews();
        String query = RecipeCatalog.key(discoveryQuery);
        if (queryField.hasFocus() && !query.isEmpty()) {
            int found = 0;
            for (RecipeCatalog.TagAxis axis : tagAxes) {
                for (Recipe.Tag tag : axis.tags) {
                    if (found < 3 && !isSelected(axis.id, tag.id)
                            && RecipeDiscovery.matchesTitle(query, tag.label, tag.alternateLabel)) {
                        addChip(hintRow, "Tag · " + tag.label, false, () -> {
                            setQuery("");
                            changeTag(axis, tag.id, true);
                        });
                        found++;
                    }
                }
            }
            for (String title : new LinkedHashSet<>(titleHints)) {
                addChip(hintRow, "Recipe · " + title, false, () -> {
                    setQuery(title);
                    dismissKeyboard();
                    discover(0, 0);
                });
            }
        }
        hintStrip.setVisibility(hintRow.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private void dismissKeyboard() {
        View focused = getCurrentFocus();
        if (focused != null) {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
        mainScreen.requestFocus();
    }

    private void openHome() {
        openScreen(Screen.HOME);
    }

    private void openScreen(Screen destination) {
        requestNumber++;
        queryField.removeCallbacks(submitQuery);
        dismissKeyboard();
        screen = destination;
        status.setText("");
        results.removeAllViews();
        scroll.scrollTo(0, 0);
        updateScreen();
    }

    private void updateScreen() {
        home.setVisibility(screen == Screen.HOME ? View.VISIBLE : View.GONE);
        pantryPanel.setVisibility(screen == Screen.PANTRY ? View.VISIBLE : View.GONE);
        discoveryControls.setVisibility(screen == Screen.PANTRY ? View.GONE : View.VISIBLE);
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
            setQuery("");
            applyFilters(picks, time);
        });
        return idea;
    }

    private void updateSelection() {
        selectedRow.removeAllViews();
        for (RecipeCatalog.TagAxis axis : tagAxes) {
            for (Recipe.Tag tag : axis.tags) {
                if (isSelected(axis.id, tag.id)) {
                    addRemovalChip(tag.label, () -> changeTag(axis, tag.id, false));
                }
            }
        }
        if (discoveryTime != null) {
            addRemovalChip(timeLabel(discoveryTime), () -> applyFilters(selectedTags, null));
        }
        if (selectedRow.getChildCount() > 0) {
            addChip(selectedRow, "Clear filters", false,
                    () -> applyFilters(Collections.emptyMap(), null));
        }
        selectedStrip.setVisibility(selectedRow.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        updateHints();
    }

    private void addRemovalChip(String label, Runnable remove) {
        Button button = addChip(selectedRow, label + " ×", true, remove);
        button.setContentDescription("Remove filter “" + label + "”");
    }

    private boolean isSelected(String axis, String tag) {
        return selectedTags.getOrDefault(axis, Collections.emptySet()).contains(tag);
    }

    private void changeTag(RecipeCatalog.TagAxis axis, String tag, boolean checked) {
        Map<String, Set<String>> updated = new LinkedHashMap<>(selectedTags);
        Set<String> chosen = new LinkedHashSet<>(updated.getOrDefault(axis.id, Collections.emptySet()));
        if (checked) {
            if (!axis.multiple) {
                chosen.clear();
            }
            chosen.add(tag);
        } else {
            chosen.remove(tag);
        }
        if (chosen.isEmpty()) {
            updated.remove(axis.id);
        } else {
            updated.put(axis.id, chosen);
        }
        applyFilters(updated, discoveryTime);
    }

    private void chooseCategory(String id) {
        RecipeCatalog.TagAxis axis = axisById(id);
        if (axis == null) {
            return;
        }
        dismissKeyboard();
        AlertDialog.Builder menu = new AlertDialog.Builder(this).setTitle(axis.label);
        String[] labels = new String[axis.tags.size()];
        boolean[] checked = new boolean[labels.length];
        int selected = 0;
        for (int i = 0; i < labels.length; i++) {
            Recipe.Tag tag = axis.tags.get(i);
            labels[i] = tag.label;
            checked[i] = isSelected(axis.id, tag.id);
            if (checked[i]) {
                selected = i + 1;
            }
        }
        if (axis.multiple) {
            menu.setMultiChoiceItems(labels, checked, (dialog, which, enabled) ->
                    changeTag(axis, axis.tags.get(which).id, enabled));
            menu.setPositiveButton("Done", null);
        } else {
            String[] options = new String[labels.length + 1];
            options[0] = "Any option";
            System.arraycopy(labels, 0, options, 1, labels.length);
            menu.setSingleChoiceItems(options, selected, (dialog, which) -> {
                dialog.dismiss();
                if (which == 0) {
                    removeCategory(axis.id);
                } else {
                    changeTag(axis, axis.tags.get(which - 1).id, true);
                }
            });
        }
        menu.show();
    }

    private RecipeCatalog.TagAxis axisById(String id) {
        for (RecipeCatalog.TagAxis axis : tagAxes) {
            if (axis.id.equals(id)) {
                return axis;
            }
        }
        return null;
    }

    private void removeCategory(String axis) {
        Map<String, Set<String>> updated = new LinkedHashMap<>(selectedTags);
        updated.remove(axis);
        applyFilters(updated, discoveryTime);
    }

    private void chooseTime() {
        dismissKeyboard();
        List<Integer> options = new ArrayList<>(Arrays.asList(null, 15, 30, 45, 60));
        if (!options.contains(discoveryTime)) {
            options.add(discoveryTime);
        }
        String[] labels = options.stream().map(this::timeLabel).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("Cooking time")
                .setSingleChoiceItems(labels, options.indexOf(discoveryTime), (dialog, which) -> {
                    dialog.dismiss();
                    applyFilters(selectedTags, options.get(which));
                }).setNegativeButton("Cancel", null).show();
    }

    private String timeLabel(Integer minutes) {
        return minutes == null ? "No time limit"
                : "Up to " + minutes + " min";
    }

    private void editFilters() {
        dismissKeyboard();
        Map<String, Set<String>> draft = new LinkedHashMap<>();
        selectedTags.forEach((axis, tags) -> draft.put(axis, new LinkedHashSet<>(tags)));
        ScrollView window = new ScrollView(this);
        LinearLayout form = column();
        form.setPadding(dp(20), dp(4), dp(20), dp(12));
        form.setFocusableInTouchMode(true);
        window.addView(form);

        form.addView(text("Maximum minutes", 16, true));
        EditText time = field(discoveryTime == null ? "" : discoveryTime.toString(), InputType.TYPE_CLASS_NUMBER);
        time.setSingleLine(true);
        time.setHint("No limit");
        time.setContentDescription("Maximum minutes for discovery");
        form.addView(time);
        form.addView(text("You can skip any category.", 15, false));
        for (RecipeCatalog.TagAxis axis : tagAxes) {
            form.addView(text(axis.label, 17, true));
            Set<String> chosen = draft.computeIfAbsent(axis.id, id -> new LinkedHashSet<>());
            List<CheckBox> choices = new ArrayList<>();
            for (Recipe.Tag tag : axis.tags) {
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
                .setTitle("Find by tags")
                .setView(window)
                .setPositiveButton("Show recipes", null)
                .setNegativeButton("Cancel", null)
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
                    time.setError("Enter a positive number of minutes.");
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
        dismissKeyboard();
        updateSelection();
        discover(0, 0);
    }

    private void discover(int offset, int position) {
        queryField.removeCallbacks(submitQuery);
        int id = ++requestNumber;
        screen = Screen.DISCOVERY;
        updateScreen();
        pageOffset = offset;
        status.setText("Finding matching recipes…");
        String title = discoveryQuery;
        Map<String, Set<String>> tags = selectedTags;
        Integer time = discoveryTime;
        worker.execute(() -> {
            try {
                RecipeCatalog.CatalogPage page = RecipeCatalog.discover(
                        getApplicationContext(), title, tags, time, offset, PAGE_SIZE);
                runOnUiThread(() -> {
                    if (isDestroyed() || id != requestNumber) {
                        return;
                    }
                    results.removeAllViews();
                    titleHints = page.titleHints;
                    updateHints();
                    boolean noExact = page.total == 0 || page.partial;
                    status.setText(noExact ? "No exact matches"
                            : "Recipes found · " + page.total);
                    showRefinements(page.refinements);
                    if (page.partial) {
                        results.addView(text("Similar recipes · " + page.total, 17, true));
                    }
                    if (page.total > 0) {
                        showCards(page.cards, 0);
                        paginate(offset, page.total, next -> discover(next, 0));
                    }
                    if (page.total == 0 && page.refinements.isEmpty()) {
                        results.addView(text("Try another recipe name or clear the filters.", 15, false));
                    }
                    restoreScroll(id, position);
                });
            } catch (IOException | android.database.SQLException error) {
                reportCatalogError(id);
            }
        });
    }

    private void showRefinements(List<RecipeCatalog.Refinement> options) {
        for (RecipeCatalog.Refinement option : options) {
            String action;
            Runnable apply;
            switch (option.change()) {
                case TIME -> {
                    action = option.minutes() == null ? "Remove time limit"
                            : "Increase time to " + option.minutes() + " min";
                    apply = () -> applyFilters(selectedTags, option.minutes());
                }
                case CATEGORY -> {
                    RecipeCatalog.TagAxis axis = axisById(option.axis());
                    if (axis == null) {
                        continue;
                    }
                    String label = axis.label;
                    Set<String> selected = selectedTags.get(axis.id);
                    if (selected != null && selected.size() == 1) {
                        for (Recipe.Tag tag : axis.tags) {
                            if (selected.contains(tag.id)) {
                                label = tag.label;
                                break;
                            }
                        }
                    }
                    action = "Remove “" + label + "”";
                    apply = () -> removeCategory(axis.id);
                }
                case TITLE -> {
                    action = "Clear recipe name";
                    apply = () -> {
                        setQuery("");
                        dismissKeyboard();
                        discover(0, 0);
                    };
                }
                default -> throw new AssertionError(option.change());
            }
            Button suggestion = button(action + " · " + option.total(), false);
            suggestion.setContentDescription(action + " · recipes " + option.total());
            suggestion.setOnClickListener(view -> apply.run());
            results.addView(suggestion);
        }
    }

    private void restoreScroll(int request, int position) {
        scroll.post(() -> {
            if (!isDestroyed() && request == requestNumber) {
                scroll.scrollTo(0, position);
            }
        });
    }

    private void addPantryControls(LinearLayout panel, String products, String time,
                                   String portions, boolean includeBasics) {
        panel.addView(text("Search by ingredients", 19, true));
        pantry = field(products, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        pantry.setMinLines(2);
        pantry.setHint("Eggs, tomatoes, cheese…");
        pantry.setContentDescription("Available ingredients");
        panel.addView(pantry);
        Button demo = button("Use an example", false);
        demo.setOnClickListener(view -> {
            pantry.setText("eggs, tomatoes, cheese, pasta, garlic, bread, cucumbers");
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
        minutes.setContentDescription("Maximum minutes");
        servings = field(portions, InputType.TYPE_CLASS_NUMBER);
        servings.setContentDescription("Number of servings");
        timeColumn.addView(text("Up to, min", 15, true));
        timeColumn.addView(minutes);
        servingsColumn.addView(text("Servings", 15, true));
        servingsColumn.addView(servings);
        panel.addView(fields);

        basics = new CheckBox(this);
        basics.setText("Include water, salt and vegetable oil");
        basics.setChecked(includeBasics);
        panel.addView(basics);
        Button submit = button("Find dinner", true);
        submit.setOnClickListener(view -> search(-1));
        panel.addView(submit);
    }

    private static String value(EditText field) {
        return field.getText().toString().trim();
    }

    private void search(int position) {
        queryField.removeCallbacks(submitQuery);
        dismissKeyboard();
        int id = ++requestNumber;
        screen = Screen.PANTRY;
        pantrySearched = true;
        updateScreen();
        pageOffset = 0;
        results.removeAllViews();
        String ingredients = value(pantry);
        if (ingredients.replaceAll("[,;\\s]+", "").isEmpty()) {
            status.setText("Enter at least one ingredient.");
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
            status.setText("Enter 5–120 minutes and 1–6 servings.");
            return;
        }
        status.setText("Finding recipes…");
        boolean includeBasics = basics.isChecked();
        worker.execute(() -> {
            try {
                List<RecipeCatalog.Card> cards = RecipeCatalog.find(
                        getApplicationContext(), ingredients, limit, includeBasics);
                runOnUiThread(() -> {
                    if (!isDestroyed() && id == requestNumber) {
                        showResults(cards, people);
                        restoreScroll(id, position < 0 ? status.getTop() : position);
                    }
                });
            } catch (IOException | android.database.SQLException error) {
                reportCatalogError(id);
            }
        });
    }

    private void paginate(int offset, int total, IntConsumer open) {
        if (offset > 0) {
            Button previous = button("Previous page", false);
            previous.setOnClickListener(view -> open.accept(Math.max(0, offset - PAGE_SIZE)));
            results.addView(previous);
        }
        if (offset + PAGE_SIZE < total) {
            Button next = button("Next page", false);
            next.setOnClickListener(view -> open.accept(offset + PAGE_SIZE));
            results.addView(next);
        }
    }

    private void reportCatalogError(int id) {
        runOnUiThread(() -> {
            if (!isDestroyed() && id == requestNumber) {
                results.removeAllViews();
                status.setText("Could not read the local catalog.");
            }
        });
    }

    private void showResults(List<RecipeCatalog.Card> cards, int people) {
        if (cards.isEmpty()) {
            status.setText("No matching recipes. Try adding ingredients or increasing the time.");
            return;
        }
        status.setText("Recipe cards: " + cards.size());
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
                item.addView(text("Variants: " + card.variants.size(), 14, false));
                if (card.summary != null && !card.summary.isEmpty()) {
                    item.addView(text(card.summary, 15, false));
                }
            }
            for (Recipe recipe : card.variants) {
                String label = recipe.minutes > 0 ? recipe.title + " · " + recipe.minutes + " min"
                        : recipe.title;
                Button details = button(label, false);
                if (discovery && card.variants.size() == 1) {
                    details.setTextSize(18);
                }
                details.setOnClickListener(view -> showRecipe(recipe, people));
                item.addView(details);
                if (discovery) {
                    addVariantTags(item, recipe);
                }
            }
        }
    }

    private Button unitsChip(Runnable onChange) {
        Button units = chip("", false, () -> chooseUnits(onChange));
        updateUnitsChip(units);
        return units;
    }

    private void updateUnitsChip(Button button) {
        String label = measurementSystem == RecipeUnits.System.METRIC ? "Metric" : "Imperial";
        button.setText(label);
        button.setContentDescription("Measurement system · " + label);
    }

    private void chooseUnits(Runnable onChange) {
        dismissKeyboard();
        RecipeUnits.System[] systems = RecipeUnits.System.values();
        new AlertDialog.Builder(this).setTitle("Measurement system")
                .setSingleChoiceItems(new String[] {"Metric (g, mL, °C)", "Imperial (lb, oz, UK fl oz, °F)"},
                        measurementSystem.ordinal(), (dialog, which) -> {
                            measurementSystem = systems[which];
                            preferences.edit().putString(MEASUREMENT_PREFERENCE, measurementSystem.name()).apply();
                            updateUnitsChip(mainUnits);
                            if (detailUnits != null) {
                                updateUnitsChip(detailUnits);
                            }
                            if (onChange != null) {
                                onChange.run();
                            }
                            dialog.dismiss();
                        })
                .setNegativeButton("Cancel", null).show();
    }

    private void showRecipe(Recipe recipe, int people) {
        dismissKeyboard();
        openRecipeId = recipe.id;
        openRecipeServings = people;
        LinearLayout page = column();
        page.setBackgroundColor(PAPER);
        page.setPadding(dp(14), dp(8), dp(14), dp(8));
        page.setFocusableInTouchMode(true);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button back = chip("← Back to results", false, this::closeRecipe);
        actions.addView(back, new LinearLayout.LayoutParams(0, -2, 1));
        TextView body = text(recipeText(recipe, people), 16, false);
        detailUnits = unitsChip(() -> body.setText(recipeText(recipe, people)));
        actions.addView(detailUnits);
        page.addView(actions);
        ScrollView details = new ScrollView(this);
        LinearLayout content = column();
        content.setPadding(dp(4), dp(8), dp(4), dp(24));
        content.addView(text(recipe.title, 27, true));
        if (recipe.minutes > 0) {
            content.addView(text(recipe.minutes + " min", 16, false));
        }
        body.setTextIsSelectable(true);
        content.addView(body);
        details.addView(content);
        page.addView(details, new LinearLayout.LayoutParams(-1, 0, 1));
        detailScreen = page;
        mainScreen.setVisibility(View.INVISIBLE);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        page.requestFocus();
    }

    private void closeRecipe() {
        openRecipeId = null;
        if (detailScreen != null) {
            root.removeView(detailScreen);
            detailScreen = null;
            detailUnits = null;
            mainScreen.setVisibility(View.VISIBLE);
        }
    }

    private void restoreRecipe() {
        if (openRecipeId == null) {
            return;
        }
        String id = openRecipeId;
        int people = openRecipeServings;
        FrameLayout surface = root;
        worker.execute(() -> {
            Recipe restored;
            try {
                restored = RecipeCatalog.recipe(getApplicationContext(), id);
            } catch (IOException | android.database.SQLException error) {
                restored = null;
            }
            Recipe recipe = restored;
            runOnUiThread(() -> {
                if (!isDestroyed() && root == surface && id.equals(openRecipeId)) {
                    if (recipe != null) {
                        showRecipe(recipe, people);
                    } else {
                        closeRecipe();
                    }
                }
            });
        });
    }

    private void addVariantTags(LinearLayout item, Recipe recipe) {
        Set<String> labels = new LinkedHashSet<>();
        for (Recipe.Tag tag : recipe.tags) {
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
            item.addView(text("Did not match · " + String.join(", ", missing), 14, true));
        }
    }

    private String recipeText(Recipe recipe, int people) {
        int portionCount = recipe.servings(people);
        StringBuilder body = new StringBuilder();
        if (!recipe.tags.isEmpty()) {
            List<String> labels = new ArrayList<>();
            for (Recipe.Tag tag : recipe.tags) {
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
        body.append("Ingredients");
        if (portionCount > 0) {
            body.append(" · Servings ").append(portionCount);
        }
        body.append('\n');
        for (String ingredient : recipe.ingredientLines(people, measurementSystem)) {
            body.append("• ").append(ingredient).append('\n');
        }
        body.append('\n').append("Method").append('\n');
        for (int i = 0; i < recipe.steps.size(); i++) {
            body.append(i + 1).append(". ").append(RecipeUnits.formatText(recipe.steps.get(i), measurementSystem)).append('\n');
        }
        if (recipe.isImported()) {
            body.append('\n').append("Source and authors · Wikibooks")
                    .append('\n').append(recipe.revisionUrl)
                    .append('\n').append("Adaptation and translation · CC BY-SA 4.0")
                    .append('\n').append(recipe.licenseUrl);
        }
        return body.toString().trim();
    }
}
