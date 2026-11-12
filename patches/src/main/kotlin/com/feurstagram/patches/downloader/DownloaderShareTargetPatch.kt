package com.feurstagram.patches.downloader

import app.morphe.patcher.patch.resourcePatch
import app.morphe.util.asSequence
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import org.w3c.dom.Element

private const val ACTIVITY =
    "com.feurstagram.extension.downloader.android.ShareTargetActivity"
private const val PROVIDER =
    "com.feurstagram.extension.downloader.android.ShareCacheProvider"

private const val ORIGINAL_PACKAGE = "com.instagram.android"
private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

/**
 * Adds the invisible system share target used by the downloader.
 *
 * <p>It is default-off on purpose: unlike the M0 patches, this one exposes an
 * exported component. The component is limited to image/video {@code SEND}
 * and {@code SEND_MULTIPLE} intents, and it validates every incoming URI in
 * {@code ShareIntentParser} before copying it. The provider that hands the
 * copied stream to the worker is private.
 *
 * <p>Only {@code POST_NOTIFICATIONS} is requested. No storage permission is
 * added: MediaStore inserts app-owned rows without one on Android 10+.
 */
@Suppress("unused")
val downloaderShareTargetPatch = resourcePatch(
    name = "Downloader share target",
    description = "Adds the invisible HMX Insta share target that saves shared photos " +
        "and videos to Pictures/HMXInsta and Movies/HMXInsta.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    execute {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.documentElement
            val application = manifest
                .getElementsByTagName("application")
                .item(0) as? Element ?: return@use
            // Read the package from the manifest, not a constant: if the Clone
            // patch already ran, the provider authority must use the clone
            // package. If Clone runs afterwards, it rewrites this prefix.
            val packageName = manifest.getAttribute("package")
                .ifEmpty { ORIGINAL_PACKAGE }

            val usesPermissions = manifest
                .getElementsByTagName("uses-permission")
                .asSequence()
                .map { it as Element }
            if (usesPermissions.none { it.getAttribute("android:name") == POST_NOTIFICATIONS }) {
                val permission = document.createElement("uses-permission")
                permission.setAttribute("android:name", POST_NOTIFICATIONS)
                manifest.insertBefore(permission, application)
            }

            val activities = application
                .getElementsByTagName("activity")
                .asSequence()
                .map { it as Element }
            if (activities.none { it.getAttribute("android:name") == ACTIVITY }) {
                application.appendChild(buildActivity(document))
            }

            val providers = application
                .getElementsByTagName("provider")
                .asSequence()
                .map { it as Element }
            if (providers.none { it.getAttribute("android:name") == PROVIDER }) {
                application.appendChild(buildProvider(document, "$packageName.hmxshare"))
            }
        }
    }
}

private fun buildActivity(document: org.w3c.dom.Document): Element {
    val activity = document.createElement("activity")
    activity.setAttribute("android:name", ACTIVITY)
    activity.setAttribute("android:exported", "true")
    activity.setAttribute("android:excludeFromRecents", "true")
    activity.setAttribute("android:theme", "@android:style/Theme.Translucent.NoTitleBar")
    activity.appendChild(buildFilter(document, "android.intent.action.SEND"))
    activity.appendChild(buildFilter(document, "android.intent.action.SEND_MULTIPLE"))
    return activity
}

private fun buildFilter(document: org.w3c.dom.Document, actionName: String): Element {
    val filter = document.createElement("intent-filter")
    val action = document.createElement("action")
    action.setAttribute("android:name", actionName)
    filter.appendChild(action)
    val category = document.createElement("category")
    category.setAttribute("android:name", "android.intent.category.DEFAULT")
    filter.appendChild(category)
    for (mime in arrayOf("image/*", "video/*")) {
        val data = document.createElement("data")
        data.setAttribute("android:mimeType", mime)
        filter.appendChild(data)
    }
    return filter
}

private fun buildProvider(document: org.w3c.dom.Document, authority: String): Element {
    val provider = document.createElement("provider")
    provider.setAttribute("android:name", PROVIDER)
    provider.setAttribute("android:authorities", authority)
    provider.setAttribute("android:exported", "false")
    provider.setAttribute("android:grantUriPermissions", "false")
    return provider
}
