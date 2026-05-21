package fumi.day.literalmusi.data.git

import java.io.File

interface GitTransport {
    suspend fun ensureInitialized(token: String, repo: String)
    suspend fun pull(): PullResult
    suspend fun stageAll(): Int
    suspend fun commit(message: String)
    suspend fun push(): Boolean
    suspend fun batchCommit(ops: List<Operation>): BatchResult
    val pileDir: File
    val trashDir: File
    fun close()
}

data class PullResult(
    val conflicts: List<String> = emptyList(),
    val filesDownloaded: Int = 0
)

data class BatchResult(
    val committed: Boolean = false,
    val errors: List<String> = emptyList()
)
