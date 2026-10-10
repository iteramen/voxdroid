package io.github.iteramen.voxdroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Reads the policy URL string resource off disk as XML: `R.string` is not a value under unit tests. */
class PrivacyPolicyTest {

    @Test
    fun theUrlResourceExistsAndIsBlankOrHttps() {
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stringsFile())
            .getElementsByTagName("string")
        val found = (0 until strings.length).map { strings.item(it) as Element }
            .filter { it.getAttribute("name") == "privacy_policy_url" }
        assertEquals("one privacy_policy_url string", 1, found.size)
        val url = found[0].textContent
        assertTrue("privacy_policy_url is blank or https://, was '$url'", url.isBlank() || url.startsWith("https://"))
    }

    @Test
    fun theRowIsHiddenForABlankOrWhitespaceUrlAndShownForAnHttpsUrl() {
        assertFalse("blank hides the row", privacyPolicyShown(""))
        assertFalse("whitespace hides the row", privacyPolicyShown("  \n"))
        assertTrue("https shows the row", privacyPolicyShown("https://iteramen.github.io/voxdroid/privacy"))
    }

    private companion object {
        fun stringsFile(): File = sequenceOf(
            "src/main/res/values/strings.xml",
            "app/src/main/res/values/strings.xml",
        ).map(::File).first(File::exists)
    }
}
