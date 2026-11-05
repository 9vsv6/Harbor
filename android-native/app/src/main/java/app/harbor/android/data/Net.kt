package app.harbor.android.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One HTTP client and JSON configuration for every network call in the app. */
object Net {
    const val USER_AGENT = "Harbor/0.1 (Android)"

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    /**
     * Where response bodies are parsed and decoded. [call]/[get] resume on the caller's
     * dispatcher, which is Main for a composable's LaunchedEffect; two threads at most, so a
     * burst of catalog pages never takes every core from the UI and render threads.
     */
    val parseDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(2)

    /** Parses [body] into a JSON object on [parseDispatcher]. */
    suspend fun parseObject(body: String): JsonObject =
        withContext(parseDispatcher) { json.parseToJsonElement(body).jsonObject }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        // Several addons often share a host (one addon configured twice, a proxy); OkHttp's
        // default of 5 per host would queue the rest behind them.
        .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 })
        .build()

    /**
     * Runs the call and reads its response with `read`, off the main thread. Cancelling the
     * coroutine (a timeout, leaving the screen) aborts the request at once; a blocking
     * execute() would hold the caller until the server answered or the read timed out.
     */
    suspend fun <T> Call.await(read: (Response) -> T): T = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use(read) }
                if (cont.isActive) result.fold(cont::resume, cont::resumeWithException)
            }
        })
    }

    suspend fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        return client.newCall(request).await { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            response.body?.string() ?: ""
        }
    }

    /** Runs `request` with Harbor's user agent; non-2xx responses throw with the body's message. */
    suspend fun call(request: Request): String =
        client.newCall(request.newBuilder().header("User-Agent", USER_AGENT).build()).await { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw HttpError(response.code, body)
            body
        }

    class HttpError(val code: Int, val body: String) : IOException("HTTP $code: ${body.take(200)}")

    suspend fun postJson(url: String, body: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(request).await { response ->
            response.body?.string() ?: throw IOException("Empty response from $url")
        }
    }
}
