package com.app.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportRequestTrackerTest {

    @Test
    fun newRequestOwnsTheExportUntilItFinishes() {
        val tracker = ExportRequestTracker()
        assertFalse(tracker.isRunning)

        val id = tracker.begin()

        assertTrue(tracker.isRunning)
        assertTrue(tracker.owns(id))
        assertTrue(tracker.finish(id))
        assertFalse(tracker.isRunning)
    }

    @Test
    fun cancelledRequestCanNeitherPublishNorChangeState() {
        val tracker = ExportRequestTracker()
        val id = tracker.begin()

        tracker.cancel()

        // Late Transformer completion / gallery save of the cancelled export must be rejected.
        assertFalse(tracker.owns(id))
        assertFalse(tracker.finish(id))
        assertFalse(tracker.isRunning)
    }

    @Test
    fun finishCanSucceedOnlyOnce() {
        val tracker = ExportRequestTracker()
        val id = tracker.begin()

        assertTrue(tracker.finish(id))
        assertFalse(tracker.finish(id))
    }

    @Test
    fun newExportCanStartAfterCancelAndOldCallbacksStayRejected() {
        val tracker = ExportRequestTracker()
        val first = tracker.begin()
        tracker.cancel()

        val second = tracker.begin()

        assertNotEquals(first, second)
        assertFalse(tracker.owns(first))
        assertTrue(tracker.owns(second))
        assertFalse(tracker.finish(first))
        assertTrue(tracker.finish(second))
    }

    @Test
    fun androidTimeoutIsACancelThatAlsoAllowsARetry() {
        val tracker = ExportRequestTracker()
        val timedOut = tracker.begin()

        tracker.cancel() // what the foreground-service timeout does

        assertFalse(tracker.finish(timedOut))
        val retry = tracker.begin()
        assertTrue(tracker.owns(retry))
    }

    @Test
    fun startingAnotherRequestSupersedesTheRunningOne() {
        val tracker = ExportRequestTracker()
        val older = tracker.begin()
        val newer = tracker.begin()

        assertFalse(tracker.owns(older))
        assertTrue(tracker.owns(newer))
        assertEquals(false, tracker.finish(older))
    }

    @Test
    fun cancelWithoutActiveRequestKeepsTrackerUsable() {
        val tracker = ExportRequestTracker()

        tracker.cancel()
        tracker.cancel()

        assertFalse(tracker.isRunning)
        assertTrue(tracker.owns(tracker.begin()))
    }
}
