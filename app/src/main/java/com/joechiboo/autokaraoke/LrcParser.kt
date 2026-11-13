package com.joechiboo.autokaraoke

/** 一句歌詞：從 [timeMs] 開始顯示 [text]。 */
data class LyricLine(val timeMs: Long, val text: String)

/**
 * 解析後的歌詞。[lines] 已依時間排序（同時間保留檔案中的先後順序）。
 * [title]、[artist] 來自 `[ti:]`、`[ar:]` 標籤，沒有就是 null。
 */
data class Lyrics(
    val lines: List<LyricLine>,
    val title: String? = null,
    val artist: String? = null,
) {
    companion object {
        val EMPTY = Lyrics(emptyList())
    }
}

/**
 * LRC 歌詞解析器（純 Kotlin，不依賴 Android）。
 *
 * 支援：
 * - `[mm:ss]`、`[mm:ss.x]`、`[mm:ss.xx]`、`[mm:ss.xxx]`，小數點也接受 `:`（`[mm:ss:xx]`）
 * - 一行多個時間標籤：`[00:12.00][01:30.50]副歌` 會展開成兩句
 * - `[ti:]`、`[ar:]` 標籤，以及 `[offset:±ms]`（正值＝歌詞提早出現）
 * - 其他 `[xx:...]` 中繼標籤、沒有時間標籤的行一律略過
 * - 開頭的 UTF-8 BOM、Windows 換行
 */
object LrcParser {

    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val META_TAG = Regex("""^\[([A-Za-z]+):(.*)]\s*$""")

    fun parse(content: String): Lyrics {
        var title: String? = null
        var artist: String? = null
        var offsetMs = 0L
        val timed = mutableListOf<LyricLine>()

        content.removePrefix("﻿").lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach

            val times = mutableListOf<Long>()
            var pos = 0
            while (true) {
                val m = TIME_TAG.matchAt(line, pos) ?: break
                times += toMillis(m)
                pos = m.range.last + 1
            }

            if (times.isNotEmpty()) {
                val text = line.substring(pos).trim()
                times.forEach { timed += LyricLine(it, text) }
                return@forEach
            }

            META_TAG.matchEntire(line)?.let { m ->
                val value = m.groupValues[2].trim()
                when (m.groupValues[1].lowercase()) {
                    "ti" -> title = value.ifEmpty { null }
                    "ar" -> artist = value.ifEmpty { null }
                    "offset" -> offsetMs = value.toLongOrNull() ?: 0L
                }
            }
        }

        val lines = timed
            .map { it.copy(timeMs = (it.timeMs - offsetMs).coerceAtLeast(0L)) }
            .sortedBy { it.timeMs } // sortedBy 是穩定排序
        return Lyrics(lines, title, artist)
    }

    private fun toMillis(m: MatchResult): Long {
        val min = m.groupValues[1].toLong()
        val sec = m.groupValues[2].toLong()
        val frac = m.groupValues[3]
        // 1 位＝十分之一秒、2 位＝百分之一秒、3 位＝毫秒
        val fracMs = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.toLong()
        }
        return (min * 60 + sec) * 1000 + fracMs
    }
}
