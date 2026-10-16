package com.binh.chordoffline.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.binh.chordoffline.R

/** Font Be Vietnam Pro (SIL OFL 1.1, assets/licenses/OFL-BeVietnamPro.txt) — hỗ trợ đầy đủ tiếng Việt. */
val BeVietnamPro = FontFamily(
    Font(R.font.be_vietnam_pro_regular, FontWeight.Normal),
    Font(R.font.be_vietnam_pro_medium, FontWeight.Medium),
    Font(R.font.be_vietnam_pro_semi_bold, FontWeight.SemiBold),
    Font(R.font.be_vietnam_pro_bold, FontWeight.Bold),
    Font(R.font.be_vietnam_pro_extra_bold, FontWeight.ExtraBold),
)

/** Mẫu B "Sân khấu đêm": nền tối, điểm nhấn hổ phách — dễ nhìn khi đàn buổi tối. */
object Stage {
    val Ink = Color(0xFF0E0F12)
    val Surface = Color(0xFF15171C)
    val Raised = Color(0xFF1E2128)
    val Line = Color(0xFF3A3E48)
    val Amber = Color(0xFFF2A33A)
    val Text = Color(0xFFEDEAE3)
    val Muted = Color(0xFF9AA0A8)
}

private val StageColors = darkColorScheme(
    primary = Stage.Amber, onPrimary = Stage.Surface,
    primaryContainer = Color(0xFF3A2A12), onPrimaryContainer = Color(0xFFFFD9A8),
    secondary = Stage.Muted, onSecondary = Stage.Surface,
    secondaryContainer = Color(0xFF3A2A12), onSecondaryContainer = Color(0xFFFFD9A8),
    tertiary = Color(0xFF4FD1C5), onTertiary = Stage.Surface,
    background = Stage.Ink, onBackground = Stage.Text,
    surface = Stage.Surface, onSurface = Stage.Text,
    surfaceVariant = Stage.Raised, onSurfaceVariant = Stage.Muted,
    surfaceTint = Stage.Amber,
    outline = Stage.Line, outlineVariant = Color(0xFF2A2D35),
    error = Color(0xFFFF6B6B), onError = Stage.Surface,
    errorContainer = Color(0xFF4A1F1F), onErrorContainer = Color(0xFFFFDAD6),
    surfaceContainerLowest = Stage.Ink, surfaceContainerLow = Color(0xFF1A1C21),
    surfaceContainer = Stage.Raised, surfaceContainerHigh = Color(0xFF23262E), surfaceContainerHighest = Color(0xFF2A2D35),
    inverseSurface = Stage.Text, inverseOnSurface = Stage.Surface, inversePrimary = Color(0xFF8A5A12),
)

private fun TextStyle.vn(weight: FontWeight? = null) = copy(fontFamily = BeVietnamPro, fontWeight = weight ?: fontWeight)

private val StageType = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.vn(FontWeight.ExtraBold), displayMedium = t.displayMedium.vn(FontWeight.ExtraBold),
        displaySmall = t.displaySmall.vn(FontWeight.ExtraBold),
        headlineLarge = t.headlineLarge.vn(FontWeight.ExtraBold), headlineMedium = t.headlineMedium.vn(FontWeight.Bold),
        headlineSmall = t.headlineSmall.vn(FontWeight.Bold),
        titleLarge = t.titleLarge.vn(FontWeight.ExtraBold), titleMedium = t.titleMedium.vn(FontWeight.Bold),
        titleSmall = t.titleSmall.vn(FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.vn(), bodyMedium = t.bodyMedium.vn(), bodySmall = t.bodySmall.vn(),
        labelLarge = t.labelLarge.vn(FontWeight.SemiBold), labelMedium = t.labelMedium.vn(FontWeight.Medium),
        labelSmall = t.labelSmall.vn(),
    )
}

@Composable
fun ChordTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = StageColors, typography = StageType, content = content)
}
