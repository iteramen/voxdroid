package io.github.iteramen.voxdroid

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One service-owned caption operation. All UI/session transitions run on the main thread. */
internal class CaptionController(
    private val service: AccessibilityService,
    private val settings: Settings,
    private val dictationIdle: () -> Boolean,
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1))
    private val processor = openCaptionProcessor(service)
    private val cache = openCaptionCache(service)
    private val session = CaptionSession(SystemClock::elapsedRealtime)
    private val panel = CaptionPanel(service, { cancel() }, ::refresh)
    private var lastOrigin: CaptionOrigin? = null
    private var lastOriginAt = 0L
    private var ticket: CaptionTicket? = null
    private var pendingResult: CaptionOutcome? = null
    private var currentCaption: CaptionOutcome.Captioned? = null
    private var forceFingerprint: String? = null
    private var returnPending: Long? = null

    fun enabled(): Boolean = settings.voiceCaptions

    fun observe(event: AccessibilityEvent?) {
        if (!enabled() || !dictationIdle()) { cancel(); return }
        event ?: return
        val root = service.getRootInActiveWindow(0)
        val source = CaptionAccessibility.source(root)
        val eventPackage = event.packageName?.toString()
        if (eventPackage == service.packageName && event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                root?.packageName?.toString() == service.packageName && !CaptionReceiveActivity.active) cancel()
            return
        }
        if (source == null) {
            val packageName = root?.packageName?.toString()
            val chooser = packageName in CHOOSERS && session.phase in setOf(CaptionPhase.Selected, CaptionPhase.Sharing)
            val receiver = packageName == service.packageName && (CaptionReceiveActivity.active || session.phase == CaptionPhase.Returning)
            if (session.active && (chooser || receiver) && session.phase in HANDOFF_PHASES) {
                if (chooser && session.phase == CaptionPhase.Selected) session.share(session.id())
                return
            }
            cancel()
            return
        }
        root ?: return
        val origin = CaptionAccessibility.origin(root, service.resources.displayMetrics.density)
        if (session.phase in setOf(CaptionPhase.Processing, CaptionPhase.Shown)) {
            // Header equality is not a durable chat identity: two contacts can have the same name.
            // Any source-view mutation or interaction invalidates the visible result conservatively.
            if (!session.stableSource(origin, eventPackage == source.packageName && event.eventType in INVALIDATING_EVENTS)) {
                cancel(clearOrigin = false)
            } else return
        }
        if (session.phase == CaptionPhase.Returning || session.phase == CaptionPhase.Reading) {
            if (eventPackage == source.packageName && event.eventType in setOf(AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_SCROLLED)) {
                cancel(); return
            }
            if (origin != null && !session.currentOrigin!!.same(origin)) { cancel(); return }
            if (session.phase == CaptionPhase.Returning) scheduleReturn(session.id())
            return
        }
        if (origin != null) {
            if (lastOrigin != null && !lastOrigin!!.same(origin)) cancel(clearOrigin = false)
            lastOrigin = origin
            lastOriginAt = SystemClock.elapsedRealtime()
        }
        val selected = CaptionAccessibility.selection(root)
        if (selected.count == 1 && selected.voice != null) {
            val known = lastOrigin
            if (known != null && known.source == source && known.window == root.windowId && SystemClock.elapsedRealtime() - lastOriginAt < ORIGIN_MS) {
                if (!session.active) {
                    session.select(known)
                    ticket = cache.begin()
                }
            }
        } else if (selected.count > 1 && session.phase == CaptionPhase.Selected) {
            cancel(clearOrigin = false)
        }
    }

    /** Returns true only when this explicit button action belongs to captions, leaving ordinary dictation alone. */
    fun trigger(): Boolean {
        if (!enabled()) return false
        val root = service.getRootInActiveWindow(0) ?: return false
        val source = CaptionAccessibility.source(root) ?: return false
        val selection = CaptionAccessibility.selection(root)
        if (selection.count == 0) return false
        if (!dictationIdle()) { notice(R.string.caption_finish_dictation); return true }
        if (selection.count != 1 || selection.voice == null) { notice(R.string.caption_select_one); return true }
        val known = lastOrigin
        if (known == null || known.source != source || known.window != root.windowId || SystemClock.elapsedRealtime() - lastOriginAt >= ORIGIN_MS) {
            cancel(); notice(R.string.caption_select_again); return true
        }
        cancel(clearOrigin = false, clearRefresh = false)
        val id = session.select(known)
        ticket = cache.begin()
        session.share(id)
        val action = selection.share ?: selection.overflow
        if (action?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) {
            cancel(clearOrigin = false); notice(R.string.caption_share_unavailable); return true
        }
        if (source == CaptionApp.WhatsApp) awaitWhatsAppShare(id, 0)
        return true
    }

    private fun awaitWhatsAppShare(id: Long, attempt: Int) {
        if (!session.current(id)) return
        val root = service.getRootInActiveWindow(0)
        if (root != null && CaptionAccessibility.source(root) == CaptionApp.WhatsApp) {
            val share = CaptionAccessibility.whatsappShare(root)
            if (share != null) {
                if (!share.performAction(AccessibilityNodeInfo.ACTION_CLICK)) cancel()
                return
            }
        }
        if (attempt >= 10) { cancel(); notice(R.string.caption_share_unavailable) }
        else main.postDelayed({ awaitWhatsAppShare(id, attempt + 1) }, 100)
    }

    fun canReceive(source: CaptionApp): Boolean = enabled() && dictationIdle() && session.currentOrigin?.source == source &&
        session.phase in setOf(CaptionPhase.Selected, CaptionPhase.Sharing)

    /** The URI lives only in the unstarted job/receiver. No URI or stream becomes a cache key or stored field. */
    fun receive(source: CaptionApp, open: () -> InputStream, acquired: () -> Unit): Boolean {
        if (!canReceive(source)) return false
        val id = session.receive(source) ?: return false
        val requestTicket = ticket
        val snapshot = captionSettings(settings, readBuiltInPrompt(service.resources))
        val bypass = forceFingerprint
        pendingResult = null
        currentCaption = null
        worker.queue.clear() // Queued jobs have not opened a stream and hold no audio resources.
        try {
            worker.execute {
                var acquisitionSignalled = false
                val outcome = try {
                    if (!session.current(id)) return@execute
                    processor.process(open(), snapshot, REQUEST_BUDGET_MS, lookup = { key ->
                        if (key.audioSha256 == bypass || !session.current(id) || requestTicket == null || cache.begin()?.epoch != requestTicket.epoch) null
                        else (cache.get(key) as? CaptionLookup.Hit)?.caption
                    }, onAcquired = {
                        acquisitionSignalled = true
                        main.post {
                            acquired()
                            if (session.returning(id)) scheduleReturn(id)
                        }
                    })
                } catch (_: Exception) {
                    CaptionOutcome.ReadFailed
                } finally {
                    if (!acquisitionSignalled) main.post {
                        acquired()
                        if (session.returning(id)) scheduleReturn(id)
                    }
                }
                main.post {
                    if (!session.current(id)) return@post
                    pendingResult = outcome
                    if (session.phase == CaptionPhase.Processing) showResult(id, requestTicket)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            cancel(); return false
        }
        return true
    }

    private fun scheduleReturn(id: Long) {
        if (returnPending == id) return
        returnPending = id
        main.postDelayed({
            if (returnPending == id) returnPending = null
            if (!session.current(id) || session.phase != CaptionPhase.Returning) return@postDelayed
            val root = service.getRootInActiveWindow(0)
            val origin = CaptionAccessibility.origin(root, service.resources.displayMetrics.density)
            if (session.returned(id, origin)) {
                panel.working(session.currentOrigin!!.source)
                if (pendingResult != null) showResult(id, ticket)
            } else if (session.waitingForReturn(id) && (root == null ||
                    (root.packageName?.toString() == service.packageName && CaptionReceiveActivity.active) ||
                    (CaptionAccessibility.source(root) == session.currentOrigin?.source && origin == null))) {
                scheduleReturn(id)
            } else {
                cancel()
            }
        }, 300)
    }

    private fun showResult(id: Long, requestTicket: CaptionTicket?) {
        if (!session.current(id)) return
        val root = service.getRootInActiveWindow(0)
        if (!session.currentOrigin!!.same(CaptionAccessibility.origin(root, service.resources.displayMetrics.density))) { cancel(); return }
        val result = pendingResult ?: return
        pendingResult = null
        if (!session.shown(id)) return
        if (result is CaptionOutcome.Captioned) {
            var remembered = result.savedAtMs != null
            if (result.savedAtMs == null && result.key != null && requestTicket != null) {
                remembered = cache.put(requestTicket, result.key, result.raw, result.displayed, result.cleanup) == CaptionWrite.Saved
            }
            currentCaption = result
            forceFingerprint = null
            panel.show(result, remembered)
        } else {
            panel.notice(service.getString(when (result) {
                CaptionOutcome.Silence -> R.string.caption_silence
                CaptionOutcome.TooLarge -> R.string.caption_too_large
                CaptionOutcome.TooLong -> R.string.caption_too_long
                CaptionOutcome.Unsupported, CaptionOutcome.Malformed -> R.string.caption_unsupported
                CaptionOutcome.TimedOut -> R.string.caption_timeout
                CaptionOutcome.Cancelled -> R.string.caption_cancelled
                else -> R.string.caption_failed
            }))
        }
    }

    private fun refresh() {
        val fingerprint = currentCaption?.key?.audioSha256
        if (fingerprint == null) { cancel(); notice(R.string.caption_select_refresh); return }
        forceFingerprint = fingerprint
        val visible = CaptionAccessibility.origin(service.getRootInActiveWindow(0), service.resources.displayMetrics.density)
        if (session.currentOrigin?.same(visible) == true) {
            lastOrigin = visible
            lastOriginAt = SystemClock.elapsedRealtime()
        }
        cancel(clearOrigin = false, clearRefresh = false)
        notice(R.string.caption_select_refresh)
    }

    fun cancel(clearOrigin: Boolean = true, clearRefresh: Boolean = true) {
        processor.cancel()
        session.invalidate()
        worker.queue.clear()
        pendingResult = null
        currentCaption = null
        ticket = null
        returnPending = null
        panel.hide()
        if (clearOrigin) lastOrigin = null
        if (clearRefresh) forceFingerprint = null
    }

    fun destroy() { cancel(); worker.shutdownNow() }
    fun preferencesChanged() { if (!enabled()) cancel() }
    private fun notice(text: Int) = Toast.makeText(service, text, Toast.LENGTH_LONG).show()

    companion object {
        private const val ORIGIN_MS = 120_000L
        private const val REQUEST_BUDGET_MS = 600_000L
        private val CHOOSERS = setOf("com.android.intentresolver", "android")
        private val HANDOFF_PHASES = setOf(CaptionPhase.Selected, CaptionPhase.Sharing, CaptionPhase.Reading, CaptionPhase.Returning)
        private val INVALIDATING_EVENTS = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_VIEW_CLICKED,
        )
    }
}
