package com.app.videoeditor

/**
 * Pure bookkeeping for export requests. Every export gets an id; only the request that currently
 * "owns" the export may change UI state or publish a file. Cancelling, timing out or starting a
 * new export invalidates every older request, so late callbacks (preflight, Transformer, gallery
 * save) can never overwrite a newer state or publish a cancelled video.
 */
class ExportRequestTracker {
    private var current = 0L
    private var running = false

    /** True while a request is active (preflight, rendering or saving). */
    val isRunning: Boolean get() = running

    /** Starts a new request and returns its id. Any older request stops owning the export. */
    fun begin(): Long {
        current++
        running = true
        return current
    }

    /** True only for the active request. */
    fun owns(id: Long): Boolean = running && id == current

    /** Cancels the active request (user cancel, Android timeout, ViewModel cleared). */
    fun cancel() {
        current++
        running = false
    }

    /**
     * Called by the owning request when it has a final result. Returns true exactly once, and
     * only if the request was not cancelled or superseded in the meantime.
     */
    fun finish(id: Long): Boolean {
        if (!owns(id)) return false
        running = false
        return true
    }
}
