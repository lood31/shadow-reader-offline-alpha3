package com.shadowreader.app.asr

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.shadowreader.app.training.TrainingRules
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface SpeechRecognizer {
    suspend fun recognize(file: File): String
}

object WhisperNative {
    init { System.loadLibrary("shadow_whisper") }
    external fun create(path: String): Long
    external fun transcribe(pointer: Long, samples: FloatArray): String?
    external fun cancel(pointer: Long)
    external fun free(pointer: Long)
}

class WhisperModel(context: Context) {
    val file = File(context.filesDir, "models/ggml-base.en-q5_1.bin")
    val ready get() = file.length() == SIZE
    private val client = OkHttpClient.Builder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
    suspend fun download(progress: (Float) -> Unit) = suspendCancellableCoroutine<Unit> { continuation ->
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${java.util.UUID.randomUUID()}.download")
        val call = client.newCall(Request.Builder().url(URL).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                temp.delete()
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("模型下载失败，请重试。", e))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        check(it.isSuccessful) { "模型下载失败（HTTP ${it.code}）。" }
                        val digest = MessageDigest.getInstance("SHA-256")
                        var total = 0L
                        it.body!!.byteStream().use { input -> temp.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (continuation.isActive) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                check(total <= SIZE) { "模型大小异常。" }
                                digest.update(buffer, 0, count)
                                out.write(buffer, 0, count)
                                progress(total.toFloat() / SIZE)
                            }
                        } }
                        if (!continuation.isActive) return
                        check(total == SIZE && digest.digest().joinToString("") { b -> "%02x".format(b) } == SHA256) {
                            "模型校验失败，请重新下载。"
                        }
                        check(temp.renameTo(file)) { "模型保存失败。" }
                        continuation.resume(Unit)
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally { temp.delete() }
            }
        })
    }
    companion object {
        const val SIZE = 59721011L
        const val SHA256 = "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f"
        const val URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.en-q5_1.bin"
    }
}

class OfflineRecognizer(private val model: WhisperModel) : SpeechRecognizer {
    private val serial = Mutex()
    private val worker = Executors.newSingleThreadExecutor()
    override suspend fun recognize(file: File): String = recognizeSamples(PcmDecoder.decode(file))
    suspend fun recognizeSamples(samples: FloatArray): String = serial.withLock {
        check(model.ready) { "请先下载离线识别模型；当前录音已保存。" }
        check(TrainingRules.evaluable(samples)) { "录音静音或过短，未评估。" }
        suspendCancellableCoroutine { continuation ->
            val guard = Any()
            var pointer = 0L
            var canceled = false
            continuation.invokeOnCancellation {
                synchronized(guard) { canceled = true; if (pointer != 0L) WhisperNative.cancel(pointer) }
            }
            worker.execute {
                if (!continuation.isActive) return@execute
                com.shadowreader.app.pronunciation.InferenceGate.lock.lock()
                try {
                    if (!continuation.isActive) return@execute
                    val created = WhisperNative.create(model.file.absolutePath)
                    check(created != 0L) { "离线模型加载失败或内存不足，未评估。" }
                    synchronized(guard) { pointer = created; if (canceled) WhisperNative.cancel(pointer) }
                    if (continuation.isActive) {
                        val text = WhisperNative.transcribe(created, samples)?.trim()
                        check(!text.isNullOrBlank()) { "没有识别到有效英语内容，未评估。" }
                        if (continuation.isActive) continuation.resume(text)
                    }
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error.message ?: "离线识别失败，未评估。", error))
                } finally {
                    synchronized(guard) { if (pointer != 0L) { WhisperNative.free(pointer); pointer = 0 } }
                    com.shadowreader.app.pronunciation.InferenceGate.lock.unlock()
                }
            }
        }
    }
    fun close() { worker.shutdown() }
}

/** Decode the existing AAC/M4A takes, then downmix and resample without sending audio off device. */
object PcmDecoder {
    suspend fun decode(file: File): FloatArray = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("录音没有音频轨道，未评估。")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var inputEnded = false
            var outputEnded = false
            val pcm = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var lastOutput = android.os.SystemClock.elapsedRealtime()
            while (!outputEnded) {
                ensureActive()
                check(android.os.SystemClock.elapsedRealtime() - lastOutput < 15_000) { "录音解码超时，未评估。" }
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val count = extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
                        if (count < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(index, 0, count, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val output = decoder.outputFormat
                    rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = output.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else if (index >= 0) {
                    lastOutput = android.os.SystemClock.elapsedRealtime()
                    if (info.size > 0) {
                        val buffer = decoder.getOutputBuffer(index)!!
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size); buffer.get(bytes); pcm.write(bytes)
                        check(pcm.size() <= 100 * 1024 * 1024) { "录音太长，未评估。" }
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(index, false)
                }
            }
            check(rate > 0 && channels in 1..8) { "录音采样格式不支持。" }
            check(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) { "录音 PCM 格式不支持。" }
            val buffer = ByteBuffer.wrap(pcm.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            val width = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
            val mono = FloatArray(buffer.remaining() / width / channels) {
                var sum = 0f
                repeat(channels) { sum += if (width == 4) buffer.float else buffer.short / 32768f }
                sum / channels
            }
            FloatArray((mono.size.toLong() * 16000 / rate).toInt()) { i ->
                val position = i.toDouble() * rate / 16000
                val left = position.toInt().coerceAtMost(mono.lastIndex)
                val right = (left + 1).coerceAtMost(mono.lastIndex)
                (mono[left] + (mono[right] - mono[left]) * (position - left).toFloat()).coerceIn(-1f, 1f)
            }
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            codec?.release()
            extractor.release()
        }
    }
}
