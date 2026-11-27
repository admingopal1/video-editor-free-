package com.app.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextLayerSnapTest {
    private fun layer() = TextLayer(id=1L,startFrac=0.2f,endFrac=0.6f)
    @Test fun moveSnapsEitherEdgeAndPreservesDuration() {
        val r=snapTextLayerTiming(layer(),10_000L,850L,listOf(3_000L),150L,TextLayerSnapMode.MOVE)
        val t=textLayerTiming(r.layer,10_000L)
        assertEquals(3_000L,t.startMs); assertEquals(7_000L,t.endMs); assertEquals(3_000L,r.snappedToMs)
    }
    @Test fun trimStartSnapsAndKeepsEnd() {
        val r=snapTextLayerTiming(layer(),10_000L,850L,listOf(3_000L),150L,TextLayerSnapMode.TRIM_START)
        val t=textLayerTiming(r.layer,10_000L); assertEquals(3_000L,t.startMs); assertEquals(6_000L,t.endMs)
    }
    @Test fun trimEndSnapsAndKeepsStart() {
        val r=snapTextLayerTiming(layer(),10_000L,-850L,listOf(5_000L),150L,TextLayerSnapMode.TRIM_END)
        val t=textLayerTiming(r.layer,10_000L); assertEquals(2_000L,t.startMs); assertEquals(5_000L,t.endMs)
    }
    @Test fun outsideThresholdDoesNotSnap() {
        val r=snapTextLayerTiming(layer(),10_000L,100L,listOf(3_000L),50L,TextLayerSnapMode.MOVE)
        assertNull(r.snappedToMs); assertEquals(2_100L,textLayerTiming(r.layer,10_000L).startMs)
    }
    @Test fun invalidPointsAndTinyClipAreSafe() {
        assertNull(snapTextLayerTiming(layer(),10_000L,100L,listOf(-1L,20_000L),50L,TextLayerSnapMode.MOVE).snappedToMs)
        val r=snapTextLayerTiming(layer(),50L,200L,listOf(10L),50L,TextLayerSnapMode.MOVE)
        assertEquals(layer(),r.layer); assertNull(r.snappedToMs)
    }
}
