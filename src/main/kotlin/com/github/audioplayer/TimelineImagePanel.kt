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
 * 波形/スペクトラム画像を表示窓 [viewStartMicros, viewEndMicros] に対応して描画する。
 * 再生位置ライン・再生済み塗り・時間ルーラーを重ね、クリック/ドラッグで [onSeek] に絶対時間を通知する。
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

    var viewStartMicros: Long = 0
        set(value) {
            field = value
            repaint()
        }

    var viewEndMicros: Long = 0
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
        if (viewEndMicros <= viewStartMicros || width <= 0) return
        onSeek(timeAtX(x, width, viewStartMicros, viewEndMicros))
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

        val vs = viewStartMicros
        val ve = viewEndMicros
        if (ve <= vs || width <= 0) return

        if (positionMicros > vs) {
            val px = xAtTime(positionMicros.coerceAtMost(ve), width, vs, ve).coerceIn(0, width)
            g.color = PROGRESS_COLOR
            g.fillRect(0, 0, px, height)
        }

        g.color = RULER_COLOR
        for (tick in computeTicks(vs, ve, width, 80)) {
            g.drawLine(tick.xPixel, height - RULER_HEIGHT, tick.xPixel, height)
            g.drawString(tick.label, tick.xPixel + 2, height - 3)
        }

        if (positionMicros in vs..ve) {
            val x = xAtTime(positionMicros, width, vs, ve)
            g.color = PLAYHEAD_COLOR
            g.fillRect(x, 0, 2, height)
        }
    }

    data class Tick(
        val xPixel: Int,
        val label: String,
    )

    companion object {
        private const val RULER_HEIGHT = 10
        private val PLAYHEAD_COLOR = JBColor(Color(0xFF, 0x33, 0x33), Color(0xFF, 0x55, 0x55))
        private val PROGRESS_COLOR = Color(0x44, 0x88, 0xCC, 0x40)
        private val RULER_COLOR = JBColor(Color(0x88, 0x88, 0x88), Color(0xAA, 0xAA, 0xAA))

        private val NICE_INTERVALS =
            listOf(
                100_000L,
                200_000L,
                500_000L,
                1_000_000L,
                2_000_000L,
                5_000_000L,
                10_000_000L,
                15_000_000L,
                30_000_000L,
                60_000_000L,
                120_000_000L,
                300_000_000L,
                600_000_000L,
                1_800_000_000L,
                3_600_000_000L,
            )

        fun timeAtX(
            x: Int,
            width: Int,
            viewStart: Long,
            viewEnd: Long,
        ): Long {
            if (width <= 0 || viewEnd <= viewStart) return viewStart
            val clampedX = x.coerceIn(0, width)
            return viewStart + (clampedX.toLong() * (viewEnd - viewStart)) / width
        }

        fun xAtTime(
            t: Long,
            width: Int,
            viewStart: Long,
            viewEnd: Long,
        ): Int {
            if (width <= 0 || viewEnd <= viewStart) return 0
            return (((t - viewStart) * width) / (viewEnd - viewStart)).toInt()
        }

        fun zoomWindow(
            viewStart: Long,
            viewEnd: Long,
            factor: Double,
            anchorFraction: Double,
            duration: Long,
        ): Pair<Long, Long> {
            val minWidth = 100_000L
            val curWidth = (viewEnd - viewStart).coerceAtLeast(1)
            val anchorTime = viewStart + (curWidth * anchorFraction).toLong()
            val maxWidth = duration.coerceAtLeast(minWidth)
            val newWidth = (curWidth * factor).toLong().coerceIn(minWidth, maxWidth)
            var newStart = anchorTime - (newWidth * anchorFraction).toLong()
            var newEnd = newStart + newWidth
            if (newStart < 0) {
                newStart = 0
                newEnd = newWidth
            }
            if (newEnd > duration) {
                newEnd = duration
                newStart = (duration - newWidth).coerceAtLeast(0)
            }
            return newStart to newEnd
        }

        fun panWindow(
            viewStart: Long,
            viewEnd: Long,
            deltaMicros: Long,
            duration: Long,
        ): Pair<Long, Long> {
            val w = viewEnd - viewStart
            val newStart = (viewStart + deltaMicros).coerceIn(0, (duration - w).coerceAtLeast(0))
            return newStart to (newStart + w)
        }

        fun computeTicks(
            viewStart: Long,
            viewEnd: Long,
            width: Int,
            targetSpacingPx: Int,
        ): List<Tick> {
            if (width <= 0 || viewEnd <= viewStart || targetSpacingPx <= 0) return emptyList()
            val windowMicros = viewEnd - viewStart
            val approxTicks = (width / targetSpacingPx).coerceAtLeast(1)
            val rawInterval = windowMicros / approxTicks
            val interval = NICE_INTERVALS.firstOrNull { it >= rawInterval } ?: NICE_INTERVALS.last()
            val ticks = mutableListOf<Tick>()
            var t = ((viewStart + interval - 1) / interval) * interval
            while (t <= viewEnd) {
                ticks.add(Tick(xAtTime(t, width, viewStart, viewEnd), formatTick(t, interval)))
                t += interval
            }
            return ticks
        }

        private fun formatTick(
            micros: Long,
            interval: Long,
        ): String {
            val totalSec = micros / 1_000_000
            return if (interval < 1_000_000) {
                "${AudioPlayerService.formatTime(totalSec)}.${(micros % 1_000_000) / 100_000}"
            } else {
                AudioPlayerService.formatTime(totalSec)
            }
        }
    }
}
