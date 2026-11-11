"""Conservative discovery labels from the title and original recipe instructions."""

import re
from collections import defaultdict


RULE_VERSION = 1

# Each expression describes a dish in its source language. Order matters for
# standalone components, whose names can contain another dish (pizza sauce).
DISH_TYPES = {
    "sauce": (r"^(?:sauce|gravy|dressing)\b|\b(?:sauce|gravy|dressing)$",
              r"^(?:соус|подлива|заправка)\b|\b(?:соус|подлива|заправка)$"),
    "condiment": (r"\b(?:seasoning|spice mix|marinade|frosting|icing|stock|broth|filling)$",
                  r"^(?:маринад|приправа|глазурь|начинка|бульон)\b"),
    "drink": (r"\b(?:lemonade|smoothie|milkshake|iced tea|coffee|drink|cocktail)s?$",
              r"^(?:лимонад|смузи|напиток|компот|квас|кофе|чай|коктейль)\b|\bкоктейль$"),
    "soup": (r"\b(?:soups?|chowder|bisque|gazpacho)\b",
             r"\b(?:суп(?:ы|а|у|ом|чик\w*)?|борщ\w*|щи|окрошк\w*|солянк\w*|рассольник\w*|уха)\b"),
    "salad": (r"\bsalads?(?=$| with | of | in )|^salad\b", r"\bсалат$|^салат\b"),
    "baking": (r"\b(?:bread|buns?|biscuits?|cakes?|cupcakes?|muffins?|cookies?|pies?|scones?|pizza|brownies)\b",
               r"\b(?:хлеб|булоч\w*|булк\w*|печень\w*|пирог\w*|пирожк\w*|пицц\w*|торт\w*|кекс\w*|маффин\w*)\b"),
    "dessert": (r"\b(?:cookies?|cupcakes?|cakes?|muffins?|brownies|ice cream|sorbet|fudge|pudding|meringue|cheesecake)\b",
                r"\b(?:десерт\w*|печень\w*|торт\w*|кекс\w*|маффин\w*|морожено\w*|сорбет\w*|чизкейк\w*|безе)\b"),
    "appetizer": (r"\b(?:appetizers?|canap[eé]s?|bruschetta|croquettes?|deviled eggs|onion rings|stuffed mushrooms)\b|\bdip$",
                  r"\b(?:закуск\w*|канапе|брускетт\w*|крокет\w*|луковые кольца)\b"),
    "pasta": (r"\b(?:pasta|spaghetti|macaroni|lasagn[ae]|linguine|fettuccine|ravioli|penne)\b",
              r"\b(?:макарон\w*|спагетти|лазан\w*|равиоли|фетучини)\b|^паста\b"),
    "porridge": (r"\bporridge\b|\boatmeal$|^(?:baked )?oatmeal\b",
                 r"\b(?:каша|овсянка)\b"),
    "stew": (r"\b(?:stew|goulash|tagine|chili con carne)\b", r"\b(?:рагу|гуляш|жаркое|таджин)\b"),
    "toast": (r"\b(?:sandwich(?:es)?|french toast|toast)\b", r"\b(?:бутерброд\w*|сэндвич\w*|тост\w*)\b"),
    "side": (r"\b(?:side dish|mashed potatoes|french fries|hash browns|coleslaw)\b",
             r"\b(?:гарнир\w*|картофельное пюре|картофель фри)\b"),
    "main": (r"\b(?:omelet(?:te)?s?|curr(?:y|ies)|lasagn[ae]|risotto|casserole|meatloaf|stir[- ]fry|pizza)\b"
             r"|\b(?:roast(?:ed)?|grilled|baked|fried|braised|stuffed) (?:chicken|fish|duck|turkey|salmon|pork|beef)\b",
             r"\b(?:омлет\w*|карри|лазан\w*|ризотто|запеканк\w*|плов|шашлык\w*|котлет\w*|пицц\w*)\b"
             r"|\b(?:жарен\w*|запечен\w*|тушен\w*) (?:куриц\w*|рыб\w*|утк\w*|индейк\w*|лосос\w*|говядин\w*|свинин\w*)\b"),
}

METHODS = {
    "skillet": (r"\b(?:skillet|frying pan|griddle)\b|\bsaut[eé]",
                r"\b(?:сковород\w*|пассиру\w*|обжарь\w*|обжарить)\b"),
    "oven": (r"\b(?:preheat\b.{0,30}\boven|bake\b|roast\b.{0,30}\boven|in the oven)\b",
             r"\b(?:духовк\w*|выпека\w*|запека\w*|запечь)\b"),
    "boil": (r"\b(?:boil(?:ing)? water|bring\b.{0,40}\bto (?:a |the )?boil|boil for)\b",
              r"\b(?:отварить|отварите|кипящ\w*|довед\w* до кипения|варите)\b"),
    "simmer": (r"\b(?:simmer|braise)\b", r"\b(?:тушить|тушите|тушат|томить|томите)\b"),
    "deep_fry": (r"\b(?:deep[- ]fry|deep[- ]fryer)\b", r"\bфритюр\w*\b"),
    "grill": (r"\b(?:preheat the grill|on the grill|grill (?:for|over|until))\b", r"\b(?:на гриле|на мангале)\b"),
    "smoke": (r"\b(?:in (?:a|the) smoker|smoke (?:for|over|until))\b", r"\b(?:коптильн\w*|коптите|коптить)\b"),
    "chill": (r"\b(?:refrigerate|chill (?:for|until|overnight))\b", r"\b(?:в холодильник\w*|охладить|охладите)\b"),
    "infuse": (r"\b(?:infuse|let steep)\b", r"\b(?:настаива\w*|дать настояться|дайте настояться)\b"),
    "brew": (r"\bbrew (?:the |a )?(?:tea|coffee)\b", r"\b(?:заварить|заварите)\b"),
    "waffle_iron": (r"\bwaffle (?:iron|maker)\b", r"\bвафельниц\w*\b"),
}


def infer_tags(language, title, ingredients, steps):
    def matches(patterns, value):
        return re.search(patterns[language == "ru"], value) is not None

    def normalized(value):
        return value.lower().replace("ё", "е").strip()

    def contains(patterns, values):
        return any(matches(patterns, value) for value in values)

    title = re.sub(r"\s*\([^()]*\)$", "", normalized(title))
    steps = [normalized(step) for step in steps]
    ingredients = [normalized(ingredient) for ingredient in ingredients]
    tags = defaultdict(list)
    dish_title = re.split(r"\s+(?:with|served with|in|over)\s+" if language == "en" else r"\s+(?:под|в|с)\s+",
                          title, maxsplit=1)[0]
    types = [key for key, patterns in DISH_TYPES.items() if matches(patterns, dish_title)]
    if types and types[0] in ("sauce", "condiment", "drink", "soup", "salad"):
        types = types[:1]
    savory_cakes = matches(
        (r"\b(?:crab|fish|salmon|shrimp|tuna|potato|rice|cod|clam) cakes?\b",
         r"\b(?:печеночн\w*|рыбн\w*|картофельн\w*) торт\w*\b"), title)
    if savory_cakes:
        types = [value for value in types if value not in {"baking", "dessert"}]
    if set(types) & {"baking", "dessert"} and "porridge" in types:
        types.remove("porridge")
    if "baking" in types and "dessert" not in types and matches(
            (r"\b(?:apple|pumpkin|pecan|cherry|blueberry|pear|peach|chocolate|lemon|lime|custard|berry)\b.{0,24}\bpie\b",
             r"\b(?:яблочн\w*|вишнев\w*|черничн\w*|грушев\w*|персиков\w*|шоколадн\w*|лимонн\w*|ягодн\w*) пирог\b"), title):
        types.append("dessert")

    methods = [key for key, patterns in METHODS.items() if contains(patterns, steps)]
    if "baking" in types and "skillet" in methods and "oven" not in methods:
        types.remove("baking")
    if types:
        tags["dish_type"] = types
    complex_prep = contains((r"\b(?:knead|proof|temper|laminat\w*|rolling pin|steriliz\w*)\b|\blet\b.{0,35}\brise\b",
                             r"\b(?:замес\w*|вымес\w*|раскат\w*|темперир\w*|стерилиз\w*|расстой\w*)\b"), steps)
    active = set(methods) - {"chill", "infuse"}
    cold_mix = (
        bool(set(types) & {"salad", "drink", "sauce"}) and not active and not complex_prep
        and 0 < len(steps) <= 4
        and all(matches((r"^(?:chop|cut|slice|mix|combine|blend|toss|serve|stir|add|wash)\b",
                         r"^(?:нареж\w*|нарез\w*|смеша\w*|перемеша\w*|соедин\w*|взбейте|добав\w*|пода\w*|вымо\w*)\b"),
                        step) for step in steps)
        and not contains((r"\b(?:cook\w*|heat\w*|boil\w*|bake\w*|fry\w*|fried|steam\w*|melt\w*)\b",
                          r"\b(?:вар\w*|жар\w*|выпеч\w*|запек\w*|растоп\w*|прогре\w*|разогре\w*)\b"), steps)
    )
    if cold_mix:
        methods.append("no_heat")
    if methods:
        tags["technique"] = methods

    difficulty = None
    if complex_prep:
        difficulty = ("medium", "technical_step")
    elif len(active) > 1 and not active <= {"boil", "simmer"}:
        difficulty = ("medium", "several_methods")
    elif cold_mix:
        difficulty = ("easy", "cold_assembly")
    elif (active and active <= {"skillet", "boil", "simmer", "brew"}
          and 0 < len(steps) <= 4 and all(len(step) <= 125 for step in steps)):
        difficulty = ("easy", "one_basic_method")
    if difficulty:
        level, note = difficulty
        tags["difficulty"].append(level)
        tags["difficulty_note"] = note

    cold = matches((r"\b(?:cold|chilled|iced|gazpacho)\b", r"\b(?:холодн\w*|ледян\w*|окрошк\w*|гаспачо)\b"), title)
    if set(types) & {"soup", "stew", "porridge"} and not cold:
        tags["mood"].append("cozy")
    if "dessert" in types:
        tags["mood"].append("sweet")
        if "baking" in types:
            tags["occasion"].append("tea")
    if "drink" in types and (cold or "chill" in methods):
        tags["mood"].append("refreshing")
    if matches((r"\b(?:spicy|extra hot)\b", r"\b(?:остр\w*|жгуч\w*)\b"), title):
        tags["mood"].append("spicy")
    if matches((r"\b(?:christmas|easter|holiday|festive|birthday)\b",
                r"\b(?:рождествен\w*|пасхальн\w*|празднич\w*|новогодн\w*)\b"), title):
        tags["occasion"].extend(("celebration", "guests"))
        tags["mood"].append("festive")
    elif matches((r"\b(?:party|for guests)\b", r"\bдля гостей\b"), title):
        tags["occasion"].append("guests")

    if "porridge" in types or matches(
            (r"\b(?:breakfast|omelet(?:te)?s?|french toast|waffles|pancakes)\b",
             r"\b(?:завтрак\w*|омлет\w*|сырник\w*|оладь\w*|вафли)\b"), title):
        tags["meal"].append("breakfast")
    if set(types) & {"main", "pasta", "stew", "soup"} and "breakfast" not in tags.get("meal", []):
        tags["meal"].extend(("lunch", "dinner"))

    if matches((r"\bhearty\b", r"\bсытн\w*\b"), title):
        tags["satiety"].append("hearty")
    elif set(types) & {"main", "stew", "pasta"}:
        solids = [ingredient for ingredient in ingredients
                  if not matches((r"\b(?:stock|broth)\b", r"\bбульон\w*\b"), ingredient)]
        protein = contains((r"\b(?:chicken|beef|pork|beans|lentils|tofu)\b",
                            r"\b(?:куриц\w*|говядин\w*|свинин\w*|фасол\w*|чечевиц\w*|тофу)\b"), solids)
        starch = contains((r"\b(?:pasta|rice|potato(?:es)?)\b", r"\b(?:макарон\w*|рис|картоф\w*)\b"), ingredients)
        if protein and starch:
            tags["satiety"].append("hearty")

    if matches((r"\b(?:unusual|novel|unexpected)\b", r"\b(?:необычн\w*|нетрадиционн\w*)\b"), title):
        tags["novelty"].append("twist")
    return dict(tags)
