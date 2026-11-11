PRAGMA user_version = 1;

CREATE TABLE recipe (
    id TEXT PRIMARY KEY NOT NULL,
    language TEXT NOT NULL CHECK (language IN ('ru', 'en')),
    title TEXT NOT NULL,
    minutes_min INTEGER CHECK (minutes_min > 0),
    minutes_max INTEGER CHECK (minutes_max >= minutes_min),
    time_text TEXT NOT NULL,
    servings INTEGER CHECK (servings > 0),
    servings_text TEXT NOT NULL
);

CREATE TABLE recipe_origin (
    recipe_id TEXT NOT NULL REFERENCES recipe(id),
    language TEXT NOT NULL CHECK (language IN ('ru', 'en')),
    page_id INTEGER NOT NULL,
    source_title TEXT NOT NULL,
    revision INTEGER NOT NULL,
    revised_at TEXT NOT NULL,
    revision_url TEXT NOT NULL,
    license_name TEXT NOT NULL,
    license_url TEXT NOT NULL,
    requires_review INTEGER NOT NULL CHECK (requires_review IN (0, 1)),
    PRIMARY KEY (language, page_id)
);

CREATE TABLE recipe_ingredient (
    recipe_id TEXT NOT NULL REFERENCES recipe(id),
    position INTEGER NOT NULL CHECK (position > 0),
    section TEXT NOT NULL,
    raw_text TEXT NOT NULL,
    ingredient_key TEXT,
    name_key TEXT,
    amount_min REAL CHECK (amount_min > 0),
    amount_max REAL CHECK (amount_max >= amount_min),
    unit TEXT,
    status TEXT NOT NULL CHECK (status IN ('mapped', 'unmapped', 'needs_review')),
    PRIMARY KEY (recipe_id, position),
    CHECK ((amount_min IS NULL) = (amount_max IS NULL)),
    CHECK ((amount_min IS NULL) = (unit IS NULL))
);

CREATE TABLE recipe_step (
    recipe_id TEXT NOT NULL REFERENCES recipe(id),
    position INTEGER NOT NULL CHECK (position > 0),
    section TEXT NOT NULL,
    text TEXT NOT NULL,
    PRIMARY KEY (recipe_id, position)
);
