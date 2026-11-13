package com.joechiboo.autokaraoke

import org.junit.Assert.assertEquals
import org.junit.Test

/** 只測純邏輯的 [LyricSyncer.indexAt]；Handler 計時部分需要 Android 環境。 */
class LyricSyncerTest {

    private val lines = listOf(
        LyricLine(1_000, "a"),
        LyricLine(3_000, "b"),
        LyricLine(3_000, "b2"),
        LyricLine(7_000, "c"),
    )

    @Test
    fun beforeFirstLineIsMinusOne() {
        assertEquals(-1, LyricSyncer.indexAt(lines, 0))
        assertEquals(-1, LyricSyncer.indexAt(lines, 999))
    }

    @Test
    fun exactBoundaryBelongsToNewLine() {
        assertEquals(0, LyricSyncer.indexAt(lines, 1_000))
    }

    @Test
    fun betweenLinesPicksEarlierOne() {
        assertEquals(0, LyricSyncer.indexAt(lines, 2_999))
        assertEquals(3, LyricSyncer.indexAt(lines, 7_500))
    }

    @Test
    fun sameTimestampPicksLastOfGroup() {
        assertEquals(2, LyricSyncer.indexAt(lines, 3_000))
    }

    @Test
    fun seekingBackwardWorks() {
        assertEquals(3, LyricSyncer.indexAt(lines, 60_000))
        assertEquals(0, LyricSyncer.indexAt(lines, 1_200))
    }

    @Test
    fun emptyListIsMinusOne() {
        assertEquals(-1, LyricSyncer.indexAt(emptyList(), 5_000))
    }
}
