package com.github.petrmakhnev.intellijmojo.ide.settings

import com.github.petrmakhnev.intellijmojo.MojoBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.io.File

class MojoProjectConfigurable(private val project: Project) :
    BoundConfigurable(MojoBundle["plugin.name"]),
    Configurable.NoScroll,
    Disposable {
    private val stdlibPathField = TextFieldWithBrowseButton(null, this).apply {
        addBrowseFolderListener(
            null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle(MojoBundle["settings.mojo.stdlib.dialog.title"]),
        )
    }

    override fun createPanel(): DialogPanel = panel {
        row(MojoBundle["settings.mojo.stdlib.label"]) {
            cell(stdlibPathField)
                .align(AlignX.FILL)
                .validationOnApply { field -> validateStdlibPath(field.text, field) }
        }
        row {
            comment(MojoBundle["settings.mojo.stdlib.comment"])
        }

        val settings = project.mojoSettings
        onApply {
            settings.stdlibPath = stdlibPathField.text.ifBlank { null }
            stdlibPathField.text = settings.stdlibPath.orEmpty()
        }
        onReset {
            stdlibPathField.text = settings.stdlibPath.orEmpty()
        }
        onIsModified {
            stdlibPathField.text.ifBlank { null } != settings.stdlibPath
        }
    }

    private fun validateStdlibPath(path: String, field: TextFieldWithBrowseButton): ValidationInfo? {
        val value = path.trim()
        if (value.isEmpty()) return null

        val selected = File(VfsUtilCore.urlToPath(value))
        val stdDirectory = if (selected.name == "std") selected else selected.resolve("std")
        val preludeInitializer = stdDirectory.resolve("prelude/__init__.mojo")
        return if (selected.isDirectory && preludeInitializer.isFile) {
            null
        } else {
            ValidationInfo(MojoBundle["settings.mojo.stdlib.validation"], field)
        }
    }

    override fun dispose() = Unit
}
