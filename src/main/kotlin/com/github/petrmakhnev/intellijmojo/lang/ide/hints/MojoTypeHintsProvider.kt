@file:Suppress("DEPRECATION")

package com.github.petrmakhnev.intellijmojo.lang.ide.hints

import com.github.petrmakhnev.intellijmojo.lang.psi.MojoAssignmentStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFile
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoNameIdentifier
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoParameterDeclaration
import com.github.petrmakhnev.intellijmojo.lang.resolve.MojoBindingKind
import com.github.petrmakhnev.intellijmojo.lang.resolve.staticBindingKind
import com.github.petrmakhnev.intellijmojo.lang.type.MojoTy
import com.github.petrmakhnev.intellijmojo.lang.type.displayName
import com.github.petrmakhnev.intellijmojo.lang.type.mojoInference
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

class MojoTypeHintsProvider : InlayHintsProvider {
    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        val project = editor.project ?: file.project
        if (project.isDefault || file !is MojoFile) return null

        return object : SharedBypassCollector {
            override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
                sink.whenOptionEnabled(VARIABLE_TYPES_OPTION) {
                    collectVariableType(element, sink)
                }
            }
        }
    }

    private fun collectVariableType(element: PsiElement, sink: InlayTreeSink) {
        val identifier = element as? MojoNameIdentifier ?: return
        if (!identifier.isValid || identifier.text == "_") return

        val kind = identifier.staticBindingKind()
        val parameter = identifier.parent as? MojoParameterDeclaration
        val isImplicitReceiver = kind == MojoBindingKind.PARAMETER &&
            identifier.text.removeSurrounding("`") == "self" &&
            parameter?.typeExpression == null
        if (kind == MojoBindingKind.TYPE_PARAMETER ||
            kind == MojoBindingKind.PARAMETER && !isImplicitReceiver ||
            kind == MojoBindingKind.CAPTURE_PARAMETER
        ) {
            return
        }

        val inference = identifier.mojoInference ?: return
        if (!inference.isLocalDeclaration(identifier)) return

        val assignment = PsiTreeUtil.getParentOfType(identifier, MojoAssignmentStatement::class.java, false)
        if (assignment?.typeExpression != null) return

        val type = inference.getType(identifier) ?: return
        if (type.containsUnknown()) return

        sink.addPresentation(
            position = InlineInlayPosition(identifier.textRange.endOffset, true),
            hasBackground = true,
        ) {
            text(": ")
            text(type.displayName)
        }
    }

    private fun MojoTy.containsUnknown(): Boolean = when (this) {
        MojoTy.Unknown -> true
        is MojoTy.Builtin -> arguments.any { it.containsUnknown() }
        is MojoTy.Struct -> arguments.any { it.containsUnknown() }
        is MojoTy.Trait -> arguments.any { it.containsUnknown() }
        is MojoTy.Tuple -> elements.any { it.containsUnknown() }
        is MojoTy.Union -> variants.any { it.containsUnknown() }
        is MojoTy.Function -> parameters.any { it.type.containsUnknown() } || returnType.containsUnknown()
        is MojoTy.Overload -> candidates.any { it.containsUnknown() }
        is MojoTy.TypeValue -> instanceType.containsUnknown()
        else -> false
    }

    private companion object {
        const val VARIABLE_TYPES_OPTION = "hint.type.variable"
    }
}
