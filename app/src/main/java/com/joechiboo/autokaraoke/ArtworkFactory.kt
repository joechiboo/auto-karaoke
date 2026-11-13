package com.joechiboo.autokaraoke

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * 產生印著歌名與歌手的封面圖。
 *
 * Android Auto 的「正在播放」畫面只有標題、副標題兩行文字，都拿去放歌詞了，
 * 歌名只剩封面這個位置可以放。顏色依歌名雜湊挑，每首歌看起來不一樣。
 *
 * 尺寸壓在 320px：每換一句歌詞 metadata 都會連同封面一起送給 Auto，圖太大會拖慢更新。
 */
object ArtworkFactory {

    private const val SIZE = 320
    private const val PADDING = 28f

    private val PALETTE = listOf(
        0xFF7B2FF7 to 0xFF2E1065,
        0xFFE11D48 to 0xFF4C0519,
        0xFF0EA5E9 to 0xFF082F49,
        0xFF16A34A to 0xFF052E16,
        0xFFF59E0B to 0xFF451A03,
        0xFFDB2777 to 0xFF500724,
        0xFF6366F1 to 0xFF1E1B4B,
    )

    fun create(title: String, artist: String?): ByteArray {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val (top, bottom) = PALETTE[abs(title.hashCode()) % PALETTE.size]
        canvas.drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, SIZE.toFloat(), SIZE.toFloat(),
                top.toInt(), bottom.toInt(), Shader.TileMode.CLAMP)
        })

        // 右上角的音符當裝飾
        canvas.drawText("♪", SIZE - PADDING - 40f, PADDING + 52f, TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 255, 255, 255)
            textSize = 64f
        })

        val width = (SIZE - PADDING * 2).toInt()

        val artistLayout = artist?.takeIf { it.isNotBlank() }?.let {
            layout(it, TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(210, 255, 255, 255)
                textSize = 26f
            }, width, maxLines = 1)
        }

        // 歌名：先用大字，排不下三行就縮小
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
        }
        var titleLayout: StaticLayout
        var size = 56f
        do {
            titlePaint.textSize = size
            titleLayout = layout(title, titlePaint, width, maxLines = 3)
            size -= 4f
        } while (size >= 32f && titleLayout.lineCount >= 3 && isEllipsized(titleLayout))

        // 文字貼齊左下角
        val gap = if (artistLayout != null) 10f else 0f
        val blockHeight = titleLayout.height + gap + (artistLayout?.height ?: 0)
        var y = SIZE - PADDING - blockHeight
        canvas.save(); canvas.translate(PADDING, y); titleLayout.draw(canvas); canvas.restore()
        y += titleLayout.height + gap
        artistLayout?.let { canvas.save(); canvas.translate(PADDING, y); it.draw(canvas); canvas.restore() }

        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int) =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    private fun isEllipsized(layout: StaticLayout) =
        (0 until layout.lineCount).any { layout.getEllipsisCount(it) > 0 }
}
