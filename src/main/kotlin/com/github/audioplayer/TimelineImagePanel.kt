package com.github.audioplayer

import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JComponent

/**
 * 波形/スペクトラム画像を幅に合わせて描画し、再生位置の縦ラインを重ねる。
 * クリック/ドラッグでその位置の時間を [onSeek] に通知する。
 * 最小サイズを小さく申告し、エディタ分割でのリサイズの妨げにならないようにする。
 */
class TimelineImagePanel(
    private val onSeek: (Long) -> Unit,
) : JComponent() {
    var image: BufferedImage? = null
        set(value) {
            field = value
            repaint()
        }

    var placeholderText: String? = null
        set(value) {
            field = value
            repaint()
        }

    var durationMicros: Long = 0
        set(value) {
            field = value
            repaint()
        }

    var positionMicros: Long = 0
        set(value) {
            field = value
            repaint()
        }

    init {
        isOpaque = false
        val mouse =
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = handle(e.x)

                override fun mouseDragged(e: MouseEvent) = handle(e.x)
            }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    private fun handle(x: Int) {
        if (durationMicros <= 0 || width <= 0) return
        onSeek(timeAtX(x, width, durationMicros))
    }

    override fun getMinimumSize(): Dimension = Dimension(50, 50)

    override fun getPreferredSize(): Dimension = Dimension(800, 200)

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val img = image
        if (img != null && width > 0 && height > 0) {
            g.drawImage(img, 0, 0, width, height, null)
        } else {
            val text = placeholderText
            if (text != null) {
                g.color = JBColor.foreground()
                val fm = g.fontMetrics
                g.drawString(text, (width - fm.stringWidth(text)) / 2, (height + fm.ascent) / 2)
            }
        }
        if (durationMicros > 0 && width > 0) {
            val x = xAtTime(positionMicros, width, durationMicros)
            g.color = PLAYHEAD_COLOR
            g.fillRect(x, 0, 2, height)
        }
    }

    companion object {
        private val PLAYHEAD_COLOR = JBColor(Color(0xFF, 0x33, 0x33), Color(0xFF, 0x55, 0x55))

        fun timeAtX(
            x: Int,
            width: Int,
            durationMicros: Long,
        ): Long {
            if (width <= 0) return 0
            val clampedX = x.coerceIn(0, width)
            return (clampedX.toLong() * durationMicros) / width
        }

        fun xAtTime(
            positionMicros: Long,
            width: Int,
            durationMicros: Long,
        ): Int {
            if (durationMicros <= 0 || width <= 0) return 0
            val clamped = positionMicros.coerceIn(0, durationMicros)
            return ((clamped * width) / durationMicros).toInt().coerceIn(0, width - 1)
        }
    }
}
