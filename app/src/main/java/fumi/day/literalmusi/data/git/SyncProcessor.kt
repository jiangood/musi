package fumi.day.literalmusi.data.git

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncProcessor @Inject constructor(
    private val gitTransport: GitTransport,
    private val opLog: OpLog
) {
    suspend fun process(): SyncResult {
        val ops = opLog.rotate()
        var uploaded = 0
        var errors = emptyList<String>()

        if (ops.isNotEmpty()) {
            val batchResult = gitTransport.batchCommit(ops)
            if (batchResult.committed) {
                uploaded = ops.size
                opLog.completeSync()
            } else {
                opLog.failSync()
            }
            errors = batchResult.errors
        }

        val pullResult = try {
            gitTransport.pull()
        } catch (e: Exception) {
            PullResult(filesDownloaded = 0)
        }

        return SyncResult(
            uploaded = uploaded,
            downloaded = pullResult.filesDownloaded,
            errors = errors + pullResult.conflicts
        )
    }
}
