package com.feurstagram.patches.downloader

import app.morphe.patcher.patch.resourcePatch
import app.morphe.util.asSequence
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import org.w3c.dom.Element

private const val ACTIVITY =
    "com.feurstagram.extension.downloader.android.ShareTargetActivity"
private const val DOWNLOADER_ACTIVITY =
    "com.feurstagram.extension.downloader.android.DownloaderActivity"
private const val PROVIDER =
    "com.feurstagram.extension.downloader.android.ShareCacheProvider"
private const val SERVICE =
    "com.feurstagram.extension.downloader.android.DownloadService"

private const val ORIGINAL_PACKAGE = "com.instagram.android"

/** Permissions the share target needs. Nothing storage-related. */
private val REQUESTED_PERMISSIONS = listOf(
    "android.permission.POST_NOTIFICATIONS",
    // The download service is a dataSync foreground service on Android 14+;
    // both permissions are ignored on older releases.
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
)

/**
 * Adds the invisible system share target and the downloader screen used by
 * the downloader.
 *
 * <p>It is default-off on purpose: unlike the M0 patches, this one exposes
 * exported components. The share activity is limited to image, video and audio
 * {@code SEND} and {@code SEND_MULTIPLE} intents, and it validates every
 * incoming URI in {@code ShareIntentParser} before copying it. The provider
 * that hands the copied stream to the worker is private, and the
 * {@code DownloadService} that keeps the queue alive is not exported.
 *
 * <p>The second exported component is the downloader screen, a launcher
 * activity of its own that shows the queue, the progress and the history
 * without touching the Instagram UI.
 *
 * <p>Permissions: {@code POST_NOTIFICATIONS} for the Android 13 drawer,
 * {@code FOREGROUND_SERVICE} and {@code FOREGROUND_SERVICE_DATA_SYNC} for the
 * {@code dataSync} download service. No storage permission is added:
 * MediaStore inserts app-owned rows without one on Android 10+.
 */
@Suppress("unused")
val downloaderShareTargetPatch = resourcePatch(
    name = "Downloader share target",
    description = "Adds the invisible HMX Insta share target that saves shared photos, " +
        "videos and audio to Pictures/HMXInsta, Movies/HMXInsta and Music/HMXInsta.",
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
                .map { it.getAttribute("android:name") }
                .toSet()
            for (permissionName in REQUESTED_PERMISSIONS) {
                if (permissionName !in usesPermissions) {
                    val permission = document.createElement("uses-permission")
                    permission.setAttribute("android:name", permissionName)
                    manifest.insertBefore(permission, application)
                }
            }

            val activities = application
                .getElementsByTagName("activity")
                .asSequence()
                .map { it as Element }
            if (activities.none { it.getAttribute("android:name") == ACTIVITY }) {
                application.appendChild(buildActivity(document))
            }
            if (activities.none { it.getAttribute("android:name") == DOWNLOADER_ACTIVITY }) {
                application.appendChild(buildDownloaderActivity(document))
            }

            val providers = application
                .getElementsByTagName("provider")
                .asSequence()
                .map { it as Element }
            if (providers.none { it.getAttribute("android:name") == PROVIDER }) {
                application.appendChild(buildProvider(document, "$packageName.hmxshare"))
            }

            val services = application
                .getElementsByTagName("service")
                .asSequence()
                .map { it as Element }
            if (services.none { it.getAttribute("android:name") == SERVICE }) {
                application.appendChild(buildService(document))
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

private fun buildDownloaderActivity(document: org.w3c.dom.Document): Element {
    // The downloader screen is a launcher activity of its own: it shows the
    // queue and the history without touching the Instagram UI. M4 can add
    // in-app entry points later without changing this declaration.
    val activity = document.createElement("activity")
    activity.setAttribute("android:name", DOWNLOADER_ACTIVITY)
    activity.setAttribute("android:exported", "true")
    activity.setAttribute("android:label", "HMX Insta Downloader")
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

private fun buildFilter(document: org.w3c.dom.Document, actionName: String): Element {
    val filter = document.createElement("intent-filter")
    val action = document.createElement("action")
    action.setAttribute("android:name", actionName)
    filter.appendChild(action)
    val category = document.createElement("category")
    category.setAttribute("android:name", "android.intent.category.DEFAULT")
    filter.appendChild(category)
    for (mime in arrayOf("image/*", "video/*", "audio/*")) {
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

private fun buildService(document: org.w3c.dom.Document): Element {
    val service = document.createElement("service")
    service.setAttribute("android:name", SERVICE)
    service.setAttribute("android:exported", "false")
    service.setAttribute("android:foregroundServiceType", "dataSync")
    return service
}
