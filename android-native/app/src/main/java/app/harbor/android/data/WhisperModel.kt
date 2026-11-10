package app.harbor.android.data

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Optional multilingual Tiny model, downloaded only when the user presses Download model. */
object WhisperModel {
    const val BYTES = 77_691_713L
    const val SHA256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"
    private val urls = listOf("https://harborcommunity.site/models/ggml-tiny.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin")
    data class State(val installed: Boolean = false, val received: Long = 0, val downloading: Boolean = false,
        val inUse: Boolean = false, val error: String? = null)
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var destination: File? = null
    private var job: Job? = null

    @Synchronized fun init(context: Context) {
        if (destination != null) return
        // Model weights are replaceable and would exceed Android's app-backup quota.
        destination = File(context.applicationContext.noBackupFilesDir, "asr/ggml-tiny.bin")
        val stale = destination?.parentFile?.listFiles()?.filter { it.name.startsWith("ggml-tiny.") && it.name.endsWith(".part") }.orEmpty()
        scope.launch { stale.forEach { it.delete() } }
        _state.value = State(installed = destination?.let { it.isFile && it.length() == BYTES } == true)
    }
    @Synchronized fun acquire(): File? {
        val file = destination?.takeIf { it.isFile && it.length() == BYTES } ?: return null
        if (_state.value.inUse) return null
        _state.value = _state.value.copy(inUse = true)
        return file
    }
    @Synchronized fun release() { _state.value = _state.value.copy(inUse = false) }
    @Synchronized fun remove() {
        if (_state.value.downloading || _state.value.inUse) return
        val removed = destination?.let { !it.exists() || it.delete() } ?: true
        _state.value = _state.value.copy(installed = !removed, received = 0,
            error = if (removed) null else "Couldn't remove the model. Try again.")
    }
    fun cancelDownload() { job?.cancel() }

    @Synchronized fun download(context: Context) {
        init(context)
        if (job?.isActive == true || _state.value.installed) return
        _state.value = _state.value.copy(downloading = true, received = 0, error = null)
        job = scope.launch {
            val dest = destination!!
            val part = File(dest.parentFile, "ggml-tiny.${System.nanoTime()}.part")
            try {
                dest.parentFile?.mkdirs()
                check((dest.parentFile?.usableSpace ?: 0) >= BYTES + 10_000_000) { "Not enough storage for the speech model." }
                var downloaded = false
                for (url in urls) {
                    currentCoroutineContext().ensureActive()
                    try { transfer(url, part); downloaded = true; break }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { part.delete() }
                }
                check(downloaded) { "Couldn't download a verified model. Check your connection and try again." }
                currentCoroutineContext().ensureActive()
                synchronized(WhisperModel) {
                    check(part.renameTo(dest)) { "Couldn't save the speech model." }
                    _state.value = State(installed = true, received = BYTES)
                }
            } catch (e: CancellationException) {
                _state.value = _state.value.copy(downloading = false, received = 0)
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(downloading = false, received = 0, error = e.message ?: "Model download failed.")
            } finally { part.delete() }
        }
    }

    private suspend fun transfer(url: String, part: File): Unit = suspendCancellableCoroutine { continuation ->
        val call = Net.client.newCall(Request.Builder().url(url).header("User-Agent", Net.USER_AGENT).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use { r ->
                    check(r.isSuccessful) { "Model download failed (HTTP ${r.code})." }
                    val body = r.body ?: error("Empty model download.")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var received = 0L
                    body.byteStream().use { input -> part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (!continuation.isActive) throw IOException("Download cancelled")
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (!continuation.isActive) throw IOException("Download cancelled")
                            received += n
                            check(received <= BYTES) { "Incorrect model size." }
                            output.write(buffer, 0, n); digest.update(buffer, 0, n)
                            _state.value = _state.value.copy(received = received)
                        }
                        output.fd.sync()
                    } }
                    check(received == BYTES && digest.digest().joinToString("") { "%02x".format(it) } == SHA256) { "Model verification failed." }
                } }
                if (continuation.isActive) result.fold({ continuation.resume(Unit) }, continuation::resumeWithException)
                else part.delete()
            }
        })
    }
    fun size(bytes: Long = BYTES) = String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
}
