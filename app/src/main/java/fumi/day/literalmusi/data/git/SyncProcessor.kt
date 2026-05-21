package fumi.day.literalmusi.data.git

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncProcessor @Inject constructor(
    private val gitTransport: GitTransport
) {
    suspend fun process(knownShas: Map<String, String> = emptyMap(), lastSyncedAt: Long? = null): SyncResult {
        val errors = mutableListOf<String>()

        val pullResult = try {
            gitTransport.pull()
        } catch (e: Exception) {
            PullResult(filesDownloaded = 0)
        }

        val commitResult = try {
            gitTransport.commit("sync", knownShas, lastSyncedAt)
        } catch (e: Exception) {
            errors.add("${e.javaClass.simpleName}: ${e.message}")
            CommitResult()
        }

        errors.addAll(commitResult.errors)

        return SyncResult(
            uploaded = commitResult.uploaded,
            downloaded = pullResult.filesDownloaded,
            errors = errors + pullResult.conflicts,
            newShas = commitResult.newShas
        )
    }
}
