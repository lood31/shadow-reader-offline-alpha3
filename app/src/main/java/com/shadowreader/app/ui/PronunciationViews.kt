package com.shadowreader.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadowreader.app.pronunciation.*

private fun pronunciationColor(status: PronunciationStatus) = when (status) {
    PronunciationStatus.GREEN -> Color(0xFF267146)
    PronunciationStatus.YELLOW -> Color(0xFF986700)
    PronunciationStatus.RED -> Color(0xFFBB3434)
    PronunciationStatus.UNKNOWN -> Color(0xFF737773)
}
private fun scoreLabel(score: Double?, status: PronunciationStatus) =
    if (score != null && status != PronunciationStatus.UNKNOWN) "旧版试验分数 ${score.toInt()}" else when (status) {
        PronunciationStatus.GREEN -> "目标音素支持较强"
        PronunciationStatus.YELLOW -> "证据有分歧，可重听重练"
        PronunciationStatus.RED -> "竞争证据较强，建议重练"
        PronunciationStatus.UNKNOWN -> "无法可靠评估"
    }

@Composable
internal fun PronunciationSummary(assessment: PronunciationAssessment) {
    Text("词色：绿 ${assessment.words.count { it.status == PronunciationStatus.GREEN }} · 黄 ${assessment.words.count { it.status == PronunciationStatus.YELLOW }} · 红 ${assessment.words.count { it.status == PronunciationStatus.RED }} · 灰 ${assessment.words.count { it.status == PronunciationStatus.UNKNOWN }}",fontSize = 12.sp)
    if (assessment.evidence) {
        Text("可评估音素覆盖率 ${(assessment.coverage*100).toInt()}%",fontSize = 12.sp)
        val focus = assessment.words.filter { it.status in listOf(PronunciationStatus.RED,PronunciationStatus.YELLOW) }
            .sortedWith(compareBy<WordAssessment> { if (it.status == PronunciationStatus.RED) 0 else 1 }.thenBy { it.index }).take(3)
        if (focus.isNotEmpty()) Text("重练重点：${focus.joinToString("、") { it.text }}",fontSize = 12.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PronunciationCard(assessment: PronunciationAssessment, replayEnabled: Boolean = true,
    onReplay: (Int,Int) -> Unit = { _,_ -> }) {
    var selected by remember(assessment.requestId) { mutableStateOf<WordAssessment?>(null) }
    var details by remember(assessment.requestId) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (assessment.evidence) "离线声学证据 · 实验版" else "旧版发音评估 · 试验结果", style = MaterialTheme.typography.titleMedium)
            Text(if (assessment.evidence) "颜色表示目标音素的声学证据强弱，不是发音正确率。点击单词查看音素。"
                else if (assessment.calibrationStatus == "UNCALIBRATED")
                "评分服务尚未校准。当前只展示音素定位，不能判断发音好坏。"
                else "颜色表示模型估计；灰色表示证据不足。点击单词查看音素。", fontSize = 12.sp)
            assessment.accuracyScore?.let { Text("试验发音评分：${it.toInt()} 分") }
            val annotated = remember(assessment) {
                buildAnnotatedString {
                    append(assessment.text)
                    assessment.words.forEach { word ->
                        addStyle(SpanStyle(color = pronunciationColor(word.status),textDecoration = TextDecoration.Underline),
                            word.sourceStart,word.sourceEnd)
                    }
                }
            }
            ClickableText(annotated, style = TextStyle(fontSize = 21.sp,lineHeight = 32.sp), onClick = { offset ->
                selected = assessment.words.firstOrNull { offset in it.sourceStart until it.sourceEnd }
            })
            Text("绿：支持较强 · 黄：有分歧 · 红：竞争较强 · 灰：无法评估", fontSize = 11.sp)
            PronunciationSummary(assessment)
            if (assessment.reasonCode == "TARGET_INCOMPATIBLE") Text("录音与目标句未能可靠对齐，请确认内容完整并重新录音。",fontSize = 12.sp)
        }
    }
    selected?.let { word ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(word.text,style = MaterialTheme.typography.headlineSmall)
                Text(scoreLabel(word.score,word.status),color = pronunciationColor(word.status))
                Text("时间戳是估计值；音素结果不代表确定的误读诊断。",fontSize = 12.sp)
                if (word.startMs != null && word.endMs != null)
                    OutlinedButton(onClick = { onReplay(word.startMs,word.endMs) },enabled = replayEnabled) { Text("回放我的这个词") }
                TextButton(onClick = { details = !details }) { Text(if (details) "收起开发详情" else "开发详情") }
                if (details) Text("模型可靠性是输出集中程度，不是发音正确率。竞争路径候选也不是误读诊断。",fontSize = 12.sp)
                word.phonemes.forEach { phone ->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("/${phone.ipa}/",Modifier.weight(1f),fontSize = 19.sp)
                        Column {
                            Text(if (phone.reasonCode == "ACOUSTIC_CONFLICT") "目标音素支持不足，未评分"
                                else scoreLabel(phone.score,phone.status),color = pronunciationColor(phone.status))
                            if (details) {
                                Text("可靠性 ${"%.3f".format(phone.confidence)} · GOP ${phone.gopRaw?.let { "%.3f".format(it) } ?: "—"}",fontSize = 11.sp)
                                Text("帧优势 ${phone.frameMargin?.let { "%.3f".format(it) } ?: "—"} · 路径优势 ${phone.pathMargin?.let { "%.3f".format(it) } ?: "—"}\n竞争候选 ${phone.competitorIpa ?: "—"}",fontSize = 11.sp)
                            }
                            if (phone.reasonCode == "POSSIBLE_ALLOPHONE") Text("可能为可接受的美式音变",fontSize = 12.sp)
                            if (phone.reasonCode == "ALIGNMENT_AMBIGUOUS") Text("定位存在歧义，暂不判断",fontSize = 12.sp)
                            if (phone.startMs != null && phone.endMs != null)
                                Text("约 ${phone.startMs}–${phone.endMs} ms",fontSize = 11.sp)
                            if (phone.startMs != null && phone.endMs != null)
                                TextButton(onClick = { onReplay(phone.startMs,phone.endMs) },enabled = replayEnabled) { Text("回放这段录音") }
                        }
                    }
                }
                if (word.phonemes.isEmpty()) Text("没有可用音素定位。")
                if (details) {
                    Text("模型 ${assessment.modelVersion.take(12)} · 配置 ${assessment.configVersion}",fontSize = 11.sp)
                    assessment.timingsMs.forEach { (name,time) -> Text("$name：${time.toInt()} ms",fontSize = 11.sp) }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
