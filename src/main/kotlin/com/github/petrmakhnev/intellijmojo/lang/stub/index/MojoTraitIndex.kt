package com.github.petrmakhnev.intellijmojo.lang.stub.index

import com.github.petrmakhnev.intellijmojo.lang.MojoFileElementType
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTraitDeclaration
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StringStubIndexExtension
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.stubs.StubIndexKey

class MojoTraitIndex : StringStubIndexExtension<MojoTraitDeclaration>() {
    override fun getVersion(): Int = MojoFileElementType.stubVersion

    override fun getKey(): StubIndexKey<String, MojoTraitDeclaration> = KEY

    companion object {
        val KEY: StubIndexKey<String, MojoTraitDeclaration> =
            StubIndexKey.createIndexKey("com.github.petrmakhnev.intellijmojo.trait")

        fun find(
            name: String,
            project: Project,
            scope: GlobalSearchScope = GlobalSearchScope.allScope(project),
        ): Collection<MojoTraitDeclaration> =
            StubIndex.getElements(KEY, name, project, scope, MojoTraitDeclaration::class.java)
    }
}
