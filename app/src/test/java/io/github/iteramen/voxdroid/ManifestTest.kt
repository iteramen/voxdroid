package io.github.iteramen.voxdroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Reads the source manifest: `android.jar`'s `PackageManager` is a stub under unit tests. */
class ManifestTest {

    @Test
    fun configurationBackupHasNoExternalEntryPoint() {
        val activities = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifestFile()).getElementsByTagName("activity")
        val backup = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(ANDROID_NS, "name") == ".ConfigBackupActivity" }
        assertEquals("false", backup.getAttributeNS(ANDROID_NS, "exported"))
        assertEquals(0, backup.getElementsByTagName("intent-filter").length)
    }

    @Test
    fun whatsappCaptionSelectorsReceiveTheViewIdsTheyNeed() {
        val file = sequenceOf("src/main/res/xml/a11y_entry.xml", "app/src/main/res/xml/a11y_entry.xml")
            .map(::File).first(File::exists)
        val service = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file).documentElement
        assertTrue("Without view-ID reporting WhatsApp header, selection and Share selectors cannot match",
            "flagReportViewIds" in service.getAttributeNS(ANDROID_NS, "accessibilityFlags").split('|'))
    }

    @Test
    fun theCaptionReceiverIsAnExplicitSingleAudioShareSurface() {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifestFile())
        val activities = document.getElementsByTagName("activity")
        val receivers = (0 until activities.length).map { activities.item(it) as Element }
            .filter { it.getAttributeNS(ANDROID_NS, "name") == ".CaptionReceiveActivity" }
        assertEquals(1, receivers.size)
        val receiver = receivers.single()
        assertEquals("true", receiver.getAttributeNS(ANDROID_NS, "exported"))
        assertEquals("true", receiver.getAttributeNS(ANDROID_NS, "excludeFromRecents"))
        val actions = receiver.getElementsByTagName("action")
        assertEquals(listOf("android.intent.action.SEND"), (0 until actions.length).map {
            (actions.item(it) as Element).getAttributeNS(ANDROID_NS, "name")
        })
        val data = receiver.getElementsByTagName("data")
        assertEquals(setOf("audio/*", "application/ogg"), (0 until data.length).map {
            (data.item(it) as Element).getAttributeNS(ANDROID_NS, "mimeType")
        }.toSet())
        val queries = document.getElementsByTagName("queries")
        assertEquals(1, queries.length)
        val packages = (queries.item(0) as Element).getElementsByTagName("package")
        assertEquals(setOf("com.whatsapp", "org.telegram.messenger"), (0 until packages.length).map {
            (packages.item(it) as Element).getAttributeNS(ANDROID_NS, "name")
        }.toSet())
    }

    @Test
    fun declaresOnlyMicrophoneNetworkAndTheTwoForegroundServiceTypes() {
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
                "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            ),
            declared.toSet(),
        )
        // A set hides a duplicate element; the count does not.
        assertEquals(5, declared.size)
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
