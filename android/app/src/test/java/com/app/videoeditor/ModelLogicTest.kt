package com.app.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelLogicTest {
    @Test
    fun emptyKeyframesReturnNeutralFrameAtRequestedTime() {
        val result = interpKeyframes(emptyList(), 0.4f)

        assertEquals(0.4f, result.t, 0.0001f)
        assertEquals(0f, result.x, 0.0001f)
        assertEquals(1f, result.scale, 0.0001f)
        assertEquals(1f, result.opacity, 0.0001f)
    }

    @Test
    fun interpolationClampsBeforeFirstAndAfterLastKeyframe() {
        val frames = listOf(
            Keyframe(t = 0.2f, x = -0.5f),
            Keyframe(t = 0.8f, x = 0.75f)
        )

        val before = interpKeyframes(frames, 0.1f)
        val after = interpKeyframes(frames, 0.9f)

        assertEquals(0.1f, before.t, 0.0001f)
        assertEquals(-0.5f, before.x, 0.0001f)
        assertEquals(0.9f, after.t, 0.0001f)
        assertEquals(0.75f, after.x, 0.0001f)
    }

    @Test
    fun smoothInterpolationUsesSmoothstepCurve() {
        val frames = listOf(
            Keyframe(t = 0f, x = 0f, ease = Ease.Smooth),
            Keyframe(t = 1f, x = 1f, ease = Ease.Smooth)
        )

        assertEquals(0.15625f, interpKeyframes(frames, 0.25f).x, 0.0001f)
        assertEquals(0.5f, interpKeyframes(frames, 0.5f).x, 0.0001f)
    }

    @Test
    fun linearInterpolationDoesNotApplySmoothstep() {
        val frames = listOf(
            Keyframe(t = 0f, x = 0f, ease = Ease.Linear),
            Keyframe(t = 1f, x = 1f, ease = Ease.Linear)
        )

        assertEquals(0.25f, interpKeyframes(frames, 0.25f).x, 0.0001f)
    }
}
