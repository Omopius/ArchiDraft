package com.example.model

import kotlin.math.abs
import kotlin.math.roundToInt

enum class UnitSystem {
  METRIC,
  IMPERIAL,
}

object UnitFormatter {
  private const val MM_PER_INCH = 25.4f
  private const val MM_PER_FOOT = 304.8f

  fun formatLength(mm: Float, unitSystem: UnitSystem, compact: Boolean = false): String {
    val safeMm = abs(mm)
    return when (unitSystem) {
      UnitSystem.METRIC -> {
        if (safeMm < 1000f) {
          "${safeMm.roundToInt()} mm"
        } else {
          val meters = safeMm / 1000f
          val str = String.format(java.util.Locale.US, "%.2f", meters)
          if (compact) "${str}m" else "$str m"
        }
      }
      UnitSystem.IMPERIAL -> {
        val totalInches = safeMm / MM_PER_INCH
        val feet = (totalInches / 12f).toInt()
        val remInches = totalInches - feet * 12f
        val wholeInches = remInches.toInt()
        val eighths = ((remInches - wholeInches) * 8f).roundToInt()
        val (adjInches, fracStr) = when {
          eighths >= 8 -> (wholeInches + 1) to ""
          eighths <= 0 -> wholeInches to ""
          eighths == 4 -> wholeInches to " 1/2"
          eighths == 2 -> wholeInches to " 1/4"
          eighths == 6 -> wholeInches to " 3/4"
          else -> wholeInches to " $eighths/8"
        }
        val finalFeet = feet + adjInches / 12
        val finalInches = adjInches % 12
        if (finalFeet == 0) {
          "$finalInches$fracStr\""
        } else if (compact) {
          "$finalFeet'$finalInches$fracStr\""
        } else {
          "$finalFeet' - $finalInches$fracStr\""
        }
      }
    }
  }

  fun formatArea(areaSqMm: Float, unitSystem: UnitSystem): String {
    val safe = abs(areaSqMm)
    return when (unitSystem) {
      UnitSystem.METRIC -> {
        val sqM = safe / 1_000_000f
        String.format(java.util.Locale.US, "%.1f m²", sqM)
      }
      UnitSystem.IMPERIAL -> {
        val sqFt = safe / 92_903.04f
        if (sqFt >= 100f) {
          String.format(java.util.Locale.US, "%.0f sq ft", sqFt)
        } else {
          String.format(java.util.Locale.US, "%.1f sq ft", sqFt)
        }
      }
    }
  }

  fun formatShortDimension(mm: Float, unitSystem: UnitSystem): String {
    val safeMm = abs(mm)
    return when (unitSystem) {
      UnitSystem.METRIC -> "${safeMm.roundToInt()} mm"
      UnitSystem.IMPERIAL -> {
        val inches = (safeMm / MM_PER_INCH).roundToInt()
        if (inches < 24) {
          "$inches\""
        } else {
          val ft = inches / 12
          val rem = inches % 12
          if (rem == 0) "$ft'" else "$ft' $rem\""
        }
      }
    }
  }

  fun formatEditableValue(mm: Float, unitSystem: UnitSystem): String {
    return when (unitSystem) {
      UnitSystem.METRIC -> mm.roundToInt().toString()
      UnitSystem.IMPERIAL -> {
        val totalInches = mm / MM_PER_INCH
        val feet = (totalInches / 12f).toInt()
        val remInches = totalInches - feet * 12f
        val roundedInches = (remInches * 2f).roundToInt() / 2f
        val adjFeet = feet + (roundedInches / 12f).toInt()
        val finalIn = roundedInches % 12f
        val inText = if (abs(finalIn - finalIn.roundToInt()) < 0.05f) {
          finalIn.roundToInt().toString()
        } else {
          String.format(java.util.Locale.US, "%.1f", finalIn)
        }
        if (adjFeet > 0) "$adjFeet'$inText\"" else "$inText\""
      }
    }
  }

  /**
   * Parses architectural dimension strings such as:
   * - "1200", "1200mm", "120cm", "1.2m"
   * - "4'6\"", "4' 6\"", "4'-6.5\"", "4'", "36\"", "36in", "4.5ft"
   */
  fun parseDimensionInput(raw: String, defaultSystem: UnitSystem): Float? {
    val text = raw.trim().lowercase(java.util.Locale.US)
    if (text.isEmpty()) return null

    // Metric explicit suffixes
    if (text.endsWith("mm")) {
      return text.removeSuffix("mm").trim().toFloatOrNull()?.takeIf { it >= 0f }
    }
    if (text.endsWith("cm")) {
      return text.removeSuffix("cm").trim().toFloatOrNull()?.let { it * 10f }?.takeIf { it >= 0f }
    }
    if (text.endsWith("m") && !text.endsWith("mm") && !text.endsWith("cm")) {
      return text.removeSuffix("m").trim().toFloatOrNull()?.let { it * 1000f }?.takeIf { it >= 0f }
    }

    // Imperial feet + inches: e.g. 4'6", 4' 6.5", 4'-6", 4'
    val feetInchRegex = Regex("""^(\d+(?:\.\d+)?)\s*(?:'|ft)\s*(?:-|\s)?\s*(\d+(?:\.\d+)?)?\s*(?:"|in)?$""")
    feetInchRegex.matchEntire(text)?.let { match ->
      val ft = match.groupValues[1].toFloatOrNull() ?: return null
      val inc = match.groupValues[2].takeIf { it.isNotEmpty() }?.toFloatOrNull() ?: 0f
      return (ft * MM_PER_FOOT + inc * MM_PER_INCH).takeIf { it >= 0f }
    }

    // Inches only: e.g. 36", 36in
    val inchRegex = Regex("""^(\d+(?:\.\d+)?)\s*(?:"|in)$""")
    inchRegex.matchEntire(text)?.let { match ->
      val inc = match.groupValues[1].toFloatOrNull() ?: return null
      return (inc * MM_PER_INCH).takeIf { it >= 0f }
    }

    // Bare number -> interpreted using active unit system (mm in Metric, inches in Imperial)
    val bare = text.toFloatOrNull() ?: return null
    if (bare < 0f) return null
    return when (defaultSystem) {
      UnitSystem.METRIC -> bare
      UnitSystem.IMPERIAL -> bare * MM_PER_INCH
    }
  }
}
