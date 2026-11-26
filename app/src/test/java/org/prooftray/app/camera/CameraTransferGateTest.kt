package org.prooftray.app.camera

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CameraTransferGateTest {
    @Test fun transferAdmissionPrecedesQueuePublication() = runBlocking {
        val gate = CameraTransferGate()
        val admitted = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val worker = launch { gate.transfer { admitted.complete(Unit); finish.await() } }
        admitted.await()
        assertFalse(gate.change({ false }) { fail("cut off a transfer") })
        finish.complete(Unit)
        worker.join()
        assertTrue(gate.change({ false }) {})
    }

    @Test fun failedWorkerReleasesAdmission() = runBlocking {
        val gate = CameraTransferGate()
        runCatching { gate.transfer { error("interrupted") } }
        assertTrue(gate.change({ false }) {})
    }

    @Test fun pausedAndQueuedWorkBlockButInterruptedWorkCanReconnect() {
        val asset = org.prooftray.app.domain.PhotoAsset("id", "camera:id", "test.NEF", org.prooftray.app.domain.MediaFormat.RAW)
        val photo = RemotePhoto(asset, 1, 1, "old-camera", "")
        for (status in DownloadStatus.entries) {
            val task = CameraDownload("id", photo, status, received = 12, outputUri = "content://partial")
            val state = CameraState(paused = true, tasks = listOf(task))
            assertEquals(status == DownloadStatus.RUNNING || status == DownloadStatus.QUEUED,
                transferBlocksCameraChange(state, AutoDownloadState()))
            assertEquals(task, state.disconnected(2).tasks.single())
        }
    }

    @Test fun disconnectMakesQueuedWorkRecoverableOnlyForItsSource() {
        val asset = org.prooftray.app.domain.PhotoAsset("id", "camera:id", "test.NEF", org.prooftray.app.domain.MediaFormat.RAW)
        val queued = CameraDownload("id", RemotePhoto(asset, 1, 1, "A", ""), received = 12, outputUri = "content://partial")
        assertEquals(queued, queued.waitAfterDisconnect("B"))
        val waiting = queued.waitAfterDisconnect("A")
        assertEquals(DownloadStatus.WAITING, waiting.status)
        assertEquals(queued.outputUri, waiting.outputUri)
        assertEquals(queued.photo, waiting.photo)
        assertEquals(12L, waiting.received)
    }

    @Test fun manualCheckAndBluetoothBatchBlockChange() {
        assertTrue(transferBlocksCameraChange(CameraState(), AutoDownloadState(checking = true)))
        assertTrue(transferBlocksCameraChange(CameraState(), AutoDownloadState(transferring = true)))
        assertFalse(transferBlocksCameraChange(CameraState(), AutoDownloadState(watching = true)))
    }

    @Test fun aConfirmedSearchSwitchStillWaitsForANewlyAdmittedTransfer() = runBlocking {
        val gate = CameraTransferGate()
        val origin = CameraState(generation = 7, sonyControlAddress = "A").changeContext(BleState(), "A")
        val admitted = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val worker = launch { gate.transfer { admitted.complete(Unit); finish.await() } }
        admitted.await()
        var selected = "A"
        val switch = {
            origin.requireApproval(origin.switchesDevice("B", CameraMake.SONY, "B"), origin)
            selected = "B"
        }
        assertFalse(gate.change({ false }, switch))
        assertEquals("A", selected)
        finish.complete(Unit)
        worker.join()
        assertTrue(gate.change({ false }, switch))
        assertEquals("B", selected)
    }
}
