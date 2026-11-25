package org.prooftray.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.prooftray.app.camera.CameraMake
import org.prooftray.app.domain.AppSettings
import org.prooftray.app.domain.DownloadState
import org.prooftray.app.domain.DownloadVariant
import org.prooftray.app.domain.MediaFormat
import org.prooftray.app.domain.PhotoAsset
import org.prooftray.app.domain.PhotoBrowserState
import org.prooftray.app.domain.PhotoCollection
import org.prooftray.app.domain.SavedCamera
import org.prooftray.app.domain.cameraCaptureKey
import org.prooftray.app.domain.cameraFileKey
import org.prooftray.app.domain.collectionAssets

class OriginalStateTest {
    private fun cameraFile(id: String, name: String, format: MediaFormat, state: DownloadState): PhotoAsset {
        val source = "Z6II:sample"
        val capture = 1700000000000L
        return PhotoAsset(id, "sample:$id", name, format, sourceId = source, downloadState = state,
            cameraCaptureKey = cameraCaptureKey(source, name, capture), cameraFileKey = cameraFileKey(source, "card:1", name, 1024, capture))
    }

    private val raw = cameraFile("raw", "DSC_0002.NEF", MediaFormat.RAW, DownloadState.REMOTE)

    private fun importState(asset: PhotoAsset, variant: DownloadVariant): OriginalState =
        originalStateResolver(listOf(asset), importing = true, variant = variant)(asset)

    @Test fun originalModeIgnoresPortableJpegHistory() {
        assertEquals(OriginalState.CAMERA, importState(raw.copy(jpegCopyImportedAtMillis = 1L), DownloadVariant.ORIGINAL))
    }

    @Test fun originalModeRecognizesOriginalHistoryOrDownloadedFile() {
        assertEquals(OriginalState.IMPORTED, importState(raw.copy(importedAtMillis = 1L), DownloadVariant.ORIGINAL))
        assertEquals(OriginalState.IMPORTED, importState(raw.copy(downloadState = DownloadState.DOWNLOADED), DownloadVariant.ORIGINAL))
    }

    @Test fun portableModeChecksOnlyPortableJpegHistory() {
        assertEquals(OriginalState.IMPORTED, importState(raw.copy(jpegCopyImportedAtMillis = 1L), DownloadVariant.CAMERA_JPEG))
        val originalOnly = raw.copy(importedAtMillis = 1L, downloadState = DownloadState.DOWNLOADED)
        assertEquals(OriginalState.CAMERA, importState(originalOnly, DownloadVariant.CAMERA_JPEG))
    }

    @Test fun queuedTakesPriorityOverBothImportHistories() {
        val queued = raw.copy(downloadState = DownloadState.QUEUED, importedAtMillis = 1L, jpegCopyImportedAtMillis = 2L)
        for (variant in DownloadVariant.entries) assertEquals(OriginalState.QUEUED, importState(queued, variant))
    }

    @Test fun switchingToSonyUsesOriginalForTheBadgeAndNewFilter() {
        val photo = raw.copy(inImportList = true, jpegCopyImportedAtMillis = 1L)
        val browser = PhotoBrowserState(collection = PhotoCollection.CAMERA_IMPORT)
        val settings = AppSettings(manualVariant = DownloadVariant.CAMERA_JPEG)
        fun newPhotos(variant: DownloadVariant) = collectionAssets(listOf(photo), browser, photo.sourceId, setOf(photo.id), variant)

        assertEquals(emptyList<PhotoAsset>(), newPhotos(settings.effectiveManualVariant))
        val sony = settings.withCamera(SavedCamera("1", CameraMake.SONY, "Sony", "sony")).effectiveManualVariant
        assertEquals(DownloadVariant.ORIGINAL, sony)
        assertEquals(OriginalState.CAMERA, importState(photo, sony))
        assertEquals(listOf(photo), newPhotos(sony))
    }

    @Test fun importingPairedJpegDoesNotCountAsImportingNef() {
        val nef = cameraFile("nef", "DSC_0001.NEF", MediaFormat.RAW, DownloadState.REMOTE)
        val jpeg = cameraFile("jpeg", "DSC_0001.JPG", MediaFormat.JPEG, DownloadState.DOWNLOADED)
        val copy = jpeg.copy(id = "portable", companionOfId = nef.id, cameraFileKey = "portable-key")
        assertEquals(nef.cameraCaptureKey, jpeg.cameraCaptureKey)
        assertEquals(OriginalState.JPEG_ONLY, originalStateResolver(listOf(nef, jpeg, copy), false)(copy))
        assertEquals(OriginalState.JPEG_ONLY, originalStateResolver(listOf(jpeg, copy), false)(copy))
        val orphan = copy.copy(cameraCaptureKey = null)
        assertEquals(OriginalState.JPEG_ONLY, originalStateResolver(listOf(orphan), false)(orphan))
        val localNef = nef.copy(downloadState = DownloadState.DOWNLOADED)
        assertEquals(OriginalState.LOCAL, originalStateResolver(listOf(localNef, jpeg, copy), false)(copy))
        assertEquals(OriginalState.LOCAL, originalStateResolver(listOf(nef, localNef.copy(id = "wifi"), jpeg, copy), false)(copy))
        assertEquals(OriginalState.LOCAL, originalStateResolver(listOf(localNef.copy(id = "wifi"), jpeg, copy), false)(copy))
        val copyOfJpeg = copy.copy(companionOfId = jpeg.id)
        assertEquals(OriginalState.LOCAL, originalStateResolver(listOf(nef, jpeg, copyOfJpeg), false)(copyOfJpeg))
    }

    @Test fun appExportsAreNotLabeledAsCameraJpegCompanions() {
        val exported = cameraFile("export", "edited.jpg", MediaFormat.JPEG, DownloadState.DOWNLOADED).copy(derivedFromId = raw.id)
        assertEquals(OriginalState.NO_ORIGINAL, originalStateResolver(listOf(raw, exported), false)(exported))
    }
}
