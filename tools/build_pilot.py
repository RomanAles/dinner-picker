"""Snapshot Wikibooks recipe pages and build a separate SQLite pilot."""

import argparse
import json
import random
import re
import sqlite3
import time
from collections import Counter
from contextlib import closing
from datetime import datetime, timezone
from hashlib import sha256
from itertools import batched
from pathlib import Path
from tempfile import TemporaryDirectory
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from mwparserfromhell import parse

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "catalog" / "pilot"
SOURCES = {
    "ru": ("Категория:Рецепты", "Рецепт:"),
    "en": ("Category:Recipes", "Cookbook:"),
}
INGREDIENT_HEADINGS = {
    "ingredients", "ingredient", "ингредиенты", "состав", "продукты",
    "список продуктов", "необходимые продукты",
}
METHOD_HEADINGS = {
    "procedure", "directions", "method", "preparation", "instructions", "steps", "cooking",
    "порядок приготовления", "приготовление", "способ приготовления", "технология приготовления",
    "как готовить", "как приготовить",
}
INFOBOX_NAMES = {"recipesummary", "recipe summary", "рецепт"}
LIST_ITEM = re.compile(r"^\s*[*#]+[ \t]*(.+)$")
TIME = re.compile(
    r"(?:(\d+)\s*(?:h|hr\.?|hrs\.?|hours?|ч\.?|час(?:а|ов)?)\s*)?"
    r"(?:(\d+)\s*(?:m|min\.?|mins\.?|minutes?|мин\.?|минут(?:а|ы)?))?",
    re.IGNORECASE,
)
SERVINGS = re.compile(r"(\d+)(?:\s+(?:servings?|people|порци[яий]|человек[а]?))?", re.IGNORECASE)


def wiki_api(language, agent, **params):
    host = f"https://{language}.wikibooks.org"
    query = urlencode(dict(action="query", format="json", formatversion=2, maxlag=5, **params))
    request = Request(f"{host}/w/api.php?{query}", headers={"User-Agent": agent})
    for attempt in range(4):
        time.sleep(0.25)
        try:
            with urlopen(request, timeout=30) as response:
                result = json.load(response)
                retry_after = response.headers.get("Retry-After", "")
        except HTTPError as error:
            if error.code not in {429, 500, 502, 503, 504} or attempt == 3:
                raise
            retry_after = error.headers.get("Retry-After", "")
        else:
            if "error" not in result:
                return result
            if result["error"]["code"] != "maxlag" or attempt == 3:
                raise ValueError(result["error"]["info"])
        delay = int(retry_after) if retry_after.isdecimal() else 5 * (attempt + 1)
        if delay > 180:
            raise ValueError(f"Wikibooks requested a pause of {delay} seconds. Retry later.")
        time.sleep(delay)


def list_members(language, agent):
    category, prefix = SOURCES[language]
    members = set()
    continuation = {}
    while True:
        result = wiki_api(language, agent, list="categorymembers", cmtitle=category,
                          cmtype="page", cmlimit=500, cmprop="ids|title", **continuation)
        members.update(page["pageid"] for page in result["query"]["categorymembers"]
                       if page["title"].startswith(prefix))
        continuation = result.get("continue")
        if not continuation:
            return sorted(members)


def fetch_source(language, sample_size, seed, agent, excluded):
    members = list_members(language, agent)
    available = [page_id for page_id in members if page_id not in excluded]
    if len(available) < sample_size:
        raise ValueError(f"{language}: only {len(available)} available category members.")
    selection = random.Random(seed).sample(available, sample_size)
    rights = wiki_api(language, agent, meta="siteinfo", siprop="rightsinfo")["query"]["rightsinfo"]
    if not rights.get("text") or not rights.get("url"):
        raise ValueError(f"{language}: Wikibooks did not return a site license.")

    records = {}
    for batch in batched(selection, 25):
        result = wiki_api(language, agent, pageids="|".join(map(str, batch)), prop="revisions",
                          rvprop="ids|timestamp|content", rvslots="main")
        for page in result["query"]["pages"]:
            if not page.get("revisions"):
                raise ValueError(f"{language}: no accessible revision for {page['title']}.")
            revision = page["revisions"][0]
            records[page["pageid"]] = {
                "page_id": page["pageid"],
                "title": page["title"],
                "revision": revision["revid"],
                "timestamp": revision["timestamp"],
                "wikitext": revision["slots"]["main"]["content"],
            }
        if len(records) % 100 == 0:
            print(f"{language}: fetched {len(records)}/{sample_size}", flush=True)
    if set(records) != set(selection):
        raise ValueError(f"{language}: the API did not return the entire sample.")
    return {
        "category": SOURCES[language][0],
        "category_size": len(members),
        "site_license": rights,
        "pages": [records[page_id] for page_id in selection],
    }


def write_json(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def fetch_snapshot(output, sample_sizes, seed, contact, exclude_paths):
    output.mkdir(parents=True, exist_ok=True)
    agent = f"DinnerCatalogPilot/0.2 ({contact or 'local recipe catalog pilot'})"
    snapshot = {
        "version": 1,
        "seed": seed,
        "fetched_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "sources": {},
    }
    excluded = {language: set() for language in SOURCES}
    for path in exclude_paths:
        previous = json.loads(path.read_bytes())
        for language, source in previous["sources"].items():
            excluded[language].update(page["page_id"] for page in source["pages"])
    for language in SOURCES:
        source = fetch_source(language, sample_sizes[language], seed, agent, excluded[language])
        snapshot["sources"][language] = source
        print(f"{language}: {len(source['pages'])} of {source['category_size']} pages", flush=True)
    with TemporaryDirectory(dir=output) as temp:
        candidate = Path(temp) / "snapshot.json"
        write_json(candidate, snapshot)
        candidate.replace(output / candidate.name)


def key(text):
    return " ".join(str(text).replace("_", " ").casefold().split())


def clean_text(code, issues):
    for tag in code.filter_tags():
        if key(tag.tag) in {"ref", "references", "gallery"} and code.contains(tag):
            code.remove(tag)
    for link in code.filter_wikilinks():
        if not code.contains(link):
            continue
        target = str(link.title).strip()
        namespace, separator, title = target.partition(":")
        if separator and key(namespace) in {"file", "image", "category", "файл", "изображение", "категория"}:
            code.remove(link)
        elif link.text is None and separator and key(namespace) in {"cookbook", "рецепт", "w"}:
            link.text = title
    for template in code.filter_templates(recursive=False):
        if key(template.name) in {"convert", "cvt"} and template.has(1) and template.has(2):
            amount, unit = (str(template.get(i).value).strip() for i in (1, 2))
            if re.fullmatch(r"\d+(?:[.,]\d+)?", amount) and re.fullmatch(r"[a-zA-Z]+", unit) and unit not in {"to", "and", "or", "by"}:
                code.replace(template, f"{amount} {unit}")
                continue
        issues.add("unexpanded_template")
    return " ".join(code.strip_code().split())


def recipe_fields(code, issues):
    fields = {}
    for template in code.filter_templates(recursive=False):
        if key(template.name) in INFOBOX_NAMES:
            for param in template.params:
                fields[key(param.name)] = str(param.value).strip()
            break

    def value(*names):
        raw = next((fields[name] for name in names if fields.get(name)), "")
        return clean_text(parse(raw.replace("<br", " <br")), issues)

    time_text = value("общее время", "total time", "время", "time")
    named_servings = value("порций", "servings", "serves")
    servings_text = named_servings or value("yield")
    match = TIME.fullmatch(time_text)
    minutes = (int(match[1] or 0) * 60 + int(match[2] or 0)) if match else None
    match = SERVINGS.fullmatch(servings_text)
    servings = int(match[1]) if match and (named_servings or not servings_text.isdecimal()) else None
    return time_text, minutes or None, servings_text, servings or None


def section_kind(heading):
    label = key(heading.split("(", 1)[0].strip(" :"))
    if label in INGREDIENT_HEADINGS or label.startswith((
        "ingredients for ", "ингредиенты для ", "компоненты для приготовления ",
    )):
        return "ingredients"
    if label in METHOD_HEADINGS:
        return "steps"
    return None


def item_lines(body, kind):
    paragraph = []
    for line in body.splitlines():
        item = LIST_ITEM.match(line)
        if kind == "steps" and paragraph and (item or not line.strip()):
            yield " ".join(paragraph)
            paragraph.clear()
        if item:
            yield item[1]
        elif kind == "steps" and line.strip():
            paragraph.append(line)
    if paragraph:
        yield " ".join(paragraph)


def recipe_sections(code, issues):
    parts = {"ingredients": [], "steps": []}
    active_kind = None
    active_level = 0
    roots = Counter()
    for section in code.get_sections(flat=True, include_lead=False, include_headings=True):
        heading = section.nodes[0]
        title = clean_text(parse(str(heading.title)), issues)
        kind = section_kind(title)
        if kind and (kind != active_kind or heading.level <= active_level):
            active_kind, active_level = kind, heading.level
            roots[kind] += 1
        elif not kind and heading.level <= active_level:
            active_kind = None
        if not active_kind:
            continue
        body = "".join(map(str, section.nodes[1:]))
        items = [clean_text(parse(line), issues) for line in item_lines(body, active_kind)]
        items = [item for item in items if item]
        if items:
            parts[active_kind].append({"heading": title, "level": heading.level, "items": items})
        elif active_kind == "ingredients" and clean_text(parse(body), issues):
            issues.add("unstructured_ingredients")

    for kind, blocks in parts.items():
        if not blocks:
            issues.add(f"missing_{kind}")
        if roots[kind] > 1:
            issues.add(f"multiple_{kind}_sections")
    return parts


def parse_page(page):
    code = parse(page["wikitext"])
    issues = set()
    if code.filter_external_links() or code.filter_tags(matches=lambda tag: key(tag.tag) == "ref"):
        issues.add("references_require_review")
    parts = recipe_sections(code, issues)
    time_text, minutes, servings_text, servings = recipe_fields(code, issues)
    if minutes is None:
        issues.add("unparsed_time" if time_text else "missing_time")
    if servings is None:
        issues.add("unparsed_servings" if servings_text else "missing_servings")
    return parts, time_text, minutes, servings_text, servings, sorted(issues)


PILOT_SCHEMA = """
CREATE TABLE source (
    language TEXT PRIMARY KEY NOT NULL,
    category TEXT NOT NULL,
    category_size INTEGER NOT NULL,
    license_name TEXT NOT NULL,
    license_url TEXT NOT NULL
);
CREATE TABLE page (
    language TEXT NOT NULL REFERENCES source(language),
    page_id INTEGER NOT NULL,
    title TEXT NOT NULL,
    revision INTEGER NOT NULL,
    revised_at TEXT NOT NULL,
    revision_url TEXT NOT NULL,
    ingredients_json TEXT NOT NULL,
    steps_json TEXT NOT NULL,
    time_text TEXT NOT NULL,
    minutes INTEGER,
    servings_text TEXT NOT NULL,
    servings INTEGER,
    issues_json TEXT NOT NULL,
    PRIMARY KEY (language, page_id)
);
"""


def import_source(connection, language, source):
    rights = source["site_license"]
    connection.execute("INSERT INTO source VALUES (?, ?, ?, ?, ?)",
                       (language, source["category"], source["category_size"], rights["text"], rights["url"]))
    counts = dict.fromkeys(("sampled", "ingredients", "steps", "both",
                            "time", "servings", "all_four"), 0)
    issue_counts = Counter()
    for page in source["pages"]:
        parts, time_text, minutes, servings_text, servings, issues = parse_page(page)
        ingredients = bool(parts["ingredients"])
        steps = bool(parts["steps"])
        counts["sampled"] += 1
        counts["ingredients"] += ingredients
        counts["steps"] += steps
        counts["both"] += ingredients and steps
        counts["time"] += minutes is not None
        counts["servings"] += servings is not None
        counts["all_four"] += ingredients and steps and minutes is not None and servings is not None
        issue_counts.update(issues)
        revision_url = f"https://{language}.wikibooks.org/w/index.php?oldid={page['revision']}"
        connection.execute(
            "INSERT INTO page VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (language, page["page_id"], page["title"], page["revision"], page["timestamp"],
             revision_url, json.dumps(parts["ingredients"], ensure_ascii=False),
             json.dumps(parts["steps"], ensure_ascii=False), time_text, minutes,
             servings_text, servings, json.dumps(issues)),
        )
    return {
        "category_size": source["category_size"],
        "counts": counts,
        "issues": dict(sorted(issue_counts.items())),
    }


def build_pilot(output):
    content = (output / "snapshot.json").read_bytes()
    snapshot = json.loads(content)
    if snapshot.get("version") != 1 or snapshot["sources"].keys() != SOURCES.keys():
        raise ValueError("Unsupported or incomplete pilot snapshot.")
    report = {
        "snapshot_sha256": sha256(content).hexdigest(),
        "fetched_at": snapshot["fetched_at"],
        "seed": snapshot["seed"],
        "grain": "one sampled category page revision",
        "sources": {},
    }
    with TemporaryDirectory(dir=output) as temp:
        database = Path(temp) / "pilot.sqlite"
        with closing(sqlite3.connect(database)) as connection:
            connection.execute("PRAGMA foreign_keys = ON")
            connection.executescript(PILOT_SCHEMA)
            with connection:
                for language, source in snapshot["sources"].items():
                    report["sources"][language] = import_source(connection, language, source)
            if connection.execute("PRAGMA integrity_check").fetchone() != ("ok",):
                raise ValueError("SQLite integrity check failed.")
        report["sqlite_sha256"] = sha256(database.read_bytes()).hexdigest()
        summary = Path(temp) / "report.json"
        write_json(summary, report)
        database.replace(output / database.name)
        summary.replace(output / summary.name)

    for language, source in report["sources"].items():
        print(f"{language}: {source['counts']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=OUTPUT)
    actions = parser.add_subparsers(dest="action", required=True)
    fetch = actions.add_parser("fetch", help="download a reproducible sample to snapshot.json")
    fetch.add_argument("--per-language", type=int, default=75, choices=range(1, 1501), metavar="1..1500")
    fetch.add_argument("--ru-count", type=int, choices=range(1, 1501), metavar="1..1500")
    fetch.add_argument("--en-count", type=int, choices=range(1, 1501), metavar="1..1500")
    fetch.add_argument("--seed", type=int, default=20261110)
    fetch.add_argument("--contact", help="operator contact to include in Wikimedia's User-Agent")
    fetch.add_argument("--exclude", type=Path, action="append", default=[], help="local snapshot to exclude")
    actions.add_parser("build", help="build pilot.sqlite and report.json from the saved snapshot")
    args = parser.parse_args()
    try:
        if args.action == "fetch":
            fetch_snapshot(args.output_dir, {"ru": args.ru_count or args.per_language,
                                            "en": args.en_count or args.per_language},
                           args.seed, args.contact, args.exclude)
        else:
            build_pilot(args.output_dir)
    except (OSError, ValueError, KeyError, sqlite3.Error) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
