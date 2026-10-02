package com.shadowreader.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shadowreader.app.*
import com.shadowreader.app.data.Article
import com.shadowreader.app.audio.SpeechVoice
import com.shadowreader.app.training.*

private val Paper = Color(0xFFFAF7F1)
private val Ink = Color(0xFF292E2B)
private val Orange = Color(0xFFD75C37)
private val Green = Color(0xFF526B59)
private val Muted = Color(0xFF72766E)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShadowReaderApp(model: ReaderViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val articles by model.library.collectAsStateWithLifecycle()
    val dueCount by model.dueCount.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var settings by rememberSaveable { mutableStateOf(false) }
    var delete by remember { mutableStateOf<Article?>(null) }
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var pendingMic by remember { mutableStateOf<Pair<String?, Int>?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val current = model.state.value
        if (granted && current.page == Page.TRAINER && pendingMic == (current.article?.id to current.index) &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) model.toggleRecording()
        else if (!granted) model.notify("需要麦克风权限才能录音，可在系统应用设置中开启。")
        pendingMic = null
    }
    LaunchedEffect(state.notice) {
        state.notice?.let { snackbar.showSnackbar(it); model.clearNotice() }
    }
    BackHandler(state.page != Page.HOME && !settings) { model.back() }
    MaterialTheme(colorScheme = lightColorScheme(primary = Orange, secondary = Green,
        background = Paper, surface = Paper, onSurface = Ink, onBackground = Ink,
        surfaceVariant = Color(0xFFEFECE4), onSurfaceVariant = Muted),
        shapes = Shapes(medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp))) {
        Scaffold(containerColor = Paper, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
            TopAppBar(title = {
                Column {
                    Text(if (state.page == Page.HOME) "Shadow Reader" else when (state.page) {
                        Page.IMPORT -> "导入素材"; Page.ARTICLE -> "文章预览"; Page.REPORT -> "训练报告"; else -> "影子跟读"
                    }, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    if (state.page == Page.HOME) Text("READ. LISTEN. FIND YOUR VOICE.", fontSize = 9.sp,
                        letterSpacing = 1.3.sp, color = Muted)
                }
            }, navigationIcon = {
                if (state.page != Page.HOME) IconButton(onClick = model::back) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
            }, actions = { IconButton(onClick = { settings = true }) { Icon(Icons.Default.Tune, "训练设置") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper))
        }) { insets ->
            Box(Modifier.fillMaxSize().padding(insets)) {
                when (state.page) {
                    Page.HOME -> Home(articles, state.busy, model::openImport, model::openArticle,
                        model::importDemo, { delete = it }, dueCount, model::startReview)
                    Page.IMPORT -> Import(state, model::editImport, model::importArticle)
                    Page.ARTICLE -> ArticlePreview(state, model::startTrainer)
                    Page.TRAINER -> Trainer(state, model::togglePlayback, model::playOriginal, model::move,
                        { model.settings(blind = !state.blind) }, {
                            if (state.phase == Phase.RECORDING || ContextCompat.checkSelfPermission(context,
                                    Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) model.toggleRecording()
                            else {
                                pendingMic = state.article?.id to state.index
                                permission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }, model::playRecording, { settings = true }, model)
                    Page.REPORT -> Report(state.report, model::back)
                }
                if (state.busy && state.page == Page.HOME) CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
        if (settings) SettingsDialog(state, model, { settings = false }, {
            try { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
            catch (_: Exception) {
                try { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                catch (_: Exception) { model.notify("请手动打开系统设置中的文字转语音。") }
            }
        })
        delete?.let { article ->
            AlertDialog(onDismissRequest = { delete = null }, title = { Text("删除这篇文章？") },
                text = { Text("“${article.title}”的正文、学习进度和跟读录音会一起删除。") },
                confirmButton = { TextButton(onClick = { model.deleteArticle(article); delete = null }) { Text("删除") } },
                dismissButton = { TextButton(onClick = { delete = null }) { Text("保留") } })
        }
    }
}

@Composable
private fun Home(articles: List<Article>, busy: Boolean, import: () -> Unit, open: (Article) -> Unit,
    demo: () -> Unit, delete: (Article) -> Unit, due: Int, review: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Ink), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("YOUR DAILY PRACTICE", color = Color(0xFFD4C6AE), fontSize = 10.sp, letterSpacing = 2.sp)
                    Text("让每一篇文章，\n成为你的声音。", fontSize = 29.sp, lineHeight = 39.sp,
                        fontWeight = FontWeight.Bold, color = Paper)
                    Text("导入英文网页，听一句、跟一句。", color = Color(0xFFD2D4CC), fontSize = 13.sp)
                    Button(onClick = import, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("导入文章 / 粘贴正文")
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = review, enabled = !busy && due > 0, modifier = Modifier.fillMaxWidth()) {
                Text("今日到期复习 · $due 句")
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("我的素材库", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${articles.size} 篇", color = Muted)
            }
        }
        if (articles.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Headphones, null, tint = Green, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(16.dp))
                Text("第一句，从这里开始。", fontSize = 19.sp)
                Text("从浏览器分享文章，或先试一段示例。", color = Muted, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp))
                OutlinedButton(onClick = demo, enabled = !busy, modifier = Modifier.padding(top = 20.dp)) { Text("体验示例文章") }
            }
        }
        items(articles, key = { it.id }) { article ->
            Card(onClick = { if (!busy) open(article) }, colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(article.title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = { delete(article) }, enabled = !busy) { Icon(Icons.Default.DeleteOutline, "删除文章", tint = Muted) }
                    }
                    Text("${article.sentenceCount} 句 · ${if (article.completedCount == 0) "尚未开始" else "已练到第 ${article.completedCount} 句"}",
                        color = Muted, fontSize = 12.sp)
                    LinearProgressIndicator(progress = { article.completedCount.toFloat() / article.sentenceCount.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(), color = Green, trackColor = Paper)
                }
            }
        }
        item { Text("素材与录音保存在此设备。", color = Muted, fontSize = 11.sp) }
    }
}

@Composable
private fun Import(state: ReaderState, edit: (String?, String?, Boolean?) -> Unit, submit: () -> Unit) {
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("把你想读的，变成练习。", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(selected = state.urlMode, onClick = { edit(null, null, true) }, enabled = !state.busy, label = { Text("网页链接") })
            FilterChip(selected = !state.urlMode, onClick = { edit(null, null, false) }, enabled = !state.busy, label = { Text("粘贴正文") })
        }
        if (!state.urlMode) OutlinedTextField(value = state.importTitle, onValueChange = { edit(it, null, null) },
            label = { Text("文章标题（可选）") }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy, singleLine = true)
        OutlinedTextField(value = state.importContent, onValueChange = { edit(null, it, null) },
            label = { Text(if (state.urlMode) "HTTPS 文章地址" else "英文正文") },
            placeholder = { Text(if (state.urlMode) "https://example.com/article" else "Paste your English article here…") },
            modifier = Modifier.fillMaxWidth().heightIn(min = if (state.urlMode) 80.dp else 240.dp),
            minLines = if (state.urlMode) 1 else 8, maxLines = if (state.urlMode) 4 else 15, enabled = !state.busy)
        Text(if (state.urlMode) "支持公开的静态文章。需要登录、付费或动态加载的网页，请切换到粘贴正文。" else
            "段落会自动切分成句子。请使用英文内容，导入后可以先检查提取结果。", color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
        Button(onClick = submit, enabled = !state.busy && state.importContent.isNotBlank(), modifier = Modifier.fillMaxWidth().height(54.dp)) {
            if (state.busy) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("正在提取与分句…") }
            else { Text("生成练习素材"); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null) }
        }
    }
}

@Composable
private fun ArticlePreview(state: ReaderState, start: () -> Unit) {
    val article = state.article ?: return
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
            item {
                Text("READY TO PRACTICE", color = Orange, fontSize = 10.sp, letterSpacing = 2.sp)
                Text(article.title, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp))
                Text("${article.sentenceCount} 句 · 约 ${maxOf(1, article.body.split(Regex("\\s+")).size / 140)} 分钟原音", color = Muted,
                    modifier = Modifier.padding(top = 12.dp))
                article.source?.let { Text(it, color = Muted, fontSize = 11.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp)) }
                HorizontalDivider(Modifier.padding(top = 24.dp))
            }
            items(state.sentences, key = { it.position }) { sentence ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("%02d".format(sentence.position + 1), color = Orange, fontSize = 11.sp, modifier = Modifier.width(24.dp).padding(top = 5.dp))
                    Text(sentence.text, fontFamily = FontFamily.Serif, fontSize = 19.sp, lineHeight = 29.sp)
                }
            }
        }
        Button(onClick = start, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).height(54.dp)) {
            Icon(Icons.Default.Headphones, null); Spacer(Modifier.width(10.dp))
            Text(if (state.index > 0) "从第 ${state.index + 1} 句继续" else "开始影子跟读")
        }
    }
}

@Composable
private fun Trainer(state: ReaderState, play: () -> Unit, original: () -> Unit, move: (Int) -> Unit,
    blind: () -> Unit, record: () -> Unit, own: () -> Unit, settings: () -> Unit, model: ReaderViewModel) {
    val sentence = state.sentences.getOrNull(state.index) ?: return
    var reveal by remember(state.index, state.blind) { mutableStateOf(false) }
    val recording = state.phase == Phase.RECORDING
    var exportId by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) exportId?.let { model.exportPronunciation(it,uri) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("SENTENCE %02d".format(state.index + 1), color = Orange, fontSize = 11.sp, letterSpacing = 1.8.sp, modifier = Modifier.weight(1f))
            Text("${state.index + 1} / ${state.sentences.size}", color = Muted, fontSize = 13.sp)
        }
        LinearProgressIndicator(progress = { (state.index + 1f) / state.sentences.size }, modifier = Modifier.fillMaxWidth(), color = Green)
        Text(if (state.reviewMode) "到期复习" else if (state.feedbackTraining && state.pronunciationEnabled)
            "美式发音评估 · 手动继续" else if (state.feedbackTraining) "内容反馈 · 手动录音" else "自由听读", color = Green)
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().heightIn(min = 220.dp).padding(28.dp), verticalArrangement = Arrangement.Center) {
                if (state.blind && !reveal) {
                    Icon(Icons.Default.VisibilityOff, null, tint = Green)
                    Text("先听，再开口。", fontSize = 25.sp, modifier = Modifier.padding(top = 18.dp))
                    TextButton(onClick = { reveal = true }) { Text("查看当前句") }
                } else Text(sentence.text, fontFamily = FontFamily.Serif, fontSize = 27.sp, lineHeight = 39.sp, color = Ink)
            }
        }
        Text(when (state.phase) {
            Phase.IDLE -> "听原音，然后用自己的声音重复"
            Phase.PREPARING -> "正在准备英语语音…"
            Phase.PLAYING -> if (state.repetition == 0) "正在播放当前句原音" else "正在播放 · 第 ${state.repetition} / ${state.repeats} 遍"
            Phase.PAUSED -> "已暂停 · 点击继续"
            Phase.GAP -> "轮到你了 · 留白 ${state.gapRemaining} 秒"
            Phase.RECORDING -> "正在录音 · 再次点击结束（最多 2 分钟）"
            Phase.OWN_AUDIO -> "正在播放你的录音"
        }, color = if (recording) Orange else Green, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            IconButton(onClick = { move(-1) }, enabled = state.index > 0 && !recording) { Icon(Icons.Default.SkipPrevious, "上一句") }
            FilledIconButton(onClick = play, enabled = !recording && !state.analyzing, modifier = Modifier.size(78.dp), shape = RoundedCornerShape(28.dp)) {
                Icon(if (state.phase in listOf(Phase.PLAYING, Phase.GAP)) Icons.Default.Pause
                    else if (state.phase in listOf(Phase.PREPARING, Phase.OWN_AUDIO)) Icons.Default.Stop else Icons.Default.PlayArrow,
                    if (state.phase in listOf(Phase.PLAYING, Phase.GAP)) "暂停" else "播放 / 继续", modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = { move(1) }, enabled = state.index < state.sentences.lastIndex && !recording) { Icon(Icons.Default.SkipNext, "下一句") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AssistChip(onClick = settings, label = { Text("${state.speed}× · 循环 ${state.repeats} 遍") }, leadingIcon = { Icon(Icons.Default.Repeat, null, Modifier.size(16.dp)) })
            FilterChip(selected = state.blind, onClick = blind, enabled = !recording, label = { Text("盲听") }, leadingIcon = { Icon(Icons.Default.VisibilityOff, null, Modifier.size(16.dp)) })
        }
        HorizontalDivider()
        Button(onClick = record, enabled = !state.analyzing && !state.busy, modifier = Modifier.fillMaxWidth().height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = if (recording) Orange else Ink)) {
            Icon(if (recording) Icons.Default.Stop else Icons.Default.Mic, null)
            Spacer(Modifier.width(10.dp)); Text(if (recording) "结束并保存录音" else if (state.hasRecording) "重新录一遍" else "录下我的跟读")
        }
        if (state.phase == Phase.GAP || state.phase == Phase.PAUSED && state.gapRemaining > 0)
            TextButton(onClick = model::skipGap) { Text("跳过留白") }
        if (!state.modelReady) {
            Text("尚未下载辅助 Whisper，可继续听读、录音及电脑发音评估。", color = Muted, fontSize = 12.sp)
            if (state.downloading) {
                LinearProgressIndicator(progress = { state.downloadProgress }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = model::cancelDownload) { Text("取消下载 · ${(state.downloadProgress * 100).toInt()}%") }
            } else OutlinedButton(onClick = model::downloadModel) { Text("下载英文离线模型 · 约 60 MB") }
        }
        if (state.analyzing) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text(if (state.pronunciationBusy) state.pronunciationStage.ifBlank { "正在分析发音…" } else "正在本机识别…", color = Green)
            TextButton(onClick = model::cancelRecognition) { Text("取消评估") }
        }
        state.pronunciation?.let { result ->
            PronunciationCard(result,replayEnabled = !state.analyzing && !recording) { start,end ->
                model.playPronunciationSegment(result.requestId,start,end)
            }
            OutlinedButton(onClick = { exportId = result.requestId; export.launch("ShadowReader-${result.requestId}.zip") },
                enabled = !state.analyzing && !recording) { Text("导出本次实验数据") }
        }
        state.pronunciationError?.let { Text(it,color = Orange,fontSize = 13.sp) }
        state.evaluation?.let { result ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(when (result) {
                        AttemptResult.CONSISTENT -> "文本一致"
                        AttemptResult.DIFFERENT -> "识别到的差异"
                        AttemptResult.DISPUTED -> "已标记识别有误"
                        else -> "未评估"
                    }, fontWeight = FontWeight.SemiBold, color = Green)
                    Text("标准句：${sentence.text}", fontSize = 14.sp)
                    if (state.recognized.isNotBlank()) Text("识别文本：${state.recognized}", fontSize = 14.sp)
                    state.evaluationMessage?.let { Text(it, color = Muted, fontSize = 12.sp) }
                    state.feedback?.let { feedback ->
                        Text(feedback.words.joinToString(" · ") { word -> when (word.kind) {
                            DifferenceKind.MATCH -> "✓ ${word.expected}"
                            DifferenceKind.MISSING -> "漏 ${word.expected}"
                            DifferenceKind.SUBSTITUTE -> "${word.expected} → ${word.heard}"
                            DifferenceKind.EXTRA -> "多 ${word.heard}"
                        } }, fontSize = 14.sp, color = if (feedback.consistent) Green else Orange)
                        feedback.priorities.forEach { word ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(when (word.kind) {
                                    DifferenceKind.MISSING -> "漏词：${word.expected}"
                                    DifferenceKind.EXTRA -> "多词：${word.heard}"
                                    else -> "替换：${word.expected} → ${word.heard}"
                                }, Modifier.weight(1f), fontSize = 13.sp)
                                TextButton(onClick = { model.playWord(word.expected ?: word.heard ?: return@TextButton) }) { Text("听单词") }
                            }
                        }
                    }
                    Text("文本一致表示识别内容相符，不代表发音已经标准。", fontSize = 11.sp, color = Muted)
                    if (state.advancePending)
                        Text("两秒后进入下一句；点击任一音频操作可停留。", fontSize = 11.sp, color = Green)
                    if (TrainingRules.needsReviewPrompt(state.consecutiveDifferences))
                        Text("连续三次仍有差异，建议加入复习；也可以继续练或暂时跳过。", fontSize = 12.sp, color = Orange)
                    Row {
                        TextButton(onClick = model::retrySentence) { Text("再读一次") }
                        if (result in listOf(AttemptResult.CONSISTENT, AttemptResult.DIFFERENT))
                            TextButton(onClick = model::recognitionWrong) { Text("识别有误") }
                        if (result == AttemptResult.DISPUTED)
                            TextButton(onClick = model::continueAfterDispute) { Text("继续") }
                    }
                }
            }
        }
        if (state.hasRecording && !recording && !state.analyzing && (state.modelReady || state.pronunciationEnabled))
            TextButton(onClick = model::recognizeExisting) { Text(if (state.pronunciationEnabled) "评估已有录音" else "识别已有录音") }
        if (state.pronunciationEnabled && state.feedbackTraining && !recording)
            OutlinedButton(onClick = model::continuePronunciation,enabled = !state.analyzing) {
                Text(if (state.index == state.sentences.lastIndex && !state.reviewMode) "完成并查看报告" else "继续下一句")
            }
        Row {
            TextButton(onClick = model::markDifficult, enabled = !recording) { Text(if (state.difficult) "已标为难句" else "标为难句 / 加入复习") }
            TextButton(onClick = model::skipSentence, enabled = !recording) { Text("暂时跳过") }
        }
        OutlinedButton(onClick = model::endTraining, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("结束训练并查看报告") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = original, enabled = !recording && !state.analyzing, modifier = Modifier.weight(1f)) { Text("听原音") }
            OutlinedButton(onClick = own, enabled = state.hasRecording && !recording && !state.analyzing, modifier = Modifier.weight(1f)) { Text("听我的录音") }
        }
        Text("录音保存在本机。启用发音评估时会发送到你设置的电脑。切页或后台会停止评估。", fontSize = 11.sp, lineHeight = 17.sp, color = Muted)
    }
}

@Composable
private fun SettingsDialog(state: ReaderState, model: ReaderViewModel, close: () -> Unit, tts: () -> Unit) {
    var serviceUrl by remember(state.pronunciationUrl) { mutableStateOf(state.pronunciationUrl) }
    AlertDialog(onDismissRequest = close, title = { Text("找到你的练习节奏") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("播放速度", fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)) { speed ->
                    FilterChip(selected = state.speed == speed, onClick = { model.settings(speed = speed) }, label = { Text("${speed}×", fontSize = 11.sp) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { model.settings(speed = state.speed - .05f) }) { Text("−") }
                Text("%.2f×".format(state.speed), Modifier.weight(1f))
                TextButton(onClick = { model.settings(speed = state.speed + .05f) }) { Text("＋") }
            }
            Slider(value = state.speed, onValueChange = { model.settings(speed = it) }, valueRange = .5f..2f, steps = 29)
            Text("每句循环", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 3, 5).forEach { repeats ->
                    FilterChip(selected = state.repeats == repeats, onClick = { model.settings(repeats = repeats) }, label = { Text("$repeats 遍") })
                }
            }
            Text("原音后的跟读留白", fontWeight = FontWeight.SemiBold)
            SettingSwitch("自由听读自适应留白", state.gapEnabled) { model.gap(enabled = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(.5f, 1f, 1.5f, 2f).forEach { ratio ->
                    FilterChip(selected = state.gapRatio == ratio, onClick = { model.gap(ratio = ratio) }, label = { Text("$ratio 倍") })
                }
            }
            Text("额外缓冲：${state.gapBuffer} 秒", fontSize = 13.sp)
            Slider(state.gapBuffer.toFloat(), { model.gap(buffer = kotlin.math.round(it).toInt()) }, valueRange = 0f..5f, steps = 4)
            SettingSwitch("自由听读自动下一句", state.autoNext) { model.settings(auto = it) }
            SettingSwitch("反馈训练", state.feedbackTraining) { model.trainingMode(it) }
            if (!state.pronunciationEnabled)
                SettingSwitch("文本一致两秒后自动下一句", state.feedbackAutoNext) { model.feedbackAuto(it) }
            if (BuildConfig.OFFLINE_PRONUNCIATION) {
                Text("手机离线发音反馈 · en-US",fontWeight = FontWeight.SemiBold)
                SettingSwitch("启用声学证据反馈",state.pronunciationEnabled) { model.pronunciationSettings("",it) }
                Text("发音模型已内置；首次使用会初始化本地资源。颜色表示实验声学证据，跟读录音在本机分析。",fontSize = 12.sp)
            } else {
            Text("电脑发音评估 · en-US",fontWeight = FontWeight.SemiBold)
            OutlinedTextField(serviceUrl,{ serviceUrl = it },label = { Text("评分服务地址") },
                placeholder = { Text("http://192.168.1.10:8765") },singleLine = true)
            TextButton(onClick = { model.pronunciationSettings(serviceUrl,state.pronunciationEnabled) }) { Text("保存服务地址") }
            SettingSwitch("启用发音评估",state.pronunciationEnabled) { model.pronunciationSettings(serviceUrl,it) }
            Text("启用后会把跟读录音发送到该服务。手机和电脑需互通；发音模式手动继续，不自动判定掌握。第一版只评估美式英语。",fontSize = 12.sp)
            }
            Text("语音音色", fontWeight = FontWeight.SemiBold)
            SpeechVoice.entries.forEach { voice ->
                FilterChip(selected = state.voice == voice, onClick = { model.voice(voice) }, label = { Text(voice.label) })
            }
            TextButton(onClick = model::previewVoice) { Text("试听当前音色（统一样句）") }
            TextButton(onClick = tts) { Text("打开系统文字转语音设置") }
            Text("Edge 生成需联网，使用非官方接口，可能失效。已缓存语音可离线回放；Whisper 识别在本机。发音评估仅发送到你配置的服务。", fontSize = 12.sp, lineHeight = 18.sp)
        }
    }, confirmButton = { TextButton(onClick = close) { Text("完成") } })
}

@Composable
private fun Report(report: TrainingReport?, home: () -> Unit) {
    if (report == null) return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("本次训练", fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text("练习 ${report.practiced} 句 · 尝试 ${report.attempts} 次", fontSize = 18.sp)
        Text("文本一致 ${report.consistent} 句\n待复习 ${report.review} 句\n跳过 ${report.skipped} 句\n未评估 ${report.unevaluated} 句", lineHeight = 28.sp)
        if (report.pronunciationRows.isNotEmpty()) {
            Text("发音练习 ${report.pronunciationRows.size} 句")
            report.pronunciationRows.forEach { row ->
                Text(row.text,fontSize = 13.sp)
                val assessed = row.pronunciationJson?.let { runCatching { com.shadowreader.app.pronunciation.PronunciationJson.decode(it) }.getOrNull() }
                if (assessed != null) PronunciationSummary(assessed)
                else Text("本次未取得可用发音结果",fontSize = 12.sp)
            }
            Text("发音练习不自动修改复习或掌握状态。",fontSize = 12.sp,color = Muted)
        }
        Text("每句按本次最终结果统计；反复练习不会重复计句。", color = Muted, fontSize = 12.sp)
        if (report.hardest.isNotEmpty()) Text("主要练习重点", fontWeight = FontWeight.SemiBold)
        report.hardest.forEach { row ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.text, fontFamily = FontFamily.Serif, fontSize = 18.sp)
                    Text(row.differences.ifBlank { "识别结果待确认" }, color = Orange, fontSize = 13.sp)
                    Text("尝试 ${row.attemptCount} 次", fontSize = 12.sp, color = Muted)
                }
            }
        }
        Text("文本匹配反馈不能衡量发音和韵律。", color = Muted, fontSize = 12.sp)
        Button(onClick = home, modifier = Modifier.fillMaxWidth()) { Text("返回素材库") }
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, changed: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = changed)
    }
}
