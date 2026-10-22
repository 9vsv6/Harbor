package app.harbor.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Harbor's own subtitle sources (src/lib/subtitles: SubDL, Subsource, Wyzie, OpenSubtitles),
 * each with the user's key. Results come back as local files, ready for the player, so a
 * zipped subtitle is opened here. Only a few per language: each is a download.
 */
object SubtitleSources {
    data class Query(val imdb: String, val series: Boolean, val season: Int?, val episode: Int?, val languages: List<String>)

    private const val PER_LANGUAGE = 2

    suspend fun search(cache: File, q: Query, settings: AppSettings, keys: Map<String, String>): List<Subtitle> = coroutineScope {
        val dir = File(cache, "subs").apply { mkdirs() }
        val langs = q.languages.map { it.substringBefore('-').lowercase() }.distinct().take(4)
        if (langs.isEmpty()) return@coroutineScope emptyList()
        listOfNotNull<kotlinx.coroutines.Deferred<List<Subtitle>>>(
            keys[ProviderKey.SUBDL]?.takeIf { settings.subdlEnabled }?.let { key -> async { runCatching { subdl(dir, q, langs, key) }.getOrDefault(emptyList()) } },
            keys[ProviderKey.SUBSOURCE]?.takeIf { settings.subsourceEnabled }?.let { key -> async { runCatching { subsource(dir, q, langs, key) }.getOrDefault(emptyList()) } },
            if (settings.wyzieEnabled) async { runCatching { wyzie(q, langs, keys[ProviderKey.WYZIE]) }.getOrDefault(emptyList()) } else null,
            keys[ProviderKey.OPENSUBTITLES]?.takeIf { settings.openSubtitlesEnabled }?.let { key -> async { runCatching { openSubtitles(dir, q, langs, key) }.getOrDefault(emptyList()) } },
        ).awaitAll().flatten()
    }

    // ---- SubDL (api.subdl.com): zips from dl.subdl.com ----

    private suspend fun subdl(dir: File, q: Query, langs: List<String>, key: String): List<Subtitle> {
        val url = buildString {
            append("https://api.subdl.com/api/v1/subtitles?subs_per_page=30&api_key=").append(key)
            append("&imdb_id=").append(q.imdb).append("&type=").append(if (q.series) "tv" else "movie")
            append("&languages=").append(langs.joinToString(",") { it.uppercase() })
            q.season?.let { append("&season_number=").append(it) }
            q.episode?.let { append("&episode_number=").append(it) }
        }
        val body = Net.json.parseToJsonElement(Net.get(url)).jsonObject
        val rows = (body["subtitles"] as? JsonArray).orEmptyObjects()
        return langs.flatMap { lang ->
            rows.filter { code(it.string("language") ?: it.string("lang")) == lang }.take(PER_LANGUAGE).mapNotNull { row ->
                val path = row.string("url") ?: return@mapNotNull null
                val file = unzip(dir, "subdl-" + path.substringAfterLast('/').substringBefore('.'), Request.Builder().url("https://dl.subdl.com$path").build(), q) ?: return@mapNotNull null
                Subtitle("SubDL · " + (row.string("release_name") ?: row.string("name") ?: lang), file, lang)
            }
        }
    }

    // ---- Subsource (api.subsource.net): the title, then its subtitles, as zips ----

    private suspend fun subsource(dir: File, q: Query, langs: List<String>, key: String): List<Subtitle> {
        val base = "https://api.subsource.net/api/v1"
        fun get(url: String) = Request.Builder().url(url).header("X-API-Key", key).build()
        val search = "$base/movies/search?searchType=imdb&imdb=${q.imdb}&type=${if (q.series) "series" else "movie"}" + (q.season?.let { "&season=$it" } ?: "")
        val movies = (Net.json.parseToJsonElement(Net.call(get(search))).jsonObject["data"] as? JsonArray).orEmptyObjects()
        val movieId = movies.firstOrNull()?.let { it["movieId"]?.jsonPrimitive?.content } ?: return emptyList()
        return langs.flatMap { lang ->
            val name = Locale(lang).getDisplayLanguage(Locale.ENGLISH).lowercase().replace(' ', '_')
            val rows = (Net.json.parseToJsonElement(Net.call(get("$base/subtitles?movieId=$movieId&limit=30&sort=rating&language=$name"))).jsonObject["data"] as? JsonArray).orEmptyObjects()
            rows.filter { r -> q.episode == null || r.string("releaseInfo").orEmpty().contains(episodeTag(q), true) || rows.size == 1 }
                .ifEmpty { rows }.take(PER_LANGUAGE).mapNotNull { row ->
                    val id = row["subtitleId"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val file = unzip(dir, "subsource-$id", get("$base/subtitles/$id/download"), q) ?: return@mapNotNull null
                    Subtitle("Subsource · " + (row.string("releaseInfo")?.lines()?.firstOrNull() ?: lang), file, lang)
                }
        }
    }

    // ---- Wyzie (sub.wyzie.io): plain subtitle URLs ----

    private suspend fun wyzie(q: Query, langs: List<String>, key: String?): List<Subtitle> {
        val url = buildString {
            append("https://sub.wyzie.io/search?source=all&id=").append(q.imdb).append("&language=").append(langs.joinToString(","))
            q.season?.let { append("&season=").append(it) }
            q.episode?.let { append("&episode=").append(it) }
            key?.let { append("&key=").append(it) }
        }
        val rows = (Net.json.parseToJsonElement(Net.get(url)) as? JsonArray).orEmptyObjects()
        return langs.flatMap { lang ->
            rows.filter { code(it.string("language")) == lang }.take(PER_LANGUAGE).mapNotNull { row ->
                Subtitle("Wyzie · " + (row.string("release") ?: row.string("display") ?: lang), row.string("url") ?: return@mapNotNull null, lang)
            }
        }
    }

    // ---- OpenSubtitles (api.opensubtitles.com): search, then ask for each file's link ----

    private suspend fun openSubtitles(dir: File, q: Query, langs: List<String>, key: String): List<Subtitle> {
        val base = "https://api.opensubtitles.com/api/v1"
        fun headers(b: Request.Builder) = b.header("Api-Key", key).header("User-Agent", "Harbor v0.8").header("Accept", "application/json")
        val url = "$base/subtitles?imdb_id=${q.imdb.removePrefix("tt").trimStart('0')}&languages=${langs.joinToString(",")}" +
            (q.season?.let { "&season_number=$it" } ?: "") + (q.episode?.let { "&episode_number=$it" } ?: "")
        val rows = (Net.json.parseToJsonElement(Net.call(headers(Request.Builder().url(url)).build())).jsonObject["data"] as? JsonArray).orEmptyObjects()
        return langs.flatMap { lang ->
            rows.mapNotNull { it["attributes"] as? JsonObject }.filter { code(it.string("language")) == lang }.take(PER_LANGUAGE).mapNotNull { attrs ->
                val fileId = ((attrs["files"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("file_id")?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val target = File(dir, "opensubtitles-$fileId.srt")
                if (!target.exists()) {
                    val body = buildJsonObject { put("file_id", fileId) }.toString().toRequestBody("application/json".toMediaType())
                    val link = Net.json.parseToJsonElement(Net.call(headers(Request.Builder().url("$base/download").post(body)).build())).jsonObject.string("link") ?: return@mapNotNull null
                    withContext(Dispatchers.IO) { target.writeBytes(Net.client.newCall(Request.Builder().url(link).build()).execute().use { it.body?.bytes() ?: ByteArray(0) }) }
                }
                Subtitle("OpenSubtitles · " + (attrs.string("release") ?: lang), target.toURI().toString(), lang)
            }
        }
    }

    // ---- Helpers ----

    private fun episodeTag(q: Query) = "S%02dE%02d".format(q.season ?: 1, q.episode ?: 1)

    /** A language name or code ("english", "EN", "eng") as its two-letter code. */
    private fun code(raw: String?): String? {
        val v = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (v.length == 2) return v
        Locale.getAvailableLocales().firstOrNull { it.getDisplayLanguage(Locale.ENGLISH).equals(v.replace('_', ' '), true) }?.let { return it.language }
        return runCatching { Locale.getAvailableLocales().firstOrNull { it.isO3Language == v }?.language }.getOrNull() ?: v
    }

    /** Downloads a zip and keeps its subtitle (the one for this episode, if it holds several). */
    private suspend fun unzip(dir: File, name: String, request: Request, q: Query): String? = withContext(Dispatchers.IO) {
        dir.listFiles()?.firstOrNull { it.name.startsWith("$name.") }?.let { return@withContext it.toURI().toString() }
        val bytes = Net.client.newCall(request).execute().use { if (it.isSuccessful) it.body?.bytes() else null } ?: return@withContext null
        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { e ->
                val ext = e.name.substringAfterLast('.', "").lowercase()
                if (!e.isDirectory && ext in setOf("srt", "ass", "ssa", "vtt")) entries += e.name to zip.readBytes()
            }
        }
        // Not a zip: the subtitle itself.
        if (entries.isEmpty() && bytes.isNotEmpty() && bytes[0] != 'P'.code.toByte()) entries += "$name.srt" to bytes
        val tag = episodeTag(q)
        val (entry, data) = entries.firstOrNull { q.episode != null && it.first.contains(tag, true) } ?: entries.firstOrNull() ?: return@withContext null
        val file = File(dir, "$name." + entry.substringAfterLast('.', "srt").lowercase())
        file.writeBytes(data)
        file.toURI().toString()
    }
}

private fun JsonArray?.orEmptyObjects(): List<JsonObject> = this?.mapNotNull { it as? JsonObject }.orEmpty()
