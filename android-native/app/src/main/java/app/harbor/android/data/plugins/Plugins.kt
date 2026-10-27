package app.harbor.android.data.plugins

import android.content.Context
import android.util.Log
import app.harbor.android.data.Addon
import app.harbor.android.data.AddonClient
import app.harbor.android.data.Manifest
import app.harbor.android.data.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.File
import java.net.URI

/**
 * Harbor's stream plugins on Android (src/lib/streams/plugins): repositories added by link, their
 * plugins installed into the app's storage, and the installed ones asked for streams beside your
 * addons. Android extensions (Cloudstream .cs3) run through [CloudstreamHost].
 */
object Plugins {
    private const val TAG = "HarborPlugins"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private lateinit var app: Context

    private val _state = MutableStateFlow(PluginState())
    val state: StateFlow<PluginState> = _state.asStateFlow()

    /** "Installing 3 of 43" per repository while a whole repository installs. */
    private val _progress = MutableStateFlow<Map<String, String>>(emptyMap())
    val progress: StateFlow<Map<String, String>> = _progress.asStateFlow()

    private val dir get() = File(app.filesDir, "plugins")
    private val stateFile get() = File(dir, "state.json")

    fun init(context: Context) {
        app = context.applicationContext
        com.lagradost.cloudstream3.CloudStreamApp.context = app
        _state.value = runCatching { Net.json.decodeFromString(PluginState.serializer(), stateFile.readText()) }.getOrDefault(PluginState())
    }

    private fun save(next: PluginState) {
        _state.value = next
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "state.json.tmp")
            tmp.writeText(Net.json.encodeToString(PluginState.serializer(), next))
            tmp.renameTo(stateFile) || run { stateFile.delete(); tmp.renameTo(stateFile) }
        }.onFailure { Log.w(TAG, "save failed", it) }
    }

    // ---------------- repositories (manifest.ts) ----------------

    /** Links in pasted text, split on spaces and commas (splitRepoLinks). */
    fun splitLinks(text: String) = text.split(Regex("[\\s,]+")).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** The addresses to try for a link: GitHub pages become raw files, a folder gets its manifest. */
    fun candidates(link: String): List<String> {
        var text = link.trim()
        require(!text.startsWith("cloudstreamrepo://", true)) { "Paste the repository's https link instead of the cloudstreamrepo:// one" }
        if (!Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(text)) text = "https://$text"
        val uri = runCatching { URI(text) }.getOrNull() ?: error("That isn't a link")
        require(uri.scheme.equals("https", true)) { "Only https links are supported" }
        var base = text.substringBefore('#')
        val host = uri.host.orEmpty().lowercase()
        val seg = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        var branches = listOf<String?>(null)
        if (host == "github.com" && seg.size >= 2) {
            val repo = seg[1].removeSuffix(".git")
            if (seg.size >= 4 && (seg[2] == "tree" || seg[2] == "blob")) {
                base = "https://raw.githubusercontent.com/${seg[0]}/$repo/${seg[3]}/${seg.drop(4).joinToString("/")}"
            } else {
                base = "https://raw.githubusercontent.com/${seg[0]}/$repo/BRANCH/${seg.drop(2).joinToString("/")}"
                branches = listOf("main", "master")
            }
        }
        val files = if (base.substringBefore('?').endsWith(".json", true)) listOf(base)
        else listOf("manifest.json", "repo.json").map { base.trimEnd('/') + "/" + it }
        return branches.flatMap { b -> files.map { if (b == null) it else it.replace("/BRANCH/", "/$b/") } }.distinct()
    }

    private suspend fun fetchJson(url: String): JsonElement = Net.json.parseToJsonElement(Net.get(url))

    private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    private fun JsonElement?.strList(): List<String> = when (this) {
        is JsonArray -> mapNotNull { it.str().ifBlank { null } }
        is JsonPrimitive -> listOfNotNull(str().ifBlank { null })
        else -> emptyList()
    }

    /** Resolves a file against its list, escaping spaces as a browser does ("Aia2tv 2.cs3"). */
    private fun resolve(file: String, against: String): String? =
        runCatching { URI(against.replace(" ", "%20")).resolve(file.trim().replace(" ", "%20")).toString() }.getOrNull()

    private val nativeMovie = setOf("movie", "animemovie", "documentary", "torrent")
    private val nativeSeries = setOf("tvseries", "anime", "ova", "cartoon", "asiandrama")
    private val nativeStatus = mapOf(0 to "down", 2 to "slow", 3 to "beta")

    /** nativeEntry: one Cloudstream plugins.json row. */
    private fun nativeEntry(o: JsonObject, listUrl: String): PluginEntry? {
        val entry = resolve(o["url"].str().ifBlank { return null }, listUrl) ?: return null
        val id = o["internalName"].str().ifBlank { entry.substringAfterLast('/').substringBeforeLast('.') }.ifBlank { return null }
        val status = (o["status"] as? JsonPrimitive)?.intOrNull ?: 1
        val tvTypes = o["tvTypes"].strList()
        val types = buildSet {
            tvTypes.forEach { t ->
                val k = t.lowercase().replace(Regex("[^a-z]"), "")
                if (k in nativeMovie) add("movie")
                if (k in nativeSeries) add("series")
            }
        }.ifEmpty { setOf("movie", "series") }
        val api = (o["apiVersion"] as? JsonPrimitive)?.intOrNull
        val note = listOfNotNull(nativeStatus[status], api?.takeIf { it != 1 }?.let { "api $it" }).joinToString(" · ").ifBlank { null }
        val version = (o["version"] as? JsonPrimitive)?.let { it.intOrNull?.toString() ?: it.contentOrNull } ?: "0"
        return PluginEntry(
            id = id, name = o["name"].str().ifBlank { id }, version = version, entry = entry, format = FORMAT_ANDROID,
            icon = o["iconUrl"].str().ifBlank { null }, description = o["description"].str().ifBlank { null },
            author = o["authors"].strList().joinToString(", ").ifBlank { null }, lang = o["language"].strList(),
            types = types.toList(), nsfw = tvTypes.any { it.equals("nsfw", true) }, enabled = status != 0, note = note,
        )
    }

    /** harborEntry / providerEntry: the JavaScript formats, listed so you can see what a repository has. */
    private fun scriptEntry(o: JsonObject, manifestUrl: String, format: String): PluginEntry? {
        val file = if (format == FORMAT_HARBOR) o["entry"].str() else o["filename"].str()
        val entry = resolve(file.ifBlank { return null }, manifestUrl) ?: return null
        val id = o["id"].str().ifBlank { file.substringBeforeLast('.') }.ifBlank { return null }
        return PluginEntry(
            id = id, name = o["name"].str().ifBlank { id }, version = o["version"].str().ifBlank { "0" }, entry = entry, format = format,
            icon = (o["icon"] ?: o["logo"]).str().ifBlank { null }, description = o["description"].str().ifBlank { null },
            author = o["author"].str().ifBlank { null }, lang = (o["lang"] ?: o["contentLanguage"]).strList(),
            nsfw = (o["nsfw"] as? JsonPrimitive)?.booleanOrNull == true, enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull != false,
        )
    }

    private fun repoTitle(name: String, url: String): String {
        if (name.isNotBlank() && name != "Repository") return name
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val seg = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        return if (uri.host.orEmpty().contains("github") && seg.size >= 2) "${seg[0]}/${seg[1]}" else uri.host.orEmpty().removePrefix("www.")
    }

    /** Reads a repository: Cloudstream (pluginLists or a plugins array), Harbor's own, or provider scripts. */
    private suspend fun readRepo(link: String): PluginRepo {
        var lastError: Throwable? = null
        for (url in candidates(link)) {
            val parsed = runCatching { fetchJson(url) }.getOrElse { lastError = it; continue }
            val obj = parsed as? JsonObject
            val array = parsed as? JsonArray ?: obj?.get("plugins") as? JsonArray
            val name = repoTitle(obj?.get("name").str(), url)
            val icon = obj?.get("iconUrl").str().ifBlank { obj?.get("icon").str() }.ifBlank { null }
            val description = obj?.get("description").str().ifBlank { null }
            val lists = (obj?.get("pluginLists") as? JsonArray)?.mapNotNull { v ->
                val raw = (v as? JsonPrimitive)?.contentOrNull ?: (v as? JsonObject)?.get("url").str()
                raw?.ifBlank { null }?.let { resolve(it, url) }
            }?.distinct()
            val nativeRows = array?.any { e -> (e as? JsonObject)?.let { it["url"].str().contains(".cs3") || it["internalName"] != null } == true } == true
            if (lists != null || nativeRows) {
                val entries = if (lists != null) coroutineScope {
                    lists.map { listUrl ->
                        async {
                            runCatching {
                                val list = fetchJson(listUrl)
                                ((list as? JsonArray) ?: (list as? JsonObject)?.get("plugins") as? JsonArray).orEmpty()
                                    .mapNotNull { (it as? JsonObject)?.let { o -> nativeEntry(o, listUrl) } }
                            }.getOrDefault(emptyList())
                        }
                    }.awaitAll().flatten()
                } else array.orEmpty().mapNotNull { (it as? JsonObject)?.let { o -> nativeEntry(o, url) } }
                return PluginRepo(url, name, FORMAT_ANDROID, icon, description, entries.distinctBy { it.id }, System.currentTimeMillis())
            }
            val scrapers = obj?.get("scrapers") as? JsonArray ?: array?.takeIf { a -> a.any { (it as? JsonObject)?.containsKey("filename") == true } }
            if (scrapers != null) {
                return PluginRepo(url, name, FORMAT_PROVIDER, icon, description,
                    scrapers.mapNotNull { (it as? JsonObject)?.let { o -> scriptEntry(o, url, FORMAT_PROVIDER) } }, System.currentTimeMillis())
            }
            if (array != null) {
                return PluginRepo(url, name, FORMAT_HARBOR, icon, description,
                    array.mapNotNull { (it as? JsonObject)?.let { o -> scriptEntry(o, url, FORMAT_HARBOR) } }, System.currentTimeMillis())
            }
            if (obj?.get("resources") != null && obj["types"] != null) error("That's a Stremio addon: add it under Addons instead")
            lastError = IllegalStateException("That link isn't a plugin repository")
        }
        throw lastError ?: IllegalStateException("That link isn't a plugin repository")
    }

    /** Adds every repository in `text`; answers one line per link that failed. */
    suspend fun addRepos(text: String): List<String> = withContext(Dispatchers.IO) {
        splitLinks(text).mapNotNull { link ->
            runCatching {
                val repo = readRepo(link)
                lock.withLock {
                    val now = _state.value
                    save(now.copy(repos = now.repos.filterNot { it.url == repo.url } + repo))
                }
            }.exceptionOrNull()?.let { "$link: ${it.message ?: "couldn't add it"}" }
        }
    }

    /** Re-reads a repository's list (Harbor's refresh button); installed plugins stay as they are. */
    suspend fun refresh(repoUrl: String) = withContext(Dispatchers.IO) {
        val result = runCatching { readRepo(repoUrl) }
        lock.withLock {
            val now = _state.value
            save(now.copy(repos = now.repos.map { r ->
                if (r.url != repoUrl) r else result.getOrNull()?.copy(url = r.url) ?: r.copy(error = result.exceptionOrNull()?.message)
            }))
        }
    }

    suspend fun removeRepo(repoUrl: String) {
        removeAll(repoUrl)
        lock.withLock { val now = _state.value; save(now.copy(repos = now.repos.filterNot { it.url == repoUrl })) }
    }

    // ---------------- installing ----------------

    fun idFor(repoUrl: String, entryId: String): String {
        var h = 0x811c9dc5.toInt()
        repoUrl.forEach { h = (h xor it.code) * 0x01000193 }
        return "plugin:${(h.toLong() and 0xffffffffL).toString(36)}.$entryId"
    }

    fun supported(entry: PluginEntry) = entry.format == FORMAT_ANDROID

    /** Downloads and installs one plugin (or updates it), then loads it to check it runs here. */
    suspend fun install(repo: PluginRepo, entry: PluginEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(supported(entry)) { "JavaScript plugins aren't supported on Android yet" }
            val id = idFor(repo.url, entry.id)
            val files = File(dir, "files").apply { mkdirs() }
            val target = File(files, id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".cs3")
            val bytes = Net.client.newCall(Request.Builder().url(entry.entry).header("User-Agent", Net.USER_AGENT).build()).execute().use { res ->
                check(res.isSuccessful) { "Download failed (${res.code})" }
                res.body?.bytes() ?: error("Empty download")
            }
            check(bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) { "That download isn't an extension archive" }
            CloudstreamHost.unload(id)
            if (target.exists()) { target.setWritable(true); target.delete() }
            File(files, "${target.name}.part").apply { writeBytes(bytes); renameTo(target) }
            val now = System.currentTimeMillis()
            var plugin = InstalledPlugin(
                id = id, entryId = entry.id, repoUrl = repo.url, repoName = repo.name, name = entry.name, version = entry.version,
                format = entry.format, file = target.absolutePath, icon = entry.icon, description = entry.description, lang = entry.lang,
                types = entry.types, nsfw = entry.nsfw, installedAt = _state.value.installed.firstOrNull { it.id == id }?.installedAt ?: now, updatedAt = now,
            )
            val loaded = CloudstreamHost.load(app, plugin)
            plugin = plugin.copy(error = loaded.exceptionOrNull()?.let { it.message ?: it.toString() }
                ?: "No sources registered".takeIf { loaded.getOrNull().isNullOrEmpty() })
            lock.withLock { val s = _state.value; save(s.copy(installed = s.installed.filterNot { it.id == id } + plugin)) }
        }
    }

    /** Installs every plugin of a repository not already installed, reporting "Installing 3 of 43". */
    fun installAll(repo: PluginRepo) = scope.launch {
        val todo = repo.entries.filter { supported(it) && it.enabled && _state.value.installed.none { p -> p.id == idFor(repo.url, it.id) } }
        todo.forEachIndexed { i, entry ->
            _progress.update { it + (repo.url to "Installing ${i + 1} of ${todo.size}") }
            install(repo, entry)
        }
        _progress.update { it - repo.url }
    }

    suspend fun uninstall(id: String) = withContext(Dispatchers.IO) {
        CloudstreamHost.unload(id)
        lock.withLock {
            val now = _state.value
            now.installed.firstOrNull { it.id == id }?.let { File(it.file).apply { setWritable(true); delete() } }
            save(now.copy(installed = now.installed.filterNot { it.id == id }))
        }
    }

    suspend fun removeAll(repoUrl: String) = _state.value.installed.filter { it.repoUrl == repoUrl }.forEach { uninstall(it.id) }

    suspend fun setEnabled(id: String, enabled: Boolean) = lock.withLock {
        val now = _state.value
        save(now.copy(installed = now.installed.map { if (it.id == id) it.copy(enabled = enabled) else it }))
    }

    // ---------------- streams ----------------

    /** A plugin as the picker's source groups see it: its name and icon, like an addon. */
    fun addonOf(plugin: InstalledPlugin) = Addon("plugin://${plugin.id}", Manifest(id = plugin.id, name = plugin.name, version = plugin.version, logo = plugin.icon))

    /** What the title is, for plugins that search by name (the desktop's StreamPluginRequest). */
    data class Title(val name: String, val year: Int?)

    private val titles = java.util.concurrent.ConcurrentHashMap<String, Title>()

    /** The id a title goes by without its episode part ("tt0903747", "kitsu:12"). */
    private fun baseId(id: String) = if (id.startsWith("tt")) id.substringBefore(':') else id.split(':').take(2).joinToString(":")

    /** Title pages note what they show, so a plugin can search by its name. */
    fun remember(id: String, name: String, year: String?) {
        if (name.isNotBlank()) titles[baseId(id)] = Title(name, year?.take(4)?.toIntOrNull())
    }

    /** The title for `id`: one a page noted, else Cinemeta's for an IMDb id. */
    suspend fun titleFor(type: String, id: String): Title? {
        val base = baseId(id)
        titles[base]?.let { return it }
        if (!base.startsWith("tt")) return null
        val kind = if (type == "movie") "movie" else "series"
        return runCatching {
            val meta = (Net.json.parseToJsonElement(Net.get("https://v3-cinemeta.strem.io/meta/$kind/$base.json")) as JsonObject)["meta"] as JsonObject
            Title(meta["name"].str(), (meta["releaseInfo"] ?: meta["year"]).str().take(4).toIntOrNull())
        }.getOrNull()?.takeIf { it.name.isNotBlank() }?.also { titles[base] = it }
    }

    val hasEnabled: Boolean get() = _state.value.installed.any { it.enabled && it.format == FORMAT_ANDROID }

    /**
     * Every enabled plugin's streams for `type`/`id`, as addon groups. `title` names what to search
     * for; without it there's nothing a plugin can match, so none is asked.
     */
    suspend fun streams(type: String, id: String, title: Title?): List<AddonClient.AddonStreams> {
        if (title == null || title.name.isBlank()) return emptyList()
        val plugins = _state.value.installed.filter { it.enabled && it.format == FORMAT_ANDROID && type in it.types.ifEmpty { listOf(type) } }
        if (plugins.isEmpty()) return emptyList()
        val parts = id.split(':')
        val series = type != "movie"
        val season = if (series) parts.getOrNull(parts.size - 2)?.toIntOrNull() else null
        val episode = if (series) parts.lastOrNull()?.toIntOrNull() else null
        val req = CloudstreamHost.LinkRequest(if (series) "series" else "movie", title.name, title.year, season, episode, null)
        return coroutineScope {
            plugins.map { plugin ->
                async(Dispatchers.IO) {
                    val apis = CloudstreamHost.load(app, plugin).getOrElse {
                        return@async AddonClient.AddonStreams(addonOf(plugin), emptyList(), it.message ?: "Couldn't load")
                    }
                    val found = apis.map { api -> async { CloudstreamHost.streams(api, req) } }.awaitAll().flatten()
                    AddonClient.AddonStreams(addonOf(plugin), found)
                }
            }.awaitAll()
        }
    }
}
