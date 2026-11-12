package com.feurstagram.patches.downloader

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

private const val REGISTRY_CLASS = "Lcom/feurstagram/extension/downloader/MediaRegistry;"
private const val MEDIA_TYPE = "Lcom/instagram/feed/media/Media;"

// Instagram's media deserialiser. The fingerprint is the one the feed filter
// already uses (it is the same parser): the error strings are unique to it and
// the field dispatch tells it apart from the helpers that share them.
internal object DownloaderMediaParseFingerprint : Fingerprint(
    returnType = MEDIA_TYPE,
    strings = listOf(
        "Media:null_pointer_exception",
        "Media:array_out_of_bounds_exception",
    ),
    custom = { method, _ ->
        method.implementation?.instructions?.any { it.isMediaFieldDispatch() } == true
    },
)

// Instagram 453 folded the codec back into the parser itself: the (parser, Z)
// entry point reads each field name and hashes it inline.
internal object DownloaderMediaParseInlineFingerprint : Fingerprint(
    returnType = MEDIA_TYPE,
    strings = listOf("Media:null_pointer_exception", "Media:array_out_of_bounds_exception"),
    custom = { method, _ ->
        method.parameterTypes.size == 2 && method.parameterTypes[1].toString() == "Z" &&
            method.implementation?.instructions?.any { it.isStringHash() } == true
    },
)

private fun Instruction.isMediaFieldDispatch(): Boolean {
    if (opcode != Opcode.INVOKE_STATIC) return false
    val reference = (this as ReferenceInstruction).reference as MethodReference
    return reference.returnType == "V" &&
        reference.parameterTypes.size == 3 &&
        reference.parameterTypes[1].toString() == "Ljava/lang/String;" &&
        reference.parameterTypes[2].toString() == "Ljava/util/Map;"
}

private fun Instruction.isStringHash(): Boolean {
    val reference = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return opcode == Opcode.INVOKE_VIRTUAL && reference.definingClass == "Ljava/lang/String;" &&
        reference.name == "hashCode"
}

/**
 * Hands every media Instagram parses to the HMX downloader.
 *
 * The media model is Instagram's own object and stays where it is: the
 * extension only reads the fields it needs (the image URL, the video URL, the
 * media type and the owner). Nothing is copied out of the app and nothing is
 * sent anywhere.
 *
 * The patch is anchored on the media parser, the same anchor the feed filter
 * uses, and it fails when the parser is not found: a downloader that silently
 * captures nothing would look like it worked.
 */
@Suppress("unused")
val downloaderCapturePatch = bytecodePatch(
    name = "HMX downloader capture",
    description = "Hands the media Instagram loads to the HMX downloader so photos, videos, " +
        "carousel items, stories and reels can be saved from inside the app.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith(EXTENSION)

    execute {
        val parser = DownloaderMediaParseFingerprint.methodOrNull
            ?: DownloaderMediaParseInlineFingerprint.methodOrNull
            ?: throw app.morphe.patcher.patch.PatchException(
                "Instagram's media parser was not found; the HMX downloader cannot capture media")

        val parserType = parser.classDef.type
        val parserName = parser.name
        val parserParams = parser.parameterTypes.map { it.toString() }

        // Every call of the parser hands back a media model: the caller is
        // where the finished media is available.
        val callSites = parser.classDef.methods
            .flatMap { method -> method.instructions.withIndex().map { method to it } }
            .filter { (_, indexed) ->
                val instruction = indexed.value
                if (instruction.opcode != Opcode.INVOKE_STATIC) return@filter false
                val reference = (instruction as ReferenceInstruction).reference as MethodReference
                reference.definingClass == parserType &&
                    reference.name == parserName &&
                    reference.parameterTypes.map { it.toString() } == parserParams
            }

        if (callSites.isEmpty()) {
            throw app.morphe.patcher.patch.PatchException(
                "Instagram's media parser is never called; the HMX downloader cannot capture media")
        }

        var hooked = 0
        callSites.forEach { (method, indexed) ->
            val index = indexed.index
            val next = method.instructions.getOrNull(index + 1) ?: return@forEach
            if (next.opcode != Opcode.MOVE_RESULT_OBJECT) return@forEach
            val register = (next as OneRegisterInstruction).registerA
            addInstructions(
                index + 1,
                "invoke-static/range { v$register .. v$register }, " +
                    "$REGISTRY_CLASS->capture(Ljava/lang/Object;)V",
            )
            hooked++
        }

        if (hooked == 0) {
            throw app.morphe.patcher.patch.PatchException(
                "Instagram's media parser hands back no media model; the HMX downloader cannot " +
                    "capture media")
        }
    }
}
