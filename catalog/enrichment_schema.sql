CREATE TABLE enrichment_recipe (
    id TEXT PRIMARY KEY NOT NULL,
    source_language TEXT NOT NULL CHECK (source_language IN ('ru', 'en')),
    revision_url TEXT NOT NULL,
    license_url TEXT NOT NULL,
    source_gap INTEGER NOT NULL CHECK (source_gap IN (0, 1)),
    translation_issues_json TEXT NOT NULL
);

CREATE TABLE recipe_localization (
    recipe_id TEXT NOT NULL REFERENCES enrichment_recipe(id),
    language TEXT NOT NULL CHECK (language IN ('ru', 'en')),
    title TEXT NOT NULL,
    ingredients_json TEXT NOT NULL,
    steps_json TEXT NOT NULL,
    PRIMARY KEY (recipe_id, language)
);

CREATE TABLE completion_proposal (
    recipe_id TEXT NOT NULL REFERENCES enrichment_recipe(id),
    kind TEXT NOT NULL CHECK (kind IN ('ingredient', 'step')),
    position INTEGER NOT NULL,
    after_step INTEGER,
    text_ru TEXT NOT NULL,
    text_en TEXT NOT NULL,
    provenance TEXT NOT NULL CHECK (provenance IN ('source_excerpt', 'source_inference', 'ai_suggestion')),
    evidence TEXT NOT NULL,
    review_status TEXT NOT NULL DEFAULT 'pending' CHECK (review_status = 'pending'),
    PRIMARY KEY (recipe_id, kind, position)
);

CREATE TABLE recipe_family (
    id TEXT PRIMARY KEY NOT NULL,
    title_ru TEXT NOT NULL,
    title_en TEXT NOT NULL,
    summary_ru TEXT NOT NULL,
    summary_en TEXT NOT NULL
);

CREATE TABLE family_member (
    recipe_id TEXT PRIMARY KEY NOT NULL REFERENCES enrichment_recipe(id),
    family_id TEXT NOT NULL REFERENCES recipe_family(id)
);
