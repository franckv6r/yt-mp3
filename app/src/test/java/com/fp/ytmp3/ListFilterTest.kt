package com.fp.ytmp3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ListFilterTest {

    private val names = listOf("Ben Harper - Jah Work", "Amy Winehouse - No Good", "No Sinner - Love")

    @Test
    fun emptyQueryKeepsAllItems() {
        assertSame(names, filterByQuery(names, "  ") { it })
    }

    @Test
    fun queryIsCaseInsensitiveAndTrimmed() {
        assertEquals(
            listOf("Amy Winehouse - No Good"),
            filterByQuery(names, "  AMY  ") { it }
        )
    }

    @Test
    fun accentsAreIgnored() {
        val list = listOf("Écoute", "Autre")
        assertEquals(listOf("Écoute"), filterByQuery(list, "ecoute") { it })
    }

    @Test
    fun noMatchReturnsEmptyList() {
        assertEquals(emptyList<String>(), filterByQuery(names, "glitter") { it })
    }
}
