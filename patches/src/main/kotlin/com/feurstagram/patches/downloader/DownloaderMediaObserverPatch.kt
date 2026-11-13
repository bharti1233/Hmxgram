package com.feurstagram.patches.downloader

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

private const val OBSERVER =
    "Lcom/feurstagram/extension/downloader/android/MediaObserver;"

/**
 * The accessor that resolves a media model's image info.
 *
 * <p>Instagram's media model is map-backed: it holds the parsed JSON and
 * reads the fields it needs on demand. The accessor that turns
 * {@code image_versions2} into an image info object is therefore the one
 * place every media item passes through, whatever the feed, the reels or the
 * stories show.
 *
 * <p>The anchor is the behaviour, not a name: the method reads
 * {@code image_versions2} and answers an image info. The accessors are
 * renamed in every release; the shape is not.
 *
 * <p>The string criterion is matched against the <em>method</em>, not against
 * the class: the accessor itself holds the one key it reads. The anchor was
 * confirmed against the dex of the published build, where this accessor is the
 * only method that both reads {@code image_versions2} and answers a
 * {@code mediasize/ImageInfo}.
 */
internal object MediaImageInfoFingerprint : Fingerprint(
    strings = listOf("image_versions2"),
    custom = { method, _ ->
        method.returnType == "Lcom/instagram/model/mediasize/ImageInfo;" &&
            method.parameterTypes.isEmpty() &&
            method.implementation?.instructions?.any { instruction ->
                val reference = (instruction as? ReferenceInstruction)?.reference
                reference is StringReference && reference.string == "image_versions2"
            } == true
    },
)

/**
 * Records the media the user is looking at, for the downloader.
 *
 * <p>The hook is a read: it hands the model it was given to the downloader's
 * observer, which copies the URL and the id out of it on its own thread. No
 * view is touched, no view hierarchy is scanned, no request is intercepted
 * and no Instagram object is modified. The model is parsed by Instagram
 * itself; this patch only reads what Instagram already read.
 *
 * <p>The patch is default-off. If the anchor does not resolve in a future
 * Instagram release, the observation is simply not installed: the patch
 * reports it and leaves the app untouched.
 */
@Suppress("unused")
val downloaderMediaObserverPatch = bytecodePatch(
    name = "Downloader media observer",
    description = "Records the media the user browses so the downloader can save it. " +
        "Read-only: nothing is modified, no view is touched and no request is intercepted.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith(EXTENSION)

    execute {
        val accessor = runCatching { MediaImageInfoFingerprint.method }.getOrNull()
        if (accessor == null) {
            println("HMX: the media accessor did not resolve; the observation is not installed")
            return@execute
        }
        val modelType = accessor.definingClass
        val target = runCatching {
            mutableClassDefBy(modelType).methods.single {
                it.name == accessor.name && it.parameterTypes.isEmpty()
            }
        }.getOrNull()
        if (target == null) {
            println("HMX: the media accessor is not patchable; the observation is not installed")
            return@execute
        }
        target.addInstructions(
            0,
            "invoke-static { p0 }, $OBSERVER->observeMedia(Ljava/lang/Object;)V",
        )
        println("HMX: the media observation is installed on ${accessor.name}")
    }
}
