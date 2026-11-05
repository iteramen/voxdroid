package io.github.iteramen.voxdroid

import org.junit.Assert.*
import org.junit.Test

class CaptionSessionTest {
    private var clock = 100L
    private val session = CaptionSession { clock }
    private val origin = CaptionOrigin(CaptionApp.Telegram, 7, "synthetic test conversation")

    @Test fun providerAuthorityAloneCannotImpersonateTheSupportedApp() {
        assertNull(CaptionApp.forProvider(origin.source.authority, "attacker.app"))
        assertNull(CaptionApp.forProvider("attacker.provider", origin.source.packageName))
        assertEquals(origin.source, CaptionApp.forProvider(origin.source.authority, origin.source.packageName))
    }

    @Test fun onlyTheSelectedSourceCanStartAReceiver() {
        val id = session.select(origin)
        assertNull(session.receive(CaptionApp.WhatsApp))
        assertEquals(id, session.receive(CaptionApp.Telegram))
        assertNull("a second URI cannot join the same request", session.receive(CaptionApp.Telegram))
    }

    @Test fun shareAndReadMustReturnToTheExactTransientOrigin() {
        val id = session.select(origin)
        assertTrue(session.share(id))
        assertEquals(id, session.receive(origin.source))
        assertTrue(session.returning(id))
        assertFalse(session.returned(id, CaptionOrigin(origin.source, 7, "another conversation")))
        assertFalse(session.returned(id, CaptionOrigin(origin.source, 8, origin.header)))
        assertTrue(session.returned(id, origin))
        assertTrue(session.shown(id))
    }

    @Test fun invalidationCannotResurrectAResultWhenItsChatReturns() {
        val old = session.select(origin)
        session.invalidate()
        session.select(origin)
        assertFalse(session.current(old))
        assertFalse(session.returning(old))
        assertFalse(session.shown(old))
    }

    @Test fun expiredSelectionsAndReceiverReturnsFailClosed() {
        var id = session.select(origin)
        clock += CaptionSession.HANDOFF_MS
        assertFalse(session.share(id))
        assertNull(session.receive(origin.source))
        id = session.select(origin)
        session.receive(origin.source)
        session.returning(id)
        clock += CaptionSession.RETURN_MS
        assertFalse(session.returned(id, origin))
    }

    @Test fun equalChatTitlesAreNotEnoughWhenTheSourceViewChanges() {
        val id = session.select(origin)
        session.receive(origin.source)
        session.returning(id)
        session.returned(id, origin)
        assertTrue(session.stableSource(origin, sourceViewChanged = false))
        assertFalse("navigation or scrolling invalidates even an equal title", session.stableSource(origin, sourceViewChanged = true))
        assertFalse(session.stableSource(CaptionOrigin(origin.source, 8, origin.header), false))
    }

    @Test fun sharingRejectsConflictingOrMultiplePayloadsAndMissingGrants() {
        assertTrue(captionShareAllowed(true, true, true, 1, true, true))
        assertTrue("EXTRA_STREAM alone can be valid", captionShareAllowed(true, true, true, null, true, true))
        assertFalse("SEND_MULTIPLE", captionShareAllowed(false, true, true, 1, true, true))
        assertFalse("file/network URI", captionShareAllowed(true, false, true, 1, true, true))
        assertFalse("no temporary read grant", captionShareAllowed(true, true, false, 1, true, true))
        assertFalse("empty ClipData", captionShareAllowed(true, true, true, 0, true, true))
        assertFalse("multiple ClipData", captionShareAllowed(true, true, true, 2, true, true))
        assertFalse("different ClipData URI", captionShareAllowed(true, true, true, 1, false, true))
        assertFalse("different data URI", captionShareAllowed(true, true, true, 1, true, false))
    }
}
