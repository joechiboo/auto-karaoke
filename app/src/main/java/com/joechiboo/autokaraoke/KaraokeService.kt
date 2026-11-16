package com.joechiboo.autokaraoke

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * 卡拉 OK 媒體服務。Android Auto 透過 MediaBrowserService 介面連進來瀏覽與控制播放。
 *
 * 歌詞顯示的作法：每當「目前這句」改變，就用 [Player.replaceMediaItem] 把目前這首的
 * MediaMetadata 換成新的（title＝目前句、subtitle＝下一句）。URI 不變時
 * ExoPlayer 只更新 metadata、不會重新載入音訊，Session 會把新 metadata 推給 Android Auto。
 *
 * 有副標題時 Android Auto 不顯示 artist，所以歌名與歌手印在封面上（見 [ArtworkFactory]）。
 * 播放畫面右下角的字幕按鈕可以關掉歌詞，關掉時標題／副標題換回歌名／歌手。
 * 預設整份歌單循環播放，循環按鈕可以關掉（播完最後一首就停）。
 */
class KaraokeService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var library: SongLibrary
    private lateinit var syncer: LyricSyncer

    /** 目前歌詞屬於哪首歌（mediaId）；用來判斷是否換歌。 */
    private var lyricsSongId: String? = null

    override fun onCreate() {
        super.onCreate()
        library = SongLibrary(assets)

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.repeatMode = if (repeatOn) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF

        // 校正值加在播放位置上：+500 代表歌詞提早半秒出現
        syncer = LyricSyncer(
            positionMs = { player.currentPosition + offsetMs(player.currentMediaItem?.mediaId) },
            onChange = ::showLyric,
        )
        player.addListener(PlayerListener())

        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setMediaButtonPreferences(buttons())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        syncer.stop()
        session.release()
        player.release()
        super.onDestroy()
    }

    // ---- 歌詞同步 ----

    /** 若目前播放的歌換了，載入新歌的歌詞；接著依目前位置同步。 */
    private fun refreshLyrics() {
        val id = player.currentMediaItem?.mediaId
        if (id != lyricsSongId) {
            lyricsSongId = id
            syncer.setLyrics(id?.let { library.find(it) }?.lyrics?.lines.orEmpty())
        } else {
            syncer.syncNow()
        }
    }

    private fun showLyric(current: LyricLine?, next: LyricLine?) {
        if (!lyricsOn) return
        val index = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return
        val song = library.find(item.mediaId) ?: return

        val title = when {
            current == null -> "♪ ${song.title}"   // 前奏，還沒到第一句
            current.text.isBlank() -> "♪"            // 間奏（LRC 裡的空白時間行）
            else -> current.text
        }
        val subtitle = when {
            SystemClock.elapsedRealtime() < noticeUntil -> noticeText  // 剛按了校正鈕，顯示目前校正值
            next == null -> ""
            next.text.isBlank() -> "♪"
            else -> next.text
        }

        val metadata = song.mediaItem.mediaMetadata.buildUpon()
            .setTitle(title)
            .setDisplayTitle(title)
            .setSubtitle(subtitle)
            .setArtist(song.title)
            .build()
        player.replaceMediaItem(index, song.mediaItem.buildUpon().setMediaMetadata(metadata).build())
    }

    // ---- 字幕開關 ----

    private val prefs by lazy { getSharedPreferences("karaoke", MODE_PRIVATE) }

    private var lyricsOn: Boolean
        get() = prefs.getBoolean(KEY_LYRICS_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_LYRICS_ON, value).apply()

    /** 循環播放（整份歌單）；預設開。 */
    private var repeatOn: Boolean
        get() = prefs.getBoolean(KEY_REPEAT_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_REPEAT_ON, value).apply()

    /**
     * 播放畫面上的自訂按鈕：字幕開時有「延後／提早」兩顆校正鈕＋字幕開關＋循環開關，
     * 字幕關時只剩兩個開關。
     */
    private fun buttons(): ImmutableList<CommandButton> {
        val toggle = CommandButton.Builder(
            if (lyricsOn) CommandButton.ICON_SUBTITLES else CommandButton.ICON_SUBTITLES_OFF
        )
            .setDisplayName(if (lyricsOn) "關閉字幕" else "開啟字幕")
            .setSessionCommand(TOGGLE_LYRICS)
            .build()
        val repeat = CommandButton.Builder(
            if (repeatOn) CommandButton.ICON_REPEAT_ALL else CommandButton.ICON_REPEAT_OFF
        )
            .setDisplayName(if (repeatOn) "關閉循環播放" else "開啟循環播放")
            .setSessionCommand(TOGGLE_REPEAT)
            .build()
        if (!lyricsOn) return ImmutableList.of(toggle, repeat)

        val later = CommandButton.Builder(CommandButton.ICON_MINUS)
            .setDisplayName("字幕延後 0.5 秒")
            .setSessionCommand(LYRICS_LATER)
            .build()
        val earlier = CommandButton.Builder(CommandButton.ICON_PLUS)
            .setDisplayName("字幕提早 0.5 秒")
            .setSessionCommand(LYRICS_EARLIER)
            .build()
        return ImmutableList.of(later, earlier, toggle, repeat)
    }

    private fun toggleRepeat() {
        // 存檔與更新按鈕交給 onRepeatModeChanged，別的控制端改循環模式時也會走同一條路
        player.repeatMode = if (repeatOn) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL
    }

    private fun toggleLyrics() {
        lyricsOn = !lyricsOn
        session.setMediaButtonPreferences(buttons())
        if (lyricsOn) {
            syncer.forceSync()
        } else {
            // 關字幕：標題換回歌名、副標題換回歌手
            val song = player.currentMediaItem?.let { library.find(it.mediaId) } ?: return
            player.replaceMediaItem(player.currentMediaItemIndex, song.mediaItem)
        }
    }

    // ---- 字幕時間校正（每首歌各自記住） ----

    private val handler = Handler(Looper.getMainLooper())
    private var noticeText = ""
    private var noticeUntil = 0L

    private fun offsetMs(songId: String?): Long =
        songId?.let { prefs.getLong(KEY_OFFSET_PREFIX + it, 0L) } ?: 0L

    private fun adjustOffset(deltaMs: Long) {
        val id = player.currentMediaItem?.mediaId ?: return
        val value = (offsetMs(id) + deltaMs).coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        prefs.edit().putLong(KEY_OFFSET_PREFIX + id, value).apply()

        noticeText = when {
            value > 0 -> "字幕提早 %.1f 秒".format(value / 1000.0)
            value < 0 -> "字幕延後 %.1f 秒".format(-value / 1000.0)
            else -> "字幕時間已歸零"
        }
        noticeUntil = SystemClock.elapsedRealtime() + NOTICE_MS
        syncer.forceSync()

        // 提示時間到就換回下一句歌詞；連按時只留最後一次的
        handler.removeCallbacks(clearNotice)
        handler.postDelayed(clearNotice, NOTICE_MS)
    }

    private val clearNotice = Runnable { syncer.forceSync() }

    private inner class PlayerListener : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refreshLyrics()

        override fun onTimelineChanged(timeline: Timeline, reason: Int) = refreshLyrics()

        // 拖曳進度、上一首/下一首、自動換歌都會觸發 → 立即同步，不等下一個 tick
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) = refreshLyrics()

        override fun onRepeatModeChanged(repeatMode: Int) {
            repeatOn = repeatMode != Player.REPEAT_MODE_OFF
            session.setMediaButtonPreferences(buttons())
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) syncer.start() else syncer.stop()
            refreshLyrics()
        }
    }

    // ---- Android Auto 瀏覽樹 ----

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                        .add(TOGGLE_LYRICS)
                        .add(LYRICS_EARLIER)
                        .add(LYRICS_LATER)
                        .add(TOGGLE_REPEAT)
                        .build()
                )
                .setMediaButtonPreferences(buttons())
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                TOGGLE_LYRICS.customAction -> toggleLyrics()
                LYRICS_EARLIER.customAction -> adjustOffset(+OFFSET_STEP_MS)
                LYRICS_LATER.customAction -> adjustOffset(-OFFSET_STEP_MS)
                TOGGLE_REPEAT.customAction -> toggleRepeat()
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(library.rootItem, params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val children: List<MediaItem> = when (parentId) {
                // 每份歌單是一個資料夾，Android Auto 顯示成上方分頁
                SongLibrary.ROOT_ID -> library.playlists.map { it.folder }
                SongLibrary.SONGS_ID -> library.main.items.ifEmpty { listOf(library.emptyHint) }
                else -> library.playlist(parentId)?.items
                    ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            }
            val paged = if (pageSize in 1 until Int.MAX_VALUE) {
                children.drop(page * pageSize).take(pageSize)
            } else {
                children
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(paged, params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = when (mediaId) {
                SongLibrary.ROOT_ID -> library.rootItem
                else -> library.playlist(mediaId)?.folder ?: library.find(mediaId)?.mediaItem
            }
            return Futures.immediateFuture(
                if (item != null) LibraryResult.ofItem(item, null)
                else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            )
        }

        /** Android Auto 只帶 mediaId 過來，這裡補上 URI 等完整資訊。 */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(
                mediaItems.mapNotNull { library.find(it.mediaId)?.mediaItem }.toMutableList()
            )

        /**
         * 在列表點一首歌時，把「那首歌所在的整份歌單」放進播放清單並從那首開始，
         * 這樣上一首／下一首才有東西可以切，循環也只在這份歌單裡轉。
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (mediaItems.size == 1) {
                val id = mediaItems[0].mediaId
                val playlist = library.playlistOf(id)
                if (playlist != null) {
                    val index = playlist.songs.indexOfFirst { it.id == id }
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(playlist.items, index, startPositionMs)
                    )
                }
            }
            val resolved = mediaItems.mapNotNull { library.find(it.mediaId)?.mediaItem }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(resolved, startIndex, startPositionMs)
            )
        }

        /** 冷啟動時直接按播放（例如上車後 Auto 的播放鍵）：從主歌單第一首開始。 */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(library.main.items, 0, C.TIME_UNSET)
            )
    }

    companion object {
        val TOGGLE_LYRICS = SessionCommand("com.joechiboo.autokaraoke.TOGGLE_LYRICS", Bundle.EMPTY)
        val LYRICS_EARLIER = SessionCommand("com.joechiboo.autokaraoke.LYRICS_EARLIER", Bundle.EMPTY)
        val LYRICS_LATER = SessionCommand("com.joechiboo.autokaraoke.LYRICS_LATER", Bundle.EMPTY)
        val TOGGLE_REPEAT = SessionCommand("com.joechiboo.autokaraoke.TOGGLE_REPEAT", Bundle.EMPTY)
        private const val KEY_LYRICS_ON = "lyrics_on"
        private const val KEY_REPEAT_ON = "repeat_on"
        private const val KEY_OFFSET_PREFIX = "offset_"
        private const val OFFSET_STEP_MS = 500L
        private const val MAX_OFFSET_MS = 30_000L
        private const val NOTICE_MS = 2_000L
    }
}
