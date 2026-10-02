package com.shadowreader.app

import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import android.provider.Settings
import com.shadowreader.app.pronunciation.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/** Explicitly invoked, isolated benchmark; never modifies training data. */
class OfflineBenchmark : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val report = JSONObject().put("status","FAIL").put("uiAndUpgradeVerified",false)
        val engine = LocalGopPronunciationEngine(targetContext)
        try {
            check(BuildConfig.OFFLINE_PRONUNCIATION)
            check(Settings.Global.getInt(targetContext.contentResolver,Settings.Global.AIRPLANE_MODE_ON,0) == 1) { "Enable airplane mode first." }
            runBlocking {
                val bytes = context.assets.open("benchmark/pcm16.raw").use { it.readBytes() }
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val five = FloatArray(bytes.size/2) { buffer.short/32768f }
                val text = "We study green mountains."
                var next = 0
                suspend fun assess(seconds: Int): PronunciationAssessment {
                    val samples = FloatArray(seconds*16000) { five[it%five.size] }
                    return engine.assess(PronunciationRequest("benchmark-${next++}",samples,List(seconds/5) { text }.joinToString(" "))).also {
                        check("inference" in it.timingsMs && it.words.isNotEmpty()) { "Benchmark must execute acoustic inference." }
                    }
                }
                val cold = JSONObject(assess(5).json)
                report.put("cold",cold)
                val gold = JSONObject(context.assets.open("benchmark/g2p.json").bufferedReader().use { it.readText() })
                for (word in gold.keys()) {
                    val actual = EspeakNative.phonemize(word)?.replace("ˈ","")?.replace("ˌ","")?.split('_')
                        ?.flatMap { it.trim().split(Regex("\\s+")) }?.filter { it.isNotBlank() }
                    val expected = gold.getJSONArray(word).let { a -> (0 until a.length()).map { a.getString(it) } }
                    check(actual == expected) { "G2P mismatch: $word" }
                }
                report.put("nativeG2pWords",gold.length())
                val warm = JSONArray(); val timings = mutableListOf<Double>()
                var peak = cold.getJSONObject("diagnostics").getLong("pronunciationSampledPeakPssKb")
                repeat(20) {
                    val result = JSONObject(assess(5).json); warm.put(result)
                    timings.add(result.getJSONObject("timingsMs").getDouble("total"))
                    peak = maxOf(peak,result.getJSONObject("diagnostics").getLong("pronunciationSampledPeakPssKb"))
                }
                report.put("warm5s",warm)
                val durations = JSONArray()
                for (seconds in listOf(10,15,30)) {
                    val result = JSONObject(assess(seconds).json); durations.put(result)
                    peak = maxOf(peak,result.getJSONObject("diagnostics").getLong("pronunciationSampledPeakPssKb"))
                }
                report.put("longRecordings",durations)
                val old = launch { assess(30) }; delay(100); old.cancelAndJoin()
                val recovered = JSONObject(assess(5).json)
                report.put("afterCancellation",recovered)
                peak = maxOf(peak,recovered.getJSONObject("diagnostics").getLong("pronunciationSampledPeakPssKb"))
                val p95 = timings.sorted()[ceil(.95*timings.size).toInt()-1]
                report.put("warm5sP95Ms",p95).put("sampledPeakPssKb",peak)
                check(p95 <= 10000) { "Warm 5s P95 exceeds 10s." }
                check(peak <= 2L*1024*1024) { "PSS exceeds 2 GiB." }
                report.put("status","PASS_ENGINE_ONLY")
            }
        } catch (error: Throwable) {
            report.put("error",error.toString())
        } finally {
            engine.close()
            File(targetContext.filesDir,"offline-benchmark.json").writeText(report.toString(2))
            finish(if (report.getString("status") == "PASS_ENGINE_ONLY") Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("status",report.getString("status")); putString("report","files/offline-benchmark.json") })
        }
    }
}
