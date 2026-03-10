package com.github.audioplayer

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

class AudioPlayerSettingsConfigurable : Configurable {
    private var ffmpegPathField: TextFieldWithBrowseButton? = null
    private var ffprobePathField: TextFieldWithBrowseButton? = null
    private var statusLabel: JBLabel? = null

    override fun getDisplayName(): String = "Audio Player"

    override fun createComponent(): JPanel {
        ffmpegPathField =
            TextFieldWithBrowseButton().apply {
                addBrowseFolderListener(
                    "Select ffmpeg",
                    "Select the ffmpeg executable",
                    null,
                    FileChooserDescriptorFactory.createSingleFileDescriptor(),
                )
            }

        ffprobePathField =
            TextFieldWithBrowseButton().apply {
                addBrowseFolderListener(
                    "Select ffprobe",
                    "Select the ffprobe executable",
                    null,
                    FileChooserDescriptorFactory.createSingleFileDescriptor(),
                )
            }

        statusLabel = JBLabel("")

        val autoDetectButton =
            JButton("Auto Detect").apply {
                addActionListener { onAutoDetect() }
            }

        val testButton =
            JButton("Test").apply {
                addActionListener { onTest() }
            }

        val buttonPanel =
            JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(autoDetectButton)
                add(testButton)
            }

        val settings = AudioPlayerSettings.instance.state
        ffmpegPathField!!.text = settings.ffmpegPath
        ffprobePathField!!.text = settings.ffprobePath

        val panel =
            FormBuilder
                .createFormBuilder()
                .addLabeledComponent("ffmpeg path:", ffmpegPathField!!)
                .addLabeledComponent("ffprobe path:", ffprobePathField!!)
                .addComponent(buttonPanel)
                .addComponent(statusLabel!!)
                .addComponentFillVertically(JPanel(), 0)
                .panel

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(panel, BorderLayout.CENTER)
        }
    }

    private fun onAutoDetect() {
        val ffmpeg = FfmpegPathUtil.autoDetectFfmpeg()
        val ffprobe = FfmpegPathUtil.autoDetectFfprobe()

        if (ffmpeg != null) ffmpegPathField?.text = ffmpeg
        if (ffprobe != null) ffprobePathField?.text = ffprobe

        val messages = mutableListOf<String>()
        if (ffmpeg != null) messages.add("ffmpeg: $ffmpeg") else messages.add("ffmpeg: not found")
        if (ffprobe != null) messages.add("ffprobe: $ffprobe") else messages.add("ffprobe: not found")
        statusLabel?.text = messages.joinToString("  |  ")
    }

    private fun onTest() {
        val ffmpegPath = ffmpegPathField?.text.orEmpty().ifEmpty { FfmpegPathUtil.autoDetectFfmpeg().orEmpty() }
        val ffprobePath = ffprobePathField?.text.orEmpty().ifEmpty { FfmpegPathUtil.autoDetectFfprobe().orEmpty() }

        val ffmpegOk = ffmpegPath.isNotEmpty() && FfmpegPathUtil.testExecutable(ffmpegPath)
        val ffprobeOk = ffprobePath.isNotEmpty() && FfmpegPathUtil.testExecutable(ffprobePath)

        val ffmpegStatus = if (ffmpegOk) "OK" else "NG"
        val ffprobeStatus = if (ffprobeOk) "OK" else "NG"
        statusLabel?.text = "ffmpeg: $ffmpegStatus  |  ffprobe: $ffprobeStatus"
    }

    override fun isModified(): Boolean {
        val settings = AudioPlayerSettings.instance.state
        return ffmpegPathField?.text != settings.ffmpegPath ||
            ffprobePathField?.text != settings.ffprobePath
    }

    override fun apply() {
        val settings = AudioPlayerSettings.instance
        settings.loadState(
            AudioPlayerSettings.SettingsState(
                ffmpegPath = ffmpegPathField?.text.orEmpty(),
                ffprobePath = ffprobePathField?.text.orEmpty(),
            ),
        )
    }

    override fun reset() {
        val settings = AudioPlayerSettings.instance.state
        ffmpegPathField?.text = settings.ffmpegPath
        ffprobePathField?.text = settings.ffprobePath
        statusLabel?.text = ""
    }

    override fun disposeUIResources() {
        ffmpegPathField = null
        ffprobePathField = null
        statusLabel = null
    }
}
