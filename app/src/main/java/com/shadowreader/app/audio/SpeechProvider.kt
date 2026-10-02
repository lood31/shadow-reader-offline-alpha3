package com.shadowreader.app.audio

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

enum class SpeechVoice(val label: String, val serviceName: String, val british: Boolean = false) {
    ARIA("Aria · 美式", "en-US-AriaNeural"), GUY("Guy · 美式", "en-US-GuyNeural"),
    SONIA("Sonia · 英式", "en-GB-SoniaNeural", true), RYAN("Ryan · 英式", "en-GB-RyanNeural", true),
    SYSTEM_US("系统语音 · 美式", "system-US"), SYSTEM_UK("系统语音 · 英式", "system-UK", true);
    val online get() = this != SYSTEM_US && this != SYSTEM_UK
}
interface SpeechGenerator { suspend fun sentenceFile(text: String, voice: SpeechVoice): File }
interface AudioPlayback {
    suspend fun play(file: File, speed: Float = 1f, onStarted: () -> Unit = {}): Long
    fun pause()
    fun resume()
    fun setSpeed(speed: Float)
    fun stop()
}

class CachedSpeech(private val context: Context, private val system: AudioEngine) : SpeechGenerator {
    private val edge = EdgeSpeech()
    private val lock = Mutex()
    override suspend fun sentenceFile(text: String, voice: SpeechVoice): File = lock.withLock {
        if (!voice.online) return@withLock system.sentenceFile(text, voice.british)
        val directory = File(context.cacheDir, "edge-speech").apply { mkdirs() }
        val hash = MessageDigest.getInstance("SHA-256").digest("${voice.serviceName}|$text".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val target = File(directory, "$hash.mp3")
        if (target.length() > 100) { target.setLastModified(System.currentTimeMillis()); return@withLock target }
        val temp = File(directory, "$hash.part")
        try {
            val data = try { edge.synthesize(text, voice.serviceName) }
                catch (timeout: TimeoutCancellationException) {
                    throw IllegalStateException("联网语音生成超时。请重试或手动选择系统语音。", timeout)
                }
            withContext(Dispatchers.IO) {
                temp.writeBytes(data)
                check(temp.renameTo(target)) { "语音缓存保存失败。" }
                val files = listOf(directory, File(context.cacheDir, "speech")).flatMap { it.listFiles().orEmpty().toList() }
                    .filter { it != target && it != system.activeFile && it.extension in listOf("wav", "mp3") }.sortedBy { it.lastModified() }
                var bytes = files.sumOf { it.length() } + target.length()
                for (old in files) {
                    if (bytes <= 100L * 1024 * 1024) break
                    val length = old.length()
                    if (old.delete()) bytes -= length
                }
            }
            target
        } finally { temp.delete() }
    }
}

/** Unofficial Edge Read Aloud protocol, isolated so service changes cannot affect offline ASR. */
class EdgeSpeech {
    private val client = OkHttpClient.Builder().readTimeout(65, java.util.concurrent.TimeUnit.SECONDS).build()
    suspend fun synthesize(text: String, voice: String): ByteArray = withTimeout(60_000) {
        val result = CompletableDeferred<ByteArray>()
        val output = ByteArrayOutputStream()
        val token = "6A5AA1D4EAFF4E9FB37E23D68491D6F4" // Public service client token, not a user credential.
        val seconds = System.currentTimeMillis() / 1000 + 11644473600L
        val ticks = (seconds - seconds % 300) * 10_000_000
        val gec = MessageDigest.getInstance("SHA-256").digest("$ticks$token".toByteArray())
            .joinToString("") { "%02X".format(it) }
        val id = UUID.randomUUID().toString().replace("-", "")
        val url = "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=$token&ConnectionId=$id&Sec-MS-GEC=$gec&Sec-MS-GEC-Version=1-143.0.3650.75"
        val request = Request.Builder().url(url)
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
            .header("Cookie", "muid=${UUID.randomUUID().toString().replace("-", "").uppercase()};")
            .build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val timestamp = ZonedDateTime.now(ZoneOffset.UTC).format(
                    DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US))
                webSocket.send("X-Timestamp:$timestamp\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                    "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}")
                val escaped = text.filter { it >= ' ' || it == '\n' }.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                webSocket.send("X-RequestId:$id\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${timestamp}Z\r\nPath:ssml\r\n\r\n" +
                    "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'><voice name='$voice'><prosody pitch='+0Hz' rate='+0%' volume='+0%'>$escaped</prosody></voice></speak>")
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size < 2) return
                val headerLength = ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255)
                if (headerLength + 2 > bytes.size) return
                val headers = bytes.substring(2, headerLength + 2).utf8()
                if (headers.contains("Path:audio") && output.size() < 4 * 1024 * 1024)
                    output.write(bytes.substring(headerLength + 2).toByteArray())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    if (output.size() > 100) result.complete(output.toByteArray())
                    else result.completeExceptionally(IllegalStateException("联网语音生成失败，未收到音频。请重试或手动选择系统语音。"))
                    webSocket.close(1000, "done")
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                result.completeExceptionally(IllegalStateException("联网语音生成失败${response?.code?.let { "（HTTP $it）" } ?: ""}。请重试或手动选择系统语音。", t))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!result.isCompleted) result.completeExceptionally(IllegalStateException("联网语音连接已关闭，请重试或手动选择系统语音。"))
            }
        })
        try { result.await() } finally { socket.cancel() }
    }
}
