package app.harbor.android.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Harbor's AI search (src/lib/ai-search.ts): describe what you want to watch, a language model
 * (OpenRouter or Groq, with your key) names real titles, and Cinemeta finds them. With live web
 * context on, a DuckDuckGo search read through Jina Reader goes along with the question.
 */
object AiSearch {
    data class Model(val id: String, val label: String, val free: Boolean)

    /** Harbor's list (src/lib/ai-models.ts). */
    val openRouterModels = listOf(
        Model("google/gemma-4-26b-a4b-it:free", "Gemma 4 26B", true),
        Model("openai/gpt-oss-20b:free", "GPT-OSS 20B", true),
        Model("nvidia/nemotron-3-super-120b-a12b:free", "Nemotron 3 Super 120B", true),
        Model("nvidia/nemotron-3-nano-30b-a3b:free", "Nemotron 3 Nano 30B", true),
        Model("google/gemini-3.1-flash-lite", "Gemini 3.1 Flash Lite", false),
        Model("google/gemini-3.5-flash", "Gemini 3.5 Flash", false),
        Model("anthropic/claude-haiku-4.5", "Claude Haiku 4.5", false),
        Model("anthropic/claude-sonnet-5", "Claude Sonnet 5", false),
        Model("openai/gpt-4o-mini", "GPT-4o mini", false),
        Model("deepseek/deepseek-v4-flash", "DeepSeek V4 Flash", false),
        Model("qwen/qwen3.6-flash", "Qwen 3.6 Flash", false),
        Model("x-ai/grok-4.5", "Grok 4.5", false),
        Model("meta-llama/llama-4-maverick", "Llama 4 Maverick", false),
    )
    val groqModels = listOf(
        Model("llama-3.3-70b-versatile", "Llama 3.3 70B Versatile", true),
        Model("meta-llama/llama-4-scout-17b-16e-instruct", "Llama 4 Scout 17B", true),
        Model("openai/gpt-oss-120b", "GPT-OSS 120B", true),
        Model("openai/gpt-oss-20b", "GPT-OSS 20B", true),
        Model("qwen/qwen3-32b", "Qwen 3 32B", true),
        Model("moonshotai/kimi-k2-instruct", "Kimi K2 Instruct", true),
        Model("llama-3.1-8b-instant", "Llama 3.1 8B Instant", true),
    )

    data class Suggestion(val title: String, val year: Int?, val type: String?, val season: Int?, val episode: Int?, val episodeTitle: String?)

    /** What came back: the title, and the episode when the question was about one. */
    data class Result(val meta: Meta, val season: Int?, val episode: Int?, val episodeTitle: String?)

    private const val SYSTEM = "You are a film and TV discovery engine for a media app. The user describes what they want to watch in natural language. " +
        "Reply with ONLY a JSON array (no prose, no markdown code fences) of up to 12 specific, real movies or TV shows that best match, most relevant first. " +
        "Each element is an object: {\"title\": string, \"year\": number, \"type\": \"movie\" or \"series\"}. " +
        "If the user is clearly asking about a SPECIFIC EPISODE (by plot, scene, character, quote, or meme), return that show as the first result and add its " +
        "\"season\" and \"episode\" numbers plus \"episodeTitle\". Use the original or most internationally recognized title. " +
        "When live web context is provided below, treat it as authoritative for fact-grounded queries and cite the exact title/year it mentions."

    class AiError(message: String) : Exception(message)

    suspend fun suggest(key: String, model: String, groq: Boolean, query: String, webContext: String?): List<Suggestion> {
        val system = if (webContext.isNullOrBlank()) SYSTEM else "$SYSTEM\n\nLive web context for this query (use it when relevant, fall back to your own knowledge otherwise):\n$webContext"
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 2000)
            if (!model.startsWith("openai/gpt-5")) put("temperature", 0.4)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", query.trim()) })
            })
        }.toString()
        val request = Request.Builder()
            .url(if (groq) "https://api.groq.com/openai/v1/chat/completions" else "https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer ${key.trim()}")
            .apply { if (!groq) { header("HTTP-Referer", "https://github.com/9vsv6/Harbor"); header("X-Title", "Harbor") } }
            .post(body.toRequestBody("application/json".toMediaType())).build()
        val response = Net.client.newCall(request).execute()
        val text = response.use { it.body?.string().orEmpty() }
        if (!response.isSuccessful) throw AiError(friendly(response.code, text))
        val content = runCatching {
            ((Net.json.parseToJsonElement(text).jsonObject["choices"] as JsonArray)[0].jsonObject["message"] as JsonObject)["content"]
                .let { (it as JsonPrimitive).content }
        }.getOrNull()
        if (content.isNullOrBlank()) throw AiError("The model replied with nothing usable. Try another model or rephrase.")
        return parse(content)
    }

    private fun friendly(status: Int, detail: String) = when (status) {
        401 -> "Your API key was rejected. Check it in Settings › Sources & library › Search."
        402 -> "Your account is out of credits for this model. Pick a free model or top up."
        404 -> "This model no longer exists at the provider. Pick another model."
        413 -> "That request was too big for this model."
        429 -> "The model is rate-limited right now. Try again in a moment or switch models."
        else -> "AI search failed ($status). ${detail.take(120)}"
    }

    private fun parse(content: String): List<Suggestion> {
        val s = content.replace(Regex("```(?:json)?", RegexOption.IGNORE_CASE), "")
        val array = runCatching {
            val trimmed = s.trim()
            if (trimmed.startsWith("{")) Net.json.parseToJsonElement(trimmed).jsonObject["results"] as JsonArray
            else Net.json.parseToJsonElement(s.substring(s.indexOf('['), s.lastIndexOf(']') + 1)) as JsonArray
        }.getOrNull() ?: return emptyList()
        return array.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val title = (o["title"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Suggestion(
                title,
                (o["year"] as? JsonPrimitive)?.intOrNull,
                (o["type"] as? JsonPrimitive)?.contentOrNull?.takeIf { it == "movie" || it == "series" },
                (o["season"] as? JsonPrimitive)?.intOrNull,
                (o["episode"] as? JsonPrimitive)?.intOrNull,
                (o["episodeTitle"] as? JsonPrimitive)?.contentOrNull,
            )
        }.distinctBy { it.title.lowercase() }.take(12)
    }

    /** Harbor's Jina web context: DuckDuckGo results read through r.jina.ai, trimmed. */
    suspend fun webContext(query: String, jinaKey: String?): String? = runCatching {
        val url = "https://r.jina.ai/https://html.duckduckgo.com/html/?q=" + java.net.URLEncoder.encode("$query movie OR tv show", "UTF-8")
        val request = Request.Builder().url(url).apply { jinaKey?.let { header("Authorization", "Bearer $it") } }.build()
        Net.call(request).lines().filter { it.isNotBlank() && !it.startsWith("![") }.joinToString("\n").take(6_000)
    }.getOrNull()

    /** Each suggestion as Cinemeta has it: same title, nearest year, right type. */
    suspend fun resolve(suggestions: List<Suggestion>, metaAddons: List<Addon>): List<Result> = coroutineScope {
        val cinemeta = metaAddons.firstOrNull { it.manifest.id == AddonClient.CINEMETA_ID } ?: return@coroutineScope emptyList()
        suggestions.map { s ->
            async {
                val types = s.type?.let { listOf(it) } ?: listOf("movie", "series")
                val found = types.flatMap { t ->
                    val catalog = cinemeta.manifest.catalogs.firstOrNull { it.type == t && it.supportsSearch } ?: return@flatMap emptyList()
                    runCatching { AddonClient.catalog(cinemeta, catalog, mapOf("search" to s.title)) }.getOrDefault(emptyList())
                }
                val best = found.sortedBy { m ->
                    val nameMiss = if (m.name.equals(s.title, true)) 0 else 1
                    val yearMiss = s.year?.let { y -> m.year?.toIntOrNull()?.let { kotlin.math.abs(it - y) } ?: 5 } ?: 0
                    nameMiss * 10 + yearMiss
                }.firstOrNull() ?: return@async null
                Result(best, s.season, s.episode, s.episodeTitle)
            }
        }.awaitAll().filterNotNull().distinctBy { it.meta.id }
    }
}
