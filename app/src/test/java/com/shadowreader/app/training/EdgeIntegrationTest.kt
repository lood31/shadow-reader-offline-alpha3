package com.shadowreader.app.training

import com.shadowreader.app.audio.EdgeSpeech
import com.shadowreader.app.audio.SpeechVoice
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Opt-in network test of the production Kotlin protocol; never part of offline unit coverage. */
class EdgeIntegrationTest {
    @Test fun allFourVoicesReturnMp3() = runBlocking {
        assumeTrue(System.getenv("RUN_EDGE_INTEGRATION") == "1")
        val directory = File("../artifacts/voice-validation").apply { mkdirs() }
        for (voice in SpeechVoice.entries.filter { it.online }) {
            val bytes = EdgeSpeech().synthesize("Every small step brings you closer to your goal.", voice.serviceName)
            assertTrue("${voice.name} returned no MP3 audio", bytes.size > 1000)
            val mp3 = bytes.indices.take(bytes.size - 1).any { i ->
                (bytes[i].toInt() and 255) == 255 && (bytes[i + 1].toInt() and 224) == 224
            }
            assertTrue("${voice.name} is not MP3", mp3)
            File(directory, "${voice.name}.mp3").writeBytes(bytes)
        }
        val variants = mapOf("missing" to "Every small step brings you closer to your.",
            "substitute" to "Every small step brings you closer to your home.",
            "extra" to "Every small brave step brings you closer to your goal.")
        for ((name, text) in variants) {
            File(directory, "$name.mp3").writeBytes(EdgeSpeech().synthesize(text, SpeechVoice.ARIA.serviceName))
        }
    }
}
