package com.feurstagram.patches.downloader

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

private const val SETTINGS = "Lcom/feurstagram/extension/Settings;"
private const val BINDER =
    "Lcom/feurstagram/extension/downloader/DownloaderBinder;"

/** Adds a model-bound action through the native feed action-row binder. */
@Suppress("unused")
val inContextDownloadPatch = bytecodePatch(
    name = "In-context download actions",
    description = "Adds a confirmation-only download icon to supported native feed " +
        "action rows, bound to that row's media. Unsupported layouts hide the control.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith(EXTENSION)

    execute {
        // Verify the binder's trace string, model field and native holder shape
        // together. No obfuscated class or method name is pinned.
        val candidates = mutableListOf<Method>()
        classDefForEach { cls ->
            cls.methods.forEach { method ->
                if (method.returnType == "V" && method.parameterTypes.size == 4 &&
                    !AccessFlags.STATIC.isSet(method.accessFlags) &&
                    method.implementation?.instructions?.any {
                        ((it as? ReferenceInstruction)?.reference as? StringReference)
                            ?.string == "itas-android"
                    } == true
                ) {
                    val model = classDefByOrNull(method.parameterTypes[2].toString())
                    val holder = classDefByOrNull(method.parameterTypes[3].toString())
                    if (model?.fields?.count {
                            it.type == "Lcom/instagram/feed/media/Media;" &&
                                !AccessFlags.STATIC.isSet(it.accessFlags)
                        } == 1 && (holder?.fields?.count {
                            it.type == "Lcom/instagram/ui/widget/bouncyufibutton/IgBouncyUfiButtonImageView;"
                        } ?: 0) >= 2
                    ) candidates.add(method)
                }
            }
        }
        val original = candidates.singleOrNull()
            ?: throw PatchException("Native feed action-row binder is missing or ambiguous")
        val feed = mutableClassDefBy(original.definingClass).methods.single {
            it.name == original.name && it.parameterTypes == original.parameterTypes
        }
        // p3=model and p4=holder are consecutive even when the method uses
        // high registers. One range invocation, no register allocation.
        feed.addInstructions(0,
            "invoke-static/range { p3 .. p4 }, " +
                "$BINDER->bindFeed(Ljava/lang/Object;Ljava/lang/Object;)V")
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
            "invoke-static { p0 }, $BINDER->install(Landroid/view/ViewGroup;)V",
        )
        println("HMX: the in-context download action is installed")
    }
}
