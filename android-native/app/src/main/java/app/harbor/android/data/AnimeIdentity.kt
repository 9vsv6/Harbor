package app.harbor.android.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/*
 * The optional offline anime identity database (Harbor PC 1.0.4: src-tauri/src/identity):
 * cedya77's anime-offline-database (ODbL 1.0 / DbCL 1.0, derived from manami-project's), turned
 * into a small local table of which Kitsu, MyAnimeList, AniList, AniDB and Simkl ids name the
 * same anime. Off by default; downloaded only when turned on, checked against the release's
 * SHA-256 digest, refreshed weekly while on, and deletable. Used to repair Continue watching
 * cards that name one anime by different sites, and to check tracker targets before syncing.
 */
object AnimeIdentity {
    data class State(
        val installed: Boolean = false, val entries: Int = 0, val builtAt: Long = 0, val version: String? = null,
        val busy: Boolean = false, val progress: String? = null, val error: String? = null,
    )

    private const val RELEASE = "https://api.github.com/repos/cedya77/anime-offline-database/releases/tags/latest"
    private const val ASSET = "anime-offline-database.jsonl.zst"
    private const val WEEK = 7L * 24 * 60 * 60_000
    private val SCHEMES = mapOf("anidb.net" to "anidb", "anilist.co" to "anilist", "kitsu.app" to "kitsu", "myanimelist.net" to "mal", "simkl.com" to "simkl")

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var db: SQLiteDatabase? = null
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

    private fun file(context: Context) = File(context.filesDir, "anime-identity.db")

    @Synchronized fun init(context: Context) {
        val f = file(context)
        if (!f.isFile) { _state.value = State(); return }
        runCatching {
            val opened = db ?: SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).also { db = it }
            fun meta(k: String) = opened.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(k)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            _state.value = State(true, meta("entries")?.toIntOrNull() ?: 0, meta("built_at")?.toLongOrNull() ?: 0, meta("version"))
        }.onFailure { remove(context); _state.value = State(error = "The anime identity database was unreadable and has been removed.") }
    }

    /** The other sites' ids for the same anime, as "scheme:id" (itself included), when the database is installed. */
    fun aliases(metaId: String): Set<String> {
        val scheme = metaId.substringBefore(':').takeIf { it in setOf("kitsu", "mal", "anilist", "anidb", "simkl") } ?: return emptySet()
        val id = metaId.substringAfter(':').substringBefore(':').takeIf { it.all(Char::isDigit) && it.isNotEmpty() } ?: return emptySet()
        val key = "$scheme:$id"
        return cache.getOrPut(key) {
            val d = db ?: return emptySet()
            runCatching {
                d.rawQuery("SELECT i2.scheme, i2.id FROM identities i1 JOIN identities i2 ON i1.entry = i2.entry WHERE i1.scheme=? AND i1.id=?", arrayOf(scheme, id)).use { c ->
                    buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
                }
            }.getOrDefault(emptyMap())
        }.map { (s, v) -> "$s:$v" }.toSet()
    }

    /** One site's id for an anime named by another (e.g. AniList's for a Kitsu entry), offline. */
    fun idOn(metaId: String, scheme: String): String? = aliases(metaId).firstOrNull { it.startsWith("$scheme:") }?.substringAfter(':')

    /** Builds it when missing or older than a week; quietly, while the option is on. */
    fun refreshIfStale(context: Context) {
        val s = _state.value
        if (!s.busy && (!s.installed || System.currentTimeMillis() - s.builtAt > WEEK)) download(context)
    }

    fun cancel() { job?.cancel() }

    @Synchronized fun remove(context: Context) {
        job?.cancel()
        db?.close(); db = null
        cache.clear()
        file(context).delete()
        File(context.filesDir, "anime-identity.db-journal").delete()
        File(context.filesDir, "anime-identity.db.part").delete()
        File(context.filesDir, "anime-identity.db.part-journal").delete()
        _state.value = State()
    }

    /** Downloads the latest release, verifies its digest and builds the database beside the old one. */
    @Synchronized fun download(context: Context) {
        if (_state.value.busy) return
        val app = context.applicationContext
        _state.value = _state.value.copy(busy = true, progress = "Checking the latest release…", error = null)
        job = scope.launch {
            val result = runCatching { build(app) }
            val f = file(app)
            if (result.isSuccess) {
                synchronized(this@AnimeIdentity) { db?.close(); db = null; cache.clear() }
                init(app)
            } else {
                val e = result.exceptionOrNull()
                _state.value = _state.value.copy(busy = false, progress = null,
                    error = if (e is kotlinx.coroutines.CancellationException) null else "Couldn't build the anime identity database: ${e?.message ?: "unknown error"}")
                if (!f.isFile) _state.value = _state.value.copy(installed = false)
            }
            _state.value = _state.value.copy(busy = false, progress = null)
        }
    }

    private suspend fun build(context: Context) = withContext(Dispatchers.IO) {
        val release = Net.json.parseToJsonElement(Net.get(RELEASE)).jsonObject
        // The tag is always "latest"; the publish date says which dataset this is.
        val version = (release["published_at"] as? JsonPrimitive)?.contentOrNull?.substringBefore('T')
            ?: (release["tag_name"] as? JsonPrimitive)?.contentOrNull ?: "latest"
        val asset = (release["assets"] as? JsonArray).orEmpty().map { it.jsonObject }
            .firstOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull == ASSET } ?: error("the release has no $ASSET")
        val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: error("no download address")
        val expected = asset["digest"]?.jsonPrimitive?.contentOrNull?.removePrefix("sha256:")?.lowercase()
            ?: error("the release has no SHA-256 digest")
        val pack = File(context.cacheDir, "anime-identity.jsonl.zst")
        _state.value = _state.value.copy(progress = "Downloading…")
        val digest = MessageDigest.getInstance("SHA-256")
        Net.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            check(r.isSuccessful) { "download failed (HTTP ${r.code})" }
            val body = r.body ?: error("empty download")
            pack.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); digest.update(buf, 0, n); total += n
                        _state.value = _state.value.copy(progress = "Downloading… ${total / 1_048_576} MB")
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == expected) { pack.delete(); "the download didn't match its SHA-256 digest" }

        _state.value = _state.value.copy(progress = "Building the database…")
        val temp = File(context.filesDir, "anime-identity.db.part")
        temp.delete()
        val out = SQLiteDatabase.openOrCreateDatabase(temp, null)
        var entries = 0
        try {
            out.execSQL("CREATE TABLE identities(scheme TEXT NOT NULL, id TEXT NOT NULL, entry INTEGER NOT NULL, PRIMARY KEY(scheme, id))")
            out.execSQL("CREATE TABLE entries(entry INTEGER PRIMARY KEY, title TEXT, type TEXT, episodes INTEGER)")
            out.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
            val identity = out.compileStatement("INSERT OR IGNORE INTO identities(scheme, id, entry) VALUES(?,?,?)")
            val entry = out.compileStatement("INSERT INTO entries(entry, title, type, episodes) VALUES(?,?,?,?)")
            out.beginTransaction()
            try {
                com.github.luben.zstd.ZstdInputStream(pack.inputStream().buffered()).bufferedReader().use { reader ->
                    reader.readLine() // The first line describes the dataset (its update date and licence).
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        if (entries % 2000 == 0) { coroutineContext.ensureActive(); _state.value = _state.value.copy(progress = "Building the database… $entries titles") }
                        val o = runCatching { Net.json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                        entries++
                        entry.bindLong(1, entries.toLong())
                        (o["title"] as? JsonPrimitive)?.contentOrNull?.let { entry.bindString(2, it) } ?: entry.bindNull(2)
                        (o["type"] as? JsonPrimitive)?.contentOrNull?.let { entry.bindString(3, it) } ?: entry.bindNull(3)
                        (o["episodes"] as? JsonPrimitive)?.intOrNull?.let { entry.bindLong(4, it.toLong()) } ?: entry.bindNull(4)
                        entry.executeInsert()
                        (o["sources"] as? JsonArray).orEmpty().forEach { source ->
                            val (scheme, id) = parse((source as? JsonPrimitive)?.contentOrNull ?: return@forEach) ?: return@forEach
                            identity.bindString(1, scheme); identity.bindString(2, id); identity.bindLong(3, entries.toLong())
                            identity.executeInsert()
                        }
                    }
                }
                check(entries > 1000) { "the dataset looked incomplete ($entries titles)" }
                listOf("entries" to "$entries", "built_at" to "${System.currentTimeMillis()}", "version" to version, "sha256" to actual).forEach { (k, v) ->
                    out.execSQL("INSERT INTO meta(key, value) VALUES(?, ?)", arrayOf(k, v))
                }
                out.setTransactionSuccessful()
            } finally { out.endTransaction() }
        } finally {
            out.close()
            pack.delete()
        }
        // Swapped in whole: a failed build leaves the previous database as it was.
        synchronized(this@AnimeIdentity) {
            db?.close(); db = null
            check(temp.renameTo(file(context)) || run { file(context).delete(); temp.renameTo(file(context)) }) { "couldn't save the database" }
            File(context.filesDir, "anime-identity.db.part-journal").delete()
        }
    }

    /** "https://kitsu.app/anime/1376" → ("kitsu", "1376"); other sites and non-numeric ids are ignored. */
    internal fun parse(url: String): Pair<String, String>? {
        val rest = url.removePrefix("https://").removePrefix("http://").takeIf { it != url } ?: return null
        val host = rest.substringBefore('/')
        val scheme = SCHEMES[host] ?: return null
        val id = rest.substringAfter('/', "").removePrefix("anime/").takeIf { rest.substringAfter('/').startsWith("anime/") }
            ?.split('/', '?', '#')?.firstOrNull()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return null
        return scheme to id
    }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
