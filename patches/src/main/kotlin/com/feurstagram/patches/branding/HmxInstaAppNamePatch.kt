package com.feurstagram.patches.branding

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.util.forEachChildElement
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import org.w3c.dom.Element

/** The product name shown on the launcher and in the app information screen. */
private const val APP_NAME = "HMX Insta+"

/** The themed-icon (monochrome) layer, bundled in the patch jar. */
private const val MONOCHROME_VECTOR = "branding/ig_launcher_monochrome.xml"

/**
 * Where the monochrome layer can live once the APK is decoded. The resource
 * name is fixed, the file name is not: the patcher may lay the decoded tree
 * out by resource name or keep Instagram's obfuscated file name.
 */
private val MONOCHROME_CANDIDATES = listOf(
    "res/drawable/ig_launcher_monochrome.xml",
    "res/drawable-anydpi/ig_launcher_monochrome.xml",
    "res/drawable-anydpi-v26/ig_launcher_monochrome.xml",
    "res/j3.xml",
)

/**
 * Shows the patched app as HMX Insta+ instead of Instagram.
 *
 * The launcher reads the label from the manifest's `android:label`, which points
 * at a string resource; changing that one string re-labels every surface that
 * uses it (the launcher entry, the task-switcher entry and the app information
 * screen) without touching Instagram's own strings.
 *
 * The launcher icon's PNG layers are not handled here: the icon's files are
 * obfuscated inside the APK, so they are resolved and replaced by the packaging
 * step (scripts/branding/apply_launcher_branding.py) before this patch runs.
 * The monochrome (themed icon) layer is a vector and is replaced here, where
 * the XML can be written as text and compiled by the patcher.
 */
@Suppress("unused")
val hmxInstaAppNamePatch = resourcePatch(
    name = "HMX Insta app name",
    description = "Shows the app as HMX Insta+ on the launcher, in the app information screen " +
        "and in the themed icon.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    execute {
        // The resource name behind the manifest's label, e.g. "app_name".
        var labelResource: String? = null

        document("AndroidManifest.xml").use { document ->
            val manifest = document.documentElement
            val application = manifest.getElementsByTagName("application").item(0) as? Element
                ?: throw PatchException("the manifest has no <application> element")
            val label = application.getAttribute("android:label")
            if (label.startsWith("@string/")) {
                labelResource = label.removePrefix("@string/")
            }
        }

        var renamed = false
        if (labelResource != null) {
            try {
                document("res/values/strings.xml").use { document ->
                    document.documentElement.forEachChildElement {
                        if (it.getAttribute("name") == labelResource) {
                            it.textContent = APP_NAME
                            renamed = true
                        }
                    }
                }
            } catch (t: Throwable) {
                println("[HMX Insta branding] the string table could not be read: $t")
            }
        }

        if (!renamed) {
            // The label is a literal, or the string lives elsewhere: set it on the
            // manifest itself so the launcher still shows the product name.
            document("AndroidManifest.xml").use { document ->
                val manifest = document.documentElement
                val application = manifest.getElementsByTagName("application").item(0) as? Element
                    ?: throw PatchException("the manifest has no <application> element")
                application.setAttribute("android:label", APP_NAME)
            }
        }

        // The themed-icon layer: Android 13+ monochrome icons.
        val vector = object {}.javaClass.classLoader.getResourceAsStream(MONOCHROME_VECTOR)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw PatchException("$MONOCHROME_VECTOR is missing from the patch bundle")
        var monochromeReplaced = false
        for (candidate in MONOCHROME_CANDIDATES) {
            val file = get(candidate)
            if (file.exists()) {
                file.writeText(vector)
                monochromeReplaced = true
                break
            }
        }
        if (!monochromeReplaced) {
            println("[HMX Insta branding] the monochrome icon layer was not found; " +
                "themed icons keep Instagram's shape")
        }
    }
}
