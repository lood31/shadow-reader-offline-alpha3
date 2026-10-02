package com.shadowreader.app.pronunciation

import org.json.JSONObject

object PronunciationJson {
    private fun number(json: JSONObject, key: String, max: Double): Double? {
        if (!json.has(key) || json.isNull(key)) return null
        return json.getDouble(key).also { require(it.isFinite() && it in 0.0..max) { "评分服务返回无效数值。" } }
    }
    private fun time(json: JSONObject, key: String): Int? = if (!json.has(key) || json.isNull(key)) null
        else json.getInt(key).also { require(it in 0..30050) { "评分时间戳无效。" } }
    private fun color(json: JSONObject, score: Double?, evidence: Boolean): PronunciationStatus = if (score == null && !evidence) PronunciationStatus.UNKNOWN
        else runCatching { PronunciationStatus.valueOf(json.optString("status")) }.getOrDefault(PronunciationStatus.UNKNOWN)

    fun decode(raw: String): PronunciationAssessment {
        require(raw.length <= 1_048_576) { "评分响应过大。" }
        val root = JSONObject(raw)
        val schema = root.getInt("schemaVersion")
        val evidence = schema == 2 && root.optString("assessmentKind") == "ACOUSTIC_EVIDENCE_EXPERIMENTAL"
        require((schema == 1 || evidence) && root.getString("language") == "en-US") { "评分协议版本或语言不受支持。" }
        if (evidence) listOf("overallScore", "fluencyScore", "completenessScore", "prosodyScore").forEach {
            require(!root.has(it) || root.isNull(it)) { "声学证据协议不能提供数字分数。" }
        }
        val text = root.getString("text")
        val calibration = root.getString("calibrationStatus")
        val array = root.getJSONArray("words")
        require(array.length() <= 120)
        var previousEnd = 0
        val words = (0 until array.length()).map { i ->
            val word = array.getJSONObject(i)
            val start = word.getInt("sourceStart"); val end = word.getInt("sourceEnd")
            require(word.getInt("wordIndex") == i && start >= previousEnd && end > start && end <= text.length) { "单词位置无效。" }
            require(text.substring(start, end) == word.getString("text")) { "评分单词与原文不对应。" }
            previousEnd = end
            val score = number(word, "score", 100.0)
            require(!evidence || score == null)
            val list = word.getJSONArray("phonemes")
            require(list.length() <= 400)
            val phones = (0 until list.length()).map { j ->
                val phone = list.getJSONObject(j)
                require(phone.getInt("phonemeIndex") == j)
                val ps = number(phone, "score", 100.0)
                require(!evidence || ps == null)
                require(calibration != "UNCALIBRATED" || ps == null) { "未校准服务不能提供发音分数。" }
                val a = time(phone, "startMs"); val b = time(phone, "endMs")
                require((a == null && b == null) || (a != null && b != null && b >= a))
                PhonemeAssessment(j, phone.getString("ipa"), ps, number(phone,"confidence",1.0) ?: 0.0,
                    color(phone,ps,evidence), a,b, if (phone.isNull("gopRaw") || !phone.has("gopRaw")) null
                    else phone.getDouble("gopRaw").also { require(it.isFinite()) },
                    if (phone.isNull("reasonCode")) null else phone.optString("reasonCode").takeIf { it.isNotBlank() },
                    diagnostic(phone,"frameMargin"),diagnostic(phone,"pathMargin"),phone.optString("competitorIpa").takeIf { it.isNotBlank() && it != "null" })
            }
            require(calibration != "UNCALIBRATED" || score == null)
            val a = time(word,"startMs"); val b = time(word,"endMs")
            require((a == null && b == null) || (a != null && b != null && b >= a))
            WordAssessment(i,word.getString("text"),start,end,score,number(word,"confidence",1.0) ?: 0.0,
                color(word,score,evidence),a,b,phones)
        }
        val accuracy = number(root,"accuracyScore",100.0)
        require(!evidence || accuracy == null)
        require(calibration != "UNCALIBRATED" || accuracy == null)
        return PronunciationAssessment(root.getString("requestId"),text,root.getString("audioSha256"),accuracy,
            calibration,root.getString("modelVersion"),root.getString("configVersion"),
            if (root.isNull("reasonCode")) null else root.optString("reasonCode").takeIf { it.isNotBlank() },words,raw,
            if (evidence) root.getString("assessmentKind") else "LEGACY_SCORE",number(root,"coverage",1.0) ?: 0.0,
            root.optJSONObject("timingsMs")?.let { obj -> obj.keys().asSequence().associateWith { key ->
                obj.getDouble(key).also { require(it.isFinite() && it >= 0) }
            } } ?: emptyMap())
    }
    private fun diagnostic(json: JSONObject, key: String): Double? = if (!json.has(key) || json.isNull(key)) null
        else json.getDouble(key).also { require(it.isFinite()) }
}
