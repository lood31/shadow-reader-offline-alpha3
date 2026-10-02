package com.shadowreader.app.audio

/** Time accumulates only while Media3 reports isPlaying: excludes preparation, pause and rebuffering. */
class PlaybackTiming(private val now: () -> Long) {
    private var started: Long? = null
    private var elapsed = 0L
    fun playing(active: Boolean) {
        if (active) { if (started == null) started = now() }
        else { started?.let { elapsed += (now() - it).coerceAtLeast(0) }; started = null }
    }
    fun finish(): Long { playing(false); return elapsed }
}

object PlaybackSettings {
    fun speed(value: Float) = (kotlin.math.round(value.coerceIn(.5f, 2f) * 20) / 20).coerceIn(.5f, 2f)
    fun gapMillis(playedMillis: Long, ratio: Float, buffer: Int, enabled: Boolean) =
        if (!enabled) 0L else (playedMillis * ratio.coerceIn(.5f, 2f)).toLong() + buffer.coerceIn(0, 5) * 1000L
}
