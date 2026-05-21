package fumi.day.literalmusi.data.git

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler @Inject constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var debounceJob: Job? = null
    private var syncCallback: (suspend () -> Unit)? = null

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    fun setSyncCallback(callback: suspend () -> Unit) {
        syncCallback = callback
    }

    @Synchronized
    fun onOperationEnqueued() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(3000)
            runSync()
        }
    }

    @Synchronized
    fun triggerSync() {
        debounceJob?.cancel()
        debounceJob = null
        runSync()
    }

    fun onResume() {
        triggerSync()
    }

    private fun runSync() {
        if (_isSyncing.value) return
        scope.launch {
            _isSyncing.value = true
            try {
                syncCallback?.invoke()
            } finally {
                _isSyncing.value = false
            }
        }
    }
}
