package com.joechiboo.autokaraoke

import org.junit.Assert.assertEquals
import org.junit.Test

/** [SongLibrary.applyOrder]：order.txt 決定歌單順序。 */
class SongOrderTest {

    private val ids = listOf("c", "a", "d", "b")

    @Test
    fun noOrderFileSortsByName() {
        assertEquals(listOf("a", "b", "c", "d"), SongLibrary.applyOrder(ids, null))
    }

    @Test
    fun starPlacesUnlistedSongs() {
        assertEquals(listOf("a", "c", "d", "b"), SongLibrary.applyOrder(ids, "*\nb\n"))
        assertEquals(listOf("d", "a", "b", "c"), SongLibrary.applyOrder(ids, "d\n*"))
    }

    @Test
    fun withoutStarUnlistedGoLast() {
        assertEquals(listOf("d", "b", "a", "c"), SongLibrary.applyOrder(ids, "d\nb"))
    }

    @Test
    fun ignoresCommentsBlankMissingDuplicatesAndBom() {
        val text = "﻿# 註解\n\n  b  \nzzz\nb\n*\n"
        assertEquals(listOf("b", "a", "c", "d"), SongLibrary.applyOrder(ids, text))
    }
}
