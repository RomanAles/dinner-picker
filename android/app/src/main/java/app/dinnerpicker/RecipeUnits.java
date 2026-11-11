package app.dinnerpicker;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RecipeUnits {
    enum System { METRIC, IMPERIAL }

    private enum Kind { MASS, VOLUME, LENGTH, TEMPERATURE, UNKNOWN }

    private enum Unit {
        GRAM(Kind.MASS, System.METRIC, 1, "g"), KILOGRAM(Kind.MASS, System.METRIC, 1000, "kg"),
        OUNCE(Kind.MASS, System.IMPERIAL, 28.349523125, "oz"), POUND(Kind.MASS, System.IMPERIAL, 453.59237, "lb"),
        MILLILITRE(Kind.VOLUME, System.METRIC, 1, "mL"), LITRE(Kind.VOLUME, System.METRIC, 1000, "L"),
        UK_FLUID_OUNCE(Kind.VOLUME, System.IMPERIAL, 28.4130625, "UK fl oz"),
        UK_PINT(Kind.VOLUME, System.IMPERIAL, 568.26125, "UK pt"),
        UK_QUART(Kind.VOLUME, System.IMPERIAL, 1136.5225, "UK qt"),
        UK_GALLON(Kind.VOLUME, System.IMPERIAL, 4546.09, "UK gal"),
        US_FLUID_OUNCE(Kind.VOLUME, null, 29.5735295625, "US fl oz"),
        US_PINT(Kind.VOLUME, null, 473.176473, "US pt"), US_QUART(Kind.VOLUME, null, 946.352946, "US qt"),
        US_GALLON(Kind.VOLUME, null, 3785.411784, "US gal"), US_CUP(Kind.VOLUME, null, 236.5882365, "US cup"),
        METRIC_CUP(Kind.VOLUME, System.METRIC, 250, "metric cup"),
        TEASPOON(Kind.VOLUME, System.IMPERIAL, 5, "tsp"), TABLESPOON(Kind.VOLUME, System.IMPERIAL, 15, "tbsp"),
        MILLIMETRE(Kind.LENGTH, System.METRIC, 0.1, "mm"), CENTIMETRE(Kind.LENGTH, System.METRIC, 1, "cm"),
        INCH(Kind.LENGTH, System.IMPERIAL, 2.54, "in"),
        CELSIUS(Kind.TEMPERATURE, System.METRIC, 1, "°C"), FAHRENHEIT(Kind.TEMPERATURE, System.IMPERIAL, 1, "°F"),
        AMBIGUOUS_VOLUME(Kind.UNKNOWN, null, 1, "");

        final Kind kind;
        final System system;
        final double factor;
        final String label;

        Unit(Kind kind, System system, double factor, String label) {
            this.kind = kind;
            this.system = system;
            this.factor = factor;
            this.label = label;
        }

        double toBase(double value) {
            return this == FAHRENHEIT ? (value - 32) / 1.8 : value * factor;
        }

        double fromBase(double value) {
            return this == FAHRENHEIT ? value * 1.8 + 32 : value / factor;
        }
    }

    private static final String FRACTIONS = "¼½¾⅐⅑⅒⅓⅔⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞";
    private static final String SIMPLE_FRACTION = "[0-9]+[ \\t]*[/⁄][ \\t]*[0-9]+";
    private static final String NUMBER = "(?:[0-9]+[ \\t]+" + SIMPLE_FRACTION + "|" + SIMPLE_FRACTION
            + "|[0-9]*[ \\t]*[" + FRACTIONS + "]|[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]+)?"
            + "|[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)";
    private static final String SEPARATOR = "(?:[-–—]|\\bto\\b|[x×]|-by-)";
    private static final String VOLUME = "(?:fluid\\s+ounces?|fl\\.?\\s*oz|pints?|pt|quarts?|qt|gallons?|gal|cups?)";
    private static final String UNIT = "(?:[°º]\\s*[cf]|[℃℉]|degrees?\\s*(?:celsius|fahrenheit|[cf])|celsius|fahrenheit"
            + "|(?:u\\.?s\\.?|u\\.?k\\.?|imperial|metric)\\s+" + VOLUME + "|" + VOLUME
            + "|kilograms?|kg|grams?|g|pounds?|lbs?|ounces?|oz|millilit(?:er|re)s?|ml|lit(?:er|re)s?|l"
            + "|teaspoons?|tsp|tablespoons?|tbsp|millimet(?:er|re)s?|mm|centimet(?:er|re)s?|cm|inch(?:es)?|in(?=\\.)|[cf])";
    private static final Pattern MEASURE = Pattern.compile(
            "(?<![\\p{L}\\p{N}.,/⁄])(?<values>[-−]?" + NUMBER
                    + "(?:\\s*" + SEPARATOR + "\\s*[-−]?" + NUMBER + ")*)[ \\t]*-?[ \\t]*"
                    + "(?<unit>" + UNIT + ")(?![\\p{L}\\p{N}])(?:[ \\t]*\\((?<alternate>[^()]*)\\))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VALUE = Pattern.compile("[-−]?" + NUMBER);
    private static final Pattern NEXT_VALUE = Pattern.compile("\\G\\s*(" + SEPARATOR + ")\\s*([-−]?" + NUMBER + ")",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DIVIDED_VALUE = Pattern.compile("(?:(\\d+)\\s+)?(\\d+)[ \\t]*[/⁄][ \\t]*(\\d+)");
    private static final Pattern BARE_DEGREES = Pattern.compile("[cf]", Pattern.CASE_INSENSITIVE);
    private static final int[][] FRACTION_VALUES = {{1,4},{1,2},{3,4},{1,7},{1,9},{1,10},{1,3},{2,3},{1,5},
            {2,5},{3,5},{4,5},{1,6},{5,6},{1,8},{3,8},{5,8},{7,8}};

    private RecipeUnits() {}

    static String formatAmount(double amount, String unitId, String label, System system) {
        Unit from = unit(unitId);
        if (from == null || from.kind == Kind.UNKNOWN) {
            return number(amount, 2) + " " + label;
        }
        Unit to = target(from, amount, system);
        return number(to.fromBase(from.toBase(amount)), 2) + " " + to.label;
    }

    static String formatText(String text, System system) {
        Matcher measures = MEASURE.matcher(text);
        StringBuffer output = new StringBuffer();
        while (measures.find()) {
            String rendered = render(measures, system);
            measures.appendReplacement(output, Matcher.quoteReplacement(rendered));
        }
        return measures.appendTail(output).toString();
    }

    private static String render(Matcher match, System system) {
        Unit from = unit(match.group("unit"));
        String values = match.group("values").trim();
        double first = firstValue(values);
        if (from == null || !Double.isFinite(first) || (first < 0 && from.kind != Kind.TEMPERATURE)
                || (BARE_DEGREES.matcher(match.group("unit")).matches()
                    && (match.end("values") != match.start("unit") || Math.abs(first) < 100))) {
            return match.group();
        }
        int end = match.end("unit") - match.start();
        String original = match.group().substring(0, end);
        String alternate = match.group("alternate");
        if (alternate != null) {
            String candidate = alternate.trim();
            Matcher pair = MEASURE.matcher(candidate);
            if (pair.matches() && pair.group("alternate") == null) {
                Unit other = unit(pair.group("unit"));
                if (other != null && compatiblePair(from, first, other, firstValue(pair.group("values")))) {
                    if (from.system == system) {
                        return original;
                    }
                    if (other.kind != Kind.UNKNOWN) {
                        return render(pair, system);
                    }
                    if (from.kind == Kind.UNKNOWN) {
                        return match.group();
                    }
                    alternate = null;
                } else {
                    return match.group();
                }
            }
        }
        Unit to = target(from, first, system);
        String suffix = alternate == null ? "" : match.group().substring(end);
        if (to == from) {
            return original + suffix;
        }
        int decimals = from.kind == Kind.TEMPERATURE ? 1 : 2;
        Matcher leading = VALUE.matcher(values);
        leading.find();
        double initial = to.fromBase(from.toBase(first));
        if (!Double.isFinite(initial)) {
            return match.group();
        }
        StringBuilder converted = new StringBuilder(number(initial, decimals));
        int parsed = leading.end();
        Matcher parts = NEXT_VALUE.matcher(values).region(parsed, values.length());
        while (parts.find()) {
            double amount = readNumber(parts.group(2));
            double next = to.fromBase(from.toBase(amount));
            if (!Double.isFinite(next) || (amount < 0 && from.kind != Kind.TEMPERATURE)) {
                return match.group();
            }
            boolean dimensions = parts.group(1).matches("(?i)[x×]|-by-");
            if (dimensions && from.kind != Kind.LENGTH) {
                return match.group();
            }
            converted.append(dimensions ? " × " : "–").append(number(next, decimals));
            parsed = parts.end();
        }
        return parsed == values.length() ? converted + " " + to.label + suffix : match.group();
    }

    private static boolean compatiblePair(Unit first, double amount, Unit second, double otherAmount) {
        if (!Double.isFinite(otherAmount)) {
            return false;
        }
        if (first.kind != second.kind || first.kind == Kind.UNKNOWN) {
            // A cookbook may provide an ingredient-specific weight for a cup or spoon.
            return first.kind != Kind.TEMPERATURE && second.kind != Kind.TEMPERATURE
                    && first.kind != Kind.LENGTH && second.kind != Kind.LENGTH;
        }
        double base = first.toBase(amount);
        double difference = Math.abs(base - second.toBase(otherAmount));
        return difference <= (first.kind == Kind.TEMPERATURE ? 5 : Math.abs(base) * 0.1);
    }

    private static Unit target(Unit from, double amount, System system) {
        if (from.system == system || from.kind == Kind.UNKNOWN) {
            return from;
        }
        double base = from.toBase(amount);
        return switch (from.kind) {
            case MASS -> system == System.METRIC ? (base >= 1000 ? Unit.KILOGRAM : Unit.GRAM)
                    : (base >= Unit.POUND.factor ? Unit.POUND : Unit.OUNCE);
            case VOLUME -> system == System.METRIC ? (base >= 1000 ? Unit.LITRE : Unit.MILLILITRE)
                    : Unit.UK_FLUID_OUNCE;
            case LENGTH -> system == System.METRIC ? Unit.CENTIMETRE : Unit.INCH;
            case TEMPERATURE -> system == System.METRIC ? Unit.CELSIUS : Unit.FAHRENHEIT;
            case UNKNOWN -> from;
        };
    }

    private static double firstValue(String values) {
        Matcher number = VALUE.matcher(values);
        return number.find() ? readNumber(number.group().trim()) : Double.NaN;
    }

    private static double readNumber(String raw) {
        String value = raw.replace('−', '-');
        boolean negative = value.startsWith("-");
        value = negative ? value.substring(1).trim() : value;
        Matcher divided = DIVIDED_VALUE.matcher(value);
        double amount;
        int fraction = FRACTIONS.indexOf(value.charAt(value.length() - 1));
        if (divided.matches()) {
            double whole = divided.group(1) == null ? 0 : Double.parseDouble(divided.group(1));
            amount = whole + Double.parseDouble(divided.group(2)) / Double.parseDouble(divided.group(3));
        } else if (fraction >= 0) {
            String prefix = value.substring(0, value.length() - 1).trim();
            amount = (prefix.isEmpty() ? 0 : Double.parseDouble(prefix))
                    + (double) FRACTION_VALUES[fraction][0] / FRACTION_VALUES[fraction][1];
        } else {
            amount = Double.parseDouble(value.replace(",", ""));
        }
        return negative ? -amount : amount;
    }

    private static String number(double value, int decimals) {
        BigDecimal exact = BigDecimal.valueOf(value);
        BigDecimal rounded = exact.setScale(decimals, RoundingMode.HALF_UP);
        return (rounded.signum() == 0 && value != 0 ? exact.round(new java.math.MathContext(2)) : rounded)
                .stripTrailingZeros().toPlainString();
    }

    private static Unit unit(String name) {
        String label = name.toLowerCase(Locale.ROOT).replace(".", "").replaceAll("\\s+", " ");
        if (label.startsWith("us ") || label.startsWith("uk ") || label.startsWith("imperial ")
                || label.startsWith("metric ")) {
            int space = label.indexOf(' ');
            String qualifier = label.substring(0, space);
            String measure = label.substring(space + 1);
            boolean us = qualifier.equals("us");
            boolean imperial = qualifier.equals("uk") || qualifier.equals("imperial");
            return switch (measure) {
                case "cup", "cups" -> us ? Unit.US_CUP : qualifier.equals("metric") ? Unit.METRIC_CUP : Unit.AMBIGUOUS_VOLUME;
                case "fl oz", "fluid ounce", "fluid ounces" -> us ? Unit.US_FLUID_OUNCE
                        : imperial ? Unit.UK_FLUID_OUNCE : Unit.AMBIGUOUS_VOLUME;
                case "pt", "pint", "pints" -> us ? Unit.US_PINT : imperial ? Unit.UK_PINT : Unit.AMBIGUOUS_VOLUME;
                case "qt", "quart", "quarts" -> us ? Unit.US_QUART : imperial ? Unit.UK_QUART : Unit.AMBIGUOUS_VOLUME;
                case "gal", "gallon", "gallons" -> us ? Unit.US_GALLON : imperial ? Unit.UK_GALLON : Unit.AMBIGUOUS_VOLUME;
                default -> Unit.AMBIGUOUS_VOLUME;
            };
        }
        return switch (label.replace('º', '°').replace("° ", "°")) {
            case "g", "gram", "grams" -> Unit.GRAM;
            case "kg", "kilogram", "kilograms" -> Unit.KILOGRAM;
            case "oz", "ounce", "ounces" -> Unit.OUNCE;
            case "lb", "lbs", "pound", "pounds" -> Unit.POUND;
            case "ml", "milliliter", "milliliters", "millilitre", "millilitres" -> Unit.MILLILITRE;
            case "l", "liter", "liters", "litre", "litres" -> Unit.LITRE;
            case "tsp", "teaspoon", "teaspoons" -> Unit.TEASPOON;
            case "tbsp", "tablespoon", "tablespoons" -> Unit.TABLESPOON;
            case "mm", "millimeter", "millimeters", "millimetre", "millimetres" -> Unit.MILLIMETRE;
            case "cm", "centimeter", "centimeters", "centimetre", "centimetres" -> Unit.CENTIMETRE;
            case "in", "inch", "inches" -> Unit.INCH;
            case "c", "°c", "℃", "degree c", "degrees c", "celsius", "degree celsius", "degrees celsius" -> Unit.CELSIUS;
            case "f", "°f", "℉", "degree f", "degrees f", "fahrenheit", "degree fahrenheit", "degrees fahrenheit" -> Unit.FAHRENHEIT;
            case "cup", "cups", "fl oz", "fluid ounce", "fluid ounces", "pt", "pint", "pints", "qt", "quart",
                    "quarts", "gal", "gallon", "gallons" -> Unit.AMBIGUOUS_VOLUME;
            default -> null;
        };
    }
}
