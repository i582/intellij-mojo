package com.github.petrmakhnev.intellijmojo.lang.stub.index

import com.github.petrmakhnev.intellijmojo.lang.MojoFileElementType
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoFunctionDeclaration
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StringStubIndexExtension
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.stubs.StubIndexKey

class MojoFunctionIndex : StringStubIndexExtension<MojoFunctionDeclaration>() {
    override fun getVersion(): Int = MojoFileElementType.stubVersion

    override fun getKey(): StubIndexKey<String, MojoFunctionDeclaration> = KEY

    companion object {
        val KEY: StubIndexKey<String, MojoFunctionDeclaration> =
            StubIndexKey.createIndexKey("com.github.petrmakhnev.intellijmojo.function")

        fun find(
            name: String,
            project: Project,
            scope: GlobalSearchScope = GlobalSearchScope.allScope(project),
        ): Collection<MojoFunctionDeclaration> =
            StubIndex.getElements(KEY, name, project, scope, MojoFunctionDeclaration::class.java)
    }
}
