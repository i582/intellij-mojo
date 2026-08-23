package com.github.petrmakhnev.intellijmojo.lang.stub.index

import com.github.petrmakhnev.intellijmojo.lang.MojoFileElementType
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoStructDeclaration
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StringStubIndexExtension
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.stubs.StubIndexKey

class MojoStructIndex : StringStubIndexExtension<MojoStructDeclaration>() {
    override fun getVersion(): Int = MojoFileElementType.stubVersion

    override fun getKey(): StubIndexKey<String, MojoStructDeclaration> = KEY

    companion object {
        val KEY: StubIndexKey<String, MojoStructDeclaration> =
            StubIndexKey.createIndexKey("com.github.petrmakhnev.intellijmojo.struct")

        fun find(
            name: String,
            project: Project,
            scope: GlobalSearchScope = GlobalSearchScope.allScope(project),
        ): Collection<MojoStructDeclaration> =
            StubIndex.getElements(KEY, name, project, scope, MojoStructDeclaration::class.java)
    }
}
