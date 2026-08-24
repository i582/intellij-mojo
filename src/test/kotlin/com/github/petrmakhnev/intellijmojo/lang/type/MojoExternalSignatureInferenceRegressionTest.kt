package com.github.petrmakhnev.intellijmojo.lang.type

import com.github.petrmakhnev.intellijmojo.ide.settings.mojoSettings
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoCallExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoComptimeStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoAssignmentStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoForStatement
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFunctionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.resolve.bindingIdentifiers
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.RecursionManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class MojoExternalSignatureInferenceRegressionTest : BasePlatformTestCase() {
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

    fun testExternalFunctionUsesSignatureWithoutInferringMutuallyRecursiveBodies() {
        myFixture.addFileToProject(
            "recursive_library.mojo",
            """
            def external(value: Int) -> Int:
                return helper(value)

            def helper(value: Int) -> Int:
                return external(value)
            """.trimIndent(),
        )
        val file = myFixture.configureByText(
            "main.mojo",
            """
            from recursive_library import external

            def use_external() -> Int:
                return external(1)
            """.trimIndent(),
        )
        val caller = PsiTreeUtil.findChildrenOfType(file, MojoFunctionDeclaration::class.java)
            .single { it.name == "use_external" }
        val call = PsiTreeUtil.findChildrenOfType(caller, MojoCallExpression::class.java)
            .single { it.text == "external(1)" }

        val inference = withoutRecursionPrevention { caller.selfInferenceResult }

        assertEquals("Int", inference.getType(call)?.displayName)
        assertEquals("external", inference.getResolvedCall(call).single().name)
    }

    fun testConfiguredStdlibPhiloxInferenceCompletesWithoutRecursiveCacheFailure() {
        val configuredPath = System.getProperty(STDLIB_PATH_PROPERTY)
            ?: System.getenv(STDLIB_PATH_ENVIRONMENT_VARIABLE)
        if (configuredPath.isNullOrBlank()) return
        val configuredRoot = Path.of(configuredPath).toAbsolutePath().normalize()
        val stdDirectory = if (configuredRoot.fileName?.toString() == "std") {
            configuredRoot
        } else {
            configuredRoot.resolve("std")
        }
        val philoxPath = stdDirectory.resolve("random/philox.mojo")
        if (!Files.isRegularFile(philoxPath)) return

        checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(configuredRoot))
        project.mojoSettings.stdlibPath = configuredRoot.toString()
        awaitRootsChanged()

        val philoxFile = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(philoxPath))
        myFixture.configureFromExistingVirtualFile(philoxFile)
        val file = checkNotNull(myFixture.file)
        val functions = PsiTreeUtil.findChildrenOfType(file, MojoFunctionDeclaration::class.java).toList()
        assertFalse("Expected functions in $philoxPath", functions.isEmpty())

        completesWithin(90, TimeUnit.SECONDS, "Inference for $philoxPath") {
            withoutRecursionPrevention {
                functions.forEach { it.selfInferenceResult }
            }
        }

        val stepNormal = functions.single { it.name == "step_normal_4" }
        val inference = stepNormal.selfInferenceResult
        val scales = PsiTreeUtil.findChildrenOfType(stepNormal, MojoComptimeStatement::class.java)
            .associateBy { it.name }
        listOf("SCALE", "SCALE_2PI", "SCALE_HALF", "SCALE_2PI_HALF").forEach { name ->
            val type = inference.getType(scales.getValue(name))
            assertEquals(name, "Float32", type?.displayName)
            assertTrue(name, type is MojoTy.Struct)
            val arguments = (type as MojoTy.Struct).genericArguments
            assertEquals(name, 2, arguments.size)
            assertFalse(name, arguments.any { it is MojoGenericArg.Hole || it == MojoGenericArg.Unknown })
            assertEquals(name, "1", (arguments[1] as MojoGenericArg.Value).displayName)
        }

        val sqrtCall = PsiTreeUtil.findChildrenOfType(stepNormal, MojoCallExpression::class.java)
            .single { it.expression.text == "sqrt" }
        assertEquals("SIMD[DType.float32, 2]", inference.getType(sqrtCall)?.displayName)
        val resolvedSqrt = inference.getResolvedCall(sqrtCall)
        assertEquals(1, resolvedSqrt.size)
        assertEquals(1, sqrtCall.expression.reference?.let { reference ->
            (reference as? com.intellij.psi.PsiPolyVariantReference)?.multiResolve(false)?.size
        })
        val sBinding = PsiTreeUtil.findChildrenOfType(stepNormal, MojoAssignmentStatement::class.java)
            .flatMap { it.bindingIdentifiers() }
            .single { it.text == "s" }
        assertEquals("SIMD[DType.float32, 2]", inference.getType(sBinding)?.displayName)

        val step = functions.single { it.name == "step" }
        val stepInference = step.selfInferenceResult
        val loop = PsiTreeUtil.findChildrenOfType(step, MojoForStatement::class.java)
            .single { it.text.contains("range(Self.rounds - 1)") }
        val rangeCall = PsiTreeUtil.findChildrenOfType(loop, MojoCallExpression::class.java)
            .single { it.expression.text == "range" }
        val loopBinding = loop.patternList!!.bindingIdentifiers().single { it.text == "i" }
        assertTrue(stepInference.getType(rangeCall)?.displayName?.startsWith("_ZeroStartingRange[") == true)
        assertEquals("Int", stepInference.getType(loopBinding)?.displayName)
        val resolvedRange = stepInference.getResolvedCall(rangeCall)
        assertEquals(1, resolvedRange.size)
    }

    private fun <T> withoutRecursionPrevention(action: () -> T): T {
        val recursionAssertions = Disposer.newDisposable("external signature inference")
        return try {
            RecursionManager.assertOnRecursionPrevention(recursionAssertions)
            action()
        } finally {
            Disposer.dispose(recursionAssertions)
        }
    }

    private fun awaitRootsChanged() {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private fun completesWithin(
        timeout: Long,
        unit: TimeUnit,
        operation: String,
        action: () -> Unit,
    ) {
        val future = AppExecutorUtil.getAppExecutorService().submit(Callable {
            ReadAction.run<RuntimeException>(action)
        })
        try {
            future.get(timeout, unit)
        } catch (error: TimeoutException) {
            future.cancel(true)
            fail("$operation did not finish within $timeout ${unit.name.lowercase()}")
        } catch (error: ExecutionException) {
            val cause = error.cause ?: error
            when (cause) {
                is Error -> throw cause
                is Exception -> throw cause
                else -> throw AssertionError(cause)
            }
        }
    }

    private companion object {
        const val STDLIB_PATH_PROPERTY = "mojo.test.stdlib.path"
        const val STDLIB_PATH_ENVIRONMENT_VARIABLE = "MOJO_TEST_STDLIB_PATH"
    }
}
