package fumi.day.literalmusi.data.git

import fumi.day.literalmusi.data.prefs.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudSyncManager @Inject constructor(
    private val gitTransport: GitTransport,
    private val userPreferences: UserPreferences
) {

    suspend fun isConfigured(): Boolean {
        val prefs = userPreferences.userPrefs.first()
        return prefs.gitHubEnabled && prefs.gitHubToken.isNotBlank() && prefs.gitHubRepo.isNotBlank()
    }

    suspend fun listRemoteFilenames(): List<String> {
        val prefs = userPreferences.userPrefs.first()
        gitTransport.ensureInitialized(prefs.gitHubToken, prefs.gitHubRepo)
        return gitTransport.listRemoteFilenames()
    }

    suspend fun uploadFiles(
        files: List<File>,
        onProgress: (fileName: String, index: Int, total: Int) -> Unit = { _, _, _ -> }
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val prefs = userPreferences.userPrefs.first()
            gitTransport.ensureInitialized(prefs.gitHubToken, prefs.gitHubRepo)
            val ops = files.map { file ->
                Operation(type = OpType.ADD, path = "pile/${file.name}")
            }
            val result = gitTransport.batchCommit(ops)
            if (result.committed) {
                files.forEachIndexed { i, file ->
                    onProgress(file.name, i + 1, files.size)
                }
                Result.success(files.map { it.name })
            } else {
                Result.failure(Exception(result.errors.joinToString(", ")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadFiles(
        fileNames: List<String>,
        onProgress: (fileName: String, index: Int, total: Int) -> Unit = { _, _, _ -> }
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val prefs = userPreferences.userPrefs.first()
            gitTransport.ensureInitialized(prefs.gitHubToken, prefs.gitHubRepo)
            val pileDir = gitTransport.pileDir
            val downloaded = mutableListOf<String>()
            for ((i, fileName) in fileNames.withIndex()) {
                onProgress(fileName, i, fileNames.size)
                val target = File(pileDir, fileName)
                gitTransport.downloadFile(fileName, target)
                downloaded.add(fileName)
                onProgress(fileName, i + 1, fileNames.size)
            }
            Result.success(downloaded)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteRemoteFiles(
        fileNames: List<String>,
        onProgress: (fileName: String, index: Int, total: Int) -> Unit = { _, _, _ -> }
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val prefs = userPreferences.userPrefs.first()
            gitTransport.ensureInitialized(prefs.gitHubToken, prefs.gitHubRepo)
            val ops = fileNames.map { fileName ->
                Operation(type = OpType.DELETE, path = "pile/$fileName")
            }
            val result = gitTransport.batchCommit(ops)
            if (result.committed) {
                fileNames.forEachIndexed { i, name ->
                    onProgress(name, i + 1, fileNames.size)
                }
                Result.success(fileNames)
            } else {
                Result.failure(Exception(result.errors.joinToString(", ")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
