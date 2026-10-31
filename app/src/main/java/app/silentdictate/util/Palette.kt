package app.silentdictate.util

import android.graphics.Color

/**
 * Fixed palette. The app is deliberately near-monochrome:
 * graphite surfaces, off-white text, and one cautious red for errors.
 * Mirrors res/values/colors.xml.
 */
object Palette {
    val bg = 0xFF101113.toInt()
    val surface = 0xFF17191C.toInt()
    val keyBg = 0xFF1F2227.toInt()
    val keyBgSpecial = 0xFF16181B.toInt()
    val keyPressed = 0xFF3A3E45.toInt()
    val hairline = 0xFF26282B.toInt()
    val textPrimary = 0xFFECEDEF.toInt()
    val textSecondary = 0xFF8A8F98.toInt()
    val textFaint = 0xFF565B63.toInt()
    val danger = 0xFFE5726E.toInt()

    /** Linear blend between two ARGB colors. [t] 0 → [a], 1 → [b]. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * k).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt(),
        )
    }

    fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
}
