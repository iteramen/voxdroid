package io.github.iteramen.voxdroid

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.annotation.XmlRes
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class SectionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.section)
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = intent.getStringExtra(EXTRA_TITLE_TEXT) ?: getString(intent.getIntExtra(EXTRA_TITLE, 0))
            setNavigationOnClickListener { finish() }
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(
                    R.id.section_content,
                    SectionFragment.of(intent.getIntExtra(EXTRA_XML, 0), intent.getStringExtra(EXTRA_CODE)),
                )
                .commit()
        }
    }

    companion object {
        private const val EXTRA_XML = "xml"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TITLE_TEXT = "title_text"
        private const val EXTRA_CODE = "code"
        fun intent(context: Context, @XmlRes xml: Int, @StringRes title: Int): Intent =
            Intent(context, SectionActivity::class.java).putExtra(EXTRA_XML, xml).putExtra(EXTRA_TITLE, title)

        fun intent(context: Context, @XmlRes xml: Int, title: String, code: String): Intent =
            Intent(context, SectionActivity::class.java).putExtra(EXTRA_XML, xml)
                .putExtra(EXTRA_TITLE_TEXT, title).putExtra(EXTRA_CODE, code)
    }
}
