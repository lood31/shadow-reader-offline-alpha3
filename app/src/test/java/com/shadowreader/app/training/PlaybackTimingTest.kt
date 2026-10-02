package com.shadowreader.app.training

import com.shadowreader.app.audio.PlaybackTiming
import com.shadowreader.app.audio.PlaybackSettings
import org.junit.Assert.*
import org.junit.Test

class PlaybackTimingTest {
    @Test fun pauseLoadingAndRebufferingAreExcluded() {
        var time = 0L
        val timing = PlaybackTiming { time }
        time = 5000 // preparation
        timing.playing(true)
        time = 6000; timing.playing(false) // 1 s played
        time = 16000; timing.playing(true) // paused for 10 s
        time = 16500; timing.playing(false) // 0.5 s played
        time = 20500; timing.playing(true) // rebuffered for 4 s
        time = 22500
        assertEquals(3500L, timing.finish())
        time = 30000
        assertEquals(3500L, timing.finish())
    }
    @Test fun adaptiveGapUsesPlayedWallTimeAndCanBeDisabled() {
        assertEquals(4500L, PlaybackSettings.gapMillis(3500, 1f, 1, true))
        assertEquals(1750L, PlaybackSettings.gapMillis(3500, .5f, 0, true))
        assertEquals(12000L, PlaybackSettings.gapMillis(3500, 2f, 5, true))
        assertEquals(0L, PlaybackSettings.gapMillis(3500, 1f, 1, false))
    }
    @Test fun speedClampsAndSnapsToFiveHundredths() {
        assertEquals(.5f, PlaybackSettings.speed(.1f), 0f)
        assertEquals(2f, PlaybackSettings.speed(4f), 0f)
        assertEquals(1.05f, PlaybackSettings.speed(1.06f), .0001f)
        assertEquals(.95f, PlaybackSettings.speed(.96f), .0001f)
    }
}
