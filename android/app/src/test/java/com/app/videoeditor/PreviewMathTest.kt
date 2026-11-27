package com.app.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewMathTest {

    // ------------------------------------------------------------ timeline position

    @Test
    fun locateFindsClipAndLocalTime() {
        val d = listOf(1000L, 2000L, 500L)
        assertEquals(TimelinePos(0, 0), TimelineMath.locate(d, 0))
        assertEquals(TimelinePos(0, 999), TimelineMath.locate(d, 999))
        assertEquals(TimelinePos(1, 1999), TimelineMath.locate(d, 2999))
        assertEquals(TimelinePos(2, 499), TimelineMath.locate(d, 3499))
    }

    @Test
    fun exactBoundaryBelongsToTheLaterClip() {
        val d = listOf(1000L, 2000L, 500L)
        assertEquals(TimelinePos(1, 0), TimelineMath.locate(d, 1000))
        assertEquals(TimelinePos(2, 0), TimelineMath.locate(d, 3000))
    }

    @Test
    fun endOfTimelineStaysOnTheLastClip() {
        val d = listOf(1000L, 2000L, 500L)
        assertEquals(TimelinePos(2, 500), TimelineMath.locate(d, 3500))
        assertEquals(TimelinePos(2, 500), TimelineMath.locate(d, 99_999))
        assertEquals(TimelinePos(0, 0), TimelineMath.locate(d, -5))
    }

    @Test
    fun zeroLengthClipsAreSkippedAndEmptyTimelineHasNoPosition() {
        assertEquals(TimelinePos(2, 0), TimelineMath.locate(listOf(1000L, 0L, 1000L), 1000))
        assertNull(TimelineMath.locate(emptyList(), 0))
    }

    @Test
    fun startsAreCumulative() {
        assertEquals(listOf(0L, 1000L, 3000L), TimelineMath.starts(listOf(1000L, 2000L, 500L)))
        assertEquals(3500L, TimelineMath.total(listOf(1000L, 2000L, 500L)))
    }

    // ------------------------------------------------------------ fades

    @Test
    fun fadeRampsInAndOut() {
        val total = 5_000_000L
        assertEquals(0f, Motion.fade(1_000_000, 1_000_000, total, 0), 0.001f)
        assertEquals(0.5f, Motion.fade(1_000_000, 1_000_000, total, 500_000), 0.001f)
        assertEquals(1f, Motion.fade(1_000_000, 1_000_000, total, 2_500_000), 0.001f)
        assertEquals(0.5f, Motion.fade(1_000_000, 1_000_000, total, 4_500_000), 0.001f)
        assertEquals(0f, Motion.fade(1_000_000, 1_000_000, total, 5_000_000), 0.001f)
    }

    @Test
    fun overlappingFadesUseTheDarkerOne() {
        // clip shorter than fadeIn + fadeOut
        assertEquals(0.5f, Motion.fade(1_000_000, 1_000_000, 1_000_000, 500_000), 0.001f)
        assertEquals(0.25f, Motion.fade(1_000_000, 1_000_000, 1_000_000, 250_000), 0.001f)
    }

    @Test
    fun noFadeMeansFullBrightness() {
        assertEquals(1f, Motion.fade(0, 0, 4_000_000, 0), 0.001f)
        assertEquals(1f, Motion.fade(0, 0, 4_000_000, 4_000_000), 0.001f)
    }

    // ------------------------------------------------------------ motion presets

    @Test
    fun zoomPresetsGrowAndShrink() {
        val total = 4_000_000L
        assertEquals(1f, Motion.anim(Anim.ZoomIn, 0, total).scale, 0.001f)
        assertEquals(1.25f, Motion.anim(Anim.ZoomIn, total, total).scale, 0.001f)
        assertEquals(1.25f, Motion.anim(Anim.ZoomOut, 0, total).scale, 0.001f)
        assertEquals(1f, Motion.anim(Anim.ZoomOut, total, total).scale, 0.001f)
        assertEquals(1f, Motion.anim(Anim.None, 2_000_000, total).scale, 0.001f)
    }

    @Test
    fun panPresetsMoveInOppositeDirections() {
        val total = 4_000_000L
        assertEquals(0.18f, Motion.anim(Anim.PanLeft, 0, total).dx, 0.001f)
        assertEquals(0f, Motion.anim(Anim.PanLeft, 2_000_000, total).dx, 0.001f)
        assertEquals(-0.18f, Motion.anim(Anim.PanLeft, total, total).dx, 0.001f)
        assertEquals(-0.18f, Motion.anim(Anim.PanRight, 0, total).dx, 0.001f)
        assertEquals(0.18f, Motion.anim(Anim.PanRight, total, total).dx, 0.001f)
    }

    @Test
    fun pulseOscillatesOncePerSecond() {
        val total = 10_000_000L
        assertEquals(1f, Motion.anim(Anim.Pulse, 0, total).scale, 0.001f)
        assertEquals(1.05f, Motion.anim(Anim.Pulse, 250_000, total).scale, 0.001f)
        assertEquals(0.95f, Motion.anim(Anim.Pulse, 750_000, total).scale, 0.001f)
    }

    // ------------------------------------------------------------ text timing

    private fun layer(start: Float, end: Float, anim: TextAnim) =
        TextLayer(id = 1L, text = "Hi", scale = 2f, y = 0.5f, startFrac = start, endFrac = end, anim = anim)

    @Test
    fun textIsOnlyVisibleInsideItsTimeRange() {
        val total = 4_000_000L
        val l = layer(0.25f, 0.75f, TextAnim.None)
        assertFalse(textRender(l, total, 999_999).visible)
        assertTrue(textRender(l, total, 1_000_000).visible)
        assertTrue(textRender(l, total, 3_000_000).visible)
        assertFalse(textRender(l, total, 3_000_001).visible)
    }

    @Test
    fun defaultTextLayerIsVisibleForTheWholeClip() {
        val total = 4_000_000L
        val l = layer(0f, 1f, TextAnim.None)
        assertTrue(textRender(l, total, 0).visible)
        assertTrue(textRender(l, total, total).visible)
        assertEquals(1f, textRender(l, total, 2_000_000).alpha, 0.001f)
    }

    @Test
    fun malformedFullClipTimingMatchesStaticExportVisibility() {
        val total = 4_000_000L
        val malformed = layer(Float.NaN, Float.NaN, TextAnim.None)
        assertFalse(requiresTimedTextOverlay(malformed, 4_000L))
        assertTrue(textRender(malformed, total, 0L).visible)
        assertTrue(textRender(malformed, total, 2_000_000L).visible)
        assertTrue(textRender(malformed, total, total).visible)
    }

    @Test
    fun malformedPartialTimingUsesTimedOverlayAndSharedPreviewRange() {
        val total = 4_000_000L
        val malformed = layer(Float.NaN, 0.5f, TextAnim.None)
        assertTrue(requiresTimedTextOverlay(malformed, 4_000L))
        assertTrue(textRender(malformed, total, 0L).visible)
        assertTrue(textRender(malformed, total, 1_999_999L).visible)
        assertFalse(textRender(malformed, total, 2_000_001L).visible)
    }

    @Test
    fun infiniteTextTimingIsNormalizedConsistently() {
        val total = 4_000_000L
        val malformed = layer(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, TextAnim.None)
        assertFalse(requiresTimedTextOverlay(malformed, 4_000L))
        assertTrue(textRender(malformed, total, 0L).visible)
        assertTrue(textRender(malformed, total, 3_000_000L).visible)
    }

    @Test
    fun fadeInAnimationRampsAlphaOverTheAnimationTime() {
        val total = 4_000_000L
        val l = layer(0.25f, 0.75f, TextAnim.Fade)
        val start = 1_000_000L
        assertEquals(0f, textRender(l, total, start).alpha, 0.001f)
        assertEquals(0.5f, textRender(l, total, start + TEXT_ANIM_US / 2).alpha, 0.001f)
        assertEquals(1f, textRender(l, total, start + TEXT_ANIM_US).alpha, 0.001f)
    }

    @Test
    fun popAnimationGrowsFromHalfSizeToFullSize() {
        val total = 4_000_000L
        val l = layer(0f, 1f, TextAnim.Pop)
        assertEquals(1f, textRender(l, total, 0).scale, 0.001f)           // 2f * 0.5
        assertEquals(1.5f, textRender(l, total, TEXT_ANIM_US / 2).scale, 0.001f)
        assertEquals(2f, textRender(l, total, TEXT_ANIM_US).scale, 0.001f)
    }

    @Test
    fun slideUpAnimationStartsLowerAndReachesItsPosition() {
        val total = 4_000_000L
        val l = layer(0f, 1f, TextAnim.SlideUp)
        assertEquals(0.25f, textRender(l, total, 0).y, 0.001f)             // 0.5 - 0.25
        assertEquals(0.5f, textRender(l, total, TEXT_ANIM_US).y, 0.001f)
    }

    // ------------------------------------------------------------ frame geometry

    @Test
    fun frameAspectAccountsForCropAndRotation() {
        assertEquals(16f / 9f, frameAspectOf(1920, 1080, 0f, 0f, 0f, 0f, 0), 0.001f)
        assertEquals(9f / 16f, frameAspectOf(1920, 1080, 0f, 0f, 0f, 0f, 90), 0.001f)
        assertEquals(960f / 1080f, frameAspectOf(1920, 1080, 0.25f, 0.25f, 0f, 0f, 0), 0.001f)
    }

    @Test
    fun landscapeClipFitsAPortraitCanvasWithBarsOrFillsIt() {
        val fit = fitFrame(16f / 9f, 1080f, 1920f, fill = false)
        assertEquals(1080f, fit.first, 0.5f)
        assertEquals(607.5f, fit.second, 0.5f)

        val fill = fitFrame(16f / 9f, 1080f, 1920f, fill = true)
        assertEquals(1920f * 16f / 9f, fill.first, 0.5f)
        assertEquals(1920f, fill.second, 0.5f)
    }

    @Test
    fun matchingAspectFillsTheCanvasExactly() {
        val f = fitFrame(9f / 16f, 1080f, 1920f, fill = false)
        assertEquals(1080f, f.first, 0.5f)
        assertEquals(1920f, f.second, 0.5f)
    }

    // ------------------------------------------------------------ combined clip transform

    private fun look(
        rotation: Int = 0, flipH: Boolean = false, flipV: Boolean = false, zoom: Float = 1f,
        anim: Anim = Anim.None, fadeIn: Long = 0, fadeOut: Long = 0, outputMs: Long = 4000,
        kfs: List<Keyframe> = emptyList()
    ) = ClipLook(rotation, flipH, flipV, zoom, anim, fadeIn, fadeOut, outputMs, kfs)

    @Test
    fun staticZoomFlipAndRotationAreCarriedOver() {
        val t = clipTransform(look(rotation = 90, flipH = true, zoom = 2f), 0)
        assertEquals(-2f, t.srScaleX, 0.001f)
        assertEquals(2f, t.srScaleY, 0.001f)
        assertEquals(90f, t.srRotation, 0.001f)
        assertEquals(1f, t.alpha, 0.001f)
    }

    @Test
    fun keyframeOpacityAndFadeMultiply() {
        val kfs = listOf(Keyframe(0f, opacity = 0f, ease = Ease.Linear), Keyframe(1f, opacity = 1f))
        val mid = clipTransform(look(kfs = kfs), 2_000_000)
        assertEquals(0.5f, mid.alpha, 0.001f)

        val faded = clipTransform(look(fadeIn = 2000, kfs = kfs), 1_000_000)   // fade 0.5 * opacity 0.25
        assertEquals(0.125f, faded.alpha, 0.001f)
    }

    @Test
    fun keyframeValuesFollowTheTimelineClock() {
        val kfs = listOf(
            Keyframe(0f, x = -1f, scale = 1f, ease = Ease.Linear),
            Keyframe(1f, x = 1f, scale = 2f)
        )
        val start = clipTransform(look(kfs = kfs), 0)
        val half = clipTransform(look(kfs = kfs), 2_000_000)
        val end = clipTransform(look(kfs = kfs), 4_000_000)
        assertEquals(-1f, start.kfX, 0.001f)
        assertEquals(0f, half.kfX, 0.001f)
        assertEquals(1.5f, half.kfScale, 0.001f)
        assertEquals(1f, end.kfX, 0.001f)
        assertNotNull(end)
    }

    @Test
    fun timeOutsideTheClipIsClamped() {
        val kfs = listOf(Keyframe(0f, x = -1f), Keyframe(1f, x = 1f))
        assertEquals(-1f, clipTransform(look(kfs = kfs), -500_000).kfX, 0.001f)
        assertEquals(1f, clipTransform(look(kfs = kfs), 99_000_000).kfX, 0.001f)
    }
}
