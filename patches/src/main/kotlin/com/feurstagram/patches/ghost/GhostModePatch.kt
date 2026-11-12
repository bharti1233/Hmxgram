package com.feurstagram.patches.ghost

import app.morphe.patcher.patch.resourcePatch
import app.morphe.util.asSequence
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import org.w3c.dom.Element

private const val ACTIVITY =
    "com.feurstagram.extension.ghost.GhostModeActivity"

private const val ORIGINAL_PACKAGE = "com.instagram.android"

/**
 * Adds the Ghost Mode settings screen.
 *
 * <p>The screen is a launcher activity of its own: it lists the privacy
 * controls with an explanation each, and it never touches the Instagram UI.
 * The controls that this repository cannot implement safely are shown with
 * the reason and cannot be switched on.
 *
 * <p>The patch is default-off, like the downloader patch: a feature that
 * changes what the app shows is reviewed before it ships.
 */
@Suppress("unused")
val ghostModePatch = resourcePatch(
    name = "Ghost Mode settings",
    description = "Adds the HMX Ghost Mode screen with the privacy controls and an " +
        "explanation for each of them.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    execute {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.documentElement
            val application = manifest
                .getElementsByTagName("application")
                .item(0) as? Element ?: return@use
            val packageName = manifest.getAttribute("package")
                .ifEmpty { ORIGINAL_PACKAGE }

            val activities = application
                .getElementsByTagName("activity")
                .asSequence()
                .map { it as Element }
            if (activities.none { it.getAttribute("android:name") == ACTIVITY }) {
                application.appendChild(buildActivity(document))
            }
        }
    }
}

private fun buildActivity(document: org.w3c.dom.Document): Element {
    val activity = document.createElement("activity")
    activity.setAttribute("android:name", ACTIVITY)
    activity.setAttribute("android:exported", "true")
    activity.setAttribute("android:label", "HMX Ghost Mode")
    activity.setAttribute("android:theme", "@android:style/Theme.DeviceDefault")
    activity.setAttribute("android:launchMode", "singleTop")
    val filter = document.createElement("intent-filter")
    val action = document.createElement("action")
    action.setAttribute("android:name", "android.intent.action.MAIN")
    filter.appendChild(action)
    val category = document.createElement("category")
    category.setAttribute("android:name", "android.intent.category.LAUNCHER")
    filter.appendChild(category)
    activity.appendChild(filter)
    return activity
}
