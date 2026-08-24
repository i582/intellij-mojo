package com.github.petrmakhnev.intellijmojo.lang.resolve

import com.github.petrmakhnev.intellijmojo.lang.MojoFileType
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFunctionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoNamedParameter
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoParameterDeclaration
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MojoNamedArgumentResolveTest : BasePlatformTestCase() {
    fun testNamedArgumentsResolveAgainstTheirParameterList() {
        val file = myFixture.configureByText(MojoFileType, source)
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val functions = PsiTreeUtil.findChildrenOfType(file, MojoFunctionDeclaration::class.java)
            .associateBy { it.name }
        val typeParameters = functions.getValue("llvm_intrinsic").functionSignature.typeParameterList
            ?.parameterDeclarationList
            .orEmpty()
            .associateBy { it.nameIdentifier!!.text }
        val callableParameters = functions.getValue("emit").functionSignature.callableParameters
            ?.parameterDeclarationList
            .orEmpty()
            .associateBy { it.nameIdentifier!!.text }
        val arguments = PsiTreeUtil.findChildrenOfType(file, MojoNamedParameter::class.java)
            .associateBy { it.nameIdentifier.text }

        assertSame(
            typeParameters.getValue("has_side_effect").nameIdentifier,
            arguments.getValue("has_side_effect").nameIdentifier.reference!!.resolve(),
        )
        assertSame(
            callableParameters.getValue("flag").nameIdentifier,
            arguments.getValue("flag").nameIdentifier.reference!!.resolve(),
        )
    }

    fun testFindUsagesIncludesNamedTypeAndCallableArguments() {
        val file = myFixture.configureByText(MojoFileType, source)
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val parameters = PsiTreeUtil.findChildrenOfType(file, MojoParameterDeclaration::class.java)
            .associateBy { it.nameIdentifier!!.text }

        assertEquals(1, myFixture.findUsages(parameters.getValue("has_side_effect").nameIdentifier!!).size)
        assertEquals(1, myFixture.findUsages(parameters.getValue("flag").nameIdentifier!!).size)
    }

    fun testStructTypeArgumentsAndConstructorArgumentsResolveSeparately() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            struct Box[T: AnyType]:
                var value: T

                def __init__(out self, value: T, *, copy: Bool = False):
                    self.value = value

            def main():
                var box = Box[T=Int](value=1, copy=False)
            """.trimIndent(),
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val functions = PsiTreeUtil.findChildrenOfType(file, MojoFunctionDeclaration::class.java)
            .associateBy { it.name }
        val constructorParameters = functions.getValue("__init__").functionSignature.callableParameters
            ?.parameterDeclarationList
            .orEmpty()
            .filter { it.nameIdentifier != null }
            .associateBy { it.nameIdentifier!!.text }
        val structParameter = PsiTreeUtil.findChildrenOfType(file, MojoParameterDeclaration::class.java)
            .single { it.nameIdentifier?.text == "T" }
        val arguments = PsiTreeUtil.findChildrenOfType(file, MojoNamedParameter::class.java)
            .associateBy { it.nameIdentifier.text }

        assertSame(structParameter.nameIdentifier, arguments.getValue("T").nameIdentifier.reference!!.resolve())
        assertSame(
            constructorParameters.getValue("value").nameIdentifier,
            arguments.getValue("value").nameIdentifier.reference!!.resolve(),
        )
        assertSame(
            constructorParameters.getValue("copy").nameIdentifier,
            arguments.getValue("copy").nameIdentifier.reference!!.resolve(),
        )
        assertEquals(3, myFixture.findUsages(structParameter.nameIdentifier!!).size)
        assertEquals(1, myFixture.findUsages(constructorParameters.getValue("copy").nameIdentifier!!).size)
    }

    fun testParametricMethodArgumentsResolveToMethodParameters() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            struct Counter:
                def update[checked: Bool = True](
                    mut self,
                    value: Int,
                    notify: Bool = False,
                ):
                    pass

            def main(counter: Counter):
                counter.update[checked=False](value=1, notify=False)
            """.trimIndent(),
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val method = PsiTreeUtil.findChildrenOfType(file, MojoFunctionDeclaration::class.java)
            .single { it.name == "update" }
        val parameters = (
            method.functionSignature.typeParameterList?.parameterDeclarationList.orEmpty() +
                method.functionSignature.callableParameters?.parameterDeclarationList.orEmpty()
            ).associateBy { it.nameIdentifier!!.text }
        val arguments = PsiTreeUtil.findChildrenOfType(file, MojoNamedParameter::class.java)
            .associateBy { it.nameIdentifier.text }

        listOf("checked", "value", "notify").forEach { name ->
            assertSame(
                parameters.getValue(name).nameIdentifier,
                arguments.getValue(name).nameIdentifier.reference!!.resolve(),
            )
        }
    }

    fun testSelectedOverloadOwnsTheNamedArgumentReference() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            def choose(value: Int, flag: Bool = False):
                pass

            def choose(value: String, flag: Bool = False):
                pass

            def main():
                choose(1, flag=False)
            """.trimIndent(),
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val intOverload = PsiTreeUtil.findChildrenOfType(file, MojoFunctionDeclaration::class.java)
            .single { function ->
                function.name == "choose" &&
                    function.functionSignature.callableParameters
                        ?.parameterDeclarationList
                        ?.firstOrNull()
                        ?.typeExpression
                        ?.text == "Int"
            }
        val flag = intOverload.functionSignature.callableParameters
            ?.parameterDeclarationList
            .orEmpty()
            .single { it.nameIdentifier?.text == "flag" }
        val argument = PsiTreeUtil.findChildOfType(file, MojoNamedParameter::class.java)!!

        assertSame(flag.nameIdentifier, argument.nameIdentifier.reference!!.resolve())
    }

    fun testNamedTypeArgumentResolvesOutsideFunctionInference() {
        val file = myFixture.configureByText(
            MojoFileType,
            """
            struct Origin[mut: Bool]:
                pass

            struct S[origin: Origin[mut=False]]:
                pass
            """.trimIndent(),
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val parameter = PsiTreeUtil.findChildrenOfType(file, MojoParameterDeclaration::class.java)
            .single { it.nameIdentifier?.text == "mut" }
        val argument = PsiTreeUtil.findChildOfType(file, MojoNamedParameter::class.java)!!

        assertSame(parameter.nameIdentifier, argument.nameIdentifier.reference!!.resolve())
        assertEquals(1, myFixture.findUsages(parameter.nameIdentifier!!).size)
    }

    private val source = """
        def llvm_intrinsic[
            intrin: String,
            type: AnyType,
            *types: AnyType,
            has_side_effect: Bool = True,
        ](*args: *types) -> type:
            pass

        def emit(value: Int, flag: Bool = False):
            pass

        def main(val: Int) -> Int:
            emit(val, flag=False)
            return llvm_intrinsic["llvm.ctlz", Int, has_side_effect=False](val, False)
    """.trimIndent()
}
