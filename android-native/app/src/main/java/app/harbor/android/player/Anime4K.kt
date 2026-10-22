package app.harbor.android.player

import android.content.Context
import app.harbor.android.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Anime4K for mpv, as Harbor's desktop player has it (`src/lib/player/anime4k-modes.ts`,
 * `src-tauri/src/anime4k.rs`): the same modes and shader chains, downloaded once from
 * the Anime4K project (MIT) into the app's storage.
 */
object Anime4K {
    enum class Mode(val label: String, val sub: String) {
        A("Mode A", "Restore + upscale. The best all-rounder for most anime."),
        B("Mode B", "Softer restore. Kinder to compressed or noisy sources."),
        C("Mode C", "Denoise + upscale. Lightest, cleanest on already-sharp video."),
        AA("Mode A+A", "Double restore. Sharpest detail, for high-quality sources."),
        BB("Mode B+B", "Double soft restore. For heavy compression artifacts."),
        CA("Mode C+A", "Denoise then restore. Balanced cleanup and detail."),
    }

    private const val BASE = "https://raw.githubusercontent.com/bloc97/Anime4K/master/glsl"

    /** Remote path under glsl/ for each shader file. */
    private val FILES = mapOf(
        "Anime4K_Clamp_Highlights.glsl" to "Restore/Anime4K_Clamp_Highlights.glsl",
        "Anime4K_Restore_CNN_VL.glsl" to "Restore/Anime4K_Restore_CNN_VL.glsl",
        "Anime4K_Restore_CNN_M.glsl" to "Restore/Anime4K_Restore_CNN_M.glsl",
        "Anime4K_Restore_CNN_Soft_VL.glsl" to "Restore/Anime4K_Restore_CNN_Soft_VL.glsl",
        "Anime4K_Restore_CNN_Soft_M.glsl" to "Restore/Anime4K_Restore_CNN_Soft_M.glsl",
        "Anime4K_Upscale_CNN_x2_VL.glsl" to "Upscale/Anime4K_Upscale_CNN_x2_VL.glsl",
        "Anime4K_Upscale_CNN_x2_M.glsl" to "Upscale/Anime4K_Upscale_CNN_x2_M.glsl",
        "Anime4K_Upscale_Denoise_CNN_x2_VL.glsl" to "Upscale%2BDenoise/Anime4K_Upscale_Denoise_CNN_x2_VL.glsl",
        "Anime4K_Upscale_Denoise_CNN_x2_M.glsl" to "Upscale%2BDenoise/Anime4K_Upscale_Denoise_CNN_x2_M.glsl",
        "Anime4K_AutoDownscalePre_x2.glsl" to "Upscale/Anime4K_AutoDownscalePre_x2.glsl",
        "Anime4K_AutoDownscalePre_x4.glsl" to "Upscale/Anime4K_AutoDownscalePre_x4.glsl",
    )

    private const val CLAMP = "Anime4K_Clamp_Highlights.glsl"
    private const val D2 = "Anime4K_AutoDownscalePre_x2.glsl"
    private const val D4 = "Anime4K_AutoDownscalePre_x4.glsl"
    private const val UPSCALE_M = "Anime4K_Upscale_CNN_x2_M.glsl"
    private const val RESTORE_M = "Anime4K_Restore_CNN_M.glsl"
    private const val RESTORE_SOFT_M = "Anime4K_Restore_CNN_Soft_M.glsl"

    /** Harbor's chains; "hq" uses the VL networks, "fast" the M ones (better for phones). */
    private fun chain(mode: Mode, hq: Boolean): List<String> {
        val big = if (hq) "VL" else "M"
        val restore = "Anime4K_Restore_CNN_$big.glsl"
        val restoreSoft = "Anime4K_Restore_CNN_Soft_$big.glsl"
        val upscale = "Anime4K_Upscale_CNN_x2_$big.glsl"
        val denoise = "Anime4K_Upscale_Denoise_CNN_x2_$big.glsl"
        return when (mode) {
            Mode.A -> listOf(CLAMP, restore, upscale, D2, D4, UPSCALE_M)
            Mode.B -> listOf(CLAMP, restoreSoft, upscale, D2, D4, UPSCALE_M)
            Mode.C -> listOf(CLAMP, denoise, D2, D4, UPSCALE_M)
            Mode.AA -> listOf(CLAMP, restore, upscale, RESTORE_M, D2, D4, UPSCALE_M)
            Mode.BB -> listOf(CLAMP, restoreSoft, upscale, D2, RESTORE_SOFT_M, D4, UPSCALE_M)
            Mode.CA -> listOf(CLAMP, denoise, D2, D4, RESTORE_M, UPSCALE_M)
        }
    }

    private fun dir(context: Context) = File(context.filesDir, "anime4k")

    /** Paths of the mode's shaders, downloading any that are missing. */
    suspend fun shaders(context: Context, mode: Mode, hq: Boolean): List<String> = withContext(Dispatchers.IO) {
        val folder = dir(context).apply { mkdirs() }
        chain(mode, hq).map { name ->
            val file = File(folder, name)
            if (!file.exists() || file.length() == 0L) download(name, file)
            file.absolutePath
        }
    }

    private fun download(name: String, target: File) {
        val remote = FILES[name] ?: throw IOException("Unknown shader $name")
        val request = Request.Builder().url("$BASE/$remote").header("User-Agent", Net.USER_AGENT).build()
        Net.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Couldn't download $name (HTTP ${response.code})")
            val body = response.body?.string().orEmpty()
            // A real shader starts with mpv hook directives; anything else is an error page.
            if ("//!" !in body) throw IOException("$name didn't look like a shader")
            val partial = File(target.parentFile, "$name.part")
            partial.writeText(body)
            partial.renameTo(target)
        }
    }
}
