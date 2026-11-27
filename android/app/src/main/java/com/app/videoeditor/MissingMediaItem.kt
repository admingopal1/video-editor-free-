package com.app.videoeditor

/** A project source that could not be opened during recovery. */
data class MissingMediaItem(val kind: String, val id: Long, val name: String)

/** Match both kind and ID because independent media lanes may reuse numeric IDs. */
fun findMissingMediaItem(items: List<MissingMediaItem>, kind: String, id: Long): MissingMediaItem? =
    items.firstOrNull { it.kind == kind && it.id == id }
