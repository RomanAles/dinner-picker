"""Find Commons dish photos and build a reproducible offline Android photo pack."""

import argparse
import html
import json
import re
import sqlite3
import time
import unicodedata
from collections import Counter
from contextlib import closing
from hashlib import sha256
from itertools import batched
from pathlib import Path
from tempfile import TemporaryDirectory
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlparse
from urllib.request import Request, urlopen
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

from tools.build_pilot import ROOT, write_json
from tools.tag_catalog import ANDROID_DATA

WORK = ROOT / "dist-data" / "recipe-photos"
CURATION = ROOT / "catalog" / "photo_sources.json"
SELECTION = ROOT / "catalog" / "recipe_photo_selection.json"
PACK = ANDROID_DATA / "recipe-photos.zip"
AGENT = "DinnerPickerCatalog/1.3 (https://github.com/RomanAles/dinner-picker)"
IMAGE = re.compile(
    r"(?:\[\[(?:file|image|файл|изображение):|\|\s*(?:image(?:_name)?|изображение)\s*=\s*)"
    r"([^\]\[|={}<>\n]+?\.(?:jpe?g|png|webp))(?:[ |\]\n}]|$)", re.I
)
BITMAP = re.compile(r"\.(?:jpe?g|png|webp)$", re.I)
SIMPLIFY = re.compile(
    r"\b(?:easy|simple|quick|homemade|basic|classic|traditional|light|fluffy|rich|portable|"
    r"slow cooker|crock pot|microwave|instant|vegan|vegetarian|gluten free|no bake|low fat)\b"
)
NON_DISH = re.compile(
    r"\b(?:rosa|illustration|map|packaging|advert|label|stamp|logo|tree|plant|flower|seeds?|"
    r"poster|storefront|recipe card|menu)\b"
)
FAMILY_PATTERNS = {
    "Soup": r"broth|stock|chowder|soup|consomme|caldo|sopa",
    "Stew": r"stew|ragout|tagine|gumbo|goulash",
    "Curry": r"curry|masala|korma|vindaloo|dal|dahl",
    "Pilaf": r"pilaf|plov|paella|biryani|jambalaya",
    "rice": r"rice|arroz|quinoa|couscous|bulgur",
    "Porridge": r"porridge|oatmeal|grits|polenta|kasha|pap",
    "pasta": r"pasta|macaroni|spaghetti|linguine|fettuccine|pappardelle|penne|gnocchi|ravioli",
    "noodles": r"noodles?|ramen|mein|udon|rapokki",
    "Pizza": r"pizza|calzone",
    "dumplings": r"dumplings?|pierogi|pelmeni|wontons?|buuz|gyoza",
    "Omelette": r"omelettes?|omelets?|frittata|egg bake",
    "eggs": r"eggs?|scramble",
    "Mashed potato": r"mash(?:ed)?|colcannon|potato snow",
    "Baked potato": r"potato(?:es)?|yams?|latkes?",
    "Salad": r"salad|coleslaw|tabbouleh",
    "Casserole": r"casserole|gratin|hotdish",
    "Fruitcake": r"fruit ?cake|christmas cake|brack",
    "Chocolate cake": r"chocolate.*cake|fudge cake",
    "Cake": r"cake|gateau|sachertorte|brownies?|sponge",
    "Cookie": r"cookies?|biscuits?|shortbread|crinkles|flapjacks?|biscotti|meringues?",
    "Scone": r"scones?",
    "Muffin": r"muffins?|cupcakes?",
    "pie": r"pies?|cobbler|crumble|strudel|turnovers?|pie crust",
    "Tart": r"tarts?|pastry|choux|eclairs?|profiteroles?",
    "Pancake": r"pancakes?|crepes?|oatcakes?|griddle ?cakes?|hotcakes?",
    "Waffle": r"waffles?",
    "bread": r"breads?|flatbread|rolls?|buns?|bagels?|naan|focaccia|chapati|roti|dough|bannock",
    "Sandwich": r"sandwich(?:es)?|toast|wraps?|panin[io]|bruschetta|baguette",
    "Hamburger": r"burgers?|hamburgers?|sliders?",
    "Taco": r"tacos?|burritos?|tostadas?|quesadillas?|enchiladas?",
    "Meatloaf": r"meatloaf|meatballs?",
    "Roast beef": r"beef|steak|brisket|veal|pot roast",
    "Pulled pork": r"pork|bacon|ham|sausages?|ribs?|trotters?|barbecue|chops?",
    "Lamb and mutton": r"lamb|mutton",
    "Roast chicken": r"chicken|turkey|poultry|wings?|duck|goose",
    "fish": r"fish|salmon|tuna|sardines?|cod|trout|catfish|tilapia|anchov(?:y|ies)|mackerel|herring",
    "seafood": r"seafood|prawns?|shrimp|lobster|crab|scallops?|oysters?|mussels?",
    "Ice cream": r"ice cream|gelato|sorbet|ice pops?|sherbet|frozen",
    "Pudding": r"puddings?|custard|flan|mousse|dessert|trifle|jelly|jello|panna cotta",
    "Fruit salad": r"apple|apples|peaches?|plums?|berries|berry|banana|orange|pineapple|fruit",
    "Chocolate": r"chocolate|cocoa|caramel|fudge|candy|pralines?|toffee",
    "Coffee": r"coffee|espresso|latte|mocha",
    "Black tea": r"tea|chai|tisane",
    "drink": r"drink|punch|soda|juice|lemonade|cocktail|wine|cider|mead|sangria|switchel",
    "milk drink": r"milk|horchata|shake",
    "Smoothie": r"smoothies?",
    "cream": r"cream|curd|buttercream|frosting|icing|glaze",
    "sauce": r"sauce|dressing|vinaigrette|dip|gravy|salsa|spread|marinade|syrup",
    "Chutney": r"chutney|relish",
    "Pickled cucumber": r"pickles?|pickled|kimchi|vinegar",
    "seasoning": r"seasoning|spice|rub|powder|extract",
    "Baked beans": r"beans?|lentils?|chickpeas?|peas|legumes?|fufu",
    "nuts": r"nuts?|peanuts?|almonds?|walnuts?",
    "plantain": r"plantains?",
    "vegetable": r"vegetables?|eggplant|cabbage|spinach|broccoli|cauliflower|carrots?|zucchini|"
                 r"asparagus|mushrooms?|pumpkin|squash|peppers?|tomatoes?|cucumber|celery|okra"
}


def request(url):
    for attempt in range(3):
        try:
            return urlopen(Request(url, headers={"User-Agent": AGENT}), timeout=20)
        except HTTPError as error:
            if error.code not in {429, 500, 502, 503, 504} or attempt == 2:
                raise
            retry_after = error.headers.get("Retry-After", "")
            delay = int(retry_after) if retry_after.isdecimal() else 3 * (attempt + 1)
            if delay > 120:
                raise ValueError(f"Wikimedia requested a {delay}s pause. Resume the cached run later.") from error
        except URLError:
            if attempt == 2:
                raise
            delay = 3 * (attempt + 1)
        time.sleep(delay)


def api(host, **params):
    query = urlencode(dict(action="query", format="json", formatversion=2, maxlag=5, **params))
    for attempt in range(4):
        time.sleep(0.35)
        with request(f"https://{host}/w/api.php?{query}") as response:
            result = json.load(response)
        error = result.get("error")
        if not error:
            return result.get("query", {})
        busy = error["code"] == "maxlag" or "too busy" in error["info"].lower()
        if not busy or attempt == 3:
            raise ValueError(error["info"])
        time.sleep(8 * (attempt + 1))


def plain(value):
    return " ".join(html.unescape(re.sub(r"<[^>]*>", " ", value)).split())


def normalize(value):
    folded = unicodedata.normalize("NFKD", value.casefold())
    return " ".join(re.findall(r"[^\W_]+", "".join(c for c in folded if not unicodedata.combining(c))))


def file_key(value):
    return value.removeprefix("File:").replace("_", " ").strip().casefold()


def catalog_rows(database):
    with closing(sqlite3.connect(database.resolve().as_uri() + "?mode=ro", uri=True)) as db:
        return db.execute("""
            SELECT id, title_en, title_ru, NULL FROM recipe
            UNION ALL
            SELECT id, title_en, title_ru, revision_url FROM offline_recipe
            ORDER BY id
        """).fetchall()


def snapshot_photos(snapshots, rows):
    revisions = {rid: url.rsplit("=", 1)[1] for rid, _, _, url in rows if url}
    result = {}
    for snapshot in snapshots:
        for language, source in json.loads(snapshot.read_bytes())["sources"].items():
            for page in source["pages"]:
                rid = f"wikibooks:{language}:{page['page_id']}"
                if str(page["revision"]) == revisions.get(rid):
                    files = IMAGE.findall(page["wikitext"])
                    if files:
                        result[rid] = ["File:" + name.replace("_", " ").strip() for name in files]
    return result


def wikipedia_photos(rows):
    result = {}
    for batch in batched(rows, 35):
        titles = {normalize(title): rid for rid, title, *_ in batch}
        query = api("en.wikipedia.org", titles="|".join(title for _, title, *_ in batch),
                    prop="pageimages", piprop="name", pilicense="free")
        for page in query.get("pages", []):
            title = normalize(page["title"])
            name = page.get("pageimage", "")
            if title in titles and page["ns"] == 0 and BITMAP.search(name):
                result[titles[title]] = "File:" + name.replace("_", " ")
    return result


def search_names(title):
    title = re.sub(r"\s+(?:[IVX]{1,4}|[1-9])$", "", title).strip()
    aliases = re.findall(r"\(([^()]*)\)", title)
    core = re.sub(r"\s*\([^()]*\)", "", title)
    return [name.strip() for name in dict.fromkeys([core, *aliases]) if name.strip()]


def commons_search(name, limit=8):
    cleaned = name.replace('"', "").replace("|", " ").strip()
    if not cleaned:
        return []
    query = api("commons.wikimedia.org", list="search", srnamespace=6, srlimit=limit,
                srprop="", srsearch=f'"{cleaned}" filetype:bitmap')
    return [row["title"] for row in query.get("search", []) if BITMAP.search(row["title"])]


def cached(path):
    return json.loads(path.read_bytes()) if path.is_file() else {}


def search_catalog(rows, snapshots, work):
    work.mkdir(parents=True, exist_ok=True)
    source = snapshot_photos(snapshots, rows)
    write_json(work / "source.json", source)
    wiki_file = work / "wikipedia.json"
    wiki = cached(wiki_file)
    if not wiki_file.is_file():
        wiki = wikipedia_photos([row for row in rows if row[0] not in source])
        write_json(wiki_file, wiki)
    print(f"{len(source)} recipe pages with images; {len(wiki)} exact Wikipedia article titles.", flush=True)
    path = work / "search.json"
    matches = cached(path)
    try:
        for rid, title, russian, _ in rows:
            if rid in matches or rid in source or rid in wiki:
                continue
            options = {}
            for name in search_names(title):
                found = commons_search(name)
                if found:
                    options[name] = found
                    break
            if not options and rid.startswith("wikibooks:ru:"):
                for name in search_names(russian):
                    found = commons_search(name)
                    if found:
                        options[name] = found
                        break
            matches[rid] = options
            if len(matches) % 20 == 0:
                write_json(path, matches)
                print(f"Searched {len(matches)} other recipes; {sum(map(bool, matches.values()))} have candidates.", flush=True)
    finally:
        write_json(path, matches)


def name_key(value):
    return " ".join(SIMPLIFY.sub(" ", normalize(value)).split())


def stems(value):
    words = name_key(value).split()
    return {word.removesuffix("s") if len(word) > 3 else word
            for word in words if word not in {"and", "with", "the", "de", "of", "for", "or", "in", "a", "style"}}


def filename(name):
    return name if name.startswith("File:") else f"File:{name}"


def dietary_variant(title, file):
    variants = ("vegan", "vegetarian", "gluten free", "low fat", "lower sugar")
    return any(variant in normalize(title) and variant not in normalize(file) for variant in variants)


def title_matches(title, file):
    short = normalize(file.removeprefix("File:").rsplit(".", 1)[0])
    desired = name_key(title)
    if not desired or NON_DISH.search(short):
        return False
    joined = short.replace(" ", "")
    return (f" {desired} " in f" {short} " or
            (len(desired.split()) > 1 and desired.replace(" ", "") in joined))


def file_candidates(title, file_names):
    result = []
    expected = stems(title)
    for name in file_names:
        short = normalize(name.removeprefix("File:").rsplit(".", 1)[0])
        if NON_DISH.search(short) or not expected:
            continue
        common = len(expected & stems(short))
        if title_matches(title, name):
            match = "illustrative" if dietary_variant(title, name) else "dish"
            result.append((3 + common, name, match, "file_title"))
        elif common >= 2 and common * 3 >= 2 * len(expected):
            result.append((common / len(expected), name, "illustrative", "related_dish"))
    return [(name, match, reason) for _, name, match, reason in sorted(result, reverse=True)]


def fallback_family(title, tags, families):
    words = normalize(title)
    descriptors = (
        (r"\b(?:spice rub|seasoning|steak rub|spiced flour)\b", "seasoning"),
        (r"\b(?:marinade|dressing|sauce|glaze)\b$", "sauce"),
        (r"\b(?:smoothie|milkshake|lassi)\b", "Smoothie"),
        (r"\b(?:pasta salad|chicken salad|cucumber salad)\b", "Salad"),
        (r"\b(?:bread pudding|rice pudding)\b", "Pudding"),
    )
    for pattern, family in descriptors:
        if re.search(pattern, words):
            return family
    # Specific dishes take precedence over broad food terms in the title.
    keys = sorted((name for name in families if len(normalize(name).split()) > 1), key=len, reverse=True)
    for name in keys:
        if f" {normalize(name)} " in f" {words} ":
            return name
    for name, patterns in FAMILY_PATTERNS.items():
        if re.search(rf"\b(?:{patterns})\b", words):
            return name
    for key, family in {"soup": "soup", "salad": "Salad", "baking": "bread",
                        "dessert": "Pudding", "drink": "drink", "side": "vegetable"}.items():
        if key in tags:
            return family
    return "mixed"


def catalog_dish_types(database):
    with closing(sqlite3.connect(database.resolve().as_uri() + "?mode=ro", uri=True)) as db:
        types = {}
        for rid, tag in db.execute("SELECT recipe_id,tag_id FROM recipe_tag WHERE axis_id='dish_type'"):
            types.setdefault(rid, set()).add(tag)
        return types


def choices(database, rows, work):
    search = cached(work / "search.json")
    source = cached(work / "source.json")
    wikipedia = cached(work / "wikipedia.json")
    families = cached(work / "families.json")
    curation = cached(CURATION)
    overrides = curation["recipes"]
    families.update(curation["fallbacks"])
    exact_families = {name_key(key): value for key, value in families.items() if key not in curation["fallbacks"]}
    illustrative = {file_key(name) for name in curation["source_illustrations"]}
    dish_types = catalog_dish_types(database)
    result = {}
    for rid, title, _, _ in rows:
        options = []
        if rid in overrides:
            name, match = overrides[rid]
            options.append((filename(name), match, "curated"))
        for name in source.get(rid, []):
            match = "illustrative" if file_key(name) in illustrative else "dish"
            options.append((name, match, "recipe_source"))
        if name := wikipedia.get(rid):
            options.append((name, "dish", "article_title"))
        normalized_title = name_key(search_names(title)[0])
        if name := exact_families.get(normalized_title):
            match = "illustrative" if dietary_variant(title, name) else "dish"
            options.append((filename(name), match, "dish_title"))
        for query, file_names in search.get(rid, {}).items():
            options.extend(file_candidates(query, file_names)[:3])
        family = fallback_family(title, dish_types.get(rid, ()), families)
        if family in families:
            options.append((filename(families[family]), "illustrative", "category:" + family))
        options.append((filename(families["mixed"]), "illustrative", "category:mixed"))
        result[rid] = list(dict.fromkeys(options))
    for key, (name, match) in overrides.items():
        if key.startswith("collection:"):
            result[key] = [(filename(name), match, "curated")]
    return result


def license_values(metadata):
    name = plain(metadata.get("LicenseShortName", {}).get("value", ""))
    url = plain(metadata.get("LicenseUrl", {}).get("value", "")).replace("http://", "https://", 1)
    match = re.fullmatch(r"CC\s*(BY(?:-SA)?|0)(?:\s+[1-4]\.\d)?(?:\s+[A-Za-z]{2,})?", name, re.I)
    if match:
        if match[1] == "0" and not url:
            url = "https://creativecommons.org/publicdomain/zero/1.0/"
        if urlparse(url).hostname == "creativecommons.org" and url.startswith("https://"):
            return name, url
    elif name.casefold() in {"public domain", "pdm"}:
        return name, "https://creativecommons.org/publicdomain/mark/1.0/"
    return None


def read_image_metadata(page, authors):
    values = page.get("imageinfo")
    if not values:
        return None
    image = values[0]
    metadata = image.get("extmetadata", {})
    license_info = license_values(metadata)
    title = page["title"].removeprefix("File:")
    author = authors.get(title) or plain(metadata.get("Artist", {}).get("value", ""))
    source = image.get("descriptionurl", "")
    thumb = image.get("thumburl", "")
    if not license_info or not thumb or urlparse(source).hostname != "commons.wikimedia.org":
        return None
    if not author and license_info[0].upper().startswith("CC BY"):
        return None
    return {
        "title": title,
        "author": author or "Unknown",
        "source_url": source,
        "license": license_info[0],
        "license_url": license_info[1],
        "thumb_url": thumb,
        "large_url": image.get("responsiveUrls", {}).get("2", thumb),
    }


def fetch_metadata(names, work):
    path = work / "image-metadata.json"
    credits = cached(path)
    authors = cached(CURATION)["authors"]
    author_reviews = {filename(name) for name in authors}
    remaining = sorted(set(names).difference(credits) | {name for name in names & author_reviews if not credits.get(name)})
    for batch in batched(remaining, 20):
        query = api("commons.wikimedia.org", titles="|".join(batch), redirects=1, prop="imageinfo",
                    iiprop="url|extmetadata", iiurlwidth=330,
                    iiextmetadatafilter="LicenseShortName|LicenseUrl|Artist")
        aliases = {file_key(item["from"]): file_key(item["to"])
                   for key in ("normalized", "redirects") for item in query.get(key, [])}
        pages = {file_key(page["title"]): read_image_metadata(page, authors) for page in query.get("pages", [])}
        for name in batch:
            key = file_key(name)
            while key in aliases and aliases[key] != key:
                key = aliases[key]
            credits[name] = pages.get(key)
        write_json(path, credits)
    return credits


def image_extension(data):
    if data.startswith(b"\xff\xd8\xff") and data.endswith(b"\xff\xd9"):
        return ".jpg"
    if data.startswith(b"\x89PNG\r\n\x1a\n") and b"IEND" in data[-16:]:
        return ".png"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP" and int.from_bytes(data[4:8], "little") == len(data) - 8:
        return ".webp"
    raise ValueError("Invalid or unsupported image content.")


def download_photo(credit, large, work):
    url = credit["large_url" if large else "thumb_url"]
    origin = urlparse(url)
    if origin.scheme != "https" or origin.hostname not in {"thumb.wikimedia.org", "upload.wikimedia.org"}:
        raise ValueError(f"Unexpected image URL: {url}")
    cache = work / "images"
    cache.mkdir(exist_ok=True)
    path = cache / sha256(url.encode()).hexdigest()
    if path.is_file():
        data = path.read_bytes()
        image_extension(data)
        return data
    time.sleep(0.13)
    with request(url) as response:
        data = response.read(768 * 1024 + 1)
    if len(data) > 768 * 1024:
        raise ValueError("Thumbnail exceeds the size limit.")
    image_extension(data)
    path.write_bytes(data)
    return data


def make_photo_pack(database, options, destination, selection_path, work):
    credits = fetch_metadata({option[0] for values in options.values() for option in values}, work)
    recipes = {}
    files = {}
    selection = {}
    failed = set()
    downloaded = {}
    destination.parent.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(dir=destination.parent) as stage:
        archive = Path(stage) / destination.name
        with ZipFile(archive, "w", compression=ZIP_DEFLATED, compresslevel=6) as pack:
            for rid, candidates in options.items():
                for name, match, reason in candidates:
                    credit = credits.get(name)
                    large = match == "collection"
                    if credit is None:
                        continue
                    key = credit["large_url" if large else "thumb_url"]
                    if key in failed:
                        continue
                    if key not in downloaded:
                        try:
                            data = download_photo(credit, large, work)
                        except (HTTPError, URLError, ValueError) as error:
                            print(f"Skipping {name}: {error}", flush=True)
                            failed.add(key)
                            continue
                        digest = sha256(data).hexdigest()
                        file = digest[:24] + image_extension(data)
                        downloaded[key] = file
                        if file not in files:
                            pack.writestr(ZipInfo(file), data)
                            files[file] = {field: value for field, value in credit.items()
                                           if field not in {"thumb_url", "large_url"}}
                            files[file]["sha256"] = digest
                    recipes[rid] = {"file": downloaded[key], "match": match, "reason": reason}
                    selection[rid] = [name, match, reason]
                    break
                if rid not in recipes:
                    raise ValueError(f"No reusable photo for {rid}. Review the candidates and retry.")
                if len(recipes) % 50 == 0:
                    print(f"Packaged {len(recipes)}/{len(options)} photo assignments.", flush=True)
            index = {"version": 1, "recipes": recipes, "files": files}
            pack.writestr(ZipInfo("credits.json"), json.dumps(index, ensure_ascii=False, sort_keys=True),
                          compress_type=ZIP_DEFLATED)
        coverage = verify_pack(database, archive)
        archive.replace(destination)
    if selection_path:
        write_json(selection_path, selection)
    print(json.dumps(coverage, ensure_ascii=False, indent=2), flush=True)


def verify_pack(database, archive):
    ids = {row[0] for row in catalog_rows(database)}
    with ZipFile(archive) as pack:
        index = json.loads(pack.read("credits.json"))
        recipes, files = index["recipes"], index["files"]
        if index["version"] != 1 or set(recipes) != ids | {"collection:cozy-dinners"}:
            raise ValueError("Photo assignments do not cover the Android catalog.")
        if {value["file"] for value in recipes.values()} != set(files):
            raise ValueError("Photo index does not agree with its image files.")
        if set(pack.namelist()) != set(files) | {"credits.json"} or pack.testzip():
            raise ValueError("Photo archive is corrupt or has unexpected entries.")
        if any(recipe["match"] not in {"dish", "illustrative"} for rid, recipe in recipes.items() if rid in ids):
            raise ValueError("Unexpected recipe photo match type.")
        for name, credit in files.items():
            data = pack.read(name)
            if (sha256(data).hexdigest() != credit["sha256"] or not name.endswith(image_extension(data))
                    or not all(credit[field] for field in ("title", "author", "license", "license_url", "source_url"))):
                raise ValueError(f"Invalid image or attribution: {name}")
        return {
            "recipes": len(ids),
            "files": len(files),
            "match": dict(Counter(recipes[rid]["match"] for rid in sorted(ids))),
            "sources": dict(Counter(recipes[rid]["reason"].split(":")[0] for rid in sorted(ids))),
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=ANDROID_DATA / "recipes.sqlite")
    parser.add_argument("--work", type=Path, default=WORK)
    parser.add_argument("--snapshot", type=Path, action="append")
    parser.add_argument("--mode", choices=("search", "select", "package", "verify"), default="search")
    parser.add_argument("--selection", type=Path, default=SELECTION)
    parser.add_argument("--pack", type=Path, default=PACK)
    args = parser.parse_args()
    snapshots = args.snapshot or sorted((ROOT / "catalog" / "pilot").rglob("snapshot.json"))
    rows = catalog_rows(args.catalog)
    if args.mode == "search":
        search_catalog(rows, snapshots, args.work)
    elif args.mode == "select":
        make_photo_pack(args.catalog, choices(args.catalog, rows, args.work), args.pack, args.selection, args.work)
    elif args.mode == "package":
        selected = {rid: [option] for rid, option in cached(args.selection).items()}
        make_photo_pack(args.catalog, selected, args.pack, None, args.work)
    else:
        print(json.dumps(verify_pack(args.catalog, args.pack), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
