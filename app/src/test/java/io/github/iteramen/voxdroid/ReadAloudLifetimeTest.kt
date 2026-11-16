package io.github.iteramen.voxdroid

import android.media.AudioManager
import android.view.accessibility.AccessibilityEvent as Event
import org.junit.Assert.*
import org.junit.Test

class ReadAloudLifetimeTest {
    @Test fun aNotificationSoundDoesNotStopNarrationButLosingTheAudioDoes() {
        assertFalse("a notification asks only to duck", readAloudStopsOnFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
        assertTrue("another app's playback", readAloudStopsOnFocusChange(AudioManager.AUDIOFOCUS_LOSS))
        assertTrue("a call or the assistant", readAloudStopsOnFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
        assertFalse(readAloudStopsOnFocusChange(AudioManager.AUDIOFOCUS_GAIN))
    }

    @Test fun unrelatedContentUpdateDoesNotStopTheChosenText() {
        assertFalse(readAloudInvalidated(true, true, Event.TYPE_WINDOW_CONTENT_CHANGED, false, true))
    }

    @Test fun missingOrChangedChosenTextStopsEvenWithoutASourceEvent() {
        assertTrue(readAloudInvalidated(true, false, null, false, false))
    }

    @Test fun sourceChangesAndNavigationStillStop() {
        assertTrue(readAloudInvalidated(false, false, null, false, true))
        for (type in listOf(Event.TYPE_VIEW_SCROLLED, Event.TYPE_WINDOW_STATE_CHANGED)) {
            assertTrue(readAloudInvalidated(true, true, type, false, true))
        }
    }

    @Test fun pickerCoordinatesAreInvalidatedByContentChanges() {
        assertTrue(readAloudInvalidated(true, true, Event.TYPE_WINDOW_CONTENT_CHANGED, true, false))
        assertFalse(readAloudInvalidated(true, false, Event.TYPE_WINDOW_CONTENT_CHANGED, true, false))
    }
}
