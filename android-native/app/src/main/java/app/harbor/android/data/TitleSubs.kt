package app.harbor.android.data

import android.content.Context

/**
 * The subtitle you chose for a title, kept for that title only (Harbor PC's player-prefs.ts
 * `subLang` / `subsOff` per show): picking English for one film doesn't change your subtitle
 * languages, and turning subtitles off for one show doesn't turn them off everywhere.
 */
object TitleSubs {
    data class Pick(val lang: String?, val off: Boolean)

    private const val MAX = 300
    private var prefs: android.content.SharedPreferences? = null
    @Volatile private var profile = MAIN_PROFILE

    fun init(context: Context) {
        prefs = context.getSharedPreferences("harbor_title_subs", Context.MODE_PRIVATE)
    }

    fun useProfile(id: String?) {
        profile = id ?: MAIN_PROFILE
    }

    private fun key(metaId: String) = storageKey(profile, metaId)

    /** Preserve addon namespaces: kitsu:1 and kitsu:2 are different shows. */
    internal fun storageKey(profile: String, metaId: String): String {
        val parts = metaId.split(':')
        val title = when {
            Regex("^tt[0-9]+$").matches(parts.first()) -> parts.first()
            parts.first() in setOf("kitsu", "mal", "anilist", "anidb") && parts.size >= 2 -> parts.take(2).joinToString(":")
            parts.first() == "tmdb" && parts.size >= 3 && parts[1] in setOf("movie", "series", "tv") -> parts.take(3).joinToString(":")
            else -> metaId
        }
        return "$profile|$title"
    }

    fun get(metaId: String): Pick? {
        if (metaId.isEmpty()) return null
        val raw = prefs?.getString(key(metaId), null) ?: return null
        val value = raw.substringAfter('|')
        return if (value == "off") Pick(null, off = true) else Pick(value.ifEmpty { null }, off = false)
    }

    fun setLang(metaId: String, lang: String) = put(metaId, lang)

    fun setOff(metaId: String) = put(metaId, "off")

    /** Stored as "time|value", so the oldest titles go once there are too many. */
    private fun put(metaId: String, value: String) {
        val p = prefs ?: return
        if (metaId.isEmpty()) return
        val edit = p.edit().putString(key(metaId), "${System.currentTimeMillis()}|$value")
        val all = p.all
        if (all.size > MAX) {
            all.entries.sortedBy { (it.value as? String)?.substringBefore('|')?.toLongOrNull() ?: 0L }
                .take(all.size - MAX).forEach { edit.remove(it.key) }
        }
        edit.apply()
    }
}
