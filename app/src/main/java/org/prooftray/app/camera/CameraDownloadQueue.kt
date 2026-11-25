package org.prooftray.app.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.prooftray.app.data.GalleryPublisher
import org.prooftray.app.data.PhotoRepository
import org.prooftray.app.data.sourceGeometry
import org.prooftray.app.domain.DownloadState
import org.prooftray.app.domain.DownloadVariant
import org.prooftray.app.domain.PhotoAsset
import org.prooftray.app.domain.photoMimeType

/** The Wi-Fi session that queued downloads read from. Connection lifecycle stays with [CameraController]. */
internal interface DownloadSource {
    val identity: String
    fun photo(id: String): RemotePhoto?
    fun active(): PtpLink
    fun isLive(link: PtpLink): Boolean
}

/** Persistent import queue: Wi-Fi originals and portable JPEGs, Bluetooth JPEG copies, cancellation and retry. */
internal class CameraDownloadQueue(
    private val context: Context, private val scope: CoroutineScope, private val state: MutableStateFlow<CameraState>,
    private val requests: Mutex, private val source: DownloadSource, private val published: suspend () -> Unit
) {
    private val repository = PhotoRepository.get(context)
    private val gallery = GalleryPublisher(context)
    private val journal = CameraJournal(context)
    private val jpegSaver = CameraJpegSaver(context)
    private val fileReceiver = CameraFileReceiver(context, requests, source::active)
    private val taskLock = Mutex()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private var restored = false

    init {
        wake()
        scope.launch {
            for (signal in signals) {
                try {
                    while (true) {
                        val next = nextWifiTask() ?: break
                        download(next)
                    }
                } catch (stop: CancellationException) {
                    throw stop
                } catch (error: Exception) {
                    state.update { it.copy(paused = true, message = "导入任务记录读写失败，队列已暂停：${error.message}") }
                }
            }
        }
    }

    fun wake() { signals.trySend(Unit) }

    fun pause(paused: Boolean) {
        state.update { it.copy(paused = paused) }
        if (!paused) wake()
    }

    private suspend fun nextWifiTask(): CameraDownload? = withTasks { tasks ->
        val camera = state.value
        if (!camera.connected || camera.connecting || camera.paused) return@withTasks null
        tasks.firstOrNull {
            it.channel == DownloadChannel.WIFI && it.status == DownloadStatus.QUEUED && it.photo.cameraIdentity == camera.sourceId
        }
    }

    suspend fun enqueue(photos: List<RemotePhoto>, variant: DownloadVariant, automatic: Boolean) {
        val added = withTasks { tasks ->
            val open = tasks.filter { it.pending }.mapTo(hashSetOf()) { it.photo.asset.id to it.variant }
            val fresh = photos.filter { open.add(it.asset.id to variant) }
                .map { CameraDownload(UUID.randomUUID().toString(), it, variant = variant, automatic = automatic) }
            if (fresh.isNotEmpty()) saveTasks(tasks + fresh)
            fresh
        }
        try {
            val queued = if (variant == DownloadVariant.ORIGINAL) DownloadState.QUEUED else DownloadState.REMOTE
            if (added.isNotEmpty()) publish(added.map { it.photo.asset.copy(downloadState = queued) })
        } finally { wake() }
    }

    suspend fun clearHistory() {
        val removed = withTasks { tasks ->
            val (ended, remaining) = tasks.partition { it.canClearHistory }
            saveTasks(remaining)
            cancelled.removeAll(ended.mapTo(hashSetOf()) { it.id })
            ended
        }
        publishRemote(removed.filter { it.status == DownloadStatus.WAITING && it.variant == DownloadVariant.ORIGINAL })
    }

    suspend fun clearImportHistory(automatic: () -> AutoDownloadState) {
        // Queue transitions cannot pass the guard while the shared repository mutex and database transaction clear history.
        withTasks { tasks -> repository.clearImportHistory { importHistoryBlockReason(tasks, automatic()) != null } }
        published()
    }

    suspend fun hasPending(sourceId: String?, selectedIds: Set<String>?): Boolean = withTasks { tasks ->
        tasks.any { task ->
            task.pending && (sourceId == null || task.photo.cameraIdentity == sourceId) &&
                (selectedIds == null || task.photo.asset.id in selectedIds)
        }
    }

    fun cancel(id: String, cancelBluetooth: () -> Unit) {
        cancelled += id
        if (state.value.tasks.any { it.id == id && it.channel == DownloadChannel.BLUETOOTH && it.status == DownloadStatus.RUNNING }) {
            cancelBluetooth()
        }
        scope.launch {
            val task = withTasks { tasks -> tasks.find { it.id == id } } ?: return@launch
            when (task.status) {
                DownloadStatus.RUNNING -> updateTask(id) { it.copy(message = "正在取消导入") }
                DownloadStatus.QUEUED, DownloadStatus.WAITING -> finishCancelled(task, task.outputUri)
                DownloadStatus.CANCELLED -> if (task.outputUri != null) finishCancelled(task, task.outputUri)
                DownloadStatus.COMPLETED -> Unit
            }
        }
    }

    fun retry(id: String, retryBluetooth: (String) -> Unit) {
        scope.launch {
            val task = withTasks { tasks -> tasks.find { it.id == id } } ?: return@launch
            check(task.status == DownloadStatus.WAITING || task.status == DownloadStatus.CANCELLED) { "只有中断或取消的任务可以重试" }
            if (task.status == DownloadStatus.CANCELLED) {
                if (!removeOutput(task.outputUri)) {
                    updateTask(id) { it.copy(message = "请先清理上次的未完成副本") }
                    return@launch
                }
                cancelled -= id
                updateTask(id) { it.copy(status = DownloadStatus.WAITING, outputUri = null, received = 0, payloadBytes = 0) }
            }
            if (task.channel == DownloadChannel.BLUETOOTH) {
                retryBluetooth(task.photo.cameraIdentity)
                return@launch
            }
            val identity = source.identity
            val current = source.photo(task.photo.asset.id)
            if (!state.value.connected || current == null || current.cameraIdentity != identity || task.photo.cameraIdentity != identity) {
                val message = if (state.value.connected && identity == task.photo.cameraIdentity) "请刷新导入列表；若照片已重新编目，请重新选择原片导入"
                    else "请连接原来的相机，刷新导入列表后重试"
                updateTask(id) { it.copy(status = DownloadStatus.WAITING, message = message) }
                return@launch
            }
            cancelled -= id
            updateTask(id) { it.copy(photo = current, status = DownloadStatus.QUEUED, message = "等待重试") }
            wake()
        }
    }

    suspend fun receiveBluetooth(photo: RemotePhoto, bytes: () -> ByteArray) {
        val task = withTasks { tasks ->
            val previous = tasks.firstOrNull {
                val sameFile = it.photo.asset.id == photo.asset.id ||
                    (photo.asset.cameraFileKey != null && it.photo.asset.cameraFileKey == photo.asset.cameraFileKey)
                it.channel == DownloadChannel.BLUETOOTH && sameFile && it.status == DownloadStatus.WAITING
            }
            val message = "蓝牙正在接收便携 JPEG"
            val next = previous?.copy(photo = photo, status = DownloadStatus.RUNNING, message = message)
                ?: CameraDownload(UUID.randomUUID().toString(), photo, DownloadStatus.RUNNING, message = message,
                    variant = DownloadVariant.CAMERA_JPEG, automatic = true, channel = DownloadChannel.BLUETOOTH)
            saveTasks(tasks.filterNot { it.id == next.id } + next)
            next
        }
        try {
            currentCoroutineContext().ensureActive()
            checkNotCancelled(task.id)
            publish(listOf(photo.asset))
            val completed = jpegSaver.save(task, bytes(), { updateTask(task.id, it) }, { task.id in cancelled })
            publish(listOf(completed))
            updateTask(task.id) { it.copy(status = DownloadStatus.COMPLETED, message = "蓝牙便携 JPEG 已保存；原片未导入") }
        } catch (error: Exception) {
            withContext(NonCancellable) {
                if (task.id in cancelled) finishCancelled(task, state.value.tasks.find { it.id == task.id }?.outputUri)
                else updateTask(task.id) { it.copy(status = DownloadStatus.WAITING, message = "蓝牙传图中断：${error.message}；小图重试会重新接收") }
            }
            throw error
        }
    }

    /** The first queue operation loads and migrates the journal before it can inspect or change the tasks. */
    private suspend fun <T> withTasks(block: suspend (List<CameraDownload>) -> T): T = withContext(Dispatchers.IO) {
        taskLock.withLock {
            if (!restored) restore()
            block(state.value.tasks)
        }
    }

    private fun restore() {
        val recovered = journal.load().map { task ->
            if (task.status == DownloadStatus.RUNNING || task.status == DownloadStatus.QUEUED) {
                task.copy(status = DownloadStatus.WAITING, message = "上次任务中断，连接同一相机并刷新目录后重试")
            } else task
        }
        saveTasks(recovered)
        restored = true
        val originals = recovered.filter { it.status == DownloadStatus.COMPLETED && it.variant == DownloadVariant.ORIGINAL }
            .mapNotNull { it.outputUri?.let(Uri::parse) }
        scope.launch { gallery.restoreIndexOnce("camera-originals-v1", originals) }
    }

    private suspend fun download(task: CameraDownload) {
        var current = task
        try {
            checkNotCancelled(task.id)
            val link = source.active()
            val photo = source.photo(task.photo.asset.id) ?: error("目录尚未重新确认，请刷新相机目录后重试")
            check(photo.cameraIdentity == source.identity && task.photo.cameraIdentity == source.identity) { "已连接的相机与任务不一致" }
            val latest = request(link) { it.objectInfo(photo.handle, verify = true) }
            check(latest.getString("name") == photo.asset.name && latest.getLong("size") == photo.asset.sizeBytes &&
                latest.getString("captured") == photo.captured) { "相机文件已改变，不能继续旧任务" }
            val sony = latest.has("sonyMedia")
            if (sony) check(cameraObjectId(latest, photo.cameraIdentity) == task.photo.asset.id) { "存储卡或原片已变化，请刷新后重新选择原片" }
            if (task.variant == DownloadVariant.CAMERA_JPEG) {
                updateTask(task.id) { it.copy(status = DownloadStatus.RUNNING, message = "正在通过 Wi-Fi 获取机内便携 JPEG") }
                val bytes = request(link) { it.portableJpeg(photo.handle) }
                val complete = jpegSaver.save(task.copy(photo = photo), bytes, { updateTask(task.id, it) }, { task.id in cancelled }) {
                    requests.withLock { if (source.isLive(link)) link.nikonPhotoHeader(photo) else null }?.sourceGeometry(photo.asset.format)
                }
                publish(listOf(complete))
                updateTask(task.id) { it.copy(status = DownloadStatus.COMPLETED, message = "便携 JPEG 已保存；原片未导入") }
                return
            }
            check(photo.asset.sizeBytes > 0 && (sony || photo.asset.sizeBytes != 0xffff_ffffL)) { "当前机身未提供可验证的文件大小，暂不导入此文件" }
            val uri = task.outputUri?.let(Uri::parse) ?: createPendingOriginal(photo).also { output ->
                current = task.copy(outputUri = output.toString())
                try { updateTask(task.id) { current } } catch (error: Exception) {
                    context.contentResolver.delete(output, null, null)
                    throw error
                }
            }
            updateTask(task.id) { it.copy(status = DownloadStatus.RUNNING, message = "正在接收原片") }
            fileReceiver.receive(photo, link, uri, task.received, object : CameraTransferProgress {
                override fun checkCancelled() = checkNotCancelled(task.id)
                override fun received(bytes: Long) {
                    state.update { value -> value.copy(tasks = value.tasks.map { if (it.id == task.id) it.copy(received = bytes) else it }) }
                }
                override suspend fun checkpoint(bytes: Long, message: String) {
                    updateTask(task.id) { it.copy(received = bytes, message = message) }
                }
                override suspend fun awaitResume() = awaitUnpaused(task.id)
            })
            checkNotCancelled(task.id)
            gallery.publish(uri)
            publish(listOf(repository.readCameraDownload(photo.asset, uri)))
            updateTask(task.id) { it.copy(status = DownloadStatus.COMPLETED, message = "原片已保存") }
            Log.i("ProofTray.Camera", "download stage=complete; bytes=${photo.asset.sizeBytes}")
        } catch (error: Exception) {
            Log.w("ProofTray.Camera", "download interrupted; cancelled=${task.id in cancelled}", error)
            if (task.id in cancelled) finishCancelled(task, (state.value.tasks.find { it.id == task.id } ?: current).outputUri)
            else updateTask(task.id) { it.copy(status = DownloadStatus.WAITING, message = error.message ?: "连接中断，请重试") }
        }
    }

    private fun createPendingOriginal(photo: RemotePhoto): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, photo.asset.name)
            put(MediaStore.MediaColumns.MIME_TYPE, requireNotNull(photoMimeType(photo.asset.name)) { "无法确定原片格式" })
            put(MediaStore.MediaColumns.RELATIVE_PATH, GalleryPublisher.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return context.contentResolver.insert(GalleryPublisher.collection, values) ?: error("无法创建待导入文件")
    }

    private suspend fun <T> request(link: PtpLink, read: (PtpLink) -> T): T = requests.withLock {
        check(source.active() === link) { "相机会话已更换" }
        read(link)
    }

    private suspend fun awaitUnpaused(id: String) {
        if (!state.value.paused) return
        state.update { it.copy(transmissionPaused = true) }
        updateTask(id) { it.copy(message = "已暂停，保留断点") }
        try { state.first { !it.paused || !it.connected || id in cancelled } }
        finally { state.update { it.copy(transmissionPaused = false) } }
        checkNotCancelled(id)
    }

    private fun checkNotCancelled(id: String) {
        if (id in cancelled) throw CancellationException("已取消导入")
    }

    /** Removes the partial file of a cancelled task and, for originals, returns the photo to the remote list. */
    private suspend fun finishCancelled(task: CameraDownload, outputUri: String?) {
        val removed = removeOutput(outputUri)
        updateTask(task.id) {
            it.copy(status = DownloadStatus.CANCELLED, outputUri = if (removed) null else it.outputUri,
                message = if (removed) "已取消" else "已取消，未完成文件待清理")
        }
        if (task.variant == DownloadVariant.ORIGINAL) publishRemote(listOf(task))
    }

    /** True once the output no longer exists. */
    private fun removeOutput(value: String?): Boolean {
        if (value == null) return true
        val uri = Uri.parse(value)
        return try {
            context.contentResolver.delete(uri, null, null) > 0 ||
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { !it.moveToFirst() } ?: false
        } catch (_: FileNotFoundException) { true }
        catch (_: Exception) { false }
    }

    private suspend fun updateTask(id: String, change: (CameraDownload) -> CameraDownload) = withTasks { tasks ->
        saveTasks(tasks.map { if (it.id == id) change(it) else it })
    }

    /** Callers hold [taskLock]; the journal is written before the UI sees the change. */
    private fun saveTasks(updated: List<CameraDownload>) {
        journal.save(updated)
        state.update { it.copy(tasks = updated) }
    }

    private suspend fun publishRemote(items: List<CameraDownload>) {
        if (items.isNotEmpty()) publish(items.map { it.photo.asset.copy(downloadState = DownloadState.REMOTE) }, resetQueued = true)
    }

    private suspend fun publish(assets: List<PhotoAsset>, resetQueued: Boolean = false) {
        repository.mergeCameraAssets(assets, resetQueued)
        published()
    }
}
