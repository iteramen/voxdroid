package io.github.iteramen.voxdroid

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class TryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.try_dictation)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.try_hint).setText(when (Settings(this).tapTrigger) {
            TapTrigger.Floating -> R.string.try_hint
            TapTrigger.AccessibilityButton -> R.string.try_hint_accessibility_button
            TapTrigger.None -> R.string.try_hint_none
        })
    }

    companion object {
        fun intent(context: Context) = Intent(context, TryActivity::class.java)
    }
}
