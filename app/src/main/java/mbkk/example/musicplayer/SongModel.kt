package mbkk.example.musicplayer

data class SongModel(
    val id: Long,
    val title: String,
    val artist: String,
    val duration: Long,
    val path: String,
    val albumArt: String
)