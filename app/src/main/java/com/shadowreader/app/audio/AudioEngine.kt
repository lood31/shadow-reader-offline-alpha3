package com.shadowreader.app.audio

import android.content.Context
import android.annotation.SuppressLint
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioEngine(private val context: Context) : AudioPlayback {
    var onInterruption: () -> Unit = {}
    private val ready = CompletableDeferred<Boolean>()
    private val tts = TextToSpeech(context) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
    private val player = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), false)
    }
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { if (it < 0) onInterruption() }.build()
    @Volatile private var synthesis: CompletableDeferred<Unit>? = null
    @Volatile private var synthesisId: String? = null
    private var recorder: MediaRecorder? = null
    private var pendingRecording: File? = null
    private var finalRecording: File? = null
    private var focusVersion = 0
    var activeFile: File? = null
        private set

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                if (utteranceId == synthesisId) synthesis?.complete(Unit)
            }
            @Deprecated("TTS legacy callback")
            override fun onError(utteranceId: String?) {
                if (utteranceId == synthesisId) synthesis?.completeExceptionally(
                    IllegalStateException("语音生成失败，请检查系统英语语音包或网络。"))
            }
        })
    }

    private fun requestFocus(): Int {
        check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            "其他应用正在使用音频，请稍后重试。"
        }
        return ++focusVersion
    }

    suspend fun sentenceFile(text: String, british: Boolean): File {
        check(withTimeoutOrNull(15_000) { ready.await() } == true) { "系统语音引擎未就绪，请在系统设置中安装英语 TTS。" }
        val locale = if (british) Locale.UK else Locale.US
        val code = tts.setLanguage(locale)
        check(code != TextToSpeech.LANG_MISSING_DATA && code != TextToSpeech.LANG_NOT_SUPPORTED) {
            "系统缺少英语语音包，请在文字转语音设置中下载。"
        }
        // Prefer an installed offline voice, but permit the user's engine network voice.
        tts.voices?.filter { it.locale == locale && !it.isNetworkConnectionRequired }
            ?.sortedBy { it.name }?.firstOrNull()?.let { tts.voice = it }
        val key = hash("${tts.defaultEngine}|${tts.voice?.name}|$text")
        val directory = File(context.cacheDir, "speech").apply { mkdirs() }
        val file = File(directory, "$key.wav")
        if (file.exists() && file.length() > 44) { file.setLastModified(System.currentTimeMillis()); return file }
        val temp = File(directory, "$key.part.wav")
        val done = CompletableDeferred<Unit>()
        synthesis = done
        val utteranceId = UUID.randomUUID().toString()
        synthesisId = utteranceId
        try {
            tts.setSpeechRate(1f)
            check(tts.synthesizeToFile(text, Bundle(), temp, utteranceId) == TextToSpeech.SUCCESS) {
                "无法生成此句语音，请检查英语 TTS 设置。"
            }
            check(withTimeoutOrNull(60_000) { done.await() } != null) {
                "语音生成超时，请检查英语语音包和网络后重试。"
            }
            check(temp.length() > 44 && temp.renameTo(file)) { "语音缓存保存失败。" }
            // Bound disposable speech cache; recordings live separately in filesDir.
            val files = listOf(directory, File(context.cacheDir, "edge-speech"))
                .flatMap { it.listFiles().orEmpty().toList() }
                .filter { it.extension in listOf("wav", "mp3") && it != file && it != activeFile }
                .sortedBy { it.lastModified() }
            var bytes = files.sumOf { it.length() } + file.length()
            for (old in files) {
                if (bytes <= 100L * 1024 * 1024) break
                bytes -= old.length()
                old.delete()
            }
            return file
        } finally {
            if (!done.isCompleted) { tts.stop(); done.cancel() }
            synthesis = null
            synthesisId = null
            temp.delete()
        }
    }

    override suspend fun play(file: File, speed: Float, onStarted: () -> Unit): Long {
        val focusToken = requestFocus()
        activeFile = file
        try {
            return suspendCancellableCoroutine { continuation ->
                val timing = PlaybackTiming { android.os.SystemClock.elapsedRealtime() }
                val listener = object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED && continuation.isActive) {
                            player.removeListener(this)
                            continuation.resume(timing.finish())
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        player.removeListener(this)
                        if (continuation.isActive) continuation.resumeWithException(
                            IllegalStateException("音频播放失败，请重试。", error))
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        timing.playing(isPlaying)
                        if (isPlaying) onStarted()
                    }
                }
                player.addListener(listener)
                continuation.invokeOnCancellation { player.removeListener(listener); player.stop() }
                player.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
                player.setPlaybackSpeed(speed)
                player.prepare()
                player.play()
            }
        } finally {
            if (activeFile == file) activeFile = null
            if (focusToken == focusVersion) manager.abandonAudioFocusRequest(focus)
        }
    }

    override fun pause() = player.pause()
    override fun resume() = player.play()
    override fun setSpeed(speed: Float) = player.setPlaybackSpeed(speed.coerceIn(.5f, 2f))
    override fun stop() { focusVersion++; player.stop(); tts.stop(); manager.abandonAudioFocusRequest(focus) }

    fun recordingFile(articleId: String, index: Int) =
        File(File(context.filesDir, "recordings/$articleId").apply { mkdirs() }, "$index.m4a")

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission") // UI requests RECORD_AUDIO before invoking this operation.
    fun startRecording(articleId: String, index: Int, attemptId: String) {
        requestFocus()
        val target = recordingFile(articleId, index)
        val temp = File(target.parentFile, "$index.$attemptId.part.m4a")
        val instance = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        try {
            instance.setAudioSource(MediaRecorder.AudioSource.MIC)
            instance.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            instance.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            instance.setAudioSamplingRate(44100)
            instance.setAudioEncodingBitRate(128000)
            instance.setMaxDuration(120_000)
            instance.setOutputFile(temp.absolutePath)
            instance.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onInterruption()
            }
            instance.prepare()
            instance.start()
            recorder = instance
            pendingRecording = temp
            finalRecording = target
        } catch (error: Exception) {
            instance.release()
            temp.delete()
            manager.abandonAudioFocusRequest(focus)
            throw IllegalStateException("录音启动失败，请检查麦克风权限和占用情况。", error)
        }
    }

    fun finishRecording(save: Boolean): File? {
        val instance = recorder ?: return null
        var valid: File? = null
        try {
            instance.stop()
            val temp = pendingRecording
            if (save && temp != null && temp.length() > 100) {
                // The attempt file is immutable until its recognition task finishes.
                val snapshot = File(temp.parentFile, temp.name.replace(".part.m4a", ".attempt.m4a"))
                check(temp.renameTo(snapshot))
                valid = snapshot
            }
        } catch (_: Exception) { /* A very short recording may contain no valid frames. */ }
        finally {
            instance.release()
            recorder = null
            pendingRecording?.delete()
            pendingRecording = null
            finalRecording = null
            manager.abandonAudioFocusRequest(focus)
        }
        return valid
    }

    @Synchronized
    fun keepRecording(snapshot: File, articleId: String, index: Int, createdAt: Long) {
        val target = recordingFile(articleId, index)
        if (target.exists() && target.lastModified() > createdAt) return
        val latest = File(target.parentFile, "${target.name}.new")
        try {
            snapshot.copyTo(latest, overwrite = true)
            java.nio.file.Files.move(latest.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            target.setLastModified(createdAt)
        } finally { latest.delete() }
    }

    fun release() { finishRecording(false); stop(); player.release(); tts.shutdown() }
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
