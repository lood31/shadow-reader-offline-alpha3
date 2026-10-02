package com.shadowreader.app.pronunciation

import ai.onnxruntime.*
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.nio.FloatBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.*

internal object EspeakNative {
    init { System.loadLibrary("shadow_phonemes") }
    external fun initialize(parentPath: String): Boolean
    external fun phonemize(text: String): String?
}

class LocalGopPronunciationEngine(private val context: Context,
    private val stage: (String,String) -> Unit = { _,_ -> }) : PronunciationEngine, AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor()
    private val sampler = Executors.newSingleThreadScheduledExecutor()
    private val env by lazy { OrtEnvironment.getEnvironment() }
    private var model: OrtSession? = null
    private var vad: OrtSession? = null
    private var vocab = emptyMap<String,Int>()
    private var manifest = JSONObject()
    private var directory: File? = null
    private val closed = AtomicBoolean(false)

    override suspend fun assess(request: PronunciationRequest): PronunciationAssessment = withTimeout(60_000) {
        suspendCancellableCoroutine { continuation ->
            check(!closed.get()) { "离线评估已关闭。" }
            val canceled = AtomicBoolean(false)
            val activeRun = AtomicReference<OrtSession.RunOptions?>()
            continuation.invokeOnCancellation { canceled.set(true); runCatching { activeRun.get()?.setTerminate(true) } }
            worker.execute {
                InferenceGate.lock.lock()
                val peak = AtomicLong(Debug.getPss().toLong())
                val sample = sampler.scheduleWithFixedDelay({ peak.accumulateAndGet(Debug.getPss().toLong()) { x,y -> max(x,y) } },0,100,TimeUnit.MILLISECONDS)
                fun checkCanceled() { if (canceled.get() || !continuation.isActive || closed.get()) throw CancellationException() }
                try {
                    checkCanceled()
                    OrtSession.RunOptions().use { run ->
                        activeRun.set(run)
                        val result = calculate(request,run,peak,::checkCanceled)
                        if (continuation.isActive) continuation.resume(result)
                    }
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally {
                    activeRun.set(null); sample.cancel(false); InferenceGate.lock.unlock()
                }
            }
        }
    }

    private fun ready(checkCanceled: () -> Unit): Double {
        if (model != null) return 0.0
        val start = SystemClock.elapsedRealtime()
        manifest = JSONObject(context.assets.open("pronunciation/manifest.json").bufferedReader().use { it.readText() })
        require(manifest.getString("onnxruntime") == "1.24.3")
        val root = File(context.noBackupFilesDir,"pronunciation/${manifest.getString("bundleVersion")}").apply { mkdirs() }
        val files = manifest.getJSONObject("files")
        for (name in files.keys()) {
            checkCanceled()
            val file = File(root,name)
            require(file.canonicalPath.startsWith(root.canonicalPath+File.separator))
            val spec = files.getJSONObject(name)
            if (file.length() == spec.getLong("bytes") && file.exists() && hash(file) == spec.getString("sha256")) continue
            check(root.usableSpace >= spec.getLong("bytes")+100L*1024*1024) { "离线模型初始化空间不足，请释放存储后重试；录音已保留。" }
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile,file.name+".part")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                context.assets.open("pronunciation/$name").use { input -> temp.outputStream().use { output ->
                    val buffer = ByteArray(64*1024)
                    while (true) {
                        checkCanceled(); val n = input.read(buffer); if (n < 0) break
                        digest.update(buffer,0,n); output.write(buffer,0,n)
                    }
                } }
                check(temp.length() == spec.getLong("bytes") && hex(digest.digest()) == spec.getString("sha256")) { "离线模型校验失败，请重试初始化。" }
                Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
            } finally { temp.delete() }
        }
        val inventory = JSONObject(File(root,"vocab.json").readText())
        vocab = inventory.keys().asSequence().associateWith { inventory.getInt(it) }
        check(EspeakNative.initialize(root.absolutePath)) { "英语音素资源初始化失败，请重试。" }
        fun options(threads: Int) = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads); setInterOpNumThreads(1)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setMemoryPatternOptimization(false); setCPUArenaAllocator(false)
        }
        try {
            options(1).use { vad = env.createSession(File(root,"vad.onnx").absolutePath,it) }
            checkCanceled()
            options(4).use { model = env.createSession(File(root,"model.int8.onnx").absolutePath,it) }
            directory = root
        } catch (error: Throwable) { model?.close(); model = null; vad?.close(); vad = null; throw error }
        return (SystemClock.elapsedRealtime()-start).toDouble()
    }

    private fun calculate(request: PronunciationRequest, run: OrtSession.RunOptions, peak: AtomicLong,
        checkCanceled: () -> Unit): PronunciationAssessment {
        require(request.language == "en-US")
        stage(request.requestId,"准备离线模型（首次使用需要初始化）…")
        val loadMs = ready(checkCanceled)
        checkCanceled()
        val start = SystemClock.elapsedRealtime()
        val wav = PcmWav.encode(request.samples)
        val pcm = ByteBuffer.wrap(wav,44,wav.size-44).order(ByteOrder.LITTLE_ENDIAN)
        val samples = FloatArray(request.samples.size) { pcm.short/32768f }
        val timings = JSONObject().put("initialization",loadMs)
        val root = JSONObject().put("schemaVersion",2).put("assessmentKind","ACOUSTIC_EVIDENCE_EXPERIMENTAL")
            .put("evidenceVersion","acoustic-evidence-v1").put("requestId",request.requestId).put("text",request.expectedText)
            .put("language","en-US").put("audioSha256",PcmWav.fingerprint(wav)).put("modelVersion",manifest.getString("modelVersion"))
            .put("configVersion","acoustic-evidence-v1").put("calibrationStatus","UNCALIBRATED").put("accuracyScore",JSONObject.NULL)
            .put("engineId","local-onnx-int8").put("timestampKind","ESTIMATED_CTC").put("timingsMs",timings)
        val words = JSONArray()
        val targets = ArrayList<IntArray>(); val ipas = ArrayList<String>()
        val weak = mapOf("a" to listOf(listOf("ɐ","ə","eɪ")),"the" to listOf(listOf("ð"),listOf("ə","iː","ɪ")),
            "to" to listOf(listOf("t"),listOf("ə","uː")),"of" to listOf(listOf("ə","ʌ"),listOf("v")))
        val matches = Regex("[A-Za-z0-9]+(?:['’‘][A-Za-z]+)*").findAll(request.expectedText).toList()
        require(matches.isNotEmpty() && matches.size <= 120) { "发音评估支持不超过120词的英文句子。" }
        matches.forEachIndexed { i,match ->
            checkCanceled()
            val text = match.value.lowercase(java.util.Locale.US).replace('’','\'').replace('‘','\'')
            val phones = EspeakNative.phonemize(text)?.replace("ˈ","")?.replace("ˌ","")?.split('_')
                ?.flatMap { it.trim().split(Regex("\\s+")) }?.filter { it.isNotBlank() } ?: emptyList()
            check(phones.isNotEmpty() && phones.all { it in vocab }) { "英语音素不在模型词表中，无法评估。" }
            val alternatives = weak[text]?.takeIf { it.size == phones.size }
            val array = JSONArray()
            phones.forEachIndexed { j,phone ->
                targets.add((listOf(phone)+(alternatives?.get(j) ?: emptyList())).mapNotNull { vocab[it] }.distinct().toIntArray())
                ipas.add(phone)
                array.put(JSONObject().put("phonemeIndex",j).put("ipa",phone).put("score",JSONObject.NULL).put("confidence",0)
                    .put("status","UNKNOWN").put("startMs",JSONObject.NULL).put("endMs",JSONObject.NULL))
            }
            words.put(JSONObject().put("wordIndex",i).put("text",match.value).put("sourceStart",match.range.first)
                .put("sourceEnd",match.range.last+1).put("score",JSONObject.NULL).put("confidence",0).put("status","UNKNOWN")
                .put("phonemes",array))
        }
        require(targets.size <= 400) { "目标句超过400个音素，请练习较短句子。" }
        root.put("words",words)
        stage(request.requestId,"正在本机分析发音…")
        val rms = sqrt(samples.sumOf { it.toDouble()*it }/samples.size)
        var reason: String? = if (rms < .003) "NO_SPEECH" else if (samples.count { abs(it) >= .999 } > samples.size*.02) "CLIPPED_AUDIO" else null
        val speech = if (reason == null) speechRange(samples,run,checkCanceled) else null
        if (speech == null && reason == null) reason = "NO_SPEECH"
        timings.put("preprocessing",(SystemClock.elapsedRealtime()-start).toDouble())
        if (reason == null && speech != null) {
            val offset = max(0,speech.first-1600); val end = min(samples.size,speech.last+1+1600)
            val segment = samples.copyOfRange(offset,end)
            val mean = (segment.sumOf { it.toDouble() }/segment.size).toFloat()
            val variance = (segment.sumOf { val d = (it-mean).toDouble(); d*d }/segment.size).toFloat()
            val scale = sqrt(variance+1e-7f)
            val values = FloatArray(segment.size) { (segment[it]-mean)/scale }
            val mark = SystemClock.elapsedRealtime()
            val logits = OnnxTensor.createTensor(env,FloatBuffer.wrap(values),longArrayOf(1,values.size.toLong())).use { input ->
                checkCanceled()
                model!!.run(mapOf("input_values" to input),run).use { result ->
                    val tensor = result[0] as OnnxTensor
                    val shape = tensor.info.shape
                    require(shape.size == 3 && shape[0] == 1L && shape[2] == vocab.size.toLong())
                    val flat = FloatArray((shape[1]*shape[2]).toInt()); tensor.floatBuffer.get(flat)
                    Array(shape[1].toInt()) { t ->
                        val row = DoubleArray(shape[2].toInt()) { p -> flat[t*shape[2].toInt()+p].toDouble() }
                        require(row.all { it.isFinite() })
                        val max = row.max(); val normalizer = ln(row.sumOf { exp(it-max) })+max
                        DoubleArray(row.size) { row[it]-normalizer }
                    }
                }
            }
            timings.put("inference",(SystemClock.elapsedRealtime()-mark).toDouble())
            val scoringStart = SystemClock.elapsedRealtime()
            val ids = vocab.filterKeys { !it.startsWith('<') && it !in listOf("|"," ") }.values.toIntArray()
            val blank = vocab.getValue("<pad>")
            val scored = try {
                EvidenceScorer(logits,targets,blank,ids,checkCanceled).assess(ipas,vocab.entries.associate { it.value to it.key })
            } catch (failure: IllegalStateException) {
                if (failure.message != "ALIGNMENT_FAILED") throw failure
                reason = "ALIGNMENT_FAILED"
                targets.map { EvidenceScorer.Phone(PronunciationStatus.UNKNOWN,0.0,"ALIGNMENT_FAILED",null,null,null,null,null,null) }
            }
            var occurrence = 0
            for (i in 0 until words.length()) {
                val word = words.getJSONObject(i); val phones = word.getJSONArray("phonemes")
                for (j in 0 until phones.length()) {
                    val phone = phones.getJSONObject(j); val result = scored[occurrence++]
                    phone.put("status",result.status.name).put("confidence",result.confidence).put("reasonCode",result.reason)
                        .put("gopRaw",result.gop ?: JSONObject.NULL).put("frameMargin",result.frameMargin ?: JSONObject.NULL)
                        .put("pathMargin",result.pathMargin ?: JSONObject.NULL).put("competitorIpa",result.competitor?.let { p -> vocab.entries.first { it.value == p }.key } ?: JSONObject.NULL)
                    fun time(frame: Int) = ((offset+frame.toDouble()/logits.size*segment.size)/16).roundToInt()
                    phone.put("startMs",result.startFrame?.let(::time) ?: JSONObject.NULL).put("endMs",result.endFrame?.let(::time) ?: JSONObject.NULL)
                }
                val (state,coverage) = EvidenceScorer.summarize((0 until phones.length()).map { PronunciationStatus.valueOf(phones.getJSONObject(it).getString("status")) })
                word.put("status",state.name).put("coverage",coverage)
                    .put("confidence",(0 until phones.length()).map { phones.getJSONObject(it).getDouble("confidence") }.minOrNull() ?: 0.0)
                    .put("startMs",phones.getJSONObject(0).get("startMs")).put("endMs",phones.getJSONObject(phones.length()-1).get("endMs"))
            }
            if (scored.all { it.reason == "TARGET_INCOMPATIBLE" }) reason = "TARGET_INCOMPATIBLE"
            timings.put("scoring",(SystemClock.elapsedRealtime()-scoringStart).toDouble())
        }
        val allPhones = (0 until words.length()).flatMap { i -> val p = words.getJSONObject(i).getJSONArray("phonemes"); (0 until p.length()).map { p.getJSONObject(it) } }
        val coverage = allPhones.count { it.getString("status") != "UNKNOWN" }.toDouble()/max(1,allPhones.size)
        root.put("coverage",coverage).put("reasonCode",reason ?: JSONObject.NULL)
        timings.put("total",(SystemClock.elapsedRealtime()-start).toDouble())
        val memory = ActivityManager.MemoryInfo(); (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        peak.accumulateAndGet(Debug.getPss().toLong()) { x,y -> max(x,y) }
        root.put("diagnostics",JSONObject().put("device",Build.MODEL).put("physicalRamBytes",memory.totalMem)
            .put("pronunciationSampledPeakPssKb",peak.get()).put("samplingIntervalMs",100).put("abi",Build.SUPPORTED_ABIS.firstOrNull())
            .put("bundleVersion",manifest.getString("bundleVersion")).put("modelSha256",manifest.getJSONObject("files").getJSONObject("model.int8.onnx").getString("sha256")))
        checkCanceled()
        return PronunciationJson.decode(root.toString())
    }

    private fun speechRange(samples: FloatArray, run: OrtSession.RunOptions, checkCanceled: () -> Unit): IntRange? {
        var state = FloatArray(256); var context = FloatArray(64)
        val spans = ArrayList<IntRange>(); var triggered = false; var start = 0; var tempEnd = 0
        OnnxTensor.createTensor(env,16000L).use { rate ->
            for (position in samples.indices step 512) {
                checkCanceled()
                val inputValues = context+FloatArray(512) { samples.getOrElse(position+it) { 0f } }
                val probability = OnnxTensor.createTensor(env,FloatBuffer.wrap(inputValues),longArrayOf(1,576)).use { input ->
                    OnnxTensor.createTensor(env,FloatBuffer.wrap(state),longArrayOf(2,1,128)).use { hidden ->
                        vad!!.run(mapOf("input" to input,"state" to hidden,"sr" to rate),run).use { result ->
                            val p = (result[0] as OnnxTensor).floatBuffer.get()
                            (result[1] as OnnxTensor).floatBuffer.get(state); p
                        }
                    }
                }
                context = inputValues.copyOfRange(512,576)
                if (probability >= .5 && tempEnd != 0) tempEnd = 0
                if (probability >= .5 && !triggered) { triggered = true; start = position; continue }
                if (probability < .35 && triggered) {
                    if (tempEnd == 0) tempEnd = position
                    if (position-tempEnd < 1600) continue
                    if (tempEnd-start > 4000) spans.add(start until tempEnd)
                    triggered = false; tempEnd = 0
                }
            }
        }
        if (triggered && samples.size-start > 4000) spans.add(start until samples.size)
        if (spans.isEmpty()) return null
        return max(0,spans.first().first-480)..min(samples.lastIndex,spans.last().last+480)
    }
    fun release() { if (!closed.get()) worker.execute { model?.close(); model = null; vad?.close(); vad = null } }
    override fun close() {
        if (closed.compareAndSet(false,true)) {
            worker.execute { model?.close(); vad?.close(); sampler.shutdown() }; worker.shutdown()
        }
    }
    private fun hash(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64*1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer,0,n) }
        hex(digest.digest())
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
