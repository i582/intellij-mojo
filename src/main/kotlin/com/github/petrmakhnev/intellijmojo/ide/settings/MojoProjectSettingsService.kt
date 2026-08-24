package com.github.petrmakhnev.intellijmojo.ide.settings

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.RootsChangeRescanningInfo
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

val Project.mojoSettings: MojoProjectSettingsService
    get() = service()

@State(
    name = "MojoProjectSettings",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
@Service(Service.Level.PROJECT)
class MojoProjectSettingsService(private val project: Project) :
    PersistentStateComponent<MojoProjectSettingsService.SettingsState> {
    private var settingsState = SettingsState()
    private val settingsModificationTracker = SimpleModificationTracker()

    var stdlibPath: String?
        get() = settingsState.stdlibPath
        set(value) {
            val normalized = normalizePath(value)
            if (settingsState.stdlibPath == normalized) return

            settingsState.stdlibPath = normalized
            settingsModificationTracker.incModificationCount()
            reloadProjectRoots()
        }

    val modificationTracker: ModificationTracker
        get() = settingsModificationTracker

    val stdlibDirectory: VirtualFile?
        get() {
            val configuredPath = settingsState.stdlibPath ?: return null
            val selected = LocalFileSystem.getInstance().findFileByPath(configuredPath)
                ?.takeIf(VirtualFile::isDirectory)
                ?: return null
            if (selected.name == STDLIB_MODULE_NAME) return selected

            return selected.findChild(STDLIB_MODULE_NAME)?.takeIf(VirtualFile::isDirectory)
        }

    fun importRoots(): List<VirtualFile> = listOfNotNull(stdlibDirectory?.parent)

    override fun getState(): SettingsState = settingsState

    override fun loadState(state: SettingsState) {
        val normalized = normalizePath(state.stdlibPath)
        val changed = settingsState.stdlibPath != normalized
        settingsState = state.apply { stdlibPath = normalized }
        if (changed) {
            settingsModificationTracker.incModificationCount()
            reloadProjectRoots()
        }
    }

    private fun reloadProjectRoots() {
        if (project.isDisposed) return
        invokeLater(modalityState = ModalityState.nonModal()) {
            if (project.isDisposed) return@invokeLater
            WriteAction.run<RuntimeException> {
                ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(
                    {},
                    RootsChangeRescanningInfo.RESCAN_DEPENDENCIES_IF_NEEDED,
                )
            }
        }
    }

    class SettingsState {
        var stdlibPath: String? = null
    }

    companion object {
        private const val STDLIB_MODULE_NAME = "std"

        private fun normalizePath(path: String?): String? {
            val value = path?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val localPath = VfsUtilCore.urlToPath(value)
            return FileUtil.toSystemIndependentName(localPath).trimEnd('/')
        }
    }
}
