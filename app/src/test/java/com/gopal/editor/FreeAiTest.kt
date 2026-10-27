package com.gopal.editor

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Free AI: providers.json validation, ordering, fallback, 429/timeout handling and the rate limiter, on a fake HTTP layer. */
class FreeAiTest {

    private val bundled = java.io.File("src/main/assets/providers_default.json").readText()
    private val repoJson = java.io.File("../providers.json").readText()

    private fun p(name: String, type: String = "openai", needsKey: Boolean = false, keyFrom: String? = null, prio: Int = 10,
                  gap: Long = 0, timeout: Long = 5_000, caps: Set<FreeCap> = setOf(FreeCap.TEXT)) =
        FreeProvider(name, type, if (type == "image-get") "https://img.example/{prompt}?w={width}&h={height}&s={seed}" else "https://$name.example/v1/chat/completions",
            "m-$name", needsKey, keyFrom, caps, prio, gap, timeout)

    private fun ok(text: String) = FreeResp(200, emptyMap(), JSONObject().put("choices", org.json.JSONArray().put(JSONObject()
        .put("message", JSONObject().put("content", text)))).toString().toByteArray(), "application/json")

    /** Fake HTTP: answers by host name; records every call. */
    private class Fake(val scope: TestScope, val answers: Map<String, suspend () -> FreeResp>) : FreeHttp {
        val calls = ArrayList<Pair<String, Long>>()
        val reqs = ArrayList<FreeReq>()
        override suspend fun send(req: FreeReq, timeoutMs: Long): FreeResp {
            val host = req.url.removePrefix("https://").substringBefore('.')
            calls += host to scope.currentTime; reqs += req
            return (answers[host] ?: error("no fake for $host")).invoke()
        }
    }

    private fun TestScope.limiter() = RateLimiter { currentTime }

    // ---------- providers.json ----------

    @Test fun bundledAndRepoListsAreValid() {
        val b = ProvidersJson.parse(bundled)
        assertTrue(b.skipped.toString(), b.skipped.isEmpty())
        assertTrue(b.providers.any { !it.needsKey && it.can(FreeCap.TEXT) })
        assertTrue(b.providers.any { !it.needsKey && it.can(FreeCap.IMAGE) })
        assertEquals(setOf("GROQ", "GEMINI", "OPENROUTER"), b.providers.filter { it.needsKey }.map { it.keyFrom }.toSet())
        assertEquals(ProvidersJson.parse(repoJson).providers, b.providers)
        // no secret anywhere in the shipped list
        assertFalse(Regex("(sk-|gsk_|AIza|sk-or-|hf_)[A-Za-z0-9]{8,}").containsMatchIn(bundled))
    }

    @Test fun badEntriesAreSkippedNotCrashing() {
        val json = """{"version":2,"providers":[
            {"name":"Good","type":"openai","url":"https://a.example/x","model":"m"},
            {"name":"NoHttps","type":"openai","url":"http://b.example/x","model":"m"},
            {"name":"BadType","type":"ftp","url":"https://c.example","model":"m"},
            {"type":"openai","url":"https://d.example","model":"m"},
            {"name":"KeyNoFrom","type":"openai","url":"https://e.example","model":"m","needsKey":true},
            {"name":"TextImage","type":"openai","url":"https://f.example","model":"m","capabilities":["image"]},
            {"name":"good","type":"openai","url":"https://g.example","model":"m"},
            42,
            {"name":"Img","type":"image-get","url":"https://h.example/{prompt}","minIntervalMs":99999999}
        ]}"""
        val r = ProvidersJson.parse(json)
        assertEquals(listOf("Good", "Img"), r.providers.map { it.name })
        assertEquals(2, r.version)
        assertEquals(7, r.skipped.size)
        assertEquals(10 * 60_000L, r.providers[1].minIntervalMs)   // clamped
        assertEquals(setOf(FreeCap.IMAGE), r.providers[1].caps)
        for (junk in listOf(null, "", "not json", "{}", "[1,2]", "{\"providers\": 5}", "<html>404</html>"))
            assertTrue(ProvidersJson.parse(junk).providers.isEmpty())
    }

    @Test fun listSource_online_thenCache_thenBundled() {
        val good = ProvidersJson.encode(listOf(p("Remote")))
        val cache = ProvidersJson.encode(listOf(p("Cached")))
        assertEquals(ProviderListChooser.Source.ONLINE, ProviderListChooser.choose(good, cache, bundled).source)
        assertTrue(ProviderListChooser.choose(good, cache, bundled).saveToCache)
        val c = ProviderListChooser.choose("<html>private repo 404</html>", cache, bundled)
        assertEquals(ProviderListChooser.Source.CACHE, c.source); assertEquals("Cached", c.providers.single().name); assertFalse(c.saveToCache)
        val b = ProviderListChooser.choose(null, null, bundled)
        assertEquals(ProviderListChooser.Source.BUNDLED, b.source); assertTrue(b.providers.isNotEmpty())
        assertEquals("https://raw.githubusercontent.com/gk/GopalEditor/main/providers.json", ProviderListChooser.defaultUrl("gk/GopalEditor"))
        assertEquals("", ProviderListChooser.defaultUrl("GITHUB_USERNAME/GopalEditor"))
    }

    // ---------- order ----------

    @Test fun userKeysFirst_thenKeylessByPriority_onlyInFreeMode() = runTest {
        val list = listOf(p("free2", prio = 20), p("free1", prio = 5), p("groqfree", needsKey = true, keyFrom = "GROQ"), p("orfree", needsKey = true, keyFrom = "OPENROUTER"))
        val keys = listOf(ApiKey("a", ProviderType.OPENAI, "sk-test"), ApiKey("b", ProviderType.GROQ, "gsk_test"), ApiKey("c", ProviderType.OPENROUTER, "x", enabled = false))
        val on = FreeAiRouter(list, keys, true, Fake(this, emptyMap()), limiter()).plan(FreeCap.TEXT)
        assertEquals(listOf("groqfree", "OpenAI", "free1", "free2"), on.map { it.id })
        assertEquals("b", on[0].key?.id)
        val off = FreeAiRouter(list, keys, false, Fake(this, emptyMap()), limiter()).plan(FreeCap.TEXT)
        assertEquals(listOf("groqfree", "OpenAI"), off.map { it.id })
        val img = FreeAiRouter(list + p("pic", "image-get", caps = setOf(FreeCap.IMAGE)), keys, true, Fake(this, emptyMap()), limiter()).plan(FreeCap.IMAGE)
        assertEquals(listOf("pic"), img.map { it.id })
    }

    @Test fun nothingToTry_hasHindiMessage() = runTest {
        L.lang = "hi"
        try { FreeAiRouter(listOf(p("free")), emptyList(), false, Fake(this, emptyMap()), limiter()).text("x"); fail() }
        catch (e: FreeAiFailed) { assertTrue(e.nothingToTry); assertTrue(e.message!!.contains("Free mode (बिना key)")) }
    }

    // ---------- fallback ----------

    @Test fun serverError_fallsBackToNext() = runTest {
        val f = Fake(this, mapOf("a" to { FreeResp(500, emptyMap(), "{\"error\":{\"message\":\"boom\"}}".toByteArray()) }, "b" to { ok("नमस्ते") }))
        val r = FreeAiRouter(listOf(p("a", prio = 1), p("b", prio = 2)), emptyList(), true, f, limiter()).text("hi")
        assertEquals("नमस्ते", r.value); assertEquals("b", r.used.id)
        assertEquals(FailKind.SERVER, r.attempts.single().kind)
    }

    @Test fun rateLimit429_honoursRetryAfter_andFallsBack() = runTest {
        val lim = limiter()
        val f = Fake(this, mapOf("a" to { FreeResp(429, mapOf("Retry-After" to "20"), "{}".toByteArray()) }, "b" to { ok("ok") }))
        val router = FreeAiRouter(listOf(p("a", prio = 1), p("b", prio = 2)), emptyList(), true, f, lim)
        val r = router.text("x")
        assertEquals("b", r.used.id); assertEquals(FailKind.RATE_LIMIT, r.attempts.single().kind)
        assertEquals(20_000L, lim.waitMs("a"))
        // within the cool-down "a" is skipped without a request
        val before = f.calls.size
        val r2 = router.text("y")
        assertEquals("b", r2.used.id); assertEquals(FailKind.SKIPPED_BUSY, r2.attempts.single().kind)
        assertEquals(listOf("b"), f.calls.drop(before).map { it.first })
    }

    @Test fun pollinationsFreeLimit402WithRetryAfter_isARateLimit() = runTest {
        val lim = limiter()
        val f = Fake(this, mapOf("img" to { FreeResp(402, mapOf("retry-after" to "15"), "{\"error\":{\"code\":\"FREE_LIMIT\"}}".toByteArray()) }))
        val img = FreeProvider("img", "image-get", "https://img.example/{prompt}", caps = setOf(FreeCap.IMAGE), minIntervalMs = 0)
        try { FreeAiRouter(listOf(img), emptyList(), true, f, lim).image("cat", 64, 64, 1); fail() }
        catch (e: FreeAiFailed) {
            assertEquals(FailKind.RATE_LIMIT, e.attempts.single().kind)
            L.lang = "hi"; assertTrue(e.hindi().contains("व्यस्त"))
        }
        assertEquals(15_000L, lim.waitMs("img"))
    }

    @Test fun timeout_movesOn() = runTest {
        val f = Fake(this, mapOf("slow" to { delay(60_000); ok("late") }, "fast" to { ok("fast") }))
        val r = FreeAiRouter(listOf(p("slow", prio = 1, timeout = 5_000), p("fast", prio = 2)), emptyList(), true, f, limiter()).text("x")
        assertEquals("fast", r.value); assertEquals(FailKind.TIMEOUT, r.attempts.single().kind)
        assertEquals(5_000L, currentTime)   // gave up exactly at the provider's timeout
    }

    @Test fun networkError_andEmptyReply_moveOn_thenAllFail_inHindi() = runTest {
        L.lang = "hi"
        val f = Fake(this, mapOf(
            "net" to { throw java.net.UnknownHostException("no internet") },
            "empty" to { ok("   ") },
            "auth" to { FreeResp(401, emptyMap(), "{\"error\":{\"message\":\"bad key\"}}".toByteArray()) }))
        try {
            FreeAiRouter(listOf(p("net", prio = 1), p("empty", prio = 2), p("auth", prio = 3)), emptyList(), true, f, limiter()).text("x"); fail()
        } catch (e: FreeAiFailed) {
            assertEquals(listOf(FailKind.NETWORK, FailKind.BAD_REPLY, FailKind.AUTH), e.attempts.map { it.kind })
            assertTrue(e.message!!, e.message!!.startsWith("अभी सारे AI सर्वर फेल हो गए"))
        }
    }

    // ---------- rate limiter ----------

    @Test fun minimumGap_isWaitedForWhenShort() = runTest {
        val f = Fake(this, mapOf("a" to { ok("1") }))
        val router = FreeAiRouter(listOf(p("a", gap = 3_000)), emptyList(), true, f, limiter())
        router.text("1"); router.text("2"); router.text("3")
        assertEquals(listOf(0L, 3_000L, 6_000L), f.calls.map { it.second })
    }

    @Test fun longGap_skipsToNextProvider_butLastOneIsWaitedFor() = runTest {
        val f = Fake(this, mapOf("a" to { ok("a") }, "b" to { ok("b") }))
        val router = FreeAiRouter(listOf(p("a", prio = 1, gap = 30_000), p("b", prio = 2, gap = 30_000)), emptyList(), true, f, limiter(), maxGapWaitMs = 4_000, maxLastWaitMs = 50_000)
        assertEquals("a", router.text("1").used.id)
        assertEquals("b", router.text("2").used.id)          // a is in its 30 s gap -> next one, no waiting
        val r3 = router.text("3")                             // both busy: a skipped, b is the last -> waited for
        assertEquals("b", r3.used.id)
        assertEquals(30_000L, f.calls.last().second)
    }

    @Test fun retryAfterParsing() {
        assertEquals(12_000L, RateLimiter.parseRetryAfter("12", 0))
        assertEquals(1_500L, RateLimiter.parseRetryAfter("1.5", 0))
        assertEquals(null, RateLimiter.parseRetryAfter(null, 0))
        assertEquals(null, RateLimiter.parseRetryAfter("soon", 0))
        assertEquals(RateLimiter.MAX_COOL_MS, RateLimiter.parseRetryAfter("999999", 0))
        val now = 1_700_000_000_000L
        val date = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
            .format(java.util.Date(now + 30_000))
        assertEquals(30_000L, RateLimiter.parseRetryAfter(date, now))
    }

    // ---------- requests / replies ----------

    @Test fun requestShapes_noKeyForKeyless_bearerForUserKey_gemini() {
        val free = FreeCalls.textRequest(p("pol"), null, "hi", "sys", 100)
        assertFalse(free.headers.containsKey("Authorization"))
        assertEquals("m-pol", JSONObject(free.body!!).getString("model"))
        assertEquals("system", JSONObject(free.body!!).getJSONArray("messages").getJSONObject(0).getString("role"))
        val groq = FreeCalls.textRequest(p("groq", needsKey = true, keyFrom = "GROQ"), ApiKey("k", ProviderType.GROQ, "gsk_abc"), "hi", null, 100)
        assertEquals("Bearer gsk_abc", groq.headers["Authorization"])
        val gem = FreeCalls.textRequest(FreeProvider("g", "gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash", true, "GEMINI"),
            ApiKey("k", ProviderType.GEMINI, "AIzaX"), "hi", null, 100)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent", gem.url)
        assertEquals("AIzaX", gem.headers["x-goog-api-key"])
        assertEquals("hello", FreeCalls.parseText("gemini", """{"candidates":[{"content":{"parts":[{"text":"hello"}]}}]}"""))
        assertEquals("hey", FreeCalls.parseText("anthropic", """{"content":[{"type":"text","text":"hey"}]}"""))
    }

    @Test fun imageUrl_isEncoded_andNonImageIsRejected() = runTest {
        val img = FreeProvider("img", "image-get", "https://image.pollinations.ai/prompt/{prompt}?width={width}&height={height}&seed={seed}&nologo=true", caps = setOf(FreeCap.IMAGE), minIntervalMs = 0)
        assertEquals("https://image.pollinations.ai/prompt/%E0%A4%B8%E0%A5%82%E0%A4%B0%E0%A4%9C%20sunset%20%26%20sky?width=720&height=1280&seed=7&nologo=true",
            FreeCalls.imageUrl(img, "सूरज sunset & sky", 720, 1280, 7))
        val jpeg = ByteArray(2000).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() }
        val good = Fake(this, mapOf("image" to { FreeResp(200, emptyMap(), jpeg, "image/jpeg") }))
        assertEquals(2000, FreeAiRouter(listOf(img), emptyList(), true, good, limiter()).image("x", 64, 64, 1).value.size)
        val html = Fake(this, mapOf("image" to { FreeResp(200, emptyMap(), "<html>".toByteArray(), "text/html") }))
        try { FreeAiRouter(listOf(img), emptyList(), true, html, limiter()).image("x", 64, 64, 1); fail() }
        catch (e: FreeAiFailed) { assertEquals(FailKind.BAD_REPLY, e.attempts.single().kind) }
    }

    // ---------- AI tools ----------

    @Test fun hookIdeas_parseJsonOrSections() {
        val j = HookIdeas.parse("```json\n{\"hooks\":[\"रुको!\"],\"titles\":[\"T1\"],\"hashtags\":[\"reels\",\"#हिंदी\", \"#reels\"]}\n```")
        assertEquals(listOf("रुको!"), j.hooks); assertEquals(listOf("#reels", "#हिंदी"), j.hashtags)
        val s = HookIdeas.parse("HOOKS:\n1. पहला हुक\n2. दूसरा\nTITLES:\n- टाइटल\nHASHTAGS: #a #b\n#c")
        assertEquals(listOf("पहला हुक", "दूसरा"), s.hooks); assertEquals(listOf("टाइटल"), s.titles); assertEquals(listOf("#a", "#b", "#c"), s.hashtags)
        assertTrue(HookIdeas.parse("sorry").isEmpty)
    }

    @Test fun captionFix_previewAndApply_keepTimings() {
        val caps = listOf(Caption("1", 0, 1000, "मै ठिक हु", listOf(Word(0, 300, "मै"), Word(300, 600, "ठिक"), Word(600, 1000, "हु"))),
                          Caption("2", 1000, 2000, "सब सही है"))
        val lines = Romanizer.parse("1|मैं ठीक हूँ\n2|सब सही है", caps.map { it.text })
        val d = CaptionAi.diff(caps, lines)
        assertEquals(listOf(true, false), d.map { it.changed })
        val out = CaptionAi.apply(caps, lines)
        assertEquals("मैं ठीक हूँ", out[0].text); assertEquals(0L, out[0].startMs); assertEquals(1000L, out[0].endMs)
        assertEquals(listOf(0L, 300L, 600L), out[0].words.map { it.startMs })   // same word count -> same word timings
        assertEquals(caps[1], out[1])
        assertTrue(CaptionAi.fixPrompt(caps.map { it.text }).contains("2|सब सही है"))
        assertEquals(listOf(0..39, 40..44), CaptionAi.chunks(List(45) { "x" }))
    }

    @Test fun scriptPrompt_andCleaning() {
        assertTrue(ScriptWriter.prompt("चाय", "hi", 30).contains("Hindi in Devanagari"))
        assertEquals("HOOK: रुको\nSCRIPT", ScriptWriter.clean("```\n**HOOK:** रुको\n## SCRIPT\n```"))
        assertEquals(720 to 1280, AiImageSize.forAspect(Aspect.R9_16, false))
        assertEquals(768 to 768, AiImageSize.forAspect(Aspect.R16_9, true))
    }
}
