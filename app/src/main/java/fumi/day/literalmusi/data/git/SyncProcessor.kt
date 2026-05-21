package fumi.day.literalmusi.data.git

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncProcessor @Inject constructor(
    private val gitTransport: GitTransport
) {
    suspend fun process(): SyncResult {
        var uploaded = 0
        var errors = emptyList<String>()

        try {
            uploaded = gitTransport.commit("sync")
        } catch (e: Exception) {
            errors = listOf("${e.javaClass.simpleName}: ${e.message}")
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
