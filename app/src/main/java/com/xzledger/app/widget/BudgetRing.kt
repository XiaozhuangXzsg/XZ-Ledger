package com.xzledger.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * 桌面小组件的预算环形条。
 *
 * RemoteViews 不能直接画圆弧，只能把画好的位图塞进 ImageView，所以这里用 Canvas 画：
 *  - 底色圈（半透明米白）：整圈
 *  - 进度弧（金 / 超支时暖红）：从 12 点方向顺时针，弧长 = 已用比例
 *  - 圆心文字：已用百分比，超支时显示实际超出的百分比（如 128%）
 */
object BudgetRing {

    private const val TRACK = 0x33F5EFE6.toInt()
    private const val GOLD = 0xFFC9A227.toInt()
    private const val OVER = 0xFFE2604A.toInt()
    private const val CREAM = 0xFFF5EFE6.toInt()

    /**
     * @param sizePx 位图边长（像素），与布局里 ImageView 的 dp 尺寸保持一致
     * @param usedPct 已用比例（0..100 之外会被裁剪 / 超支时用于圆心文字）
     * @param over 是否超出预算
     */
    fun render(sizePx: Int, usedPct: Int, over: Boolean): Bitmap {
        val size = sizePx.coerceAtLeast(32)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val stroke = size * 0.12f
        val inset = stroke / 2f + size * 0.03f
        val box = RectF(inset, inset, size - inset, size - inset)

        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = TRACK
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(box, 0f, 360f, false, track)

        val sweepPct = if (over) 100 else usedPct.coerceIn(0, 100)
        if (sweepPct > 0) {
            val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                color = if (over) OVER else GOLD
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawArc(box, -90f, 360f * sweepPct / 100f, false, arc)
        }

        val label = (if (over) usedPct.coerceAtLeast(100) else sweepPct).toString() + "%"
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (over) OVER else CREAM
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = size * 0.30f
        }
        val fm = text.fontMetrics
        canvas.drawText(label, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, text)
        return bmp
    }
}
