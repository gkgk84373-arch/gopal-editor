package com.gopal.editor

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Which AI features a provider can serve with a plain API key. */
enum class Capability { CHAT, STT }

enum class ProviderType(
    val label: String,
    val defaultBase: String,
    val defaultModel: String,
    val defaultSttModel: String?,   // null = no speech-to-text API
    val keyPrefix: String           // shown as a hint only
) {
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", "whisper-1", "sk-"),
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash", "gemini-2.5-flash", "AIza"),
    ANTHROPIC("Anthropic Claude", "https://api.anthropic.com/v1", "claude-haiku-4-5", null, "sk-ant-"),
    GROQ("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "whisper-large-v3", "gsk_"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "openrouter/auto", null, "sk-or-"),
    HUGGINGFACE("Hugging Face", "https://router.huggingface.co/v1", "meta-llama/Llama-3.1-8B-Instruct", "openai/whisper-large-v3", "hf_"),
    CUSTOM("Custom (OpenAI-compatible URL)", "", "", "whisper-1", "");

    fun supports(c: Capability) = when (c) { Capability.CHAT -> true; Capability.STT -> defaultSttModel != null }
    companion object { fun parse(s: String?) = entries.firstOrNull { it.name == s } ?: OPENAI }
}

data class ApiKey(
    val id: String,
    val type: ProviderType,
    val key: String,
    val model: String = "",
    val sttModel: String = "",
    val baseUrl: String = "",
    val enabled: Boolean = true
) {
    val chatModel: String get() = model.ifBlank { type.defaultModel }
    val speechModel: String get() = sttModel.ifBlank { type.defaultSttModel ?: "" }
    val base: String get() = baseUrl.ifBlank { type.defaultBase }.trimEnd('/')
    /** "sk-…a1B2" (never the whole key in the UI or logs). */
    val masked: String get() = if (key.length <= 8) "••••" else key.take(4) + "…" + key.takeLast(4)

    fun toJson(): JSONObject = JSONObject().put("id", id).put("type", type.name).put("key", key).put("model", model)
        .put("stt", sttModel).put("base", baseUrl).put("on", enabled)

    companion object {
        fun fromJson(o: JSONObject) = ApiKey(o.getString("id"), ProviderType.parse(o.optString("type")), o.optString("key"),
            o.optString("model"), o.optString("stt"), o.optString("base"), o.optBoolean("on", true))
        fun listToJson(l: List<ApiKey>): String = JSONArray().apply { l.forEach { put(it.toJson()) } }.toString()
        fun listFromJson(s: String?): List<ApiKey> = runCatching {
            val a = JSONArray(s ?: "[]"); (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}

/** API keys live only in EncryptedSharedPreferences (AES-256, key in the Android Keystore). Never logged. */
class KeyVault(ctx: Context) {
    private val prefs: SharedPreferences? = open(ctx)
    private var memory: String? = null
    val isEncrypted: Boolean get() = prefs != null

    fun load(): List<ApiKey> = ApiKey.listFromJson(prefs?.getString(K, null) ?: memory)
    fun save(keys: List<ApiKey>) {
        val s = ApiKey.listToJson(keys)
        if (prefs != null) prefs.edit().putString(K, s).apply() else memory = s
    }

    companion object {
        private const val K = "api_keys_v1"
        private const val FILE = "gopal_editor_secure"
        private fun create(c: Context): SharedPreferences = EncryptedSharedPreferences.create(
            c, FILE,
            MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        /** Some phones corrupt the keystore after a restore; then we wipe the file once and retry. */
        fun open(c: Context): SharedPreferences? = try { create(c) } catch (e: Exception) {
            try { c.deleteSharedPreferences(FILE); create(c) } catch (e2: Exception) { null }
        }
    }
}

class ApiException(val provider: String, val code: Int, message: String) : Exception("$provider: HTTP $code – $message")

class AllProvidersFailed(val failures: List<Pair<ApiKey, Throwable>>) :
    Exception(if (failures.isEmpty()) "No API key can do this. Add one in API Settings."
              else failures.joinToString("\n") { (k, e) -> "${k.type.label}: ${e.message}" })

data class ChainResult<T>(val value: T, val usedKey: ApiKey, val failures: List<Pair<ApiKey, Throwable>>)

/**
 * Fallback: tries the user's keys in their order, skipping disabled keys and providers that can't
 * do the job; any error (HTTP, network, bad reply) moves on to the next key.
 */
class ProviderChain(private val keys: List<ApiKey>) {
    fun candidates(need: Capability): List<ApiKey> = keys.filter { it.enabled && it.key.isNotBlank() && it.type.supports(need) && (it.type != ProviderType.CUSTOM || it.baseUrl.isNotBlank()) }

    suspend fun <T> run(need: Capability, block: suspend (ApiKey) -> T): ChainResult<T> {
        val fails = ArrayList<Pair<ApiKey, Throwable>>()
        for (k in candidates(need)) {
            try {
                return ChainResult(block(k), k, fails)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fails += k to e
            }
        }
        throw AllProvidersFailed(fails)
    }
}

/** Direct calls to each provider's own public API with the user's key. No backend in between. */
class AiClient(
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).writeTimeout(180, TimeUnit.SECONDS).build()
) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** Minimal real request for the "Test Key" button: returns the provider's own reply text. */
    suspend fun testKey(k: ApiKey): String = chat(k, "Reply with just the word OK.", maxTokens = 200)   // room for reasoning models

    suspend fun chat(k: ApiKey, prompt: String, system: String? = null, maxTokens: Int = 1024): String = withContext(Dispatchers.IO) {
        when (k.type) {
            ProviderType.ANTHROPIC -> {
                val body = JSONObject().put("model", k.chatModel).put("max_tokens", maxTokens)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                if (system != null) body.put("system", system)
                val req = Request.Builder().url("${k.base}/messages")
                    .header("x-api-key", k.key).header("anthropic-version", "2023-06-01")
                    .post(body.toString().toRequestBody(jsonType)).build()
                val o = JSONObject(exec(k, req))
                val parts = o.optJSONArray("content") ?: JSONArray()
                (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }.trim()
                    .ifEmpty { throw ApiException(k.type.label, 200, "empty reply (stop_reason=${o.optString("stop_reason")})") }
            }
            ProviderType.GEMINI -> {
                val contents = JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt))))
                val body = JSONObject().put("contents", contents)
                    .put("generationConfig", JSONObject().put("maxOutputTokens", maxOf(maxTokens, 1024)))   // 2.5 models spend some on thinking
                if (system != null) body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                geminiText(k, exec(k, geminiReq(k, body)))
            }
            else -> {   // OpenAI, Groq, OpenRouter, Hugging Face router, Custom: OpenAI chat-completions format
                val msgs = JSONArray()
                if (system != null) msgs.put(JSONObject().put("role", "system").put("content", system))
                msgs.put(JSONObject().put("role", "user").put("content", prompt))
                // OpenAI's newer models only accept max_completion_tokens (all current ones accept it); others use max_tokens
                val body = JSONObject().put("model", k.chatModel).put("messages", msgs)
                    .put(if (k.type == ProviderType.OPENAI) "max_completion_tokens" else "max_tokens", maxTokens)
                val rb = Request.Builder().url("${k.base}/chat/completions").header("Authorization", "Bearer ${k.key}")
                if (k.type == ProviderType.OPENROUTER) rb.header("HTTP-Referer", "https://github.com/").header("X-Title", "Gopal Editor")
                val o = JSONObject(exec(k, rb.post(body.toString().toRequestBody(jsonType)).build()))
                o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: throw ApiException(k.type.label, 200, "empty reply")
            }
        }
    }

    /**
     * Speech-to-text with timestamps. [lang] = "hi" | "en" | null (auto).
     * [audio] is an .m4a (AAC); for Gemini it is re-wrapped as ADTS ([adts]) because Gemini lists audio/aac, not m4a.
     */
    suspend fun transcribe(k: ApiKey, audio: File, lang: String?, adts: (() -> File)? = null): List<Segment> = withContext(Dispatchers.IO) {
        when (k.type) {
            ProviderType.OPENAI, ProviderType.GROQ, ProviderType.CUSTOM -> {
                val mb = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", audio.name, audio.asRequestBody("audio/mp4".toMediaType()))
                    .addFormDataPart("model", k.speechModel)
                    .addFormDataPart("response_format", "verbose_json")
                    .addFormDataPart("timestamp_granularities[]", "segment")
                    .addFormDataPart("timestamp_granularities[]", "word")
                    .addFormDataPart("temperature", "0")
                if (lang != null) mb.addFormDataPart("language", lang)
                val req = Request.Builder().url("${k.base}/audio/transcriptions").header("Authorization", "Bearer ${k.key}").post(mb.build()).build()
                TranscriptParser.whisperVerbose(exec(k, req))
            }
            ProviderType.GEMINI -> {
                val f = adts?.invoke() ?: audio
                val mime = if (f.extension == "aac") "audio/aac" else "audio/mp4"
                val b64 = f.readBytes().toByteString().base64()
                val langName = when (lang) { "hi" -> "Hindi (write in Devanagari)"; "en" -> "English"; else -> "the spoken language" }
                val prompt = "Transcribe this audio in $langName. Return ONLY a JSON array of caption segments, each at most ~6 seconds: " +
                    "[{\"start\": seconds_as_number, \"end\": seconds_as_number, \"text\": \"...\"}]. Timestamps must match the audio exactly."
                val parts = JSONArray().put(JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", b64)))
                    .put(JSONObject().put("text", prompt))
                val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                    .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0))
                TranscriptParser.gemini(geminiText(k, exec(k, geminiReq(k, body, k.speechModel))))
            }
            ProviderType.HUGGINGFACE -> {
                val root = k.base.removeSuffix("/v1")
                val b64 = audio.readBytes().toByteString().base64()
                val body = JSONObject().put("inputs", b64).put("parameters", JSONObject().put("return_timestamps", true))
                val req = Request.Builder().url("$root/hf-inference/models/${k.speechModel}")
                    .header("Authorization", "Bearer ${k.key}").post(body.toString().toRequestBody(jsonType)).build()
                TranscriptParser.huggingFace(exec(k, req))
            }
            else -> throw ApiException(k.type.label, 0, "no speech-to-text API")
        }
    }

    private fun geminiReq(k: ApiKey, body: JSONObject, model: String = k.chatModel) = Request.Builder()
        .url("${k.base}/models/$model:generateContent")
        .header("x-goog-api-key", k.key)
        .post(body.toString().toRequestBody(jsonType)).build()

    private fun geminiText(k: ApiKey, raw: String): String {
        val o = JSONObject(raw)
        val c = o.optJSONArray("candidates")?.optJSONObject(0)
        val parts = c?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val t = (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }.trim()
        if (t.isEmpty()) {
            val why = c?.optString("finishReason") ?: o.optJSONObject("promptFeedback")?.optString("blockReason") ?: "no candidates"
            throw ApiException(k.type.label, 200, "empty reply ($why)")
        }
        return t
    }

    /** Runs the request; non-2xx becomes ApiException with the provider's own error message. */
    private fun exec(k: ApiKey, req: Request): String {
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(k.type.label, r.code, errorMessage(body).ifBlank { r.message })
            return body
        }
    }

    companion object {
        /** Pulls the human message out of OpenAI / Anthropic / Gemini / HF error JSON. */
        fun errorMessage(body: String): String = runCatching {
            val o = JSONObject(body)
            when (val e = o.opt("error")) {
                is JSONObject -> e.optString("message").ifBlank { e.toString() }
                is String -> e
                else -> o.optString("message")
            }
        }.getOrElse { body.take(200) }.take(300)
    }
}
