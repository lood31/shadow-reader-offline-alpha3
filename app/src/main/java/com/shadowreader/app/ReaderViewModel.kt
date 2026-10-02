package com.shadowreader.app

import android.app.Application
import android.util.Patterns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.shadowreader.app.audio.AudioEngine
import com.shadowreader.app.audio.CachedSpeech
import com.shadowreader.app.audio.SpeechVoice
import com.shadowreader.app.audio.PlaybackSettings
import com.shadowreader.app.asr.*
import com.shadowreader.app.training.*
import com.shadowreader.app.data.*
import com.shadowreader.app.pronunciation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.UUID
import java.time.LocalDate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class Page { HOME, IMPORT, ARTICLE, TRAINER, REPORT }
enum class Phase { IDLE, PREPARING, PLAYING, PAUSED, GAP, RECORDING, OWN_AUDIO }
data class ReaderState(
    val page: Page = Page.HOME,
    val article: Article? = null,
    val sentences: List<Sentence> = emptyList(),
    val index: Int = 0,
    val busy: Boolean = false,
    val importTitle: String = "",
    val importContent: String = "",
    val urlMode: Boolean = true,
    val phase: Phase = Phase.IDLE,
    val speed: Float = .9f,
    val repeats: Int = 1,
    val repetition: Int = 0,
    val gapSeconds: Int = 3,
    val gapRemaining: Int = 0,
    val autoNext: Boolean = false,
    val blind: Boolean = false,
    val british: Boolean = false,
    val hasRecording: Boolean = false,
    val notice: String? = null,
    val voice: SpeechVoice = SpeechVoice.ARIA,
    val gapEnabled: Boolean = true,
    val gapRatio: Float = 1f,
    val gapBuffer: Int = 1,
    val feedbackTraining: Boolean = true,
    val feedbackAutoNext: Boolean = true,
    val analyzing: Boolean = false,
    val feedback: Feedback? = null,
    val recognized: String = "",
    val evaluation: AttemptResult? = null,
    val evaluationMessage: String? = null,
    val consecutiveDifferences: Int = 0,
    val difficult: Boolean = false,
    val modelReady: Boolean = false,
    val downloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val report: TrainingReport? = null,
    val reviewMode: Boolean = false,
    val advancePending: Boolean = false,
    val pronunciationEnabled: Boolean = false,
    val pronunciationUrl: String = "",
    val pronunciation: PronunciationAssessment? = null,
    val pronunciationBusy: Boolean = false,
    val pronunciationError: String? = null,
    val pronunciationStage: String = "",
)

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val database = (application as ShadowApplication).database
    private val dao = database.articles()
    private val importer = ArticleImporter()
    private val audio = AudioEngine(application)
    private val speech = CachedSpeech(application, audio)
    private val whisperModel = WhisperModel(application)
    private val recognizer = OfflineRecognizer(whisperModel)
    private val training = database.training()
    private val prefs = application.getSharedPreferences("trainer", 0)
    private val localEngine = lazy {
        LocalGopPronunciationEngine(application) { id,stage ->
            if (latestAttempt?.id == id && state.value.pronunciationBusy)
                mutable.update { it.copy(pronunciationStage = stage) }
        }
    }
    private val mutable = MutableStateFlow(ReaderState(
        speed = prefs.getFloat("speed", .9f), repeats = prefs.getInt("repeats", 1),
        gapSeconds = prefs.getInt("gap", 3), autoNext = prefs.getBoolean("auto", false),
        british = prefs.getBoolean("british", false), blind = prefs.getBoolean("blind", false),
        voice = runCatching { SpeechVoice.valueOf(prefs.getString("voice", null) ?: "") }.getOrDefault(
            if (prefs.getBoolean("british", false)) SpeechVoice.SONIA else SpeechVoice.ARIA),
        gapEnabled = prefs.getBoolean("gapEnabled", true), gapRatio = prefs.getFloat("gapRatio", 1f),
        gapBuffer = prefs.getInt("gapBuffer", 1), feedbackAutoNext = prefs.getBoolean("feedbackAuto", true),
        feedbackTraining = prefs.getBoolean("feedbackTraining", true), modelReady = whisperModel.ready,
        pronunciationEnabled = if (BuildConfig.OFFLINE_PRONUNCIATION) prefs.getBoolean("offlinePronunciationEnabled",true)
            else prefs.getBoolean("pronunciationEnabled", false),
        pronunciationUrl = prefs.getString("pronunciationUrl", "") ?: ""))
    val state = mutable.asStateFlow()
    val library = dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private var playback: Job? = null
    private var load: Job? = null
    private var playbackVersion = 0
    private var pausedPhase = Phase.PLAYING
    private var paused = false
    private var prefetch: Job? = null
    private var recognition: Job? = null
    private var advancement: Job? = null
    private var download: Job? = null
    private var session: TrainingSession? = null
    private var recordingAttempt: Attempt? = null
    private var recordingSentence: String = ""
    private var latestAttempt: Attempt? = null
    private var reviewBeforeAttempt: SentenceReview? = null
    private var streakBeforeAttempt = 0
    private val persistenceJobs = mutableListOf<Job>()
    private var gapSkip = false
    private val reviewLock = Mutex()
    private var reviewQueue: List<ReviewSentence> = emptyList()
    private var reviewCursor = 0
    private val day = MutableStateFlow(LocalDate.now().toEpochDay())
    @OptIn(ExperimentalCoroutinesApi::class)
    val dueCount = day.flatMapLatest { training.observeDue(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    init {
        audio.onInterruption = { viewModelScope.launch { stop() } }
        val startupCutoff = System.currentTimeMillis()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                File(application.filesDir, "recordings").listFiles()?.filter { it.isDirectory }?.forEach { directory ->
                    directory.listFiles()?.filter { it.lastModified() < startupCutoff &&
                        (it.name.endsWith(".attempt.m4a") || it.name.endsWith(".part.m4a") || it.name.endsWith(".m4a.new")) }
                        ?.forEach { it.delete() }
                }
                File(application.filesDir, "models").listFiles()?.filter { it.extension == "download" && it.lastModified() < startupCutoff }
                    ?.forEach { it.delete() }
            }
        }
    }
    fun refreshDay() { day.value = LocalDate.now().toEpochDay() }

    fun clearNotice() { mutable.update { it.copy(notice = null) } }
    fun notify(message: String) { mutable.update { it.copy(notice = message) } }
    fun openImport() { stop(); mutable.update { it.copy(page = Page.IMPORT) } }
    fun editImport(title: String? = null, content: String? = null, url: Boolean? = null) {
        mutable.update { it.copy(importTitle = title ?: it.importTitle,
            importContent = content ?: it.importContent, urlMode = url ?: it.urlMode) }
    }

    fun receiveShare(text: String) {
        stop()
        load?.cancel()
        val match = Patterns.WEB_URL.matcher(text)
        val url = if (match.find()) match.group().trimEnd('.', ',', ')', ']') else null
        mutable.update { it.copy(page = Page.IMPORT, busy = false, importTitle = "",
            importContent = url?.takeIf { value -> value.startsWith("https://") } ?: text,
            urlMode = url?.startsWith("https://") == true) }
    }

    fun importArticle() {
        if (state.value.busy) return
        val draft = state.value
        mutable.update { it.copy(busy = true) }
        load = viewModelScope.launch {
            try {
                val imported = if (draft.urlMode) importer.fromUrl(draft.importContent)
                    else importer.fromText(draft.importTitle, draft.importContent)
                val texts = withContext(Dispatchers.Default) { SentenceSplitter.split(imported.body) }
                require(texts.isNotEmpty()) { "正文中没有可练习的句子。" }
                val article = Article(UUID.randomUUID().toString(), imported.title, imported.source,
                    imported.body, texts.size)
                val sentences = texts.mapIndexed { i, text -> Sentence(article.id, i, text) }
                ensureActive()
                database.withTransaction { dao.insert(article); dao.insert(sentences) }
                mutable.update { it.copy(page = Page.ARTICLE, article = article, sentences = sentences,
                    index = 0, busy = false, importContent = "", importTitle = "", hasRecording = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(busy = false, notice = error.message ?: "导入失败。") } }
        }
    }

    fun openArticle(article: Article) {
        if (state.value.busy) return
        stop()
        mutable.update { it.copy(busy = true) }
        load = viewModelScope.launch {
            try {
                val sentences = dao.sentences(article.id)
                check(sentences.isNotEmpty()) { "文章没有句子，请重新导入。" }
                val position = article.lastIndex.coerceIn(sentences.indices)
                mutable.update { it.copy(page = Page.ARTICLE, article = article, sentences = sentences,
                    index = position, busy = false, hasRecording = recordingExists(article.id, position)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(busy = false, notice = error.message) } }
        }
    }

    fun startTrainer() {
        session = TrainingSession(UUID.randomUUID().toString(), System.currentTimeMillis())
        reviewQueue = emptyList()
        latestAttempt = null
        mutable.update { it.copy(page = Page.TRAINER, reviewMode = false, report = null,
            feedback = null, evaluation = null, recognized = "", evaluationMessage = null, consecutiveDifferences = 0,
            pronunciation = null, pronunciationError = null) }
        refreshReview()
    }
    fun back() {
        if (state.value.page == Page.TRAINER) { endTraining(); return }
        stop()
        load?.cancel()
        mutable.update { it.copy(page = if (it.page == Page.TRAINER) Page.ARTICLE else Page.HOME, busy = false) }
    }

    fun move(delta: Int) {
        if (state.value.reviewMode) { if (delta > 0) skipSentence() else notify("复习按到期队列进行。"); return }
        stop()
        select((state.value.index + delta).coerceIn(0, (state.value.sentences.size - 1).coerceAtLeast(0)))
    }

    private fun select(index: Int) {
        val article = state.value.article ?: return
        latestAttempt = null
        mutable.update { it.copy(index = index, hasRecording = recordingExists(article.id, index),
            feedback = null, recognized = "", evaluation = null, evaluationMessage = null,
            consecutiveDifferences = 0, difficult = false, pronunciation = null, pronunciationError = null) }
        viewModelScope.launch { dao.progress(article.id, index, 0) }
        refreshReview()
    }

    private fun recordingExists(id: String, index: Int) = audio.recordingFile(id, index).let { it.exists() && it.length() > 100 }

    fun togglePlayback() {
        when (state.value.phase) {
            Phase.PLAYING, Phase.GAP -> {
                pausedPhase = state.value.phase
                paused = true
                audio.pause()
                mutable.update { it.copy(phase = Phase.PAUSED) }
            }
            Phase.PAUSED -> {
                paused = false
                mutable.update { it.copy(phase = pausedPhase) }
                if (pausedPhase == Phase.PLAYING) audio.resume()
            }
            Phase.PREPARING, Phase.OWN_AUDIO -> stop()
            Phase.RECORDING -> Unit
            Phase.IDLE -> playSequence()
        }
    }

    private fun playSequence() {
        val previous = playback
        stop()
        val version = playbackVersion
        playback = viewModelScope.launch {
            previous?.join()
            if (version != playbackVersion) return@launch
            try {
                do {
                    val settings = state.value
                    val article = settings.article ?: break
                    val sentence = settings.sentences.getOrNull(settings.index) ?: break
                    mutable.update { it.copy(phase = Phase.PREPARING, repetition = 0) }
                    val file = speech.sentenceFile(sentence.text, settings.voice)
                    prefetch?.cancel()
                    prefetch = viewModelScope.launch {
                        settings.sentences.getOrNull(settings.index + 1)?.let { next ->
                            runCatching { speech.sentenceFile(next.text, settings.voice) }
                        }
                    }
                    repeat(if (settings.feedbackTraining) 1 else settings.repeats) { repetition ->
                        mutable.update { it.copy(phase = Phase.PLAYING, repetition = repetition + 1) }
                        val playedMillis = audio.play(file, state.value.speed)
                        mutable.update { it.copy(phase = Phase.GAP) }
                        // Count only active gap time; pausing also pauses the countdown.
                        gapSkip = false
                        var remaining = kotlin.math.ceil(PlaybackSettings.gapMillis(playedMillis, state.value.gapRatio,
                            state.value.gapBuffer, state.value.gapEnabled && !state.value.feedbackTraining) / 100.0).toInt()
                        while (remaining > 0 && !gapSkip) {
                            mutable.update { it.copy(gapRemaining = (remaining + 9) / 10) }
                            delay(100)
                            if (!paused) remaining--
                        }
                        while (paused) delay(100)
                    }
                    val completed = maxOf(article.completedCount, settings.index + 1)
                    dao.progress(article.id, settings.index, completed)
                    mutable.update { it.copy(article = it.article?.copy(completedCount = completed)) }
                    if (settings.feedbackTraining || !settings.autoNext || settings.index == settings.sentences.lastIndex) break
                    select(settings.index + 1)
                } while (currentCoroutineContext().isActive)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { notify(error.message ?: "播放失败。") }
            finally {
                if (version == playbackVersion) {
                    paused = false
                    mutable.update { it.copy(phase = Phase.IDLE, gapRemaining = 0) }
                }
            }
        }
    }

    fun toggleRecording() {
        if (state.value.phase == Phase.RECORDING) {
            val saved = audio.finishRecording(true)
            val s = state.value
            val attempt = recordingAttempt
            recordingAttempt = null
            mutable.update { it.copy(phase = Phase.IDLE,
                hasRecording = s.article?.let { a -> recordingExists(a.id, s.index) } ?: false,
                notice = if (saved == null) "录音太短或保存失败，未评估。" else null) }
            if (saved != null) markRecordedPractice(s)
            if (attempt != null) evaluate(attempt, recordingSentence, saved, s.feedbackTraining)
            return
        }
        if (state.value.analyzing) { notify("请等待识别完成，或先取消识别。"); return }
        stop()
        val s = state.value
        val article = s.article ?: return
        try {
            val currentSession = session ?: return
            val attempt = Attempt(UUID.randomUUID().toString(), currentSession.id, article.id, s.index, System.currentTimeMillis())
            recordingSentence = s.sentences[s.index].text
            audio.startRecording(article.id, s.index, attempt.id)
            recordingAttempt = attempt
            mutable.update { it.copy(phase = Phase.RECORDING, feedback = null, recognized = "",
                evaluation = null, evaluationMessage = null, pronunciation = null, pronunciationError = null) }
        } catch (error: Exception) { notify(error.message ?: "无法启动录音。") }
    }

    fun playOriginal() {
        val previous = playback
        stop()
        val version = playbackVersion
        val s = state.value
        val sentence = s.sentences.getOrNull(s.index) ?: return
        playback = viewModelScope.launch {
            previous?.join()
            if (version != playbackVersion) return@launch
            try {
                mutable.update { it.copy(phase = Phase.PREPARING) }
                val file = speech.sentenceFile(sentence.text, s.voice)
                prefetch = viewModelScope.launch {
                    s.sentences.getOrNull(s.index + 1)?.let { next -> runCatching { speech.sentenceFile(next.text, s.voice) } }
                }
                mutable.update { it.copy(phase = Phase.PLAYING, repetition = 0) }
                audio.play(file, s.speed)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { notify(error.message ?: "播放失败。") }
            finally { if (version == playbackVersion) mutable.update { it.copy(phase = Phase.IDLE) } }
        }
    }

    fun playRecording() {
        val previous = playback
        stop()
        val version = playbackVersion
        val s = state.value
        val article = s.article ?: return
        val file = audio.recordingFile(article.id, s.index)
        if (!file.exists()) return
        playback = viewModelScope.launch {
            previous?.join()
            if (version != playbackVersion) return@launch
            try { mutable.update { it.copy(phase = Phase.OWN_AUDIO) }; audio.play(file) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { notify(error.message ?: "录音播放失败。") }
            finally { if (version == playbackVersion) mutable.update { it.copy(phase = Phase.IDLE) } }
        }
    }

    fun playPronunciationSegment(requestId: String, startMs: Int, endMs: Int) {
        val assessed = state.value.pronunciation?.takeIf { it.requestId == requestId } ?: return
        if (state.value.analyzing) return
        val article = state.value.article ?: return
        val source = audio.recordingFile(article.id,state.value.index)
        stop(); val token = playbackVersion
        playback = viewModelScope.launch {
            val clip = File(getApplication<Application>().cacheDir,"phoneme-${UUID.randomUUID()}.wav")
            try {
                val samples = PcmDecoder.decode(source)
                check(PcmWav.fingerprint(PcmWav.encode(samples)) == assessed.audioSha256) { "录音已变化，请重新评估。" }
                if (token != playbackVersion || state.value.pronunciation?.requestId != requestId) return@launch
                val desiredStart = ((startMs-80).coerceAtLeast(0)*16).coerceAtMost(samples.size-8000)
                val desiredEnd = ((endMs+80)*16).coerceIn(desiredStart+8000,samples.size)
                withContext(Dispatchers.IO) { clip.writeBytes(PcmWav.encode(samples.copyOfRange(desiredStart,desiredEnd))) }
                mutable.update { it.copy(phase = Phase.OWN_AUDIO) }
                audio.play(clip)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { notify(error.message ?: "片段回放失败。") }
            finally { clip.delete(); if (token == playbackVersion) mutable.update { it.copy(phase = Phase.IDLE) } }
        }
    }

    fun exportPronunciation(requestId: String, uri: android.net.Uri) {
        val assessed = state.value.pronunciation?.takeIf { it.requestId == requestId }
            ?: return notify("已切换录音，请重新导出当前结果。")
        val article = state.value.article ?: return
        val source = audio.recordingFile(article.id,state.value.index)
        viewModelScope.launch {
            try {
                val samples = PcmDecoder.decode(source)
                val wav = PcmWav.encode(samples)
                check(PcmWav.fingerprint(wav) == assessed.audioSha256) { "录音已变化，请重新评估后导出。" }
                withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    val output = app.contentResolver.openOutputStream(uri,"wt") ?: error("无法写入所选位置。")
                    java.util.zip.ZipOutputStream(output.buffered()).use { zip ->
                        fun entry(name: String, data: ByteArray) {
                            zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(data); zip.closeEntry()
                        }
                        entry("audio.wav",wav)
                        entry("target.txt",assessed.text.toByteArray(Charsets.UTF_8))
                        entry("assessment.json",assessed.json.toByteArray(Charsets.UTF_8))
                        entry("README.txt","这是实验声学证据，不代表人类发音准确率。时间定位为估计值；模型版本、耗时与设备信息在assessment.json中。".toByteArray(Charsets.UTF_8))
                    }
                }
                notify("实验数据已导出到所选位置。")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { notify(error.message ?: "实验数据导出失败。") }
        }
    }

    fun stop() {
        playbackVersion++
        playback?.cancel()
        playback = null
        prefetch?.cancel(); prefetch = null
        advancement?.cancel(); advancement = null
        recognition?.cancel()
        if (state.value.analyzing) mutable.update { it.copy(evaluation = AttemptResult.UNEVALUATED,
            evaluationMessage = "识别已取消，本次未评估。") }
        paused = false
        if (state.value.phase == Phase.RECORDING) {
            val s = state.value
            val saved = audio.finishRecording(true)
            val attempt = recordingAttempt
            recordingAttempt = null
            mutable.update { it.copy(hasRecording = s.article?.let { a -> recordingExists(a.id, s.index) } ?: false) }
            if (saved != null) markRecordedPractice(s)
            else notify("录音太短或保存失败，上一遍录音仍保留。")
            if (attempt != null) evaluate(attempt, recordingSentence, saved, false)
        }
        audio.stop()
        mutable.update { it.copy(phase = Phase.IDLE, gapRemaining = 0, repetition = 0, analyzing = false, advancePending = false,
            pronunciationBusy = false) }
    }

    private fun markRecordedPractice(snapshot: ReaderState) {
        val article = snapshot.article ?: return
        val completed = maxOf(article.completedCount, snapshot.index + 1)
        mutable.update { it.copy(article = it.article?.copy(completedCount = completed)) }
        viewModelScope.launch { dao.progress(article.id, snapshot.index, completed) }
    }

    fun settings(speed: Float = state.value.speed, repeats: Int = state.value.repeats,
        gap: Int = state.value.gapSeconds, auto: Boolean = state.value.autoNext,
        blind: Boolean = state.value.blind, british: Boolean = state.value.british) {
        val safeSpeed = PlaybackSettings.speed(speed)
        audio.setSpeed(safeSpeed)
        mutable.update { it.copy(speed = safeSpeed, repeats = repeats, gapSeconds = gap,
            autoNext = auto, blind = blind, british = british) }
        prefs.edit().putFloat("speed", safeSpeed).putInt("repeats", repeats).putInt("gap", gap)
            .putBoolean("auto", auto).putBoolean("blind", blind).putBoolean("british", british).apply()
    }

    fun voice(voice: SpeechVoice) {
        stop()
        mutable.update { it.copy(voice = voice, british = voice.british) }
        prefs.edit().putString("voice", voice.name).putBoolean("british", voice.british).apply()
    }

    fun gap(enabled: Boolean = state.value.gapEnabled, ratio: Float = state.value.gapRatio,
        buffer: Int = state.value.gapBuffer) {
        mutable.update { it.copy(gapEnabled = enabled, gapRatio = ratio, gapBuffer = buffer.coerceIn(0, 5)) }
        if (!enabled && (state.value.phase == Phase.GAP || state.value.phase == Phase.PAUSED && pausedPhase == Phase.GAP)) skipGap()
        prefs.edit().putBoolean("gapEnabled", enabled).putFloat("gapRatio", ratio)
            .putInt("gapBuffer", buffer.coerceIn(0, 5)).apply()
    }
    fun skipGap() {
        gapSkip = true
        if (state.value.phase == Phase.PAUSED && pausedPhase == Phase.GAP) {
            paused = false
            mutable.update { it.copy(phase = Phase.GAP) }
        }
    }
    fun trainingMode(enabled: Boolean) {
        stop()
        mutable.update { it.copy(feedbackTraining = enabled) }
        prefs.edit().putBoolean("feedbackTraining", enabled).apply()
    }
    fun feedbackAuto(enabled: Boolean) {
        if (!enabled) advancement?.cancel()
        mutable.update { it.copy(feedbackAutoNext = enabled, advancePending = if (enabled) it.advancePending else false) }
        prefs.edit().putBoolean("feedbackAuto", enabled).apply()
    }

    fun downloadModel() {
        if (state.value.downloading || whisperModel.ready) return
        mutable.update { it.copy(downloading = true, downloadProgress = 0f) }
        download = viewModelScope.launch {
            try {
                whisperModel.download { value -> mutable.update { it.copy(downloadProgress = value) } }
                mutable.update { it.copy(modelReady = true, notice = "离线识别模型下载并校验完成。") }
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { notify(error.message ?: "模型下载失败，请重试。") }
            finally { mutable.update { it.copy(downloading = false, modelReady = whisperModel.ready) } }
        }
    }
    fun cancelDownload() { download?.cancel() }
    fun cancelRecognition() { stop(); notify("评估已取消，录音仍保留。") }

    fun pronunciationSettings(url: String, enabled: Boolean) {
        try {
            if (BuildConfig.OFFLINE_PRONUNCIATION) {
                stop(); prefs.edit().putBoolean("offlinePronunciationEnabled",enabled).apply()
                mutable.update { it.copy(pronunciationEnabled = enabled,pronunciation = null,pronunciationError = null) }
                return
            }
            val safeUrl = if (url.isBlank() && !enabled) "" else RemoteGopPronunciationEngine.validateEndpoint(url)
            stop()
            prefs.edit().putString("pronunciationUrl", safeUrl).putBoolean("pronunciationEnabled", enabled).apply()
            mutable.update { it.copy(pronunciationUrl = safeUrl, pronunciationEnabled = enabled,
                pronunciation = null, pronunciationError = null) }
        } catch (error: Exception) { notify(error.message ?: "评分服务地址无效。") }
    }

    private fun currentAttempt(attempt: Attempt, token: Int): Boolean = token == playbackVersion &&
        state.value.article?.id == attempt.articleId && state.value.index == attempt.position &&
        session?.id == attempt.sessionId && latestAttempt?.id == attempt.id

    private suspend fun assessPronunciation(attempt: Attempt, expected: String, samples: FloatArray, url: String, token: Int) {
        coroutineScope {
                var persisted = false
                try {
                    if (currentAttempt(attempt, token)) mutable.update { it.copy(pronunciationBusy = true, pronunciationError = null) }
                    val engine: PronunciationEngine = if (BuildConfig.OFFLINE_PRONUNCIATION) localEngine.value else RemoteGopPronunciationEngine(url)
                    val assessed = engine.assess(PronunciationRequest(attempt.id, samples, expected))
                    ensureActive()
                    training.pronunciation(attempt.id, assessed.json)
                    persisted = true
                    if (currentAttempt(attempt, token)) {
                        latestAttempt = latestAttempt?.copy(pronunciationJson = assessed.json)
                        mutable.update { it.copy(pronunciation = assessed) }
                    }
                } catch (cancelled: CancellationException) {
                    if (!persisted) withContext(NonCancellable) {
                        training.pronunciation(attempt.id, org.json.JSONObject().put("state", "CANCELLED").toString())
                    }
                    throw cancelled
                } catch (error: Exception) {
                    training.pronunciation(attempt.id, org.json.JSONObject().put("state", "ERROR")
                        .put("message", error.message ?: "评分失败").toString())
                    if (currentAttempt(attempt, token)) mutable.update { it.copy(pronunciationError = error.message ?: "评分失败，录音仍保留。") }
                } finally {
                    if (currentAttempt(attempt, token)) mutable.update { it.copy(pronunciationBusy = false) }
                }
            ensureActive()
                if (!whisperModel.ready) {
                    if (currentAttempt(attempt, token)) mutable.update { it.copy(evaluation = AttemptResult.UNEVALUATED,
                        evaluationMessage = "辅助 Whisper 未下载；不影响发音评估。") }
                    return@coroutineScope
                }
                try {
                    val text = recognizer.recognizeSamples(samples)
                    check(WordAligner.normalize(text).isNotEmpty()) { "辅助识别没有有效转录。" }
                    val feedback = WordAligner.compare(expected, text)
                    val result = if (feedback.consistent) "CONSISTENT" else "DIFFERENT"
                    ensureActive()
                    training.content(attempt.id, text, differenceJson(feedback), result, feedback.errorRatio)
                    if (currentAttempt(attempt, token)) {
                        latestAttempt = latestAttempt?.copy(recognized = text, differences = differenceJson(feedback),
                            result = result, errorRatio = feedback.errorRatio)
                        mutable.update { it.copy(recognized = text, feedback = feedback,
                            evaluation = AttemptResult.valueOf(result), evaluationMessage = "辅助内容对比，不决定发音分数或掌握状态。") }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (currentAttempt(attempt, token)) mutable.update { it.copy(evaluation = AttemptResult.UNEVALUATED,
                        evaluationMessage = error.message ?: "辅助识别失败；发音评估独立运行。") }
                }
        }
    }

    private fun differenceJson(feedback: Feedback): String = org.json.JSONArray().apply {
        feedback.words.forEach { word -> put(org.json.JSONObject().apply {
            put("kind", word.kind.name); put("expected", word.expected); put("heard", word.heard)
        }) }
    }.toString()

    private fun evaluate(attempt: Attempt, expected: String, file: File?, assess: Boolean) {
        val token = playbackVersion
        val capturedSession = session ?: return
        val usePronunciation = assess && state.value.pronunciationEnabled
        val capturedUrl = state.value.pronunciationUrl
        val event = attempt.copy(feedbackMode = if (!assess) "LISTEN_ONLY" else if (usePronunciation) "PRONUNCIATION" else "CONTENT")
        latestAttempt = event
        mutable.update { it.copy(analyzing = file != null, pronunciation = null, pronunciationError = null) }
        recognition = viewModelScope.launch {
            var result = event
            try {
                var samples: FloatArray? = null
                // Even canceled/interrupted attempts belong in the final per-sentence report.
                withContext(NonCancellable) {
                    database.withTransaction { training.insert(capturedSession); training.insert(event) }
                    if (file != null) {
                        samples = PcmDecoder.decode(file)
                        check(TrainingRules.evaluable(samples!!)) { "录音静音或过短，未评估；上一遍有效录音仍保留。" }
                        withContext(Dispatchers.IO) { audio.keepRecording(file, attempt.articleId, attempt.position, attempt.createdAt) }
                        if (state.value.article?.id == attempt.articleId && state.value.index == attempt.position)
                            mutable.update { it.copy(hasRecording = true) }
                    }
                }
                ensureActive()
                val reason = when {
                    file == null -> "录音太短或保存失败，未评估。"
                    !assess -> "录音已保存，本次未评估。"
                    !whisperModel.ready && !usePronunciation -> "录音已保存；下载离线模型后可主动识别。"
                    else -> null
                }
                if (reason != null) {
                    if (usePronunciation) training.pronunciation(event.id, org.json.JSONObject()
                        .put("state", "UNEVALUATED").put("message", reason).toString())
                    if (token == playbackVersion) {
                        latestAttempt = result
                        mutable.update { it.copy(evaluation = AttemptResult.UNEVALUATED, evaluationMessage = reason,
                            pronunciationError = if (usePronunciation) reason else null) }
                    }
                    return@launch
                }
                if (usePronunciation) {
                    assessPronunciation(event, expected, samples!!, capturedUrl, token)
                    return@launch
                }
                val text = recognizer.recognizeSamples(samples!!)
                val feedback = WordAligner.compare(expected, text)
                check(WordAligner.normalize(text).isNotEmpty()) { "没有识别到英语词语，未评估。" }
                result = event.copy(recognized = text, differences = differenceJson(feedback),
                    result = if (feedback.consistent) "CONSISTENT" else "DIFFERENT", errorRatio = feedback.errorRatio)
                ensureActive()
                val previousReview = training.review(attempt.articleId, attempt.position)
                database.withTransaction { training.update(result); updateReview(result, capturedSession.reviewMode) }
                if (token == playbackVersion && state.value.article?.id == attempt.articleId && state.value.index == attempt.position && session?.id == attempt.sessionId) {
                    latestAttempt = result
                    reviewBeforeAttempt = previousReview
                    streakBeforeAttempt = state.value.consecutiveDifferences
                    val consecutive = if (feedback.consistent) 0 else state.value.consecutiveDifferences + 1
                    mutable.update { it.copy(feedback = feedback, recognized = text,
                        evaluation = AttemptResult.valueOf(result.result), evaluationMessage = null,
                        consecutiveDifferences = consecutive) }
                    refreshReview()
                    if (feedback.consistent && state.value.feedbackTraining && state.value.feedbackAutoNext) {
                        mutable.update { it.copy(advancePending = true) }
                        advancement = viewModelScope.launch {
                            delay(2000)
                            if (token == playbackVersion && state.value.page == Page.TRAINER && state.value.feedbackAutoNext) advanceSentence()
                        }
                    }
                }
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) {
                if (usePronunciation) training.pronunciation(event.id, org.json.JSONObject()
                    .put("state", "ERROR").put("message", error.message ?: "录音处理失败").toString())
                if (token == playbackVersion) {
                    latestAttempt = result
                    mutable.update { it.copy(evaluation = AttemptResult.UNEVALUATED,
                        evaluationMessage = error.message ?: "识别失败，未评估。",
                        pronunciationError = if (usePronunciation) error.message ?: "录音处理失败，未评分。" else null) }
                }
            } finally {
                file?.delete()
                if (token == playbackVersion) mutable.update { it.copy(analyzing = false, pronunciationBusy = false) }
            }
        }
        recognition?.let { persistenceJobs += it }
    }

    private suspend fun updateReview(attempt: Attempt, reviewMode: Boolean) {
        val old = training.review(attempt.articleId, attempt.position) ?: SentenceReview(attempt.articleId, attempt.position)
        val today = LocalDate.now()
        when (attempt.result) {
            "DIFFERENT" -> training.save(old.copy(streak = old.streak + 1,
                difficult = old.difficult || old.streak + 1 >= 2, round = 0, dueDay = today.plusDays(1).toEpochDay()))
            "CONSISTENT" -> {
                val currentReview = reviewMode && old.dueDay != null && old.dueDay <= today.toEpochDay()
                val next = if (currentReview) TrainingRules.nextReview(today, old.round, true) else old.round to old.dueDay?.let(LocalDate::ofEpochDay)
                training.save(old.copy(streak = 0, round = next.first, dueDay = next.second?.toEpochDay()))
            }
        }
    }

    private fun refreshReview() {
        val articleId = state.value.article?.id ?: return
        val index = state.value.index
        viewModelScope.launch {
            val review = training.review(articleId, index)
            if (state.value.article?.id == articleId && state.value.index == index)
                mutable.update { it.copy(difficult = review?.difficult == true) }
        }
    }
    fun markDifficult() {
        val article = state.value.article ?: return
        val index = state.value.index
        viewModelScope.launch {
            reviewLock.withLock {
                val old = training.review(article.id, index) ?: SentenceReview(article.id, index)
                training.save(old.copy(difficult = true, dueDay = old.dueDay ?: LocalDate.now().plusDays(1).toEpochDay()))
            }
            refreshReview(); notify("已标为难句，加入复习。")
        }
    }

    fun retrySentence() {
        playOriginal()
        val job = playback
        val token = playbackVersion
        viewModelScope.launch {
            job?.join()
            if (token == playbackVersion && state.value.page == Page.TRAINER)
                mutable.update { it.copy(evaluationMessage = "点击录音开始，再点击结束。") }
        }
    }
    fun recognizeExisting() {
        val s = state.value
        val article = s.article ?: return
        val currentSession = session ?: return
        if (!s.hasRecording || s.analyzing) return
        stop()
        val attempt = Attempt(UUID.randomUUID().toString(), currentSession.id, article.id, s.index, System.currentTimeMillis())
        val source = audio.recordingFile(article.id, s.index)
        val snapshot = File(source.parentFile, "${s.index}.${attempt.id}.attempt.m4a")
        try {
            source.copyTo(snapshot)
            evaluate(attempt, s.sentences[s.index].text, snapshot, true)
        } catch (error: Exception) { snapshot.delete(); notify(error.message ?: "无法读取录音。") }
    }
    fun recognitionWrong() {
        advancement?.cancel()
        val attempt = latestAttempt ?: return
        val disputed = attempt.copy(result = "DISPUTED")
        latestAttempt = disputed
        mutable.update { it.copy(evaluation = AttemptResult.DISPUTED,
            advancePending = false, evaluationMessage = "已标记识别有误；可重识别或继续，本次不自动通过。") }
        val previousReview = reviewBeforeAttempt
        mutable.update { it.copy(consecutiveDifferences = streakBeforeAttempt) }
        persistenceJobs += viewModelScope.launch {
            database.withTransaction {
                training.content(disputed.id, disputed.recognized, disputed.differences, disputed.result, disputed.errorRatio)
                if (attempt.feedbackMode != "PRONUNCIATION") {
                    if (previousReview == null) training.removeReview(attempt.articleId, attempt.position)
                    else training.save(previousReview)
                }
            }
            refreshReview()
        }
    }
    fun continueAfterDispute() { if (state.value.evaluation == AttemptResult.DISPUTED) advanceSentence() }
    fun continuePronunciation() {
        if (state.value.pronunciationEnabled && !state.value.analyzing && state.value.phase != Phase.RECORDING)
            advanceSentence()
    }
    fun skipSentence() {
        val s = state.value
        val article = s.article ?: return
        val captured = session ?: return
        stop()
        viewModelScope.launch {
            database.withTransaction {
                training.insert(captured)
                training.insert(Attempt(UUID.randomUUID().toString(), captured.id, article.id, s.index,
                    System.currentTimeMillis(), result = "SKIPPED", feedbackMode = if (s.pronunciationEnabled) "PRONUNCIATION" else "CONTENT"))
            }
            advanceSentence()
        }
    }
    private fun advanceSentence() {
        if (state.value.reviewMode) {
            stop()
            reviewCursor++
            if (reviewCursor >= reviewQueue.size) endTraining() else openReviewSentence(reviewQueue[reviewCursor])
        } else if (state.value.index == state.value.sentences.lastIndex) endTraining()
        else { stop(); select(state.value.index + 1) }
    }

    fun startReview() {
        stop()
        refreshDay()
        viewModelScope.launch {
            reviewQueue = training.due(LocalDate.now().toEpochDay())
            if (reviewQueue.isEmpty()) { notify("今天没有到期句子。"); return@launch }
            reviewCursor = 0
            session = TrainingSession(UUID.randomUUID().toString(), System.currentTimeMillis(), reviewMode = true)
            openReviewSentence(reviewQueue.first())
        }
    }
    private fun openReviewSentence(sentence: ReviewSentence) {
        load?.cancel()
        mutable.update { it.copy(busy = true) }
        load = viewModelScope.launch {
            try {
                val article = training.article(sentence.articleId) ?: error("复习文章已删除。")
                val sentences = dao.sentences(article.id)
                latestAttempt = null
                mutable.update { it.copy(page = Page.TRAINER, article = article, sentences = sentences,
                    index = sentence.position, busy = false, reviewMode = true, feedbackTraining = true,
                    feedback = null, recognized = "", evaluation = null, evaluationMessage = null,
                    consecutiveDifferences = 0, hasRecording = recordingExists(article.id, sentence.position), report = null,
                    pronunciation = null, pronunciationError = null) }
                refreshReview()
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { mutable.update { it.copy(busy = false, notice = error.message) } }
        }
    }

    fun endTraining() {
        if (state.value.busy) return
        stop()
        if (localEngine.isInitialized()) localEngine.value.release()
        val captured = session ?: return
        val pending = recognition
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                pending?.join()
                persistenceJobs.toList().forEach { it.join() }
                persistenceJobs.clear()
                training.insert(captured)
                training.end(captured.id, System.currentTimeMillis())
                val attempts = training.attempts(captured.id)
                val sentences = attempts.map { it.articleId }.distinct().flatMap { dao.sentences(it) }
                    .associateBy { it.articleId to it.position }
                val events = attempts.map { attempt ->
                    val key = attempt.articleId to attempt.position
                    val assessed = attempt.pronunciationJson?.let { runCatching { PronunciationJson.decode(it) }.getOrNull() }
                    ReportRow(key.first, key.second, sentences[key]?.text ?: "", AttemptResult.valueOf(attempt.result),
                        attempt.errorRatio, describeDifferences(attempt.differences), 1, attempt.feedbackMode,
                        assessed?.accuracyScore, assessed?.words?.count { it.status != PronunciationStatus.UNKNOWN } ?: 0,
                        attempt.pronunciationJson)
                }
                session = null
                mutable.update { it.copy(page = Page.REPORT, report = TrainingReport.fromAttempts(events), busy = false) }
            } catch (error: Exception) { mutable.update { it.copy(busy = false, notice = error.message) } }
        }
    }
    private fun describeDifferences(json: String): String {
        if (json.isBlank()) return ""
        val array = org.json.JSONArray(json)
        return (0 until array.length()).map { array.getJSONObject(it) }.filter { it.getString("kind") != "MATCH" }
            .take(3).joinToString("；") {
                when (it.getString("kind")) {
                    "MISSING" -> "漏词 ${it.optString("expected")}"
                    "EXTRA" -> "多词 ${it.optString("heard")}"
                    else -> "${it.optString("expected")} → ${it.optString("heard")}"
                }
            }
    }
    fun playWord(word: String) {
        stop()
        val token = playbackVersion
        playback = viewModelScope.launch {
            try {
                mutable.update { it.copy(phase = Phase.PREPARING) }
                val file = speech.sentenceFile(word, state.value.voice)
                mutable.update { it.copy(phase = Phase.PLAYING) }
                audio.play(file, state.value.speed)
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { notify(error.message ?: "语音生成失败。") }
            finally { if (token == playbackVersion) mutable.update { it.copy(phase = Phase.IDLE) } }
        }
    }
    fun previewVoice() { playWord("Every small step brings you closer to your goal.") }

    fun deleteArticle(article: Article) {
        stop()
        viewModelScope.launch {
            persistenceJobs.toList().forEach { it.join() }
            dao.delete(article.id)
            withContext(Dispatchers.IO) {
                // Only delete exact files under this article's private recording directory.
                val directory = File(getApplication<Application>().filesDir, "recordings/${article.id}")
                directory.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
                directory.delete()
            }
            if (state.value.article?.id == article.id) mutable.update {
                it.copy(page = Page.HOME, article = null, sentences = emptyList(), index = 0)
            }
        }
    }

    fun importDemo() {
        editImport("The art of small steps", "Learning a language is a journey, not a race.\n\n" +
            "Start with one sentence. Listen carefully to its rhythm. Then say it aloud in your own voice.\n\n" +
            "You do not need to be perfect today. A little practice every day can make a big difference.", false)
        importArticle()
    }

    override fun onCleared() {
        playback?.cancel(); prefetch?.cancel(); recognition?.cancel(); advancement?.cancel(); download?.cancel()
        audio.finishRecording(false); audio.release(); recognizer.close(); if (localEngine.isInitialized()) localEngine.value.close(); super.onCleared()
    }
}
