package com.github.audioplayer

import com.intellij.openapi.fileTypes.FileType
import javax.swing.Icon

class AudioFileType private constructor() : FileType {
    override fun getName(): String = "Audio"

    override fun getDescription(): String = AudioPlayerBundle.message("fileType.description")

    override fun getDefaultExtension(): String = "mp3"

    override fun getIcon(): Icon? = null

    override fun isBinary(): Boolean = true

    override fun isReadOnly(): Boolean = true

    companion object {
        val INSTANCE = AudioFileType()
    }
}
