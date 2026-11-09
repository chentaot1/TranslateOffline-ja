package io.github.ilovephos.translate

import android.graphics.RectF
import com.yomu.pipeline.bubble.Bubble
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReadingSessionContainmentTest {

    @Test
    fun readingSession_retainsUnenclosedTextOutsidePanelsAndNearMargins() {
        // Screen height = 1000. In v0.5.2 (restoring v0.4.0 behavior), supplemental side notes,
        // tsukkomi, vertical commentary, and margin narrations are kept regardless of page location.
        val rawBoxes = listOf(
            Bubble(1, RectF(100f, 20f, 300f, 60f), 0.9f),   // top margin bubble
            Bubble(10, RectF(100f, 20f, 300f, 50f), 0.9f),  // top margin caption (was dropped by hull/margins)
            Bubble(11, RectF(100f, 950f, 300f, 980f), 0.9f), // bottom margin caption (was dropped by margins)
            Bubble(12, RectF(100f, 200f, 130f, 225f), 0.9f)  // small unenclosed caption (h=25 < 36, area=750 < 1200)
        )
        val filtered = ReadingSession.filterDetections(rawBoxes, screenWidth = 1000, screenHeight = 1000)
        assertEquals(4, filtered.size)
        assertEquals(listOf(1, 10, 11, 12), filtered.map { it.id })
    }

    @Test
    fun readingSession_rejectsTinyNoiseAndFullscreenArtifacts() {
        val rawBoxes = listOf(
            // Tiny noise box: w = 8 (< 12) -> discarded
            Bubble(1, RectF(100f, 100f, 108f, 130f), 0.9f),
            // Tiny noise box: h = 10 (< 12) -> discarded
            Bubble(2, RectF(100f, 100f, 130f, 110f), 0.9f),
            // Valid caption: 15x15 -> kept
            Bubble(3, RectF(100f, 100f, 115f, 115f), 0.9f),
            // Fullscreen background box: 998x998 on 1000x1000 -> discarded
            Bubble(4, RectF(0f, 0f, 998f, 998f), 0.9f)
        )
        val filtered = ReadingSession.filterDetections(rawBoxes, screenWidth = 1000, screenHeight = 1000)
        assertEquals(1, filtered.size)
        assertEquals(3, filtered[0].id)
    }

    @Test
    fun readingSession_rejectsDetectionsInsideControlBar() {
        val controlBar = RectF(0f, 900f, 1000f, 1000f)
        val rawBoxes = listOf(
            // Inside control bar (overlap >= 90% and height <= control bar) -> discarded
            Bubble(1, RectF(400f, 920f, 600f, 980f), 0.9f),
            // Inside comic area above control bar -> kept
            Bubble(2, RectF(400f, 800f, 600f, 860f), 0.9f)
        )
        val filtered = ReadingSession.filterDetections(rawBoxes, screenWidth = 1000, screenHeight = 1000, ignoredControls = controlBar)
        assertEquals(1, filtered.size)
        assertEquals(2, filtered[0].id)
    }
}
