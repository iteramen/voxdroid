package io.github.iteramen.voxdroid

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.util.concurrent.Executors

/** No exported entry point, plaintext file, saved password or persistent URI permission. */
class ConfigBackupActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val store by lazy { ConfigBackupStore(this) }
    private var pendingExport: ByteArray? = null
    private var busy = false
    private var passwordDialog: AlertDialog? = null
    private lateinit var status: TextView

    private val create = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val bytes = pendingExport.also { pendingExport = null }
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null && bytes != null) {
            work {
                try {
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
                    val saved = contentResolver.openInputStream(uri)!!.use(ConfigBackup::readBounded)
                    try { check(saved.contentEquals(bytes)) } finally { saved.fill(0) }
                    R.string.backup_saved
                } finally { bytes.fill(0) }
            }
        } else { bytes?.fill(0); show(R.string.backup_cancelled) }
    }
    private val open = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.data?.let(::restore)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.config_backup)
        status = findViewById(R.id.backup_status)
        findViewById<View>(R.id.backup_back).setOnClickListener { finish() }
        findViewById<View>(R.id.backup_export).setOnClickListener { password(true) { chars ->
            work {
                val bytes = try {
                    val snapshot = store.snapshot()
                    ConfigBackup.encrypt(snapshot, chars).also { check(ConfigBackup.decrypt(it, chars) == snapshot) }
                } finally { chars.fill('\u0000') }
                runOnUiThread {
                    if (isDestroyed || isFinishing) bytes.fill(0) else {
                        pendingExport?.fill(0)
                        pendingExport = bytes
                        create.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/octet-stream"
                            putExtra(Intent.EXTRA_TITLE, "voxdroid-settings.vdxb")
                        })
                    }
                }
                R.string.backup_choose_destination
            }
        } }
        findViewById<View>(R.id.backup_import).setOnClickListener {
            open.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            })
        }
    }

    private fun restore(uri: Uri) = password(false) { chars ->
        work {
            val snapshot = try {
                val bytes = contentResolver.openInputStream(uri)!!.use(ConfigBackup::readBounded)
                try { ConfigBackup.decrypt(bytes, chars) } finally { bytes.fill(0) }
            } finally { chars.fill('\u0000') }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    if (CaptionServiceBridge.controller?.configurationBusy() == true) show(R.string.backup_busy)
                    else try { store.restore(snapshot); show(R.string.backup_restored) }
                    catch (_: Exception) { show(R.string.backup_restore_failed) }
                }
            }
            null
        }
    }

    private fun password(export: Boolean, ready: (CharArray) -> Unit) {
        if (busy || passwordDialog != null) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
        }
        fun field(hintText: Int) = EditText(this).apply {
            hint = getString(hintText)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isSaveEnabled = false
            setSingleLine(true)
            box.addView(this)
        }
        val first = field(R.string.backup_passphrase)
        val second = if (export) field(R.string.backup_confirm_passphrase) else null
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (export) R.string.backup_export else R.string.backup_import)
            .setMessage(if (export) R.string.backup_password_help else R.string.backup_replace_help)
            .setView(box).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null).create()
        passwordDialog = dialog
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val chars = CharArray(first.length()) { first.text[it] }
                val confirm = second?.let { field -> CharArray(field.length()) { field.text[it] } }
                if (chars.size !in 12..1024 || (confirm != null && !chars.contentEquals(confirm))) {
                    chars.fill('\u0000'); confirm?.fill('\u0000')
                    first.error = getString(R.string.backup_password_invalid)
                } else {
                    confirm?.fill('\u0000')
                    dialog.dismiss()
                    ready(chars)
                }
            }
        }
        dialog.setOnDismissListener { first.text.clear(); second?.text?.clear(); passwordDialog = null }
        dialog.show()
    }

    private fun work(action: () -> Int?) {
        if (busy) return
        busy = true
        buttons(false)
        show(R.string.backup_working)
        worker.execute {
            val message = try { action() } catch (_: Exception) { R.string.backup_failed }
            runOnUiThread {
                busy = false
                if (!isDestroyed && !isFinishing) { buttons(true); message?.let(::show) }
            }
        }
    }

    private fun buttons(enabled: Boolean) {
        findViewById<MaterialButton>(R.id.backup_export).isEnabled = enabled
        findViewById<MaterialButton>(R.id.backup_import).isEnabled = enabled
    }
    private fun show(message: Int) { status.setText(message) }

    override fun onDestroy() {
        passwordDialog?.dismiss()
        pendingExport?.fill(0)
        worker.shutdown() // A queued operation must reach its finally block and clear the password.
        super.onDestroy()
    }
}
