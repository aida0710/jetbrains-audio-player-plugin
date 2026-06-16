package com.github.audioplayer

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class AudioFileEditorProvider :
    FileEditorProvider,
    DumbAware {
    override fun accept(
        project: Project,
        file: VirtualFile,
    ): Boolean = AudioConverter.isSupportedExtension(file.extension)

    override fun createEditor(
        project: Project,
        file: VirtualFile,
    ): FileEditor = AudioFileEditor(file, project)

    override fun getEditorTypeId(): String = "audio-player-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
