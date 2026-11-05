package io.github.iteramen.voxdroid

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/** A transparent handoff: retains the temporary grant only until the selected bytes have been acquired. */
class CaptionReceiveActivity : Activity() {
    private var ownsRequest = false
    private var handedOff = false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        active = true
        val controller = CaptionServiceBridge.controller
        val uri = try { captionSharedUri(intent) } catch (_: RuntimeException) { null }
        val provider = uri?.authority?.let { packageManager.resolveContentProvider(it, 0) }
        val source = CaptionApp.forProvider(uri?.authority, provider?.packageName)
        if (controller == null || source == null || uri == null || !controller.canReceive(source)) {
            refuse()
            return
        }
        val accepted = controller.receive(source, open = {
            contentResolver.openInputStream(uri) ?: throw java.io.IOException("unreadable selected audio")
        }, acquired = {
            handedOff = true
            // Remove our reference, including ClipData, before dropping the Activity's grant.
            intent = Intent()
            if (!isFinishing) finish()
        })
        ownsRequest = accepted
        if (!accepted) refuse()
    }

    private fun refuse() {
        Toast.makeText(this, R.string.caption_receive_refused, Toast.LENGTH_LONG).show()
        intent = Intent()
        finish()
    }

    override fun onDestroy() {
        if (ownsRequest && !handedOff) CaptionServiceBridge.controller?.cancel()
        active = false
        super.onDestroy()
    }

    override fun onResume() { super.onResume(); active = true }
    override fun onPause() { active = false; super.onPause() }

    companion object {
        @Volatile internal var active = false
            private set
    }
}

/** Both representations must name the same single content URI. Never follow a caller-supplied file or network URL. */
internal fun captionSharedUri(intent: Intent): Uri? {
    val stream = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: return null
    val clip = intent.clipData
    return stream.takeIf {
        captionShareAllowed(
            send = intent.action == Intent.ACTION_SEND,
            content = stream.scheme == "content" && !stream.authority.isNullOrBlank(),
            grant = intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
            clipCount = clip?.itemCount,
            clipMatches = clip == null || (clip.itemCount == 1 && clip.getItemAt(0).uri == stream),
            dataMatches = intent.data == null || intent.data == stream,
        )
    }
}

internal object CaptionServiceBridge {
    @Volatile private var reference: java.lang.ref.WeakReference<CaptionController>? = null
    var controller: CaptionController?
        get() = reference?.get()
        set(value) { reference = value?.let { java.lang.ref.WeakReference(it) } }
}
