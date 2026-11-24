package app.harbor.android.player

/** mpv's parsed ASS header has styles but no events; prefer conventional dialogue style names. */
internal object AssHeader {
    private val dialogue = Regex("(?:^|[\\s._-])(?:default|dialogue|dialog|main|regular)(?:\\s*\\d+)?$", RegexOption.IGNORE_CASE)
    private val signs = Regex("(?:^|[\\s._-])(?:signs?|signage|title|song|lyrics?|credits?|notes?|op|ed|opening|ending|insert|card)(?:$|[\\s._-])", RegexOption.IGNORE_CASE)

    fun factor(text: String): Double? {
        val styles = linkedMapOf<String, Double>()
        var section = ""
        var nameColumn = -1
        var sizeColumn = -1
        var resX = 0
        var resY = 0
        text.lineSequence().forEach { raw ->
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.startsWith('[')) section = line.lowercase()
            val key = line.substringBefore(':').trim().lowercase()
            val value = line.substringAfter(':', "").trim()
            if (section == "[script info]") when (key) {
                "playresx" -> resX = value.toIntOrNull() ?: 0
                "playresy" -> resY = value.toIntOrNull() ?: 0
            }
            if (section in setOf("[v4+ styles]", "[v4 styles]")) when (key) {
                "format" -> {
                    val fields = value.split(',').map { it.trim().lowercase() }
                    nameColumn = fields.indexOf("name")
                    sizeColumn = fields.indexOf("fontsize")
                }
                "style" -> {
                    val fields = value.split(',').map(String::trim)
                    val name = fields.getOrNull(nameColumn)
                    val size = fields.getOrNull(sizeColumn)?.toDoubleOrNull()
                    if (!name.isNullOrBlank() && size != null && size.isFinite()) styles[name] = size
                }
            }
        }
        if (resY <= 0) resY = when { resX == 1280 -> 1024; resX > 0 -> (resX.toLong() * 3 / 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(); else -> 288 }
        val positive = styles.filterValues { it > 0 }
        val chosen = positive.entries.firstOrNull { it.key.equals("Default", true) }
            ?: positive.entries.firstOrNull { dialogue.containsMatchIn(it.key) && !signs.containsMatchIn(it.key) }
            ?: positive.entries.firstOrNull() ?: return null
        val fraction = chosen.value / resY
        if (fraction !in 0.02..0.22) return null
        return (resY / (720.0 * chosen.value)).takeIf { it.isFinite() && it > 0 }
    }

    fun scale(header: String?, targetSize: Double): Double? = header?.let(::factor)?.let {
        (it * targetSize.takeIf(Double::isFinite).let { size -> size ?: 32.0 }).coerceIn(0.2, 6.0)
    }
}
