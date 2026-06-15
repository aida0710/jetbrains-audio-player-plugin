package com.github.audioplayer

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.*
import javax.swing.event.ChangeEvent
import javax.swing.table.DefaultTableModel

class AudioPlayerPanel(
    private val file: VirtualFile,
) : JPanel(BorderLayout()) {
    private val playerService = AudioPlayerService()

    private val timelinePanel = TimelineImagePanel { micros -> seekToMicros(micros) }

    private lateinit var topSplit: JSplitPane
    private lateinit var mainSplit: JSplitPane
    private lateinit var currentCenter: JComponent

    private val playPauseButton = JButton("\u25B6")
    private val stopButton = JButton("\u23F9")
    private val loopButton = JToggleButton("Loop")
    private val seekSlider = JSlider(0, 1000, 0)
    private val volumeSlider = JSlider(0, 100, 80)
    private val timeLabel = JLabel("00:00 / 00:00")
    private val volumeValueLabel = JLabel("80%")
    private val fileNameLabel = JLabel(file.name)
    private val statusLabel = JLabel("")
    private val settingsLink =
        HyperlinkLabel("ffmpeg/ffprobe が見つかりません。設定で設定してください").apply {
            addHyperlinkListener {
                ShowSettingsUtil.getInstance().showSettingsDialog(null, AudioPlayerSettingsConfigurable::class.java)
            }
            isVisible = false
        }

    // 解析パネルのコンポーネント
    private val analyzeWaveformButton = JButton("Waveform")
    private val analyzeSpectrumButton = JButton("Spectrum")

    // 情報パネルのテーブル
    private val infoTableModel =
        object : DefaultTableModel(arrayOf("Property", "Value"), 0) {
            override fun isCellEditable(
                row: Int,
                column: Int,
            ) = false
        }
    private val infoTable = JTable(infoTableModel)

    private var isSeeking = false
    private var positionTimer: Timer? = null

    private val settingsState
        get() = AudioPlayerSettings.instance.state

    init {
        border = JBUI.Borders.empty(8)
        background = JBColor.background()
        volumeSlider.value = settingsState.lastVolume
        volumeValueLabel.text = "${settingsState.lastVolume}%"
        loopButton.isSelected = settingsState.lastLooping
        setupUI()
        setupListeners()
        loadFile()
    }

    private fun setupUI() {
        val infoPanel = createInfoPanel()
        val controlsPanel = createControlsPanel()

        infoPanel.minimumSize = Dimension(0, 0)
        controlsPanel.minimumSize = Dimension(0, 0)

        topSplit =
            JSplitPane(JSplitPane.HORIZONTAL_SPLIT, infoPanel, controlsPanel).apply {
                resizeWeight = 0.5
                border = null
                isOpaque = false
                minimumSize = Dimension(0, 0)
            }

        val analyzePanel = createAnalyzePanel()
        analyzePanel.minimumSize = Dimension(0, 0)

        mainSplit =
            JSplitPane(JSplitPane.VERTICAL_SPLIT, topSplit, analyzePanel).apply {
                resizeWeight = 0.5
                border = null
                isOpaque = false
                minimumSize = Dimension(0, 0)
            }

        currentCenter = if (settingsState.showVisualizer) mainSplit else topSplit
        add(currentCenter, BorderLayout.CENTER)

        addComponentListener(
            object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent?) {
                    if (currentCenter === mainSplit) {
                        mainSplit.setDividerLocation(0.5)
                        topSplit.setDividerLocation(0.5)
                    }
                }
            },
        )
    }

    override fun getMinimumSize(): Dimension = Dimension(100, 100)

    private fun createInfoPanel(): JPanel {
        infoTable.apply {
            tableHeader.reorderingAllowed = false
            setShowGrid(false)
            intercellSpacing = Dimension(0, 0)
            rowHeight = 22
        }
        infoTable.columnModel.getColumn(0).preferredWidth = 100
        infoTable.columnModel.getColumn(1).preferredWidth = 200

        // 初期表示
        infoTableModel.addRow(arrayOf("File", file.name))
        infoTableModel.addRow(arrayOf("Status", "Loading..."))

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8)
            add(JScrollPane(infoTable), BorderLayout.CENTER)
        }
    }

    private fun createControlsPanel(): JPanel {
        fileNameLabel.font = fileNameLabel.font.deriveFont(Font.BOLD, 16f)
        fileNameLabel.horizontalAlignment = SwingConstants.CENTER

        val topPanel =
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(fileNameLabel, BorderLayout.CENTER)
                add(timeLabel, BorderLayout.EAST)
                border = JBUI.Borders.emptyBottom(12)
            }

        seekSlider.apply {
            isOpaque = false
            toolTipText = "Seek"
        }

        val seekPanel =
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(seekSlider, BorderLayout.CENTER)
                border = JBUI.Borders.emptyBottom(8)
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

        val buttonsPanel =
            JPanel(FlowLayout(FlowLayout.CENTER, 8, 0)).apply {
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
        volumeValueLabel.preferredSize = Dimension(36, 20)

        val volumePanel =
            JPanel(FlowLayout(FlowLayout.CENTER, 4, 0)).apply {
                isOpaque = false
                add(volumeLabel)
                add(volumeSlider)
                add(volumeValueLabel)
                border = JBUI.Borders.emptyTop(4)
            }

        statusLabel.apply {
            horizontalAlignment = SwingConstants.CENTER
            foreground = JBColor.RED
        }

        val centerPanel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(topPanel)
                add(seekPanel)
                add(buttonsPanel)
                add(volumePanel)
                add(Box.createVerticalStrut(4))
                add(statusLabel)
                add(settingsLink)
            }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8)
            add(centerPanel, BorderLayout.CENTER)
        }
    }

    private fun createAnalyzePanel(): JPanel {
        analyzeWaveformButton.toolTipText = "Generate waveform image"
        analyzeSpectrumButton.toolTipText = "Generate spectrum image"

        val buttonPanel =
            JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                isOpaque = false
                add(analyzeWaveformButton)
                add(analyzeSpectrumButton)
            }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8)
            add(buttonPanel, BorderLayout.NORTH)
            add(timelinePanel, BorderLayout.CENTER)
        }
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
            settingsState.lastLooping = loopButton.isSelected
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
            volumeValueLabel.text = "${volumeSlider.value}%"
            playerService.setVolume(volumeSlider.value.toFloat())
            settingsState.lastVolume = volumeSlider.value
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

        analyzeWaveformButton.addActionListener {
            settingsState.defaultView = "waveform"
            loadVisualization()
        }

        analyzeSpectrumButton.addActionListener {
            settingsState.defaultView = "spectrum"
            loadVisualization()
        }

        // Spaceキーで再生/一時停止を切り替え
        val spaceKey = KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0)
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(spaceKey, "togglePlayPause")
        actionMap.put(
            "togglePlayPause",
            object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                    when (playerService.state) {
                        AudioPlayerService.PlaybackState.PLAYING -> playerService.pause()
                        else -> playerService.play()
                    }
                }
            },
        )

        registerSeekKey(KeyEvent.VK_LEFT, "seekBackward") { seekRelative(-5_000_000) }
        registerSeekKey(KeyEvent.VK_RIGHT, "seekForward") { seekRelative(5_000_000) }
        registerSeekKey(KeyEvent.VK_HOME, "seekStart") { seekToMicros(0) }
    }

    private fun loadFile() {
        statusLabel.text = "Loading..."
        Thread {
            val metadata = AudioProbe.probe(File(file.path))
            playerService.load(File(file.path))

            SwingUtilities.invokeLater {
                timelinePanel.durationMicros = playerService.totalMicroseconds
                if (playerService.totalMicroseconds > 0) {
                    statusLabel.text = ""
                    playerService.setVolume(volumeSlider.value.toFloat())
                    playerService.setLooping(loopButton.isSelected)
                } else if (statusLabel.text == "Loading...") {
                    statusLabel.text = "Failed to load audio file"
                }

                val ffmpegMissing = FfmpegPathUtil.findFfmpeg() == null
                val ffprobeMissing = FfmpegPathUtil.findFfprobe() == null
                settingsLink.isVisible = ffmpegMissing || ffprobeMissing

                updateTimeLabel(0, playerService.totalMicroseconds)
                updateInfoTable(metadata)

                if (settingsState.showVisualizer) {
                    loadVisualization()
                }
            }
        }.start()
    }

    private fun updateInfoTable(metadata: AudioMetadata?) {
        infoTableModel.rowCount = 0
        infoTableModel.addRow(arrayOf("File", file.name))

        if (metadata != null) {
            infoTableModel.addRow(arrayOf("Encoding", metadata.encoding))
            infoTableModel.addRow(arrayOf("Format", metadata.format))
            infoTableModel.addRow(
                arrayOf("Channels", AudioProbe.formatChannels(metadata.channels, metadata.channelLayout)),
            )
            infoTableModel.addRow(arrayOf("Sample Rate", AudioProbe.formatSampleRate(metadata.sampleRate)))
            infoTableModel.addRow(arrayOf("File Size", AudioProbe.formatFileSize(metadata.fileSize)))
            val durationStr = AudioPlayerService.formatTime(metadata.durationSeconds.toLong())
            infoTableModel.addRow(arrayOf("Duration", durationStr))
        } else {
            infoTableModel.addRow(arrayOf("Info", "Metadata unavailable (ffprobe required)"))
        }
    }

    private fun registerSeekKey(
        keyCode: Int,
        actionKey: String,
        action: () -> Unit,
    ) {
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(keyCode, 0), actionKey)
        actionMap.put(
            actionKey,
            object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) = action()
            },
        )
    }

    private fun seekRelative(deltaMicros: Long) {
        val total = playerService.totalMicroseconds
        if (total <= 0) return
        seekToMicros(AudioPlayerService.computeSeekTarget(playerService.currentMicroseconds, deltaMicros, total))
    }

    private fun seekToMicros(micros: Long) {
        val total = playerService.totalMicroseconds
        if (total <= 0) return
        playerService.seek(micros)
        seekSlider.value = ((micros * 1000) / total).toInt()
        timelinePanel.positionMicros = micros
        updateTimeLabel(micros, total)
    }

    private fun loadVisualization() {
        val view = settingsState.defaultView
        val isSpectrum = view == "spectrum"
        timelinePanel.image = null
        timelinePanel.placeholderText = if (isSpectrum) "Generating spectrum..." else "Generating waveform..."
        Thread {
            val img: BufferedImage? =
                if (isSpectrum) {
                    AudioAnalyzer.generateSpectrumImage(File(file.path), 800, 200)
                } else {
                    AudioAnalyzer.generateWaveformImage(File(file.path), 800, 200)
                }
            SwingUtilities.invokeLater {
                timelinePanel.durationMicros = playerService.totalMicroseconds
                if (img != null) {
                    timelinePanel.placeholderText = null
                    timelinePanel.image = img
                } else {
                    timelinePanel.placeholderText = "Failed to generate (ffmpeg required)"
                }
            }
        }.start()
    }

    private fun startPositionTimer() {
        stopPositionTimer()
        positionTimer =
            Timer(100) {
                if (!isSeeking) {
                    val current = playerService.currentMicroseconds
                    val total = playerService.totalMicroseconds
                    if (total > 0) {
                        seekSlider.value = ((current * 1000) / total).toInt()
                        timelinePanel.positionMicros = current
                    }
                    updateTimeLabel(current, total)
                }
            }.apply { start() }
    }

    private fun stopPositionTimer() {
        positionTimer?.stop()
        positionTimer = null
    }

    private fun updateTimeLabel(
        currentMicros: Long,
        totalMicros: Long,
    ) {
        val currentSec = currentMicros / 1_000_000
        val totalSec = totalMicros / 1_000_000
        timeLabel.text = "${AudioPlayerService.formatTime(currentSec)} / ${AudioPlayerService.formatTime(totalSec)}"
    }

    fun pausePlayback() {
        if (playerService.state == AudioPlayerService.PlaybackState.PLAYING) {
            playerService.pause()
        }
    }

    fun dispose() {
        stopPositionTimer()
        playerService.dispose()
    }
}
