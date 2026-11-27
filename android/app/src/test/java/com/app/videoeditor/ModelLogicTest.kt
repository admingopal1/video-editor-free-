package com.app.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Test
import android.net.Uri

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
    @Test
    fun trimDurationUsesEndMinusStartAndClampsInvalidRanges() {
        assertEquals(7_500L, trimmedDurationMs(2_500L, 10_000L))
        assertEquals(0L, trimmedDurationMs(10_000L, 2_500L))
    }

    @Test
    fun outputDurationAccountsForPlaybackSpeed() {
        assertEquals(10_000L, outputDurationMs(0L, 5_000L, 0.5f))
        assertEquals(2_500L, outputDurationMs(0L, 5_000L, 2f))
    }

    @Test
    fun invalidPlaybackSpeedProducesZeroOutputDuration() {
        assertEquals(0L, outputDurationMs(0L, 5_000L, 0f))
        assertEquals(0L, outputDurationMs(0L, 5_000L, -1f))
    }

    @Test
    fun staleExportCleanupOnlyDeletesOldExportFiles() {
        val cutoff = 100_000L

        assertEquals(true, shouldDeleteStaleExportFile("export_old.mp4", 99_999L, cutoff))
        assertEquals(false, shouldDeleteStaleExportFile("export_recent.mp4", 99_999L + 1L, cutoff))
        assertEquals(false, shouldDeleteStaleExportFile("silence_temp.mp4", 1L, cutoff))
        assertEquals(false, shouldDeleteStaleExportFile("export_future.mp4", 100_001L, cutoff))
    }

    @Test
    fun missingLegacyTrimEndKeepsFullSource() {
        assertEquals(Long.MAX_VALUE, normalizedAudioTrimEndMs(null, 500L))
    }

    @Test
    fun trimEndIsAlwaysAfterTrimStart() {
        assertEquals(1_001L, normalizedAudioTrimEndMs(900L, 1_000L))
        assertEquals(8_900L, normalizedAudioTrimEndMs(8_900L, 1_000L))
    }

    @Test
    fun audioFadeEnvelopeRampsInAndOut() {
        assertEquals(0f, audioFadeGainAt(0L, 1_000L, 200L, 300L), 0.0001f)
        assertEquals(0.5f, audioFadeGainAt(100L, 1_000L, 200L, 300L), 0.0001f)
        assertEquals(1f, audioFadeGainAt(200L, 1_000L, 200L, 300L), 0.0001f)
        assertEquals(0.5f, audioFadeGainAt(850L, 1_000L, 200L, 300L), 0.0001f)
        assertEquals(0f, audioFadeGainAt(1_000L, 1_000L, 200L, 300L), 0.0001f)
    }

    @Test
    fun audioFadeEnvelopeClampsOutsideTrackDuration() {
        assertEquals(0f, audioFadeGainAt(-100L, 1_000L, 250L, 250L), 0.0001f)
        assertEquals(0f, audioFadeGainAt(1_100L, 1_000L, 250L, 250L), 0.0001f)
        assertEquals(1f, audioFadeGainAt(500L, 1_000L, 0L, 0L), 0.0001f)
    }

    @Test
    fun audioGainKeyframesInterpolateAndClamp() {
        val points = listOf(AudioGainPoint(0L, 0f), AudioGainPoint(1_000L, 2f))
        assertEquals(0f, audioKeyframeGainAt(points, 0L), 0.0001f)
        assertEquals(1f, audioKeyframeGainAt(points, 500L), 0.0001f)
        assertEquals(2f, audioKeyframeGainAt(points, 1_500L), 0.0001f)
    }

    @Test
    fun audioGainAutomationStaysAtUnityBeforeFirstPoint() {
        val points = listOf(AudioGainPoint(2_000L, 0.25f), AudioGainPoint(4_000L, 1.5f))
        assertEquals(1f, audioKeyframeGainAt(points, 0L), 0.0001f)
        assertEquals(1f, audioKeyframeGainAt(points, 1_999L), 0.0001f)
        assertEquals(0.25f, audioKeyframeGainAt(points, 2_000L), 0.0001f)
    }

    @Test
    fun voiceDuckingFollowsVoiceoverOffsetAndReturnsTowardUnity() {
        val voice = VoiceoverActivity(id = 7L, offsetMs = 1_000L, trimStartMs = 0L, trimEndMs = Long.MAX_VALUE)
        val peaks = FloatArray(30) { index -> if (index in 0..5) 0.9f else 0f }
        val points = buildVoiceDuckingPoints(listOf(voice), mapOf(7L to peaks), 3_000L, 0L)
        assertEquals(1f, audioKeyframeGainAt(points, 0L), 0.0001f)
        assertEquals(true, audioKeyframeGainAt(points, 1_500L) < 0.5f)
        assertEquals(true, audioKeyframeGainAt(points, 2_800L) > audioKeyframeGainAt(points, 1_500L))
    }

    @Test
    fun audioGainKeyframesUseSortedBinarySearchInterpolation() {
        val points = listOf(AudioGainPoint(0L, 0f), AudioGainPoint(1_000L, 2f), AudioGainPoint(2_000L, 1f))
        assertEquals(1f, audioKeyframeGainAt(points, 500L), 0.0001f)
        assertEquals(1.5f, audioKeyframeGainAt(points, 1_500L), 0.0001f)
    }

    @Test
    fun emptyAudioGainKeyframesKeepUnityGain() {
        assertEquals(1f, audioKeyframeGainAt(emptyList(), 500L), 0.0001f)
    }

}
