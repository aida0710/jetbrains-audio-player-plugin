package com.github.audioplayer

import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import javax.swing.JComponent

/** ピーク(枠線)とRMS(塗り)を 0..1 で表示する軽量レベルメーター。 */
class LevelMeterBar : JComponent() {
    var rms: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            repaint()
        }

    var peak: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            repaint()
        }

    override fun getPreferredSize(): Dimension = Dimension(120, 12)

    override fun getMinimumSize(): Dimension = Dimension(40, 8)

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val w = width
        val h = height
        g.color = JBColor(Color(0x33, 0x33, 0x33), Color(0x22, 0x22, 0x22))
        g.fillRect(0, 0, w, h)
        val rmsW = (rms * w).toInt()
        g.color = if (rms > 0.9f) JBColor.RED else JBColor(Color(0x4C, 0xAF, 0x50), Color(0x4C, 0xAF, 0x50))
        g.fillRect(0, 0, rmsW, h)
        val peakX = (peak * w).toInt().coerceIn(0, w - 1)
        g.color = JBColor(Color(0xFF, 0xC1, 0x07), Color(0xFF, 0xC1, 0x07))
        g.fillRect(peakX, 0, 2, h)
    }
}
