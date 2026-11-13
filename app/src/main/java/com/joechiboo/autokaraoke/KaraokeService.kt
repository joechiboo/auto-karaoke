package com.joechiboo.autokaraoke

import android.os.Bundle
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

        syncer = LyricSyncer(positionMs = { player.currentPosition }, onChange = ::showLyric)
        player.addListener(PlayerListener())

        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setMediaButtonPreferences(ImmutableList.of(lyricsButton()))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onDestroy() {
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

    private fun lyricsButton(): CommandButton =
        CommandButton.Builder(if (lyricsOn) CommandButton.ICON_SUBTITLES else CommandButton.ICON_SUBTITLES_OFF)
            .setDisplayName(if (lyricsOn) "關閉字幕" else "開啟字幕")
            .setSessionCommand(TOGGLE_LYRICS)
            .build()

    private fun toggleLyrics() {
        lyricsOn = !lyricsOn
        session.setMediaButtonPreferences(ImmutableList.of(lyricsButton()))
        if (lyricsOn) {
            syncer.forceSync()
        } else {
            // 關字幕：標題換回歌名、副標題換回歌手
            val song = player.currentMediaItem?.let { library.find(it.mediaId) } ?: return
            player.replaceMediaItem(player.currentMediaItemIndex, song.mediaItem)
        }
    }

    private inner class PlayerListener : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refreshLyrics()

        override fun onTimelineChanged(timeline: Timeline, reason: Int) = refreshLyrics()

        // 拖曳進度、上一首/下一首、自動換歌都會觸發 → 立即同步，不等下一個 tick
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) = refreshLyrics()

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
                        .build()
                )
                .setMediaButtonPreferences(ImmutableList.of(lyricsButton()))
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == TOGGLE_LYRICS.customAction) {
                toggleLyrics()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
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
                SongLibrary.ROOT_ID -> listOf(library.songsFolder)
                SongLibrary.SONGS_ID -> library.songItems.ifEmpty { listOf(library.emptyHint) }
                else -> return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
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
                SongLibrary.SONGS_ID -> library.songsFolder
                else -> library.find(mediaId)?.mediaItem
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
         * 在列表點一首歌時，把「整份歌單」放進播放清單並從那首開始，
         * 這樣上一首／下一首才有東西可以切。
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (mediaItems.size == 1) {
                val index = library.indexOf(mediaItems[0].mediaId)
                if (index >= 0) {
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(library.songItems, index, startPositionMs)
                    )
                }
            }
            val resolved = mediaItems.mapNotNull { library.find(it.mediaId)?.mediaItem }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(resolved, startIndex, startPositionMs)
            )
        }

        /** 冷啟動時直接按播放（例如上車後 Auto 的播放鍵）：從第一首開始。 */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(library.songItems, 0, C.TIME_UNSET)
            )
    }

    companion object {
        val TOGGLE_LYRICS = SessionCommand("com.joechiboo.autokaraoke.TOGGLE_LYRICS", Bundle.EMPTY)
        private const val KEY_LYRICS_ON = "lyrics_on"
    }
}
