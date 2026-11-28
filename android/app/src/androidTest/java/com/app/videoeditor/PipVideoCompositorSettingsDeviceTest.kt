package com.app.videoeditor

import androidx.media3.common.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Android-runtime smoke tests for the Media3 compositor adapter (not encoded-output acceptance tests). */
@RunWith(AndroidJUnit4::class)
class PipVideoCompositorSettingsDeviceTest {

    private fun layer(): OverlayClip = OverlayClip(
        id = 41L,
        uriString = "file:///unused/pip.mp4",
        name = "PIP test",
        durationMs = 2_000L,
        width = 320,
        height = 240,
        timelineStartMs = 500L,
        startMs = 0L,
        endMs = 2_000L,
        scale = 0.35f,
        opacity = 0.8f,
        muted = true,
        layerOrder = 1
    )

    @Test
    fun compositorUsesPrimaryStreamDimensions() {
        val plan = buildPipVideoCompositionPlan(listOf(layer()))
        val settings = PipVideoCompositorSettings(plan)
        val primary = Size(1280, 720)
        val pip = Size(320, 240)

        assertEquals(primary, settings.getOutputSize(listOf(primary, pip)))
    }

    @Test
    fun activeAndInactiveOverlaySettingsAreConstructibleOnAndroid() {
        val plan = buildPipVideoCompositionPlan(listOf(layer()))
        val settings = PipVideoCompositorSettings(plan)
        val pipInputId = plan.videoInputs.single().inputId

        // The PIP starts at 500 ms and ends at 2,500 ms on the project clock.
        assertNotNull(settings.getOverlaySettings(pipInputId, 1_000_000L))
        assertNotNull(settings.getOverlaySettings(pipInputId, 2_500_000L))
        assertNotNull(settings.getOverlaySettings(plan.primaryInputId, 1_000_000L))
    }
}
