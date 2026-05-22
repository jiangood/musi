package fumi.day.literalmusi.data.git

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncProcessor @Inject constructor(
    private val gitTransport: GitTransport,
    private val opLog: OpLog
) {
    suspend fun process(): SyncResult {
        var uploaded = 0
        val errors = mutableListOf<String>()

        val ops = opLog.rotate()
        if (ops.isNotEmpty()) {
            val result = gitTransport.batchCommit(ops)
            if (result.committed) {
                uploaded = ops.size
                opLog.completeSync()
            } else {
                opLog.failSync()
            }
            errors.addAll(result.errors)
        }

        val pullResult = try {
            gitTransport.pull()
        } catch (e: Exception) {
            PullResult(filesDownloaded = 0)
        }

        errors.addAll(pullResult.conflicts)

        return SyncResult(
            uploaded = uploaded,
            downloaded = pullResult.filesDownloaded,
            errors = errors
        )
    }
}
