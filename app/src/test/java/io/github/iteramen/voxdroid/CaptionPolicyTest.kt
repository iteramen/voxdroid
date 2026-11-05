package io.github.iteramen.voxdroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Reads the backup rules and the main sources off disk; `app/build.gradle.kts` lists both as test inputs. */
class CaptionPolicyTest {

    @Test
    fun everyFileTheCacheOwnsIsExcludedFromCloudBackupAndDeviceTransfer() {
        val filesDir = File("files")
        val dir = captionDir(filesDir)
        val entry = File(dir, CaptionKey("pcm16-sha256.v1", "a".repeat(64), "p").digest + CaptionCache.ENTRY_SUFFIX)
        val owned = listOf(dir, entry, File(dir, CaptionCache.STATE), CaptionCache.sidecarOf(entry), CaptionCache.sidecarOf(File(dir, CaptionCache.STATE)))
            .map { it.relativeTo(filesDir).invariantSeparatorsPath }

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(rulesFile())
        for (section in listOf("cloud-backup", "device-transfer")) {
            val excludes = (document.getElementsByTagName(section).item(0) as Element).getElementsByTagName("exclude")
            val rules = (0 until excludes.length).map { excludes.item(it) as Element }
            val filePaths = rules.filter { it.getAttribute("domain") == "file" }.map { it.getAttribute("path").trimEnd('/') }
            for (path in owned) {
                assertTrue("$section excludes $path", filePaths.any { path == it || path.startsWith("$it/") })
            }
            assertTrue("$section still excludes secrets.xml", rules.any { it.getAttribute("domain") == "sharedpref" && it.getAttribute("path") == "secrets.xml" })
        }
    }

    @Test
    fun nothingInTheAppUsesTheCacheYet() {
        val sources = sourceDir().walk().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("the source walk must find the cache itself", sources.any { it.name == "CaptionCache.kt" })
        val users = sources.filter { it.name != "CaptionCache.kt" && CACHE_NAMES.containsMatchIn(it.readText()) }
        assertEquals("dictation, the microphone and delivery stay unconnected", emptyList<String>(), users.map { it.name })

        val cache = sources.single { it.name == "CaptionCache.kt" }.readText()
        val reached = DICTATION_NAMES.findAll(cache).map { it.value }.toSet()
        assertEquals("the cache reaches into nothing that dictates", emptySet<String>(), reached)
    }

    private companion object {
        val CACHE_NAMES = Regex("""\b(Caption\w*|openCaptionCache|captionDir|CAPTION_\w+)\b""")
        val DICTATION_NAMES = Regex(
            """\b(AudioRecorder|AudioRecord|Take|TakeHost|SttClient|PolishClient|boundedPost|deliver|insert|copy|commitText|""" +
                """VoxdroidA11yService|Settings|SettingsStore|getSharedPreferences|Log)\b""",
        )

        fun rulesFile(): File = sequenceOf(
            "src/main/res/xml/data_extraction_rules.xml",
            "app/src/main/res/xml/data_extraction_rules.xml",
        ).map(::File).first(File::exists)

        fun sourceDir(): File = sequenceOf("src/main/java", "app/src/main/java").map(::File).first(File::exists)
    }
}
