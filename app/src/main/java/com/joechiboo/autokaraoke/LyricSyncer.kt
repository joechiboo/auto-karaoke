package com.joechiboo.autokaraoke

import android.os.Handler
import android.os.Looper

/**
 * 依播放位置找出「目前這句」與「下一句」歌詞。
 *
 * - 播放中每 [intervalMs] 檢查一次（主執行緒 Handler，Player 只能在主執行緒呼叫）
 * - 只有句子換了才呼叫 [onChange]，避免一直刷 metadata
 * - 拖曳進度後呼叫 [syncNow] 立即同步，不用等下一個 tick
 *
 * [onChange] 的 current 為 null 代表還沒到第一句（前奏）；next 為 null 代表已是最後一句。
 */
class LyricSyncer(
    private val positionMs: () -> Long,
    private val onChange: (current: LyricLine?, next: LyricLine?) -> Unit,
    private val intervalMs: Long = 200L,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var lines: List<LyricLine> = emptyList()
    private var lastIndex = UNSET
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            syncNow()
            if (running) handler.postDelayed(this, intervalMs)
        }
    }

    /** 換歌時呼叫；會立刻依目前位置送出一次 [onChange]（沒有歌詞就不送）。 */
    fun setLyrics(newLines: List<LyricLine>) {
        lines = newLines
        lastIndex = UNSET
        syncNow()
    }

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    /** 不管句子有沒有變，都依目前位置重送一次（例如字幕剛打開）。 */
    fun forceSync() {
        lastIndex = UNSET
        syncNow()
    }

    /** 立即依目前位置同步；句子沒變就什麼都不做。 */
    fun syncNow() {
        if (lines.isEmpty()) return
        val index = indexAt(lines, positionMs())
        if (index == lastIndex) return
        lastIndex = index
        onChange(lines.getOrNull(index), lines.getOrNull(index + 1))
    }

    companion object {
        private const val UNSET = Int.MIN_VALUE

        /**
         * 回傳 timeMs <= [positionMs] 的最後一句的 index；還沒到第一句回傳 -1。
         * [lines] 必須已依時間排序（[LrcParser] 的輸出就是）。
         */
        fun indexAt(lines: List<LyricLine>, positionMs: Long): Int {
            var lo = 0
            var hi = lines.size - 1
            var ans = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (lines[mid].timeMs <= positionMs) {
                    ans = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return ans
        }
    }
}
