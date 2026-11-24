package app.harbor.android.data

import android.content.Context
import app.harbor.android.player.EngineTrack
import kotlinx.serialization.Serializable
import java.util.Locale

/** Stable track attributes, never engine track IDs or playback URLs. */
@Serializable
data class AudioPick(
    val language: String?, val label: String, val detail: String?, val commentary: Boolean = false,
    val title: String? = null, val codec: String? = null, val channels: Int? = null,
)

object AudioChoices {
    fun scope(repository: Repository): String {
        val account = if (repository.accounts.nuvioActive) repository.accounts.nuvio.value.session?.userId
            else repository.account.value?.email
        return digest("${repository.accounts.shared.value.provider}|${account.orEmpty()}|${repository.profileKey("audio")}")
    }

    private fun digest(value: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    fun get(context: Context, scope: String, title: String): AudioPick? =
        context.getSharedPreferences("harbor_audio_choices", Context.MODE_PRIVATE).getString("$scope|${digest(title)}", null)
            ?.let { runCatching { Net.json.decodeFromString(AudioPick.serializer(), it) }.getOrNull() }

    fun save(context: Context, scope: String, title: String, track: EngineTrack) {
        if (title.isBlank()) return
        val prefs = context.getSharedPreferences("harbor_audio_choices", Context.MODE_PRIVATE)
        prefs.edit().apply {
            if (prefs.all.size >= 400) prefs.all.keys.take(40).forEach(::remove)
            putString("$scope|${digest(title)}", Net.json.encodeToString(AudioPick.serializer(), AudioPick(track.language, track.label, track.detail, track.commentary, track.title, track.codec, track.channels)))
        }.apply()
    }

    fun match(pick: AudioPick?, tracks: List<EngineTrack>): EngineTrack? {
        pick ?: return null
        fun normalized(value: String?) = value.orEmpty().trim().lowercase(Locale.ROOT)
        fun untagged(value: String?) = normalized(value) in setOf("", "und", "unknown")
        fun codec(value: String?) = normalized(value).replace(Regex("\\s*\\([^)]*\\)"), "").replace(Regex("[^a-z0-9]"), "")
        val eligible = tracks.filter { it.commentary == pick.commentary }
        val exact = eligible.filter {
            (sameLanguage(it.language, pick.language) || untagged(it.language) && untagged(pick.language)) &&
                normalized(it.title ?: it.label) == normalized(pick.title ?: pick.label) &&
                (if (pick.codec != null) codec(it.codec) == codec(pick.codec) && it.channels == pick.channels else it.detail == pick.detail)
        }
        if (exact.size == 1) return exact.single()
        // Decoder initialization can enrich codec/channel details. A unique muxed title is stable.
        if (untagged(pick.language)) {
            val title = normalized(pick.title ?: pick.label).takeIf { it.isNotEmpty() } ?: return null
            return eligible.filter { untagged(it.language) && normalized(it.title ?: it.label) == title }.singleOrNull()
        }
        return eligible.filter { sameLanguage(it.language, pick.language) && it.detail == pick.detail }.singleOrNull()
            ?: eligible.filter { sameLanguage(it.language, pick.language) }.singleOrNull()
    }

    fun languages(configured: List<String>, original: String?, anime: Boolean, device: String): List<String> =
        configured.ifEmpty { listOf(device) }.mapNotNull {
            if (it == "original") original?.takeIf(String::isNotBlank) ?: if (anime) "ja" else device else it
        }.distinct()

    private val titleLanguages by lazy {
        Locale.getISOLanguages().associateBy { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT) }
    }

    /** Untagged audio can still name its language before a codec or release description. */
    internal fun trackLanguage(track: EngineTrack): String? {
        track.language?.trim()?.takeUnless { it.lowercase(Locale.ROOT) in setOf("", "und", "unknown") }?.let { return it }
        val prefix = track.title?.split(Regex("[:|]|\\s+-\\s+"), limit = 2)?.firstOrNull()?.trim() ?: return null
        for (candidate in listOf(prefix, prefix.replace(Regex("\\s*\\([^()]*\\)$"), "").trim())) {
            val name = candidate.lowercase(Locale.ROOT)
            titleLanguages[name]?.let { return it }
            if (name.matches(Regex("[a-z]{2,3}(?:-[a-z]{2})?")) && Locale.getISOLanguages().any { sameLanguage(it, name) }) return name
        }
        return null
    }

    fun preferred(tracks: List<EngineTrack>, languages: List<String>): EngineTrack? =
        languages.firstNotNullOfOrNull { language -> tracks.firstOrNull { sameLanguage(trackLanguage(it), language) } }

    fun sameLanguage(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        fun canonical(value: String): String {
            val base = value.trim().lowercase(Locale.ROOT).replace('_', '-').substringBefore('-')
            val aliases = mapOf("fre" to "fra", "ger" to "deu", "chi" to "zho", "dut" to "nld", "cze" to "ces", "per" to "fas", "rum" to "ron", "gre" to "ell", "slo" to "slk")
            return aliases[base] ?: runCatching { Locale.forLanguageTag(base).isO3Language }.getOrNull()?.takeIf { it.isNotBlank() } ?: base
        }
        return canonical(a) == canonical(b)
    }
}
