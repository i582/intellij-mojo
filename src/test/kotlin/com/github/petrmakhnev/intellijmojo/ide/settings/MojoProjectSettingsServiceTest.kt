package com.github.petrmakhnev.intellijmojo.ide.settings

import com.github.petrmakhnev.intellijmojo.lang.psi.MojoNameIdentifier
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoStructDeclaration
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import java.nio.file.Files
import java.nio.file.Path

class MojoProjectSettingsServiceTest : BasePlatformTestCase() {
    private lateinit var externalDirectory: Path

    override fun setUp() {
        super.setUp()
        externalDirectory = Files.createTempDirectory("intellij-mojo-stdlib-test")
    }

    override fun tearDown() {
        try {
            if (!project.isDisposed) {
                project.mojoSettings.stdlibPath = null
                awaitRootsChanged()
            }
            externalDirectory.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testAcceptsPathToStdDirectory() {
        val stdlib = createStdlib("sdk", "ExternalType")

        project.mojoSettings.stdlibPath = "${stdlib.stdDirectory}/"
        awaitRootsChanged()

        assertEquals(stdlib.stdDirectory.toString(), project.mojoSettings.stdlibPath)
        assertEquals(stdlib.stdDirectory.toString(), project.mojoSettings.stdlibDirectory?.path)
        assertEquals(listOf(stdlib.sdkDirectory.toString()), project.mojoSettings.importRoots().map { it.path })
        assertEquals(
            listOf(stdlib.stdDirectory.toString()),
            MojoStdlibLibraryRootsProvider()
                .getAdditionalProjectLibraries(project)
                .single()
                .sourceRoots
                .map { it.path },
        )
    }

    fun testAcceptsPathToDirectoryContainingStd() {
        val stdlib = createStdlib("sdk", "ExternalType")

        project.mojoSettings.stdlibPath = stdlib.sdkDirectory.toString()
        awaitRootsChanged()

        assertEquals(stdlib.sdkDirectory.toString(), project.mojoSettings.stdlibPath)
        assertEquals(stdlib.stdDirectory.toString(), project.mojoSettings.stdlibDirectory?.path)
        assertEquals(listOf(stdlib.sdkDirectory.toString()), project.mojoSettings.importRoots().map { it.path })
    }

    fun testPersistsConfiguredStdlibPath() {
        val stdlib = createStdlib("sdk", "ExternalType")
        val settings = project.mojoSettings
        settings.stdlibPath = stdlib.sdkDirectory.toString()
        val serialized = XmlSerializer.serialize(settings.state)

        settings.stdlibPath = null
        val restored = XmlSerializer.deserialize(serialized, MojoProjectSettingsService.SettingsState::class.java)
        settings.loadState(restored)

        assertEquals(stdlib.sdkDirectory.toString(), settings.stdlibPath)
        assertEquals(stdlib.stdDirectory.toString(), settings.stdlibDirectory?.path)
    }

    fun testChangingPathInvalidatesRootsAndModificationTracker() {
        val first = createStdlib("first-sdk", "FirstType")
        val second = createStdlib("second-sdk", "SecondType")
        val settings = project.mojoSettings
        val before = settings.modificationTracker.modificationCount

        settings.stdlibPath = first.sdkDirectory.toString()
        awaitRootsChanged()
        val afterFirst = settings.modificationTracker.modificationCount
        settings.stdlibPath = second.sdkDirectory.toString()
        awaitRootsChanged()

        assertTrue(afterFirst > before)
        assertTrue(settings.modificationTracker.modificationCount > afterFirst)
        assertEquals(second.stdDirectory.toString(), settings.stdlibDirectory?.path)
        assertEquals(
            listOf(second.stdDirectory.toString()),
            MojoStdlibLibraryRootsProvider()
                .getAdditionalProjectLibraries(project)
                .single()
                .sourceRoots
                .map { it.path },
        )
    }

    fun testResolvesImplicitPreludeFromExternalStdlib() {
        val stdlib = createStdlib("sdk", "ExternalType")
        project.mojoSettings.stdlibPath = stdlib.sdkDirectory.toString()
        awaitRootsChanged()
        val file = myFixture.configureByText(
            "main.mojo",
            "def load(value: ExternalType):\n    pass\n",
        )

        val resolved = resolveType(file, "ExternalType")

        assertInstanceOf(resolved, MojoStructDeclaration::class.java)
        assertEquals("types.mojo", resolved!!.containingFile.name)
        assertTrue(resolved.containingFile.virtualFile.path.startsWith(stdlib.stdDirectory.toString()))
    }

    fun testChangingStdlibUpdatesImplicitPreludeResolutionInExistingFile() {
        val first = createStdlib("first-sdk", "FirstType")
        val second = createStdlib("second-sdk", "SecondType")
        val settings = project.mojoSettings
        val file = myFixture.configureByText(
            "main.mojo",
            """
            def use_first(value: FirstType):
                pass

            def use_second(value: SecondType):
                pass
            """.trimIndent(),
        )

        settings.stdlibPath = first.sdkDirectory.toString()
        awaitRootsChanged()
        assertEquals(first.typesFile.toString(), resolveType(file, "FirstType")?.containingFile?.virtualFile?.path)
        assertNull(resolveType(file, "SecondType"))

        settings.stdlibPath = second.sdkDirectory.toString()
        awaitRootsChanged()
        assertNull(resolveType(file, "FirstType"))
        assertEquals(second.typesFile.toString(), resolveType(file, "SecondType")?.containingFile?.virtualFile?.path)
    }

    fun testBlankPathClearsConfiguredStdlib() {
        val stdlib = createStdlib("sdk", "ExternalType")
        val settings = project.mojoSettings
        settings.stdlibPath = stdlib.sdkDirectory.toString()
        awaitRootsChanged()

        settings.stdlibPath = "   "
        awaitRootsChanged()

        assertNull(settings.stdlibPath)
        assertNull(settings.stdlibDirectory)
        assertEmpty(settings.importRoots())
        assertEmpty(MojoStdlibLibraryRootsProvider().getAdditionalProjectLibraries(project))
    }

    private fun createStdlib(name: String, exportedType: String): TestStdlib {
        val sdkDirectory = Files.createDirectories(externalDirectory.resolve(name))
        val stdDirectory = Files.createDirectories(sdkDirectory.resolve("std"))
        val builtinDirectory = Files.createDirectories(stdDirectory.resolve("builtin"))
        val preludeDirectory = Files.createDirectories(stdDirectory.resolve("prelude"))
        Files.writeString(stdDirectory.resolve("__init__.mojo"), "")
        Files.writeString(builtinDirectory.resolve("__init__.mojo"), "")
        val typesFile = builtinDirectory.resolve("types.mojo")
        Files.writeString(typesFile, "struct $exportedType:\n    pass\n")
        Files.writeString(
            preludeDirectory.resolve("__init__.mojo"),
            "from std.builtin.types import $exportedType\n",
        )
        refresh(sdkDirectory)
        return TestStdlib(sdkDirectory, stdDirectory, typesFile)
    }

    private fun refresh(path: Path): VirtualFile =
        checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))

    private fun awaitRootsChanged() {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private fun resolveType(file: PsiFile, name: String) =
        PsiTreeUtil.findChildrenOfType(file, MojoNameIdentifier::class.java)
            .single { it.text == name && it.references.isNotEmpty() }
            .reference
            ?.resolve()

    private data class TestStdlib(
        val sdkDirectory: Path,
        val stdDirectory: Path,
        val typesFile: Path,
    )
}
