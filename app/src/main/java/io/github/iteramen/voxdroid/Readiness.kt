package io.github.iteramen.voxdroid

import com.google.android.material.color.ColorRoles
import com.google.android.material.color.MaterialColors

sealed interface Probe {
    data class Reachable(val ms: Long) : Probe
    data object Unreachable : Probe
    data object Unknown : Probe
}

enum class Problem { ServiceOff, MicrophoneMissing, NoEndpoint, EndpointBlocked, EndpointUnreachable }

/** [endpointBlocked]: then the switch, not the network, is why the probe fails. */
fun problems(serviceOn: Boolean, micGranted: Boolean, endpointSet: Boolean, probe: Probe, endpointBlocked: Boolean): List<Problem> =
    buildList {
        if (!serviceOn) add(Problem.ServiceOff)
        if (!micGranted) add(Problem.MicrophoneMissing)
        if (!endpointSet) add(Problem.NoEndpoint)
        else if (endpointBlocked) add(Problem.EndpointBlocked)
        else if (probe == Probe.Unreachable) add(Problem.EndpointUnreachable)
    }

fun route(
    keyboardTag: String?,
    isExplicit: (String) -> Boolean,
    listed: List<String>,
    laneCodes: List<String>,
): String = when (val language = takeFor(keyboardTag, isExplicit, listed, laneCodes)) {
    is SttLanguage.Explicit -> if (language.code in laneCodes) "lane:${language.code}" else "explicit:${language.code}"
    is SttLanguage.Auto -> "auto:" + language.candidates.joinToString(",")
}

enum class MicrophoneStep {
    Done,
    Ask,
    /** Android will not ask again (a second denial), so the dialog would answer false at once. */
    OpenSettings,
}

/** [canAsk] is false on a fresh install as well as after a final denial; [askedHere] tells the two apart. */
fun microphoneStep(granted: Boolean, askedHere: Boolean, canAsk: Boolean): MicrophoneStep = when {
    granted -> MicrophoneStep.Done
    askedHere && !canAsk -> MicrophoneStep.OpenSettings
    else -> MicrophoneStep.Ask
}

fun hasLane(code: String, chipLanguages: List<String>, laneBaseUrl: String): Boolean =
    code in chipLanguages && laneBaseUrl.isNotBlank()

fun laneCodes(chipLanguages: List<String>, laneBaseUrl: (String) -> String): List<String> =
    chipLanguages.filter { hasLane(it, chipLanguages, laneBaseUrl(it)) }

/** The application id and the Kotlin package differ, so the class's own name is used. */
internal fun serviceComponent(packageName: String): String = "$packageName/${VoxdroidA11yService::class.java.name}"

internal fun withService(current: String?, component: String): String =
    (current.orEmpty().split(':').filter { it.isNotBlank() && it != "null" } - component + component)
        .joinToString(":")

private const val READY_SEED = 0xFF2E7D32.toInt()

/** The green seed pulled toward the wallpaper's [primary]; the pure Material overloads, so a JVM test can run them. */
fun readyCardRoles(primary: Int, light: Boolean): ColorRoles =
    MaterialColors.getColorRoles(MaterialColors.harmonize(READY_SEED, primary), light)
