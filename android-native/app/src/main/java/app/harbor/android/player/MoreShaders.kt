package app.harbor.android.player

import android.content.Context
import app.harbor.android.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Harbor's More shaders (src/lib/player/shader-catalog.ts, src-tauri/src/shaders.rs): optional
 * mpv picture shaders downloaded from their authors, run in Harbor's stage order. hdr-toys is
 * left out: it needs mpv's gpu-next renderer, which Harbor's Android mpv doesn't use.
 */
object MoreShaders {
    enum class Stage(val label: String, val order: Int) {
        Prescale("Upscaling", 10), Restore("Detail recovery", 20), Chroma("Color", 30), Sharpen("Sharpening", 40),
    }

    class Variant(val id: String, val label: String, val sub: String, val file: String)

    class Entry(
        val id: String,
        val name: String,
        val category: String,
        /** "all" or "anime". */
        val content: String,
        /** fast, quality, heavy. */
        val tier: String,
        val description: String,
        val stage: Stage,
        val sourceLabel: String,
        val sourceUrl: String,
        /** Where the files download from. */
        val base: String,
        val file: String,
        val variants: List<Variant> = emptyList(),
    )

    val CATALOG = listOf(
        Entry("fsrcnnx", "FSRCNNX", "Neural upscaler", "all", "quality",
            "A neural network that doubles luma resolution. The sharpest general-purpose upscaler, strong on both live action and anime. Pick one variant.",
            Stage.Prescale, "igv/FSRCNN-TensorFlow", "https://github.com/igv/FSRCNN-TensorFlow",
            "https://github.com/igv/FSRCNN-TensorFlow/releases/download/1.1", "FSRCNNX_x2_16-0-4-1.glsl",
            listOf(
                Variant("hq", "High quality", "16-0-4-1. The reference variant, heaviest on the GPU.", "FSRCNNX_x2_16-0-4-1.glsl"),
                Variant("light", "Light", "8-0-4-1. Half the passes, kinder to weaker cards.", "FSRCNNX_x2_8-0-4-1.glsl"),
            )),
        Entry("fsr", "AMD FSR", "Spatial upscaler", "all", "fast",
            "AMD FidelityFX Super Resolution. A fast spatial upscaler that fires when the video is smaller than the window. A great default for live action where Anime4K is the wrong tool.",
            Stage.Prescale, "agyild (gist)", "https://gist.github.com/agyild/82219c545228d70c5604f865ce0b0ce5",
            "https://gist.githubusercontent.com/agyild/82219c545228d70c5604f865ce0b0ce5/raw", "FSR.glsl"),
        Entry("nis", "NVIDIA NIS", "Spatial upscaler", "all", "fast",
            "NVIDIA Image Scaling. A light spatial upscaler and sharpener, an alternative to FSR that runs well on any GPU.",
            Stage.Prescale, "agyild (gist)", "https://gist.github.com/agyild/7e8951915b2bf24526a9343d951db214",
            "https://gist.githubusercontent.com/agyild/7e8951915b2bf24526a9343d951db214/raw", "NVScaler.glsl"),
        Entry("sgsr", "Snapdragon SGSR", "Spatial upscaler", "all", "fast",
            "Qualcomm Snapdragon Game Super Resolution. A very cheap single-pass spatial upscaler, a lighter alternative to FSR on low-power hardware.",
            Stage.Prescale, "agyild (gist)", "https://gist.github.com/agyild/7715b6b1f38427839d58f80884902cab",
            "https://gist.githubusercontent.com/agyild/7715b6b1f38427839d58f80884902cab/raw", "SGSR.glsl"),
        Entry("ravu", "RAVU Lite", "Luma prescaler", "anime", "fast",
            "A trained luma doubler that is cheap enough to leave on. Excellent on anime and flat-shaded sources. Pick a radius, larger is sharper and heavier.",
            Stage.Prescale, "bjin/mpv-prescalers", "https://github.com/bjin/mpv-prescalers",
            "https://raw.githubusercontent.com/bjin/mpv-prescalers/master", "ravu-lite-r3.hook",
            listOf(
                Variant("r3", "Radius 3", "The balanced default.", "ravu-lite-r3.hook"),
                Variant("r4", "Radius 4", "Sharpest, heaviest.", "ravu-lite-r4.hook"),
                Variant("r2", "Radius 2", "Lightest, for weaker cards.", "ravu-lite-r2.hook"),
            )),
        Entry("nnedi3", "NNEDI3", "Neural upscaler", "all", "heavy",
            "A classic neural edge-directed luma doubler. Very high quality, heavy on the GPU. More neurons means sharper and slower.",
            Stage.Prescale, "bjin/mpv-prescalers", "https://github.com/bjin/mpv-prescalers",
            "https://raw.githubusercontent.com/bjin/mpv-prescalers/master", "nnedi3-nns32-win8x4.hook",
            listOf(
                Variant("nns32", "32 neurons", "The practical default.", "nnedi3-nns32-win8x4.hook"),
                Variant("nns64", "64 neurons", "Sharper, heavier.", "nnedi3-nns64-win8x4.hook"),
                Variant("nns128", "128 neurons", "Reference quality, very heavy.", "nnedi3-nns128-win8x4.hook"),
            )),
        Entry("ssimsuperres", "SSimSuperRes", "Detail refine", "all", "quality",
            "Runs after an upscaler to recover the detail and sharpness a doubler softens. Best paired with FSRCNNX or RAVU, not used alone.",
            Stage.Restore, "igv (gist)", "https://gist.github.com/igv/2364ffa6e81540f29cb7ab4c9bc05b6b",
            "https://gist.githubusercontent.com/igv/2364ffa6e81540f29cb7ab4c9bc05b6b/raw", "SSimSuperRes.glsl"),
        Entry("krig", "KrigBilateral", "Chroma upscaler", "all", "quality",
            "A high quality chroma upscaler. Fixes the color blur and bleeding of default chroma scaling, most visible on saturated edges and subtitles.",
            Stage.Chroma, "igv (gist)", "https://gist.github.com/igv/a015fc885d5c22e6891820ad89555637",
            "https://gist.githubusercontent.com/igv/a015fc885d5c22e6891820ad89555637/raw", "KrigBilateral.glsl"),
        Entry("adaptive-sharpen", "Adaptive Sharpen", "Sharpener", "all", "fast",
            "Edge-aware sharpening that lifts soft detail without the halos of a naive sharpen. An alternative to CAS, run one or the other, not both.",
            Stage.Sharpen, "igv (gist)", "https://gist.github.com/igv/8a77e4eb8276753b54bb94c1c50c317e",
            "https://gist.githubusercontent.com/igv/8a77e4eb8276753b54bb94c1c50c317e/raw", "adaptive-sharpen.glsl"),
        Entry("cas", "Contrast Adaptive Sharpening", "Sharpener", "all", "fast",
            "AMD's cheap, natural sharpener. Runs last in the chain and lifts detail on soft sources without the halos of a naive sharpen.",
            Stage.Sharpen, "agyild (gist)", "https://gist.github.com/agyild/bbb4e58298b2f86aa24da3032a0d2ee6",
            "https://gist.githubusercontent.com/agyild/bbb4e58298b2f86aa24da3032a0d2ee6/raw", "CAS.glsl"),
    )

    val TIER_LABEL = mapOf("fast" to "Light", "quality" to "Quality", "heavy" to "Heavy")
    val TIER_LOAD = mapOf("fast" to "Light on the graphics card.", "quality" to "Moderate load on the graphics card.", "heavy" to "Heavy on the graphics card.")

    fun appliesLabel(content: String) =
        if (content == "anime") "Applies to anime when you play it."
        else "Applies when you play something. Only visibly changes the picture when the video is being scaled."

    private fun fileFor(entry: Entry, variant: String?) = entry.variants.firstOrNull { it.id == variant }?.file ?: entry.variants.firstOrNull()?.file ?: entry.file

    private fun dir(context: Context) = File(context.filesDir, "shaders")

    fun downloaded(context: Context, entry: Entry, variant: String?): Boolean = File(dir(context), fileFor(entry, variant)).let { it.exists() && it.length() > 0 }

    /** generalShaderChain(): the turned-on shaders in stage order, downloading any missing; anime-only ones only for anime. */
    suspend fun chain(context: Context, enabled: Map<String, String>, anime: Boolean): List<String> = withContext(Dispatchers.IO) {
        CATALOG.filter { it.id in enabled && (it.content != "anime" || anime) }
            .sortedBy { it.stage.order }
            .map { entry -> download(context, entry, enabled[entry.id]) }
    }

    /** One shader's file, downloaded once into the app's storage. */
    fun download(context: Context, entry: Entry, variant: String?): String {
        val name = fileFor(entry, variant)
        val target = File(dir(context).apply { mkdirs() }, name)
        if (target.exists() && target.length() > 0) return target.absolutePath
        val request = Request.Builder().url("${entry.base}/$name").header("User-Agent", Net.USER_AGENT).build()
        Net.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Couldn't download $name (HTTP ${response.code})")
            val body = response.body?.string().orEmpty()
            if ("//!" !in body) throw IOException("$name didn't look like a shader")
            File(target.parentFile, "$name.part").apply { writeText(body) }.renameTo(target)
        }
        return target.absolutePath
    }
}
