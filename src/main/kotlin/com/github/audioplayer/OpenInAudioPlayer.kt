package com.github.audioplayer

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

// 音声ファイルをエディタのタブで開く。EDT から呼ぶ。
// FileEditorManager.openFile は 2026.3 で非推奨になり、代わりの requestOpenFile は 2026.3 で入った試験的な API で
// 2024.3 には無い。対応する全バージョンで非推奨でない OpenFileDescriptor から開く。
fun openInAudioPlayer(
    project: Project,
    file: VirtualFile,
) {
    // 削除などで無効になったファイルは navigate が例外にするので、開かずに終える。
    val descriptor = OpenFileDescriptor(project, file)
    if (descriptor.canNavigate()) descriptor.navigate(true)
}
