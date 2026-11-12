package com.feurstagram.patches.downloader

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

private const val REGISTRY_CLASS = "Lcom/feurstagram/extension/downloader/MediaRegistry;"
private const val MEDIA_TYPE = "Lcom/instagram/feed/media/Media;"

/**
 * How many call sites the patch is prepared to hook.
 *
 * The media parser is called from a handful of places — the feed, the direct
 * inbox, the story tray. A number far above that means the fingerprint matched
 * something else, and hooking thousands of sites would rewrite the whole dex
 * and exhaust the build machine.
 */
private const val MAX_CALL_SITES = 400

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
 * One place where the media parser hands a model back to its caller.
 *
 * [index] is the instruction *after* Instagram's own `move-result-object`: the
 * model is already in [register] there. Inserting before that move-result
 * would take the result of the capture call instead of the parser's, which is
 * invalid dex.
 */
private data class CallSite(val type: String, val method: Int, val index: Int, val register: Int)

/**
 * Hands every media Instagram parses to the HMX downloader.
 *
 * The media model is Instagram's own object and stays where it is: the
 * extension only reads the fields it needs (the image URL, the video URL, the
 * media type and the owner). Nothing is copied out of the app and nothing is
 * sent anywhere.
 *
 * The hook is inserted after Instagram's own `move-result-object`, where the
 * parsed model is already in a register. The call takes that register and
 * returns the model unchanged, and a `move-result-object` puts it back — the
 * same pattern the feed filter uses.
 *
 * Inserting *before* Instagram's move-result would take the result of the
 * capture call instead of the parser's: the dex would hold a move-result for a
 * void call, the class would fail verification, every media parse would throw
 * and the app would sit on a spinner with the network on. That is exactly what
 * the first version of this patch did.
 *
 * Only the classes that actually call the parser are made mutable: touching
 * every class in the dex would rewrite all of Instagram's 223,000 classes and
 * exhaust the build machine.
 *
 * The patch fails when the parser is not found, is never called, or hands back
 * no media: a downloader that silently captures nothing would look like it
 * worked.
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
        val fingerprint = when {
            DownloaderMediaParseFingerprint.methodOrNull != null -> DownloaderMediaParseFingerprint
            DownloaderMediaParseInlineFingerprint.methodOrNull != null ->
                DownloaderMediaParseInlineFingerprint
            else -> throw PatchException(
                "Instagram's media parser was not found; the HMX downloader cannot capture media")
        }

        val parser = fingerprint.method
        val parserType = fingerprint.classDef.type
        val parserName = parser.name
        val parserParams = parser.parameterTypes.map { it.toString() }

        // First pass: find the call sites, reading the class definitions as they
        // are. Nothing is made mutable here.
        val callSites = mutableListOf<CallSite>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lcom/feurstagram/")) return@classDefForEach
            classDef.methods.forEachIndexed { methodIndex, method ->
                // A method without an implementation (abstract, native) has no
                // instructions to read: Morphe's extension throws on those.
                if (method.implementation == null) return@forEachIndexed
                val methodInstructions = method.instructions
                methodInstructions.forEachIndexed { index, instruction ->
                    if (instruction.opcode != Opcode.INVOKE_STATIC) return@forEachIndexed
                    val reference = (instruction as ReferenceInstruction).reference as MethodReference
                    if (reference.definingClass != parserType) return@forEachIndexed
                    if (reference.name != parserName) return@forEachIndexed
                    if (reference.parameterTypes.map { it.toString() } != parserParams) {
                        return@forEachIndexed
                    }
                    // Instagram materialises the parser's result with its own
                    // move-result-object. The hook goes *after* it: the model is
                    // in a register by then, and the move-result that follows
                    // the parser stays attached to the parser.
                    val next = methodInstructions.elementAtOrNull(index + 1) ?: return@forEachIndexed
                    if (next.opcode != Opcode.MOVE_RESULT_OBJECT) return@forEachIndexed
                    callSites += CallSite(
                        classDef.type, methodIndex, index + 2,
                        (next as OneRegisterInstruction).registerA,
                    )
                }
            }
        }

        if (callSites.isEmpty()) {
            throw PatchException(
                "Instagram's media parser hands back no media model; the HMX downloader cannot " +
                    "capture media")
        }
        if (callSites.size > MAX_CALL_SITES) {
            throw PatchException(
                "the media parser is called ${callSites.size} times, which is more than the " +
                    "downloader expects; refusing to rewrite the whole dex")
        }

        // Second pass: make only the classes that hold a call site mutable and
        // insert the hook. Backwards, so an insertion does not move the sites
        // still to visit.
        var hooked = 0
        callSites.groupBy { it.type }.forEach { (type, sites) ->
            val mutableClass = mutableClassDefBy(type)
            mutableClass.methods.forEachIndexed { methodIndex, method ->
                sites.filter { it.method == methodIndex }
                    .sortedByDescending { it.index }
                    .forEach { site ->
                        method.addInstructions(
                            site.index,
                            "invoke-static/range { v${site.register} .. v${site.register} }, " +
                                "$REGISTRY_CLASS->capture(Ljava/lang/Object;)Ljava/lang/Object;\n" +
                                "move-result-object v${site.register}",
                        )
                        hooked++
                    }
            }
        }

        if (hooked == 0) {
            throw PatchException(
                "Instagram's media parser hands back no media model; the HMX downloader cannot " +
                    "capture media")
        }
    }
}
