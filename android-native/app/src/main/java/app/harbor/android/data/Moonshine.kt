package app.harbor.android.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File

/*
 * Moonshine speech recognition for subtitle Auto sync, as Harbor PC 1.0.4
 * (src/lib/subtitles/autosync/moonshine-models.ts and src-tauri/src/subsync/moonshine*): one
 * small "tiny streaming" model per language, with word timestamps, downloaded only when the user
 * asks. PC runs it through a Python worker; here the Moonshine Voice Android library (MIT, as are
 * all streaming models) runs it in-process. Whisper Tiny stays for every other language.
 */
object Moonshine {
    /** The languages PC offers, in PC's order. */
    val LANGUAGES = listOf(
        "en" to "English", "ar" to "Arabic", "de" to "German", "es" to "Spanish",
        "ja" to "Japanese", "zh" to "Mandarin", "tl" to "Tagalog", "vi" to "Vietnamese",
    )
    private val codes = LANGUAGES.map { it.first }.toSet()

    fun isSupported(code: String?) = code != null && code in codes
    fun name(code: String) = LANGUAGES.firstOrNull { it.first == code }?.second ?: code

    /** Moonshine's "tiny streaming" architecture, the one PC downloads (MOONSHINE_MODEL_ARCH_TINY_STREAMING). */
    const val ARCH = 2

    /** The native runtime loads on this device (its libraries ship for arm64, armeabi-v7a and x86_64). */
    val available: Boolean by lazy {
        runCatching { Class.forName("ai.moonshine.voice.JNI", true, Moonshine::class.java.classLoader) }.isSuccess
    }

    private val titlePrefix = Regex(
        """^(English|Arabic|German|Spanish|Japanese|Mandarin|Chinese|Tagalog|Filipino|Vietnamese)(?:\s*\([^)]{1,40}\))?\s*(?::|[-|]|$)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The model language for a video, as PC's resolveMoonshineLanguage: an explicit choice wins;
     * then the selected audio track's language tag; only with no usable tag, a track title that
     * starts with the language's name ("English : DTS-HD 5.1"). Never the subtitles or the title.
     */
    fun resolve(preference: String?, audioTrackLanguage: String?, audioTrackTitle: String? = null): String? {
        if (!preference.isNullOrBlank() && preference != "auto") return preference.substringBefore('-').lowercase().takeIf(::isSupported)
        val tagged = audioTrackLanguage?.let { LanguageCodes.code(it) }?.substringBefore('-')?.lowercase().orEmpty()
        if (tagged.isNotEmpty() && tagged !in setOf("und", "mul", "zxx", "mis")) return twoLetter(tagged).takeIf(::isSupported)
        val name = titlePrefix.find(audioTrackTitle?.trim().orEmpty())?.groupValues?.get(1)?.lowercase() ?: return null
        return when (name) {
            "chinese" -> "zh"
            "filipino" -> "tl"
            else -> LANGUAGES.firstOrNull { it.second.lowercase() == name }?.first
        }
    }

    /** ISO 639-2 (what mpv and MKV tracks carry: "eng", "jpn", "ger") to 639-1, as PC's normalizeLang. */
    private val threeToTwo: Map<String, String> by lazy {
        java.util.Locale.getISOLanguages().associateBy { runCatching { java.util.Locale(it).isO3Language }.getOrDefault("") } +
            mapOf("ger" to "de", "chi" to "zh", "cmn" to "zh", "fil" to "tl", "fre" to "fr", "dut" to "nl", "per" to "fa", "cze" to "cs", "gre" to "el", "rum" to "ro", "slo" to "sk", "arm" to "hy", "baq" to "eu", "bur" to "my", "geo" to "ka", "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms", "tib" to "bo", "wel" to "cy", "alb" to "sq")
    }

    private fun twoLetter(code: String) = if (code.length == 3) threeToTwo[code] ?: code else code

    /** The message that offers a language's model in the player, as PC's wording. */
    fun missingModelMessage(language: String) =
        "The ${name(language)} Moonshine model is not installed. No model was downloaded. Silero remains available."

    /** The language named in [missingModelMessage], if this is one. */
    fun missingModelLanguage(message: String): String? =
        LANGUAGES.firstOrNull { (_, label) -> message.startsWith("The $label Moonshine model is not installed") }?.first
}

/** Installed Moonshine models (one folder per language) and the one download at a time. */
object MoonshineModels {
    data class State(
        val installed: Set<String> = emptySet(),
        /** The language downloading now. */
        val downloading: String? = null,
        val file: Int = 0,
        val files: Int = 0,
        val received: Long = 0,
        val total: Long = -1,
        /** A language whose model Auto sync is using. */
        val inUse: String? = null,
        val error: Pair<String, String>? = null,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var root: File? = null
    private const val MARKER = "harbor-moonshine.json"

    private fun dir(context: Context, language: String) = File(File(context.filesDir, "moonshine"), language)

    @Synchronized fun init(context: Context) {
        val base = File(context.filesDir, "moonshine").also { root = it }
        val installed = Moonshine.LANGUAGES.map { it.first }.filter { File(File(base, it), MARKER).isFile }.toSet()
        _state.value = _state.value.copy(installed = installed)
    }

    fun installed(language: String) = language in _state.value.installed

    /** The language's model folder, held until [release]; null when it isn't installed or another sync has it. */
    @Synchronized fun acquire(language: String): File? {
        val base = root ?: return null
        if (!installed(language) || _state.value.inUse != null) return null
        _state.value = _state.value.copy(inUse = language)
        return File(base, language)
    }

    @Synchronized fun release() { _state.value = _state.value.copy(inUse = null) }

    @Synchronized fun remove(context: Context, language: String) {
        val s = _state.value
        if (s.downloading == language || s.inUse == language) return
        dir(context, language).deleteRecursively()
        _state.value = s.copy(installed = s.installed - language, error = null)
    }

    fun cancelDownload() { job?.cancel() }

    /** Downloads one language's model (with word timestamps). Only ever started by the user. */
    @Synchronized fun download(context: Context, language: String) {
        if (!Moonshine.isSupported(language) || _state.value.downloading != null) return
        if (!Moonshine.available) {
            _state.value = _state.value.copy(error = language to "Moonshine isn't available on this device.")
            return
        }
        val target = dir(context.applicationContext, language)
        _state.value = _state.value.copy(downloading = language, file = 0, files = 0, received = 0, total = -1, error = null)
        job = scope.launch {
            val result = runCatching {
                runInterruptible {
                    target.mkdirs()
                    File(target, MARKER).delete()
                    val spec = ai.moonshine.voice.ModelSpec.stt(language, Moonshine.ARCH, false, true)
                    val downloader = ai.moonshine.voice.AssetDownloader(Net.client)
                    downloader.ensureModelPresent(target, spec) { _, index, total, done, size ->
                        _state.value = _state.value.copy(file = index, files = total, received = done, total = size)
                    }
                    if (!downloader.isModelPresent(target, spec)) error("The model download is incomplete.")
                    File(target, MARKER).writeText("""{"language":"$language","arch":${Moonshine.ARCH}}""")
                }
            }
            synchronized(this@MoonshineModels) {
                val s = _state.value
                _state.value = result.fold(
                    { s.copy(downloading = null, installed = s.installed + language) },
                    { e ->
                        if (e is kotlinx.coroutines.CancellationException) s.copy(downloading = null)
                        else s.copy(downloading = null, error = language to "Moonshine operation failed: ${e.message ?: "download failed"}")
                    },
                )
            }
        }
    }

    /** The size of what's on disk for a language, for the settings row. */
    fun sizeOnDisk(context: Context, language: String): Long =
        dir(context, language).walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
