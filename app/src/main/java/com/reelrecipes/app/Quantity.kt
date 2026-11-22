package com.reelrecipes.app

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** A parsed ingredient amount such as "1½ cups", "200 g", "2-3", "~1 tbsp". */
data class Amount(val value: Double, val upper: Double?, val unit: String, val approx: Boolean) {
    fun times(f: Double) = copy(value = value * f, upper = upper?.times(f))
}

/** Base measure for costing: grams, millilitres or a count of items. */
data class Base(val amount: Double, val kind: Kind) {
    enum class Kind { G, ML, EA }
}

object Qty {
    private val FRACTIONS = mapOf('½' to 0.5, '¼' to 0.25, '¾' to 0.75, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3, '⅛' to 0.125, '⅜' to 0.375, '⅝' to 0.625, '⅞' to 0.875)

    /** Unit spellings → canonical unit. Empty string = a plain count. */
    private val UNITS = linkedMapOf(
        "kg" to "kg", "kgs" to "kg", "kilo" to "kg", "kilos" to "kg", "kilogram" to "kg", "kilograms" to "kg",
        "g" to "g", "gm" to "g", "gms" to "g", "gr" to "g", "gram" to "g", "grams" to "g",
        "mg" to "mg",
        "l" to "l", "lt" to "l", "litre" to "l", "litres" to "l", "liter" to "l", "liters" to "l",
        "ml" to "ml", "mls" to "ml", "millilitre" to "ml", "millilitres" to "ml", "milliliter" to "ml", "milliliters" to "ml",
        "tsp" to "tsp", "tsps" to "tsp", "teaspoon" to "tsp", "teaspoons" to "tsp", "t" to "tsp",
        "tbsp" to "tbsp", "tbsps" to "tbsp", "tbs" to "tbsp", "tbl" to "tbsp", "tablespoon" to "tbsp", "tablespoons" to "tbsp", "T" to "tbsp",
        "cup" to "cup", "cups" to "cup", "c" to "cup",
        "oz" to "oz", "ounce" to "oz", "ounces" to "oz",
        "lb" to "lb", "lbs" to "lb", "pound" to "lb", "pounds" to "lb",
        "pinch" to "pinch", "pinches" to "pinch", "dash" to "dash", "dashes" to "dash", "splash" to "splash",
        "handful" to "handful", "handfuls" to "handful",
        "clove" to "clove", "cloves" to "clove",
        "can" to "can", "cans" to "can", "tin" to "can", "tins" to "can",
        "bunch" to "bunch", "bunches" to "bunch",
        "slice" to "slice", "slices" to "slice",
        "piece" to "piece", "pieces" to "piece", "pc" to "piece", "pcs" to "piece",
        "sprig" to "sprig", "sprigs" to "sprig", "stalk" to "stalk", "stalks" to "stalk",
        "stick" to "stick", "sticks" to "stick", "sheet" to "sheet", "sheets" to "sheet",
        "packet" to "packet", "packets" to "packet", "pack" to "packet", "packs" to "packet", "pkt" to "packet",
        "x" to "",
    )

    private const val NUM = "(?:\\d+\\s+\\d+/\\d+|\\d+\\s*[½¼¾⅓⅔⅛⅜⅝⅞]|\\d+/\\d+|\\d+(?:[.,]\\d+)?|[½¼¾⅓⅔⅛⅜⅝⅞])"
    private val AMOUNT = Regex("^\\s*(~|≈|approx\\.?|about|around)?\\s*($NUM)(?:\\s*(?:-|–|to)\\s*($NUM))?\\s*([A-Za-z]+\\.?)?\\s*(.*)$")
    private val WORDY = Regex("^\\s*(a|an|one)\\s+(pinch|dash|splash|handful|bunch|few|couple)(?:\\s+of)?\\s*$", RegexOption.IGNORE_CASE)

    fun number(s: String): Double? {
        val t = s.trim().replace(',', '.')
        Regex("^(\\d+)\\s+(\\d+)/(\\d+)$").find(t)?.let { m -> return m.groupValues[1].toDouble() + m.groupValues[2].toDouble() / m.groupValues[3].toDouble() }
        Regex("^(\\d+)\\s*([½¼¾⅓⅔⅛⅜⅝⅞])$").find(t)?.let { m -> return m.groupValues[1].toDouble() + (FRACTIONS[m.groupValues[2][0]] ?: 0.0) }
        Regex("^(\\d+)/(\\d+)$").find(t)?.let { m -> val d = m.groupValues[2].toDouble(); return if (d == 0.0) null else m.groupValues[1].toDouble() / d }
        if (t.length == 1 && FRACTIONS.containsKey(t[0])) return FRACTIONS[t[0]]
        return t.toDoubleOrNull()
    }

    /** Parses the amount column. Returns null for "to taste", blank and similar. */
    fun parse(q: String): Amount? {
        val s = q.trim()
        if (s.isEmpty()) return null
        WORDY.find(s)?.let { m ->
            val w = m.groupValues[2].lowercase()
            return when (w) { "few" -> Amount(3.0, null, "", true); "couple" -> Amount(2.0, null, "", true); else -> Amount(1.0, null, UNITS[w] ?: w, false) }
        }
        val m = AMOUNT.find(s) ?: return null
        val v = number(m.groupValues[2]) ?: return null
        val up = m.groupValues[3].takeIf { it.isNotBlank() }?.let { number(it) }
        val rawUnit = m.groupValues[4].trimEnd('.')
        val unit = UNITS[rawUnit] ?: UNITS[rawUnit.lowercase()] ?: if (rawUnit.isEmpty()) "" else return Amount(v, up, "", m.groupValues[1].isNotBlank())
        return Amount(v, up, unit, m.groupValues[1].isNotBlank())
    }

    /** "200 g" × 1.5 → "300 g". Text that can't be parsed (e.g. "to taste") is returned unchanged. */
    fun scale(q: String, factor: Double): String {
        if (abs(factor - 1.0) < 1e-9) return q
        val s = q.trim()
        if (WORDY.matches(s)) return if (factor >= 1.5) "${format(factor, "")} × $s" else s
        val m = AMOUNT.find(s) ?: return q
        val sc = (parse(s) ?: return q).times(factor)
        val unitText = m.groupValues[4]
        val rest = m.groupValues[5].trim()
        val pre = if (sc.approx) "~" else ""
        fun join(a: String) = if (rest.isEmpty()) a else "$a $rest"
        if (sc.upper == null && sc.unit == "g" && sc.value >= 1000) return join(pre + format(sc.value / 1000, "kg") + " kg")
        if (sc.upper == null && sc.unit == "ml" && sc.value >= 1000) return join(pre + format(sc.value / 1000, "l") + " L")
        val num = format(sc.value, sc.unit) + (sc.upper?.let { "–" + format(it, sc.unit) } ?: "")
        val unitOut = when {
            unitText.isEmpty() -> ""
            sc.unit in setOf("g", "kg", "ml", "l", "mg", "tsp", "tbsp", "oz", "lb") -> unitText
            else -> pluralise(unitText.trimEnd('.'), sc.upper ?: sc.value)
        }
        val attached = unitText.isNotEmpty() && Regex("\\d" + Regex.escape(unitText)).containsMatchIn(s)
        val numUnit = when { unitOut.isEmpty() -> num; attached -> num + unitOut; else -> "$num $unitOut" }
        return join(pre + numUnit)
    }

    private fun pluralise(word: String, n: Double): String {
        val singular = mapOf("cups" to "cup", "cloves" to "clove", "cans" to "can", "tins" to "tin", "slices" to "slice", "pieces" to "piece",
            "sprigs" to "sprig", "stalks" to "stalk", "sticks" to "stick", "sheets" to "sheet", "packets" to "packet", "bunches" to "bunch",
            "handfuls" to "handful", "pinches" to "pinch", "teaspoons" to "teaspoon", "tablespoons" to "tablespoon")
        val plural = singular.entries.associate { (k, v) -> v to k }
        return if (n <= 1.0) singular[word.lowercase()] ?: word else plural[word.lowercase()] ?: word
    }

    /** Kitchen-friendly number: fractions for spoons/cups/counts, rounded figures for weights. */
    fun format(v: Double, unit: String): String {
        if (unit in setOf("g", "ml", "mg")) {
            val r = when { v >= 500 -> (v / 10).roundToInt() * 10.0; v >= 100 -> (v / 5).roundToInt() * 5.0; v >= 10 -> v.roundToInt().toDouble(); else -> (v * 2).roundToInt() / 2.0 }
            return trim(r)
        }
        if (unit in setOf("kg", "l")) return trim((v * 100).roundToInt() / 100.0)
        val whole = floor(v + 1e-9)
        val frac = v - whole
        val options = listOf(0.0 to "", 0.25 to "¼", 1.0 / 3 to "⅓", 0.5 to "½", 2.0 / 3 to "⅔", 0.75 to "¾", 1.0 to "")
        val best = options.minBy { abs(it.first - frac) }
        val w = if (best.first == 1.0) whole + 1 else whole
        if (v >= 10) return v.roundToInt().toString()
        return when {
            w == 0.0 && best.second.isEmpty() -> if (v > 0) "¼" else "0"
            w == 0.0 -> best.second
            else -> trim(w) + best.second
        }
    }

    private fun trim(d: Double): String = if (d == floor(d)) d.toLong().toString() else "%.2f".format(d).trimEnd('0').trimEnd('.')

    /**
     * Converts an amount to grams / millilitres / count, using a per-ingredient weight
     * when a count needs to become a weight. Returns null when it can't be measured.
     */
    fun toBase(a: Amount, item: String): Base? {
        val v = a.upper?.let { (a.value + it) / 2 } ?: a.value
        return when (a.unit) {
            "g" -> Base(v, Base.Kind.G)
            "kg" -> Base(v * 1000, Base.Kind.G)
            "mg" -> Base(v / 1000, Base.Kind.G)
            "oz" -> Base(v * 28.35, Base.Kind.G)
            "lb" -> Base(v * 453.6, Base.Kind.G)
            "ml" -> Base(v, Base.Kind.ML)
            "l" -> Base(v * 1000, Base.Kind.ML)
            "tsp" -> Base(v * 5, Base.Kind.ML)
            "tbsp" -> Base(v * 15, Base.Kind.ML)
            "cup" -> Base(v * 250, Base.Kind.ML)
            "pinch", "dash" -> Base(v * 0.5, Base.Kind.G)
            "splash" -> Base(v * 10, Base.Kind.ML)
            "handful" -> Base(v * 30, Base.Kind.G)
            "can" -> Base(v * 400, Base.Kind.G)
            "clove" -> Base(v * 5, Base.Kind.G)
            "bunch" -> Base(v, Base.Kind.EA)
            "slice" -> Base(v * 35, Base.Kind.G)
            "sprig", "stalk", "stick", "sheet", "piece", "packet" -> Base(v, Base.Kind.EA)
            else -> Base(v, Base.Kind.EA)
        }
    }

    /** Typical weight of one of something, for turning "2 onions" into grams. */
    fun eachWeight(item: String): Double? {
        val t = item.lowercase()
        val table = listOf(
            "garlic" to 5.0, "egg" to 55.0, "onion" to 150.0, "shallot" to 40.0, "spring onion" to 15.0, "chicken breast" to 220.0,
            "chicken thigh" to 120.0, "drumstick" to 110.0, "potato" to 200.0, "kumara" to 300.0, "sweet potato" to 300.0, "carrot" to 120.0,
            "tomato" to 120.0, "lemon" to 120.0, "lime" to 70.0, "avocado" to 170.0, "capsicum" to 160.0, "pepper" to 160.0,
            "cucumber" to 350.0, "courgette" to 200.0, "zucchini" to 200.0, "banana" to 120.0, "apple" to 180.0, "chilli" to 10.0,
            "chili" to 10.0, "mushroom" to 20.0, "celery" to 60.0, "leek" to 250.0, "broccoli" to 350.0, "cauliflower" to 800.0,
            "cabbage" to 1000.0, "lettuce" to 400.0, "eggplant" to 300.0, "aubergine" to 300.0, "corn" to 250.0, "steak" to 250.0,
            "salmon fillet" to 150.0, "fillet" to 150.0, "tortilla" to 40.0, "wrap" to 60.0, "bread" to 35.0, "bun" to 70.0, "ginger" to 15.0,
        )
        return table.firstOrNull { (k, _) -> t.contains(k) }?.second
    }

    /** Grams per millilitre, so "2 cups flour" can be costed against a 1.5 kg bag. */
    fun density(item: String): Double {
        val t = item.lowercase()
        return when {
            t.contains("flour") -> 0.53; t.contains("icing sugar") -> 0.5; t.contains("brown sugar") -> 0.9; t.contains("sugar") -> 0.85
            t.contains("rice") -> 0.85; t.contains("oat") -> 0.4; t.contains("butter") -> 0.96; t.contains("honey") || t.contains("syrup") -> 1.4
            t.contains("cheese") -> 0.45; t.contains("breadcrumb") || t.contains("panko") -> 0.25; t.contains("cocoa") -> 0.45
            t.contains("salt") -> 1.2; t.contains("oil") -> 0.92; t.contains("nut") || t.contains("seed") -> 0.6; t.contains("spinach") -> 0.15
            t.contains("pasta") -> 0.45; t.contains("lentil") || t.contains("bean") || t.contains("chickpea") -> 0.8
            else -> 1.0
        }
    }
}
