package com.joechiboo.autokaraoke

import android.content.res.AssetManager
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants

/** 一首歌：assets/songs/[<歌單>/]<檔名>.mp3，搭配同名 .lrc（可以沒有）。 */
data class Song(
    /** 根目錄的歌是檔名；子資料夾的歌是「歌單/檔名」。也是校正值存檔的 key。 */
    val id: String,
    val title: String,
    val artist: String?,
    val lyrics: Lyrics,
    /** 瀏覽列表與播放清單用的「原始」MediaItem（標題＝歌名）。 */
    val mediaItem: MediaItem,
)

/** 一份歌單：Android Auto 上方的一個分頁。 */
data class Playlist(
    val id: String,
    val title: String,
    val songs: List<Song>,
    val folder: MediaItem,
) {
    val items: List<MediaItem> get() = songs.map { it.mediaItem }
}

/**
 * 掃描 assets/songs/ 建立歌單與 Android Auto 的瀏覽樹。
 *
 * - songs/ 根目錄的歌是主歌單（分頁「歌曲」），一定排第一個
 * - songs/ 底下每個子資料夾是另一份歌單，資料夾名就是分頁名（例如 songs/小孩/）
 * - 每份歌單各自排序：預設照檔名；資料夾裡有 order.txt 時照它排（見 [applyOrder]）
 */
class SongLibrary(private val assets: AssetManager) {

    val playlists: List<Playlist>
    private val byId: Map<String, Song>
    private val playlistById: Map<String, Playlist>
    private val playlistBySong: Map<String, Playlist>

    init {
        val entries = assets.list(DIR)?.toList().orEmpty()
        val main = loadPlaylist(SONGS_ID, MAIN_TITLE, DIR, idPrefix = "", entries)
        // 子資料夾：assets.list 對檔案回傳空陣列，對資料夾回傳內容
        val subs = entries.sorted().mapNotNull { name ->
            val path = "$DIR/$name"
            val children = assets.list(path)?.toList().orEmpty()
            if (children.isEmpty()) return@mapNotNull null
            loadPlaylist(PLAYLIST_PREFIX + name, name, path, idPrefix = "$name/", children)
                .takeIf { it.songs.isNotEmpty() }
        }
        playlists = listOf(main) + subs
        byId = playlists.flatMap { it.songs }.associateBy { it.id }
        playlistById = playlists.associateBy { it.id }
        playlistBySong = playlists.flatMap { pl -> pl.songs.map { it.id to pl } }.toMap()
    }

    val main: Playlist get() = playlists.first()

    fun find(mediaId: String): Song? = byId[mediaId]

    fun playlist(id: String): Playlist? = playlistById[id]

    /** 這首歌屬於哪份歌單（點歌時用來決定播放清單）。 */
    fun playlistOf(songId: String): Playlist? = playlistBySong[songId]

    val rootItem: MediaItem = folder(ROOT_ID, "root")

    /** 主歌單沒放 mp3 時顯示的提示項目（不可播放）。 */
    val emptyHint: MediaItem = MediaItem.Builder()
        .setMediaId(EMPTY_HINT_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("還沒有歌曲")
                .setSubtitle("把 mp3 放進 app/src/main/assets/songs/ 再重新編譯")
                .setIsBrowsable(false)
                .setIsPlayable(false)
                .build()
        )
        .build()

    private fun loadPlaylist(id: String, title: String, path: String, idPrefix: String, files: List<String>): Playlist {
        val lrcByBase = files
            .filter { it.endsWith(".lrc", ignoreCase = true) }
            .associateBy { it.substringBeforeLast('.').lowercase() }
        val mp3ByBase = files
            .filter { it.endsWith(".mp3", ignoreCase = true) }
            .associateBy { it.substringBeforeLast('.') }
        val orderText = if (ORDER_FILE in files) readText("$path/$ORDER_FILE") else null

        val songs = applyOrder(mp3ByBase.keys.toList(), orderText).map { base ->
            val lyrics = lrcByBase[base.lowercase()]
                ?.let { readText("$path/$it") }
                ?.let(LrcParser::parse)
                ?: Lyrics.EMPTY
            val songId = idPrefix + base
            val songTitle = lyrics.title ?: base
            Song(songId, songTitle, lyrics.artist, lyrics,
                buildSongItem(songId, "$path/${mp3ByBase.getValue(base)}", songTitle, lyrics.artist))
        }
        return Playlist(id, title, songs, folder(id, title))
    }

    private fun readText(path: String) =
        assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun buildSongItem(id: String, assetPath: String, title: String, artist: String?) =
        MediaItem.Builder()
            .setMediaId(id)
            .setUri("asset:///$assetPath")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setDisplayTitle(title)
                    .setArtist(artist)
                    .setSubtitle(artist)
                    .setArtworkData(ArtworkFactory.create(title, artist), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()

    private fun folder(id: String, title: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .setExtras(Bundle().apply {
                    // Android Auto 以清單（而非格狀）顯示
                    putInt(
                        MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
                    )
                })
                .build()
        )
        .build()

    companion object {
        private const val DIR = "songs"
        private const val ORDER_FILE = "order.txt"
        private const val MAIN_TITLE = "歌曲"
        const val ROOT_ID = "[root]"
        const val SONGS_ID = "[songs]"
        const val PLAYLIST_PREFIX = "[list]"
        const val EMPTY_HINT_ID = "[empty]"

        /**
         * 依 order.txt 排出歌曲順序。
         *
         * order.txt 一行一首（檔名不含 .mp3），照行的順序排；`*` 那一行代表「其他沒列到的歌」，
         * 照檔名排序後放在那個位置。沒寫 `*` 時，沒列到的歌接在最後。
         * 列了但不存在的歌、`#` 開頭的註解、空行都略過。
         */
        fun applyOrder(ids: List<String>, orderText: String?): List<String> {
            val sorted = ids.sorted()
            if (orderText == null) return sorted
            val lines = orderText.removePrefix("﻿").lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
            val existing = ids.toSet()
            val listed = lines.filter { it != "*" && it in existing }.distinct()
            val rest = sorted.filter { it !in listed }
            if ("*" !in lines) return listed + rest
            return lines.flatMap { line ->
                when {
                    line == "*" -> rest
                    line in listed -> listOf(line)
                    else -> emptyList()
                }
            }.distinct()
        }
    }
}
