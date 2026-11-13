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
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.RelativeSizeSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
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
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public final class MainActivity extends Activity {
    private static final int PAPER = 0xfffff9ee;
    private static final int INK = 0xff322319;
    private static final int ACCENT = 0xff7c3b21;
    private static final int MUTED = 0xff68523d;
    private static final int CARD = 0xfffffdf8;
    private static final int SAND = 0xfff4e5d3;
    private static final int SAGE = 0xffe5eadb;
    private static final int PAGE_SIZE = 24;
    private static final String MEASUREMENT_PREFERENCE = "measurement_system";
    private static final String SAVED_RECIPES_PREFERENCE = "saved_recipe_ids";

    private enum Screen { HOME, DISCOVERY, PANTRY, SAVED }

    private record PantryInput(String ingredients, String minutes, String servings, boolean includeBasics) {
        static PantryInput read(Bundle state) {
            return new PantryInput(state.getString("pantry", ""), state.getString("minutes", "20"),
                    state.getString("servings", "2"), state.getBoolean("basics", true));
        }

        void write(Bundle state) {
            state.putString("pantry", ingredients);
            state.putString("minutes", minutes);
            state.putString("servings", servings);
            state.putBoolean("basics", includeBasics);
        }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService imageWorker = Executors.newSingleThreadExecutor();
    private RecipePhotos photos;
    private SharedPreferences preferences;
    private RecipeUnits.System measurementSystem;
    private Button mainUnits;
    private Button boardButton;
    private Button detailUnits;
    private Screen screen = Screen.HOME;
    private PantryInput submittedPantry;
    private String discoveryQuery = "";
    private String collectionTitle = "";
    private int pageOffset;
    private Map<String, Set<String>> selectedTags = new LinkedHashMap<>();
    private Integer discoveryTime;
    private List<RecipeCatalog.TagAxis> tagAxes = Collections.emptyList();
    private int requestNumber;
    private Future<?> searchTask;
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
    private final Runnable submitQuery = () -> loadDiscoveryPage(requestNumber, 0, 0);
    private View detailScreen;
    private ScrollView detailScroll;
    private int pendingDetailScroll;
    private String openRecipeId;
    private int openRecipeServings;
    private final Set<Integer> checkedIngredients = new LinkedHashSet<>();
    private EditText pantry;
    private EditText minutes;
    private EditText servings;
    private CheckBox basics;
    private TextView status;
    private TextView collectionHeading;
    private LinearLayout results;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        photos = new RecipePhotos(getAssets());
        preferences = getSharedPreferences("settings", MODE_PRIVATE);
        measurementSystem = RecipeUnits.System.IMPERIAL.name().equals(preferences.getString(MEASUREMENT_PREFERENCE, ""))
                ? RecipeUnits.System.IMPERIAL : RecipeUnits.System.METRIC;
        PantryInput pantryDraft = PantryInput.read(state == null ? Bundle.EMPTY : state);
        if (state != null) {
            String savedScreen = state.getString("screen", Screen.HOME.name());
            screen = "CATALOG".equals(savedScreen) ? Screen.DISCOVERY : Screen.valueOf(savedScreen);
            Bundle submitted = state.getBundle("submittedPantry");
            if (submitted != null) {
                submittedPantry = PantryInput.read(submitted);
            } else if (!state.containsKey("submittedPantry")
                    && state.getBoolean("pantrySearched", screen == Screen.PANTRY)) {
                submittedPantry = pantryDraft;
            }
            discoveryQuery = state.getString("discoveryQuery", state.getString("catalogQuery", ""));
            collectionTitle = state.getString("collectionTitle", "");
            pageOffset = state.getInt("pageOffset");
            openRecipeId = state.getString("openRecipeId");
            openRecipeServings = state.getInt("openRecipeServings");
            pendingDetailScroll = state.getInt("detailScrollY");
            ArrayList<Integer> checked = state.getIntegerArrayList("checkedIngredients");
            if (checked != null) {
                checkedIngredients.addAll(checked);
            }
            discoveryTime = state.containsKey("discoveryTime") ? state.getInt("discoveryTime") : null;
            Bundle savedTags = state.getBundle("tags");
            if (savedTags != null) {
                for (String axis : savedTags.keySet()) {
                    selectedTags.put(axis, new LinkedHashSet<>(savedTags.getStringArrayList(axis)));
                }
            }
        }
        render(pantryDraft);
        restoreResults(state == null ? 0 : state.getInt("scrollY"));
        restoreRecipe();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        pantryInput().write(state);
        Bundle submitted = null;
        if (submittedPantry != null) {
            submitted = new Bundle();
            submittedPantry.write(submitted);
        }
        state.putBundle("submittedPantry", submitted);
        state.putString("screen", screen.name());
        state.putString("discoveryQuery", discoveryQuery);
        state.putString("collectionTitle", collectionTitle);
        state.putInt("pageOffset", pageOffset);
        state.putInt("scrollY", scroll.getScrollY());
        state.putString("openRecipeId", openRecipeId);
        state.putInt("openRecipeServings", openRecipeServings);
        state.putInt("detailScrollY", pendingDetailScroll != 0 || detailScroll == null
                ? pendingDetailScroll : detailScroll.getScrollY());
        state.putIntegerArrayList("checkedIngredients", new ArrayList<>(checkedIngredients));
        if (discoveryTime != null) {
            state.putInt("discoveryTime", discoveryTime);
        }
        Bundle savedTags = new Bundle();
        selectedTags.forEach((axis, tags) -> savedTags.putStringArrayList(axis, new ArrayList<>(tags)));
        state.putBundle("tags", savedTags);
    }

    @Override
    protected void onDestroy() {
        invalidateSearch();
        worker.shutdownNow();
        imageWorker.shutdownNow();
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

    private TextView headline(String content, int size) {
        TextView title = text(content, size, true);
        title.setTypeface(Typeface.create("serif", Typeface.BOLD));
        if (Build.VERSION.SDK_INT >= 28) {
            title.setAccessibilityHeading(true);
        }
        return title;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setStateListAnimator(null);
        button.setElevation(0);
        button.setTextColor(primary ? Color.WHITE : ACCENT);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x347c3b21),
                surface(primary ? ACCENT : SAND, 14), null));
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
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

    private void render(PantryInput pantryDraft) {
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
        header.setMinimumHeight(dp(58));
        header.setPadding(dp(16), 0, dp(12), 0);
        header.addView(text("Dinner Picker", 18, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        boardButton = chip("Board", false, this::openHome);
        header.addView(boardButton);
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
        addPantryControls(pantryPanel, pantryDraft);
        collectionHeading = headline("", 28);
        page.addView(collectionHeading);
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
                if (submittedPantry != null) {
                    search(submittedPantry, position);
                }
            }
            case SAVED -> loadSavedRecipes(position);
            case HOME -> { }
        }
    }

    private void addIdeas(LinearLayout page) {
        page.addView(headline("What’s for dinner?", 34));
        Button searchRecipes = button("Search recipes", false);
        searchRecipes.setOnClickListener(view -> {
            collectionTitle = "";
            setQuery("");
            applyFilters(Collections.emptyMap(), null);
            queryField.requestFocus();
            getSystemService(InputMethodManager.class).showSoftInput(queryField, InputMethodManager.SHOW_IMPLICIT);
        });
        Button pantry = button("Pantry", false);
        pantry.setOnClickListener(view -> {
            openScreen(Screen.PANTRY);
            restoreResults(0);
        });
        addChoices(page, Arrays.asList(searchRecipes, pantry));
        Button saved = button("Saved recipes", false);
        saved.setOnClickListener(view -> {
            openScreen(Screen.SAVED);
            loadSavedRecipes(0);
        });
        page.addView(saved);

        FrameLayout feature = new FrameLayout(this);
        feature.setBackground(surface(0xff553e2e, 20));
        feature.setClipToOutline(true);
        FrameLayout.LayoutParams match = new FrameLayout.LayoutParams(-1, -1);
        ImageView cover = photoView();
        feature.addView(cover, match);
        bindPhoto(cover, RecipePhotos.COZY_DINNERS, false, null);
        Button cozy = preset("Cozy dinners", null, "mood", "cozy", "meal", "dinner");
        SpannableString caption = new SpannableString("Cozy dinners\nExplore collection →");
        int actionStart = "Cozy dinners\n".length();
        caption.setSpan(new RelativeSizeSpan(0.65f), actionStart, caption.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        caption.setSpan(new TypefaceSpan("sans-serif"), actionStart, caption.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        cozy.setText(caption);
        cozy.setContentDescription("Cozy dinners");
        cozy.setGravity(Gravity.BOTTOM | Gravity.START);
        cozy.setTextColor(Color.WHITE);
        cozy.setTextSize(22);
        cozy.setTypeface(Typeface.create("serif", Typeface.BOLD));
        cozy.setPadding(dp(16), dp(12), dp(16), dp(16));
        GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0xf224160f, 0x5524160f, 0x0524160f});
        cozy.setBackground(new RippleDrawable(ColorStateList.valueOf(0x35ffffff), shade, null));
        feature.addView(cozy, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams featured = new LinearLayout.LayoutParams(-1, dp(188));
        featured.setMargins(0, dp(14), 0, dp(12));
        page.addView(feature, featured);

        TextView heading = text("Browse collections", 19, true);
        if (Build.VERSION.SDK_INT >= 28) {
            heading.setAccessibilityHeading(true);
        }
        page.addView(heading);
        addChoices(page, Arrays.asList(
                preset("Under 30 min", 30),
                collectionTile("For guests", "occasion", "guests"),
                preset("Something new", null, "novelty", "discovery"),
                collectionTile("Breakfast", "meal", "breakfast"),
                preset("Baking for tea", null, "dish_type", "baking", "occasion", "tea"),
                preset("Quick lunch", 20, "meal", "lunch")));
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
                collectionTitle = "";
                titleHints = Collections.emptyList();
                updateHints();
                beginDiscovery(0);
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
        chip.setTextColor(selected ? Color.WHITE : ACCENT);
        chip.setBackground(new RippleDrawable(ColorStateList.valueOf(0x347c3b21),
                surface(selected ? ACCENT : SAND, 16), null));
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
        invalidateSearch();
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
        discoveryControls.setVisibility(screen == Screen.DISCOVERY ? View.VISIBLE : View.GONE);
        boardButton.setVisibility(screen == Screen.HOME ? View.GONE : View.VISIBLE);
        status.setVisibility(screen == Screen.HOME ? View.GONE : View.VISIBLE);
        String heading = switch (screen) {
            case SAVED -> "Saved recipes";
            case DISCOVERY -> collectionTitle;
            default -> "";
        };
        collectionHeading.setText(heading);
        collectionHeading.setVisibility(heading.isEmpty() ? View.GONE : View.VISIBLE);
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
                spacing.setMargins(dp(2), dp(3), dp(2), dp(3));
                row.addView(choice, spacing);
            }
            parent.addView(row);
        }
    }

    private Button preset(String label, Integer time, String... tags) {
        Button idea = button(label, false);
        idea.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        idea.setMinimumHeight(dp(61));
        idea.setOnClickListener(view -> {
            Map<String, Set<String>> picks = new LinkedHashMap<>();
            for (int i = 0; i < tags.length; i += 2) {
                picks.put(tags[i], Collections.singleton(tags[i + 1]));
            }
            setQuery("");
            applyFilters(picks, time, label);
        });
        return idea;
    }

    private Button collectionTile(String label, String... tags) {
        Button idea = preset(label, null, tags);
        idea.setBackground(new RippleDrawable(ColorStateList.valueOf(0x34495633),
                surface(SAGE, 14), null));
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
        applyFilters(tags, time, "");
    }

    private void applyFilters(Map<String, Set<String>> tags, Integer time, String collection) {
        collectionTitle = collection;
        selectedTags = tags;
        discoveryTime = time;
        dismissKeyboard();
        updateSelection();
        discover(0, 0);
    }

    private void discover(int offset, int position) {
        loadDiscoveryPage(beginDiscovery(offset), offset, position);
    }

    private int invalidateSearch() {
        queryField.removeCallbacks(submitQuery);
        if (searchTask != null) {
            searchTask.cancel(false);
            searchTask = null;
        }
        return ++requestNumber;
    }

    private boolean isCurrentSearch(int id) {
        return id == requestNumber && !isDestroyed();
    }

    private int beginDiscovery(int offset) {
        int id = invalidateSearch();
        screen = Screen.DISCOVERY;
        updateScreen();
        pageOffset = offset;
        results.removeAllViews();
        status.setText("Finding matching recipes…");
        return id;
    }

    private void loadDiscoveryPage(int id, int offset, int position) {
        String title = discoveryQuery;
        Map<String, Set<String>> tags = selectedTags;
        Integer time = discoveryTime;
        searchTask = worker.submit(() -> {
            try {
                RecipeCatalog.CatalogPage page = RecipeCatalog.discover(
                        getApplicationContext(), title, tags, time, offset, PAGE_SIZE);
                runOnUiThread(() -> {
                    if (isCurrentSearch(id)) {
                        renderDiscoveryPage(page, offset);
                        restoreScroll(id, position);
                    }
                });
            } catch (IOException | android.database.SQLException error) {
                reportCatalogError(id);
            }
        });
    }

    private void renderDiscoveryPage(RecipeCatalog.CatalogPage page, int offset) {
        titleHints = page.titleHints;
        updateHints();
        status.setText(page.total == 0 || page.partial ? "No exact matches" : "Recipes found · " + page.total);
        showRefinements(page.refinements);
        if (page.partial) {
            results.addView(text("Similar recipes · " + page.total, 17, true));
        }
        if (page.total > 0) {
            showCards(page.cards, 0);
            paginate(offset, page.total, next -> discover(next, 0));
        } else if (page.refinements.isEmpty()) {
            results.addView(text("Try another recipe name or clear the filters.", 15, false));
        }
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
            if (isCurrentSearch(request)) {
                scroll.scrollTo(0, position);
            }
        });
    }

    private void addPantryControls(LinearLayout panel, PantryInput draft) {
        panel.addView(text("Search by ingredients", 19, true));
        pantry = field(draft.ingredients(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
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
        minutes = field(draft.minutes(), InputType.TYPE_CLASS_NUMBER);
        minutes.setContentDescription("Maximum minutes");
        servings = field(draft.servings(), InputType.TYPE_CLASS_NUMBER);
        servings.setContentDescription("Number of servings");
        timeColumn.addView(text("Up to, min", 15, true));
        timeColumn.addView(minutes);
        servingsColumn.addView(text("Servings", 15, true));
        servingsColumn.addView(servings);
        panel.addView(fields);

        basics = new CheckBox(this);
        basics.setText("Include water, salt and vegetable oil");
        basics.setChecked(draft.includeBasics());
        panel.addView(basics);
        Button submit = button("Find dinner", true);
        submit.setOnClickListener(view -> {
            submittedPantry = pantryInput();
            search(submittedPantry, -1);
        });
        panel.addView(submit);
    }

    private static String value(EditText field) {
        return field.getText().toString().trim();
    }

    private PantryInput pantryInput() {
        return new PantryInput(value(pantry), value(minutes), value(servings), basics.isChecked());
    }

    private void search(PantryInput input, int position) {
        int id = invalidateSearch();
        dismissKeyboard();
        screen = Screen.PANTRY;
        updateScreen();
        pageOffset = 0;
        results.removeAllViews();
        String ingredients = input.ingredients();
        if (ingredients.replaceAll("[,;\\s]+", "").isEmpty()) {
            status.setText("Enter at least one ingredient.");
            return;
        }
        int limit;
        int people;
        try {
            limit = Integer.parseInt(input.minutes());
            people = Integer.parseInt(input.servings());
            if (limit < 5 || limit > 120 || people < 1 || people > 6) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException error) {
            status.setText("Enter 5–120 minutes and 1–6 servings.");
            return;
        }
        status.setText("Finding recipes…");
        searchTask = worker.submit(() -> {
            try {
                RecipeCatalog.PantryResult found = RecipeCatalog.findPantry(
                        getApplicationContext(), ingredients, limit, input.includeBasics());
                runOnUiThread(() -> {
                    if (isCurrentSearch(id)) {
                        showPantryResults(found, people);
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
            if (isCurrentSearch(id)) {
                results.removeAllViews();
                status.setText("Could not read the local catalog.");
            }
        });
    }

    private void showPantryResults(RecipeCatalog.PantryResult found, int people) {
        if (!found.cards().isEmpty()) {
            status.setText("Recipe cards: " + found.cards().size());
            showCards(found.cards(), people);
        } else if (found.suggestions().isEmpty()) {
            status.setText("No matching recipes. Try adding ingredients or increasing the time.");
        } else {
            status.setText("No exact matches. These recipes need one more ingredient.");
            for (RecipeCatalog.PantrySuggestion suggestion : found.suggestions()) {
                LinearLayout card = recipeCard();
                addRecipeRow(card, suggestion.recipe(), people);
                card.addView(text("Missing: " + suggestion.missingIngredient(), 15, true));
            }
        }
    }

    private Set<String> savedRecipeIds() {
        return preferences.getStringSet(SAVED_RECIPES_PREFERENCE, Collections.emptySet());
    }

    private void loadSavedRecipes(int position) {
        int id = invalidateSearch();
        Set<String> saved = new LinkedHashSet<>(savedRecipeIds());
        results.removeAllViews();
        if (saved.isEmpty()) {
            status.setText("Save a recipe to find it here later.");
            return;
        }
        status.setText("Loading saved recipes…");
        searchTask = worker.submit(() -> {
            try {
                List<Recipe> recipes = RecipeCatalog.savedRecipes(getApplicationContext(), saved);
                runOnUiThread(() -> {
                    if (isCurrentSearch(id)) {
                        status.setText(recipes.isEmpty() ? "Saved recipes are unavailable in this catalog." : "");
                        for (Recipe recipe : recipes) {
                            addRecipeRow(recipeCard(), recipe, 0);
                        }
                        restoreScroll(id, position);
                    }
                });
            } catch (IOException | android.database.SQLException error) {
                reportCatalogError(id);
            }
        });
    }

    private void showCards(List<RecipeCatalog.Card> cards, int people) {
        for (RecipeCatalog.Card card : cards) {
            LinearLayout item = recipeCard();
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
                addRecipeRow(item, recipe, people);
                if (discovery) {
                    addVariantTags(item, recipe);
                }
            }
        }
    }

    private LinearLayout recipeCard() {
        LinearLayout card = column();
        card.setBackground(surface(CARD, 18));
        card.setPadding(dp(12), dp(9), dp(12), dp(9));
        LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2);
        spacing.bottomMargin = dp(10);
        results.addView(card, spacing);
        return card;
    }

    private void addRecipeRow(LinearLayout card, Recipe recipe, int people) {
        String label = recipe.minutes > 0 ? recipe.title + " · " + recipe.minutes + " min" : recipe.title;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView thumbnail = photoView();
        thumbnail.setBackground(surface(SAND, 11));
        thumbnail.setClipToOutline(true);
        row.addView(thumbnail, new LinearLayout.LayoutParams(dp(70), dp(62)));
        bindPhoto(thumbnail, recipe.id, true, null);
        Button details = button(label, false);
        details.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        details.setBackground(new RippleDrawable(ColorStateList.valueOf(0x347c3b21), surface(CARD, 12), null));
        details.setTextSize(16);
        details.setTextColor(INK);
        details.setTypeface(null, Typeface.BOLD);
        details.setTag(recipe.id);
        details.setOnClickListener(view -> showRecipe(recipe, people));
        thumbnail.setOnClickListener(view -> details.performClick());
        row.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(row);
    }

    private ImageView photoView() {
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return image;
    }

    private void bindPhoto(ImageView view, String key, boolean thumbnail, Consumer<RecipePhotos.Credit> onComplete) {
        View.OnAttachStateChangeListener binding = new View.OnAttachStateChangeListener() {
            private Future<?> request;

            @Override public void onViewAttachedToWindow(View attached) {
                request = loadPhoto(view, key, thumbnail, onComplete);
            }

            @Override public void onViewDetachedFromWindow(View detached) {
                request.cancel(false);
                detached.removeOnAttachStateChangeListener(this);
            }
        };
        view.addOnAttachStateChangeListener(binding);
        if (view.isAttachedToWindow()) {
            binding.onViewAttachedToWindow(view);
        }
    }

    private Future<?> loadPhoto(ImageView view, String key, boolean thumbnail,
                                Consumer<RecipePhotos.Credit> onComplete) {
        return imageWorker.submit(() -> {
            RecipePhotos.Photo photo = null;
            try {
                photo = photos.load(key, thumbnail);
            } catch (IOException error) {
                Log.w("DinnerPicker", "Cannot load a local recipe photo", error);
            }
            RecipePhotos.Photo decoded = photo;
            runOnUiThread(() -> {
                if (isDestroyed() || !view.isAttachedToWindow()) {
                    return;
                }
                if (decoded != null) {
                    view.setImageBitmap(decoded.bitmap());
                }
                if (onComplete != null) {
                    onComplete.accept(decoded == null ? null : decoded.credit());
                }
            });
        });
    }

    private void showPhotoCredit(RecipePhotos.Credit credit) {
        String title = credit.title() + "\n" + credit.author() + "\n\n";
        String license = credit.license() + "\n";
        String source = "Source · Wikimedia Commons";
        SpannableString message = new SpannableString(title + license + source + "\n\nPhoto resized and cropped to fit.");
        message.setSpan(new URLSpan(credit.licenseUrl()), title.length(),
                title.length() + credit.license().length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int sourceStart = title.length() + license.length();
        message.setSpan(new URLSpan(credit.sourceUrl()), sourceStart,
                sourceStart + source.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView body = text("", 15, false);
        body.setText(message);
        body.setLinkTextColor(ACCENT);
        body.setMovementMethod(LinkMovementMethod.getInstance());
        body.setPadding(dp(22), dp(12), dp(22), dp(16));
        ScrollView content = new ScrollView(this);
        content.addView(body);
        new AlertDialog.Builder(this).setTitle("Photo credit").setView(content)
                .setPositiveButton("Close", null).show();
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
        if (!recipe.id.equals(openRecipeId)) {
            checkedIngredients.clear();
            pendingDetailScroll = 0;
        }
        openRecipeId = recipe.id;
        openRecipeServings = people;
        LinearLayout page = column();
        page.setBackgroundColor(PAPER);
        page.setPadding(dp(14), dp(8), dp(14), dp(8));
        page.setFocusableInTouchMode(true);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        String backLabel = screen == Screen.SAVED ? "← Back to saved recipes" : "← Back to results";
        Button back = chip(backLabel, false, this::closeRecipe);
        actions.addView(back, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(actions);
        ScrollView details = new ScrollView(this);
        LinearLayout content = column();
        content.setPadding(dp(4), dp(8), dp(4), dp(24));
        content.addView(headline(recipe.title, 30));
        if (recipe.minutes > 0) {
            content.addView(text(recipe.minutes + " min", 16, false));
        }
        addSaveAction(content, recipe.id);
        addRecipePhoto(content, recipe.id, details);
        addRecipeTags(content, recipe);
        detailUnits = unitsChip(addRecipeSections(content, recipe, people));
        actions.addView(detailUnits);
        if (recipe.notes != null && !recipe.notes.isEmpty()) {
            LinearLayout notes = column();
            notes.addView(headline("Notes", 20));
            TextView note = text(recipe.notes, 15, false);
            Linkify.addLinks(note, Linkify.WEB_URLS);
            notes.addView(note);
            content.addView(recipeSection(notes), sectionSpacing());
        }
        if (recipe.isImported()) {
            TextView source = text("Source and authors · Wikibooks\n" + recipe.revisionUrl
                    + "\nAdaptation and translation · CC BY-SA 4.0\n" + recipe.licenseUrl, 14, false);
            Linkify.addLinks(source, Linkify.WEB_URLS);
            content.addView(source, sectionSpacing());
        }
        details.addView(content);
        page.addView(details, new LinearLayout.LayoutParams(-1, 0, 1));
        detailScreen = page;
        detailScroll = details;
        mainScreen.setVisibility(View.INVISIBLE);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        page.requestFocus();
    }

    private void addSaveAction(LinearLayout content, String recipeId) {
        Button save = button(savedRecipeIds().contains(recipeId) ? "Remove from saved" : "Save recipe", false);
        save.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        save.setOnClickListener(view -> {
            Set<String> ids = new LinkedHashSet<>(savedRecipeIds());
            boolean saved = !ids.remove(recipeId);
            if (saved) {
                ids.add(recipeId);
            }
            preferences.edit().putStringSet(SAVED_RECIPES_PREFERENCE, ids).apply();
            save.setText(saved ? "Remove from saved" : "Save recipe");
        });
        LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2);
        spacing.bottomMargin = dp(8);
        content.addView(save, spacing);
    }

    private void addRecipePhoto(LinearLayout content, String recipeId, ScrollView details) {
        ImageView image = photoView();
        image.setBackground(surface(SAND, 18));
        image.setClipToOutline(true);
        image.setVisibility(View.GONE);
        content.addView(image, new LinearLayout.LayoutParams(-1, dp(192)));
        Button creditButton = button("Photo credit", false);
        creditButton.setVisibility(View.GONE);
        content.addView(creditButton);
        bindPhoto(image, recipeId, false, credit -> {
            if (credit != null) {
                image.setVisibility(View.VISIBLE);
                creditButton.setText(credit.illustrative() ? "Illustrative photo · Credit" : "Photo credit");
                creditButton.setOnClickListener(view -> showPhotoCredit(credit));
                creditButton.setVisibility(View.VISIBLE);
            }
            restoreDetailScroll(details);
        });
    }

    private void restoreDetailScroll(ScrollView details) {
        if (pendingDetailScroll == 0) {
            return;
        }
        // Wait for the optional photo and credit to take up their final space.
        details.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                details.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if (detailScroll == details) {
                    if (details.getScrollY() == 0) {
                        details.scrollTo(0, pendingDetailScroll);
                    }
                    pendingDetailScroll = 0;
                }
            }
        });
        details.requestLayout();
    }

    private LinearLayout.LayoutParams sectionSpacing() {
        LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2);
        spacing.topMargin = dp(12);
        return spacing;
    }

    private LinearLayout recipeSection(LinearLayout content) {
        content.setBackground(surface(CARD, 18));
        content.setPadding(dp(13), dp(8), dp(13), dp(10));
        return content;
    }

    private Runnable addRecipeSections(LinearLayout content, Recipe recipe, int people) {
        LinearLayout ingredients = recipeSection(column());
        LinearLayout method = recipeSection(column());
        int portions = recipe.servings(people);
        ingredients.addView(headline(portions > 0 ? "Ingredients · Servings " + portions : "Ingredients", 20));
        method.addView(headline("Method", 20));

        List<String> initialLines = recipe.ingredientLines(people, measurementSystem);
        List<CheckBox> ingredientRows = new ArrayList<>(initialLines.size());
        for (String line : initialLines) {
            int position = ingredientRows.size();
            CheckBox ingredient = new CheckBox(this);
            ingredient.setText(line);
            ingredient.setTextSize(16);
            ingredient.setTextColor(INK);
            ingredient.setMinimumHeight(dp(48));
            ingredient.setChecked(checkedIngredients.contains(position));
            ingredient.setOnCheckedChangeListener((view, checked) -> {
                if (checked) {
                    checkedIngredients.add(position);
                } else {
                    checkedIngredients.remove(position);
                }
            });
            ingredientRows.add(ingredient);
            ingredients.addView(ingredient);
        }
        List<TextView> stepRows = new ArrayList<>(recipe.steps.size());
        for (int index = 0; index < recipe.steps.size(); index++) {
            TextView step = text(formatStep(recipe, index), 16, false);
            step.setTextIsSelectable(true);
            stepRows.add(step);
            method.addView(step);
        }
        content.addView(ingredients, sectionSpacing());
        content.addView(method, sectionSpacing());
        return () -> {
            List<String> lines = recipe.ingredientLines(people, measurementSystem);
            for (int index = 0; index < ingredientRows.size(); index++) {
                ingredientRows.get(index).setText(lines.get(index));
            }
            for (int index = 0; index < stepRows.size(); index++) {
                stepRows.get(index).setText(formatStep(recipe, index));
            }
        };
    }

    private String formatStep(Recipe recipe, int index) {
        return (index + 1) + ". " + RecipeUnits.formatText(recipe.steps.get(index), measurementSystem);
    }

    private void closeRecipe() {
        View opener = openRecipeId == null ? null : results.findViewWithTag(openRecipeId);
        boolean removedFromSaved = screen == Screen.SAVED && openRecipeId != null
                && !savedRecipeIds().contains(openRecipeId);
        openRecipeId = null;
        checkedIngredients.clear();
        pendingDetailScroll = 0;
        if (detailScreen != null) {
            root.removeView(detailScreen);
            detailScreen = null;
            detailScroll = null;
            detailUnits = null;
            mainScreen.setVisibility(View.VISIBLE);
            if (removedFromSaved) {
                loadSavedRecipes(scroll.getScrollY());
            } else if (opener != null) {
                restoreResultFocus(opener);
            }
        }
    }

    private void restoreResultFocus(View opener) {
        opener.post(() -> {
            if (detailScreen != null || !opener.isShown() || !opener.isAttachedToWindow()) {
                return;
            }
            if (getSystemService(AccessibilityManager.class).isTouchExplorationEnabled()) {
                opener.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
            } else if (!opener.isInTouchMode()) {
                opener.requestFocus();
            }
        });
    }

    private void restoreRecipe() {
        if (openRecipeId == null) {
            return;
        }
        String id = openRecipeId;
        int people = openRecipeServings;
        int request = requestNumber;
        worker.execute(() -> {
            Recipe restored;
            try {
                restored = RecipeCatalog.recipe(getApplicationContext(), id);
            } catch (IOException | android.database.SQLException error) {
                restored = null;
            }
            Recipe recipe = restored;
            runOnUiThread(() -> {
                if (isDestroyed() || detailScreen != null || !id.equals(openRecipeId)) {
                    return;
                }
                if (recipe != null && isCurrentSearch(request)) {
                    showRecipe(recipe, people);
                } else {
                    closeRecipe();
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

    private void addRecipeTags(LinearLayout content, Recipe recipe) {
        if (!recipe.tags.isEmpty()) {
            List<String> labels = new ArrayList<>();
            for (Recipe.Tag tag : recipe.tags) {
                if (tag.note == null) {
                    labels.add(tag.label);
                } else {
                    TextView note = text(tag.label + " · " + tag.note, 14, false);
                    note.setTextColor(MUTED);
                    content.addView(note);
                }
            }
            if (!labels.isEmpty()) {
                content.addView(text(String.join(" · ", labels), 14, false));
            }
        }
    }
}
