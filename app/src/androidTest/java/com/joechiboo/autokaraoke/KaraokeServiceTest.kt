package com.joechiboo.autokaraoke

import android.content.ComponentName
import android.media.MediaMetadata as FwMetadata
import android.media.browse.MediaBrowser as FwBrowser
import android.media.session.MediaController as FwController
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 實機端對端測試：連進 KaraokeService，驗證瀏覽樹、歌詞同步、拖曳、上一首/下一首。
 *
 * 需要 assets/songs/ 裡有 sample.mp3 與 sample2.mp3（README 有產生測試音檔的 ffmpeg 指令），
 * 沒有就跳過。執行：./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class KaraokeServiceTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val component = ComponentName(ctx, KaraokeService::class.java)

    @Before
    fun requireTestSongs() {
        val files = ctx.assets.list("songs").orEmpty().toSet()
        assumeTrue("需要 sample.mp3 與 sample2.mp3", "sample.mp3" in files && "sample2.mp3" in files)
    }

    // ---------- media3 MediaBrowser ----------

    private var browser: MediaBrowser? = null

    @After
    fun tearDown() {
        browser?.let { b -> onMain { b.stop(); b.release() } }
    }

    @Test
    fun browseTree_listsSongs() {
        val b = connectMedia3()
        val root = onMain { b.getLibraryRoot(null) }.get(5, TimeUnit.SECONDS).value!!
        val folders = onMain { b.getChildren(root.mediaId, 0, 100, null) }.get(5, TimeUnit.SECONDS).value!!
        assertEquals(1, folders.size)
        val songs = onMain { b.getChildren(folders[0].mediaId, 0, 100, null) }.get(5, TimeUnit.SECONDS).value!!
        val ids = songs.map { it.mediaId }
        assertTrue(ids.toString(), ids.containsAll(listOf("sample", "sample2")))
        assertEquals("測試之歌", songs.first { it.mediaId == "sample" }.mediaMetadata.title.toString())
        assertTrue(songs.all { it.mediaMetadata.isPlayable == true })
    }

    @Test
    fun playback_syncsLyricsSeeksAndSkips() {
        val b = connectMedia3()
        onMain {
            b.setMediaItem(MediaItem.Builder().setMediaId("sample").build())
            b.prepare()
            b.play()
        }
        waitUntil("開始播放") { onMain { b.isPlaying } }

        // 點一首歌要把整份歌單放進佇列
        assertTrue(onMain { b.mediaItemCount } >= 2)

        // 前奏（00:00 那句）
        waitUntil("前奏") { onMain { b.mediaMetadata.title?.toString() } == "（前奏）" }
        assertEquals("引擎輕輕地發動", onMain { b.mediaMetadata.subtitle?.toString() })
        assertEquals("測試之歌", onMain { b.mediaMetadata.artist?.toString() })

        // 自然播到 00:04
        waitUntil("播到第一句", timeoutMs = 7_000) { onMain { b.mediaMetadata.title?.toString() } == "引擎輕輕地發動" }
        assertEquals("導航說往前直走", onMain { b.mediaMetadata.subtitle?.toString() })

        // 拖曳到 00:30 → 「下一個交流道」，下一句是 00:32 的空白間奏 → ♪
        var t0 = SystemClock.elapsedRealtime()
        onMain { b.seekTo(30_000) }
        waitUntil("拖曳到 30 秒") { onMain { b.mediaMetadata.title?.toString() } == "下一個交流道 還不想下來" }
        Log.i(TAG, "seek → 歌詞更新耗時 ${SystemClock.elapsedRealtime() - t0} ms")
        assertEquals("♪", onMain { b.mediaMetadata.subtitle?.toString() })

        // 拖曳到間奏 00:33 → 標題 ♪
        onMain { b.seekTo(33_000) }
        waitUntil("間奏") { onMain { b.mediaMetadata.title?.toString() } == "♪" }
        assertEquals("雨刷左右在合聲", onMain { b.mediaMetadata.subtitle?.toString() })

        // 往回拖到 00:53 → 一行多時間標籤的第二次出現
        t0 = SystemClock.elapsedRealtime()
        onMain { b.seekTo(53_000) }
        waitUntil("多時間標籤") { onMain { b.mediaMetadata.title?.toString() } == "唱吧 唱吧 方向盤上打拍子" }
        Log.i(TAG, "seek → 歌詞更新耗時 ${SystemClock.elapsedRealtime() - t0} ms")
        assertEquals("唱吧 唱吧 高速公路是舞台", onMain { b.mediaMetadata.subtitle?.toString() })

        // 暫停：歌詞停在原句
        onMain { b.pause() }
        val pausedTitle = onMain { b.mediaMetadata.title?.toString() }
        SystemClock.sleep(1_000)
        assertEquals(pausedTitle, onMain { b.mediaMetadata.title?.toString() })

        // 下一首
        onMain { b.seekToNextMediaItem(); b.play() }
        waitUntil("下一首") { onMain { b.mediaMetadata.artist?.toString() } == "測試之歌二" }
        waitUntil("下一首第一句") { onMain { b.mediaMetadata.title?.toString() } == "第二首 第一句" }

        // 上一首（播放 < 3 秒時回到前一首）
        onMain { b.seekToPreviousMediaItem() }
        waitUntil("上一首") { onMain { b.mediaMetadata.artist?.toString() } == "測試之歌" }
        onMain { b.pause() }
    }

    // ---------- 框架 MediaBrowser（Android Auto 用的舊介面）----------

    @Test
    fun legacyBrowser_seesTreeAndLyricMetadata() {
        val connected = CountDownLatch(1)
        lateinit var fb: FwBrowser
        onMain {
            fb = FwBrowser(ctx, component, object : FwBrowser.ConnectionCallback() {
                override fun onConnected() = connected.countDown()
            }, null)
            fb.connect()
        }
        assertTrue("legacy 連線逾時", connected.await(10, TimeUnit.SECONDS))
        try {
            val folders = subscribe(fb, fb.root)
            val songs = subscribe(fb, folders.single().mediaId!!)
            assertTrue(songs.map { it.mediaId }.containsAll(listOf("sample", "sample2")))

            val controller = FwController(ctx, fb.sessionToken)
            val tc = controller.transportControls
            onMain { tc.playFromMediaId("sample", null) }
            waitUntil("legacy 前奏") {
                controller.metadata?.getString(FwMetadata.METADATA_KEY_DISPLAY_TITLE) == "（前奏）"
            }

            onMain { tc.seekTo(41_000) }
            waitUntil("legacy 拖曳") {
                controller.metadata?.getString(FwMetadata.METADATA_KEY_DISPLAY_TITLE) == "後照鏡裡夕陽紅"
            }
            val md = controller.metadata!!
            assertEquals("副駕駛座空空的", md.getString(FwMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
            assertEquals("測試之歌", md.getString(FwMetadata.METADATA_KEY_ARTIST))
            assertEquals("後照鏡裡夕陽紅", md.getString(FwMetadata.METADATA_KEY_TITLE))
            Log.i(TAG, "legacy metadata: title=${md.getString(FwMetadata.METADATA_KEY_TITLE)} " +
                "subtitle=${md.getString(FwMetadata.METADATA_KEY_DISPLAY_SUBTITLE)} " +
                "duration=${md.getLong(FwMetadata.METADATA_KEY_DURATION)}")
            onMain { tc.pause() }
        } finally {
            onMain { fb.disconnect() }
        }
    }

    // ---------- helpers ----------

    private fun connectMedia3(): MediaBrowser {
        val token = SessionToken(ctx, component)
        val future = onMain {
            MediaBrowser.Builder(ctx, token).setApplicationLooper(Looper.getMainLooper()).buildAsync()
        }
        return future.get(10, TimeUnit.SECONDS).also { browser = it }
    }

    private fun subscribe(fb: FwBrowser, parentId: String): List<FwBrowser.MediaItem> {
        val latch = CountDownLatch(1)
        var result: List<FwBrowser.MediaItem> = emptyList()
        onMain {
            fb.subscribe(parentId, object : FwBrowser.SubscriptionCallback() {
                override fun onChildrenLoaded(id: String, children: MutableList<FwBrowser.MediaItem>) {
                    result = children
                    latch.countDown()
                }
            })
        }
        assertTrue("subscribe $parentId 逾時", latch.await(5, TimeUnit.SECONDS))
        onMain { fb.unsubscribe(parentId) }
        return result
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instr.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitUntil(what: String, timeoutMs: Long = 3_000, cond: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (cond()) return
            SystemClock.sleep(50)
        }
        throw AssertionError("等待逾時：$what")
    }

    companion object {
        private const val TAG = "KaraokeServiceTest"
    }
}
