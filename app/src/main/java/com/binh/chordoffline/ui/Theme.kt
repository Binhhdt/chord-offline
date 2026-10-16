package com.binh.chordoffline.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
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

/** Mẫu D "Tối giản xanh": nền sáng, chữ đậm, một điểm nhấn xanh chàm. */
object Minimal {
    val Indigo = Color(0xFF2B3CC2)
    val IndigoSoft = Color(0xFFDDE1FA)
    val Ink = Color(0xFF151826)
    val Muted = Color(0xFF5E6478)
    val Page = Color(0xFFF4F5FA)
    val Card = Color(0xFFFFFFFF)
    val Line = Color(0xFFD5D9E6)
}

private val MinimalColors = lightColorScheme(
    primary = Minimal.Indigo, onPrimary = Color.White,
    primaryContainer = Minimal.IndigoSoft, onPrimaryContainer = Color(0xFF1C2A99),
    secondary = Minimal.Muted, onSecondary = Color.White,
    secondaryContainer = Minimal.IndigoSoft, onSecondaryContainer = Color(0xFF1C2A99),
    tertiary = Color(0xFF00796B), onTertiary = Color.White,
    background = Minimal.Page, onBackground = Minimal.Ink,
    surface = Minimal.Card, onSurface = Minimal.Ink,
    surfaceVariant = Color(0xFFEEF0F7), onSurfaceVariant = Minimal.Muted,
    surfaceTint = Minimal.Indigo,
    outline = Color(0xFFB7BDCF), outlineVariant = Minimal.Line,
    error = Color(0xFFC62828), onError = Color.White,
    errorContainer = Color(0xFFFDE7E7), onErrorContainer = Color(0xFF7F1D1D),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFAFBFD),
    surfaceContainer = Color(0xFFF1F3F9), surfaceContainerHigh = Color(0xFFEBEEF6), surfaceContainerHighest = Color(0xFFE4E8F2),
    inverseSurface = Minimal.Ink, inverseOnSurface = Color.White, inversePrimary = Color(0xFFB9C1FF),
)

private fun TextStyle.vn(weight: FontWeight? = null) = copy(fontFamily = BeVietnamPro, fontWeight = weight ?: fontWeight)

private val MinimalType = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.vn(FontWeight.ExtraBold), displayMedium = t.displayMedium.vn(FontWeight.ExtraBold),
        displaySmall = t.displaySmall.vn(FontWeight.ExtraBold),
        headlineLarge = t.headlineLarge.vn(FontWeight.ExtraBold), headlineMedium = t.headlineMedium.vn(FontWeight.ExtraBold),
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
    MaterialTheme(colorScheme = MinimalColors, typography = MinimalType, content = content)
}
