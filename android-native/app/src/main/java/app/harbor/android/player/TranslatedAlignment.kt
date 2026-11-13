package app.harbor.android.player

import app.harbor.android.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import kotlin.math.abs

/**
 * Auto sync for a subtitle in another language than the audio (Arabic lines on an English film):
 * the lines near the stretches heard are translated into the audio's language, then each one is
 * matched to what Whisper heard by the words they share, not by exact phrases. Lines that land on
 * the same offset across the video give the timing.
 */
internal object TranslatedAlignment {
    /** How far a subtitle can be out and still be found: two minutes either way. */
    private const val MAX_LAG_MS = 120_000L
    private const val INLIER_MS = 1_500L

    private val wordPattern = Regex("[\\p{L}\\p{N}']+")
    // Words that say nothing about which line it is; English only (Whisper's usual output).
    private val stop = ("the a an and or but to of in on at for is are was were be been it its this that i you he she we they me him her us them " +
        "my your his our their do does did not no yes so if as with by from just what who how why when where there here have has had will would " +
        "can could should oh okay ok well all one i'm it's don't that's you're").split(' ').toSet()

    internal fun tokens(text: String): List<String> =
        wordPattern.findAll(text.lowercase(Locale.ROOT).replace('’', '\'')).map { it.value.trim('\'') }
            .filter { it.length > 1 && it !in stop }.toList()

    /** The lines worth translating: near a stretch that was heard, with letters in them. */
    fun near(cues: List<SubtitleFile.Cue>, heard: List<Pair<Float, Float>>): List<SubtitleFile.Cue> = cues.filter { c ->
        c.text.any(Char::isLetter) && heard.any { (start, len) ->
            c.startMs >= (start * 1000).toLong() - MAX_LAG_MS && c.startMs <= ((start + len) * 1000).toLong() + MAX_LAG_MS
        }
    }

    // A line's words are enough to match it; one runaway line can't make a request too long.
    private fun clean(text: String) = text.replace(Regex("<[^>]+>|\\{[^}]*\\}"), " ").replace(Regex("\\s+"), " ").trim().take(300)

    private val slots = Semaphore(3)
    private val memo = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** `lines` in `target`, line for line, through the translator the review Translate button uses. */
    suspend fun translate(lines: List<String>, target: String): Map<String, String> = coroutineScope {
        val wanted = lines.map(::clean).filter { it.isNotEmpty() }.distinct()
        val todo = wanted.filter { !memo.containsKey("$target|$it") }
        // Several lines a request, one per line, under a safe URL length.
        val batches = mutableListOf<MutableList<String>>()
        var used = 0
        for (line in todo) {
            val cost = java.net.URLEncoder.encode(line, "UTF-8").length + 3
            if (batches.isEmpty() || used + cost > 6_000) { batches += mutableListOf<String>(); used = 0 }
            batches.last() += line
            used += cost
        }
        batches.map { batch ->
            async(Dispatchers.IO) {
                slots.withPermit {
                    runCatching {
                        val url = okhttp3.HttpUrl.Builder().scheme("https").host("clients5.google.com").addPathSegments("translate_a/t")
                            .addQueryParameter("client", "dict-chrome-ex").addQueryParameter("sl", "auto").addQueryParameter("tl", target)
                            .addQueryParameter("q", batch.joinToString("\n")).build()
                        val root = Net.json.parseToJsonElement(Net.get(url.toString())) as? JsonArray ?: return@runCatching
                        val first = root.firstOrNull() ?: return@runCatching
                        val text = (first as? JsonPrimitive)?.contentOrNull
                            ?: (first as? JsonArray)?.let { arr -> (arr.firstOrNull() as? JsonPrimitive)?.contentOrNull ?: arr.mapNotNull { (it as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull }.joinToString("") }
                            ?: return@runCatching
                        val out = text.split('\n')
                        // A batch whose lines didn't come back one for one can't be paired up: skip it.
                        if (out.size == batch.size) batch.zip(out).forEach { (from, to) -> memo["$target|$from"] = to.trim() }
                    }
                }
            }
        }.awaitAll()
        if (memo.size > 4_000) memo.clear()
        wanted.mapNotNull { line -> memo["$target|$line"]?.let { line to it } }.toMap()
    }

    /**
     * The offset from translated lines (`translated`: each line's text, cleaned, to its translation)
     * against the words heard in each window. Null unless most matches agree, in two windows or more.
     */
    fun solve(cues: List<SubtitleFile.Cue>, translated: Map<String, String>, windows: List<SpeechAlignment.Window>, few: Boolean = false): AudioSync.Result? {
        val words = windows.flatMap { w -> w.words.filter { it.probability >= 0.3 }.map { w.id to it } }.sortedBy { it.second.startMs }
        // `few`: a slow TV hears only a sentence or two per clip, so fewer matching lines must do;
        // they still have to agree, and come from two clips or more.
        if (words.size < (if (few) 10 else 20)) return null
        // (time, token, word index) for every meaningful word heard.
        val heard = words.flatMapIndexed { index, (_, w) -> tokens(w.text).map { Triple(w.startMs, it, index) } }
        // Where the sentence a word belongs to began: back over close words, not past a full stop.
        fun onset(index: Int): Long {
            var j = index
            repeat(4) {
                if (j == 0) return words[j].second.startMs
                val previous = words[j - 1].second
                if (previous.text.trimEnd().lastOrNull() in setOf('.', '?', '!') || words[j].second.startMs - previous.endMs > 500) return words[j].second.startMs
                j--
            }
            return words[j].second.startMs
        }
        data class Anchor(val cue: Long, val audio: Long, val window: Int)
        val anchors = mutableListOf<Anchor>()
        for (cue in cues) {
            val text = translated[clean(cue.text)] ?: continue
            val wanted = tokens(text).toSet()
            if (wanted.size < 2) continue
            val reach = maxOf(3_000L, cue.endMs - cue.startMs + 2_000)
            // Every place the line could start: score by how many of its words follow close by.
            val found = mutableListOf<Triple<Double, Int, Int>>() // score, shared words, word index
            for (i in heard.indices) {
                val (t0, token, index) = heard[i]
                if (token !in wanted || abs(t0 - cue.startMs) > MAX_LAG_MS) continue
                val near = HashSet<String>()
                var k = i
                while (k < heard.size && k < i + wanted.size + 4 && heard[k].first - t0 <= reach) { near += heard[k].second; k++ }
                val shared = wanted.count { it in near }
                found += Triple(shared.toDouble() / wanted.size, shared, index)
            }
            if (found.isEmpty()) continue
            val best = found.maxWith(compareBy({ it.first }, { it.second }))
            val bestAt = words[best.third].second.startMs
            val second = found.filter { abs(words[it.third].second.startMs - bestAt) > 4_000 }.maxOfOrNull { it.first }
            // Only clear matches: most of its words, and no near-equal match somewhere else.
            if (best.second < 2 || best.first < 0.5 || (second != null && second > best.first - 0.25)) continue
            anchors += Anchor(cue.startMs, onset(best.third), words[best.third].first)
        }
        if (anchors.size < (if (few) 4 else 8)) return null
        val offsets = anchors.map { it.audio - it.cue }
        val first = offsets.sorted()[offsets.size / 2]
        val inliers = anchors.filter { abs(it.audio - it.cue - first) <= INLIER_MS }
        if (inliers.size < (if (few) 3 else 6) || inliers.size < anchors.size * 0.6 || inliers.map { it.window }.distinct().size < 2) return null
        val sorted = inliers.map { it.audio - it.cue }.sorted()
        val offset = sorted[sorted.size / 2]
        val spread = inliers.map { abs(it.audio - it.cue - offset) }.sorted()[inliers.size / 2]
        val confidence = (inliers.size.toDouble() / anchors.size * (1.0 - spread / 2_000.0)).coerceIn(0.0, 0.85)
        return AudioSync.Result(offset, 1.0, confidence.toFloat(), "speech recognition (translated)")
    }
}
