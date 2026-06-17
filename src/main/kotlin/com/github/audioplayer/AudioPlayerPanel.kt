package com.github.audioplayer

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.event.ChangeEvent
import javax.swing.table.DefaultTableModel

class AudioPlayerPanel(
    private val file: VirtualFile,
    private val project: Project,
) : JPanel(BorderLayout()) {
    private val playerService = AudioPlayerService()
    private val log = Logger.getInstance(AudioPlayerPanel::class.java)

    private val timelinePanel = TimelineImagePanel { micros -> seekToMicros(micros) }

    private lateinit var topSplit: JSplitPane
    private lateinit var mainSplit: JSplitPane
    private lateinit var currentCenter: JComponent
    private var dividersInitialized = false

    private val playPauseButton = JButton("\u25B6")
    private val stopButton = JButton("\u23F9")
    private val loopButton = JToggleButton("Loop")
    private val visualizerToggle = JCheckBox("ビジュアライザを表示", true)
    private val seekSlider = JSlider(0, 1000, 0)
    private val volumeSlider = JSlider(0, 100, 80)
    private val speedCombo = JComboBox(arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x"))
    private val levelMeter = LevelMeterBar()
    private val timeLabel = JLabel("00:00 / 00:00")
    private val volumeValueLabel = JLabel("80%")
    private val fileNameLabel = JLabel(file.name)
    private val statusLabel = JLabel("")
    private val settingsLink =
        HyperlinkLabel("ffmpeg/ffprobe が見つかりません。設定で設定してください").apply {
            addHyperlinkListener {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, AudioPlayerSettingsConfigurable::class.java)
            }
            isVisible = false
        }

    // 解析パネルのコンポーネント
    private val analyzeWaveformButton = JButton("Waveform")
    private val analyzeSpectrumButton = JButton("Spectrum")
    private val saveImageButton = JButton("画像を保存")

    // 表示窓・ズーム/スクロール
    private var viewStartMicros = 0L
    private var viewEndMicros = 0L
    private val zoomInButton = JButton("＋")
    private val zoomOutButton = JButton("−")
    private val zoomFitButton = JButton("全体")
    private val splitChannelsToggle = JToggleButton("L/R分離")
    private val scrollBar = JScrollBar(JScrollBar.HORIZONTAL)
    private var isSyncingScrollBar = false

    // 情報パネルのアクションボタン
    private val copyInfoButton = JButton("情報をコピー")
    private val measureLufsButton = JButton("ラウドネス測定")
    private val exportAudioButton = JButton("別形式で書き出し")
    private val infoActionsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }

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
    private var visualizationRequestId = 0

    private var speedSeq = 0
    private val levelExecutor = Executors.newSingleThreadExecutor()
    private val levelBusy = AtomicBoolean(false)

    // プラグインの動的アンロード中などサービスが取得できない場合に備え、取得失敗時は
    // 使い捨ての既定値を返す（teardown 中のリスナ発火による NPE を防ぐ）。
    private val settingsState: AudioPlayerSettings.SettingsState
        get() =
            try {
                AudioPlayerSettings.instance.state
            } catch (e: Exception) {
                AudioPlayerSettings.SettingsState()
            }

    init {
        border = JBUI.Borders.empty(8)
        background = JBColor.background()
        volumeSlider.value = settingsState.lastVolume
        volumeValueLabel.text = "${settingsState.lastVolume}%"
        loopButton.isSelected = settingsState.lastLooping
        speedCombo.selectedItem =
            (0 until speedCombo.itemCount)
                .map { speedCombo.getItemAt(it) }
                .firstOrNull { it.removeSuffix("x").toFloat() == settingsState.lastSpeed } ?: "1.0x"
        setupUI()
        setupListeners()
        loadFile()
    }

    // 音声ファイルのドラッグ&ドロップを受け付けて開く。flavor 未対応(IDE内部ドラッグ等)は拒否し、
    // VFS 解決は EDT を塞がないようプール上で行い、openFile のみ EDT に戻す。
    private fun setupDropTarget() {
        DropTarget(
            this,
            object : DropTargetAdapter() {
                override fun drop(event: DropTargetDropEvent) {
                    if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        event.rejectDrop()
                        return
                    }
                    try {
                        event.acceptDrop(DnDConstants.ACTION_COPY)
                        @Suppress("UNCHECKED_CAST")
                        val dropped =
                            event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<java.io.File>
                        val audio = AudioConverter.firstAudioFile(dropped)
                        event.dropComplete(audio != null)
                        if (audio != null) {
                            ApplicationManager.getApplication().executeOnPooledThread {
                                val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(audio)
                                if (vf != null) {
                                    ApplicationManager.getApplication().invokeLater {
                                        FileEditorManager.getInstance(project).openFile(vf, true)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        log.error("Drop failed", e)
                        event.dropComplete(false)
                    }
                }
            },
        )
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

        // 初回レイアウト確定時に一度だけ 50/50 へ。以降は resizeWeight に任せ、
        // ユーザーがドラッグした分割位置を維持できるようにする（毎回 0.5 に戻さない）。
        addComponentListener(
            object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent?) {
                    if (currentCenter === mainSplit && !dividersInitialized && width > 0 && height > 0) {
                        mainSplit.setDividerLocation(0.5)
                        topSplit.setDividerLocation(0.5)
                        dividersInitialized = true
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

        infoActionsPanel.add(copyInfoButton)
        infoActionsPanel.add(measureLufsButton)
        infoActionsPanel.add(exportAudioButton)

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8)
            add(JScrollPane(infoTable), BorderLayout.CENTER)
            add(infoActionsPanel, BorderLayout.SOUTH)
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

        val speedPanel =
            JPanel(FlowLayout(FlowLayout.CENTER, 4, 0)).apply {
                isOpaque = false
                add(JLabel("Speed"))
                add(speedCombo)
                add(levelMeter)
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
                add(speedPanel)
                add(Box.createVerticalStrut(4))
                add(statusLabel)
                add(settingsLink)
                add(Box.createVerticalStrut(4))
                add(visualizerToggle.apply { alignmentX = java.awt.Component.CENTER_ALIGNMENT })
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
        zoomInButton.toolTipText = "拡大"
        zoomOutButton.toolTipText = "縮小"
        zoomFitButton.toolTipText = "全体を表示"
        splitChannelsToggle.toolTipText = "波形をL/Rチャンネルで分離表示"
        splitChannelsToggle.isSelected = settingsState.waveformSplitChannels
        scrollBar.isEnabled = false

        val buttonPanel =
            JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                isOpaque = false
                add(analyzeWaveformButton)
                add(analyzeSpectrumButton)
                add(saveImageButton)
                add(zoomOutButton)
                add(zoomInButton)
                add(zoomFitButton)
                add(splitChannelsToggle)
            }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8)
            add(buttonPanel, BorderLayout.NORTH)
            add(timelinePanel, BorderLayout.CENTER)
            add(scrollBar, BorderLayout.SOUTH)
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

        speedCombo.addActionListener {
            val sel = speedCombo.selectedItem as? String ?: return@addActionListener
            val newSpeed = sel.removeSuffix("x").toFloat()
            settingsState.lastSpeed = newSpeed
            requestSpeedChange(newSpeed)
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
                        levelMeter.peak = 0f
                        levelMeter.rms = 0f
                    }
                    AudioPlayerService.PlaybackState.STOPPED -> {
                        playPauseButton.text = "\u25B6"
                        seekSlider.value = 0
                        stopPositionTimer()
                        updateTimeLabel(0, playerService.totalMicroseconds)
                        levelMeter.peak = 0f
                        levelMeter.rms = 0f
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

        zoomInButton.addActionListener {
            val (s, e) =
                TimelineImagePanel.zoomWindow(
                    viewStartMicros,
                    viewEndMicros,
                    0.5,
                    anchorFraction(),
                    playerService.totalMicroseconds,
                )
            setWindow(s, e)
        }
        zoomOutButton.addActionListener {
            val (s, e) =
                TimelineImagePanel.zoomWindow(
                    viewStartMicros,
                    viewEndMicros,
                    2.0,
                    anchorFraction(),
                    playerService.totalMicroseconds,
                )
            setWindow(s, e)
        }
        zoomFitButton.addActionListener {
            setWindow(0, playerService.totalMicroseconds)
        }
        splitChannelsToggle.addActionListener {
            settingsState.waveformSplitChannels = splitChannelsToggle.isSelected
            loadVisualization()
        }
        scrollBar.addAdjustmentListener {
            if (isSyncingScrollBar) return@addAdjustmentListener
            val unit = 1000L
            val newStart = scrollBar.value.toLong() * unit
            val w = viewEndMicros - viewStartMicros
            viewStartMicros = newStart.coerceIn(0, (playerService.totalMicroseconds - w).coerceAtLeast(0))
            viewEndMicros = viewStartMicros + w
            timelinePanel.viewStartMicros = viewStartMicros
            timelinePanel.viewEndMicros = viewEndMicros
            if (!scrollBar.valueIsAdjusting) {
                loadVisualization()
            }
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

        visualizerToggle.isSelected = settingsState.showVisualizer
        visualizerToggle.addActionListener {
            applyVisualizerVisibility(visualizerToggle.isSelected)
        }

        copyInfoButton.addActionListener {
            val rows =
                (0 until infoTableModel.rowCount).map {
                    (infoTableModel.getValueAt(it, 0)?.toString() ?: "") to
                        (infoTableModel.getValueAt(it, 1)?.toString() ?: "")
                }
            CopyPasteManager.getInstance().setContents(StringSelection(infoRowsToText(rows)))
        }

        measureLufsButton.addActionListener {
            measureLufsButton.isEnabled = false
            setInfoRow("Loudness", "Measuring...")
            Thread {
                val result = LoudnessAnalyzer.measure(File(file.path))
                SwingUtilities.invokeLater {
                    setInfoRow("Loudness", result ?: "測定不可 (ffmpeg required)")
                    measureLufsButton.isEnabled = true
                }
            }.start()
        }

        exportAudioButton.addActionListener {
            val chooser = JFileChooser()
            chooser.selectedFile = File("${File(file.path).nameWithoutExtension}.mp3")
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return@addActionListener
            val output = chooser.selectedFile
            exportAudioButton.isEnabled = false
            Thread {
                val ok = AudioConverter.export(File(file.path), output)
                SwingUtilities.invokeLater {
                    notifyUser(
                        if (ok) "書き出し完了: ${output.name}" else "書き出しに失敗しました (ffmpeg required)",
                        if (ok) NotificationType.INFORMATION else NotificationType.WARNING,
                    )
                    exportAudioButton.isEnabled = true
                }
            }.start()
        }

        saveImageButton.addActionListener {
            val img = timelinePanel.image
            if (img == null) {
                notifyUser("保存する画像がありません。先に波形/スペクトラムを生成してください。", NotificationType.WARNING)
                return@addActionListener
            }
            val snapshot =
                java.awt.image.BufferedImage(
                    img.width,
                    img.height,
                    if (img.type ==
                        java.awt.image.BufferedImage.TYPE_CUSTOM
                    ) {
                        java.awt.image.BufferedImage.TYPE_INT_ARGB
                    } else {
                        img.type
                    },
                )
            snapshot.createGraphics().apply {
                drawImage(img, 0, 0, null)
                dispose()
            }
            val chooser = JFileChooser()
            chooser.selectedFile =
                File(defaultImageFileName(File(file.path).nameWithoutExtension, settingsState.defaultView))
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return@addActionListener
            val out = chooser.selectedFile
            Thread {
                val ok =
                    try {
                        ImageIO.write(snapshot, "png", out)
                    } catch (e: Exception) {
                        log.error("Failed to save image", e)
                        false
                    }
                SwingUtilities.invokeLater {
                    notifyUser(
                        if (ok) "画像を保存しました: ${out.name}" else "画像の保存に失敗しました",
                        if (ok) NotificationType.INFORMATION else NotificationType.WARNING,
                    )
                }
            }.start()
        }

        setupDropTarget()
    }

    private fun loadFile() {
        statusLabel.text = "Loading..."
        Thread {
            val metadata = AudioProbe.probe(File(file.path))
            playerService.load(File(file.path))

            val ffmpegMissing = FfmpegPathUtil.findFfmpeg() == null
            val ffprobeMissing = FfmpegPathUtil.findFfprobe() == null

            SwingUtilities.invokeLater {
                timelinePanel.durationMicros = playerService.totalMicroseconds
                viewStartMicros = 0
                viewEndMicros = playerService.totalMicroseconds
                timelinePanel.viewStartMicros = viewStartMicros
                timelinePanel.viewEndMicros = viewEndMicros
                syncScrollBar()
                if (playerService.totalMicroseconds > 0) {
                    statusLabel.text = ""
                    playerService.setVolume(volumeSlider.value.toFloat())
                    playerService.setLooping(loopButton.isSelected)
                    if (settingsState.lastSpeed != 1.0f) {
                        requestSpeedChange(settingsState.lastSpeed)
                    }
                } else if (statusLabel.text == "Loading...") {
                    statusLabel.text = "Failed to load audio file"
                }

                settingsLink.isVisible = ffmpegMissing || ffprobeMissing
                if (ffmpegMissing || ffprobeMissing) {
                    notifyDependencyMissing(ffmpegMissing, ffprobeMissing)
                }

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
            val tagLabels =
                listOf(
                    "title" to "Title",
                    "artist" to "Artist",
                    "album" to "Album",
                    "date" to "Year",
                    "genre" to "Genre",
                    "track" to "Track",
                )
            for ((key, label) in tagLabels) {
                metadata.tags[key]?.let { infoTableModel.addRow(arrayOf(label, it)) }
            }
        } else {
            infoTableModel.addRow(arrayOf("Info", "Metadata unavailable (ffprobe required)"))
        }
    }

    private fun setInfoRow(
        label: String,
        value: String,
    ) {
        for (i in 0 until infoTableModel.rowCount) {
            if (infoTableModel.getValueAt(i, 0) == label) {
                infoTableModel.setValueAt(value, i, 1)
                return
            }
        }
        infoTableModel.addRow(arrayOf(label, value))
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

    private fun setWindow(
        start: Long,
        end: Long,
    ) {
        val total = playerService.totalMicroseconds
        if (total <= 0) return
        viewStartMicros = start.coerceIn(0, total)
        viewEndMicros = end.coerceIn(viewStartMicros + 1, total)
        timelinePanel.viewStartMicros = viewStartMicros
        timelinePanel.viewEndMicros = viewEndMicros
        syncScrollBar()
        loadVisualization()
    }

    private fun anchorFraction(): Double {
        val w = (viewEndMicros - viewStartMicros).coerceAtLeast(1)
        val pos = playerService.currentMicroseconds
        return if (pos in viewStartMicros..viewEndMicros) {
            (pos - viewStartMicros).toDouble() / w
        } else {
            0.5
        }
    }

    private fun syncScrollBar() {
        val total = playerService.totalMicroseconds
        if (total <= 0) return
        val unit = 1000
        val extent = ((viewEndMicros - viewStartMicros) / unit).toInt().coerceAtLeast(1)
        val max = (total / unit).toInt().coerceAtLeast(1)
        val value = (viewStartMicros / unit).toInt()
        isSyncingScrollBar = true
        scrollBar.setValues(value, extent, 0, max)
        scrollBar.isEnabled = extent < max
        isSyncingScrollBar = false
    }

    private fun requestSpeedChange(newSpeed: Float) {
        val seq = ++speedSeq
        speedCombo.isEnabled = false
        Thread {
            val wav = playerService.prepareSpeed(newSpeed)
            SwingUtilities.invokeLater {
                if (seq == speedSeq) {
                    if (wav != null) {
                        playerService.applySpeed(newSpeed, wav)
                    } else {
                        notifyUser("速度変更に失敗しました (ffmpeg required)", NotificationType.WARNING)
                    }
                    speedCombo.isEnabled = true
                }
            }
        }.start()
    }

    private fun loadVisualization() {
        val requestId = ++visualizationRequestId
        val view = settingsState.defaultView
        val isSpectrum = view == "spectrum"
        val total = playerService.totalMicroseconds
        val full = viewStartMicros <= 0 && (viewEndMicros >= total || viewEndMicros <= 0)
        val startSec = if (full) null else viewStartMicros / 1_000_000.0
        val lenSec = if (full) null else (viewEndMicros - viewStartMicros) / 1_000_000.0
        val split = settingsState.waveformSplitChannels
        timelinePanel.image = null
        timelinePanel.placeholderText = if (isSpectrum) "Generating spectrum..." else "Generating waveform..."
        Thread {
            val img: BufferedImage? =
                if (isSpectrum) {
                    AudioAnalyzer.generateSpectrumImage(File(file.path), 800, 200, startSec, lenSec)
                } else {
                    AudioAnalyzer.generateWaveformImage(File(file.path), 800, 200, startSec, lenSec, split)
                }
            SwingUtilities.invokeLater {
                if (requestId != visualizationRequestId) return@invokeLater
                if (img != null) {
                    timelinePanel.placeholderText = null
                    timelinePanel.image = img
                } else {
                    timelinePanel.placeholderText = "Failed to generate (ffmpeg required)"
                }
            }
        }.start()
    }

    private fun applyVisualizerVisibility(show: Boolean) {
        settingsState.showVisualizer = show
        remove(currentCenter)
        if (show) {
            // OFF の間に topSplit をパネル直下へ移していた場合、mainSplit から外れているため再アタッチする。
            // （Swing は1コンポーネント1親。これを怠ると mainSplit の上半分が空になり下半分が全面占有してしまう）
            mainSplit.topComponent = topSplit
            currentCenter = mainSplit
        } else {
            currentCenter = topSplit
        }
        add(currentCenter, BorderLayout.CENTER)
        revalidate()
        repaint()
        if (show) {
            if (timelinePanel.image == null && playerService.totalMicroseconds > 0) {
                loadVisualization()
            }
            SwingUtilities.invokeLater {
                mainSplit.setDividerLocation(0.5)
                topSplit.setDividerLocation(0.5)
                dividersInitialized = true
            }
        }
    }

    private fun notifyUser(
        content: String,
        type: NotificationType,
    ) {
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            ?.createNotification("Audio Player", content, type)
            ?.notify(project)
    }

    private fun notifyDependencyMissing(
        ffmpegMissing: Boolean,
        ffprobeMissing: Boolean,
    ) {
        if (dependencyNotificationShown) return
        val group =
            NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP_ID) ?: return
        val missing =
            buildList {
                if (ffmpegMissing) add("ffmpeg")
                if (ffprobeMissing) add("ffprobe")
            }.joinToString(" / ")
        val content =
            "$missing が見つかりません。再生・波形表示には ffmpeg、メタデータ表示には ffprobe が必要です。" +
                "インストール例 — macOS: brew install ffmpeg / Ubuntu: sudo apt install ffmpeg / Windows: winget install ffmpeg"
        group
            .createNotification("Audio Player", content, NotificationType.WARNING)
            .addAction(
                NotificationAction.createSimple("設定を開く") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(
                        project,
                        AudioPlayerSettingsConfigurable::class.java,
                    )
                },
            ).notify(project)
        dependencyNotificationShown = true
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
                    if (levelBusy.compareAndSet(false, true)) {
                        levelExecutor.submit {
                            val lvl = playerService.currentLevel()
                            SwingUtilities.invokeLater {
                                if (lvl != null) {
                                    levelMeter.peak = lvl.first
                                    levelMeter.rms = lvl.second
                                } else {
                                    levelMeter.peak = 0f
                                    levelMeter.rms = 0f
                                }
                                levelBusy.set(false)
                            }
                        }
                    }
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
        levelExecutor.shutdownNow()
        playerService.dispose()
    }

    companion object {
        private const val NOTIFICATION_GROUP_ID = "Audio Player"
        private var dependencyNotificationShown = false

        fun infoRowsToText(rows: List<Pair<String, String>>): String =
            rows.joinToString("\n") { "${it.first}\t${it.second}" }

        fun defaultImageFileName(
            baseName: String,
            view: String,
        ): String = "${baseName}_$view.png"
    }
}
