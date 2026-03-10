package com.github.audioplayer

import com.intellij.openapi.fileTypes.FileType
import javax.swing.Icon

class AudioFileType private constructor() : FileType {
    override fun getName(): String = "Audio"

    override fun getDescription(): String = "Audio file"

    override fun getDefaultExtension(): String = "mp3"

    override fun getIcon(): Icon? = null

    override fun isBinary(): Boolean = true

    override fun isReadOnly(): Boolean = true

    companion object {
        val INSTANCE = AudioFileType()

        val EXTENSIONS =
            listOf(
                "mp3",
                "wav",
                "ogg",
                "flac",
                "aac",
                "m4a",
                "wma",
                "opus",
                "ape",
                "aiff",
                "aif",
            )
    }
}
