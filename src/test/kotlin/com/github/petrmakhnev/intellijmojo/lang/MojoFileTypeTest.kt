package com.github.petrmakhnev.intellijmojo.lang

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MojoFileTypeTest : BasePlatformTestCase() {
    fun testMojoFileTypeRegistration() {
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName("main.mojo")

        assertSame(MojoFileType, fileType)
        assertEquals("Mojo", fileType.name)
        assertEquals("mojo", fileType.defaultExtension)
    }
}
