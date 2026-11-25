package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ArchLightColorScheme = lightColorScheme(
  primary = BlueprintCobalt,
  onPrimary = Color.White,
  primaryContainer = BlueprintCobaltContainer,
  onPrimaryContainer = BlueprintCobaltOnContainer,
  secondary = BlueprintNavy,
  onSecondary = Color.White,
  secondaryContainer = Color(0xFFE2E8F0),
  onSecondaryContainer = InkCharcoal,
  tertiary = SnapAmber,
  onTertiary = Color.White,
  tertiaryContainer = SnapAmberContainer,
  onTertiaryContainer = Color(0xFF78350F),
  background = DraftPaper,
  onBackground = InkCharcoal,
  surface = DraftPaperElevated,
  onSurface = InkCharcoal,
  surfaceVariant = Color(0xFFF1F5F9),
  onSurfaceVariant = InkSlate,
  outline = OutlineHairline,
  outlineVariant = DraftGridMajor,
  error = DangerCrimson,
  errorContainer = DangerContainer,
  onError = Color.White,
  onErrorContainer = Color(0xFF7F1D1D),
)

@Composable
fun ArchDraftTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = ArchLightColorScheme,
    typography = AppTypography,
    content = content,
  )
}
