package fumi.day.literalmusi.data.github

import android.util.Base64
import fumi.day.literalmusi.data.git.GitForgeApi
import fumi.day.literalmusi.data.git.RemoteFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitHubRepository @Inject constructor() : GitForgeApi {

    private val baseUrl = "https://api.github.com"

    private suspend fun makeRequest(
        method: String,
        url: String,
        token: String,
        body: String? = null
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(body)
                }
            }
            val responseCode = connection.responseCode
            val responseBody = try {
                connection.inputStream.bufferedReader().use(BufferedReader::readText)
            } catch (e: Exception) {
                connection.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            }
            Pair(responseCode, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun listPileFiles(token: String, repo: String): Result<List<RemoteFile>> =
        listFilesInDir(token, repo, "pile")

    override suspend fun listTrashFiles(token: String, repo: String): Result<List<RemoteFile>> =
        listFilesInDir(token, repo, "trash")

    private suspend fun listFilesInDir(token: String, repo: String, dir: String): Result<List<RemoteFile>> {
        return try {
            val (code, body) = makeRequest("GET", "$baseUrl/repos/$repo/contents/$dir", token)
            when (code) {
                200 -> {
                    val files = mutableListOf<RemoteFile>()
                    val array = JSONArray(body)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        files.add(RemoteFile(path = obj.getString("path"), sha = obj.getString("sha")))
                    }
                    Result.success(files)
                }
                404 -> Result.success(emptyList())
                else -> Result.failure(Exception("Failed to list files in $dir: $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getFile(token: String, repo: String, path: String): Result<RemoteFile> {
        return try {
            val (code, body) = makeRequest("GET", "$baseUrl/repos/$repo/contents/$path", token)
            when (code) {
                200 -> {
                    val obj = JSONObject(body)
                    val encoded = obj.getString("content").replace("\n", "")
                    val content = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                    Result.success(RemoteFile(path = obj.getString("path"), sha = obj.getString("sha"), content = content))
                }
                else -> Result.failure(Exception("Failed to get file: $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun putFile(
        token: String,
        repo: String,
        path: String,
        content: String,
        sha: String?,
        message: String
    ): Result<RemoteFile> {
        return try {
            val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val bodyObj = JSONObject().apply {
                put("message", message)
                put("content", encoded)
                if (sha != null) put("sha", sha)
            }
            val (code, body) = makeRequest("PUT", "$baseUrl/repos/$repo/contents/$path", token, bodyObj.toString())
            when (code) {
                200, 201 -> {
                    val obj = JSONObject(body).getJSONObject("content")
                    Result.success(RemoteFile(path = obj.getString("path"), sha = obj.getString("sha"), content = content))
                }
                else -> Result.failure(Exception("Failed to put file: $code - $body"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteFile(
        token: String,
        repo: String,
        path: String,
        sha: String,
        message: String
    ): Result<Unit> {
        return try {
            val bodyObj = JSONObject().apply {
                put("message", message)
                put("sha", sha)
            }
            val (code, _) = makeRequest("DELETE", "$baseUrl/repos/$repo/contents/$path", token, bodyObj.toString())
            when (code) {
                200 -> Result.success(Unit)
                else -> Result.failure(Exception("Failed to delete file: $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
