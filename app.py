"""Local dinner picker with optional Ollama summaries of recipe families."""

import argparse
import json
import os
import re
from collections import defaultdict
from http import HTTPStatus
from http.client import HTTPException
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.request import Request, urlopen

from recipe_catalog import load_catalog

ROOT = Path(__file__).resolve().parent
CATALOG, ALIASES, BASICS = load_catalog()
OLLAMA_URL = "http://127.0.0.1:11434/api/chat"
DEFAULT_MODEL = "qwen3:4b"
SUMMARY_FIELDS = {"family", "base_id", "summary"}


def read_preferences(data):
    if not isinstance(data, dict):
        raise ValueError("Ожидается объект с параметрами подбора.")
    ingredients = data.get("ingredients")
    minutes = data.get("minutes")
    servings = data.get("servings")
    basics = data.get("basics", True)
    use_ai = data.get("use_ai", True)

    if not isinstance(ingredients, str) or len(ingredients) > 1000:
        raise ValueError("Укажи продукты списком до 1000 символов.")
    if type(minutes) is not int or not 5 <= minutes <= 120:
        raise ValueError("Укажи время от 5 до 120 минут.")
    if type(servings) is not int or not 1 <= servings <= 6:
        raise ValueError("Укажи от 1 до 6 порций.")
    if type(basics) is not bool or type(use_ai) is not bool:
        raise ValueError("Некорректный режим подбора.")

    names = [" ".join(word.lower().replace("ё", "е").split())
             for word in re.split(r"[,;\r\n]+", ingredients)]
    pantry = {ALIASES.get(name, name) for name in names if name}
    if not pantry:
        raise ValueError("Добавь хотя бы один продукт.")
    if basics:
        pantry.update(BASICS)
    return pantry, minutes, servings, use_ai


def match_recipes(pantry, minutes):
    families = defaultdict(list)
    for recipe in CATALOG:
        if recipe["minutes"] <= minutes and recipe["ingredients"].keys() <= pantry:
            families[recipe["family"]].append(recipe)
    return {
        family: sorted(recipes, key=lambda recipe: (recipe["minutes"], recipe["id"]))
        for family, recipes in families.items()
    }


def request_summaries(families, model):
    choices = [
        {"family": family, "recipes": [
            {key: recipe[key] for key in ("id", "title", "minutes", "ingredients", "steps")}
            for recipe in recipes
        ]}
        for family, recipes in families.items()
    ]
    schema = {
        "type": "object",
        "properties": {
            "groups": {
                "type": "array",
                "minItems": len(families),
                "maxItems": len(families),
                "items": {
                    "type": "object",
                    "properties": {
                        "family": {"type": "string", "enum": list(families)},
                        "base_id": {"type": "string", "enum": [
                            recipe["id"] for recipes in families.values() for recipe in recipes
                        ]},
                        "summary": {"type": "string", "minLength": 10, "maxLength": 300},
                    },
                    "required": sorted(SUMMARY_FIELDS),
                    "additionalProperties": False,
                },
            }
        },
        "required": ["groups"],
        "additionalProperties": False,
    }
    instruction = (
        "Ты редактор кулинарного каталога. Верни одно обобщение на каждую семью. "
        "В summary на русском в 1–2 коротких предложениях опиши общий способ приготовления "
        "и чем различаются варианты. Не выдумывай продукты или шаги и не объединяй варианты "
        "в новый рецепт. В base_id укажи ID одного простого исходного варианта из этой семьи. "
        "В family дословно верни название семьи. Верни только JSON."
    )
    payload = {
        "model": model,
        "stream": False,
        "think": False,
        "format": schema,
        "options": {"temperature": 0, "num_ctx": 8192, "num_predict": 1000},
        "messages": [
            {"role": "system", "content": instruction},
            {"role": "user", "content": json.dumps(choices, ensure_ascii=False)},
        ],
    }
    request = Request(
        OLLAMA_URL,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urlopen(request, timeout=75) as response:
        message = json.load(response)["message"]["content"]
    return validate_summaries(json.loads(message), families)


def validate_summaries(data, families):
    if not isinstance(data, dict) or set(data) != {"groups"} or not isinstance(data["groups"], list):
        raise ValueError("Неверный формат обобщений.")

    summaries = {}
    for group in data["groups"]:
        if not isinstance(group, dict) or group.keys() != SUMMARY_FIELDS:
            raise ValueError("Неверный формат карточки.")
        if any(not isinstance(value, str) for value in group.values()):
            raise ValueError("Поля карточки должны быть строками.")
        family = group["family"]
        if family not in families or family in summaries:
            raise ValueError("Неизвестная или повторная группа.")
        if group["base_id"] not in {recipe["id"] for recipe in families[family]}:
            raise ValueError("Базовый рецепт не входит в группу.")
        summary = " ".join(group["summary"].split())
        if not 10 <= len(summary) <= 300:
            raise ValueError("Неверная длина обобщения.")
        summaries[family] = (group["base_id"], summary)

    if summaries.keys() != families.keys():
        raise ValueError("Ответ модели пропустил группы.")
    return summaries


def make_selection(data, model=DEFAULT_MODEL):
    pantry, minutes, servings, use_ai = read_preferences(data)
    families = match_recipes(pantry, minutes)
    grouped = {family: recipes for family, recipes in families.items() if len(recipes) > 1}
    ai_status = "not_needed" if use_ai else "disabled"
    summaries = {}
    if use_ai and grouped:
        try:
            summaries = request_summaries(grouped, model)
            ai_status = "used"
        except (OSError, HTTPException):
            ai_status = "unavailable"
        except (ValueError, TypeError, KeyError):
            ai_status = "invalid"

    cards = []
    for family, recipes in families.items():
        base_id = recipes[0]["id"]
        summary = None
        source = "recipe"
        if family in summaries:
            base_id, summary = summaries[family]
            source = "ai"
        elif len(recipes) > 1:
            source = "group"

        common = set.intersection(*(set(recipe["ingredients"]) for recipe in recipes))
        cards.append({
            "title": family if len(recipes) > 1 else recipes[0]["title"],
            "base_id": base_id,
            "summary": summary,
            "source": source,
            "common": sorted(common - BASICS),
            "variants": [
                {
                    "id": recipe["id"],
                    "title": recipe["title"],
                    "minutes": recipe["minutes"],
                    "ingredients": [
                        {
                            "name": name,
                            "amount": f"{amount * servings / recipe['servings']:g}",
                            "unit": unit,
                        }
                        for name, (amount, unit) in recipe["ingredients"].items()
                    ],
                    "steps": recipe["steps"],
                }
                for recipe in recipes
            ],
        })
    cards.sort(key=lambda card: (card["variants"][0]["minutes"], card["title"]))
    return {"cards": cards, "servings": servings, "ai_status": ai_status}


class RecipeHandler(BaseHTTPRequestHandler):
    model = DEFAULT_MODEL

    def send_body(self, status, content_type, body):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def send_json(self, status, data):
        self.send_body(status, "application/json; charset=utf-8",
                       json.dumps(data, ensure_ascii=False).encode("utf-8"))

    def do_GET(self):
        if self.path != "/":
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        self.send_body(HTTPStatus.OK, "text/html; charset=utf-8",
                       (ROOT / "index.html").read_bytes())

    def do_POST(self):
        if self.path != "/api/recipes":
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        if self.headers.get_content_type() != "application/json":
            self.send_json(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, {"error": "Ожидается JSON."})
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= 16_384:
                raise ValueError("Некорректный размер запроса.")
            data = json.loads(self.rfile.read(size))
        except ValueError:
            self.send_json(HTTPStatus.BAD_REQUEST, {"error": "Не удалось прочитать запрос."})
            return
        try:
            result = make_selection(data, self.model)
        except ValueError as error:
            self.send_json(HTTPStatus.BAD_REQUEST, {"error": str(error)})
            return
        self.send_json(HTTPStatus.OK, result)


def main():
    parser = argparse.ArgumentParser(description="Локальный подбор рецептов на ужин.")
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--model", default=os.environ.get("DINNER_MODEL", DEFAULT_MODEL))
    args = parser.parse_args()
    RecipeHandler.model = args.model
    with ThreadingHTTPServer(("127.0.0.1", args.port), RecipeHandler) as server:
        print(f"Открой http://127.0.0.1:{server.server_port}/", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
