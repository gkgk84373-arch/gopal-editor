package com.gopal.editor

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** OkHttp behind the [FreeHttp] seam. Per-call timeout; cancelling the coroutine cancels the call. */
class OkFreeHttp(private val base: OkHttpClient) : FreeHttp {
    override suspend fun send(req: FreeReq, timeoutMs: Long): FreeResp = suspendCancellableCoroutine { cont ->
        val client = base.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        val b = Request.Builder().url(req.url)
        req.headers.forEach { (k, v) -> if (!k.equals("Content-Type", true)) b.header(k, v) }
        val ct = req.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value ?: "application/json"
        if (req.method == "POST") b.post((req.body ?: "").toRequestBody(ct.toMediaType())) else b.get()
        val call = client.newCall(b.build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { r ->
                        val bytes = r.body?.bytes() ?: ByteArray(0)
                        val h = HashMap<String, String>(); r.headers.names().forEach { n -> r.header(n)?.let { h[n] = it } }
                        if (cont.isActive) cont.resume(FreeResp(r.code, h, bytes, r.body?.contentType()?.toString() ?: (h["Content-Type"] ?: "")))
                    }
                } catch (e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            }
        })
    }
}

/**
 * providers.json: downloaded at app start from the URL in API Settings; a valid download is cached;
 * no internet / private repo / bad file -> the last cached copy -> the list bundled in the app.
 */
class ProvidersRepo(private val ctx: Context, private val http: OkHttpClient) {
    private val cacheFile get() = File(ctx.filesDir, "free_ai/providers_cache.json")

    fun bundled(): String? = runCatching { ctx.assets.open("providers_default.json").bufferedReader().use { it.readText() } }.getOrNull()
    fun cached(): String? = runCatching { cacheFile.takeIf { it.exists() }?.readText() }.getOrNull()

    data class Loaded(val choice: ProviderListChooser.Choice, val fetchError: String?)

    suspend fun load(url: String): Loaded = withContext(Dispatchers.IO) {
        var err: String? = null
        val remote: String? = if (url.isBlank()) { err = "no URL"; null } else try {
            val c = http.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()
            c.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { r ->
                if (!r.isSuccessful) { err = "HTTP ${r.code}" + if (r.code == 404) " (private repo or wrong path?)" else ""; null }
                else r.body?.string()?.takeIf { it.length < 256_000 }
            }
        } catch (e: Exception) { err = e.message ?: e.javaClass.simpleName; null }
        if (remote != null && ProvidersJson.parse(remote).providers.isEmpty()) err = "file has no valid providers"
        val choice = ProviderListChooser.choose(remote, cached(), bundled())
        if (choice.saveToCache && remote != null) runCatching { cacheFile.parentFile?.mkdirs(); cacheFile.writeText(remote) }
        Loaded(choice, err)
    }
}

/** Turns a generated picture into a short silent video clip, so it sits on the main track like any clip. */
object ImageClipMaker {
    suspend fun make(ctx: Context, image: File, mime: String, lenMs: Long, w: Int, h: Int, out: File, onProgress: (Int) -> Unit = {}): File {
        out.parentFile?.mkdirs()
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                val item = EditedMediaItem.Builder(
                    MediaItem.Builder().setUri(Uri.fromFile(image)).setMimeType(mime).setImageDurationMs(lenMs).build()
                ).setDurationUs(lenMs * 1000).setFrameRate(30)
                    .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(w and 1.inv(), h and 1.inv(), Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))))
                    .build()
                val comp = Composition.Builder(listOf(EditedMediaItemSequence(listOf(item)))).build()
                val t = Transformer.Builder(ctx).setVideoMimeType(MimeTypes.VIDEO_H264).addListener(transformerListener(out, cont)).build()
                startWithProgress(t, comp, out, onProgress, cont)
            }
        }
        return out
    }
}
