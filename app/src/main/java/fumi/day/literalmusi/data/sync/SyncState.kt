package fumi.day.literalmusi.data.sync

enum class SyncDirection { IDLE, UPLOAD, DOWNLOAD }

data class SyncState(
    val isActive: Boolean = false,
    val direction: SyncDirection = SyncDirection.IDLE,
    val totalFiles: Int = 0,
    val completedFiles: Int = 0,
    val currentFileName: String = "",
    val totalBytes: Long = 0L,
    val transferredBytes: Long = 0L,
    val speedBytesPerSec: Double = 0.0,
    val errors: List<String> = emptyList()
) {
    val progress: Float
        get() = if (totalBytes > 0) (transferredBytes.toFloat() / totalBytes) else 0f

    val formattedSpeed: String
        get() = when {
            speedBytesPerSec >= 1_000_000 -> "%.1f MB/s".format(speedBytesPerSec / 1_000_000)
            speedBytesPerSec >= 1_000 -> "%.0f KB/s".format(speedBytesPerSec / 1_000)
            else -> "0 B/s"
        }

    val etaSeconds: Long?
        get() = if (speedBytesPerSec > 0 && transferredBytes < totalBytes)
            ((totalBytes - transferredBytes) / speedBytesPerSec).toLong() else null

    val formattedEta: String
        get() = etaSeconds?.let { sec ->
            if (sec > 60) "${sec / 60}m ${sec % 60}s" else "${sec}s"
        } ?: "--"
}
