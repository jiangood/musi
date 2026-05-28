package fumi.day.literalmusi.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncStateHolder @Inject constructor() {
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private var startTime = 0L

    fun start(direction: SyncDirection, fileNames: List<String>, fileSizes: List<Long>) {
        _state.value = SyncState(
            isActive = true,
            direction = direction,
            totalFiles = fileNames.size,
            totalBytes = fileSizes.sum(),
            currentFileName = fileNames.firstOrNull() ?: ""
        )
        startTime = System.currentTimeMillis()
    }

    fun reportCurrentFile(fileName: String) {
        _state.value = _state.value.copy(currentFileName = fileName)
    }

    fun reportBytes(transferredBytes: Long) {
        val s = _state.value
        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        val speed = if (elapsed > 0) transferredBytes / elapsed else 0.0
        _state.value = s.copy(
            transferredBytes = transferredBytes,
            speedBytesPerSec = speed
        )
    }

    fun completeFile() {
        val s = _state.value
        _state.value = s.copy(completedFiles = s.completedFiles + 1)
    }

    fun finish(errors: List<String> = emptyList()) {
        _state.value = SyncState(errors = errors)
    }

    fun cancel() {
        _state.value = SyncState()
    }
}
