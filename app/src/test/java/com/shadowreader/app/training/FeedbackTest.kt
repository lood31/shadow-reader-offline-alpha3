package com.shadowreader.app.training

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FeedbackTest {
    @Test fun punctuationCaseAndQuotes() {
        assertTrue(WordAligner.compare("I CAN’T go, today!", "I can not go today.").consistent)
    }
    @Test fun morphologyIsSubstitution() {
        val result = WordAligner.compare("We begin today", "we beginning today")
        assertEquals(DifferenceKind.SUBSTITUTE, result.differences.single().kind)
        assertEquals("begin", result.differences.single().expected)
        assertEquals("beginning", result.differences.single().heard)
    }
    @Test fun missingExtraAndSubstitute() {
        assertEquals(DifferenceKind.MISSING, WordAligner.compare("I read a book", "I read book").differences.single().kind)
        assertEquals(DifferenceKind.EXTRA, WordAligner.compare("I read a book", "I read a good book").differences.single().kind)
        assertEquals(DifferenceKind.SUBSTITUTE, WordAligner.compare("I read a book", "I read a story").differences.single().kind)
    }
    @Test fun repeatedWordsAlignInOrder() {
        val result = WordAligner.compare("I had had a plan", "I had a plan")
        assertEquals(1, result.differences.size)
        assertEquals(DifferenceKind.MISSING, result.differences.single().kind)
        assertEquals(4, result.words.count { it.kind == DifferenceKind.MATCH })
    }
    @Test fun onlyUnambiguousContractionsExpand() {
        assertTrue(WordAligner.compare("I'm ready and we'll go", "I am ready and we will go").consistent)
        assertFalse(WordAligner.compare("She's ready", "She has ready").consistent)
    }
    @Test fun prioritiesAreLimitedAndRatioCanExceedOne() {
        val result = WordAligner.compare("a b c d e", "f g h i j")
        assertEquals(3, result.priorities.size)
        assertEquals(1.0, result.errorRatio, 0.0)
    }
    @Test fun silentAndShortAudioAreUnevaluated() {
        assertFalse(TrainingRules.evaluable(FloatArray(16000)))
        assertFalse(TrainingRules.evaluable(FloatArray(7999) { .3f }))
        assertFalse(TrainingRules.evaluable(FloatArray(16000) { .001f }))
        assertTrue(TrainingRules.evaluable(FloatArray(16000) { if (it % 2 == 0) .1f else -.1f }))
    }
    @Test fun threeValidDifferencesPromptButDoNotForceStop() {
        assertFalse(TrainingRules.needsReviewPrompt(2))
        assertTrue(TrainingRules.needsReviewPrompt(3))
        assertTrue(TrainingRules.needsReviewPrompt(4))
    }
    @Test fun reviewUsesCalendarDaysIncludingMonthAndYearBoundary() {
        val date = LocalDate.of(2026, 12, 31)
        assertEquals(1 to LocalDate.of(2027, 1, 1), TrainingRules.nextReview(date, 0, true))
        assertEquals(2 to LocalDate.of(2027, 1, 3), TrainingRules.nextReview(date, 1, true))
        assertEquals(3 to LocalDate.of(2027, 1, 7), TrainingRules.nextReview(date, 2, true))
        assertEquals(4 to null, TrainingRules.nextReview(date, 3, true))
        assertEquals(0 to LocalDate.of(2027, 1, 1), TrainingRules.nextReview(date, 3, false))
    }
    @Test fun reportDeduplicatesBySentenceAndUsesFinalOutcome() {
        val events = listOf(
            ReportRow("a", 0, "one", AttemptResult.DIFFERENT, 1.0, "missing", 1),
            ReportRow("a", 0, "one", AttemptResult.CONSISTENT, 0.0, "", 1),
            ReportRow("a", 1, "two", AttemptResult.DIFFERENT, .5, "substitute", 1),
            ReportRow("b", 0, "three", AttemptResult.SKIPPED, 0.0, "", 1),
            ReportRow("b", 1, "four", AttemptResult.DISPUTED, .8, "", 1))
        val report = TrainingReport.fromAttempts(events)
        assertEquals(4, report.practiced)
        assertEquals(4, report.attempts)
        assertEquals(1, report.consistent)
        assertEquals(1, report.review)
        assertEquals(1, report.skipped)
        assertEquals(1, report.unevaluated)
        assertEquals(2, report.rows.first().attemptCount)
        assertEquals("two", report.hardest.single().text)
    }
}
