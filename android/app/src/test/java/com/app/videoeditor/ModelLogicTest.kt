package com.app.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelLogicTest {
    @Test
    fun clipOriginalAudioEqRoundTripsAndLegacyProjectsDefaultToFlat() {
        val original = Clip(
            id = 91L,
            uri = android.net.Uri.parse("content://test/video"),
            durationMs = 12_000L,
            width = 1920,
            height = 1080,
            audioEqGainsDb = listOf(5f, 3f, 0f, -2f, 1f)
        )
        val restored = clipFromJson(original.toJson())
        assertEquals(original.audioEqGainsDb, restored.audioEqGainsDb)

        val legacyJson = original.toJson().apply { remove("audioEq") }
        assertEquals(List(5) { 0f }, clipFromJson(legacyJson).audioEqGainsDb)
    }

    @Test
    fun audioMuteAndSoloRulesAreConsistent() {
        assertEquals(true, isAudioTrackAudible(false, false, false))
        assertEquals(false, isAudioTrackAudible(true, false, false))
        assertEquals(true, isAudioTrackAudible(false, true, true))
        assertEquals(false, isAudioTrackAudible(false, false, true))
        assertEquals(false, isAudioTrackAudible(true, true, true))
    }

    @Test
    fun audioTrackNameIsTrimmedAndLimited() {
        assertEquals("Narration", normalizedAudioTrackName("  Narration  "))
        assertEquals(80, normalizedAudioTrackName("a".repeat(100)).length)
        assertEquals("", normalizedAudioTrackName("   "))
    }

    @Test
    fun duplicateAudioTrackNameAddsCopySuffix() {
        assertEquals("Music copy", duplicateAudioTrackName("Music"))
    }

    @Test
    fun audioTrackReorderMovesOneStepAndRespectsBoundaries() {
        val ids = listOf(10L, 20L, 30L)
        assertEquals(listOf(20L, 10L, 30L), moveAudioTrackId(ids, 10L, 1))
        assertEquals(listOf(10L, 30L, 20L), moveAudioTrackId(ids, 30L, -1))
        assertEquals(ids, moveAudioTrackId(ids, 10L, -1))
        assertEquals(ids, moveAudioTrackId(ids, 30L, 1))
        assertEquals(ids, moveAudioTrackId(ids, 99L, 1))
        assertEquals(ids, moveAudioTrackId(ids, 20L, 0))
    }

    @Test
    fun voiceGateRespectsTrimStartAndEnd() {
        val wave = floatArrayOf(0f, 0f, 0.9f, 0.9f, 0f, 0f)
        val points = buildTrimmedVoiceGatePoints(wave, 200L, 400L, 0.1f)
        assertEquals(0L, points.first().timeMs)
        assertEquals(true, audioKeyframeGainAt(points, 0L) > 0.9f)
        assertEquals(200L, points.last().timeMs)
    }

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
    fun crossfadeAlignsNextTrackAndSetsMatchingFadeEnvelopes() {
        val first = AudioCrossfadeState(1L, 0L, 0L, Long.MAX_VALUE, 0L, 0L, true)
        val second = AudioCrossfadeState(2L, 9_000L, 0L, Long.MAX_VALUE, 0L, 0L, true)
        val result = configureAudioCrossfade(listOf(first, second), 1L, mapOf(1L to 10_000L, 2L to 8_000L), 2_000L)
        assertEquals(10_000L, result[0].trimEndMs)
        assertEquals(2_000L, result[0].fadeOutMs)
        assertEquals(8_000L, result[1].offsetMs)
        assertEquals(2_000L, result[1].fadeInMs)
        assertEquals(false, result[0].loop)
        assertEquals(false, result[1].loop)
    }

    @Test
    fun crossfadeDoesNotChangeTracksWithoutKnownDurationsOrNextTrack() {
        val first = AudioCrossfadeState(1L, 0L, 0L, Long.MAX_VALUE, 0L, 0L, false)
        val second = AudioCrossfadeState(2L, 5_000L, 0L, Long.MAX_VALUE, 0L, 0L, false)
        assertEquals(listOf(first), configureAudioCrossfade(listOf(first), 1L, mapOf(1L to 10_000L), 2_000L))
        assertEquals(listOf(first, second), configureAudioCrossfade(listOf(first, second), 1L, emptyMap(), 2_000L))
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

    @Test
    fun movingAutomationPointPreservesUniqueTimesAndGainValues() {
        val points = listOf(AudioGainPoint(100L, 0.2f), AudioGainPoint(200L, 0.8f), AudioGainPoint(300L, 1.4f))
        val moved = moveAudioGainPoint(points, 1, 100L)
        assertEquals(listOf(100L, 300L, 400L), moved.map { it.timeMs })
        assertEquals(0.8f, moved.first { it.timeMs == 400L }.gain, 0.0001f)
        assertEquals(points, moveAudioGainPoint(points, -1, 100L))
    }

    @Test
    fun movingAutomationPointBackwardClampsAtZeroAndSkipsOccupiedTimes() {
        val points = listOf(AudioGainPoint(0L, 0.3f), AudioGainPoint(100L, 0.7f), AudioGainPoint(200L, 1.1f))
        val moved = moveAudioGainPoint(points, 2, -200L)
        assertEquals(listOf(0L, 100L, 200L), moved.map { it.timeMs })
        assertEquals(1.1f, moved.last().gain, 0.0001f)
    }

    @Test
    fun pcmFloatSamplesAreClampedAndNonFiniteSamplesAreSilenced() {
        assertEquals(1f, clampPcmFloatSample(1.5f), 0.0001f)
        assertEquals(-1f, clampPcmFloatSample(-2f), 0.0001f)
        assertEquals(0.25f, clampPcmFloatSample(0.25f), 0.0001f)
        assertEquals(0f, clampPcmFloatSample(Float.NaN), 0.0001f)
        assertEquals(0f, clampPcmFloatSample(Float.POSITIVE_INFINITY), 0.0001f)
    }


    @Test
    fun sequentialAudioEnvelopeCursorMatchesReferenceInterpolation() {
        val points = listOf(
            AudioGainPoint(200L, 0.25f),
            AudioGainPoint(700L, 1.5f),
            AudioGainPoint(1_400L, 0.4f),
            AudioGainPoint(2_000L, 1.8f)
        )
        val cursor = AudioGainEnvelopeCursor(points)
        val times = listOf(0L, 199L, 200L, 201L, 450L, 700L, 999L, 1_400L, 1_999L, 2_000L, 2_500L)
        times.forEach { time ->
            assertEquals(audioKeyframeGainAt(points, time), cursor.gainAt(time), 0.0001f)
        }
    }

    @Test
    fun sequentialAudioEnvelopeCursorCanResetForAReplayedExport() {
        val points = listOf(AudioGainPoint(0L, 0.2f), AudioGainPoint(1_000L, 1.8f))
        val cursor = AudioGainEnvelopeCursor(points)
        assertEquals(1.8f, cursor.gainAt(1_000L), 0.0001f)
        cursor.reset()
        assertEquals(0.2f, cursor.gainAt(0L), 0.0001f)
        assertEquals(1f, AudioGainEnvelopeCursor(emptyList()).gainAt(500L), 0.0001f)
    }
}
