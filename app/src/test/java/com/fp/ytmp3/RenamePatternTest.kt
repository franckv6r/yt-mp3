package com.fp.ytmp3

import org.junit.Assert.assertEquals
import org.junit.Test

class RenamePatternTest {

    private fun m(
        year: Int? = 1997,
        artist: String = "Ben Harper",
        title: String = "Jah Work",
        album: String? = "The Will to Live"
    ) = Meta(artist, title, year, album, "rgid", null, true)

    @Test
    fun defaultPattern() {
        assertEquals(
            listOf("1997 - Ben Harper - Jah Work.mp3"),
            RenamePattern.render(RenamePattern.DEFAULT, m())
        )
    }

    @Test
    fun missingYearCollapsesDanglingSeparator() {
        assertEquals(
            listOf("Ben Harper - Jah Work.mp3"),
            RenamePattern.render(RenamePattern.DEFAULT, m(year = null))
        )
    }

    @Test
    fun slashesCreateSubfolders() {
        assertEquals(
            listOf("Ben Harper", "The Will to Live", "Jah Work.mp3"),
            RenamePattern.render("%artist%/%album%/%title%", m())
        )
    }

    @Test
    fun missingAlbumDropsFolder() {
        assertEquals(
            listOf("Ben Harper", "Jah Work.mp3"),
            RenamePattern.render("%artist%/%album%/%title%", m(album = null))
        )
    }

    @Test
    fun specialCharactersAreSanitized() {
        val meta = m(artist = "AC/DC", title = "Live: \"Best\"?", album = "Rock")
        assertEquals(
            listOf("AC_DC", "Rock", "Live_ _Best__.mp3"),
            RenamePattern.render("%artist%/%album%/%title%", meta)
        )
    }

    @Test
    fun reservedWindowsNamesArePrefixed() {
        assertEquals(listOf("_CON.mp3"), RenamePattern.render("%title%", m(title = "CON")))
    }

    @Test
    fun hiddenNamesAreNotHidden() {
        assertEquals(listOf("hidden.mp3"), RenamePattern.render("%title%", m(title = ".hidden")))
    }

    @Test
    fun emptyTitleFallsBack() {
        assertEquals(listOf("inconnu.mp3"), RenamePattern.render("%title%", m(title = "")))
    }

    @Test
    fun explicitExtensionIsKept() {
        assertEquals(listOf("Jah Work.mp3"), RenamePattern.render("%title%.mp3", m()))
    }
}
