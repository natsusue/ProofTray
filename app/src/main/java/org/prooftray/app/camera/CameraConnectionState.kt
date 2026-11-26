package org.prooftray.app.camera

internal data class BrowseProgress(
    val generation: Int, val indexed: Int, val total: Int, val busy: Boolean, val hasMore: Boolean,
    val photoIds: Set<String>, val message: String? = null, val stopped: Boolean = false, val readId: Long = 0,
    val dates: List<SonyCaptureDay> = emptyList(), val startDay: SonyCaptureDay? = null
)

/** Disconnecting releases UI work flags; download tasks and user pause choices remain intact. */
internal fun CameraState.disconnected(nextGeneration: Int, reason: String? = null): CameraState = copy(
    connected = false, connecting = false, busy = false, transmissionPaused = false, generation = nextGeneration,
    connectionPhase = if (reason == null) ConnectionPhase.IDLE else ConnectionPhase.FAILED,
    stage = if (reason == null) "相机未连接" else "连接已断开", message = reason, pairingCode = null,
    accessRequest = null, attemptId = null, connectedId = null, controlAttempt = null, failedChannel = SavedCameraChannel.WIFI,
    failedId = if (reason != null) attemptId ?: connectedId else null, deviceError = reason,
    sourceId = "", hasMore = false, catalogIds = emptySet(), catalogError = null, catalogStopped = false,
    catalogDates = emptyList(), catalogStartDay = null, make = CameraMake.OTHER, pairingTarget = null,
    protocolDiagnostic = if (reason == null) null else protocolDiagnostic
)

internal fun CameraState.withCatalogProgress(progress: BrowseProgress): CameraState {
    if (!connected || connecting || progress.generation != generation) return this
    return copy(busy = progress.busy, indexed = progress.indexed, total = progress.total, hasMore = progress.hasMore,
        catalogStopped = progress.stopped, catalogError = progress.message, catalogReadId = progress.readId,
        catalogDates = progress.dates, catalogStartDay = progress.startDay,
        catalogIds = progress.photoIds, message = progress.message, stage = when {
            progress.message != null -> "目录读取中断"
            progress.stopped -> if (progress.busy) "正在停止读取" else "已停止读取"
            progress.busy -> "读取本批照片信息"
            progress.hasMore -> "本批已加载，可继续加载"
            else -> "目录已更新"
        })
}
