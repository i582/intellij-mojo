package com.github.petrmakhnev.intellijmojo.lang.stub.index

import com.github.petrmakhnev.intellijmojo.lang.MojoFileElementType
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFile
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StringStubIndexExtension
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.stubs.StubIndexKey

class MojoModuleIndex : StringStubIndexExtension<MojoFile>() {
    override fun getVersion(): Int = MojoFileElementType.stubVersion

    override fun getKey(): StubIndexKey<String, MojoFile> = KEY

    companion object {
        val KEY: StubIndexKey<String, MojoFile> =
            StubIndexKey.createIndexKey("com.github.petrmakhnev.intellijmojo.module")

        fun find(
            name: String,
            project: Project,
            scope: GlobalSearchScope = GlobalSearchScope.allScope(project),
        ): Collection<MojoFile> = StubIndex.getElements(KEY, name, project, scope, MojoFile::class.java)
    }
}
