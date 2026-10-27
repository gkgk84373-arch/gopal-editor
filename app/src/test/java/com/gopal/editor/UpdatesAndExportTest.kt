package com.gopal.editor

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UpdatesAndExportTest {
    private val json = """{"versionCode":42,"versionName":"1.0.42","apkUrl":"https://github.com/gk/GopalEditor/releases/download/v1.0.42/gopal-editor.apk","changelog":["Faster export","Fix captions"]}"""

    @Test fun parseVersionJson() {
        val v = VersionInfo.parse(json)
        assertEquals(42, v.versionCode); assertEquals("1.0.42", v.versionName)
        assertEquals("• Faster export\n• Fix captions", v.changelog)
        assertEquals("plain text", VersionInfo.parse("""{"versionCode":3,"apkUrl":"https://x/a.apk","changelog":"plain text"}""").changelog)
    }

    @Test fun rejectsBadVersionJson() {
        for (bad in listOf("""{"versionName":"1"}""", """{"versionCode":2,"apkUrl":"http://insecure/a.apk"}""")) {
            try { VersionInfo.parse(bad); fail(bad) } catch (e: IllegalArgumentException) { /* ok */ }
        }
    }

    @Test fun newerOnlyByVersionCode() {
        val v = VersionInfo.parse(json)
        assertTrue(VersionCompare.isNewer(v, 41))
        assertFalse(VersionCompare.isNewer(v, 42))
        assertFalse(VersionCompare.isNewer(v, 100))
        assertFalse(VersionCompare.isNewer(null, 1))
        assertTrue(VersionCompare.compareNames("1.2.10", "1.2.9") > 0)
        assertEquals(0, VersionCompare.compareNames("v1.0", "1.0.0"))
        assertTrue(VersionCompare.compareNames("1.0.0", "1.1") < 0)
    }

    @Test fun updaterFetchAndDownload(): Unit = runBlocking {
        val s = MockWebServer(); s.start()
        s.enqueue(MockResponse().setBody(json))
        s.enqueue(MockResponse().setResponseCode(404))
        val payload = ByteArray(300_000) { (it % 251).toByte() }
        s.enqueue(MockResponse().setBody(Buffer().write(payload)))
        val u = Updater()
        assertEquals(42, u.fetch(s.url("/version.json").toString()).versionCode)
        try { u.fetch(s.url("/version.json").toString()); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("No release")) }
        val dest = File(kotlin.io.path.createTempDirectory().toFile(), "u/app.apk")
        val seen = ArrayList<Float>()
        u.download(s.url("/app.apk").toString(), dest) { seen += it }
        assertTrue(dest.readBytes().contentEquals(payload))
        assertFalse(File(dest.path + ".part").exists())
        assertEquals(1f, seen.last())
        assertTrue(seen.zipWithNext().all { (x, y) -> y >= x })
        s.shutdown()
    }

    @Test fun versionJsonUrl() {
        assertEquals("https://github.com/gk/GopalEditor/releases/latest/download/version.json", UpdateConfig.versionJsonUrl("gk/GopalEditor"))
    }

    @Test fun exportSizesAreEvenAndRightOrientation() {
        assertEquals(1080 to 1920, ExportMath.outputSize(Aspect.R9_16, 1080))
        assertEquals(1920 to 1080, ExportMath.outputSize(Aspect.R16_9, 1080))
        assertEquals(720 to 720, ExportMath.outputSize(Aspect.R1_1, 720))
        assertEquals(1080 to 1350, ExportMath.outputSize(Aspect.R4_5, 1080))
        assertEquals(2160 to 3840, ExportMath.outputSize(Aspect.R9_16, 2160))
        assertEquals(720 to 1280, ExportMath.outputSize(Aspect.R9_16, 720))
        for (a in Aspect.entries) for (p in listOf(720, 1080, 2160)) {
            val (w, h) = ExportMath.outputSize(a, p); assertTrue(w % 2 == 0 && h % 2 == 0)
        }
    }

    @Test fun bitrates() {
        assertEquals(6_220_800, ExportMath.autoBitrate(1080, 1920, 30))
        assertEquals(2_000_000, ExportMath.autoBitrate(100, 100, 24))
        assertEquals(60_000_000, ExportMath.autoBitrate(3840, 2160, 120))
        assertEquals(8_000_000, ExportMath.bitrate(ExportSettings(bitrateMbps = 8), 1080, 1920))
        assertEquals(6.348f, ExportMath.estimateMb(6_220_800, 8_000), 0.01f)
    }

    @Test fun audioPlanFillsGapsAndClips() {
        val c1 = AudioClip("a", "u", "m", 20_000, 0, 5_000, startMs = 2_000)
        val c2 = AudioClip("b", "u", "m", 20_000, 1_000, 9_000, startMs = 6_000)   // overlaps c1 by 1 s
        val p = AudioPlan.pieces(listOf(c2, c1), totalMs = 12_000)
        assertEquals(listOf(null, "a", "b"), p.map { it.clip?.id })
        assertEquals(2_000L, p[0].durMs)
        assertEquals(7_000L, p.sumOf { it.durMs } - 5_000)   // ends exactly at 12 s
        assertEquals(12_000L, p.sumOf { it.durMs })
        assertEquals(2_000L, p[2].clip!!.inMs)            // overlapped head dropped
        assertEquals(5_000L, p[2].durMs)                  // clipped at the video end
        assertEquals(listOf(null, "a", null), AudioPlan.pieces(listOf(c1), 10_000).map { it.clip?.id })
        assertTrue(AudioPlan.pieces(emptyList(), 10_000).isEmpty())
    }

    @Test fun silentWavHeader() {
        val b = Wav.silence(1_000)
        assertEquals(44 + 88_200, b.size)
        assertEquals("RIFF", String(b, 0, 4)); assertEquals("WAVE", String(b, 8, 4)); assertEquals("data", String(b, 36, 4))
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44_100, bb.getInt(24)); assertEquals(88_200, bb.getInt(40)); assertEquals(36 + 88_200, bb.getInt(4))
        assertTrue(b.drop(44).all { it == 0.toByte() })
    }

    @Test fun captionGeometry() {
        assertEquals(1080 * 0.058f, CaptionGeometry.textPx(1080, 1920, 1f), 0.01f)
        assertEquals(CaptionGeometry.textPx(1080, 1920, 1f), CaptionGeometry.textPx(1920, 1080, 1f), 0.01f)   // short side rules
        assertEquals(929, CaptionGeometry.maxWidth(1080))
        assertEquals(1920 * 0.8f - 50f, CaptionGeometry.blockTop(1920, 100f, 0.8f), 0.01f)
        assertEquals(1920 * 0.94f - 300f, CaptionGeometry.blockTop(1920, 300f, 0.99f), 0.01f)  // pushed above bottom safe area
        assertEquals(1920 * 0.06f, CaptionGeometry.blockTop(1920, 100f, 0.0f), 0.01f)
    }

    @Test fun wordRanges() {
        assertEquals(listOf(0..4, 6..10), CaptionRenderer.wordRanges("Gopal एडिटर  x", 2))
        assertTrue(Templates.all.size >= 6)
        assertEquals(Templates.all.map { it.id }.toSet().size, Templates.all.size)
        assertEquals("classic", Templates.byId("missing").id)
    }

    @Test fun adtsHeader() {
        val h = Adts.header(100, 2, 4, 2)
        assertEquals(7, h.size)
        assertEquals(0xFF, h[0].toInt() and 0xFF); assertEquals(0xF1, h[1].toInt() and 0xFF)
        val len = ((h[3].toInt() and 3) shl 11) or ((h[4].toInt() and 0xFF) shl 3) or ((h[5].toInt() and 0xFF) shr 5)
        assertEquals(107, len)
        assertEquals(Triple(2, 4, 2), Adts.parseAsc(byteArrayOf(0x12, 0x10)))
    }
}
