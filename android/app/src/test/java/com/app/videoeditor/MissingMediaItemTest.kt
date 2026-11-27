package com.app.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MissingMediaItemTest {
    @Test
    fun relinkLookupUsesKindAndIdTogether() {
        val clip = MissingMediaItem("clip", 42L, "Main clip")
        val audio = MissingMediaItem("audio", 42L, "Music")
        val items = listOf(clip, audio)

        assertSame(clip, findMissingMediaItem(items, "clip", 42L))
        assertSame(audio, findMissingMediaItem(items, "audio", 42L))
        assertNull(findMissingMediaItem(items, "overlay", 42L))
        assertNull(findMissingMediaItem(items, "audio", 7L))
    }

    @Test
    fun missingMediaKeepsOriginalNameAndKind() {
        val item = MissingMediaItem("overlay", 9L, "Picture-in-picture")

        assertEquals("overlay", item.kind)
        assertEquals(9L, item.id)
        assertEquals("Picture-in-picture", item.name)
    }
}
