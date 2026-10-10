package io.github.iteramen.voxdroid

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadinessTest {

    @Test
    fun readyWhenEverythingHolds() {
        assertEquals(emptyList<Problem>(), problems(true, true, true, Probe.Reachable(48), false))
    }

    @Test
    fun problemsComeInFixOrder() {
        assertEquals(
            listOf(Problem.ServiceOff, Problem.MicrophoneMissing, Problem.NoEndpoint),
            problems(serviceOn = false, micGranted = false, endpointSet = false, probe = Probe.Unknown, endpointBlocked = false),
        )
    }

    @Test
    fun anUnreachableEndpointIsAProblemAndAnUnprobedOneIsNot() {
        assertEquals(listOf(Problem.EndpointUnreachable), problems(true, true, true, Probe.Unreachable, false))
        assertEquals(emptyList<Problem>(), problems(true, true, true, Probe.Unknown, false))
    }

    @Test
    fun noEndpointIsNotAlsoUnreachable() {
        assertEquals(listOf(Problem.NoEndpoint), problems(true, true, false, Probe.Unreachable, false))
    }

    @Test
    fun aBlockedEndpointIsBlockedNotUnreachable() {
        assertEquals(listOf(Problem.EndpointBlocked), problems(true, true, true, Probe.Unreachable, true))
        assertEquals(listOf(Problem.EndpointBlocked), problems(true, true, true, Probe.Unknown, true))
        assertEquals(listOf(Problem.NoEndpoint), problems(true, true, false, Probe.Unreachable, true))
    }

    @Test
    fun theRouteNamesTheLaneTheExplicitLanguageOrTheListedLanguages() {
        val explicit: (String) -> Boolean = { it == "he" }
        val languages = listOf("he", "ru", "en")
        val lanes = listOf("he")
        assertEquals("lane:he", route("iw", explicit, languages, lanes))
        assertEquals("explicit:ru", route("ru", { it == "he" || it == "ru" }, languages, lanes))
        assertEquals("auto:he,en", route(null, { it == "he" || it == "ru" }, languages, lanes))
        assertEquals("auto:he,ru,en", route("en-US", explicit, languages, lanes))
        assertEquals("auto:he,ru,en", route(null, explicit, languages, lanes))
        assertEquals("auto:ru,en", route("en-US", explicit, languages, emptyList()))
        assertEquals("auto:ru,en", route(null, explicit, listOf("ru", "en"), emptyList()))
        assertEquals("auto:", route(null, explicit, emptyList(), emptyList()))
    }

    @Test
    fun theLaneCodesAreTheListedLanguagesWithALaneUrl() {
        val urls = mapOf("he" to "http://x/he/v1", "ru" to "  ", "en" to "http://x/en/v1")
        assertEquals(listOf("he"), laneCodes(listOf("he", "ru"), { urls.getValue(it) }))
        assertEquals(emptyList<String>(), laneCodes(emptyList(), { urls.getValue(it) }))
    }

    @Test
    fun microphoneStepIsDoneOnceGrantedWhateverWasAsked() {
        assertEquals(MicrophoneStep.Done, microphoneStep(granted = true, askedHere = false, canAsk = false))
        assertEquals(MicrophoneStep.Done, microphoneStep(granted = true, askedHere = true, canAsk = true))
    }

    @Test
    fun microphoneStepAsksBeforeThisScreenHasAskedAndAfterAPlainDenial() {
        assertEquals(MicrophoneStep.Ask, microphoneStep(granted = false, askedHere = false, canAsk = false))
        assertEquals(MicrophoneStep.Ask, microphoneStep(granted = false, askedHere = false, canAsk = true))
        assertEquals(MicrophoneStep.Ask, microphoneStep(granted = false, askedHere = true, canAsk = true))
    }

    @Test
    fun microphoneStepOpensSettingsOnceAndroidWillNotAskAgain() {
        assertEquals(MicrophoneStep.OpenSettings, microphoneStep(granted = false, askedHere = true, canAsk = false))
    }

    @Test
    fun aLaneNeedsTheLanguageInTheLanguageListAndAUrl() {
        val chips = listOf("he", "ru")
        assertEquals(true, hasLane("he", chips, "http://x/he/v1"))
        assertEquals(false, hasLane("en", chips, "http://x/en/v1"))
        assertEquals(false, hasLane("he", chips, ""))
        assertEquals(false, hasLane("he", chips, "  "))
    }

    @Test
    fun theServiceComponentNamesTheClassUnderTheApplicationId() {
        assertEquals(
            "io.github.iteramen.voxdroid/io.github.iteramen.voxdroid.VoxdroidA11yService",
            serviceComponent("io.github.iteramen.voxdroid"),
        )
    }

    @Test
    fun theListsDropBlankAndNullEntriesAndMoveTheServiceLast() {
        assertEquals("a/b:c/d:me/Svc", withService("a/b::null:me/Svc:c/d", "me/Svc"))
        assertEquals("me/Svc", withService(null, "me/Svc"))
        assertEquals("me/Svc", withService("null", "me/Svc"))
    }
}
