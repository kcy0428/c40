package kr.c40.floor

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.TextView

/** 화면 공통 색과 작은 도우미. 태블릿(1280×720)에서 멀리서도 읽히도록 글자를 크게 쓴다. */
object Ui {
    val INK = Color.rgb(0x15, 0x18, 0x1B)
    val MUTED = Color.rgb(0x52, 0x5A, 0x61)
    val LINE = Color.rgb(0xD9, 0xDD, 0xD8)
    val BG = Color.rgb(0xF3, 0xF4, 0xF1)
    val SURFACE = Color.WHITE
    val SOFT = Color.rgb(0xEE, 0xF1, 0xEC)
    val ACCENT = Color.rgb(0x0F, 0x62, 0xA8)
    val OK = Color.rgb(0x1E, 0x7B, 0x4A)
    val WARN = Color.rgb(0xB4, 0x5A, 0x09)
    val WARN_BG = Color.rgb(0xFD, 0xF1, 0xE3)
    val BAD = Color.rgb(0xB0, 0x2A, 0x2A)

    fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun Context.box(fill: Int, radiusDp: Int, strokeDp: Int = 0, stroke: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
        }

    fun Context.text(s: String, sp: Float, color: Int = INK, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun Context.bigButton(label: String, fill: Int = ACCENT, fg: Int = Color.WHITE) = Button(this).apply {
        text = label
        textSize = 21f
        typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false
        setTextColor(fg)
        background = box(fill, 14)
        minHeight = dp(64)
        gravity = Gravity.CENTER
        stateListAnimator = null
    }

    fun Button.setEnabledLook(enabled: Boolean, fill: Int = ACCENT) {
        isEnabled = enabled
        background = context.box(if (enabled) fill else SOFT, 14)
        setTextColor(if (enabled) Color.WHITE else MUTED)
    }
}
