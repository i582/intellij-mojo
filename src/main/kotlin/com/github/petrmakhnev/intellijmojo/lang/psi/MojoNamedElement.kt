package com.github.petrmakhnev.intellijmojo.lang.psi

import com.intellij.navigation.NavigationItem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner

interface MojoNamedElement : PsiNameIdentifierOwner, NavigationItem {
    val identifier: PsiElement?
}

interface MojoTypeSymbol : MojoNamedElement
