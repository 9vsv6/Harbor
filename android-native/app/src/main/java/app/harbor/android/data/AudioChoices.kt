package app.harbor.android.data

import android.content.Context
import app.harbor.android.player.EngineTrack
import kotlinx.serialization.Serializable
import java.util.Locale

/** Stable track attributes, never engine track IDs or playback URLs. */
@Serializable
data class AudioPick(val language: String?, val label: String, val detail: String?, val commentary: Boolean = false)

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
            putString("$scope|${digest(title)}", Net.json.encodeToString(AudioPick.serializer(), AudioPick(track.language, track.label, track.detail, track.commentary)))
        }.apply()
    }

    fun match(pick: AudioPick?, tracks: List<EngineTrack>): EngineTrack? {
        pick ?: return null
        return tracks.firstOrNull { sameLanguage(it.language, pick.language) && it.label == pick.label && it.detail == pick.detail && it.commentary == pick.commentary }
            ?: tracks.firstOrNull { !pick.language.isNullOrBlank() && sameLanguage(it.language, pick.language) && it.detail == pick.detail && it.commentary == pick.commentary }
            ?: tracks.firstOrNull { !pick.language.isNullOrBlank() && sameLanguage(it.language, pick.language) && it.commentary == pick.commentary }
            ?: tracks.firstOrNull { pick.language.isNullOrBlank() && it.label == pick.label && it.detail == pick.detail && it.commentary == pick.commentary }
    }

    fun languages(configured: List<String>, original: String?, anime: Boolean, device: String): List<String> =
        configured.ifEmpty { listOf(device) }.mapNotNull {
            if (it == "original") original?.takeIf(String::isNotBlank) ?: if (anime) "ja" else device else it
        }.distinct()

    fun sameLanguage(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        fun canonical(value: String): String {
            val base = value.lowercase(Locale.ROOT).substringBefore('-')
            val aliases = mapOf("fre" to "fra", "ger" to "deu", "chi" to "zho", "dut" to "nld", "cze" to "ces", "per" to "fas", "rum" to "ron", "gre" to "ell", "slo" to "slk")
            return aliases[base] ?: runCatching { Locale.forLanguageTag(base).isO3Language }.getOrNull()?.takeIf { it.isNotBlank() } ?: base
        }
        return canonical(a) == canonical(b)
    }
}
