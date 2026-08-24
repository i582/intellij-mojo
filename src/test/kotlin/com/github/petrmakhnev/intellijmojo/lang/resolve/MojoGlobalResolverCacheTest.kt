package com.github.petrmakhnev.intellijmojo.lang.resolve

import com.github.petrmakhnev.intellijmojo.ide.settings.mojoSettings
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFunctionDeclaration
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoReferenceExpression
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoStructDeclaration
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

class MojoGlobalResolverCacheTest : BasePlatformTestCase() {
    private lateinit var externalDirectory: Path

    override fun setUp() {
        super.setUp()
        externalDirectory = Files.createTempDirectory("mojo-resolver-cache-test")
    }

    override fun tearDown() {
        try {
            if (project.mojoSettings.stdlibPath != null) {
                project.mojoSettings.stdlibPath = null
                awaitRootsChanged()
            }
        } finally {
            if (::externalDirectory.isInitialized) {
                externalDirectory.toFile().deleteRecursively()
            }
            super.tearDown()
        }
    }

    fun testLocalDeclarationShortCircuitsExplicitImport() {
        myFixture.addFileToProject("library.mojo", "def helper():\n    pass\n")
        val file = myFixture.configureByText(
            "main.mojo",
            "from library import helper\ndef helper():\n    pass\nhelper()\n",
        )

        val targets = MojoGlobalSymbolResolver.resolve(expression(file, "helper"), "helper")

        assertSize(1, targets)
        assertEquals("main.mojo", targets.single().containingFile.name)
    }

    fun testFileSnapshotInvalidatesAfterPsiEdit() {
        val file = myFixture.configureByText(
            "main.mojo",
            "def before():\n    pass\nbefore()\n",
        )
        assertEquals(
            "before",
            (MojoGlobalSymbolResolver.resolve(expression(file, "before"), "before").single() as MojoFunctionDeclaration).name,
        )

        replaceDocumentText("def after():\n    pass\nafter()\n")

        val updatedFile = myFixture.file
        val targets = MojoGlobalSymbolResolver.resolve(expression(updatedFile, "after"), "after")
        assertEquals("after", (targets.single() as MojoFunctionDeclaration).name)
        assertTrue(MojoGlobalSymbolResolver.resolve(expression(updatedFile, "after"), "before").isEmpty())
    }

    fun testTypeMemberSnapshotInvalidatesAfterSignatureEdit() {
        val file = myFixture.configureByText(
            "main.mojo",
            "class Box:\n    def before(self):\n        pass\n",
        )
        val owner = PsiTreeUtil.findChildOfType(file, MojoStructDeclaration::class.java)!!
        assertEquals(
            "before",
            (MojoGlobalSymbolResolver.resolveTypeMember(listOf(owner), "before").single() as MojoFunctionDeclaration).name,
        )

        val document = myFixture.editor.document
        val nameOffset = document.text.indexOf("before")
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(nameOffset, nameOffset + "before".length, "after")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }

        val updatedOwner = PsiTreeUtil.findChildOfType(myFixture.file, MojoStructDeclaration::class.java)!!
        val targets = MojoGlobalSymbolResolver.resolveTypeMember(listOf(updatedOwner), "after")
        assertEquals("after", (targets.single() as MojoFunctionDeclaration).name)
        assertTrue(MojoGlobalSymbolResolver.resolveTypeMember(listOf(updatedOwner), "before").isEmpty())
    }

    fun testCachedFileSnapshotDoesNotPinStdlibRoot() {
        val firstSdk = createStdlib("sdk-first")
        val secondSdk = createStdlib("sdk-second")
        val file = myFixture.configureByText("main.mojo", "selected()\n")
        val call = expression(file, "selected")

        project.mojoSettings.stdlibPath = firstSdk.toString()
        awaitRootsChanged()
        val first = MojoGlobalSymbolResolver.resolve(call, "selected")
        assertTrue(first.isNotEmpty())
        assertTrue(
            first.joinToString { it.containingFile.virtualFile.path },
            first.all { it.containingFile.virtualFile.path.startsWith(firstSdk.toString()) },
        )

        project.mojoSettings.stdlibPath = secondSdk.toString()
        awaitRootsChanged()
        val second = MojoGlobalSymbolResolver.resolve(call, "selected")
        assertTrue(second.isNotEmpty())
        assertTrue(
            second.joinToString { it.containingFile.virtualFile.path },
            second.all { it.containingFile.virtualFile.path.startsWith(secondSdk.toString()) },
        )
    }

    private fun createStdlib(name: String): Path {
        val sdkDirectory = Files.createDirectories(externalDirectory.resolve(name))
        val stdDirectory = Files.createDirectories(sdkDirectory.resolve("std"))
        val preludeDirectory = Files.createDirectories(stdDirectory.resolve("prelude"))
        Files.writeString(stdDirectory.resolve("__init__.mojo"), "")
        Files.writeString(preludeDirectory.resolve("__init__.mojo"), "def selected():\n    pass\n")
        checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sdkDirectory))
        return sdkDirectory
    }

    private fun replaceDocumentText(text: String) {
        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(text)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }

    private fun expression(file: PsiFile, text: String): MojoReferenceExpression =
        PsiTreeUtil.findChildrenOfType(file, MojoReferenceExpression::class.java).single { it.text == text }

    private fun awaitRootsChanged() {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }
}
