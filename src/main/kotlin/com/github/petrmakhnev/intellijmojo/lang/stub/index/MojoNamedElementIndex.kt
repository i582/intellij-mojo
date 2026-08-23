package com.github.petrmakhnev.intellijmojo.lang.stub.index

import com.github.petrmakhnev.intellijmojo.lang.MojoFileElementType
import com.github.petrmakhnev.intellijmojo.lang.psi.MojoNamedElement
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StringStubIndexExtension
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.stubs.StubIndexKey
import com.intellij.util.Processor

class MojoNamedElementIndex : StringStubIndexExtension<MojoNamedElement>() {
    override fun getVersion(): Int = MojoFileElementType.stubVersion

    override fun getKey(): StubIndexKey<String, MojoNamedElement> = KEY

    companion object {
        val KEY: StubIndexKey<String, MojoNamedElement> =
            StubIndexKey.createIndexKey("com.github.petrmakhnev.intellijmojo.named")

        fun find(
            name: String,
            project: Project,
            scope: GlobalSearchScope = GlobalSearchScope.allScope(project),
        ): Collection<MojoNamedElement> =
            StubIndex.getElements(KEY, name, project, scope, MojoNamedElement::class.java)

        fun processAllKeys(
            project: Project,
            scope: GlobalSearchScope = GlobalSearchScope.allScope(project),
            processor: Processor<String>,
        ): Boolean = StubIndex.getInstance().processAllKeys(KEY, processor, scope)
    }
}
