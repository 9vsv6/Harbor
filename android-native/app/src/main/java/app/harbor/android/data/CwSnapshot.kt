package app.harbor.android.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * "Copy CW" (Harbor PC 1.0.4's lib/cw-share-snapshot.ts): a snapshot of what fed Continue
 * watching, to paste into a bug report. No auth keys, emails, profile ids, tokens, stream URLs
 * or file paths; it does include titles, times and progress, as the privacy line says.
 */
internal object CwSnapshot {
    private val safe = Regex("^[a-z0-9][a-z0-9:._-]{0,175}$", RegexOption.IGNORE_CASE)

    /** An id as it is, or "[redacted-id]" when it could be a URL, a path or encoded data. */
    fun safeId(value: String?): JsonElement = when {
        value.isNullOrEmpty() -> JsonNull
        safe.matches(value) -> JsonPrimitive(value)
        else -> JsonPrimitive("[redacted-id]")
    }

    private fun card(item: CwItem, depth: Int = 0): JsonObject = buildJsonObject {
        put("id", safeId(item.meta.id))
        put("type", item.meta.type)
        put("name", item.meta.name)
        put("source", item.source ?: "harbor")
        put("videoId", safeId(item.videoId))
        put("season", item.season)
        put("episode", item.episode)
        put("offset", item.positionMs)
        put("duration", item.durationMs)
        item.fraction?.let { put("fraction", it) }
        put("updatedAt", item.updatedAt)
        if (item.altIds.isNotEmpty()) put("altIds", JsonArray(item.altIds.sorted().map(::safeId)))
        if (depth < 2 && item.members.isNotEmpty()) put("members", JsonArray(item.members.map { card(it, depth + 1) }))
    }

    private fun library(item: LibraryItem): JsonObject = buildJsonObject {
        put("id", safeId(item.id))
        put("type", item.type)
        put("name", item.name)
        put("removed", item.removed)
        put("temp", item.temp)
        put("mtime", item.mtime)
        put("state", item.state?.let { s ->
            buildJsonObject {
                put("videoId", safeId(s.videoId))
                put("offset", s.timeOffset)
                put("duration", s.duration)
                put("lastWatched", s.lastWatched)
                put("flaggedWatched", s.flaggedWatched)
                put("timesWatched", s.timesWatched)
            }
        } ?: JsonNull)
    }

    fun build(
        build: String,
        flags: Map<String, JsonElement>,
        local: Map<String, Resume>,
        library: List<LibraryItem>,
        trakt: List<CwItem>,
        external: List<CwItem>,
        dismissed: Set<String>,
        displayed: List<CwItem>,
        now: Long = System.currentTimeMillis(),
    ): JsonObject = buildJsonObject {
        put("format", "harbor-cw-snapshot")
        put("version", 1)
        put("platform", "android")
        put("build", build)
        put("capturedAt", java.time.Instant.ofEpochMilli(now).toString())
        put("privacy", "Includes viewing titles, timestamps and playback progress. Review before sharing.")
        put("flags", JsonObject(flags))
        put("inputs", buildJsonObject {
            put("local", JsonArray(local.entries.sortedByDescending { it.value.updatedAt }.map { (videoId, r) ->
                buildJsonObject {
                    put("videoId", safeId(videoId))
                    put("id", safeId(r.metaId))
                    put("type", r.type)
                    put("name", r.name)
                    put("offset", r.positionMs)
                    put("duration", r.durationMs)
                    put("updatedAt", r.updatedAt)
                }
            }))
            // Only entries that could feed the row: something played or in progress.
            put("library", JsonArray(library.filter { (it.state?.timeOffset ?: 0) > 0 || it.state?.lastWatched != null }.map(::library)))
            put("trakt", JsonArray(trakt.map { card(it) }))
            put("external", JsonArray(external.map { card(it) }))
            put("dismissed", JsonArray(dismissed.sorted().map { key -> JsonPrimitive(key.split('@').joinToString("@") { part -> (safeId(part) as? JsonPrimitive)?.content ?: "" }) }))
        })
        put("displayed", JsonArray(displayed.map { card(it) }))
    }
}
