package com.github.petrmakhnev.intellijmojo.ide.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.VirtualFile

class MojoStdlibLibraryRootsProvider : AdditionalLibraryRootsProvider() {
    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> =
        listOfNotNull(project.mojoSettings.stdlibDirectory?.let(::createLibrary))

    override fun getRootsToWatch(project: Project): Collection<VirtualFile> =
        listOfNotNull(project.mojoSettings.stdlibDirectory)

    private fun createLibrary(sourceRoot: VirtualFile): SyntheticLibrary =
        SyntheticLibrary.newImmutableLibrary(
            COMPARISON_ID,
            listOf(sourceRoot),
            emptyList(),
            emptySet(),
            null,
        )

    companion object {
        private const val COMPARISON_ID = "mojo-stdlib"
    }
}
