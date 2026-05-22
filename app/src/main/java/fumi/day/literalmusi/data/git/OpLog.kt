package fumi.day.literalmusi.data.git

import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class OpType { ADD, DELETE, RENAME, MODIFY }

data class Operation(
    val id: String = UUID.randomUUID().toString(),
    val type: OpType,
    val path: String,
    val oldPath: String? = null,
    val time: Long = System.currentTimeMillis()
)

@Singleton
class OpLog @Inject constructor() {
    private var oplogDir: File? = null

    private val oplogFile: File? get() = oplogDir?.let { File(it, "oplog.jsonl") }
    private val pendingFile: File? get() = oplogDir?.let { File(it, "oplog.pending") }

    fun init(dir: File) {
        oplogDir = dir
        dir.mkdirs()
        val pf = pendingFile
        val of = oplogFile
        if (pf != null && of != null && pf.exists() && !of.exists()) {
            pf.renameTo(of)
        }
    }

    @Synchronized
    fun append(op: Operation) {
        val file = oplogFile ?: return
        val json = JSONObject().apply {
            put("id", op.id)
            put("type", op.type.name)
            put("path", op.path)
            op.oldPath?.let { put("oldPath", it) }
            put("time", op.time)
        }
        file.appendText(json.toString() + "\n")
    }

    @Synchronized
    fun rotate(): List<Operation> {
        val file = oplogFile ?: return emptyList()
        if (!file.exists()) return emptyList()
        val pend = pendingFile ?: return emptyList()
        pend.delete()
        file.renameTo(pend)
        return loadOperations(pend)
    }

    @Synchronized
    fun completeSync() {
        pendingFile?.delete()
    }

    @Synchronized
    fun failSync() {
        val file = oplogFile ?: return
        val pend = pendingFile ?: return
        if (!pend.exists()) return
        val pendingContent = pend.readText()
        val existingContent = if (file.exists()) file.readText() else ""
        file.writeText(pendingContent + existingContent)
        pend.delete()
    }

    @Synchronized
    fun pendingCount(): Int {
        val file = oplogFile ?: return 0
        if (!file.exists()) return 0
        return file.readLines().count { it.isNotBlank() }
    }

    private fun loadOperations(file: File): List<Operation> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            try {
                val json = JSONObject(line)
                Operation(
                    id = json.getString("id"),
                    type = OpType.valueOf(json.getString("type")),
                    path = json.getString("path"),
                    oldPath = json.optString("oldPath", null),
                    time = json.optLong("time", System.currentTimeMillis())
                )
            } catch (_: Exception) { null }
        }
    }
}
