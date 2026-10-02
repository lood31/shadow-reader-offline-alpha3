package com.shadowreader.app.pronunciation

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.roundToInt

enum class PronunciationStatus { GREEN, YELLOW, RED, UNKNOWN }
data class PhonemeAssessment(val index: Int, val ipa: String, val score: Double?, val confidence: Double,
    val status: PronunciationStatus, val startMs: Int?, val endMs: Int?, val gopRaw: Double?,
    val reasonCode: String? = null, val frameMargin: Double? = null, val pathMargin: Double? = null,
    val competitorIpa: String? = null)
data class WordAssessment(val index: Int, val text: String, val sourceStart: Int, val sourceEnd: Int,
    val score: Double?, val confidence: Double, val status: PronunciationStatus,
    val startMs: Int?, val endMs: Int?, val phonemes: List<PhonemeAssessment>)
data class PronunciationAssessment(val requestId: String, val text: String, val audioSha256: String,
    val accuracyScore: Double?, val calibrationStatus: String, val modelVersion: String,
    val configVersion: String, val reasonCode: String?, val words: List<WordAssessment>, val json: String,
    val assessmentKind: String = "LEGACY_SCORE", val coverage: Double = 0.0,
    val timingsMs: Map<String,Double> = emptyMap()) {
    val evidence get() = assessmentKind == "ACOUSTIC_EVIDENCE_EXPERIMENTAL"
}
data class PronunciationRequest(val requestId: String, val samples: FloatArray, val expectedText: String,
    val language: String = "en-US")

interface PronunciationEngine {
    suspend fun assess(request: PronunciationRequest): PronunciationAssessment
}

object PcmWav {
    fun encode(samples: FloatArray): ByteArray {
        require(samples.size in 8000..480000) { "发音评估支持 0.5–30 秒录音，录音仍保留。" }
        require(samples.all { it.isFinite() }) { "录音包含无效采样。" }
        val dataSize = samples.size * 2
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + dataSize)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16); putShort(1); putShort(1)
            putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(dataSize)
            samples.forEach { putShort((it.coerceIn(-1f, 1f) * 32768).roundToInt().coerceIn(-32768, 32767).toShort()) }
        }.array()
    }
    fun fingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
