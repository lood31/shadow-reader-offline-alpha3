package com.shadowreader.app.pronunciation

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

class PronunciationTest {
    private fun response(requestId: String = "a", hash: String = "hash", calibration: String = "UNCALIBRATED") = """
        {"schemaVersion":1,"requestId":"$requestId","text":"I I.","audioSha256":"$hash","language":"en-US",
        "calibrationStatus":"$calibration","modelVersion":"model","configVersion":"cfg","accuracyScore":null,
        "words":[{"wordIndex":0,"text":"I","sourceStart":0,"sourceEnd":1,"score":null,"confidence":0.9,
        "status":"UNKNOWN","phonemes":[]},{"wordIndex":1,"text":"I","sourceStart":2,"sourceEnd":3,
        "score":null,"confidence":0.9,"status":"UNKNOWN","phonemes":[]}]}
    """.trimIndent()

    @Test fun waveEncodingPreservesPcmFormatAndAmplitude() {
        val audio = FloatArray(8000); audio[0] = -1f; audio[1] = 1f
        val wav = PcmWav.encode(audio)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav.copyOfRange(0,4),Charsets.US_ASCII))
        assertEquals(16000,b.getInt(24)); assertEquals(1,b.getShort(22).toInt())
        assertEquals(16,b.getShort(34).toInt()); assertEquals(16000,b.getInt(40))
        assertEquals(-32768,b.getShort(44).toInt()); assertEquals(32767,b.getShort(46).toInt())
    }
    @Test fun repeatedWordsUseSourcePositions() {
        val words = PronunciationJson.decode(response()).words
        assertEquals(listOf(0,2),words.map { it.sourceStart })
        assertTrue(words.all { it.status == PronunciationStatus.UNKNOWN && it.score == null })
    }
    @Test fun actualBackendResponsesPreservePhoneDetailsAndCalibration() {
        fun fixture(name: String) = javaClass.getResourceAsStream("/pronunciation/$name.json")!!
            .bufferedReader(Charsets.UTF_8).use { PronunciationJson.decode(it.readText()) }
        val raw = fixture("uncalibrated")
        assertEquals(9,raw.words.size)
        assertTrue(raw.words.all { it.score == null && it.status == PronunciationStatus.UNKNOWN })
        assertTrue(raw.words.flatMap { it.phonemes }.all { it.startMs != null && it.endMs != null })
        val pilot = fixture("pilot")
        assertEquals("PILOT",pilot.calibrationStatus)
        assertTrue(pilot.words.flatMap { it.phonemes }.any { it.score != null })
        assertEquals(raw.words.map { it.text },pilot.words.map { it.text })
    }
    @Test fun conflictReasonSurvivesResponseParsing() {
        val raw = javaClass.getResourceAsStream("/pronunciation/pilot.json")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(raw)
        val phone = root.getJSONArray("words").getJSONObject(0).getJSONArray("phonemes").getJSONObject(0)
        phone.put("score",JSONObject.NULL).put("status","UNKNOWN").put("reasonCode","ACOUSTIC_CONFLICT")
        val decoded = PronunciationJson.decode(root.toString()).words[0].phonemes[0]
        assertEquals("ACOUSTIC_CONFLICT",decoded.reasonCode)
        assertNull(decoded.score)
        assertEquals(PronunciationStatus.UNKNOWN,decoded.status)
    }
    @Test fun rejectsUncalibratedScoresAndInvalidSpans() {
        val root = JSONObject(response())
        root.getJSONArray("words").getJSONObject(0).put("score", 90)
        assertTrue(runCatching { PronunciationJson.decode(root.toString()) }.isFailure)
        val other = JSONObject(response())
        other.getJSONArray("words").getJSONObject(1).put("sourceStart",1)
        assertTrue(runCatching { PronunciationJson.decode(other.toString()) }.isFailure)
    }
    @Test fun scoresDoNotDependOnTranscription() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val samples = FloatArray(8000)
            val hash = PcmWav.fingerprint(PcmWav.encode(samples))
            server.enqueue(MockResponse().setBody(response(hash=hash)))
            val result = RemoteGopPronunciationEngine(server.url("/").toString()).assess(PronunciationRequest("a",samples,"I I."))
            assertEquals("a",result.requestId)
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("metadata")); assertFalse(body.contains("transcript"))
        } finally { server.shutdown() }
    }
    @Test fun rejectsResponseFromAnotherAttempt() = runBlocking {
        val server = MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setBody(response(requestId="old")))
            assertTrue(runCatching { RemoteGopPronunciationEngine(server.url("/").toString())
                .assess(PronunciationRequest("new",FloatArray(8000),"I I.")) }.isFailure)
        } finally { server.shutdown() }
    }
    @Test fun remoteAssessmentCanBeCancelled() = runBlocking {
        val server = MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending = async(Dispatchers.IO) { RemoteGopPronunciationEngine(server.url("/").toString())
                .assess(PronunciationRequest("a",FloatArray(8000),"I I.")) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3,TimeUnit.SECONDS) })
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
        } finally { server.shutdown() }
    }
}
