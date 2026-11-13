package com.joechiboo.autokaraoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun parsesBasicTimeTags() {
        val lyrics = LrcParser.parse(
            """
            [00:01.50]第一句
            [00:05.25]第二句
            """.trimIndent()
        )
        assertEquals(
            listOf(LyricLine(1_500, "第一句"), LyricLine(5_250, "第二句")),
            lyrics.lines,
        )
    }

    @Test
    fun handlesFractionPrecision() {
        val lyrics = LrcParser.parse(
            """
            [00:01]a
            [00:02.5]b
            [00:03.25]c
            [00:04.125]d
            [00:05:50]e
            [01:00.00]f
            [100:00.00]g
            """.trimIndent()
        )
        assertEquals(
            listOf(1_000L, 2_500L, 3_250L, 4_125L, 5_500L, 60_000L, 6_000_000L),
            lyrics.lines.map { it.timeMs },
        )
    }

    @Test
    fun expandsMultipleTimeTagsAndSorts() {
        val lyrics = LrcParser.parse(
            """
            [00:10.00][00:40.00]副歌
            [00:20.00]主歌二
            [00:00.00]開頭
            """.trimIndent()
        )
        assertEquals(
            listOf(
                LyricLine(0, "開頭"),
                LyricLine(10_000, "副歌"),
                LyricLine(20_000, "主歌二"),
                LyricLine(40_000, "副歌"),
            ),
            lyrics.lines,
        )
    }

    @Test
    fun readsMetadataAndIgnoresUnknownTags() {
        val lyrics = LrcParser.parse(
            """
            [ti:測試歌]
            [ar:某歌手]
            [al:專輯]
            [by:someone]
            [00:01.00]唯一一句
            """.trimIndent()
        )
        assertEquals("測試歌", lyrics.title)
        assertEquals("某歌手", lyrics.artist)
        assertEquals(listOf(LyricLine(1_000, "唯一一句")), lyrics.lines)
    }

    @Test
    fun appliesOffset() {
        // 正 offset ＝ 歌詞提早出現；不會變成負數
        val lyrics = LrcParser.parse(
            """
            [offset:+500]
            [00:00.20]a
            [00:02.00]b
            """.trimIndent()
        )
        assertEquals(listOf(0L, 1_500L), lyrics.lines.map { it.timeMs })
    }

    @Test
    fun keepsEmptyTextLinesAsInterludes() {
        val lyrics = LrcParser.parse("[00:01.00]唱\n[00:03.00]\n[00:05.00]再唱")
        assertEquals("", lyrics.lines[1].text)
        assertEquals(3, lyrics.lines.size)
    }

    @Test
    fun skipsLinesWithoutTimeTagsAndHandlesBomAndCrlf() {
        val lyrics = LrcParser.parse("﻿[ti:x]\r\n純文字行\r\n\r\n[00:01.00]有時間\r\n")
        assertEquals("x", lyrics.title)
        assertEquals(listOf(LyricLine(1_000, "有時間")), lyrics.lines)
    }

    @Test
    fun keepsBracketsInsideLyricText() {
        val lyrics = LrcParser.parse("[00:01.00]合唱 [男] 一起")
        assertEquals("合唱 [男] 一起", lyrics.lines.single().text)
    }

    @Test
    fun emptyInputGivesEmptyLyrics() {
        val lyrics = LrcParser.parse("")
        assertTrue(lyrics.lines.isEmpty())
        assertNull(lyrics.title)
    }
}
