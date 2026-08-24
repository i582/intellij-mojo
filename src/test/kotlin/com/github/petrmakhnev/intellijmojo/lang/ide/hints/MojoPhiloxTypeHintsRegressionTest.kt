package com.github.petrmakhnev.intellijmojo.lang.ide.hints

import com.github.petrmakhnev.intellijmojo.ide.settings.mojoSettings
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoAssignmentStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoCallExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFunctionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoNameIdentifier
import com.github.petrmakhnev.intellijmojo.lang.resolve.bindingIdentifiers
import com.github.petrmakhnev.intellijmojo.lang.type.MojoTy
import com.github.petrmakhnev.intellijmojo.lang.type.displayName
import com.github.petrmakhnev.intellijmojo.lang.type.selfInferenceResult
import com.intellij.codeInsight.hints.declarative.CollapseState
import com.intellij.codeInsight.hints.declarative.CollapsiblePresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayActionData
import com.intellij.codeInsight.hints.declarative.InlayPayload
import com.intellij.codeInsight.hints.declarative.InlayPosition
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.PresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

class MojoPhiloxTypeHintsRegressionTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            if (!project.isDisposed && project.mojoSettings.stdlibPath != null) {
                project.mojoSettings.stdlibPath = null
                awaitRootsChanged()
            }
        } finally {
            super.tearDown()
        }
    }

    fun testFmaBindingsInferSimdFloat32x2() {
        val stepNormal = configurePhiloxFunction("step_normal_4") ?: return
        val inference = stepNormal.selfInferenceResult

        val rawBindings = PsiTreeUtil.findChildrenOfType(stepNormal, MojoAssignmentStatement::class.java)
            .flatMap { it.bindingIdentifiers() }
            .filter { it.text == "u_raw" || it.text == "v_raw" }
        assertEquals(setOf("u_raw", "v_raw"), rawBindings.mapTo(mutableSetOf()) { it.text })
        rawBindings.forEach { identifier ->
            assertEquals(
                identifier.text,
                "SIMD[DType.float32, 2]",
                inference.getType(identifier)?.displayName,
            )
        }

        fmaBindings(stepNormal).forEach { binding ->
            val calleeType = inference.getType(binding.call.expression)
            assertInstanceOf(calleeType, MojoTy.Overload::class.java)

            val argumentTypes = binding.call.argumentList.argumentList.map { argument ->
                val expression = argument.namedParameter?.expression ?: argument.expression
                inference.getType(checkNotNull(expression))?.displayName
            }
            assertEquals(
                binding.name,
                List(3) { "SIMD[DType.float32, 2]" },
                argumentTypes,
            )

            val resolved = inference.getResolvedCall(binding.call)
            assertEquals(binding.name, 1, resolved.size)
            assertEquals(binding.name, "fma", resolved.single().name)
            assertEquals(
                binding.name,
                "SIMD[DType.float32, 2]",
                inference.getType(binding.call)?.displayName,
            )
            assertEquals(
                binding.name,
                "SIMD[DType.float32, 2]",
                inference.getType(binding.identifier)?.displayName,
            )
        }
    }

    fun testFmaBindingsProduceInlineTypeHints() {
        val stepNormal = configurePhiloxFunction("step_normal_4") ?: return
        val collector = MojoTypeHintsProvider().createCollector(myFixture.file, myFixture.editor)
        assertInstanceOf(collector, SharedBypassCollector::class.java)

        fmaBindings(stepNormal).forEach { binding ->
            val sink = RecordingInlayTreeSink()
            (collector as SharedBypassCollector).collectFromElement(binding.identifier, sink)

            assertEquals(binding.name, listOf(": SIMD[DType.float32, 2]"), sink.hints.map { it.text })
            val position = assertInstanceOf(sink.hints.single().position, InlineInlayPosition::class.java)
            assertEquals(binding.name, binding.identifier.textRange.endOffset, position.offset)
        }
    }

    fun testStepNormalBindingsInferSimdFloat32x4() {
        val stepNormal = configurePhiloxFunction("step_normal") ?: return
        val inference = stepNormal.selfInferenceResult

        stepNormalBindings(stepNormal).forEach { identifier ->
            assertEquals(
                identifier.text,
                "SIMD[DType.float32, 4]",
                inference.getType(identifier)?.displayName,
            )
        }
    }

    fun testStepNormalBindingsProduceInlineTypeHints() {
        val stepNormal = configurePhiloxFunction("step_normal") ?: return
        val collector = MojoTypeHintsProvider().createCollector(myFixture.file, myFixture.editor)
        assertInstanceOf(collector, SharedBypassCollector::class.java)

        stepNormalBindings(stepNormal).forEach { identifier ->
            val sink = RecordingInlayTreeSink()
            (collector as SharedBypassCollector).collectFromElement(identifier, sink)

            assertEquals(identifier.text, listOf(": SIMD[DType.float32, 4]"), sink.hints.map { it.text })
            val position = assertInstanceOf(sink.hints.single().position, InlineInlayPosition::class.java)
            assertEquals(identifier.text, identifier.textRange.endOffset, position.offset)
        }
    }

    fun testJoinSubstitutesReceiverLengthInResultAndTypeInfo() {
        val stepNormal = configurePhiloxFunction("step_normal") ?: return
        val inference = stepNormal.selfInferenceResult
        val joinCall = PsiTreeUtil.findChildrenOfType(stepNormal, MojoCallExpression::class.java)
            .single { it.text == "z0.join(z1)" }
        val inferredType = inference.getType(joinCall)

        assertEquals("SIMD[DType.float32, 8]", inferredType?.displayName)
        assertEquals(
            "SIMD[DType.float32, 8]",
            MojoTypeProvider().getInformationHint(joinCall),
        )
    }

    private fun configurePhiloxFunction(functionName: String): MojoFunctionDeclaration? {
        val configuredPath = System.getProperty(STDLIB_PATH_PROPERTY)
            ?: System.getenv(STDLIB_PATH_ENVIRONMENT_VARIABLE)
        if (configuredPath.isNullOrBlank()) return null
        val configuredRoot = Path.of(configuredPath).toAbsolutePath().normalize()
        val stdDirectory = if (configuredRoot.fileName?.toString() == "std") {
            configuredRoot
        } else {
            configuredRoot.resolve("std")
        }
        val philoxPath = stdDirectory.resolve("random/philox.mojo")
        if (!Files.isRegularFile(philoxPath)) return null

        checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(configuredRoot))
        project.mojoSettings.stdlibPath = configuredRoot.toString()
        awaitRootsChanged()

        val philoxFile = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(philoxPath))
        myFixture.configureFromExistingVirtualFile(philoxFile)
        return PsiTreeUtil.findChildrenOfType(myFixture.file, MojoFunctionDeclaration::class.java)
            .single { it.name == functionName }
    }

    private fun stepNormalBindings(function: MojoFunctionDeclaration): List<MojoNameIdentifier> {
        val names = setOf("u1", "u2", "r", "theta", "z0", "z1")
        return PsiTreeUtil.findChildrenOfType(function, MojoAssignmentStatement::class.java)
            .filter { it.text.trimStart().startsWith("var ") }
            .flatMap { it.bindingIdentifiers() }
            .filter { it.text in names }
            .also { bindings -> assertEquals(names, bindings.mapTo(mutableSetOf()) { it.text }) }
    }

    private fun fmaBindings(function: MojoFunctionDeclaration) =
        PsiTreeUtil.findChildrenOfType(function, MojoAssignmentStatement::class.java)
            .mapNotNull { assignment ->
                val identifier = assignment.bindingIdentifiers().singleOrNull() ?: return@mapNotNull null
                if (identifier.text !in setOf("u", "v")) return@mapNotNull null
                val call = PsiTreeUtil.findChildrenOfType(assignment, MojoCallExpression::class.java)
                    .single { it.expression.text == "fma" }
                FmaBinding(identifier.text, identifier, call)
            }
            .also { bindings -> assertEquals(setOf("u", "v"), bindings.mapTo(mutableSetOf()) { it.name }) }

    private fun awaitRootsChanged() {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private data class RecordedHint(val position: InlayPosition, val text: String)

    private data class FmaBinding(
        val name: String,
        val identifier: MojoNameIdentifier,
        val call: MojoCallExpression,
    )

    private class RecordingInlayTreeSink : InlayTreeSink {
        val hints = mutableListOf<RecordedHint>()

        override fun whenOptionEnabled(optionId: String, block: () -> Unit) {
            if (optionId == VARIABLE_TYPES_OPTION) block()
        }

        override fun addPresentation(
            position: InlayPosition,
            payloads: List<InlayPayload>?,
            tooltip: String?,
            hintFormat: HintFormat,
            builder: PresentationTreeBuilder.() -> Unit,
        ) {
            val text = StringBuilder()
            RecordingPresentationTreeBuilder(text).builder()
            hints += RecordedHint(position, text.toString())
        }
    }

    private class RecordingPresentationTreeBuilder(
        private val output: StringBuilder,
    ) : CollapsiblePresentationTreeBuilder {
        override fun list(builder: PresentationTreeBuilder.() -> Unit) {
            builder()
        }

        override fun collapsibleList(
            state: CollapseState,
            expandedState: CollapsiblePresentationTreeBuilder.() -> Unit,
            collapsedState: CollapsiblePresentationTreeBuilder.() -> Unit,
        ) {
            expandedState()
        }

        override fun text(text: String, actionData: InlayActionData?) {
            output.append(text)
        }

        override fun clickHandlerScope(
            actionData: InlayActionData,
            builder: PresentationTreeBuilder.() -> Unit,
        ) {
            builder()
        }

        override fun toggleButton(builder: PresentationTreeBuilder.() -> Unit) {
            builder()
        }
    }

    private companion object {
        const val STDLIB_PATH_PROPERTY = "mojo.test.stdlib.path"
        const val STDLIB_PATH_ENVIRONMENT_VARIABLE = "MOJO_TEST_STDLIB_PATH"
        const val VARIABLE_TYPES_OPTION = "hint.type.variable"
    }
}
