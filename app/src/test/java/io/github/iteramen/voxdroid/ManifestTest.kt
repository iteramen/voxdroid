package io.github.iteramen.voxdroid

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Reads the source manifest: `android.jar`'s `PackageManager` is a stub under unit tests. */
class ManifestTest {

    @Test
    fun declaresTheMicrophoneTheNetworkAndTheMicrophoneServiceAndNothingElse() {
        val elements = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifestFile())
            .getElementsByTagName("uses-permission")
        val declared = (0 until elements.length)
            .map { (elements.item(it) as Element).getAttributeNS(ANDROID_NS, "name") }

        assertEquals(
            setOf(
                "android.permission.RECORD_AUDIO",
                "android.permission.INTERNET",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_MICROPHONE",
            ),
            declared.toSet(),
        )
        // A set hides a duplicate element; the count does not.
        assertEquals(4, declared.size)
    }

    @Test
    fun removesTheEmoji2InitializerFromTheMergedInitializationProvider() {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifestFile())
        val providers = document.getElementsByTagName("provider")
        val startup = (0 until providers.length).map { providers.item(it) as Element }
            .filter { it.getAttributeNS(ANDROID_NS, "name") == "androidx.startup.InitializationProvider" }
        assertEquals("one InitializationProvider element", 1, startup.size)
        assertEquals("merge", startup[0].getAttributeNS(TOOLS_NS, "node"))

        val metas = startup[0].getElementsByTagName("meta-data")
        val removed = (0 until metas.length).map { metas.item(it) as Element }
            .filter { it.getAttributeNS(ANDROID_NS, "name") == "androidx.emoji2.text.EmojiCompatInitializer" }
        assertEquals("one EmojiCompatInitializer meta-data", 1, removed.size)
        assertEquals("remove", removed[0].getAttributeNS(TOOLS_NS, "node"))
        // Only the one initializer is named, so the other two stay.
        assertEquals(1, metas.length)
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val TOOLS_NS = "http://schemas.android.com/tools"

        fun manifestFile(): File = sequenceOf(
            "src/main/AndroidManifest.xml",
            "app/src/main/AndroidManifest.xml",
        ).map(::File).first(File::exists)
    }
}
