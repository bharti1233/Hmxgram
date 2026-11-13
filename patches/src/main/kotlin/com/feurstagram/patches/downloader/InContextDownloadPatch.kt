package com.feurstagram.patches.downloader

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

private const val SETTINGS = "Lcom/feurstagram/extension/Settings;"
private const val OVERLAY =
    "Lcom/feurstagram/extension/downloader/android/InContextDownloadOverlay;"

/**
 * Adds the in-context download action over the feed, reels and stories.
 *
 * <p>Instagram's own code is not modified. The patch hooks the extension's
 * tab-bar watcher, once, so the overlay is installed when the HMX settings
 * entry point is set up. The overlay lives in Instagram's window, reads the
 * existing read-only media observation and opens a confirmation sheet; it
 * never touches the protected row binder, a post's views or a request.
 *
 * <p>The patch is default-off: only a gated build that asks for it gets the
 * chip. If the extension method it hooks disappears, the patch fails loudly
 * instead of shipping a feature that silently does nothing.
 */
@Suppress("unused")
val inContextDownloadPatch = bytecodePatch(
    name = "In-context download actions",
    description = "Draws the HMX download action over supported feed, reels and story " +
        "media. Read-only: it reuses the existing media observation, never modifies " +
        "Instagram's row binder or a post's views, and always asks before saving.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith(EXTENSION)

    execute {
        val settings = mutableClassDefBy(SETTINGS)
        val watcher = settings.methods.singleOrNull {
            it.name == "installHomeTabWatcher" &&
                it.parameterTypes.map { type -> type.toString() } ==
                listOf("Landroid/view/ViewGroup;") &&
                it.returnType == "V"
        } ?: throw PatchException(
            "Extension has no installHomeTabWatcher(ViewGroup) to install the " +
                "in-context download action on",
        )
        watcher.addInstructions(
            0,
            "invoke-static { p0 }, $OVERLAY->install(Landroid/view/ViewGroup;)V",
        )
        println("HMX: the in-context download action is installed")
    }
}
