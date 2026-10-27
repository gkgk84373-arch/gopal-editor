package com.gopal.editor

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

class ProviderChainTest {
    private lateinit var a: MockWebServer
    private lateinit var b: MockWebServer
    private val ai = AiClient()

    @Before fun up() { a = MockWebServer().apply { start() }; b = MockWebServer().apply { start() } }
    @After fun down() { a.shutdown(); b.shutdown() }

    private fun custom(id: String, s: MockWebServer, model: String = "m") = ApiKey(id, ProviderType.CUSTOM, "key-$id", model, "", s.url("/v1").toString())
    private fun chatOk(text: String) = MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"$text"}}]}""")

    @Test fun fallsBackToNextKeyOnHttpError(): Unit = runBlocking {
        a.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Incorrect API key provided"}}"""))
        b.enqueue(chatOk("OK"))
        val chain = ProviderChain(listOf(custom("1", a), custom("2", b)))
        val r = chain.run(Capability.CHAT) { k -> ai.chat(k, "hi") }
        assertEquals("OK", r.value)
        assertEquals("2", r.usedKey.id)
        assertEquals(1, r.failures.size)
        assertTrue(r.failures[0].second.message!!.contains("Incorrect API key provided"))
        val req = a.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer key-1", req.getHeader("Authorization"))
        assertEquals("m", JSONObject(req.body.readUtf8()).getString("model"))
    }

    @Test fun fallsBackOnEmptyReplyAndOn500(): Unit = runBlocking {
        a.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        b.enqueue(MockResponse().setBody("""{"choices":[]}"""))
        try {
            ProviderChain(listOf(custom("1", a), custom("2", b))).run(Capability.CHAT) { ai.chat(it, "x") }
            fail("expected AllProvidersFailed")
        } catch (e: AllProvidersFailed) {
            assertEquals(2, e.failures.size)
            assertTrue(e.message!!.contains("HTTP 500"))
            assertTrue(e.message!!.contains("empty reply"))
        }
    }

    @Test fun skipsDisabledAndChatOnlyProvidersForSpeech() {
        val keys = listOf(
            ApiKey("1", ProviderType.ANTHROPIC, "sk-ant-x"),
            ApiKey("2", ProviderType.GROQ, "gsk_x", enabled = false),
            ApiKey("3", ProviderType.OPENROUTER, "sk-or-x"),
            ApiKey("4", ProviderType.OPENAI, "sk-x"),
            ApiKey("5", ProviderType.CUSTOM, "k"),          // custom without base URL is ignored
            ApiKey("6", ProviderType.GEMINI, "")             // empty key ignored
        )
        assertEquals(listOf("4"), ProviderChain(keys).candidates(Capability.STT).map { it.id })
        assertEquals(listOf("1", "3", "4"), ProviderChain(keys).candidates(Capability.CHAT).map { it.id })
    }

    @Test fun noCandidatesGivesFriendlyError(): Unit = runBlocking {
        try { ProviderChain(emptyList()).run(Capability.STT) { "x" }; fail() } catch (e: AllProvidersFailed) {
            assertTrue(e.message!!.contains("API Settings"))
        }
    }

    @Test fun anthropicRequestShape(): Unit = runBlocking {
        a.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"OK"}],"stop_reason":"end_turn"}"""))
        val k = ApiKey("1", ProviderType.ANTHROPIC, "sk-ant-1", baseUrl = a.url("/v1").toString())
        assertEquals("OK", ai.testKey(k))
        val req = a.takeRequest()
        assertEquals("/v1/messages", req.path)
        assertEquals("sk-ant-1", req.getHeader("x-api-key"))
        assertEquals("2023-06-01", req.getHeader("anthropic-version"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("claude-haiku-4-5", body.getString("model"))
        assertEquals(200, body.getInt("max_tokens"))
        assertEquals("user", body.getJSONArray("messages").getJSONObject(0).getString("role"))
    }

    @Test fun geminiRequestShapeAndBlockedReply(): Unit = runBlocking {
        a.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"OK"}],"role":"model"},"finishReason":"STOP"}]}"""))
        a.enqueue(MockResponse().setBody("""{"candidates":[{"finishReason":"SAFETY"}]}"""))
        val k = ApiKey("1", ProviderType.GEMINI, "AIzaX", baseUrl = a.url("/v1beta").toString())
        assertEquals("OK", ai.testKey(k))
        val req = a.takeRequest()
        assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", req.path)
        assertEquals("AIzaX", req.getHeader("x-goog-api-key"))
        try { ai.testKey(k); fail() } catch (e: ApiException) { assertTrue(e.message!!.contains("SAFETY")) }
    }

    @Test fun whisperTranscriptionMultipart(): Unit = runBlocking {
        a.enqueue(MockResponse().setBody("""{"text":"hi","segments":[{"start":0.0,"end":1.0,"text":"hi"}],"words":[{"word":"hi","start":0.1,"end":0.6}]}"""))
        val f = File.createTempFile("aud", ".m4a").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        val k = ApiKey("1", ProviderType.GROQ, "gsk_1", baseUrl = a.url("/openai/v1").toString())
        val segs = ai.transcribe(k, f, "hi")
        assertEquals(1, segs.size); assertEquals(100L, segs[0].words[0].startMs)
        val req = a.takeRequest()
        assertEquals("/openai/v1/audio/transcriptions", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("whisper-large-v3"))
        assertTrue(body.contains("verbose_json"))
        assertTrue(body.contains("name=\"timestamp_granularities[]\""))
        assertTrue(body.contains("\r\nword\r\n") && body.contains("\r\nsegment\r\n"))
        assertTrue(body.contains("name=\"language\"\r\n") && body.contains("\r\nhi\r\n"))
        f.delete()
    }

    @Test fun speechFallsBackFromWhisperToGemini(): Unit = runBlocking {
        a.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"Rate limit reached"}}"""))
        b.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"[{\"start\":0.5,\"end\":2,\"text\":\"नमस्ते\"}]"}]}}]}"""))
        val f = File.createTempFile("aud", ".m4a").apply { writeBytes(ByteArray(32)) }
        val adts = File.createTempFile("aud", ".aac").apply { writeBytes(ByteArray(16)) }
        val keys = listOf(ApiKey("w", ProviderType.OPENAI, "sk-1", baseUrl = a.url("/v1").toString()),
                          ApiKey("g", ProviderType.GEMINI, "AIza", baseUrl = b.url("/v1beta").toString()))
        val r = ProviderChain(keys).run(Capability.STT) { k -> ai.transcribe(k, f, "hi") { adts } }
        assertEquals("g", r.usedKey.id)
        assertEquals("नमस्ते", r.value[0].text); assertEquals(500L, r.value[0].startMs)
        assertTrue(r.failures[0].second.message!!.contains("Rate limit"))
        val g = JSONObject(b.takeRequest().body.readUtf8())
        val part = g.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getJSONObject("inline_data")
        assertEquals("audio/aac", part.getString("mime_type"))
        assertEquals("application/json", g.getJSONObject("generationConfig").getString("responseMimeType"))
        f.delete(); adts.delete()
    }

    @Test fun huggingFaceSpeechUrl(): Unit = runBlocking {
        a.enqueue(MockResponse().setBody("""{"text":"x","chunks":[{"timestamp":[0,1],"text":"x"}]}"""))
        val f = File.createTempFile("aud", ".m4a").apply { writeBytes(ByteArray(8)) }
        val k = ApiKey("h", ProviderType.HUGGINGFACE, "hf_1", baseUrl = a.url("/v1").toString())
        assertEquals(1, ai.transcribe(k, f, null).size)
        val req = a.takeRequest()
        assertEquals("/hf-inference/models/openai/whisper-large-v3", req.path)
        assertTrue(JSONObject(req.body.readUtf8()).getJSONObject("parameters").getBoolean("return_timestamps"))
        f.delete()
    }

    @Test fun openAiUsesMaxCompletionTokens(): Unit = runBlocking {
        a.enqueue(chatOk("OK"))
        ai.testKey(ApiKey("o", ProviderType.OPENAI, "sk-1", baseUrl = a.url("/v1").toString()))
        val body = JSONObject(a.takeRequest().body.readUtf8())
        assertEquals("gpt-4o-mini", body.getString("model"))
        assertTrue(body.has("max_completion_tokens") && !body.has("max_tokens"))
    }

    @Test fun errorMessageExtraction() {
        assertEquals("bad key", AiClient.errorMessage("""{"error":{"message":"bad key","type":"x"}}"""))
        assertEquals("Model too busy", AiClient.errorMessage("""{"error":"Model too busy"}"""))
        assertEquals("<html>nope", AiClient.errorMessage("<html>nope"))
    }

    @Test fun keyMaskingAndJson() {
        val k = ApiKey("1", ProviderType.OPENAI, "sk-proj-abcdef123456", "gpt-4o-mini", "", "")
        assertEquals("sk-p…3456", k.masked)
        val back = ApiKey.listFromJson(ApiKey.listToJson(listOf(k, k.copy(id = "2", type = ProviderType.CUSTOM, baseUrl = "https://x/v1"))))
        assertEquals(listOf(k, k.copy(id = "2", type = ProviderType.CUSTOM, baseUrl = "https://x/v1")), back)
        assertEquals("https://x/v1", ApiKey("3", ProviderType.CUSTOM, "k", baseUrl = "https://x/v1/").base)
    }
}
