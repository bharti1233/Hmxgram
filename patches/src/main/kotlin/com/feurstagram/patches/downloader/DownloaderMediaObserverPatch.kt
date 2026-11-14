package com.feurstagram.patches.downloader

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
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
 * The accessor that resolves a media model's video versions.
 *
 * <p>Instagram 454 has no video info object anymore. The video URLs live in
 * a list the media accessor returns, and that accessor reads the
 * {@code video_versions} key. The list is handed to the observer together
 * with the model, so a video is observed as a video instead of falling back
 * to its cover image.
 *
 * <p>The criterion is the shape, not a name: the method answers a
 * {@code List} and reads the {@code video_versions} key. In the published
 * build the only such method is the media accessor; the parsers that also
 * read the key answer an object, not a list.
 */
internal object MediaVideoVersionsFingerprint : Fingerprint(
    strings = listOf("video_versions"),
    custom = { method, _ ->
        method.returnType == "Ljava/util/List;" &&
            method.parameterTypes.isEmpty() &&
            method.implementation?.instructions?.any { instruction ->
                val reference = (instruction as? ReferenceInstruction)?.reference
                reference is StringReference && reference.string == "video_versions"
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
 * <p>Two accessors are hooked, independently:
 *
 * <ul>
 *   <li>the image info accessor, which every media item passes through;</li>
 *   <li>the video versions accessor, whose return value is the list the
 *       reader needs for a video.</li>
 * </ul>
 *
 * <p>If one of them does not resolve in a future Instagram release, only that
 * one is skipped: the observation is still installed on the other, and the
 * patch reports what it could not hook. Nothing else in the app changes.
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
        val imageAccessor = runCatching { MediaImageInfoFingerprint.method }.getOrNull()
        val videoAccessor = runCatching { MediaVideoVersionsFingerprint.method }.getOrNull()
        if (imageAccessor == null && videoAccessor == null) {
            println("HMX: neither the image nor the video accessor resolved; " +
                "the observation is not installed")
            return@execute
        }

        if (imageAccessor != null) {
            val target = runCatching {
                mutableClassDefBy(imageAccessor.definingClass).methods.single {
                    it.name == imageAccessor.name && it.parameterTypes.isEmpty()
                }
            }.getOrNull()
            if (target == null) {
                println("HMX: the image accessor is not patchable")
            } else {
                val installed = runCatching {
                    target.addInstructions(
                        0,
                        "invoke-static { p0 }, $OBSERVER->observeMedia(Ljava/lang/Object;)V",
                    )
                }.isSuccess
                if (installed) {
                    println("HMX: the media observation is installed on ${imageAccessor.name}")
                } else {
                    println("HMX: the image observation could not be installed")
                }
            }
        } else {
            println("HMX: the image accessor did not resolve; the observation " +
                "is installed on video versions only")
        }

        if (videoAccessor != null) {
            val target = runCatching {
                mutableClassDefBy(videoAccessor.definingClass).methods.single {
                    it.name == videoAccessor.name && it.parameterTypes.isEmpty()
                }
            }.getOrNull()
            if (target == null) {
                println("HMX: the video accessor is not patchable")
            } else {
                val returns = target.implementation.instructions.filter {
                    it.opcode == Opcode.RETURN_OBJECT
                }
                if (returns.isEmpty()) {
                    println("HMX: the video accessor has no return object to read")
                } else {
                    val installed = runCatching {
                        // Inserted last-to-first so the indices stay valid:
                        // each hook reads the list the return is about to hand
                        // back and passes it to the observer with the model.
                        returns.reversed().forEach { returned ->
                            val register = (returned as OneRegisterInstruction).registerA
                            target.addInstructions(
                                returned.location.index,
                                "invoke-static { p0, v$register }, " +
                                    "$OBSERVER->observeVideo(Ljava/lang/Object;Ljava/lang/Object;)V",
                            )
                        }
                    }.isSuccess
                    if (installed) {
                        println("HMX: the video observation is installed on ${videoAccessor.name}")
                    } else {
                        println("HMX: the video observation could not be installed")
                    }
                }
            }
        } else {
            println("HMX: the video accessor did not resolve; the observation " +
                "is installed on image info only")
        }
    }
}
