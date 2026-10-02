package com.shadowreader.app.training

import java.time.LocalDate
import kotlin.math.sqrt

enum class DifferenceKind { MATCH, MISSING, SUBSTITUTE, EXTRA }
data class WordDifference(val kind: DifferenceKind, val expected: String?, val heard: String?)
data class Feedback(val words: List<WordDifference>) {
    val differences get() = words.filter { it.kind != DifferenceKind.MATCH }
    val consistent get() = differences.isEmpty()
    val errorRatio get() = differences.size.toDouble() / words.count { it.expected != null }.coerceAtLeast(1)
    val priorities get() = differences.distinctBy { it.expected to it.heard }.take(3)
}

interface FeedbackAlgorithm { fun compare(expected: String, heard: String): Feedback }

object WordAligner : FeedbackAlgorithm {
    // Expand only unambiguous contractions; 's and 'd deliberately retain their ambiguity.
    private val contractions = mapOf("can't" to "can not", "cannot" to "can not", "won't" to "will not",
        "don't" to "do not", "doesn't" to "does not", "didn't" to "did not", "isn't" to "is not",
        "aren't" to "are not", "wasn't" to "was not", "weren't" to "were not", "haven't" to "have not",
        "hasn't" to "has not", "hadn't" to "had not", "couldn't" to "could not", "wouldn't" to "would not",
        "shouldn't" to "should not", "mustn't" to "must not", "i'm" to "i am", "you're" to "you are",
        "we're" to "we are", "they're" to "they are", "i've" to "i have", "you've" to "you have",
        "we've" to "we have", "they've" to "they have", "i'll" to "i will", "you'll" to "you will",
        "he'll" to "he will", "she'll" to "she will", "we'll" to "we will", "they'll" to "they will")
    fun normalize(text: String): List<String> = Regex("[a-z0-9]+(?:'[a-z]+)?")
        .findAll(text.lowercase(java.util.Locale.ROOT).replace('’', '\'').replace('‘', '\''))
        .flatMap { (contractions[it.value] ?: it.value).split(' ').asSequence() }.toList()

    override fun compare(expected: String, heard: String): Feedback {
        val a = normalize(expected); val b = normalize(heard)
        require(a.size <= 1000 && b.size <= 1000) { "句子过长，无法评估。" }
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices) dp[i + 1][0] = i + 1
        for (j in b.indices) dp[0][j + 1] = j + 1
        for (i in 1..a.size) for (j in 1..b.size) {
            dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1,
                dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        var i = a.size; var j = b.size
        val words = mutableListOf<WordDifference>()
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && dp[i][j] == dp[i - 1][j - 1] + (if (a[i - 1] == b[j - 1]) 0 else 1) -> {
                    words += WordDifference(if (a[i - 1] == b[j - 1]) DifferenceKind.MATCH else DifferenceKind.SUBSTITUTE, a[--i], b[--j])
                }
                i > 0 && dp[i][j] == dp[i - 1][j] + 1 -> words += WordDifference(DifferenceKind.MISSING, a[--i], null)
                else -> words += WordDifference(DifferenceKind.EXTRA, null, b[--j])
            }
        }
        return Feedback(words.reversed())
    }
}

enum class AttemptResult { CONSISTENT, DIFFERENT, SKIPPED, UNEVALUATED, DISPUTED }
object TrainingRules {
    fun needsReviewPrompt(consecutiveDifferences: Int) = consecutiveDifferences >= 3
    fun nextReview(today: LocalDate, round: Int, passed: Boolean): Pair<Int, LocalDate?> =
        if (!passed) 0 to today.plusDays(1)
        else when (round) { 0 -> 1 to today.plusDays(1); 1 -> 2 to today.plusDays(3)
            2 -> 3 to today.plusDays(7); else -> 4 to null }
    fun evaluable(samples: FloatArray): Boolean {
        if (samples.size < 8000) return false
        val rms = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
        val voiced = samples.count { kotlin.math.abs(it) >= .01f }
        return rms >= .003 && voiced >= 1600
    }
}

data class ReportRow(val articleId: String, val position: Int, val text: String,
    val result: AttemptResult, val ratio: Double, val differences: String, val attemptCount: Int,
    val feedbackMode: String = "LEGACY", val pronunciationScore: Double? = null, val assessedWords: Int = 0,
    val pronunciationJson: String? = null)
data class TrainingReport(val rows: List<ReportRow>, val attempts: Int) {
    val practiced get() = rows.size
    val consistent get() = rows.count { it.feedbackMode != "PRONUNCIATION" && it.result == AttemptResult.CONSISTENT }
    val review get() = rows.count { it.feedbackMode != "PRONUNCIATION" && it.result == AttemptResult.DIFFERENT }
    val skipped get() = rows.count { it.result == AttemptResult.SKIPPED }
    val unevaluated get() = rows.count { it.feedbackMode != "PRONUNCIATION" && (it.result == AttemptResult.UNEVALUATED || it.result == AttemptResult.DISPUTED) }
    val pronunciationRows get() = rows.filter { it.feedbackMode == "PRONUNCIATION" }
    val hardest get() = rows.filter { it.feedbackMode != "PRONUNCIATION" && it.result == AttemptResult.DIFFERENT }
        .sortedWith(compareByDescending<ReportRow> { it.ratio }.thenByDescending { it.attemptCount }).take(3)
    companion object {
        fun fromAttempts(events: List<ReportRow>): TrainingReport {
            val rows = events.groupBy { it.articleId to it.position }.values.map { group ->
                group.last().copy(attemptCount = group.count { it.result != AttemptResult.SKIPPED })
            }
            return TrainingReport(rows, events.count { it.result != AttemptResult.SKIPPED })
        }
    }
}
