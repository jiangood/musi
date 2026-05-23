package fumi.day.literalmusi.domain.model

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val uri: String,
    val dataModified: Long,
    val format: String? = null,
    val qualityLabel: String? = null,
    val isUploaded: Boolean = false
) {
    val formattedDuration: String
        get() {
            val totalSeconds = duration / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }
}
