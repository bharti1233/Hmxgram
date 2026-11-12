package com.feurstagram.patches.ghost

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.feurstagram.patches.network.TigonStartRequestFingerprint
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

private const val GHOST_CLASS = "Lcom/feurstagram/extension/ghost/GhostNetwork;"

private fun Instruction.isUriLoad() =
    opcode == Opcode.IGET_OBJECT &&
        ((this as? ReferenceInstruction)?.reference as? FieldReference)?.type == "Ljava/net/URI;"

/**
 * Lets ghost mode hold back the receipts Instagram would otherwise send.
 *
 * The hook sits in Instagram's network layer, right where the request URI is
 * materialised — the same anchor the content blocker uses, and one that has
 * not moved between Instagram releases. When ghost mode is on, the request
 * that would report a story view, a read receipt or a typing indicator is
 * dropped: it fails as a network error, Instagram carries on, and the account
 * is left untouched.
 *
 * Nothing else is intercepted: messages are still delivered, notifications are
 * still shown and no security event is suppressed.
 */
@Suppress("unused")
val ghostModePatch = bytecodePatch(
    name = "HMX ghost mode",
    description = "Can hold back the story-view receipt, the message read receipt and the " +
        "typing indicator, so Instagram does not report them. The controls that Instagram " +
        "decides on its servers stay off and say so.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith(EXTENSION)

    execute {
        TigonStartRequestFingerprint.method.apply {
            // The request URI is loaded into a register via an `iget-object` of
            // type java.net.URI inside the request-sending try block. The check
            // goes right after it, so a suppressed request is handled as a
            // normal network failure.
            val uriLoad = instructions.first { it.isUriLoad() }
            val uriRegister = (uriLoad as OneRegisterInstruction).registerA

            addInstructions(
                uriLoad.location.index + 1,
                "invoke-static/range { v$uriRegister .. v$uriRegister }, " +
                    "$GHOST_CLASS->throwIfSuppressed(Ljava/net/URI;)V",
            )
        }
    }
}
