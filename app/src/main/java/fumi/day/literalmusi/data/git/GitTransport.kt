package fumi.day.literalmusi.data.git

import java.io.File

data class CommitResult(
    val uploaded: Int = 0,
    val newShas: Map<String, String> = emptyMap(),
    val errors: List<String> = emptyList()
)

interface GitTransport {
    suspend fun ensureInitialized(token: String, repo: String)
    suspend fun pull(): PullResult
    suspend fun stageAll(): Int
    suspend fun commit(message: String, knownShas: Map<String, String> = emptyMap(), lastSyncedAt: Long? = null): CommitResult
    suspend fun push(): Boolean
    val pileDir: File
    val trashDir: File
    fun close()
}

data class PullResult(
    val conflicts: List<String> = emptyList(),
    val filesDownloaded: Int = 0
)
