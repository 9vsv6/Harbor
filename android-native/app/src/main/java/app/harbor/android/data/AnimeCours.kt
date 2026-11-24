package app.harbor.android.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

object AnimeCours {
    internal fun linked(meta: Meta, relation: String): String? {
        val links = meta.links as? JsonArray ?: return null
        return links.mapNotNull { it as? JsonObject }.firstOrNull {
            it.string("category").equals("franchise", true) &&
                it.string("name")?.substringBefore(' ').equals(relation, true)
        }?.string("url")?.let { url ->
            Regex("/detail/series/([^/?#]+)").find(url)?.groupValues?.get(1)?.let {
                runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull()
            }
        }?.takeIf { Regex("(kitsu|mal|anilist):[1-9]\\d*").matches(it) }
    }

    /** Cours have their own identities; never merge them by their shared IMDb show id. */
    suspend fun entries(current: Meta, addons: List<Addon>): List<Meta> {
        val kitsu = current.id.takeIf { it.startsWith("kitsu:") } ?: AnimeInfo.ids(current.id)?.kitsu?.let { "kitsu:$it" }
        val sources = addons.sortedBy { it.manifest.id != AddonClient.KITSU_ID }
        suspend fun lookup(id: String): Meta? = withTimeoutOrNull(6_000) { AddonClient.meta(sources, "series", id) }
        val base = if (kitsu != null && kitsu != current.id) lookup(kitsu) ?: current else current
        return chain(base) { meta, relation ->
            try {
                val id = linked(meta, relation) ?: AnimeInfo.ids(meta.id)?.anilist?.let { anilist ->
                    AnimeInfo.extra(anilist)?.related?.firstOrNull { it.relation.equals(relation, true) }?.let { node ->
                        AnimeInfo.ids("anilist:${node.anilistId}")?.kitsu?.let { "kitsu:$it" }
                    }
                }
                id?.let { lookup(it) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        }
    }

    internal suspend fun chain(current: Meta, neighbour: suspend (Meta, String) -> Meta?): List<Meta> {
        val seen = mutableSetOf(current.id)
        val before = mutableListOf<Meta>()
        var cursor = current
        for (i in 0 until 10) {
            val previous = neighbour(cursor, "Prequel") ?: break
            if (!seen.add(previous.id)) break
            before.add(0, previous)
            cursor = previous
        }
        val after = mutableListOf<Meta>()
        cursor = current
        for (i in 0 until 20) {
            val next = neighbour(cursor, "Sequel") ?: break
            if (!seen.add(next.id)) break
            after += next
            cursor = next
        }
        return before + current + after
    }
}
