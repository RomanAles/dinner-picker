PRAGMA user_version = 1;

CREATE TABLE source (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    url TEXT,
    license TEXT
);

CREATE TABLE family (
    id TEXT PRIMARY KEY NOT NULL,
    name_ru TEXT NOT NULL UNIQUE,
    name_en TEXT NOT NULL UNIQUE
);

CREATE TABLE ingredient (
    id TEXT PRIMARY KEY NOT NULL,
    name_ru TEXT NOT NULL UNIQUE,
    name_en TEXT NOT NULL UNIQUE,
    is_basic INTEGER NOT NULL DEFAULT 0 CHECK (is_basic IN (0, 1))
);

CREATE TABLE ingredient_alias (
    language TEXT NOT NULL CHECK (language IN ('ru', 'en')),
    alias TEXT NOT NULL,
    ingredient_id TEXT NOT NULL REFERENCES ingredient(id),
    PRIMARY KEY (language, alias)
);

CREATE TABLE unit (
    id TEXT PRIMARY KEY NOT NULL,
    label_ru TEXT NOT NULL UNIQUE,
    label_en TEXT NOT NULL UNIQUE
);

CREATE TABLE recipe (
    id TEXT PRIMARY KEY NOT NULL,
    family_id TEXT NOT NULL REFERENCES family(id),
    source_id TEXT NOT NULL REFERENCES source(id),
    source_key TEXT NOT NULL,
    original_language TEXT NOT NULL CHECK (original_language IN ('ru', 'en')),
    title_ru TEXT NOT NULL,
    title_en TEXT NOT NULL,
    minutes INTEGER NOT NULL CHECK (minutes > 0),
    servings INTEGER NOT NULL CHECK (servings > 0),
    UNIQUE (source_id, source_key)
);

CREATE TABLE recipe_ingredient (
    recipe_id TEXT NOT NULL REFERENCES recipe(id),
    position INTEGER NOT NULL CHECK (position > 0),
    ingredient_id TEXT NOT NULL REFERENCES ingredient(id),
    amount NUMERIC NOT NULL CHECK (amount > 0),
    unit_id TEXT NOT NULL REFERENCES unit(id),
    PRIMARY KEY (recipe_id, position),
    UNIQUE (recipe_id, ingredient_id)
);

CREATE TABLE recipe_step (
    recipe_id TEXT NOT NULL REFERENCES recipe(id),
    position INTEGER NOT NULL CHECK (position > 0),
    text_ru TEXT NOT NULL,
    text_en TEXT NOT NULL,
    PRIMARY KEY (recipe_id, position)
);
