package fumi.day.literalmusi.data.git

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JGitTransport @Inject constructor(
    @ApplicationContext private val context: Context
) : GitTransport {

    private val repoDir: File by lazy { File(context.filesDir, "repo") }
    override val pileDir: File by lazy { File(repoDir, "pile").also { it.mkdirs() } }
    override val trashDir: File by lazy { File(repoDir, "trash").also { it.mkdirs() } }

    private var git: Git? = null
    private var _token: String = ""

    private fun credentials(token: String): UsernamePasswordCredentialsProvider {
        return UsernamePasswordCredentialsProvider(token, "")
    }

    override suspend fun ensureInitialized(token: String, repo: String) {
        _token = token
        if (git == null) {
            val gitDir = File(repoDir, ".git")
            git = if (gitDir.exists()) {
                Git.open(repoDir)
            } else {
                if (repoDir.exists()) {
                    repoDir.deleteRecursively()
                }
                Git.cloneRepository()
                    .setURI("https://github.com/$repo.git")
                    .setDirectory(repoDir)
                    .setCredentialsProvider(credentials(token))
                    .call()
            }
        }
    }

    override suspend fun pull(): PullResult {
        val g = git ?: error("Git not initialized")

        val prePullFiles = pileDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()

        val result = g.pull()
            .setCredentialsProvider(credentials(_token))
            .call()

        val mergeResult = result.mergeResult
        val conflicts = if (mergeResult?.mergeStatus == MergeResult.MergeStatus.CONFLICTING) {
            mergeResult.conflicts?.keys?.toList() ?: emptyList()
        } else {
            emptyList()
        }

        val pileConflicts = conflicts.filter { it.startsWith("pile/") }
        if (pileConflicts.isNotEmpty()) {
            for (conflict in pileConflicts) {
                val conflictFile = File(repoDir, conflict)
                if (conflictFile.exists()) {
                    val content = conflictFile.readBytes()
                    val conflictName = conflict.substringAfter("pile/") + "_conflict_" + System.currentTimeMillis()
                    File(pileDir, conflictName).writeBytes(content)
                }
                g.add().addFilepattern(conflict).setUpdate(true).call()
                g.add().addFilepattern(conflict).call()
            }
            g.commit()
                .setMessage("auto-merge: resolve binary conflicts")
                .call()
        }

        val postPullFiles = pileDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val filesDownloaded = (postPullFiles - prePullFiles).size

        return PullResult(
            conflicts = pileConflicts.map { it.substringAfter("pile/") },
            filesDownloaded = filesDownloaded
        )
    }

    override suspend fun stageAll(): Int {
        val g = git ?: error("Git not initialized")
        val status = g.status().call()
        val stagedCount = status.uncommittedChanges.size + status.added.size + status.changed.size + status.missing.size + status.modified.size + status.removed.size
        g.add().addFilepattern(".").call()
        return stagedCount
    }

    override suspend fun commit(message: String) {
        val g = git ?: error("Git not initialized")
        val status = g.status().call()
        if (!status.isClean) {
            g.commit()
                .setMessage(message)
                .setAuthor("Literal Musi", "app@literalmusi.app")
                .call()
        }
    }

    override suspend fun push(): Boolean {
        val g = git ?: error("Git not initialized")
        return try {
            val pushResult = g.push()
                .setCredentialsProvider(credentials(_token))
                .call()
            pushResult.all { result ->
                result.remoteUpdates.all { update ->
                    update.status == RemoteRefUpdate.Status.OK
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun close() {
        try {
            git?.close()
        } catch (_: Exception) {
        }
        git = null
    }
}
