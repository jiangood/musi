package fumi.day.literalmusi.data.git

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitHubApiTransport @Inject constructor(
    @ApplicationContext private val context: Context
) : GitTransport {

    private val repoDir: File by lazy { File(context.filesDir, "repo") }
    override val pileDir: File by lazy { File(repoDir, "pile").also { it.mkdirs() } }
    override val trashDir: File by lazy { File(repoDir, "trash").also { it.mkdirs() } }

    private var token: String = ""
    private var repoOwner: String = ""
    private var repoName: String = ""
    private var branch: String = "master"

    private var remoteFiles: Map<String, RemoteFileInfo> = emptyMap()

    private data class RemoteFileInfo(val sha: String, val size: Long)

    override suspend fun ensureInitialized(token: String, repo: String) {
        this.token = token
        val parts = repo.split("/", limit = 2)
        repoOwner = parts[0]
        repoName = parts[1]
        remoteFiles = listRemoteFiles()
    }

    override suspend fun pull(): PullResult {
        pileDir.mkdirs()
        val trashNames = trashDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val remote = remoteFiles.keys

        val downloaded = (remote - trashNames).count { fileName ->
            val localFile = File(pileDir, fileName)
            if (localFile.exists()) return@count false
            try {
                val content = downloadFile(fileName)
                localFile.writeBytes(content)
                true
            } catch (_: Exception) {
                false
            }
        }

        return PullResult(conflicts = emptyList(), filesDownloaded = downloaded)
    }

    override suspend fun stageAll(): Int {
        val local = pileDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        return (local - remoteFiles.keys).size
    }

    override suspend fun commit(message: String): Int {
        val localFiles = pileDir.listFiles()?.associateBy { it.name } ?: emptyMap()
        val localPileNames = localFiles.keys
        val trashNames = trashDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val remoteNames = remoteFiles.keys

        val toUpload = localPileNames - remoteNames
        val toDelete = remoteNames.intersect(trashNames)
        val toKeep = localPileNames.intersect(remoteNames)

        val changed = toUpload.size + toDelete.size
        if (changed == 0) return 0

        ensureRepoInitialized()

        val entries = mutableListOf<JSONObject>()

        for (name in toKeep) {
            remoteFiles[name]?.let { info ->
                entries.add(JSONObject().apply {
                    put("path", "pile/$name")
                    put("mode", "100644")
                    put("type", "blob")
                    put("sha", info.sha)
                })
            }
        }

        for (name in toUpload) {
            val file = localFiles[name] ?: continue
            val sha = createBlob(file.readBytes())
            entries.add(JSONObject().apply {
                put("path", "pile/$name")
                put("mode", "100644")
                put("type", "blob")
                put("sha", sha)
            })
        }

        val treeSha = createTree(entries)
        val parentSha = getRefSha()
        val commitSha = createCommit(message, treeSha, parentSha)
        updateRef(commitSha)

        remoteFiles = listRemoteFiles()
        return changed
    }

    override suspend fun push(): Boolean = true

    override fun close() {
        remoteFiles = emptyMap()
    }

    private fun apiUrl(path: String) = "https://api.github.com$path"

    private fun repoApi(path: String) = apiUrl("/repos/$repoOwner/$repoName$path")

    private fun openConnection(url: String, method: String = "GET"): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        return conn
    }

    private fun checkResponse(conn: HttpURLConnection) {
        if (conn.responseCode !in 200..299) {
            val body = try { InputStreamReader(conn.errorStream).readText() } catch (_: Exception) { "unknown" }
            throw RuntimeException("GitHub API ${conn.responseCode}: $body")
        }
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return InputStreamReader(stream).readText()
    }

    private fun listRemoteFiles(): Map<String, RemoteFileInfo> {
        return try {
            val conn = openConnection(repoApi("/contents/pile"))
            when (conn.responseCode) {
                404 -> emptyMap()
                in 200..299 -> {
                    val arr = JSONArray(readBody(conn))
                    val result = mutableMapOf<String, RemoteFileInfo>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        if (obj.getString("type") == "file") {
                            result[obj.getString("name")] = RemoteFileInfo(
                                sha = obj.getString("sha"),
                                size = obj.optLong("size", 0)
                            )
                        }
                    }
                    result
                }
                else -> emptyMap()
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun downloadFile(fileName: String): ByteArray {
        val conn = openConnection(repoApi("/contents/pile/$fileName"))
        conn.setRequestProperty("Accept", "application/vnd.github.v3.raw")
        checkResponse(conn)
        val baos = ByteArrayOutputStream()
        conn.inputStream.copyTo(baos)
        return baos.toByteArray()
    }

    private fun ensureRepoInitialized() {
        if (getRefSha() != null) return
        createInitialCommitViaContents()
    }

    private fun createInitialCommitViaContents() {
        val conn = openConnection(repoApi("/contents/.musi-init"), "PUT")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().apply {
            put("message", "Initialize repository for Literal Musi sync")
            put("content", Base64.getEncoder().encodeToString("musi".toByteArray()))
        }
        DataOutputStream(conn.outputStream).use { it.writeBytes(body.toString()) }
        checkResponse(conn)
    }

    private fun createRef(commitSha: String) {
        val conn = openConnection(repoApi("/git/refs"), "POST")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().apply {
            put("ref", "refs/heads/$branch")
            put("sha", commitSha)
        }
        DataOutputStream(conn.outputStream).use { it.writeBytes(body.toString()) }
        checkResponse(conn)
    }

    private fun createBlob(content: ByteArray): String {
        val conn = openConnection(repoApi("/git/blobs"), "POST")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().apply {
            put("content", Base64.getEncoder().encodeToString(content))
            put("encoding", "base64")
        }
        DataOutputStream(conn.outputStream).use { it.writeBytes(body.toString()) }
        checkResponse(conn)
        return JSONObject(readBody(conn)).getString("sha")
    }

    private fun createTree(entries: List<JSONObject>): String {
        val conn = openConnection(repoApi("/git/trees"), "POST")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().apply {
            put("tree", JSONArray(entries))
        }
        DataOutputStream(conn.outputStream).use { it.writeBytes(body.toString()) }
        checkResponse(conn)
        return JSONObject(readBody(conn)).getString("sha")
    }

    private fun getRefSha(): String? {
        return try {
            val conn = openConnection(repoApi("/git/refs/heads/$branch"))
            JSONObject(readBody(conn)).getJSONObject("object").getString("sha")
        } catch (_: Exception) {
            null
        }
    }

    private fun createCommit(message: String, treeSha: String, parentSha: String?): String {
        val conn = openConnection(repoApi("/git/commits"), "POST")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().apply {
            put("message", message)
            put("tree", treeSha)
            put("parents", if (parentSha != null) JSONArray(listOf(parentSha)) else JSONArray())
        }
        DataOutputStream(conn.outputStream).use { it.writeBytes(body.toString()) }
        checkResponse(conn)
        return JSONObject(readBody(conn)).getString("sha")
    }

    private fun updateRef(commitSha: String) {
        val conn = openConnection(repoApi("/git/refs/heads/$branch"), "PATCH")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().apply {
            put("sha", commitSha)
            put("force", true)
        }
        DataOutputStream(conn.outputStream).use { it.writeBytes(body.toString()) }
        if (conn.responseCode == 409) {
            createRef(commitSha)
        } else {
            checkResponse(conn)
        }
    }
}
