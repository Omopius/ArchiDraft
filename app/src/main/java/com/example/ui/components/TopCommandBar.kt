package com.example.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.outlined.GridOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.UnitSystem
import com.example.viewmodel.DraftingUiState

@Composable
fun TopCommandBar(
  state: DraftingUiState,
  isWideScreen: Boolean,
  onOpenPlansSheet: () -> Unit,
  onUndo: () -> Unit,
  onRedo: () -> Unit,
  onToggleUnit: () -> Unit,
  onToggleGridSnap: () -> Unit,
  onToggleAngleSnap: () -> Unit,
  onAutoExteriorDims: () -> Unit,
  onFitToPlan: () -> Unit,
  onResetView: () -> Unit,
  onExportPng: () -> Unit,
  onExportSvg: () -> Unit,
  onExportDxf: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var showOverflow by remember { mutableStateOf(false) }

  Surface(
    modifier = modifier
      .fillMaxWidth()
      .statusBarsPadding()
      .padding(horizontal = 12.dp, vertical = 8.dp),
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
    tonalElevation = 2.dp,
    shadowElevation = 4.dp,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      // Plan Selector / Menu Pill
      Surface(
        onClick = onOpenPlansSheet,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
          .heightIn(min = 48.dp)
          .weight(1f, fill = false)
          .testTag("plans_menu_button"),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = Icons.Default.FolderOpen,
            contentDescription = "Open plans and export menu",
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(18.dp),
          )
          Spacer(Modifier.width(8.dp))
          Text(
            text = state.planName,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      Spacer(Modifier.width(6.dp))

      Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        IconButton(
          onClick = onUndo,
          enabled = state.canUndo,
          modifier = Modifier.testTag("undo_button"),
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.Undo,
            contentDescription = "Undo",
          )
        }
        IconButton(
          onClick = onRedo,
          enabled = state.canRedo,
          modifier = Modifier.testTag("redo_button"),
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.Redo,
            contentDescription = "Redo",
          )
        }

        // Unit Switcher Pill (defaults to Metric mm/m)
        FilterChip(
          selected = true,
          onClick = onToggleUnit,
          label = {
            Text(
              text = if (state.unitSystem == UnitSystem.METRIC) "mm/m" else "ft-in",
              style = MaterialTheme.typography.labelSmall,
            )
          },
          colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = if (state.unitSystem == UnitSystem.METRIC) {
              MaterialTheme.colorScheme.primaryContainer
            } else {
              MaterialTheme.colorScheme.tertiaryContainer
            },
            selectedLabelColor = if (state.unitSystem == UnitSystem.METRIC) {
              MaterialTheme.colorScheme.onPrimaryContainer
            } else {
              MaterialTheme.colorScheme.onTertiaryContainer
            },
          ),
          modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("unit_switcher_button"),
        )

        // Automatic Exterior Dimensions (In-to-In) quick action button
        FilterChip(
          selected = false,
          onClick = onAutoExteriorDims,
          leadingIcon = {
            Icon(
              imageVector = Icons.Default.Straighten,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
          },
          label = {
            Text(
              text = if (isWideScreen) "Auto Exterior Dims" else "Auto Dims",
              style = MaterialTheme.typography.labelSmall,
            )
          },
          modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("top_auto_exterior_dims_button"),
        )

        if (isWideScreen) {
          FilterChip(
            selected = state.gridSnap,
            onClick = onToggleGridSnap,
            leadingIcon = {
              Icon(
                imageVector = if (state.gridSnap) Icons.Default.GridOn else Icons.Outlined.GridOff,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
              )
            },
            label = { Text("Grid", style = MaterialTheme.typography.labelMedium) },
            modifier = Modifier
              .heightIn(min = 48.dp)
              .testTag("grid_snap_button"),
          )
          FilterChip(
            selected = state.angleSnap,
            onClick = onToggleAngleSnap,
            leadingIcon = {
              Icon(
                imageVector = Icons.Default.Straighten,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
              )
            },
            label = { Text("45°/90°", style = MaterialTheme.typography.labelMedium) },
            modifier = Modifier
              .heightIn(min = 48.dp)
              .testTag("angle_snap_button"),
          )
          IconButton(
            onClick = onFitToPlan,
            modifier = Modifier.testTag("fit_to_plan_button"),
          ) {
            Icon(Icons.Default.FitScreen, contentDescription = "Fit to plan")
          }
          IconButton(
            onClick = onResetView,
            modifier = Modifier.testTag("reset_view_button"),
          ) {
            Icon(Icons.Default.RestartAlt, contentDescription = "Reset view")
          }
        }

        Box {
          IconButton(
            onClick = { showOverflow = true },
            modifier = Modifier.testTag("top_more_button"),
          ) {
            Icon(Icons.Default.MoreVert, contentDescription = "More drafting options")
          }
          DropdownMenu(
            expanded = showOverflow,
            onDismissRequest = { showOverflow = false },
          ) {
            if (!isWideScreen) {
              DropdownMenuItem(
                text = { Text(if (state.gridSnap) "Grid Snap: On" else "Grid Snap: Off") },
                leadingIcon = {
                  Icon(
                    if (state.gridSnap) Icons.Default.GridOn else Icons.Outlined.GridOff,
                    contentDescription = null,
                  )
                },
                onClick = {
                  onToggleGridSnap()
                  showOverflow = false
                },
              )
              DropdownMenuItem(
                text = { Text(if (state.angleSnap) "Angle Snap (45°/90°): On" else "Angle Snap: Off") },
                leadingIcon = { Icon(Icons.Default.Straighten, contentDescription = null) },
                onClick = {
                  onToggleAngleSnap()
                  showOverflow = false
                },
              )
              DropdownMenuItem(
                text = { Text("Fit to Plan") },
                leadingIcon = { Icon(Icons.Default.FitScreen, contentDescription = null) },
                onClick = {
                  onFitToPlan()
                  showOverflow = false
                },
              )
              DropdownMenuItem(
                text = { Text("Reset View") },
                leadingIcon = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
                onClick = {
                  onResetView()
                  showOverflow = false
                },
              )
            }
            DropdownMenuItem(
              text = { Text("Auto Exterior Dimensions") },
              leadingIcon = { Icon(Icons.Default.Straighten, contentDescription = null) },
              onClick = {
                showOverflow = false
                onAutoExteriorDims()
              },
            )
            DropdownMenuItem(
              text = { Text("Export PNG Image") },
              onClick = {
                showOverflow = false
                onExportPng()
              },
            )
            DropdownMenuItem(
              text = { Text("Export SVG Vector") },
              onClick = {
                showOverflow = false
                onExportSvg()
              },
            )
            DropdownMenuItem(
              text = { Text("Export DXF (CAD)") },
              onClick = {
                showOverflow = false
                onExportDxf()
              },
            )
          }
        }
      }
    }
  }
}
