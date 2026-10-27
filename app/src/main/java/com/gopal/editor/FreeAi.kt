package com.gopal.editor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/*
 * "Free AI": a provider list that is NOT hard-coded. It comes from providers.json
 * (user-editable URL, cached copy, bundled default), keyless providers (Pollinations) run
 * when Free mode is ON, the user's own keys always go first, and every call goes through
 * one router with per-provider rate limits, timeouts and automatic fallback.
 *
 * Everything in this file is plain Kotlin + org.json, and HTTP goes through [FreeHttp], so the
 * routing, 429/timeout handling and rate limiter are unit-tested on the JVM with a fake HTTP layer.
 */

enum class FreeCap { TEXT, IMAGE }

/** One entry of providers.json. */
data class FreeProvider(
    val name: String,
    /** "openai" = OpenAI-compatible chat/completions; "gemini" = generateContent; "image-get" = GET url template returning an image. */
    val type: String,
    val url: String,
    val model: String = "",
    val needsKey: Boolean = false,
    /** For needsKey entries: which of the user's saved keys it uses (a ProviderType name: GROQ, GEMINI, OPENROUTER, OPENAI…). */
    val keyFrom: String? = null,
    val caps: Set<FreeCap> = setOf(FreeCap.TEXT),
    /** Lower runs first among providers of the same kind. */
    val priority: Int = 100,
    /** Minimum gap between two requests to this provider. */
    val minIntervalMs: Long = 3000,
    val timeoutMs: Long = 45_000,
    val note: String = ""
) {
    val keyType: ProviderType? get() = keyFrom?.let { k -> ProviderType.entries.firstOrNull { it.name.equals(k, true) } }
    fun can(c: FreeCap) = c in caps

    fun toJson(): JSONObject = JSONObject().put("name", name).put("type", type).put("url", url).put("model", model)
        .put("needsKey", needsKey).apply { if (keyFrom != null) put("keyFrom", keyFrom) }
        .put("capabilities", JSONArray().apply { caps.forEach { put(it.name.lowercase()) } })
        .put("priority", priority).put("minIntervalMs", minIntervalMs).put("timeoutMs", timeoutMs)
        .apply { if (note.isNotBlank()) put("note", note) }
}

/** Validating parser: bad entries are skipped (with a reason), bad JSON gives an empty list. Never throws. */
object ProvidersJson {
    val TYPES = setOf("openai", "gemini", "image-get")

    data class Parsed(val providers: List<FreeProvider>, val skipped: List<String>, val version: Int = 0)

    fun parse(text: String?): Parsed {
        if (text.isNullOrBlank()) return Parsed(emptyList(), listOf("empty"))
        val arr: JSONArray
        var version = 0
        try {
            val t = text.trim()
            if (t.startsWith("[")) arr = JSONArray(t)
            else {
                val o = JSONObject(t)
                version = o.optInt("version", 0)
                arr = o.optJSONArray("providers") ?: return Parsed(emptyList(), listOf("no \"providers\" array"), version)
            }
        } catch (e: Exception) {
            return Parsed(emptyList(), listOf("not valid JSON"))
        }
        val out = ArrayList<FreeProvider>(); val skipped = ArrayList<String>()
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) { skipped += "#$i not an object"; continue }
            val p = runCatching { entry(o) }.getOrElse { e -> skipped += "#$i ${e.message}"; null } ?: continue
            if (!seen.add(p.name.lowercase())) { skipped += "#$i duplicate name ${p.name}"; continue }
            out += p
        }
        return Parsed(out, skipped, version)
    }

    private fun entry(o: JSONObject): FreeProvider {
        val name = o.optString("name").trim()
        require(name.isNotEmpty() && name.length <= 60) { "missing name" }
        val type = o.optString("type").trim().lowercase()
        require(type in TYPES) { "$name: unknown type '$type'" }
        val url = o.optString("url").trim()
        require(url.startsWith("https://") && url.length in 12..500) { "$name: url must be https" }
        val needsKey = o.optBoolean("needsKey", false)
        val keyFrom = o.optString("keyFrom").trim().ifEmpty { null }
        require(!needsKey || keyFrom != null) { "$name: needsKey without keyFrom" }
        val capsJson = o.optJSONArray("capabilities")
        val caps = if (capsJson == null) setOf(if (type == "image-get") FreeCap.IMAGE else FreeCap.TEXT)
        else (0 until capsJson.length()).mapNotNull { k -> FreeCap.entries.firstOrNull { it.name.equals(capsJson.optString(k), true) } }.toSet()
        require(caps.isNotEmpty()) { "$name: no known capability" }
        require(type != "image-get" || caps == setOf(FreeCap.IMAGE)) { "$name: image-get can only do image" }
        require(type == "image-get" || FreeCap.IMAGE !in caps) { "$name: only image-get can do image" }
        require(o.has("model") || type == "image-get") { "$name: missing model" }
        return FreeProvider(
            name = name, type = type, url = url, model = o.optString("model").trim(), needsKey = needsKey, keyFrom = keyFrom, caps = caps,
            priority = o.optInt("priority", 100),
            minIntervalMs = o.optLong("minIntervalMs", 3000).coerceIn(0, 10 * 60_000),
            timeoutMs = o.optLong("timeoutMs", 45_000).coerceIn(3_000, 180_000),
            note = o.optString("note").take(200)
        )
    }

    fun encode(list: List<FreeProvider>, version: Int = 1): String =
        JSONObject().put("version", version).put("providers", JSONArray().apply { list.forEach { put(it.toJson()) } }).toString(2)
}

/** Which list the app uses: a valid download, else the last good cached copy, else the bundled default. */
object ProviderListChooser {
    enum class Source { ONLINE, CACHE, BUNDLED }
    data class Choice(val providers: List<FreeProvider>, val source: Source, val saveToCache: Boolean)

    fun choose(remote: String?, cached: String?, bundled: String?): Choice {
        val r = ProvidersJson.parse(remote).providers
        if (r.isNotEmpty()) return Choice(r, Source.ONLINE, saveToCache = true)
        val c = ProvidersJson.parse(cached).providers
        if (c.isNotEmpty()) return Choice(c, Source.CACHE, false)
        return Choice(ProvidersJson.parse(bundled).providers, Source.BUNDLED, false)
    }

    /** raw.githubusercontent URL for providers.json in the Updates repo (only loads if that repo is PUBLIC). */
    fun defaultUrl(repo: String, branch: String = "main"): String =
        if (repo.startsWith("GITHUB_USERNAME") || !repo.contains('/')) "" else "https://raw.githubusercontent.com/${repo.trim('/')}/$branch/providers.json"
}

// ---------------- HTTP seam ----------------

data class FreeReq(val method: String, val url: String, val headers: Map<String, String> = emptyMap(), val body: String? = null)
class FreeResp(val code: Int, val headers: Map<String, String>, val body: ByteArray, val contentType: String = "") {
    val text: String get() = String(body, Charsets.UTF_8)
    fun header(n: String): String? = headers.entries.firstOrNull { it.key.equals(n, true) }?.value
}

/** The only door to the network for Free AI. OkHttp in the app, a fake in tests. */
fun interface FreeHttp { suspend fun send(req: FreeReq, timeoutMs: Long): FreeResp }

// ---------------- rate limiter ----------------

/**
 * Per-provider minimum gap between requests, plus a "cool-down until" after a 429 (Retry-After honoured).
 * [now] is injectable for tests.
 */
class RateLimiter(private val now: () -> Long = System::currentTimeMillis) {
    private val nextAt = HashMap<String, Long>()

    /** Milliseconds to wait before [name] may be called again (0 = go now). */
    @Synchronized fun waitMs(name: String): Long = ((nextAt[name] ?: 0L) - now()).coerceAtLeast(0)

    /** Call made now: the next one may start [minIntervalMs] later. */
    @Synchronized fun mark(name: String, minIntervalMs: Long) {
        val t = now() + minIntervalMs
        nextAt[name] = maxOf(nextAt[name] ?: 0L, t)
    }

    /** 429 / overload: nothing to [name] for [coolMs]. */
    @Synchronized fun coolDown(name: String, coolMs: Long) {
        val t = now() + coolMs
        nextAt[name] = maxOf(nextAt[name] ?: 0L, t)
    }

    companion object {
        const val DEFAULT_429_COOL_MS = 30_000L
        const val MAX_COOL_MS = 15 * 60_000L

        /** Retry-After as seconds ("12") or an HTTP date. Null when absent/unreadable. */
        fun parseRetryAfter(v: String?, nowMs: Long): Long? {
            val s = v?.trim().orEmpty()
            if (s.isEmpty()) return null
            s.toDoubleOrNull()?.let { return (it * 1000).toLong().coerceIn(0, MAX_COOL_MS) }
            return runCatching {
                val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
                (f.parse(s)!!.time - nowMs).coerceIn(0, MAX_COOL_MS)
            }.getOrNull()
        }
    }
}

// ---------------- router ----------------

/** One thing the router can try: a providers.json entry, with the user's key when it needs one. */
data class FreeCandidate(val provider: FreeProvider, val key: ApiKey? = null) {
    val id: String get() = provider.name
    val label: String get() = provider.name + if (key != null) " (" + L.t("your key", "आपकी key") + ")" else ""
}

enum class FailKind { RATE_LIMIT, TIMEOUT, NETWORK, AUTH, SERVER, BAD_REPLY, SKIPPED_BUSY }

data class FreeAttempt(val candidate: FreeCandidate, val kind: FailKind, val detail: String)

class FreeHttpError(val code: Int, val retryAfterMs: Long?, msg: String) : Exception("HTTP $code $msg")

class FreeAiFailed(val attempts: List<FreeAttempt>, val nothingToTry: Boolean = false) : Exception() {
    override val message: String get() = hindi()

    /** Simple Hindi (and English) for the user. */
    fun hindi(): String {
        if (nothingToTry) return L.t(
            "No AI is available. Turn ON “Free mode (no key)” or add your own key in API Settings.",
            "कोई AI चालू नहीं है। API Settings में “Free mode (बिना key)” चालू करें या अपनी key डालें।")
        val kinds = attempts.map { it.kind }.toSet()
        val why = when {
            kinds.all { it == FailKind.RATE_LIMIT || it == FailKind.SKIPPED_BUSY } ->
                L.t("All AI servers are busy right now (limit reached). Please try again in a minute.",
                    "सभी AI सर्वर अभी व्यस्त हैं (लिमिट पूरी हो गई)। एक मिनट बाद फिर कोशिश करें।")
            kinds.all { it == FailKind.NETWORK || it == FailKind.TIMEOUT } ->
                L.t("Could not reach any AI server. Check your internet and try again.",
                    "किसी भी AI सर्वर से जुड़ नहीं पाए। इंटरनेट चेक करके फिर कोशिश करें।")
            FailKind.AUTH in kinds && kinds.size == 1 ->
                L.t("Your API key was refused. Check the key in API Settings.",
                    "आपकी API key नहीं चली। API Settings में key जाँचें।")
            else -> L.t("All AI servers failed right now. Please try again after a little while.",
                "अभी सारे AI सर्वर फेल हो गए। थोड़ी देर बाद फिर कोशिश करें।")
        }
        return why + "\n" + attempts.joinToString("\n") { "• " + it.candidate.provider.name + ": " + kindText(it.kind) }
    }

    companion object {
        fun kindText(k: FailKind) = when (k) {
            FailKind.RATE_LIMIT -> L.t("limit reached (429)", "लिमिट पूरी (429)")
            FailKind.TIMEOUT -> L.t("took too long", "बहुत देर लगी")
            FailKind.NETWORK -> L.t("no connection", "कनेक्शन नहीं")
            FailKind.AUTH -> L.t("key refused", "key नहीं चली")
            FailKind.SERVER -> L.t("server error", "सर्वर में गड़बड़")
            FailKind.BAD_REPLY -> L.t("empty/odd reply", "खाली/गलत जवाब")
            FailKind.SKIPPED_BUSY -> L.t("waiting for its gap, skipped", "रुकने का समय बाकी था, छोड़ा")
        }
    }
}

data class FreeResult<T>(val value: T, val used: FreeCandidate, val attempts: List<FreeAttempt>)

/**
 * Order: the user's own keys first (providers.json entries whose keyFrom matches a saved, enabled key,
 * then any other saved chat keys in the user's own order), then — only when Free mode is ON —
 * the keyless providers by priority.
 */
class FreeAiRouter(
    private val providers: List<FreeProvider>,
    private val userKeys: List<ApiKey>,
    private val freeMode: Boolean,
    private val http: FreeHttp,
    private val limiter: RateLimiter,
    /** Wait for a provider's gap when it is at most this long; longer = skip to the next provider. */
    private val maxGapWaitMs: Long = 4_000,
    /** The last provider in the plan may be waited for this long (e.g. Pollinations' free image gap). */
    private val maxLastWaitMs: Long = 50_000,
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {
    fun plan(cap: FreeCap): List<FreeCandidate> {
        val keys = userKeys.filter { it.enabled && it.key.isNotBlank() }
        val out = ArrayList<FreeCandidate>()
        // 1) providers.json entries that need a key the user has saved (in the user's key order)
        val keyed = providers.filter { it.needsKey && it.can(cap) && it.keyType != null }
        for (k in keys) keyed.filter { it.keyType == k.type }.sortedBy { it.priority }.forEach { out += FreeCandidate(it, k) }
        // 2) the user's other saved chat keys (types providers.json doesn't list), as OpenAI-compatible / Gemini calls
        if (cap == FreeCap.TEXT) for (k in keys) {
            if (out.any { it.key?.id == k.id }) continue
            asProvider(k)?.let { out += FreeCandidate(it, k) }
        }
        // 3) keyless, only in Free mode
        if (freeMode) providers.filter { !it.needsKey && it.can(cap) }.sortedBy { it.priority }.forEach { out += FreeCandidate(it) }
        return out
    }

    suspend fun text(prompt: String, system: String? = null, maxTokens: Int = 1500): FreeResult<String> =
        run(FreeCap.TEXT) { c -> FreeCalls.text(http, c, prompt, system, maxTokens) }

    suspend fun image(prompt: String, w: Int, h: Int, seed: Int): FreeResult<ByteArray> =
        run(FreeCap.IMAGE) { c -> FreeCalls.image(http, c, prompt, w, h, seed) }

    suspend fun <T> run(cap: FreeCap, call: suspend (FreeCandidate) -> T): FreeResult<T> {
        val plan = plan(cap)
        if (plan.isEmpty()) throw FreeAiFailed(emptyList(), nothingToTry = true)
        val attempts = ArrayList<FreeAttempt>()
        for (c in plan) {
            val gap = limiter.waitMs(c.id)
            val last = c === plan.last()
            // a long gap: skip to the next provider; but the last one left is worth a longer wait
            if (gap > maxGapWaitMs && !(last && gap <= maxLastWaitMs)) { attempts += FreeAttempt(c, FailKind.SKIPPED_BUSY, "${gap}ms"); continue }
            if (gap > 0) sleep(gap)
            limiter.mark(c.id, c.provider.minIntervalMs)
            try {
                val v = withTimeout(c.provider.timeoutMs) { call(c) }
                return FreeResult(v, c, attempts)
            } catch (e: TimeoutCancellationException) {
                attempts += FreeAttempt(c, FailKind.TIMEOUT, "${c.provider.timeoutMs}ms")
            } catch (e: CancellationException) {
                throw e
            } catch (e: FreeHttpError) {
                // Pollinations answers its free-tier limit with 402 + Retry-After: that is a rate limit, not a bad key
                val kind = if (e.code == 402 && e.retryAfterMs != null) FailKind.RATE_LIMIT else classify(e.code)
                if (kind == FailKind.RATE_LIMIT) limiter.coolDown(c.id, e.retryAfterMs ?: RateLimiter.DEFAULT_429_COOL_MS)
                else if (e.code == 503 && e.retryAfterMs != null) limiter.coolDown(c.id, e.retryAfterMs)
                attempts += FreeAttempt(c, kind, e.message ?: "")
            } catch (e: java.io.InterruptedIOException) {
                attempts += FreeAttempt(c, FailKind.TIMEOUT, e.message ?: "timeout")
            } catch (e: java.io.IOException) {
                attempts += FreeAttempt(c, FailKind.NETWORK, e.message ?: e.javaClass.simpleName)
            } catch (e: Exception) {
                attempts += FreeAttempt(c, FailKind.BAD_REPLY, e.message ?: e.javaClass.simpleName)
            }
        }
        throw FreeAiFailed(attempts)
    }

    companion object {
        fun classify(code: Int): FailKind = when (code) {
            429 -> FailKind.RATE_LIMIT
            401, 402, 403 -> FailKind.AUTH
            408, 504 -> FailKind.TIMEOUT
            else -> if (code >= 500) FailKind.SERVER else FailKind.BAD_REPLY
        }

        /** A saved key turned into a router entry. Anthropic isn't OpenAI-compatible, so it uses its own call. */
        fun asProvider(k: ApiKey): FreeProvider? = when (k.type) {
            ProviderType.GEMINI -> FreeProvider(k.type.label, "gemini", k.base, k.chatModel, true, k.type.name, priority = 0, minIntervalMs = 1000, timeoutMs = 60_000)
            ProviderType.ANTHROPIC -> FreeProvider(k.type.label, "anthropic", k.base, k.chatModel, true, k.type.name, priority = 0, minIntervalMs = 1000, timeoutMs = 60_000)
            else -> if (k.base.isBlank()) null
                else FreeProvider(k.type.label, "openai", k.base + "/chat/completions", k.chatModel, true, k.type.name, priority = 0, minIntervalMs = 1000, timeoutMs = 60_000)
        }
    }
}

/** Request building + reply parsing for each provider type (pure, so tests can check them). */
object FreeCalls {
    private const val JSON = "application/json"

    suspend fun text(http: FreeHttp, c: FreeCandidate, prompt: String, system: String?, maxTokens: Int): String {
        val p = c.provider
        val req = textRequest(p, c.key, prompt, system, maxTokens)
        val r = http.send(req, p.timeoutMs)
        check(r, p)
        return parseText(p.type, r.text)
    }

    fun textRequest(p: FreeProvider, key: ApiKey?, prompt: String, system: String?, maxTokens: Int): FreeReq {
        val model = p.model.ifBlank { key?.chatModel ?: "" }
        return when (p.type) {
            "gemini" -> {
                val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                    .put("generationConfig", JSONObject().put("maxOutputTokens", maxOf(maxTokens, 1024)))
                if (system != null) body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                val base = p.url.trimEnd('/')
                val url = if (base.contains(":generateContent")) base else "$base/models/$model:generateContent"
                FreeReq("POST", url, mapOf("Content-Type" to JSON) + (key?.let { mapOf("x-goog-api-key" to it.key) } ?: emptyMap()), body.toString())
            }
            "anthropic" -> {
                val body = JSONObject().put("model", model).put("max_tokens", maxTokens)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                if (system != null) body.put("system", system)
                FreeReq("POST", p.url.trimEnd('/') + "/messages", mapOf("Content-Type" to JSON, "x-api-key" to (key?.key ?: ""), "anthropic-version" to "2023-06-01"), body.toString())
            }
            else -> {   // "openai"
                val msgs = JSONArray()
                if (system != null) msgs.put(JSONObject().put("role", "system").put("content", system))
                msgs.put(JSONObject().put("role", "user").put("content", prompt))
                val body = JSONObject().put("model", model).put("messages", msgs)
                    .put(if (key?.type == ProviderType.OPENAI) "max_completion_tokens" else "max_tokens", maxTokens)
                val h = LinkedHashMap<String, String>(); h["Content-Type"] = JSON
                if (key != null) h["Authorization"] = "Bearer ${key.key}"
                if (key?.type == ProviderType.OPENROUTER || p.url.contains("openrouter.ai")) { h["HTTP-Referer"] = "https://github.com/"; h["X-Title"] = "Gopal Editor" }
                FreeReq("POST", p.url, h, body.toString())
            }
        }
    }

    fun parseText(type: String, raw: String): String {
        val o = JSONObject(raw)
        val t = when (type) {
            "gemini" -> {
                val parts = o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
            }
            "anthropic" -> {
                val parts = o.optJSONArray("content") ?: JSONArray()
                (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
            }
            else -> {
                val m = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                // some free reasoning models leave content empty and put text elsewhere; content is what we show
                m?.optString("content")?.takeIf { it != "null" } ?: ""
            }
        }.trim()
        if (t.isEmpty()) throw IllegalStateException("empty reply")
        return t
    }

    suspend fun image(http: FreeHttp, c: FreeCandidate, prompt: String, w: Int, h: Int, seed: Int): ByteArray {
        val p = c.provider
        require(p.type == "image-get") { "${p.name} can't make images" }
        val r = http.send(FreeReq("GET", imageUrl(p, prompt, w, h, seed), key(c)), p.timeoutMs)
        check(r, p)
        val isImage = r.contentType.startsWith("image/") || looksLikeImage(r.body)
        if (!isImage || r.body.size < 1000) throw IllegalStateException("not an image (${r.contentType}, ${r.body.size} bytes)")
        return r.body
    }

    private fun key(c: FreeCandidate) = c.key?.let { mapOf("Authorization" to "Bearer ${it.key}") } ?: emptyMap()

    /** url template placeholders: {prompt} {width} {height} {seed} {model}. */
    fun imageUrl(p: FreeProvider, prompt: String, w: Int, h: Int, seed: Int): String {
        val enc = URLEncoder.encode(prompt.trim().take(900), "UTF-8").replace("+", "%20")
        return p.url.replace("{prompt}", enc).replace("{width}", w.toString()).replace("{height}", h.toString())
            .replace("{seed}", seed.toString()).replace("{model}", URLEncoder.encode(p.model, "UTF-8"))
    }

    fun looksLikeImage(b: ByteArray): Boolean = b.size > 4 && (
        (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) ||                                   // JPEG
        (b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte()) ||   // PNG
        (b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b.size > 12 && b[8] == 'W'.code.toByte()))  // WEBP

    private fun check(r: FreeResp, p: FreeProvider) {
        if (r.code in 200..299) return
        val ra = RateLimiter.parseRetryAfter(r.header("Retry-After"), System.currentTimeMillis())
        throw FreeHttpError(r.code, ra, AiClient.errorMessage(r.text).take(160).ifBlank { p.name })
    }
}
