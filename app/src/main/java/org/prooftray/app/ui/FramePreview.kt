package org.prooftray.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.prooftray.app.domain.FrameTemplate
import org.prooftray.app.frame.FrameRendered
import org.prooftray.app.frame.FrameRenderSession
import org.prooftray.app.frame.FrameRepository

private const val PREVIEW_EDGE = 1200

internal data class FramePreviewRequest(val source: EditBitmap?, val template: FrameTemplate, val metadata: Map<String, String>, val revision: Int)
internal data class FramePreviewResult(val request: FramePreviewRequest? = null, val rendered: FrameRendered? = null,
    val image: EditBitmap? = null, val error: String? = null)

@Composable
internal fun rememberFramePreview(request: FramePreviewRequest, repository: FrameRepository, enabled: Boolean): FramePreviewResult {
    val lease = remember(request.source, repository) { request.source?.retain() }
    val session = remember(lease) { lease?.let { FrameRenderSession(it.bitmap, repository) } }
    DisposableEffect(session) {
        onDispose {
            // Closing the synchronized session can wait for an in-flight render.
            if (session != null) CoroutineScope(Dispatchers.Default).launch {
                try { session.close() } finally { lease?.close() }
            }
        }
    }
    return rememberEditResult(FramePreviewResult(), session, request, enabled) {
        if (!enabled || session == null || lease == null) return@rememberEditResult FramePreviewResult()
        delay(35)
        renderOrMessage("边框预览内存不足", "无法绘制边框", { FramePreviewResult(error = it) }) {
            withContext(editRenderDispatcher) {
                val snapshot = repository.snapshot(request.template, request.metadata)
                val rendered = session.render(snapshot, PREVIEW_EDGE, cancelled = { coroutineContext.ensureActive() })
                FramePreviewResult(request, rendered, own(rendered.bitmap))
            }
        }
    }
}
