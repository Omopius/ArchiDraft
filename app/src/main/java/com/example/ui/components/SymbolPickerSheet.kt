package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.FixtureCategory
import com.example.model.SymbolType
import com.example.model.UnitFormatter
import com.example.model.UnitSystem
import com.example.ui.canvas.ArchitecturalCanvasRenderer
import com.example.ui.theme.InkCharcoal
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SymbolPickerSheet(
  activeSymbol: SymbolType,
  unitSystem: UnitSystem,
  onSelectSymbol: (SymbolType) -> Unit,
  onDismiss: () -> Unit,
) {
  var filterCategory by remember { mutableStateOf<FixtureCategory?>(null) }
  val density = LocalDensity.current.density

  val items = remember(filterCategory) {
    if (filterCategory == null) SymbolType.entries
    else SymbolType.entries.filter { it.category == filterCategory }
  }

  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
        .padding(bottom = 24.dp),
    ) {
      Text(
        text = "Fixtures & Furniture",
        style = MaterialTheme.typography.titleLarge,
      )
      Spacer(Modifier.height(4.dp))
      Text(
        text = "Pick a 2D symbol, then tap anywhere on the canvas to place it.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(12.dp))

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
          selected = filterCategory == null,
          onClick = { filterCategory = null },
          label = { Text("All") },
          modifier = Modifier.heightIn(min = 48.dp),
        )
        FixtureCategory.entries.forEach { cat ->
          FilterChip(
            selected = filterCategory == cat,
            onClick = { filterCategory = cat },
            label = { Text(cat.label) },
            modifier = Modifier.heightIn(min = 48.dp),
          )
        }
      }

      Spacer(Modifier.height(12.dp))

      LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 136.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.heightIn(max = 420.dp),
      ) {
        items(items, key = { it.name }) { sym ->
          val selected = sym == activeSymbol
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp,
            modifier = Modifier
              .clickable {
                onSelectSymbol(sym)
                onDismiss()
              }
              .testTag("symbol_card_${sym.name.lowercase()}"),
          ) {
            Column(
              modifier = Modifier.padding(12.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              Canvas(modifier = Modifier.size(60.dp)) {
                val scale = min(size.width * 0.78f / sym.defaultWidthMm, size.height * 0.78f / sym.defaultDepthMm)
                val w = sym.defaultWidthMm * scale
                val d = sym.defaultDepthMm * scale
                withTransform({
                  translate(size.width * 0.5f, size.height * 0.5f)
                }) {
                  with(ArchitecturalCanvasRenderer) {
                    drawSymbolGeometry(
                      type = sym,
                      w = w,
                      d = d,
                      strokeColor = InkCharcoal,
                      fillColor = Color.White,
                      strokeWidth = 1.5f * density,
                    )
                  }
                }
              }
              Spacer(Modifier.height(8.dp))
              Text(
                text = sym.displayName,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                text = "${UnitFormatter.formatShortDimension(sym.defaultWidthMm, unitSystem)} × ${UnitFormatter.formatShortDimension(sym.defaultDepthMm, unitSystem)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }
}
