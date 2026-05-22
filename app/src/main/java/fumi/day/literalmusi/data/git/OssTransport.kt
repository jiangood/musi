package fumi.day.literalmusi.data.git

import android.content.Context
import com.qiniu.storage.BucketManager
import com.qiniu.storage.Configuration
import com.qiniu.storage.Region
import com.qiniu.storage.UploadManager
import com.qiniu.util.Auth
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OssTransport @Inject constructor(
    @ApplicationContext private val context: Context
) : GitTransport {

    private val repoDir: File by lazy { File(context.filesDir, "repo") }
    override val pileDir: File by lazy { File(repoDir, "pile").also { it.mkdirs() } }
    override val trashDir: File by lazy { File(repoDir, "trash").also { it.mkdirs() } }

    private var auth: Auth? = null
    private var uploadManager: UploadManager? = null
    private var bucketManager: BucketManager? = null
    private var bucket: String = ""
    private var domain: String = ""

    private var remoteFiles: Map<String, RemoteFileInfo> = emptyMap()

    private data class RemoteFileInfo(val hash: String, val size: Long)

    override suspend fun ensureInitialized(
        accessKey: String,
        secretKey: String,
        bucket: String,
        region: String,
        domain: String
    ) {
        this.auth = Auth.create(accessKey, secretKey)
        this.bucket = bucket
        this.domain = domain.trimEnd('/')

        val reg = Region.autoRegion(accessKey, bucket)
        val cfg = Configuration(reg)
        cfg.connectTimeout = 30
        cfg.readTimeout = 60

        this.uploadManager = UploadManager(cfg)
        this.bucketManager = BucketManager(auth, cfg)
        this.remoteFiles = listRemoteFiles()
    }

    override suspend fun pull(): PullResult {
        pileDir.mkdirs()
        val trashNames = trashDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val remote = remoteFiles.keys

        val downloaded = (remote - trashNames).count { fileName ->
            val localFile = File(pileDir, fileName)
            if (localFile.exists()) return@count false
            try {
                downloadFile("pile/$fileName", localFile)
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

    override suspend fun commit(
        message: String,
        knownShas: Map<String, String>,
        lastSyncedAt: Long?
    ): CommitResult {
        val localFiles = pileDir.listFiles()?.associateBy { it.name } ?: emptyMap()
        val localPileNames = localFiles.keys
        val trashNames = trashDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val remoteNames = remoteFiles.keys

        val skipped = mutableSetOf<String>()
        val toUpload = mutableSetOf<String>()
        toUpload.addAll(localPileNames - remoteNames)

        val errors = mutableListOf<String>()
        var uploaded = 0
        val newShas = mutableMapOf<String, String>()

        for (name in localPileNames.intersect(remoteNames)) {
            val file = localFiles[name] ?: continue
            val remoteInfo = remoteFiles[name] ?: continue
            val knownSha = knownShas[name]

            val localChanged = file.lastModified() > (lastSyncedAt ?: 0L)
            val remoteChanged = knownSha != null && knownSha != remoteInfo.hash

            when {
                !localChanged && !remoteChanged -> skipped.add(name)
                localChanged && !remoteChanged -> toUpload.add(name)
                !localChanged && remoteChanged -> {
                    file.delete()
                    skipped.add(name)
                }
                else -> {
                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val conflictName = "${name}_conflict_$ts"
                    file.renameTo(File(pileDir, conflictName))
                    skipped.add(name)
                    errors.add("Conflict: $name renamed to $conflictName")
                }
            }
        }

        for (name in toUpload) {
            val file = localFiles[name] ?: continue
            try {
                val key = "pile/$name"
                val token = auth!!.uploadToken(bucket, key)
                uploadManager!!.put(file.absolutePath, key, token)
                newShas[name] = remoteFileHash(file)
                uploaded++
            } catch (e: Exception) {
                errors.add("Upload $name failed: ${e.message}")
            }
        }

        for (name in trashNames) {
            val trashFile = File(trashDir, name)
            if (!trashFile.exists()) continue
            try {
                if (name in remoteNames) {
                    bucketManager!!.move(bucket, "pile/$name", bucket, "trash/$name")
                } else {
                    val key = "trash/$name"
                    val token = auth!!.uploadToken(bucket, key)
                    uploadManager!!.put(trashFile.absolutePath, key, token)
                }
                trashFile.delete()
            } catch (e: Exception) {
                errors.add("Trash $name failed: ${e.message}")
            }
        }

        remoteFiles = listRemoteFiles()
        return CommitResult(uploaded = uploaded, newShas = newShas + knownShas, errors = errors)
    }

    override suspend fun push(): Boolean = true

    override fun close() {
        remoteFiles = emptyMap()
    }

    private fun remoteFileHash(file: File): String {
        return "${file.lastModified()}_${file.length()}"
    }

    private fun listRemoteFiles(): Map<String, RemoteFileInfo> {
        return try {
            val result = mutableMapOf<String, RemoteFileInfo>()
            var marker: String? = null
            do {
                val listing = bucketManager!!.listFiles(bucket, "pile/", marker, 1000, null)
                for (item in listing.items) {
                    val name = item.key.removePrefix("pile/")
                    result[name] = RemoteFileInfo(hash = item.hash, size = item.fsize)
                }
                marker = listing.marker
            } while (!listing.isEOF)
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun downloadFile(key: String, target: File) {
        val url = auth!!.privateDownloadUrl("$domain/$key", 3600)
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        target.outputStream().use { output ->
            conn.inputStream.copyTo(output)
        }
    }
}
