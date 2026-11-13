package com.joechiboo.autokaraoke

import android.content.res.AssetManager
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants

/** 一首歌：assets/songs/<id>.mp3，搭配同名 <id>.lrc（可以沒有）。 */
data class Song(
    val id: String,
    val title: String,
    val artist: String?,
    val lyrics: Lyrics,
    /** 瀏覽列表與播放清單用的「原始」MediaItem（標題＝歌名）。 */
    val mediaItem: MediaItem,
)

/** 掃描 assets/songs/ 建立歌曲清單與 Android Auto 的瀏覽樹。 */
class SongLibrary(assets: AssetManager) {

    val songs: List<Song>
    private val byId: Map<String, Song>

    init {
        val files = assets.list(DIR)?.toList().orEmpty()
        val lrcByBase = files
            .filter { it.endsWith(".lrc", ignoreCase = true) }
            .associateBy { it.substringBeforeLast('.').lowercase() }

        songs = files
            .filter { it.endsWith(".mp3", ignoreCase = true) }
            .sorted()
            .map { mp3 ->
                val id = mp3.substringBeforeLast('.')
                val lyrics = lrcByBase[id.lowercase()]
                    ?.let { lrc -> assets.open("$DIR/$lrc").bufferedReader(Charsets.UTF_8).use { it.readText() } }
                    ?.let(LrcParser::parse)
                    ?: Lyrics.EMPTY
                val title = lyrics.title ?: id
                Song(id, title, lyrics.artist, lyrics, buildSongItem(id, mp3, title, lyrics.artist))
            }
        byId = songs.associateBy { it.id }
    }

    val songItems: List<MediaItem> get() = songs.map { it.mediaItem }

    fun find(mediaId: String): Song? = byId[mediaId]

    fun indexOf(mediaId: String): Int = songs.indexOfFirst { it.id == mediaId }

    val rootItem: MediaItem = folder(ROOT_ID, "root")

    val songsFolder: MediaItem = folder(SONGS_ID, "歌曲")

    /** 沒放 mp3 時顯示的提示項目（不可播放）。 */
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

    private fun buildSongItem(id: String, fileName: String, title: String, artist: String?) =
        MediaItem.Builder()
            .setMediaId(id)
            .setUri("asset:///$DIR/$fileName")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setDisplayTitle(title)
                    .setArtist(artist)
                    .setSubtitle(artist)
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
        const val ROOT_ID = "[root]"
        const val SONGS_ID = "[songs]"
        const val EMPTY_HINT_ID = "[empty]"
    }
}
