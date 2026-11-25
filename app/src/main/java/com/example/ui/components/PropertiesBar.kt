package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.geometry.GeometryMath
import com.example.model.DoorType
import com.example.model.DraftingPresets
import com.example.model.DraftingTool
import com.example.model.SelectionTarget
import com.example.model.UnitFormatter
import com.example.viewmodel.DraftingUiState

enum class CustomInputKind {
  WALL_THICKNESS_DEFAULT,
  WALL_THICKNESS_SELECTED,
  DOOR_WIDTH_DEFAULT,
  DOOR_WIDTH_SELECTED,
  WINDOW_WIDTH_DEFAULT,
  WINDOW_WIDTH_SELECTED,
  WINDOW_SILL_SELECTED,
}

@Composable
fun PropertiesBar(
  state: DraftingUiState,
  onSetDefaultWallThickness: (Float) -> Unit,
  onSetSelectedWallThickness: (Float) -> Unit,
  onSetDefaultDoorWidth: (Float) -> Unit,
  onSetSelectedDoorWidth: (Float) -> Unit,
  onSetDefaultDoorType: (DoorType) -> Unit,
  onSetSelectedDoorType: (DoorType) -> Unit,
  onFlipDoorSwing: () -> Unit,
  onFlipDoorHinge: () -> Unit,
  onSetDefaultWindowWidth: (Float) -> Unit,
  onSetSelectedWindowWidth: (Float) -> Unit,
  onRotateSymbol: (Float) -> Unit,
  onOpenSymbolPicker: () -> Unit,
  onAutoExteriorDims: () -> Unit,
  onSetDefaultTextSize: (Float) -> Unit,
  onSetDefaultTextOpaqueBg: (Boolean) -> Unit,
  onSelectRoomLabelPreset: (String?) -> Unit,
  onEditSelectedText: (String, Float, Boolean) -> Unit,
  onSetSelectedTextSize: (Float) -> Unit,
  onToggleSelectedTextOpaqueBg: () -> Unit,
  onRotateSelectedText: (Float) -> Unit,
  onOpenCustomInput: (CustomInputKind, Float) -> Unit,
  onDuplicateSelected: () -> Unit,
  onDeleteSelected: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val showBar = state.selection != null ||
    state.activeTool == DraftingTool.WALL ||
    state.activeTool == DraftingTool.DOOR ||
    state.activeTool == DraftingTool.WINDOW ||
    state.activeTool == DraftingTool.FIXTURE ||
    state.activeTool == DraftingTool.DIMENSION ||
    state.activeTool == DraftingTool.ROOM_LABEL ||
    state.activeTool == DraftingTool.TEXT

  AnimatedVisibility(
    visible = showBar,
    enter = fadeIn() + slideInVertically { it / 2 },
    exit = fadeOut() + slideOutVertically { it / 2 },
    modifier = modifier,
  ) {
    Surface(
      modifier = Modifier
        .widthIn(max = 760.dp)
        .padding(horizontal = 12.dp),
      shape = RoundedCornerShape(18.dp),
      color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
      tonalElevation = 3.dp,
      shadowElevation = 5.dp,
    ) {
      Row(
        modifier = Modifier
          .horizontalScroll(rememberScrollState())
          .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        when (val sel = state.selection) {
          is SelectionTarget.SelectedWall -> {
            val wall = state.plan.walls.find { it.id == sel.id }
            if (wall != null) {
              Text(
                text = "Wall ${UnitFormatter.formatLength(GeometryMath.wallLength(wall), state.unitSystem)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
              )
              Spacer(Modifier.width(4.dp))
              DraftingPresets.wallThicknessMm.forEach { preset ->
                FilterChip(
                  selected = kotlin.math.abs(wall.thicknessMm - preset) < 2f,
                  onClick = { onSetSelectedWallThickness(preset) },
                  label = {
                    Text(
                      UnitFormatter.formatShortDimension(preset, state.unitSystem),
                      style = MaterialTheme.typography.labelSmall,
                    )
                  },
                  modifier = Modifier.heightIn(min = 48.dp),
                )
              }
              FilterChip(
                selected = false,
                onClick = { onOpenCustomInput(CustomInputKind.WALL_THICKNESS_SELECTED, wall.thicknessMm) },
                leadingIcon = { Icon(Icons.Default.Tune, null, Modifier.size(16.dp)) },
                label = { Text("Custom") },
                modifier = Modifier.heightIn(min = 48.dp),
              )
              ActionDuplicateDelete(onDuplicateSelected, onDeleteSelected)
            }
          }

          is SelectionTarget.SelectedDoor -> {
            val door = state.plan.doors.find { it.id == sel.id }
            if (door != null) {
              Text(
                text = "Door",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
              )
              DoorType.entries.forEach { type ->
                FilterChip(
                  selected = door.doorType == type,
                  onClick = { onSetSelectedDoorType(type) },
                  label = { Text(type.label) },
                  modifier = Modifier.heightIn(min = 48.dp),
                )
              }
              if (door.doorType != DoorType.SLIDING) {
                FilterChip(
                  selected = false,
                  onClick = onFlipDoorSwing,
                  leadingIcon = { Icon(Icons.Default.Flip, null, Modifier.size(16.dp)) },
                  label = { Text("Swing") },
                  modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("flip_door_swing_button"),
                )
              }
              if (door.doorType == DoorType.SINGLE_SWING) {
                FilterChip(
                  selected = false,
                  onClick = onFlipDoorHinge,
                  leadingIcon = { Icon(Icons.Default.SwapHoriz, null, Modifier.size(16.dp)) },
                  label = { Text("Hinge") },
                  modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("flip_door_hinge_button"),
                )
              }
              DraftingPresets.doorWidthsMm.forEach { preset ->
                FilterChip(
                  selected = kotlin.math.abs(door.widthMm - preset) < 5f,
                  onClick = { onSetSelectedDoorWidth(preset) },
                  label = {
                    Text(
                      UnitFormatter.formatShortDimension(preset, state.unitSystem),
                      style = MaterialTheme.typography.labelSmall,
                    )
                  },
                  modifier = Modifier.heightIn(min = 48.dp),
                )
              }
              FilterChip(
                selected = false,
                onClick = { onOpenCustomInput(CustomInputKind.DOOR_WIDTH_SELECTED, door.widthMm) },
                leadingIcon = { Icon(Icons.Default.Tune, null, Modifier.size(16.dp)) },
                label = { Text("Width") },
                modifier = Modifier.heightIn(min = 48.dp),
              )
              ActionDuplicateDelete(onDuplicateSelected, onDeleteSelected)
            }
          }

          is SelectionTarget.SelectedWindow -> {
            val win = state.plan.windows.find { it.id == sel.id }
            if (win != null) {
              Text(
                text = "Window",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
              )
              DraftingPresets.windowWidthsMm.forEach { preset ->
                FilterChip(
                  selected = kotlin.math.abs(win.widthMm - preset) < 5f,
                  onClick = { onSetSelectedWindowWidth(preset) },
                  label = {
                    Text(
                      UnitFormatter.formatShortDimension(preset, state.unitSystem),
                      style = MaterialTheme.typography.labelSmall,
                    )
                  },
                  modifier = Modifier.heightIn(min = 48.dp),
                )
              }
              FilterChip(
                selected = false,
                onClick = { onOpenCustomInput(CustomInputKind.WINDOW_WIDTH_SELECTED, win.widthMm) },
                leadingIcon = { Icon(Icons.Default.Tune, null, Modifier.size(16.dp)) },
                label = { Text("Width") },
                modifier = Modifier.heightIn(min = 48.dp),
              )
              FilterChip(
                selected = false,
                onClick = { onOpenCustomInput(CustomInputKind.WINDOW_SILL_SELECTED, win.sillHeightMm) },
                label = {
                  Text(
                    "Sill ${UnitFormatter.formatShortDimension(win.sillHeightMm, state.unitSystem)}",
                    style = MaterialTheme.typography.labelSmall,
                  )
                },
                modifier = Modifier.heightIn(min = 48.dp),
              )
              ActionDuplicateDelete(onDuplicateSelected, onDeleteSelected)
            }
          }

          is SelectionTarget.SelectedSymbol -> {
            val sym = state.plan.symbols.find { it.id == sel.id }
            if (sym != null) {
              Text(
                text = sym.type.displayName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
              )
              FilterChip(
                selected = false,
                onClick = { onRotateSymbol(90f) },
                leadingIcon = { Icon(Icons.Default.RotateRight, null, Modifier.size(16.dp)) },
                label = { Text("90°") },
                modifier = Modifier
                  .heightIn(min = 48.dp)
                  .testTag("rotate_symbol_90_button"),
              )
              FilterChip(
                selected = false,
                onClick = { onRotateSymbol(45f) },
                label = { Text("+45°") },
                modifier = Modifier.heightIn(min = 48.dp),
              )
              ActionDuplicateDelete(onDuplicateSelected, onDeleteSelected)
            }
          }

          is SelectionTarget.SelectedDimension -> {
            val dim = state.plan.dimensions.find { it.id == sel.id }
            if (dim != null) {
              val len = GeometryMath.distance(dim.start, dim.end)
              Text(
                text = "Measure: ${UnitFormatter.formatLength(len, state.unitSystem)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
              )
              ActionDuplicateDelete(onDuplicateSelected, onDeleteSelected)
            }
          }

          is SelectionTarget.SelectedText -> {
            val txt = state.plan.texts.find { it.id == sel.id }
            if (txt != null) {
              Text(
                text = "Text",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
              )
              FilterChip(
                selected = false,
                onClick = { onEditSelectedText(txt.text, txt.fontSizeMm, txt.hasOpaqueBackground) },
                leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(16.dp)) },
                label = { Text("Edit") },
                modifier = Modifier
                  .heightIn(min = 48.dp)
                  .testTag("edit_selected_text_button"),
              )
              DraftingPresets.textSizesMm.forEach { (sizeMm, label) ->
                FilterChip(
                  selected = kotlin.math.abs(txt.fontSizeMm - sizeMm) < 10f,
                  onClick = { onSetSelectedTextSize(sizeMm) },
                  label = { Text(label) },
                  modifier = Modifier.heightIn(min = 48.dp),
                )
              }
              FilterChip(
                selected = txt.hasOpaqueBackground,
                onClick = onToggleSelectedTextOpaqueBg,
                label = { Text(if (txt.hasOpaqueBackground) "Opaque BG" else "Clear BG") },
                modifier = Modifier
                  .heightIn(min = 48.dp)
                  .testTag("toggle_text_opaque_bg_button"),
              )
              FilterChip(
                selected = false,
                onClick = { onRotateSelectedText(90f) },
                leadingIcon = { Icon(Icons.Default.RotateRight, null, Modifier.size(16.dp)) },
                label = { Text("90°") },
                modifier = Modifier.heightIn(min = 48.dp),
              )
              ActionDuplicateDelete(onDuplicateSelected, onDeleteSelected)
            }
          }

          null -> {
            // Tool preset bar before placing
            when (state.activeTool) {
              DraftingTool.WALL -> {
                Text("Wall Thickness:", style = MaterialTheme.typography.labelMedium)
                DraftingPresets.wallThicknessMm.forEach { preset ->
                  FilterChip(
                    selected = kotlin.math.abs(state.defaultWallThicknessMm - preset) < 2f,
                    onClick = { onSetDefaultWallThickness(preset) },
                    label = {
                      Text(
                        UnitFormatter.formatShortDimension(preset, state.unitSystem),
                        style = MaterialTheme.typography.labelSmall,
                      )
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                  )
                }
                FilterChip(
                  selected = false,
                  onClick = { onOpenCustomInput(CustomInputKind.WALL_THICKNESS_DEFAULT, state.defaultWallThicknessMm) },
                  leadingIcon = { Icon(Icons.Default.Tune, null, Modifier.size(16.dp)) },
                  label = { Text("Custom") },
                  modifier = Modifier.heightIn(min = 48.dp),
                )
              }

              DraftingTool.DOOR -> {
                Text("Door:", style = MaterialTheme.typography.labelMedium)
                DoorType.entries.forEach { type ->
                  FilterChip(
                    selected = state.defaultDoorType == type,
                    onClick = { onSetDefaultDoorType(type) },
                    label = { Text(type.label) },
                    modifier = Modifier.heightIn(min = 48.dp),
                  )
                }
                DraftingPresets.doorWidthsMm.forEach { preset ->
                  FilterChip(
                    selected = kotlin.math.abs(state.defaultDoorWidthMm - preset) < 5f,
                    onClick = { onSetDefaultDoorWidth(preset) },
                    label = {
                      Text(
                        UnitFormatter.formatShortDimension(preset, state.unitSystem),
                        style = MaterialTheme.typography.labelSmall,
                      )
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                  )
                }
                FilterChip(
                  selected = false,
                  onClick = { onOpenCustomInput(CustomInputKind.DOOR_WIDTH_DEFAULT, state.defaultDoorWidthMm) },
                  label = { Text("Custom") },
                  modifier = Modifier.heightIn(min = 48.dp),
                )
              }

              DraftingTool.WINDOW -> {
                Text("Window:", style = MaterialTheme.typography.labelMedium)
                DraftingPresets.windowWidthsMm.forEach { preset ->
                  FilterChip(
                    selected = kotlin.math.abs(state.defaultWindowWidthMm - preset) < 5f,
                    onClick = { onSetDefaultWindowWidth(preset) },
                    label = {
                      Text(
                        UnitFormatter.formatShortDimension(preset, state.unitSystem),
                        style = MaterialTheme.typography.labelSmall,
                      )
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                  )
                }
                FilterChip(
                  selected = false,
                  onClick = { onOpenCustomInput(CustomInputKind.WINDOW_WIDTH_DEFAULT, state.defaultWindowWidthMm) },
                  label = { Text("Custom") },
                  modifier = Modifier.heightIn(min = 48.dp),
                )
              }

              DraftingTool.FIXTURE -> {
                Text(
                  text = "Tap canvas to place: ${state.activeSymbolType.displayName}",
                  style = MaterialTheme.typography.labelMedium,
                )
                FilterChip(
                  selected = false,
                  onClick = onOpenSymbolPicker,
                  leadingIcon = { Icon(Icons.Default.Chair, null, Modifier.size(16.dp)) },
                  label = { Text("Change Symbol") },
                  modifier = Modifier.heightIn(min = 48.dp),
                )
              }

              DraftingTool.DIMENSION -> {
                Text(
                  text = "Measure:",
                  style = MaterialTheme.typography.labelMedium,
                )
                FilterChip(
                  selected = false,
                  onClick = onAutoExteriorDims,
                  leadingIcon = { Icon(Icons.Default.Straighten, null, Modifier.size(16.dp)) },
                  label = { Text("Auto Exterior (In-to-In)") },
                  modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("auto_exterior_dims_button"),
                )
              }

              DraftingTool.ROOM_LABEL -> {
                Text(
                  text = "Tap room to center label:",
                  style = MaterialTheme.typography.labelMedium,
                )
                FilterChip(
                  selected = state.activeRoomLabelPreset == null,
                  onClick = { onSelectRoomLabelPreset(null) },
                  label = { Text("Ask Name") },
                  modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("room_label_ask_chip"),
                )
                DraftingPresets.roomLabelPresets.forEach { preset ->
                  FilterChip(
                    selected = state.activeRoomLabelPreset == preset,
                    onClick = { onSelectRoomLabelPreset(preset) },
                    label = { Text(preset) },
                    modifier = Modifier.heightIn(min = 48.dp),
                  )
                }
                FilterChip(
                  selected = state.defaultTextOpaqueBg,
                  onClick = { onSetDefaultTextOpaqueBg(!state.defaultTextOpaqueBg) },
                  label = { Text(if (state.defaultTextOpaqueBg) "Opaque BG" else "Clear BG") },
                  modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("room_label_opaque_bg_button"),
                )
              }

              DraftingTool.TEXT -> {
                Text(
                  text = "Tap canvas to add text  •  Size:",
                  style = MaterialTheme.typography.labelMedium,
                )
                DraftingPresets.textSizesMm.forEach { (sizeMm, label) ->
                  FilterChip(
                    selected = kotlin.math.abs(state.defaultTextSizeMm - sizeMm) < 10f,
                    onClick = { onSetDefaultTextSize(sizeMm) },
                    label = { Text(label) },
                    modifier = Modifier.heightIn(min = 48.dp),
                  )
                }
                FilterChip(
                  selected = state.defaultTextOpaqueBg,
                  onClick = { onSetDefaultTextOpaqueBg(!state.defaultTextOpaqueBg) },
                  label = { Text(if (state.defaultTextOpaqueBg) "Opaque BG" else "Clear BG") },
                  modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("default_text_opaque_bg_button"),
                )
              }

              else -> Unit
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ActionDuplicateDelete(
  onDuplicate: () -> Unit,
  onDelete: () -> Unit,
) {
  IconButton(
    onClick = onDuplicate,
    modifier = Modifier.testTag("duplicate_selected_button"),
  ) {
    Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate selected element")
  }
  IconButton(
    onClick = onDelete,
    modifier = Modifier.testTag("delete_selected_button"),
  ) {
    Icon(
      Icons.Default.DeleteOutline,
      contentDescription = "Delete selected element",
      tint = MaterialTheme.colorScheme.error,
    )
  }
}
