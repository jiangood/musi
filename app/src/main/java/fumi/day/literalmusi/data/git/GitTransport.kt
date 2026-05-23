package fumi.day.literalmusi.data.git

import java.io.File
import java.util.UUID

enum class OpType { ADD, DELETE, RENAME, MODIFY }

data class Operation(
    val id: String = UUID.randomUUID().toString(),
    val type: OpType,
    val path: String,
    val oldPath: String? = null,
    val time: Long = System.currentTimeMillis()
)

data class BatchResult(
    val committed: Boolean = false,
    val errors: List<String> = emptyList()
)

interface GitTransport {
    suspend fun ensureInitialized(token: String, repo: String)
    suspend fun pull(): PullResult
    suspend fun batchCommit(ops: List<Operation>): BatchResult
    val pileDir: File
    val trashDir: File
    fun remoteFileCount(): Int?
    suspend fun listRemoteFilenames(): List<String>
    suspend fun downloadFile(fileName: String, target: File)
    fun close()
}

data class PullResult(
    val conflicts: List<String> = emptyList(),
    val filesDownloaded: Int = 0
)
