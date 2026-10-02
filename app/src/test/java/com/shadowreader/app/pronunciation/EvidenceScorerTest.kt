package com.shadowreader.app.pronunciation

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ln

class EvidenceScorerTest {
    @Test fun independentKotlinPortMatchesSyntheticDesktopFixtures() {
        val root = JSONObject(javaClass.getResourceAsStream("/pronunciation/evidence.json")!!.bufferedReader().use { it.readText() })
        val phoneIds = root.getJSONArray("phoneIds").let { a -> IntArray(a.length()) { a.getInt(it) } }
        val vocab = root.getJSONObject("vocab").let { obj -> obj.keys().asSequence().associate { obj.getInt(it) to it } }
        val cases = root.getJSONArray("cases")
        for (n in 0 until cases.length()) {
            val c = cases.getJSONObject(n)
            val values = c.getJSONArray("logp").let { a -> Array(a.length()) { t -> a.getJSONArray(t).let { row -> DoubleArray(row.length()) { row.getDouble(it) } } } }
            val targets = c.getJSONArray("targets").let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { q -> IntArray(q.length()) { q.getInt(it) } } } }
            val ipas = c.getJSONArray("ipas").let { a -> (0 until a.length()).map { a.getString(it) } }
            val results = EvidenceScorer(values,targets,root.getInt("blank"),phoneIds).assess(ipas,vocab)
            val gold = c.getJSONArray("expected")
            for (i in results.indices) {
                val expected = gold.getJSONObject(i); val actual = results[i]
                assertEquals("${c.getString("id")}/$i",expected.getString("status"),actual.status.name)
                assertEquals(expected.getString("reasonCode"),actual.reason)
                assertEquals(expected.getDouble("confidence"),actual.confidence,2e-6)
                if (expected.has("gopRaw")) assertEquals(expected.getDouble("gopRaw"),actual.gop!!,2e-6)
                if (expected.has("pathMargin")) assertEquals(expected.getDouble("pathMargin"),actual.pathMargin!!,1e-7)
            }
        }
    }
    @Test fun evidenceProtocolPreservesNullScoreColorsAndRejectsScores() {
        val source = javaClass.getResourceAsStream("/pronunciation/pilot.json")!!.bufferedReader().use { it.readText() }
        val root = JSONObject(source).put("schemaVersion",2).put("assessmentKind","ACOUSTIC_EVIDENCE_EXPERIMENTAL")
            .put("calibrationStatus","UNCALIBRATED").put("accuracyScore",JSONObject.NULL).put("coverage",.8)
        val words = root.getJSONArray("words")
        for (i in 0 until words.length()) {
            val word = words.getJSONObject(i).put("score",JSONObject.NULL).put("status",if (i%2 == 0) "RED" else "YELLOW")
            val phones = word.getJSONArray("phonemes")
            for (j in 0 until phones.length()) phones.getJSONObject(j).put("score",JSONObject.NULL).put("status","YELLOW")
        }
        val decoded = PronunciationJson.decode(root.toString())
        assertTrue(decoded.evidence); assertNull(decoded.accuracyScore)
        assertEquals(PronunciationStatus.RED,decoded.words.first().status)
        assertEquals(PronunciationStatus.YELLOW,decoded.words.first().phonemes.first().status)
        words.getJSONObject(0).put("score",90)
        assertTrue(runCatching { PronunciationJson.decode(root.toString()) }.isFailure)
    }
    @Test fun boundariesAllophonesAndCoverageRemainConservative() {
        assertEquals(PronunciationStatus.GREEN,EvidenceScorer.classify(.7,ln(3.0),ln(3.0)).first)
        assertEquals(PronunciationStatus.RED,EvidenceScorer.classify(.8,-ln(10.0),-ln(10.0)).first)
        assertEquals(PronunciationStatus.YELLOW,EvidenceScorer.classify(.9,-7.0,-7.0,flap=true).first)
        assertEquals(PronunciationStatus.UNKNOWN,EvidenceScorer.classify(.99,7.0,7.0,ambiguous=true).first)
        assertEquals(PronunciationStatus.UNKNOWN,EvidenceScorer.classify(.69,7.0,7.0).first)
        assertEquals(PronunciationStatus.YELLOW,EvidenceScorer.classify(.9,0.0,0.0).first)
        assertEquals(PronunciationStatus.RED,EvidenceScorer.summarize(List(10) { PronunciationStatus.GREEN }+PronunciationStatus.RED).first)
        assertEquals(PronunciationStatus.UNKNOWN,EvidenceScorer.summarize(listOf(PronunciationStatus.GREEN,PronunciationStatus.UNKNOWN)).first)
    }
    @Test fun replacementTieWithDifferentIntervalsIsAmbiguous() {
        val scorer = EvidenceScorer(Array(4) { DoubleArray(3) { ln(1.0/3) } },listOf(intArrayOf(1)),0,intArrayOf(1,2))
        assertTrue(scorer.replace(0,intArrayOf(2)).ambiguous)
    }
}
