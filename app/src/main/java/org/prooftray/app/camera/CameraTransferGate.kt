package org.prooftray.app.camera

/** A change publishes its new session state before another worker can acquire admission. */
internal class CameraTransferGate {
    private var workers = 0

    @Synchronized fun change(blocked: () -> Boolean, change: () -> Unit): Boolean {
        if (workers != 0 || blocked()) return false
        change()
        return true
    }

    suspend fun <T> transfer(work: suspend () -> T): T {
        synchronized(this) { workers++ }
        try { return work() } finally { synchronized(this) { workers-- } }
    }
}

internal fun CameraDownload.waitAfterDisconnect(sourceId: String): CameraDownload =
    if (channel == DownloadChannel.WIFI && photo.cameraIdentity == sourceId && status == DownloadStatus.QUEUED)
        copy(status = DownloadStatus.WAITING, message = "连接已断开，请返回原相机后重试")
    else this

internal fun transferBlocksCameraChange(camera: CameraState, automatic: AutoDownloadState) =
    importHistoryBlockReason(camera.tasks, automatic) != null
