package com.github.petrmakhnev.intellijmojo.lang.stub.index

import com.github.petrmakhnev.intellijmojo.lang.MojoFileElementType
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTypeSymbol
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StringStubIndexExtension
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.stubs.StubIndexKey

class MojoTypeIndex : StringStubIndexExtension<MojoTypeSymbol>() {
    override fun getVersion(): Int = MojoFileElementType.stubVersion

    override fun getKey(): StubIndexKey<String, MojoTypeSymbol> = KEY

    companion object {
        val KEY: StubIndexKey<String, MojoTypeSymbol> =
            StubIndexKey.createIndexKey("com.github.petrmakhnev.intellijmojo.type")

        fun find(
            name: String,
            project: Project,
            scope: GlobalSearchScope = GlobalSearchScope.allScope(project),
        ): Collection<MojoTypeSymbol> =
            StubIndex.getElements(KEY, name, project, scope, MojoTypeSymbol::class.java)
    }
}
