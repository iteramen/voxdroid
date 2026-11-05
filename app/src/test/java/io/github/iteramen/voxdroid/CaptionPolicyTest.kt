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
    fun everyFileTheCaptionsOwnIsExcludedFromCloudBackupAndDeviceTransfer() {
        val filesDir = File("files")
        val dir = captionDir(filesDir)
        val entry = File(dir, CaptionKey("pcm16-sha256.v1", "a".repeat(64), "p").digest + CaptionCache.ENTRY_SUFFIX)
        val spoolDir = captionSpoolDir(filesDir)
        val spool = File(spoolDir, "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0$CAPTION_SPOOL_SUFFIX")
        val owned = listOf(
            dir, entry, File(dir, CaptionCache.STATE), CaptionCache.sidecarOf(entry), CaptionCache.sidecarOf(File(dir, CaptionCache.STATE)),
            spoolDir, spool,
        ).map { it.relativeTo(filesDir).invariantSeparatorsPath }

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
    fun onlyTheExplicitCaptionIntegrationUsesTheFoundation() {
        val sources = sourceDir().walk().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("the source walk must find the foundation itself", sources.map { it.name }.containsAll(FOUNDATION))
        val users = sources.filter { it.name !in FOUNDATION && CAPTION_NAMES.containsMatchIn(it.readText()) }
        val allowed = setOf("CaptionController.kt", "CaptionReceiveActivity.kt", "CaptionSession.kt", "CaptionAccessibility.kt",
            "CaptionPanel.kt", "VoxdroidA11yService.kt", "Settings.kt", "SettingsActivity.kt", "SectionFragment.kt")
        assertTrue("unexpected caption callers: ${users.map { it.name }.filter { it !in allowed }}", users.all { it.name in allowed })
        assertTrue("positive control: receiver/controller/settings are wired", users.map { it.name }.containsAll(
            setOf("CaptionReceiveActivity.kt", "CaptionController.kt", "SectionFragment.kt", "VoxdroidA11yService.kt")))

        val cache = sources.single { it.name == "CaptionCache.kt" }.readText()
        val reached = DICTATION_NAMES.findAll(cache).map { it.value }.toSet()
        assertEquals("the cache reaches into nothing that dictates", emptySet<String>(), reached)

        val service = sources.single { it.name == "VoxdroidA11yService.kt" }.readText()
        assertTrue("the positive control: the live names match the service", LIVE_NAMES.containsMatchIn(service))
        for (file in sources.filter { it.name in FOUNDATION }) {
            val live = LIVE_NAMES.findAll(file.readText()).map { it.value }.toSet()
            assertEquals("${file.name} reaches no recording, take, field, keyboard language or log", emptySet<String>(), live)
        }
        val processor = sources.single { it.name == "CaptionProcessor.kt" }.readText()
        assertTrue("the positive control: the processor's own source is read", processor.contains("SttLanguage.Auto(settings.languages)"))
        val importing = setOf("CaptionReceiveActivity.kt", "CaptionController.kt", "CaptionAccessibility.kt")
        val forbidden = Regex("""\b(AudioRecorder|AudioRecord|RECORD_AUDIO|FieldAccess|commitText|startRecording|goForeground)\b""")
        for (file in sources.filter { it.name in importing }) {
            assertEquals("${file.name} cannot record or deliver text", emptySet<String>(), forbidden.findAll(file.readText()).map { it.value }.toSet())
        }
    }

    private companion object {
        val FOUNDATION = setOf("CaptionCache.kt", "CaptionAudio.kt", "CaptionMediaDecoder.kt", "CaptionProcessor.kt")
        val CAPTION_NAMES = Regex("""\b([Cc]aption\w*|CAPTION_\w+)\b""")
        val DICTATION_NAMES = Regex(
            """\b(AudioRecorder|AudioRecord|Take|TakeHost|SttClient|PolishClient|boundedPost|deliver|insert|copy|commitText|""" +
                """VoxdroidA11yService|Settings|SettingsStore|getSharedPreferences|Log)\b""",
        )

        /** What the processing foundation may not touch: it reuses STT, routing and cleanup, never the live take or its field. */
        val LIVE_NAMES = Regex(
            """\b(AudioRecorder|AudioRecord|RECORD_AUDIO|Take|TakeHost|TakeEvent|machine|deliver|insert|commitText|FieldAccess|""" +
                """ClipboardManager|VoxdroidA11yService|keyboardTag|takeFor|takeLanguage|candidatesFor|isExplicit|Log)\b""",
        )

        fun rulesFile(): File = sequenceOf(
            "src/main/res/xml/data_extraction_rules.xml",
            "app/src/main/res/xml/data_extraction_rules.xml",
        ).map(::File).first(File::exists)

        fun sourceDir(): File = sequenceOf("src/main/java", "app/src/main/java").map(::File).first(File::exists)
    }
}
