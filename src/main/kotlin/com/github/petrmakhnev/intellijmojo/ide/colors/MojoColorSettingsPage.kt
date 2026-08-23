package com.github.petrmakhnev.intellijmojo.ide.colors

import com.github.petrmakhnev.intellijmojo.icons.MojoIcons
import com.github.petrmakhnev.intellijmojo.lang.highlighting.MojoSyntaxHighlighter
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.io.StreamUtil
import javax.swing.Icon

class MojoColorSettingsPage : ColorSettingsPage {
    override fun getIcon(): Icon = MojoIcons.FILE

    override fun getHighlighter(): SyntaxHighlighter = MojoSyntaxHighlighter()

    override fun getDemoText(): String = DEMO_TEXT

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = ANNOTATOR_TAGS

    override fun getAttributeDescriptors(): Array<out AttributesDescriptor> = ATTRIBUTES

    override fun getColorDescriptors(): Array<out ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getDisplayName(): @NlsContexts.ConfigurableName String = "Mojo"

    companion object {
        private val ATTRIBUTES = MojoColor.entries.map(MojoColor::attributesDescriptor).toTypedArray()
        private val ANNOTATOR_TAGS = MojoColor.entries.associate { it.name to it.textAttributesKey }

        private val DEMO_TEXT: String by lazy {
            val stream = MojoColorSettingsPage::class.java.classLoader
                .getResourceAsStream("colorSchemes/MojoHighlighterDemo.mojo")
                ?: error("Cannot load resource `colorSchemes/MojoHighlighterDemo.mojo`")
            stream.use { StreamUtil.convertSeparators(it.reader().readText()) }
        }
    }
}
