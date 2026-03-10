package com.github.audioplayer

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.io.File
import javax.swing.*
import javax.swing.event.ChangeEvent

class AudioPlayerPanel(private val file: VirtualFile) : JPanel(BorderLayout()) {

    private val playerService = AudioPlayerService()

    private val playPauseButton = JButton("\u25B6")
    private val stopButton = JButton("\u23F9")
    private val loopButton = JToggleButton("Loop")
    private val seekSlider = JSlider(0, 1000, 0)
    private val volumeSlider = JSlider(0, 100, 80)
    private val timeLabel = JLabel("00:00 / 00:00")
    private val fileNameLabel = JLabel(file.name)
    private val statusLabel = JLabel("")

    private var isSeeking = false
    private var positionTimer: Timer? = null

    init {
        border = JBUI.Borders.empty(20)
        background = JBColor.background()
        setupUI()
        setupListeners()
        loadFile()
    }

    private fun setupUI() {
        fileNameLabel.font = fileNameLabel.font.deriveFont(Font.BOLD, 16f)
        fileNameLabel.horizontalAlignment = SwingConstants.CENTER

        val topPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(fileNameLabel, BorderLayout.CENTER)
            add(timeLabel, BorderLayout.EAST)
            border = JBUI.Borders.emptyBottom(16)
        }

        seekSlider.apply {
            isOpaque = false
            toolTipText = "Seek"
        }

        val seekPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(seekSlider, BorderLayout.CENTER)
            border = JBUI.Borders.emptyBottom(12)
        }

        playPauseButton.apply {
            font = font.deriveFont(18f)
            toolTipText = "Play / Pause"
            preferredSize = Dimension(60, 40)
        }
        stopButton.apply {
            font = font.deriveFont(18f)
            toolTipText = "Stop"
            preferredSize = Dimension(60, 40)
        }
        loopButton.apply {
            toolTipText = "Loop"
            preferredSize = Dimension(60, 40)
        }

        val controlsPanel = JPanel(FlowLayout(FlowLayout.CENTER, 8, 0)).apply {
            isOpaque = false
            add(playPauseButton)
            add(stopButton)
            add(loopButton)
        }

        val volumeLabel = JLabel("Vol")
        volumeSlider.apply {
            isOpaque = false
            preferredSize = Dimension(120, 20)
            toolTipText = "Volume"
        }

        val volumePanel = JPanel(FlowLayout(FlowLayout.CENTER, 4, 0)).apply {
            isOpaque = false
            add(volumeLabel)
            add(volumeSlider)
            border = JBUI.Borders.emptyTop(8)
        }

        statusLabel.apply {
            horizontalAlignment = SwingConstants.CENTER
            foreground = JBColor.RED
        }

        val centerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(topPanel)
            add(seekPanel)
            add(controlsPanel)
            add(volumePanel)
            add(Box.createVerticalStrut(8))
            add(statusLabel)
        }

        val wrapperPanel = JPanel(GridBagLayout()).apply {
            isOpaque = false
            add(centerPanel)
        }

        add(wrapperPanel, BorderLayout.CENTER)
    }

    private fun setupListeners() {
        playPauseButton.addActionListener {
            when (playerService.state) {
                AudioPlayerService.PlaybackState.PLAYING -> playerService.pause()
                else -> playerService.play()
            }
        }

        stopButton.addActionListener {
            playerService.stop()
            seekSlider.value = 0
        }

        loopButton.addActionListener {
            playerService.setLooping(loopButton.isSelected)
        }

        seekSlider.addChangeListener { _: ChangeEvent ->
            if (seekSlider.valueIsAdjusting) {
                isSeeking = true
                val total = playerService.totalMicroseconds
                val target = (seekSlider.value.toLong() * total) / 1000
                updateTimeLabel(target, total)
            } else if (isSeeking) {
                isSeeking = false
                val total = playerService.totalMicroseconds
                val target = (seekSlider.value.toLong() * total) / 1000
                playerService.seek(target)
            }
        }

        volumeSlider.addChangeListener {
            playerService.setVolume(volumeSlider.value.toFloat())
        }

        playerService.onStateChanged = { state ->
            SwingUtilities.invokeLater {
                when (state) {
                    AudioPlayerService.PlaybackState.PLAYING -> {
                        playPauseButton.text = "\u23F8"
                        startPositionTimer()
                    }
                    AudioPlayerService.PlaybackState.PAUSED -> {
                        playPauseButton.text = "\u25B6"
                        stopPositionTimer()
                    }
                    AudioPlayerService.PlaybackState.STOPPED -> {
                        playPauseButton.text = "\u25B6"
                        seekSlider.value = 0
                        stopPositionTimer()
                        updateTimeLabel(0, playerService.totalMicroseconds)
                    }
                }
            }
        }

        playerService.onError = { message ->
            SwingUtilities.invokeLater {
                statusLabel.text = message
            }
        }
    }

    private fun loadFile() {
        statusLabel.text = "Loading..."
        Thread {
            playerService.load(File(file.path))
            SwingUtilities.invokeLater {
                statusLabel.text = ""
                playerService.setVolume(volumeSlider.value.toFloat())
                updateTimeLabel(0, playerService.totalMicroseconds)
            }
        }.start()
    }

    private fun startPositionTimer() {
        stopPositionTimer()
        positionTimer = Timer(100) {
            if (!isSeeking) {
                val current = playerService.currentMicroseconds
                val total = playerService.totalMicroseconds
                if (total > 0) {
                    seekSlider.value = ((current * 1000) / total).toInt()
                }
                updateTimeLabel(current, total)
            }
        }.apply { start() }
    }

    private fun stopPositionTimer() {
        positionTimer?.stop()
        positionTimer = null
    }

    private fun updateTimeLabel(currentMicros: Long, totalMicros: Long) {
        val currentSec = currentMicros / 1_000_000
        val totalSec = totalMicros / 1_000_000
        timeLabel.text = "${AudioPlayerService.formatTime(currentSec)} / ${AudioPlayerService.formatTime(totalSec)}"
    }

    fun dispose() {
        stopPositionTimer()
        playerService.dispose()
    }
}
