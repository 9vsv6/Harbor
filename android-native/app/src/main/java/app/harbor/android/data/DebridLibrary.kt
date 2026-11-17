package app.harbor.android.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.util.Locale

/** Only cloud identity travels with a source. Keys and expiring download links stay out of it. */
@Serializable
data class LibrarySource(val service: DebridService, val itemId: String, val fileId: String, val kind: String = "torrents", val unverified: Boolean = false)

internal data class LibraryFile(val id: String, val name: String, val size: Long = 0)
internal data class LibraryEntry(val id: String, val name: String, val files: List<LibraryFile> = emptyList(), val hash: String? = null, val kind: String = "torrents")

internal object DebridLibrary {
    data class Query(val type: String, val title: String, val year: Int? = null, val season: Int? = null, val episode: Int? = null)
    data class Match(val fileIndex: Int, val unverified: Boolean = false)

    fun query(type: String, id: String, title: String, year: Int?): Query {
        if (type == "movie") return Query(type, title, year)
        val parts = id.split(':').drop(if (id.startsWith("tt")) 1 else 2)
        val season = if (parts.size >= 2) parts[parts.size - 2].toIntOrNull() else null
        return Query(type, title, year, season, parts.lastOrNull()?.toIntOrNull())
    }

    private val episodeCode = Regex("""(?i)(?<![a-z0-9])s(\d{1,2})[ ._-]*e(\d{1,4})|(?<!\d)(\d{1,2})x(\d{1,4})(?!\d)""")
    private val seasonCode = Regex("""(?i)(?<![a-z0-9])(?:s|season[ ._-]*)(\d{1,2})(?!\d)""")
    private val episodeOnly = Regex("""(?i)(?<![a-z0-9])(?:e|ep|episode)[ ._-]*(\d{1,4})(?!\d)""")
    private val absoluteCode = Regex("""(?i)\s-\s(\d{1,4})(?:v\d)?\b""")
    private val yearCode = Regex("""(?<!\d)(?:19|20)\d{2}(?!\d)""")
    private val quality = Regex("""(?i)(?<![a-z0-9])(?:2160p|1080[pi]|720p|480p|576p|1440p|4k|uhd|web[ ._-]?(?:dl|rip)|blu[ ._-]?ray|b[dr]rip|hdtv|remux|hdr10?\+?|dovi|dv|hevc|x26[45]|h[ .]?26[45]|av1|aac|dts|ddp?|ac3|10bit|complete|batch)(?![a-z0-9])""")
    private val extra = Regex("""(?i)(?:^|[^a-z0-9])(?:ncop|nced|creditless|opening|ending|preview|trailer|extra|special|sample|menu|bonus|interview|making|scan|booklet|ost)(?:[^a-z0-9]|$)""")
    private val video = Regex("""(?i)\.(mkv|mp4|avi|mov|m4v|webm|ts|flv|wmv|m2ts|mpg|mpeg|ogv|3gp)(?:[?#]|$)""")
    private data class Parsed(val title: String, val year: Int?, val season: Int?, val episode: Int?)

    private fun parse(text: String): Parsed {
        val name = text.substringAfterLast('/').substringAfterLast('\\').replace(Regex("""^\[[^]]+]\s*"""), "")
        val code = episodeCode.find(name)
        val season = code?.let { (it.groups[1] ?: it.groups[3])?.value?.toIntOrNull() } ?: seasonCode.find(name)?.groupValues?.get(1)?.toIntOrNull()
        val absolute = absoluteCode.find(name)
        val episode = code?.let { (it.groups[2] ?: it.groups[4])?.value?.toIntOrNull() } ?: episodeOnly.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: absolute?.groupValues?.get(1)?.toIntOrNull()
        // A year at the start can be the movie's title (1917, 2012).
        val year = yearCode.findAll(name).firstOrNull { name.substring(0, it.range.first).any(Char::isLetterOrDigit) }
        val end = listOfNotNull(code?.range?.first, seasonCode.find(name)?.range?.first, episodeOnly.find(name)?.range?.first, absolute?.range?.first, year?.range?.first, quality.find(name)?.range?.first).minOrNull()
        val title = (end?.let { name.take(it) } ?: name.replace(Regex("""\.[a-z0-9]{2,4}$""", RegexOption.IGNORE_CASE), "")).trim(' ', '.', '-', '_', '(', '[', ')', ']')
        return Parsed(title, year?.value?.toIntOrNull(), season, episode)
    }

    private fun normalize(text: String): String = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
        .replace(Regex("""[\p{M}\s.\-_()\[\]:;,!?'"‘’“”–—]+"""), "")

    private fun sameTitle(a: String, b: String): Boolean {
        val left = normalize(a)
        val right = normalize(b)
        return left.length >= 2 && right.length >= 2 && (left.contains(right) || right.contains(left))
    }

    fun titleMatches(entry: LibraryEntry, query: Query): Boolean =
        (listOf(entry.name) + entry.files.map { it.name }).any { sameTitle(query.title, parse(it).title) }

    private fun check(text: String, query: Query): Boolean {
        val parsed = parse(text)
        if (!compatible(parsed, query)) return false
        if (query.type != "movie" && (query.season != null || query.episode != null) && parsed.season == null && parsed.episode == null) return false
        return sameTitle(query.title, parsed.title)
    }

    private fun compatible(parsed: Parsed, query: Query): Boolean {
        if (query.type == "movie" && query.year != null && parsed.year != null && kotlin.math.abs(query.year - parsed.year) > 1) return false
        if (query.season != null && parsed.season != null && query.season != parsed.season) return false
        if (query.episode != null && parsed.episode != null && query.episode != parsed.episode) return false
        return true
    }

    /** Filename, then container name, then PC's natural-order fallback for unnumbered packs. */
    fun match(entry: LibraryEntry, query: Query): Match? {
        val vids = entry.files.withIndex().filter { video.containsMatchIn(it.value.name) }
        vids.firstOrNull { check(it.value.name, query) }?.let { return Match(it.index) }
        val parent = parse(entry.name)
        if (sameTitle(query.title, parent.title) && compatible(parent, query) && query.type != "movie") {
            vids.firstOrNull {
                val p = parse(it.value.name)
                p.episode != null && compatible(p, query)
            }?.let { return Match(it.index) }
        }
        if (check(entry.name, query)) {
            // A pack's season alone must not silently select its first episode.
            if (query.type == "movie" || parse(entry.name).episode != null || vids.size == 1) {
                vids.firstOrNull { file ->
                    val p = parse(file.value.name)
                    compatible(p, query)
                }?.let { return Match(it.index, !check(it.value.name, query)) }
            }
        }
        val episodes = vids.filter { !extra.containsMatchIn(it.value.name) }
        if (query.type == "movie" || query.episode == null || !titleMatches(entry, query) || episodes.size < 2) return null
        val pack = parse(entry.name)
        if (query.season != null && pack.season != null && query.season != pack.season) return null
        // Explicit episode numbers outrank a guessed position; never relabel a different episode.
        if (episodes.any { parse(it.value.name).episode != null }) return null
        val chosen = episodes.sortedWith { a, b -> naturalCompare(a.value.name, b.value.name) }.getOrNull(query.episode - 1) ?: return null
        return Match(chosen.index, unverified = true)
    }

    internal fun naturalCompare(a: String, b: String): Int {
        val chunks = Regex("""\d+|\D+""")
        val left = chunks.findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
        val right = chunks.findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
        for (i in 0 until minOf(left.size, right.size)) {
            val x = left[i]
            val y = right[i]
            val compared = if (x.first().isDigit() && y.first().isDigit()) {
                val xn = x.trimStart('0').ifEmpty { "0" }
                val yn = y.trimStart('0').ifEmpty { "0" }
                xn.length.compareTo(yn.length).takeIf { it != 0 } ?: xn.compareTo(yn)
            } else x.compareTo(y)
            if (compared != 0) return compared
        }
        return left.size.compareTo(right.size)
    }

    fun stream(service: DebridService, entry: LibraryEntry, match: Match): Stream {
        val file = entry.files[match.fileIndex]
        val slug = slug(service)
        return Stream(
            name = file.name, title = "[${slug.uppercase(Locale.ROOT)}+] ${service.label} Library", infoHash = entry.hash,
            fileIdx = match.fileIndex, behaviorHints = StreamHints(filename = file.name, videoSize = file.size.takeIf { it > 0 }),
            library = LibrarySource(service, entry.id, file.id, entry.kind, match.unverified),
        )
    }

    fun slug(service: DebridService): String = when (service) {
        DebridService.RealDebrid -> "rd"
        DebridService.TorBox -> "tb"
        DebridService.AllDebrid -> "ad"
        DebridService.Premiumize -> "pm"
        DebridService.DebridLink -> "dl"
    }

    suspend fun streams(accounts: List<DebridAccount>, query: Query, onGroup: (suspend (AddonClient.AddonStreams) -> Unit)? = null): List<AddonClient.AddonStreams> = coroutineScope {
        accounts.map { account -> async(Dispatchers.Default) {
            val label = "${account.service.label} Library"
            val addon = Addon("harbor-library://${slug(account.service)}/manifest.json", Manifest("${slug(account.service)}-library", label))
            val group = try {
                withTimeoutOrNull(60_000) {
                    val entries = Debrid.listLibrary(account)
                    val matches = entries.mapNotNull { entry ->
                        val candidate = titleMatches(entry, query)
                        val detailed = if (entry.files.isEmpty() && candidate) Debrid.libraryFiles(account, entry) else entry
                        match(detailed, query)?.let { stream(account.service, detailed, it) }
                    }
                    AddonClient.AddonStreams(addon, matches)
                } ?: AddonClient.AddonStreams(addon, emptyList(), "$label timed out. Try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Provider response bodies can contain private links. Show only a safe, useful message.
                AddonClient.AddonStreams(addon, emptyList(), "Couldn't load $label. Check the account in Streaming services.")
            }
            onGroup?.invoke(group)
            group
        } }.awaitAll()
    }
}
