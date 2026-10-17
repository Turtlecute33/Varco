package app.varco.importer

import app.varco.term.Scheme
import app.varco.term.Schemes

/**
 * Reads a Termux `~/.termux/colors.properties` file. Keys: foreground, background, cursor, color0..color15.
 * Colour forms: #rgb, #rrggbb, rgb:r/g/b with 1 to 4 hex digits per part (the forms that Termux reads).
 * Missing colours come from the Termux default scheme, as in Termux.
 */
object TermuxColors {
    fun parse(text: String, id: String, name: String): Scheme? {
        val values = HashMap<String, Int>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#') || line.startsWith('!')) continue
            val sep = line.indexOfFirst { it == '=' || it == ':' }
            if (sep <= 0) continue
            val key = line.substring(0, sep).trim().lowercase()
            val colour = parseColour(line.substring(sep + 1).trim()) ?: continue
            values[key] = colour
        }
        val base = Schemes.byId("termux")
        if (values.keys.none { it == "foreground" || it == "background" || it.startsWith("color") }) return null
        return Scheme(
            id = id,
            name = name,
            foreground = values["foreground"] ?: base.foreground,
            background = values["background"] ?: base.background,
            cursor = values["cursor"] ?: values["foreground"] ?: base.cursor,
            ansi = IntArray(16) { values["color$it"] ?: base.ansi[it] },
        )
    }

    fun parseColour(value: String): Int? {
        val v = value.trim()
        val parts: List<String> = when {
            v.startsWith('#') -> {
                val hex = v.substring(1)
                if (hex.isEmpty() || hex.length % 3 != 0 || hex.length > 12) return null
                val n = hex.length / 3
                listOf(hex.substring(0, n), hex.substring(n, 2 * n), hex.substring(2 * n))
            }
            v.startsWith("rgb:", ignoreCase = true) -> v.substring(4).split('/').takeIf { it.size == 3 } ?: return null
            else -> return null
        }
        var rgb = 0
        for (part in parts) {
            if (part.isEmpty() || part.length > 4) return null
            val n = part.toIntOrNull(16) ?: return null
            // Scale 1..4 hex digits to 8 bits: "f" -> ff, "fff" -> ff, "ffff" -> ff.
            val max = (1 shl (4 * part.length)) - 1
            rgb = (rgb shl 8) or Math.round(n * 255.0 / max).toInt()
        }
        return Schemes.opaque(rgb)
    }
}
